package dev.kirill.kandinsky.chat.network

import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.media.*
import dev.kirill.kandinsky.chat.storage.SQLiteStore
import dev.kirill.kandinsky.chat.storage.ChatStorageCodec
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal data class StudioMediaRemoval(val blob:String,val preview:String){
    val key get()=PREFIX+blob
    companion object{
        const val PREFIX="studioRemoved:"
        private val id=Regex("[a-f0-9]{32}")
        /**
         * Принимает описание удаления только при корректных тридцатидвухзначных шестнадцатеричных номерах оригинала и копии
         * Для других строк возвращает отсутствие описания, не позволяя использовать их как имена удаляемых файлов
         */
        fun parse(blob:String,preview:String)=if(id.matches(blob) && id.matches(preview))StudioMediaRemoval(blob,preview)else null
    }
}

/**
 * Получает удалённые файлы через существующую очередь двух исполнителей декодирования
 * Число одновременно работающих и ожидающих обращений ограничивает владелец этой очереди
 */
class NetworkMedia(private val store:SQLiteStore,private val api:NetworkAPI,private val cacheLimitBytes:Long=64L*1024*1024) {
    private val downloads=Any();private val pins=HashMap<String,Int>();private val pinGate=Any()
    private val revoked=linkedSetOf<String>()
    /**
     * Проверяет, не был ли удалённый файл уже отозван из доступного содержимого
     * Останавливает чтение отменой, чтобы сохранённая копия не вернула удалённый результат в историю
     */
    private fun requireAvailable(name:String){
        if(synchronized(pinGate){name.take(32) in revoked})throw MediaException(MediaError.CANCELLED)
    }
    /**
     * Запоминает отзыв результата в ограниченном наборе, чтобы новые обращения к нему прекращались отменой
     * Под общим замком загрузок удаляет только проверенные имена оригинала, производных изображений и уменьшенной копии
     * Отдельно убирает папку скачанных участков видео этого результата и сообщает отказ при незавершённом удалении
     */
    internal fun removeCached(removal:StudioMediaRemoval){
        synchronized(pinGate){
            revoked.add(removal.blob)
            if(revoked.size>512)revoked.remove(revoked.first())
        }
        synchronized(downloads){
            val media=File(store.root.canonicalFile,"media")
            /**
             * Строит путь относительно собственной папки медиа и сравнивает его обычный и приведённый вид
             * Несовпадение отклоняет до удаления, чтобы переход через символическую ссылку или части пути не подменил выбранный файл
             */
            fun owned(relative:String):File {
                val file=File(media,relative)
                check(file.canonicalFile==file.absoluteFile)
                return file
            }
            /**
             * Удаляет переданный уже проверенный путь, только если запись ещё существует
             * Неудачное удаление сообщает отказом, чтобы отметка очистки не считалась успешно обработанной
             */
            fun unlink(file:File){if(file.exists())check(file.delete())}
            val originalNames=owned("remote").list()?.filter{it==removal.blob || it.startsWith(removal.blob+".")} ?: emptyList()
            val candidates=originalNames+removal.blob+listOf("jpg","png","gif","heic","heif","mp4","mov","webp","bin").map{removal.blob+"."+it}
            for(name in candidates.toSet()){
                val relative="remote/$name"
                unlink(owned(relative));unlink(owned("previews/"+MediaPreviewGenerator.derivedName(relative)))
            }
            unlink(owned("previews/"+removal.preview));unlink(owned("previews/"+removal.preview+".jpg"))
            val ranges=owned("ranges/"+removal.blob)
            if(ranges.exists())check(ranges.deleteRecursively())
        }
    }
    /**
     * Сначала возвращает уже готовый оригинал без ожидания чужой загрузки и при необходимости переносит прежний файл к имени с расширением
     * При отсутствии оригинала получает связь вложения с серверным файлом из базы с ограниченным ожиданием
     * Выбирает оригинал или допустимую уменьшенную копию, повторно проверяет готовность под замком загрузки и передаёт недостающий файл
     * Учитывает отмену и отзыв, после записи запускает очистку лишних сохранённых копий
     */
    fun resolve(attachment:Attachment,preview:Boolean,token:MediaCancellation):Pair<File,String>{
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())
        val name=attachment.relativePath.removePrefix("remote/");requireAvailable(name)
        var original=File(store.root,"media/"+attachment.relativePath)
        if(attachment.relativePath.startsWith("remote/") && original.extension.isEmpty()){
            val typed=File(original.path+"."+extension(attachment.mime))
            synchronized(downloads){if(original.isFile && !typed.exists() && !original.renameTo(typed))throw MediaException(MediaError.IO)}
            original=typed
        }
        // Готовый местный файл не должен ждать замка посторонней загрузки
        synchronized(pinGate){requireAvailable(name);if(original.isFile){original.setLastModified(System.currentTimeMillis());return original to attachment.mime}}
        if(!attachment.relativePath.startsWith("remote/"))throw MediaException(MediaError.IO)
        val done=CountDownLatch(1);var mapping:Result<Triple<String,String?,Long>>?=null
        store.submit({db->db.rawQuery("SELECT blobID,previewBlobID,byteSize FROM net_media WHERE attachmentID=?",arrayOf(attachment.id)).use{
            if(!it.moveToFirst())throw RepoException(RepoError.NOT_FOUND)
            Triple(it.getString(0),if(it.isNull(1))null else it.getString(1),it.getLong(2))
        }}){mapping=it;done.countDown()}
        if(!done.await(10,TimeUnit.SECONDS) || token.isCancelled)throw MediaException(MediaError.CANCELLED)
        val (blob,small,bytes)=checkNotNull(mapping).getOrThrow()
        if(preview && small==null && bytes>256*1024)throw MediaException(MediaError.UNAVAILABLE)
        val usePreview=preview && small!=null
        val target=if(usePreview)File(store.root,"media/previews/$small")else original
        synchronized(downloads){
            requireAvailable(name)
            if(token.isCancelled)throw MediaException(MediaError.CANCELLED)
            if(original.isFile){original.setLastModified(System.currentTimeMillis());return original to attachment.mime}
            api.download(if(usePreview)small!! else blob,target,if(usePreview)256*1024L else bytes,token)
            prune(target)
        }
        return target to if(usePreview)"image/jpeg" else attachment.mime
    }
    /**
     * Создаёт источник воспроизведения из готового файла, если оригинал уже сохранён
     * В остальных случаях связывает системное чтение видео с хранилищем отдельных участков
     * Удерживает используемый файл от вытеснения до закрытия источника и проверяет отзыв перед получением новых байтов
     */
    fun playback(attachment:Attachment,token:MediaCancellation):MediaPlaybackSource {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())
        requireAvailable(attachment.relativePath.removePrefix("remote/"))
        var file=File(store.root,"media/"+attachment.relativePath)
        if(attachment.relativePath.startsWith("remote/") && file.extension.isEmpty()){val typed=File(file.path+"."+extension(attachment.mime));synchronized(downloads){if(file.isFile && !typed.exists())check(file.renameTo(typed))};file=typed}
        val release=pin(file);var transferred=false
        try{
        if(file.isFile){transferred=true;return MediaPlaybackSource(file=file,onClose=release)}
        require(attachment.relativePath.startsWith("remote/"));val done=CountDownLatch(1);var mapping:Result<Pair<String,Long>>?=null
        store.submit({db->db.rawQuery("SELECT blobID,byteSize FROM net_media WHERE attachmentID=?",arrayOf(attachment.id)).use{require(it.moveToFirst());it.getString(0) to it.getLong(1)}}){mapping=it;done.countDown()}
        if(!done.await(10,TimeUnit.SECONDS) || token.isCancelled)throw MediaException(MediaError.CANCELLED)
        val (blob,size)=checkNotNull(mapping).getOrThrow()
        val source=MediaPlaybackSource(ranges=MediaRangeCache(store.root,blob,size,file){start,end,request->
            requireAvailable(blob)
            api.range(blob,start,end,size,request).also{requireAvailable(blob)}
        },onClose=release);transferred=true;return source
        }finally{if(!transferred)release()}
    }
    /**
     * Увеличивает число использований файла, чтобы очистка диска не удалила его во время чтения
     * Возвращает действие освобождения, которое уменьшит счётчик и уберёт запись после последнего использования
     */
    private fun pin(file:File):()->Unit {
        val path=file.absolutePath;synchronized(pinGate){pins[path]=(pins[path] ?:0)+1}
        return {synchronized(pinGate){val count=(pins[path] ?:1)-1;if(count==0)pins.remove(path)else pins[path]=count};Unit}
    }
    /**
     * Передаёт завершённую запись проверке допустимого объёма файлов на диске
     * Удаляет старые копии только после проверки, что их сейчас не удерживает чтение или проигрыватель
     */
    private fun prune(keep:File){

        val files=listOf("remote","previews").flatMap{File(store.root,"media/$it").listFiles()?.filter{f->f.isFile && !f.name.endsWith(".part")} ?: emptyList()}
        var bytes=files.sumOf{it.length()}
        if(bytes<=cacheLimitBytes)return
        for(file in files.sortedBy{it.lastModified()})if(bytes>cacheLimitBytes && file!=keep){synchronized(pinGate){if(pins[file.absolutePath]==null){val size=file.length();if(file.delete())bytes-=size}}}
    }
    companion object {
        /**
         * Подбирает известное расширение для объявленного вида изображения, видео или звукового файла
         * Для неизвестного вида возвращает общее служебное расширение, сохраняя предсказуемое имя местной копии
         */
        fun extension(mime:String)=mapOf("image/jpeg" to "jpg","image/png" to "png","image/gif" to "gif","image/heic" to "heic","image/heif" to "heif","video/mp4" to "mp4","video/quicktime" to "mov","audio/mp4" to "m4a")[mime] ?: "bin"
        // Использует постоянный номер содержимого и расширение его вида, чтобы повторные обращения находили ту же местную копию
        fun relativePath(blob:String,mime:String)="remote/"+blob+"."+extension(mime)
        /**
         * Читает все сохранённые намерения отправки из сетевой исходящей очереди
         * Возвращает разные пути их вложений, чтобы восстановление и очистка учитывали ещё нужные местные исходники
         */
        fun pendingReferences(database:android.database.sqlite.SQLiteDatabase):Set<String>{
            val paths=mutableSetOf<String>()
            database.rawQuery("SELECT payload FROM net_outbox WHERE action='send'",null).use{while(it.moveToNext())for(a in ChatStorageCodec.readCommand(it.getString(0)).attachments)paths.add(a.relativePath)}
            return paths
        }
    }
}
