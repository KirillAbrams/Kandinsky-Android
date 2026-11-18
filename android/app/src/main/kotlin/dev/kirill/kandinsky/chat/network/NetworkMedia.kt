package dev.kirill.kandinsky.chat.network

import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.media.*
import dev.kirill.kandinsky.chat.storage.ChatStorageCodec
import dev.kirill.kandinsky.chat.storage.SQLiteStore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal data class StudioMediaRemoval(val blob: String, val preview: String) {
    val key
        get() = PREFIX + blob

    companion object {
        const val PREFIX = "studioRemoved:"
        private val id = Regex("[a-f0-9]{32}")

        fun parse(blob: String, preview: String) =
            if (id.matches(blob) && id.matches(preview)) StudioMediaRemoval(blob, preview) else null
    }
}

class NetworkMedia(
    private val store: SQLiteStore,
    private val api: NetworkAPI,
    private val cacheLimitBytes: Long = 64L * 1024 * 1024,
) {
    private val downloads = Any()
    private val pins = HashMap<String, Int>()
    private val pinGate = Any()
    private val revoked = linkedSetOf<String>()

    // Останавливает чтение отменой, чтобы сохранённая копия не вернула удалённый результат в
    // историю.
    private fun requireAvailable(name: String) {
        if (synchronized(pinGate) { name.take(32) in revoked })
            throw MediaException(MediaError.CANCELLED)
    }

    // Запоминает отзыв результата в ограниченном наборе, чтобы новые обращения к нему прекращались
    // отменой.
    internal fun removeCached(removal: StudioMediaRemoval) {
        synchronized(pinGate) {
            revoked.add(removal.blob)
            if (revoked.size > 512) revoked.remove(revoked.first())
        }
        synchronized(downloads) {
            val media = File(store.root.canonicalFile, "media")
            // Проверяем путь до удаления файла, чтобы ссылка не вывела нас за пределы папки
            // приложения.
            fun owned(relative: String): File {
                val file = File(media, relative)
                check(file.canonicalFile == file.absoluteFile)
                return file
            }
            // Неудачное удаление сообщает отказом, чтобы отметка очистки не считалась успешно
            // обработанной.
            fun unlink(file: File) {
                if (file.exists()) check(file.delete())
            }
            val originalNames =
                owned("remote").list()?.filter {
                    it == removal.blob || it.startsWith(removal.blob + ".")
                } ?: emptyList()
            val candidates =
                originalNames +
                    removal.blob +
                    listOf("jpg", "png", "gif", "heic", "heif", "mp4", "mov", "webp", "bin").map {
                        removal.blob + "." + it
                    }
            for (name in candidates.toSet()) {
                val relative = "remote/$name"
                unlink(owned(relative))
                unlink(owned("previews/" + MediaPreviewGenerator.derivedName(relative)))
            }
            unlink(owned("previews/" + removal.preview))
            unlink(owned("previews/" + removal.preview + ".jpg"))
            val ranges = owned("ranges/" + removal.blob)
            if (ranges.exists()) check(ranges.deleteRecursively())
        }
    }

    fun resolve(
        attachment: Attachment,
        preview: Boolean,
        token: MediaCancellation,
    ): Pair<File, String> {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
        val name = attachment.relativePath.removePrefix("remote/")
        requireAvailable(name)
        var original = File(store.root, "media/" + attachment.relativePath)
        if (attachment.relativePath.startsWith("remote/") && original.extension.isEmpty()) {
            val typed = File(original.path + "." + extension(attachment.mime))
            synchronized(downloads) {
                if (original.isFile && !typed.exists() && !original.renameTo(typed))
                    throw MediaException(MediaError.IO)
            }
            original = typed
        }
        // Готовый местный файл не должен ждать замка посторонней загрузки
        synchronized(pinGate) {
            requireAvailable(name)
            if (original.isFile) {
                original.setLastModified(System.currentTimeMillis())
                return original to attachment.mime
            }
        }
        if (!attachment.relativePath.startsWith("remote/")) throw MediaException(MediaError.IO)
        val done = CountDownLatch(1)
        var mapping: Result<Triple<String, String?, Long>>? = null
        store.submit({ db ->
            db.rawQuery(
                    "SELECT blobID,previewBlobID,byteSize FROM net_media WHERE attachmentID=?",
                    arrayOf(attachment.id),
                )
                .use {
                    if (!it.moveToFirst()) throw RepoException(RepoError.NOT_FOUND)
                    Triple(
                        it.getString(0),
                        if (it.isNull(1)) null else it.getString(1),
                        it.getLong(2),
                    )
                }
        }) {
            mapping = it
            done.countDown()
        }
        if (!done.await(10, TimeUnit.SECONDS) || token.isCancelled)
            throw MediaException(MediaError.CANCELLED)
        val (blob, small, bytes) = checkNotNull(mapping).getOrThrow()
        if (preview && small == null && bytes > 256 * 1024)
            throw MediaException(MediaError.UNAVAILABLE)
        val usePreview = preview && small != null
        val target = if (usePreview) File(store.root, "media/previews/$small") else original
        synchronized(downloads) {
            requireAvailable(name)
            if (token.isCancelled) throw MediaException(MediaError.CANCELLED)
            if (original.isFile) {
                original.setLastModified(System.currentTimeMillis())
                return original to attachment.mime
            }
            api.download(
                if (usePreview) small!! else blob,
                target,
                if (usePreview) 256 * 1024L else bytes,
                token,
            )
            prune(target)
        }
        return target to if (usePreview) "image/jpeg" else attachment.mime
    }

    fun playback(attachment: Attachment, token: MediaCancellation): MediaPlaybackSource {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
        requireAvailable(attachment.relativePath.removePrefix("remote/"))
        var file = File(store.root, "media/" + attachment.relativePath)
        if (attachment.relativePath.startsWith("remote/") && file.extension.isEmpty()) {
            val typed = File(file.path + "." + extension(attachment.mime))
            synchronized(downloads) {
                if (file.isFile && !typed.exists()) check(file.renameTo(typed))
            }
            file = typed
        }
        val release = pin(file)
        var transferred = false
        try {
            if (file.isFile) {
                transferred = true
                return MediaPlaybackSource(file = file, onClose = release)
            }
            require(attachment.relativePath.startsWith("remote/"))
            val done = CountDownLatch(1)
            var mapping: Result<Pair<String, Long>>? = null
            store.submit({ db ->
                db.rawQuery(
                        "SELECT blobID,byteSize FROM net_media WHERE attachmentID=?",
                        arrayOf(attachment.id),
                    )
                    .use {
                        require(it.moveToFirst())
                        it.getString(0) to it.getLong(1)
                    }
            }) {
                mapping = it
                done.countDown()
            }
            if (!done.await(10, TimeUnit.SECONDS) || token.isCancelled)
                throw MediaException(MediaError.CANCELLED)
            val (blob, size) = checkNotNull(mapping).getOrThrow()
            val source =
                MediaPlaybackSource(
                    ranges =
                        MediaRangeCache(store.root, blob, size, file) { start, end, request ->
                            requireAvailable(blob)
                            api.range(blob, start, end, size, request).also {
                                requireAvailable(blob)
                            }
                        },
                    onClose = release,
                )
            transferred = true
            return source
        } finally {
            if (!transferred) release()
        }
    }

    // Пока файл читает плеер, очистка кеша должна его пропускать.
    private fun pin(file: File): () -> Unit {
        val path = file.absolutePath
        synchronized(pinGate) { pins[path] = (pins[path] ?: 0) + 1 }
        return {
            synchronized(pinGate) {
                val count = (pins[path] ?: 1) - 1
                if (count == 0) pins.remove(path) else pins[path] = count
            }
            Unit
        }
    }

    private fun prune(keep: File) {

        val files =
            listOf("remote", "previews").flatMap {
                File(store.root, "media/$it").listFiles()?.filter { f ->
                    f.isFile && !f.name.endsWith(".part")
                } ?: emptyList()
            }
        var bytes = files.sumOf { it.length() }
        if (bytes <= cacheLimitBytes) return
        for (file in files.sortedBy { it.lastModified() }) if (
            bytes > cacheLimitBytes && file != keep
        ) {
            synchronized(pinGate) {
                if (pins[file.absolutePath] == null) {
                    val size = file.length()
                    if (file.delete()) bytes -= size
                }
            }
        }
    }

    companion object {
        fun extension(mime: String) =
            mapOf(
                "image/jpeg" to "jpg",
                "image/png" to "png",
                "image/gif" to "gif",
                "image/heic" to "heic",
                "image/heif" to "heif",
                "video/mp4" to "mp4",
                "video/quicktime" to "mov",
                "audio/mp4" to "m4a",
            )[mime] ?: "bin"

        // Использует постоянный номер содержимого и расширение его вида, чтобы повторные обращения
        // находили ту же местную копию
        fun relativePath(blob: String, mime: String) = "remote/" + blob + "." + extension(mime)

        // Возвращает разные пути их вложений, чтобы восстановление и очистка учитывали ещё нужные
        // местные
        // исходники.
        fun pendingReferences(database: android.database.sqlite.SQLiteDatabase): Set<String> {
            val paths = mutableSetOf<String>()
            StudioDraftStorage.load(database)?.draft?.let { draft ->
                paths.addAll(draft.attachmentDrafts.map { it.relativePath })
                draft.pendingSend?.let { command ->
                    paths.addAll(command.attachments.map { it.relativePath })
                }
                draft.pendingMediaBatch.orEmpty().forEach { command ->
                    paths.addAll(command.attachments.map { it.relativePath })
                }
            }
            database.rawQuery("SELECT payload FROM net_outbox WHERE action='send'", null).use {
                while (it.moveToNext()) for (a in
                    ChatStorageCodec.readCommand(it.getString(0)).attachments) paths.add(
                    a.relativePath
                )
            }
            return paths
        }
    }
}
