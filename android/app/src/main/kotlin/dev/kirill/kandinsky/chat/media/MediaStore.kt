package dev.kirill.kandinsky.chat.media

import android.graphics.*
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Size
import dev.kirill.kandinsky.chat.attachments.StudioPhotoEncoder
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.storage.*
import java.io.*
import java.util.UUID
import org.json.JSONObject

class MediaStore(
    val store: SQLiteStore,
    private val repository: ChatRepository,
    private val studioOriginals: Boolean = false,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tinyPreviewQueue = MediaDecodeQueue(1)
    private val mediaDecodeQueue = MediaDecodeQueue()
    private val videoPrefetchQueue = MediaDecodeQueue(1)
    private val imageMemoryBudget = ImageByteBudget()
    private val thumbnailCache =
        ThumbnailCache(
            imageMemoryBudget,
            if (studioOriginals) 48 else 16,
            (if (studioOriginals) 16 else 8) * 1024 * 1024,
        )
    var remotePlayback: ((Attachment, MediaCancellation) -> MediaPlaybackSource)? = null
    var remoteFile: ((Attachment, Boolean, MediaCancellation) -> Pair<File, String>)? = null
    var pendingReferences: ((android.database.sqlite.SQLiteDatabase) -> Set<String>)? = null
    private var closed = false
    private var recovering = false
    private var navigating = false
    private val invalidationHandlers = mutableMapOf<UUID, () -> Unit>()
    private var previewThread: HandlerThread? = null

    internal fun previewWorker(): Handler {
        checkMainThread()
        check(!closed)
        val thread =
            previewThread
                ?: HandlerThread("Kandinsky-muted-video")
                    .apply { start() }
                    .also { previewThread = it }
        return Handler(thread.looper)
    }

    var maximumThumbnailPixel = 1024
        private set

    val peakBytesUsed
        get() = imageMemoryBudget.peakBytes

    val bytesUsed
        get() = imageMemoryBudget.bytes

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper())
    }

    fun fileURL(relative: String): File = resolve(store.root, relative)

    // Проверяем наличие файла на телефоне без загрузки и декодирования.
    fun hasLocalOriginal(attachment: Attachment): Boolean =
        runCatching { fileURL(attachment.relativePath).isFile }.getOrDefault(false)

    fun stage(
        open: () -> InputStream,
        mime: String,
        callback: (Result<AttachmentDraft>) -> Unit,
    ): Cancellation = stage(open, mime, "objects", callback)

    private fun stage(
        open: () -> InputStream,
        mime: String,
        directory: String,
        callback: (Result<AttachmentDraft>) -> Unit,
        prepareStudioPhoto: Boolean = false,
    ): Cancellation {
        checkMainThread()
        if (closed || recovering) {
            mainHandler.post { callback(Result.failure(MediaException(MediaError.CANCELLED))) }
            return Cancellation {}
        }
        val root = store.root
        val faults = store.faults
        return mediaDecodeQueue.submit(
            work = { copyAndProbe(open, root, mime, faults, directory, prepareStudioPhoto) },
            dispose = { draft -> runCatching { resolve(root, draft.relativePath).delete() } },
        ) { result ->
            callback(result.fold({ Result.success(it) }, { Result.failure(map(it)) }))
        }
    }

    fun stageSelection(
        open: () -> InputStream,
        mime: String,
        callback: (Result<AttachmentDraft>) -> Unit,
    ): Cancellation = stage(open, mime, "selections", callback)

    fun stageStudioPhoto(
        open: () -> InputStream,
        callback: (Result<AttachmentDraft>) -> Unit,
    ): Cancellation = stage(open, "image/jpeg", "selections", callback, prepareStudioPhoto = true)

    fun commit(
        drafts: List<AttachmentDraft>,
        command: SendCommand,
        callback: (Result<MessageDelta>) -> Unit,
    ): Cancellation {
        checkMainThread()
        if (closed || recovering) {
            mainHandler.post { callback(Result.failure(MediaException(MediaError.CANCELLED))) }
            return Cancellation {}
        }
        return repository.send(command.copy(attachments = drafts)) {
            callback(it.fold({ delta -> Result.success(delta) }, { error -> Result.failure(map(error)) }))
        }
    }

    fun discardUnpublishedImport(
        draft: AttachmentDraft,
        callback: (Result<Unit>) -> Unit = {},
    ): Cancellation = discardUnpublishedImports(listOf(draft), callback)

    fun discardUnpublishedImports(
        drafts: List<AttachmentDraft>,
        callback: (Result<Unit>) -> Unit = {},
    ): Cancellation {
        checkMainThread()
        if (
            drafts.any { draft ->
                val parts = draft.relativePath.split('/')
                parts.size != 2 ||
                    parts[0] !in listOf("objects", "selections") ||
                    runCatching { UUID.fromString(draft.id) }.isFailure ||
                    File(draft.relativePath).nameWithoutExtension != draft.id
            }
        ) {
            mainHandler.post { callback(Result.failure(MediaException(MediaError.CORRUPT))) }
            return Cancellation {}
        }
        val root = store.root
        val pending = pendingReferences
        return store.submit({ db ->
            val referencedPaths = readReferencedPaths(db, pending)
            val files =
                drafts
                    .filter { it.relativePath !in referencedPaths }
                    .map { draft ->
                        val file =
                            try {
                                resolve(root, draft.relativePath)
                            } catch (_: MediaException) {
                                throw RepoException(RepoError.CORRUPT)
                            }
                        val expected =
                            File(root.canonicalFile, "media/" + draft.relativePath).absoluteFile
                        if (file != expected) throw RepoException(RepoError.CORRUPT)
                        file
                    }
            for (file in files) if (file.exists() && !file.delete())
                throw RepoException(RepoError.IO)
            Unit
        }) { result ->
            callback(result.fold({ Result.success(Unit) }, { Result.failure(map(it)) }))
        }
    }

    fun recover(callback: (Result<Unit>) -> Unit): Cancellation {
        checkMainThread()
        if (
            closed ||
                recovering ||
                mediaDecodeQueue.runningCount != 0 ||
                mediaDecodeQueue.pendingCount != 0
        ) {
            mainHandler.post { callback(Result.failure(MediaException(MediaError.UNAVAILABLE))) }
            return Cancellation {}
        }
        recovering = true
        val token = MediaCancellation()
        val pending = pendingReferences
        val referenceQuery =
            store.submit({ db -> readReferencedPaths(db, pending) }) { result ->
                result.fold(
                    { referencedPaths ->
                        val cleanup =
                            mediaDecodeQueue.submit(
                                work = { deleteUnreferencedFiles(store.root, referencedPaths) },
                                dispose = {},
                            ) { cleanupResult ->
                                recovering = false
                                token.finish()
                                callback(
                                    cleanupResult.fold({ Result.success(Unit) }, { Result.failure(map(it)) })
                                )
                            }
                        token.onCancel { cleanup.cancel() }
                    },
                    {
                        recovering = false
                        token.finish()
                        callback(Result.failure(map(it)))
                    },
                )
            }
        token.onCancel { referenceQuery.cancel() }
        return token
    }

    fun isStudioVideo(attachment: Attachment) =
        studioOriginals && attachment.mime.startsWith("video/")

    // Возвращает отдельный признак оригинала, чтобы этот рисунок не смешивался с обычной
    // уменьшенной копией.
    fun studioPhotoKey(attachment: Attachment, maxPixel: Int? = null): ThumbnailKey? {
        if (
            !studioOriginals ||
                !attachment.relativePath.startsWith("remote/") ||
                attachment.mime !in STUDIO_PHOTO_MIMES
        )
            return null
        val edge = maxOf(attachment.rawWidth, attachment.rawHeight)
        val limit =
            minOf(
                if (maximumThumbnailPixel < 1024) maximumThumbnailPixel else 1280,
                maxPixel ?: 1280,
            )
        return ThumbnailKey(if (edge > 0) minOf(edge, limit) else limit, studioOriginal = true)
    }

    // Не начинает загрузку и декодирование, поэтому ячейка может сразу показать известный кадр при
    // привязке.
    fun cachedThumbnail(attachment: Attachment, key: ThumbnailKey): PreparedImage? {
        checkMainThread()
        if (closed || recovering) return null
        val original = key.studioOriginal || key.maxPixel > 1024
        val size =
            minOf(
                if (original) if (maximumThumbnailPixel < 1024) 1024 else 2048
                else maximumThumbnailPixel,
                key.maxPixel,
            )
        if (size !in 1..2048) return null
        return thumbnailCache.frame(
            attachment.relativePath +
                (if (key.studioOriginal) "@studio:" else if (original) "@original:" else "@") +
                size
        )
    }

    fun thumbnail(
        attachment: Attachment,
        key: ThumbnailKey,
        ticket: Int,
        localOnly: Boolean = false,
        progress: (Double) -> Unit = {},
        completion: (Int, Result<PreparedImage>) -> Unit,
    ): Cancellation {
        checkMainThread()
        val token = MediaCancellation()
        val original = key.studioOriginal || key.maxPixel > 1024
        val size =
            minOf(
                if (original) if (maximumThumbnailPixel < 1024) 1024 else 2048
                else maximumThumbnailPixel,
                key.maxPixel,
            )
        val file = runCatching { fileURL(attachment.relativePath) }.getOrNull()
        if (closed || recovering || size !in 1..2048 || file == null) {
            mainHandler.post {
                token.finish()
                completion(ticket, Result.failure(MediaException(MediaError.CANCELLED)))
            }
            return token
        }
        val cacheKey =
            attachment.relativePath +
                (if (key.studioOriginal) "@studio:" else if (original) "@original:" else "@") +
                size
        val cached = thumbnailCache.frame(cacheKey)
        if (cached != null) {
            token.onCancel { cached.close() }
            mainHandler.post {
                if (token.finish()) completion(ticket, Result.success(cached))
                else {
                    cached.close()
                    completion(ticket, Result.failure(MediaException(MediaError.CANCELLED)))
                }
            }
            return token
        }
        val edge = maxOf(attachment.rawWidth, attachment.rawHeight)
        val shortEdge = minOf(attachment.rawWidth, attachment.rawHeight)
        val height =
            if (key.studioOriginal && edge > 0 && shortEdge > 0)
                maxOf(
                    minOf(size, 512),
                    kotlin.math.ceil(size.toDouble() * shortEdge / edge).toInt(),
                )
            else size
        val reserve = size * height * 8 + 4096
        val charge =
            if (thumbnailCache.makeRoom(reserve)) imageMemoryBudget.reserve(reserve) else null
        if (charge == null) {
            mainHandler.post {
                token.finish()
                completion(ticket, Result.failure(MediaException(MediaError.UNAVAILABLE)))
            }
            return token
        }
        val remote = remoteFile
        val fetch =
            MediaCancellation().also {
                it.onProgress { value -> mainHandler.post { progress(value) } }
            }
        val request =
            mediaDecodeQueue.submit(
                priority = if (localOnly) 0 else 1,
                work = {
                    try {
                        val resolved =
                            if (localOnly) {
                                if (!file.isFile) throw MediaException(MediaError.UNAVAILABLE)
                                file to attachment.mime
                            } else
                                remote?.invoke(attachment, !original, fetch)
                                    ?: (file to attachment.mime)
                        if (fetch.isCancelled) throw MediaException(MediaError.CANCELLED)
                        val video = resolved.second.startsWith("video/")
                        val normal =
                            if (video)
                                MediaPreviewGenerator.normalFile(
                                    store.root,
                                    attachment.relativePath,
                                    resolved.first,
                                    resolved.second,
                                )
                            else resolved.first
                        val image =
                            decode(normal, if (video) "image/jpeg" else resolved.second, size)
                        charge.shrink(image.allocationByteCount)
                        ImagePixels(image, charge)
                    } catch (t: Throwable) {
                        charge.close()
                        throw map(t)
                    }
                },
                dispose = { it.closeOwner() },
            ) { result ->
                if (closed) {
                    result.getOrNull()?.closeOwner()
                    charge.close()
                    completion(ticket, Result.failure(MediaException(MediaError.CANCELLED)))
                } else
                    result.fold(
                        { pixels ->
                            if (!token.finish()) {
                                pixels.closeOwner()
                                completion(
                                    ticket,
                                    Result.failure(MediaException(MediaError.CANCELLED)),
                                )
                            } else {
                                thumbnailCache.insert(pixels, cacheKey)
                                val frame = thumbnailCache.frame(cacheKey)
                                if (frame != null) completion(ticket, Result.success(frame))
                                else
                                    completion(
                                        ticket,
                                        Result.failure(MediaException(MediaError.CANCELLED)),
                                    )
                            }
                        },
                        {
                            charge.close()
                            token.finish()
                            completion(ticket, Result.failure(map(it)))
                        },
                    )
            }
        token.onCancel {
            fetch.cancel()
            request.cancel()
        }
        return token
    }

    // Не ждёт загрузки оригинала, поэтому даёт первый рисунок до появления более подробного
    // изображения.
    fun tinyPreview(
        attachment: Attachment,
        completion: (Result<PreparedImage>) -> Unit,
    ): Cancellation {
        val text =
            dev.kirill.kandinsky.chat.storage.MediaPreviewStorage.tiny(attachment.tinyPreview)
        val reserve = 64 * 64 * 8 + 4096
        val charge =
            if (!closed && text != null && thumbnailCache.makeRoom(reserve))
                imageMemoryBudget.reserve(reserve)
            else null
        if (charge == null) {
            mainHandler.post { completion(Result.failure(MediaException(MediaError.UNAVAILABLE))) }
            return Cancellation {}
        }
        return tinyPreviewQueue.submit(
            work = {
                try {
                    val data = android.util.Base64.decode(text, android.util.Base64.NO_WRAP)
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(data, 0, data.size, options)
                    require(options.outWidth in 1..64 && options.outHeight in 1..64)
                    val bitmap =
                        BitmapFactory.decodeByteArray(data, 0, data.size)
                            ?: throw MediaException(MediaError.CORRUPT)
                    charge.shrink(bitmap.allocationByteCount)
                    ImagePixels(bitmap, charge)
                } catch (t: Throwable) {
                    charge.close()
                    throw t
                }
            },
            dispose = { it.closeOwner() },
        ) { result ->
            result.fold(
                { pixels ->
                    val frame = pixels.frame()
                    pixels.closeOwner()
                    if (frame != null) completion(Result.success(frame))
                    else completion(Result.failure(MediaException(MediaError.CANCELLED)))
                },
                {
                    charge.close()
                    completion(Result.failure(it))
                },
            )
        }
    }

    fun playbackSource(
        attachment: Attachment,
        callback: (Result<MediaPlaybackSource>) -> Unit,
    ): Cancellation {
        fileURL(attachment.relativePath)
        val remote =
            remotePlayback
                ?: return ensureOriginal(attachment) {
                    callback(it.map { file -> MediaPlaybackSource(file = file) })
                }
        val token = MediaCancellation()
        val request =
            mediaDecodeQueue.submit(
                work = { remote(attachment, token) },
                dispose = { it.close() },
                completion = callback,
            )
        return Cancellation {
            token.cancel()
            request.cancel()
        }
    }

    fun prepareStudioVideo(attachment: Attachment): Cancellation? {
        checkMainThread()
        if (
            !studioOriginals ||
                closed ||
                recovering ||
                !attachment.relativePath.startsWith("remote/") ||
                attachment.mime != "video/mp4" ||
                attachment.byteSize !in 1L..(15L * 1024 * 1024)
        )
            return null
        val resolve = remoteFile ?: return null
        val transfer = MediaCancellation()
        val fetch = MediaCancellation()
        val work =
            videoPrefetchQueue.submit(
                work = {
                    resolve(attachment, false, fetch)
                    Unit
                },
                dispose = {},
                completion = { transfer.finish() },
            )
        transfer.onCancel {
            fetch.cancel()
            work.cancel()
        }
        return transfer
    }

    // Отмена прекращает ожидающее чтение, а готовые изображения соседних сообщений не очищаются
    // из-за
    // завершения этого запроса.
    fun ensureOriginal(attachment: Attachment, callback: (Result<File>) -> Unit): Cancellation =
        ensureOriginal(attachment, {}, callback)

    // Отмена прекращает ожидающее чтение, а готовые изображения соседних сообщений не очищаются
    // из-за
    // завершения этого запроса.
    fun ensureOriginal(
        attachment: Attachment,
        progress: (Double) -> Unit,
        callback: (Result<File>) -> Unit,
    ): Cancellation {
        val remote = remoteFile
        val token = MediaCancellation().also { it.onProgress(progress) }
        // Оригинал и предварительный рисунок имеют разные постоянные обозначения готовых пикселей
        val request =
            mediaDecodeQueue.submit(
                work = {
                    remote?.invoke(attachment, false, token)?.first
                        ?: fileURL(attachment.relativePath)
                },
                dispose = {},
            ) {
                callback(it)
            }
        return Cancellation {
            token.cancel()
            request.cancel()
        }
    }

    fun <T> withNavigationCache(action: () -> T): T {
        checkMainThread()
        val previous = navigating
        navigating = true
        try {
            return action()
        } finally {
            navigating = previous
            thumbnailCache.retainNavigationImages()
        }
    }

    fun releaseIdleImages() {
        checkMainThread()
        if (navigating) thumbnailCache.retainNavigationImages() else thumbnailCache.clear()
    }

    fun registerInvalidation(action: () -> Unit): UUID {
        checkMainThread()
        val id = UUID.randomUUID()
        invalidationHandlers[id] = action
        return id
    }

    fun unregisterInvalidation(id: UUID) {
        checkMainThread()
        invalidationHandlers.remove(id)
    }

    fun trimMemory() {
        checkMainThread()
        maximumThumbnailPixel = 512
        invalidationHandlers.values.toList().forEach { it() }
        thumbnailCache.clear()
        imageMemoryBudget.limit = 24 * 1024 * 1024
    }

    fun close() {
        checkMainThread()
        if (closed) return
        closed = true
        invalidationHandlers.values.toList().forEach { it() }
        invalidationHandlers.clear()
        mediaDecodeQueue.close()
        tinyPreviewQueue.close()
        videoPrefetchQueue.close()
        thumbnailCache.clear()
        previewThread?.quitSafely()
        previewThread = null
    }

    companion object {
        private val STUDIO_PHOTO_MIMES = setOf("image/png", "image/jpeg", "image/webp")

        private fun deleteUnreferencedFiles(root: File, referencedPaths: Set<String>) {
            for (directoryName in listOf("staging", "objects", "selections")) {
                val directory = ownedDirectory(root, directoryName)
                for (file in directory.listFiles() ?: emptyArray()) {
                    if (!file.isFile) continue
                    val canDelete =
                        directoryName == "staging" ||
                            "$directoryName/" + file.name !in referencedPaths
                    if (canDelete && !file.delete()) throw MediaException(MediaError.IO)
                }
            }
        }

        // Файл может ещё не попасть в сообщение, но уже быть нужен черновику или очереди отправки.
        private fun readReferencedPaths(
            db: android.database.sqlite.SQLiteDatabase,
            pending: ((android.database.sqlite.SQLiteDatabase) -> Set<String>)?,
        ): Set<String> {
            val paths = MediaSelectionStorage.references(db).toMutableSet()
            pending?.let { paths.addAll(it(db)) }
            db.rawQuery("SELECT relativePath FROM attachments", null).use { cursor ->
                while (cursor.moveToNext()) paths += cursor.getString(0)
            }
            db.rawQuery(
                    "SELECT attachmentDraft FROM drafts WHERE attachmentDraft IS NOT NULL",
                    null,
                )
                .use { cursor ->
                    while (cursor.moveToNext()) {
                        val draft = JSONObject(cursor.getString(0))
                        fun collectAttachmentPaths(attachments: org.json.JSONArray) {
                            for (index in 0 until attachments.length()) {
                                paths += attachments.getJSONObject(index).getString("relativePath")
                            }
                        }
                        if (draft.has("attachmentDrafts") && !draft.isNull("attachmentDrafts"))
                            collectAttachmentPaths(draft.getJSONArray("attachmentDrafts"))
                        if (draft.has("pendingSend") && !draft.isNull("pendingSend"))
                            collectAttachmentPaths(
                                draft.getJSONObject("pendingSend").getJSONArray("attachments")
                            )
                        if (draft.has("pendingMediaBatch") && !draft.isNull("pendingMediaBatch")) {
                            val batch = draft.getJSONArray("pendingMediaBatch")
                            for (index in 0 until batch.length()) {
                                collectAttachmentPaths(batch.getJSONObject(index).getJSONArray("attachments"))
                            }
                        }
                    }
                }
            return paths
        }

        fun map(t: Throwable): Throwable {
            if (t is MediaException) return t
            if (t is RepoException)
                return MediaException(
                    when (t.reason) {
                        RepoError.STORAGE_FULL -> MediaError.STORAGE_FULL
                        RepoError.CANCELLED -> MediaError.CANCELLED
                        RepoError.CORRUPT -> MediaError.CORRUPT
                        else -> MediaError.IO
                    }
                )
            if (t is ImageDecoder.DecodeException) return MediaException(MediaError.CORRUPT)
            if (t is SecurityException) return MediaException(MediaError.PERMISSION_DENIED)
            var cause: Throwable? = t
            repeat(8) {
                val currentCause = cause
                if (currentCause is ErrnoException)
                    return MediaException(
                        when (currentCause.errno) {
                            OsConstants.ENOSPC -> MediaError.STORAGE_FULL
                            OsConstants.EACCES,
                            OsConstants.EPERM -> MediaError.PERMISSION_DENIED
                            else -> MediaError.IO
                        }
                    )
                cause = currentCause?.cause
            }
            return MediaException(
                if (t is OutOfMemoryError) MediaError.UNAVAILABLE else MediaError.IO
            )
        }

        private fun ownedDirectory(root: File, name: String): File {
            val base = File(root.canonicalFile, "media")
            val dir = File(base, name)
            if (base.canonicalFile != base || dir.canonicalFile != dir)
                throw MediaException(MediaError.CORRUPT)
            if (!dir.isDirectory && !dir.mkdirs()) throw MediaException(MediaError.IO)
            return dir
        }

        private fun resolve(root: File, relative: String): File {
            if (
                relative.startsWith("/") ||
                    relative.split('/').any { it.isEmpty() || it == "." || it == ".." }
            )
                throw MediaException(MediaError.CORRUPT)
            val base = File(root.canonicalFile, "media")
            if (base.canonicalFile != base) throw MediaException(MediaError.CORRUPT)
            val file = File(base, relative).canonicalFile
            if (!file.path.startsWith(base.path + "/")) throw MediaException(MediaError.CORRUPT)
            return file
        }

        private fun syncDirectory(directory: File) {
            val directoryDescriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(directoryDescriptor)
            } finally {
                Os.close(directoryDescriptor)
            }
        }

        private fun copyAndProbe(
            open: () -> InputStream,
            root: File,
            mime: String,
            faults: RepositoryFaults,
            directory: String,
            prepareStudioPhoto: Boolean,
        ): AttachmentDraft {
            val fileExtension =
                mapOf(
                    "image/jpeg" to "jpg",
                    "image/png" to "png",
                    "image/heic" to "heic",
                    "image/heif" to "heif",
                    "image/gif" to "gif",
                    "video/mp4" to "mp4",
                    "video/quicktime" to "mov",
                    "audio/mp4" to "m4a",
                )[mime] ?: throw MediaException(MediaError.UNAVAILABLE)
            val stagingDirectory = ownedDirectory(root, "staging")
            val destinationDirectory = ownedDirectory(root, directory)
            val id = UUID.randomUUID().toString()
            val temporaryFile = File(stagingDirectory, id + ".part")
            val destinationFile = File(destinationDirectory, "$id.$fileExtension")
            val preparedPhoto = if (prepareStudioPhoto) File(stagingDirectory, "$id.studio.jpg") else null
            if (destinationFile.exists() || !temporaryFile.createNewFile()) throw MediaException(MediaError.IO)
            try {
                var byteCount = 0L
                open().use { input ->
                    FileOutputStream(temporaryFile).use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val bytesRead = input.read(buffer)
                            if (bytesRead < 0) break
                            if (bytesRead == 0) continue
                            byteCount += bytesRead
                            if (byteCount > 64 * 1024 * 1024)
                                throw MediaException(MediaError.UNAVAILABLE)
                            if (
                                dev.kirill.kandinsky.chat.BuildConfig.DEBUG &&
                                    faults.consume(RepoFault.STORAGE_FULL)
                            ) {
                                output.write(buffer, 0, minOf(16, bytesRead))
                                throw java.io.IOException(
                                    "Injected bounded staging write",
                                    ErrnoException("write", OsConstants.ENOSPC),
                                )
                            }
                            output.write(buffer, 0, bytesRead)
                        }
                        output.fd.sync()
                    }
                }
                if (preparedPhoto != null) {
                    val image =
                        ImageDecoder.decodeBitmap(ImageDecoder.createSource(temporaryFile)) {
                            decoder,
                            info,
                            _ ->
                            val width = info.size.width
                            val height = info.size.height
                            if (
                                width !in 1..32768 ||
                                    height !in 1..32768 ||
                                    width.toLong() * height > 64_000_000
                            )
                                throw MediaException(MediaError.UNAVAILABLE)
                            val scale = minOf(1.0, 2048.0 / maxOf(width, height))
                            decoder.setTargetSize(
                                maxOf(1, (width * scale).toInt()),
                                maxOf(1, (height * scale).toInt()),
                            )
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                            decoder.setOnPartialImageListener { false }
                        }
                    try {
                        StudioPhotoEncoder().jpeg(image, preparedPhoto)
                    } finally {
                        image.recycle()
                    }
                    byteCount = preparedPhoto.length()
                }
                val fileToPublish = preparedPhoto ?: temporaryFile
                val draft = probe(fileToPublish, mime, id, directory + "/" + destinationFile.name, byteCount)
                if (faults.consume(RepoFault.BEFORE_RENAME)) throw MediaException(MediaError.IO)
                Os.rename(fileToPublish.path, destinationFile.path)
                syncDirectory(destinationDirectory)
                if (faults.consume(RepoFault.AFTER_RENAME)) throw MediaException(MediaError.IO)
                var draftWithPreview = draft.copy(thumbnailKey = id)
                if (mime.startsWith("image/") || mime.startsWith("video/")) {
                    val tiny =
                        runCatching {
                                MediaPreviewGenerator.tiny(
                                    MediaPreviewGenerator.normalFile(
                                        root,
                                        draft.relativePath,
                                        destinationFile,
                                        mime,
                                    )
                                )
                            }
                            .getOrNull()
                    draftWithPreview = draftWithPreview.copy(tinyPreview = tiny)
                }
                return draftWithPreview
            } finally {
                temporaryFile.delete()
                preparedPhoto?.delete()
            }
        }

        private fun probe(
            file: File,
            mime: String,
            id: String,
            relative: String,
            bytes: Long,
        ): AttachmentDraft {
            if (mime.startsWith("image/")) {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, options)
                val pixelWidth = options.outWidth
                val pixelHeight = options.outHeight
                if (pixelWidth <= 0 || pixelHeight <= 0) throw MediaException(MediaError.CORRUPT)
                if (
                    pixelWidth > 32768 ||
                        pixelHeight > 32768 ||
                        pixelWidth.toLong() * pixelHeight > 64_000_000 ||
                        (mime == "image/png" && pixelWidth.toLong() * pixelHeight > 16_000_000)
                )
                    throw MediaException(MediaError.UNAVAILABLE)
                val rawOrientation =
                    runCatching {
                            ExifInterface(file.path)
                                .getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
                        }
                        .getOrDefault(1)
                val orientation = if (rawOrientation == 0) 1 else rawOrientation
                if (orientation !in 1..8) throw MediaException(MediaError.CORRUPT)
                return AttachmentDraft(
                    id,
                    relative,
                    mime,
                    bytes,
                    pixelWidth,
                    pixelHeight,
                    orientation,
                )
            }
            val retriever = MediaMetadataRetriever()
            try {
                FileInputStream(file).use { retriever.setDataSource(it.fd) }
                val duration =
                    retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0
                if (duration <= 0 || duration > 86400000) throw MediaException(MediaError.CORRUPT)
                if (mime.startsWith("video/")) {
                    val pixelWidth =
                        retriever
                            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                            ?.toIntOrNull() ?: 0
                    val pixelHeight =
                        retriever
                            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                            ?.toIntOrNull() ?: 0
                    if (pixelWidth <= 0 || pixelHeight <= 0)
                        throw MediaException(MediaError.CORRUPT)
                    val rotation =
                        retriever
                            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                            ?.toIntOrNull() ?: 0
                    val orientation =
                        when (rotation) {
                            90 -> 6
                            180 -> 3
                            270 -> 8
                            else -> 1
                        }
                    return AttachmentDraft(
                        id,
                        relative,
                        mime,
                        bytes,
                        pixelWidth,
                        pixelHeight,
                        orientation,
                        duration,
                    )
                }
                if (
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) !=
                        "yes"
                )
                    throw MediaException(MediaError.CORRUPT)
                return AttachmentDraft(id, relative, mime, bytes, durationMs = duration)
            } finally {
                retriever.release()
            }
        }

        private fun decode(file: File, mime: String, maxPixel: Int): Bitmap {
            val result =
                if (mime.startsWith("video/"))
                    ThumbnailUtils.createVideoThumbnail(file, Size(maxPixel, maxPixel), null)
                else
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                        val scale =
                            minOf(
                                1.0,
                                maxPixel.toDouble() / maxOf(info.size.width, info.size.height),
                            )
                        decoder.setTargetSize(
                            maxOf(1, kotlin.math.floor(info.size.width * scale).toInt()),
                            maxOf(1, kotlin.math.floor(info.size.height * scale).toInt()),
                        )
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                        decoder.setOnPartialImageListener { false }
                    }
            if (maxOf(result.width, result.height) > maxPixel) {
                result.recycle()
                throw MediaException(MediaError.CORRUPT)
            }
            return result
        }
    }
}
