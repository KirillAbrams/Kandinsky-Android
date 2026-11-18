package dev.kirill.kandinsky.chat.media

import dev.kirill.kandinsky.chat.attachments.StudioPhotoEncoder
import android.graphics.*
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Handler
import android.os.Looper
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Size
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.storage.*
import org.json.JSONObject
import java.io.*
import java.util.UUID
class MediaStore(val store:SQLiteStore,private val repository:ChatRepository,private val studioOriginals:Boolean=false) {
 private val main=Handler(Looper.getMainLooper());private val tinyQueue=MediaDecodeQueue(1);private val queue=MediaDecodeQueue();private val videoPrefetch=MediaDecodeQueue(1)
 private val budget=ImageByteBudget();private val cache=ThumbnailCache(budget,if(studioOriginals)48 else 16,(if(studioOriginals)16 else 8)*1024*1024)
 var remotePlayback:((Attachment,MediaCancellation)->MediaPlaybackSource)?=null
 var remoteFile:((Attachment,Boolean,MediaCancellation)->Pair<File,String>)?=null
 var pendingReferences:((android.database.sqlite.SQLiteDatabase)->Set<String>)?=null
 private var closed=false;private var recovering=false
 private var navigating=false
 private val invalidators=mutableMapOf<UUID,()->Unit>()
 private var previewThread:android.os.HandlerThread?=null
 /**
  * Создаёт сам поток только при первом обращении и не допускает его получение после закрытия
  * Вызывается из потока экрана, а дальнейшие вызовы проигрывателя выполняются на возвращённом обработчике
  */
 internal fun previewWorker():Handler{
  checkMain();check(!closed)
  val thread=previewThread ?: android.os.HandlerThread("Kandinsky-muted-video").apply{start()}.also{previewThread=it}
  return Handler(thread.looper)
 }
 var maximumThumbnailPixel=1024;private set
 val peakBytesUsed get()=budget.peakBytes
 val bytesUsed get()=budget.bytes
 /**
  * Проверяет, что управление владельцами медиа происходит в потоке экрана
  * При обращении из другого потока немедленно сообщает об ошибке
  * Самостоятельно переносить действие между потоками не пытается
  */
 private fun checkMain(){check(Looper.myLooper()==Looper.getMainLooper())}
 /**
  * Передаёт относительный путь проверке собственного каталога текущей базы
  * Возвращает допустимое расположение медиа без самостоятельной загрузки файла
  * Наличие оригинала проверяется отдельно
  */
 fun fileURL(relative:String):File=resolve(store.root,relative)
 /**
  * При ошибке определения пути возвращает отсутствие файла
  * Не начинает загрузку и не подготавливает изображение, поэтому подходит для быстрого выбора способа показа
  */
 fun hasLocalOriginal(attachment:Attachment):Boolean=runCatching{fileURL(attachment.relativePath).isFile}.getOrDefault(false)
 /**
  * Передаёт чтение и определение свойств общему ограниченному способу импорта
  * Исходный выбранный пользователем файл не перемещает из его прежнего места
  */
 fun stage(open:()->InputStream,mime:String,callback:(Result<AttachmentDraft>)->Unit):Cancellation=stage(open,mime,"objects",callback)
 /**
  * Запускает местное копирование и чтение свойств в ограниченной очереди медиа
  * При отдельном признаке предварительно приводит фотографию к допустимым для модели размеру и ориентации
  * Не принимает работу закрытого или восстанавливаемого хранилища и удаляет результат, который отменён до передачи владельцу
  */
 private fun stage(open:()->InputStream,mime:String,directory:String,callback:(Result<AttachmentDraft>)->Unit,prepareStudioPhoto:Boolean=false):Cancellation {
  checkMain();if(closed || recovering){main.post{callback(Result.failure(MediaException(MediaError.CANCELLED)))};return Cancellation{}}
  val root=store.root;val faults=store.faults
  return queue.submit(work={copyAndProbe(open,root,mime,faults,directory,prepareStudioPhoto)},dispose={draft->runCatching{resolve(root,draft.relativePath).delete()}}){result->callback(result.fold({Result.success(it)},{Result.failure(map(it))}))}
 }
 /**
  * Подготавливает выбранный файл в отдельном каталоге неподтверждённых исходников
  * Полученный черновик вложения затем может быть включён в зафиксированную отправку
  * Само сообщение и операцию отправки здесь ещё не создаёт
  */
 fun stageSelection(open:()->InputStream,mime:String,callback:(Result<AttachmentDraft>)->Unit):Cancellation=stage(open,mime,"selections",callback)
 /**
  * Создаёт пригодную для модели копию выбранной фотографии в каталоге исходников
  * Просит общий импорт исправить ориентацию и ограничить размеры перед чтением свойств
  * Возвращает черновик вложения, сохраняя пользовательский оригинал отдельно
  */
 fun stageStudioPhoto(open:()->InputStream,callback:(Result<AttachmentDraft>)->Unit):Cancellation =
  stage(open,"image/jpeg","selections",callback,prepareStudioPhoto=true)
 /**
  * Подставляет подготовленные вложения в переданную команду и сохраняет её через хранилище сообщений
  * Не принимает действие во время закрытия или восстановления медиа
  * Возвращает изменение истории либо общую медийную причину ошибки сохранения
  */
 fun commit(drafts:List<AttachmentDraft>,command:SendCommand,callback:(Result<MessageDelta>)->Unit):Cancellation {
  checkMain();if(closed || recovering){main.post{callback(Result.failure(MediaException(MediaError.CANCELLED)))};return Cancellation{}}
  return repository.send(command.copy(attachments=drafts)){callback(it.fold({v->Result.success(v)},{e->Result.failure(map(e))}))}
 }
 /**
  * Передаёт один отменённый исходник общей проверке неподтверждённых импортов
  * Файл может быть удалён только при отсутствии ссылок из базы и ожидающих операций
  * Сам факт отмены вызывающей стороны не отменяет владение файлом со стороны сообщения или черновика
  */
 fun discardUnpublishedImport(draft:AttachmentDraft,callback:(Result<Unit>)->Unit={}):Cancellation=discardUnpublishedImports(listOf(draft),callback)
 /**
  * Проверяет, что переданы новые принадлежащие приложению исходники с подходящими обозначениями и путями
  * В очереди базы собирает ссылки из сообщений, черновиков и незавершённых операций
  * Удаляет только файлы без таких владельцев и повторно проверяет итоговое расположение перед очисткой
  * Недопустимое описание или ошибка файловой операции завершают запрос отказом
  */
 fun discardUnpublishedImports(drafts:List<AttachmentDraft>,callback:(Result<Unit>)->Unit={}):Cancellation{
  checkMain()
  if(drafts.any{draft->val parts=draft.relativePath.split('/');parts.size!=2 || parts[0] !in listOf("objects","selections") || runCatching{UUID.fromString(draft.id)}.isFailure || File(draft.relativePath).nameWithoutExtension!=draft.id}){
   main.post{callback(Result.failure(MediaException(MediaError.CORRUPT)))};return Cancellation{}
  }
  val root=store.root;val pending=pendingReferences
  return store.submit({db->
   val references=references(db,pending)
   val files=drafts.filter{it.relativePath !in references}.map{draft->
    val file=try{resolve(root,draft.relativePath)}catch(_:MediaException){throw RepoException(RepoError.CORRUPT)}
    val expected=File(root.canonicalFile,"media/"+draft.relativePath).absoluteFile
    if(file!=expected)throw RepoException(RepoError.CORRUPT);file
   }
   for(file in files)if(file.exists() && !file.delete())throw RepoException(RepoError.IO)
   Unit
  }){result->callback(result.fold({Result.success(Unit)},{Result.failure(map(it))}))}
 }
 /**
  * Восстанавливает согласованность местных исходников перед началом обычной медийной работы
  * Требует пустую очередь подготовки, затем получает все действующие ссылки из базы
  * Удаляет временные остатки и неподтверждённые файлы без владельцев, сохраняя исходники ожидающих отправок
  * На время проверки запрещает пересекающийся импорт и после завершения снимает состояние восстановления
  */
 fun recover(callback:(Result<Unit>)->Unit):Cancellation {
  checkMain()
  if(closed || recovering || queue.runningCount!=0 || queue.pendingCount!=0){main.post{callback(Result.failure(MediaException(MediaError.UNAVAILABLE)))};return Cancellation{}}
  recovering=true
  val token=MediaCancellation()
  val pending=pendingReferences
  val request=store.submit({db->references(db,pending)}){result->result.fold({references->
   val work=queue.submit(work={
    for(name in listOf("staging","objects","selections")){
     val dir=ownedDirectory(store.root,name)
     for(file in dir.listFiles() ?: emptyArray())if(file.isFile && (name=="staging" || "$name/"+file.name !in references)){if(!file.delete())throw MediaException(MediaError.IO)}
    }
   },dispose={}){done->recovering=false;token.finish();callback(done.fold({Result.success(Unit)},{Result.failure(map(it))}))}
   token.onCancel{work.cancel()}
  },{recovering=false;token.finish();callback(Result.failure(map(it)))})}
  token.onCancel{request.cancel()}
  return token
 }
 /**
  * Используется для выбора беззвучного оформления и воспроизведения результата
  * Готовность загрузки или содержимое самого файла здесь не проверяется
  */
 fun isStudioVideo(attachment:Attachment)=
  studioOriginals && attachment.mime.startsWith("video/")
 /**
  * Выбирает параметры чёткого показа удалённой фотографии в режиме результатов генерации
  * Учитывает известную длинную сторону, запрос ячейки и уменьшенный предел после нехватки памяти
  * Возвращает отдельный признак оригинала, чтобы этот рисунок не смешивался с обычной уменьшенной копией
  */
 fun studioPhotoKey(attachment:Attachment,maxPixel:Int?=null):ThumbnailKey? {
  if(!studioOriginals || !attachment.relativePath.startsWith("remote/") ||
     attachment.mime !in STUDIO_PHOTO_MIMES)return null
  val edge=maxOf(attachment.rawWidth,attachment.rawHeight)
  val limit=minOf(if(maximumThumbnailPixel<1024)maximumThumbnailPixel else 1280,maxPixel ?: 1280)
  return ThumbnailKey(if(edge>0)minOf(edge,limit) else limit,studioOriginal=true)
 }
 /**
  * Ищет уже готовые пиксели подходящего размера в оперативном запасе изображений
  * Не начинает загрузку и декодирование, поэтому ячейка может сразу показать известный кадр при привязке
  */
 fun cachedThumbnail(attachment:Attachment,key:ThumbnailKey):PreparedImage?{
  checkMain();if(closed || recovering)return null
  val original=key.studioOriginal || key.maxPixel>1024
  val size=minOf(if(original)if(maximumThumbnailPixel<1024)1024 else 2048 else maximumThumbnailPixel,key.maxPixel)
  if(size !in 1..2048)return null
  return cache.frame(attachment.relativePath+(if(key.studioOriginal)"@studio:" else if(original)"@original:" else "@")+size)
 }
 /**
  * Сначала ищет готовое изображение нужного размера, а при его отсутствии резервирует память для декодирования
  * Получает подходящий файл и готовит пиксели вне потока экрана через ограниченную очередь
  * После завершения сохраняет результат под его отдельным именем и передаёт удерживаемый кадр вызывающей ячейке
  * Отмена останавливает чтение и подготовку, а неподошедший результат освобождает выделенную память
  */
 fun thumbnail(attachment:Attachment,key:ThumbnailKey,ticket:Int,localOnly:Boolean=false,progress:(Double)->Unit={},completion:(Int,Result<PreparedImage>)->Unit):Cancellation {
  checkMain();val token=MediaCancellation()
  val original=key.studioOriginal || key.maxPixel>1024
  val size=minOf(if(original)if(maximumThumbnailPixel<1024)1024 else 2048 else maximumThumbnailPixel,key.maxPixel)
  val file=runCatching{fileURL(attachment.relativePath)}.getOrNull()
  if(closed || recovering || size !in 1..2048 || file==null){main.post{token.finish();completion(ticket,Result.failure(MediaException(MediaError.CANCELLED)))};return token}
  val cacheKey=attachment.relativePath+(if(key.studioOriginal)"@studio:" else if(original)"@original:" else "@")+size
  val cached=cache.frame(cacheKey)
  if(cached!=null){
   token.onCancel{cached.close()}
   main.post{if(token.finish())completion(ticket,Result.success(cached)) else {cached.close();completion(ticket,Result.failure(MediaException(MediaError.CANCELLED)))}}
   return token
  }
  val edge=maxOf(attachment.rawWidth,attachment.rawHeight)
  val shortEdge=minOf(attachment.rawWidth,attachment.rawHeight)
  val height=if(key.studioOriginal && edge>0 && shortEdge>0)
   maxOf(minOf(size,512),kotlin.math.ceil(size.toDouble()*shortEdge/edge).toInt()) else size
  val reserve=size*height*8+4096
  val charge=if(cache.makeRoom(reserve))budget.reserve(reserve) else null
  if(charge==null){main.post{token.finish();completion(ticket,Result.failure(MediaException(MediaError.UNAVAILABLE)))};return token}
  val remote=remoteFile;val fetch=MediaCancellation().also{it.onProgress{value->main.post{progress(value)}}}
  val request=queue.submit(priority=if(localOnly)0 else 1,work={
   try{
    val resolved=if(localOnly){
     if(!file.isFile)throw MediaException(MediaError.UNAVAILABLE)
     file to attachment.mime
    }else remote?.invoke(attachment,!original,fetch) ?: (file to attachment.mime)
    if(fetch.isCancelled)throw MediaException(MediaError.CANCELLED)
    val video=resolved.second.startsWith("video/")
    val normal=if(video)MediaPreviewGenerator.normalFile(store.root,attachment.relativePath,resolved.first,resolved.second) else resolved.first
    val image=decode(normal,if(video)"image/jpeg" else resolved.second,size)
    charge.shrink(image.allocationByteCount);ImagePixels(image,charge)
   }
   catch(t:Throwable){charge.close();throw map(t)}
  },dispose={it.closeOwner()}){result->
   if(closed){result.getOrNull()?.closeOwner();charge.close();completion(ticket,Result.failure(MediaException(MediaError.CANCELLED)))}
   else result.fold({pixels->
    if(!token.finish()){pixels.closeOwner();completion(ticket,Result.failure(MediaException(MediaError.CANCELLED)))}
    else{cache.insert(pixels,cacheKey);val frame=cache.frame(cacheKey);if(frame!=null)completion(ticket,Result.success(frame))else completion(ticket,Result.failure(MediaException(MediaError.CANCELLED)))}
   },{charge.close();token.finish();completion(ticket,Result.failure(map(it)))})
  };token.onCancel{fetch.cancel();request.cancel()};return token
 }

 /**
  * Готовит самый маленький кадр из данных, которые уже сохранены вместе с описанием вложения
  * Проверяет допустимость данных и заранее резервирует ограниченную память
  * Не ждёт загрузки оригинала, поэтому даёт первый рисунок до появления более подробного изображения
  */
 fun tinyPreview(attachment:Attachment,completion:(Result<PreparedImage>)->Unit):Cancellation {
  val text=dev.kirill.kandinsky.chat.storage.MediaPreviewStorage.tiny(attachment.tinyPreview);val reserve=64*64*8+4096
  val charge=if(!closed && text!=null && cache.makeRoom(reserve))budget.reserve(reserve) else null
  if(charge==null){main.post{completion(Result.failure(MediaException(MediaError.UNAVAILABLE)))};return Cancellation{}}
  return tinyQueue.submit(work={try{val data=android.util.Base64.decode(text,android.util.Base64.NO_WRAP);val options=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(data,0,data.size,options);require(options.outWidth in 1..64 && options.outHeight in 1..64);val bitmap=BitmapFactory.decodeByteArray(data,0,data.size) ?: throw MediaException(MediaError.CORRUPT);charge.shrink(bitmap.allocationByteCount);ImagePixels(bitmap,charge)}catch(t:Throwable){charge.close();throw t}},dispose={it.closeOwner()}){result->result.fold({pixels->val frame=pixels.frame();pixels.closeOwner();if(frame!=null)completion(Result.success(frame))else completion(Result.failure(MediaException(MediaError.CANCELLED)))},{charge.close();completion(Result.failure(it))})}
 }
 private data class GifInput(val file:File,val width:Int,val height:Int,val reserve:Int)
 /**
  * Получает источник данных видео через ограниченную очередь медиа
  * При подключённом сетевом источнике разрешает чтение отдельными участками, а без него использует местный оригинал
  * Передаёт отмену ожидающей работе и закрывает результат, который больше не нужен вызывающему коду
  */
 fun playbackSource(attachment:Attachment,callback:(Result<MediaPlaybackSource>)->Unit):Cancellation {
  fileURL(attachment.relativePath)
  val remote=remotePlayback ?: return ensureOriginal(attachment){callback(it.map{file->MediaPlaybackSource(file=file)})}
  val token=MediaCancellation();val request=queue.submit(work={remote(attachment,token)},dispose={it.close()},completion=callback);return Cancellation{token.cancel();request.cancel()}
 }
 /**
  * Заранее запрашивает оригинал подходящего видео генерации до его активного показа
  * Начинает работу только для допустимого удалённого файла при открытом хранилище
  * Возвращает отмену, связанную и с сетевым чтением, и с ожидающим заданием подготовки
  */
 fun prepareStudioVideo(attachment:Attachment):Cancellation? {
  checkMain()
  if(!studioOriginals || closed || recovering || !attachment.relativePath.startsWith("remote/") ||
     attachment.mime!="video/mp4" || attachment.byteSize !in 1L..(15L*1024*1024))return null
  val resolve=remoteFile ?: return null
  val transfer=MediaCancellation()
  val fetch=MediaCancellation()
  val work=videoPrefetch.submit(work={resolve(attachment,false,fetch);Unit},dispose={},completion={transfer.finish()})
  transfer.onCancel{fetch.cancel();work.cancel()}
  return transfer
 }
 /**
  * Выполняет чтение вне потока экрана и передаёт измеренный ход загрузки вызывающему коду
  * Отмена прекращает ожидающее чтение, а готовые изображения соседних сообщений не очищаются из-за завершения этого запроса
  */
 fun ensureOriginal(attachment:Attachment,callback:(Result<File>)->Unit):Cancellation=ensureOriginal(attachment,{},callback)
 /**
  * Выполняет чтение вне потока экрана и передаёт измеренный ход загрузки вызывающему коду
  * Отмена прекращает ожидающее чтение, а готовые изображения соседних сообщений не очищаются из-за завершения этого запроса
  */
 fun ensureOriginal(attachment:Attachment,progress:(Double)->Unit,callback:(Result<File>)->Unit):Cancellation {
  val remote=remoteFile;val token=MediaCancellation().also{it.onProgress(progress)}
  // Оригинал и предварительный рисунок имеют разные постоянные обозначения готовых пикселей
  val request=queue.submit(work={remote?.invoke(attachment,false,token)?.first ?: fileURL(attachment.relativePath)},dispose={}){callback(it)}
  return Cancellation{token.cancel();request.cancel()}
 }
 /**
  * Временно удерживает ограниченный запас готовых изображений на время смены чата
  * Выполняет переданное действие и восстанавливает прежний режим даже при ошибке
  * После перехода оставляет только небольшой набор кадров для быстрого возврата
  */
 fun <T> withNavigationCache(action:()->T):T{
  checkMain();val previous=navigating;navigating=true
  try{return action()}finally{navigating=previous;cache.retainNavigationImages()}
 }
 /**
  * Освобождает готовые изображения, которые больше не удерживаются видимыми элементами
  * Во время перехода между чатами оставляет ограниченный запас маленьких кадров, а в обычном режиме очищает неиспользуемый запас
  */
 fun releaseIdleImages(){checkMain();if(navigating)cache.retainNavigationImages()else cache.clear()}
  /**
   * Сохраняет действие владельца медиа, которое нужно вызвать при сбросе хранилища
   * Возвращает отдельное обозначение для удаления наблюдения
   * Используется поверхностями и проигрывателями, чтобы они вовремя завершили работу с прежними ресурсами
   */
  fun registerInvalidation(action:()->Unit):UUID{checkMain();val id=UUID.randomUUID();invalidators[id]=action;return id}
 /**
  * Снимает ранее назначенное действие сброса по его обозначению
  * Остальные владельцы медиа остаются подключёнными
  * Само закрытие удаляемого владельца этим методом повторно не запускается
  */
 fun unregisterInvalidation(id:UUID){checkMain();invalidators.remove(id)}
 /**
  * Уменьшает размер последующих изображений и допустимый бюджет их памяти
  * Просит подключённых владельцев освободиться и очищает готовый запас изображений
  * Это запрос на сокращение восстанавливаемых данных, который сохраняет постоянные файлы и историю
  */
 fun trimMemory(){checkMain();maximumThumbnailPixel=512;invalidators.values.toList().forEach{it()};cache.clear();budget.limit=24*1024*1024}
 /**
  * Закрывает приём медийной работы и уведомляет подключённых владельцев о необходимости освобождения
  * Отменяет ограниченные очереди изображений, маленьких копий и предварительной подготовки видео
  * Очищает готовые изображения и снимает связанные с этим хранилищем наблюдения
  * Повторный вызов не выполняет очистку владельцев второй раз
  */
 fun close(){checkMain();if(closed)return;closed=true;invalidators.values.toList().forEach{it()};invalidators.clear();queue.close();tinyQueue.close();videoPrefetch.close();cache.clear();previewThread?.quitSafely();previewThread=null}
 companion object {
  private val STUDIO_PHOTO_MIMES=setOf("image/png","image/jpeg","image/webp")
  /**
   * Дополняет их сохранёнными исходниками выбора и ссылками внешней очереди, если она подключена
   * Полученный набор защищает принадлежащие данным файлы от очистки после отмены или перезапуска
   */
  private fun references(db:android.database.sqlite.SQLiteDatabase,pending:((android.database.sqlite.SQLiteDatabase)->Set<String>)?):Set<String>{
   val paths=MediaSelectionStorage.references(db).toMutableSet()
   pending?.let{paths.addAll(it(db))}
   db.rawQuery("SELECT relativePath FROM attachments",null).use{c->while(c.moveToNext())paths+=c.getString(0)}
   db.rawQuery("SELECT attachmentDraft FROM drafts WHERE attachmentDraft IS NOT NULL",null).use{c->while(c.moveToNext()){
    val draft=JSONObject(c.getString(0))
    /**
     * Добавляет пути вложений из одного сохранённого списка в общий набор используемых файлов
     * Считывает каждое описание, чтобы не потерять исходники ожидающей отправки
     * Сами файлы здесь не открывает и не изменяет
     */
    fun attachments(value:org.json.JSONArray){for(i in 0 until value.length())paths+=value.getJSONObject(i).getString("relativePath")}
    if(draft.has("attachmentDrafts") && !draft.isNull("attachmentDrafts"))attachments(draft.getJSONArray("attachmentDrafts"))
    if(draft.has("pendingSend") && !draft.isNull("pendingSend"))attachments(draft.getJSONObject("pendingSend").getJSONArray("attachments"))
    if(draft.has("pendingMediaBatch") && !draft.isNull("pendingMediaBatch")){
     val batch=draft.getJSONArray("pendingMediaBatch");for(i in 0 until batch.length())attachments(batch.getJSONObject(i).getJSONArray("attachments"))
    }
   }}
   return paths
  }
  /**
   * Переводит ошибку базы, файлов или системного декодирования в общую причину медийной операции
   * Сохраняет уже известные причины и отдельно различает отмену, повреждение, отказ доступа и заполненный накопитель
   * Остальные сбои передаёт как ошибку чтения или записи
   */
  fun map(t:Throwable):Throwable {
   if(t is MediaException)return t
   if(t is RepoException)return MediaException(when(t.reason){RepoError.STORAGE_FULL->MediaError.STORAGE_FULL;RepoError.CANCELLED->MediaError.CANCELLED;RepoError.CORRUPT->MediaError.CORRUPT;else->MediaError.IO})
   if(t is ImageDecoder.DecodeException)return MediaException(MediaError.CORRUPT)
   if(t is SecurityException)return MediaException(MediaError.PERMISSION_DENIED)
   var cause:Throwable?=t;repeat(8){
    val c=cause
    if(c is ErrnoException)return MediaException(when(c.errno){OsConstants.ENOSPC->MediaError.STORAGE_FULL;OsConstants.EACCES,OsConstants.EPERM->MediaError.PERMISSION_DENIED;else->MediaError.IO})
    cause=c?.cause
   }
   return MediaException(if(t is OutOfMemoryError)MediaError.UNAVAILABLE else MediaError.IO)
  }
  /**
   * Отклоняет подменённое через ссылки расположение и создаёт отсутствующий каталог
   * Возвращает путь для временных файлов или принадлежащих приложению исходников
   */
  private fun ownedDirectory(root:File,name:String):File{
   val base=File(root.canonicalFile,"media");val dir=File(base,name)
   if(base.canonicalFile!=base || dir.canonicalFile!=dir)throw MediaException(MediaError.CORRUPT)
   if(!dir.isDirectory && !dir.mkdirs())throw MediaException(MediaError.IO)
   return dir
  }
  /**
   * Отклоняет абсолютные пути, пустые части и переходы к родительскому каталогу
   * Разрешает ссылки и проверяет, что итоговый файл остаётся внутри собственной папки медиа
   * Возвращает допустимый путь также для файла, который ещё предстоит сохранить
   */
  private fun resolve(root:File,relative:String):File{
   if(relative.startsWith("/") || relative.split('/').any{it.isEmpty() || it=="." || it==".."})throw MediaException(MediaError.CORRUPT)
   val base=File(root.canonicalFile,"media")
   if(base.canonicalFile!=base)throw MediaException(MediaError.CORRUPT)
   val file=File(base,relative).canonicalFile
   if(!file.path.startsWith(base.path+"/"))throw MediaException(MediaError.CORRUPT)
   return file
  }
  /**
   * Просит систему закрепить изменения рабочего каталога на накопителе
   * Открывает каталог только на время синхронизации и закрывает его при любом результате
   * Ошибка передаётся вызывающей файловой операции
   */
  private fun syncDirectory(dir:File){val fd=Os.open(dir.path,OsConstants.O_RDONLY,0);try{Os.fsync(fd)}finally{Os.close(fd)}}
  /**
   * Копирует допустимый исходник во временный файл с ограничением размера и закрепляет записанные байты
   * Читает действительные свойства, затем перемещает готовый файл в выбранный собственный каталог и закрепляет изменение каталога
   * Для фотографии и видео по возможности добавляет маленькое изображение, для звука подготовленную волну
   * Временные остатки очищает при завершении, а проверочные отказы позволяют проверить восстановление на границах записи
   */
  private fun copyAndProbe(open:()->InputStream,root:File,mime:String,faults:RepositoryFaults,directory:String,prepareStudioPhoto:Boolean):AttachmentDraft{
   val ext=mapOf("image/jpeg" to "jpg","image/png" to "png","image/heic" to "heic","image/heif" to "heif","image/gif" to "gif","video/mp4" to "mp4","video/quicktime" to "mov","audio/mp4" to "m4a")[mime] ?: throw MediaException(MediaError.UNAVAILABLE)
   val staging=ownedDirectory(root,"staging");val objects=ownedDirectory(root,directory);val id=UUID.randomUUID().toString()
   val temp=File(staging,id+".part");val final=File(objects,"$id.$ext")
   val prepared=if(prepareStudioPhoto)File(staging,"$id.studio.jpg") else null
   if(final.exists() || !temp.createNewFile())throw MediaException(MediaError.IO)
   try{
    var bytes=0L
    open().use{input->FileOutputStream(temp).use{output->
     val buffer=ByteArray(65536)
     while(true){val count=input.read(buffer);if(count<0)break;if(count==0)continue;bytes+=count;if(bytes>64*1024*1024)throw MediaException(MediaError.UNAVAILABLE);if(dev.kirill.kandinsky.chat.BuildConfig.DEBUG && faults.consume(RepoFault.STORAGE_FULL)){output.write(buffer,0,minOf(16,count));throw java.io.IOException("Injected bounded staging write",ErrnoException("write",OsConstants.ENOSPC))};output.write(buffer,0,count)}
     output.fd.sync()
    }}
    if(prepared!=null){
     val image=ImageDecoder.decodeBitmap(ImageDecoder.createSource(temp)){decoder,info,_->
      val width=info.size.width
      val height=info.size.height
      if(width !in 1..32768 || height !in 1..32768 || width.toLong()*height>64_000_000)
       throw MediaException(MediaError.UNAVAILABLE)
      val scale=minOf(1.0,2048.0/maxOf(width,height))
      decoder.setTargetSize(maxOf(1,(width*scale).toInt()),maxOf(1,(height*scale).toInt()))
      decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
      decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
      decoder.setOnPartialImageListener{false}
     }
     try{StudioPhotoEncoder().jpeg(image,prepared)}finally{image.recycle()}
     bytes=prepared.length()
    }
    val staged=prepared ?: temp
    val draft=probe(staged,mime,id,directory+"/"+final.name,bytes)
    if(faults.consume(RepoFault.BEFORE_RENAME))throw MediaException(MediaError.IO)
    Os.rename(staged.path,final.path);syncDirectory(objects)
    if(faults.consume(RepoFault.AFTER_RENAME))throw MediaException(MediaError.IO)
    var enriched=draft.copy(thumbnailKey=id)
    if(mime.startsWith("image/") || mime.startsWith("video/")){
     val tiny=runCatching{MediaPreviewGenerator.tiny(MediaPreviewGenerator.normalFile(root,draft.relativePath,final,mime))}.getOrNull();enriched=enriched.copy(tinyPreview=tiny)
    }
    return enriched
   }finally{temp.delete();prepared?.delete()}
  }
  /**
   * Отклоняет недопустимые размеры, продолжительность и отсутствие нужного содержимого
   * Возвращает описание черновика вложения с принадлежащим приложению путём без самостоятельной отправки
   */
  private fun probe(file:File,mime:String,id:String,relative:String,bytes:Long):AttachmentDraft{
   if(mime.startsWith("image/")){
    val options=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(file.path,options)
    val w=options.outWidth;val h=options.outHeight
    if(w<=0 || h<=0)throw MediaException(MediaError.CORRUPT)
    if(w>32768 || h>32768 || w.toLong()*h>64_000_000 || (mime=="image/png" && w.toLong()*h>16_000_000))throw MediaException(MediaError.UNAVAILABLE)
    val rawOrientation=runCatching{ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION,1)}.getOrDefault(1)
    val orientation=if(rawOrientation==0)1 else rawOrientation
    if(orientation !in 1..8)throw MediaException(MediaError.CORRUPT)
    return AttachmentDraft(id,relative,mime,bytes,w,h,orientation)
   }
   val retriever=MediaMetadataRetriever()
   try{
    FileInputStream(file).use{retriever.setDataSource(it.fd)}
    val duration=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
    if(duration<=0 || duration>86400000)throw MediaException(MediaError.CORRUPT)
    if(mime.startsWith("video/")){
     val w=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
     val h=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
     if(w<=0 || h<=0)throw MediaException(MediaError.CORRUPT)
     val rotation=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
     val orientation=when(rotation){90->6;180->3;270->8;else->1}
     return AttachmentDraft(id,relative,mime,bytes,w,h,orientation,duration)
    }
    if(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)!="yes")throw MediaException(MediaError.CORRUPT)
    return AttachmentDraft(id,relative,mime,bytes,durationMs=duration)
   }finally{retriever.release()}
  }
  /**
   * Получает изображение или кадр видео с ограниченной длинной стороной и правильной ориентацией
   * Готовит пиксели в предусмотренном цветовом представлении для обычного вывода
   * Проверяет конечные размеры и отклоняет результат за пределами заданного размера
   */
  private fun decode(file:File,mime:String,maxPixel:Int):Bitmap {
   val result=if(mime.startsWith("video/"))ThumbnailUtils.createVideoThumbnail(file,Size(maxPixel,maxPixel),null)
   else ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)){decoder,info,_->
    val scale=minOf(1.0,maxPixel.toDouble()/maxOf(info.size.width,info.size.height))
    decoder.setTargetSize(maxOf(1,kotlin.math.floor(info.size.width*scale).toInt()),maxOf(1,kotlin.math.floor(info.size.height*scale).toInt()))
    decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE;decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));decoder.setOnPartialImageListener{false}
   }
   if(maxOf(result.width,result.height)>maxPixel){result.recycle();throw MediaException(MediaError.CORRUPT)}
   return result
  }
 }
}
