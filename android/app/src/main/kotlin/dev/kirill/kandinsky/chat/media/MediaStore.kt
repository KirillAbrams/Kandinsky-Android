// Хранит выбранные файлы и готовые изображения отдельно от сетевого чтения. Проверяет путь,
// размер и владельцев, прежде чем показать или убрать вложение.

package dev.kirill.kandinsky.chat.media

import android.graphics.*
import androidx.exifinterface.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.storage.*
import java.io.*
import java.util.UUID
import org.json.JSONObject

class MediaStore(
    val store: SQLiteStore,
    private val studioOriginals: Boolean = false,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tinyPreviewQueue = MediaDecodeQueue(1)
    private val mediaDecodeQueue = MediaDecodeQueue()
    private val videoPreparation = MediaVideoPreparation()
    private val imageMemoryBudget = ImageByteBudget()
    private val galleryDisk = VideoCoverDiskCache(store.root)
    private val seenGalleryPaths = linkedSetOf<String>()
    internal val videoPlayback = VideoPlaybackCache(imageMemoryBudget, store.root)
    private val tinyPreviewCache = ThumbnailCache(imageMemoryBudget, entryLimit = 64)
    private val imageWorkQueue = MediaImageWorkQueue()
    private val thumbnailRequests = MediaThumbnailRequests()
        .also { it.onRemoteActivity = videoPreparation::setPaused }
    private val thumbnailCache =
        ThumbnailCache(
            imageMemoryBudget,
            if (studioOriginals) 48 else 16,
            (if (studioOriginals) 32 else 8) * 1024 * 1024,
        )
    var remotePlayback: ((Attachment, MediaCancellation) -> MediaPlaybackSource)? = null
    var remoteFile: ((Attachment, Boolean, MediaCancellation) -> Pair<File, String>)? = null
    var remoteCached: ((Attachment, Boolean, MediaCancellation) -> Pair<File, String>?)? = null
    var pendingReferences: ((android.database.sqlite.SQLiteDatabase) -> Set<String>)? = null
    private var closed = false
    private var recovering = false
    private var navigating = false
    private val invalidationHandlers = mutableMapOf<UUID, () -> Unit>()
    private var previewThread: HandlerThread? = null

    // Создаёт один рабочий поток для тихих роликов по первому запросу. Следующие просмотры
    // используют его же и не добавляют отдельный поток каждому видео.
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
        // Возвращает наибольший расход памяти за время жизни хранилища. По нему можно проверить,
        // соблюдался ли предел при одновременной подготовке.
        get() = imageMemoryBudget.peakBytes

    val bytesUsed
        // Возвращает байты готовых изображений и временных резервов сейчас. После ухода последних
        // читателей счёт должен уменьшиться.
        get() = imageMemoryBudget.bytes

    // Проверяет поток экрана для операций с ключами и подписками. Их состояние не меняется из
    // фонового чтения одновременно.
    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper())
    }

    // Возвращает проверенный путь к вложению внутри папки текущего владельца. Исключает ошибочный
    // выход за её границы.
    fun fileURL(relative: String): File = resolve(store.root, relative)

    // Проверяет, лежит ли готовый оригинал на телефоне, без сети и разбора изображения. Неудачная
    // проверка пути означает, что местной копии нет.
    fun hasLocalOriginal(attachment: Attachment): Boolean =
        runCatching { fileURL(attachment.relativePath).isFile }.getOrDefault(false)

    // Готовит присланные байты для постоянной папки вложений. Черновик получает путь только после
    // общей проверки файла.
    fun stage(
        open: () -> InputStream,
        mime: String,
        callback: (Result<AttachmentDraft>) -> Unit,
    ): Cancellation = stage(open, mime, "objects", callback)

    // Передаёт чтение фоновой очереди, если хранилище открыто и не чистится. При отмене убирает
    // слишком поздно созданную копию.
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

    // Сохраняет выбор пользователя отдельно от файлов истории. После отказа от выбора такую копию
    // можно убрать.
    fun stageSelection(
        open: () -> InputStream,
        mime: String,
        callback: (Result<AttachmentDraft>) -> Unit,
    ): Cancellation = stage(open, mime, "selections", callback)

    // Выбирает подготовку фотографии допустимого размера перед помещением в папку исходников.
    // Генератор получит готовую копию для передачи.
    fun stageStudioPhoto(
        open: () -> InputStream,
        callback: (Result<AttachmentDraft>) -> Unit,
    ): Cancellation = stage(open, "image/jpeg", "selections", callback, prepareStudioPhoto = true)

    // Передаёт одну неиспользованную копию общей уборке. Ссылки из других черновиков всё ещё
    // учитываются.
    fun discardUnpublishedImport(
        draft: AttachmentDraft,
        callback: (Result<Unit>) -> Unit = {},
    ): Cancellation = discardUnpublishedImports(listOf(draft), callback)

    // Проверяет имена и принадлежность файлов, читает живые ссылки и удаляет только
    // неиспользуемые. Иначе отмена импорта могла бы оборвать ожидающую отправку.
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

    // Не начинает уборку при открытом импорте, сначала читает ссылки, затем очищает файлы
    // отдельно. Пока идёт восстановление, новые операции не должны добавить файл мимо списка.
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

    // Проверяет, подходит ли вложение к режиму подготовки видео генерации. Обычные сообщения не
    // используют это правило автоматически.
    fun isStudioVideo(attachment: Attachment) =
        studioOriginals && attachment.mime.startsWith("video/")

    // Ищет отметку прежнего показа по пути. При возврате экран может избежать повторной размытой
    // картинки.
    internal fun wasShownInGallery(attachment: Attachment): Boolean =
        attachment.relativePath in seenGalleryPaths

    // Запоминает показанный путь как самый свежий и ограничивает список недавних показов. Старые
    // просмотры не занимают память бесконечно.
    internal fun markShownInGallery(attachment: Attachment) {
        val path = attachment.relativePath
        seenGalleryPaths.remove(path)
        seenGalleryPaths.add(path)
        if (seenGalleryPaths.size > 512) seenGalleryPaths.remove(seenGalleryPaths.first())
    }

    // Выбирает подготовку сетевого фото из оригинала с отдельным ключом и пределом размера.
    // Обычная миниатюра не заменяет результат этого чтения.
    fun studioPhotoKey(attachment: Attachment, maxPixel: Int? = null): ThumbnailKey? {
        if (
            !studioOriginals ||
                !attachment.relativePath.startsWith("remote/") ||
                attachment.mime !in STUDIO_PHOTO_MIMES
        )
            return null
        val edge = maxOf(attachment.rawWidth, attachment.rawHeight)
        val limit = minOf(1280, maxPixel ?: 1280)
        return ThumbnailKey(if (edge > 0) minOf(edge, limit) else limit, studioOriginal = true)
    }

    // Считает имя нужного варианта и возвращает готовый рисунок из памяти. Такой поиск не
    // открывает файл или сеть.
    fun cachedThumbnail(attachment: Attachment, key: ThumbnailKey): PreparedImage? {
        checkMainThread()
        if (closed || recovering) return null
        val original = key.studioOriginal || key.maxPixel > 1024
        val size =
            minOf(
                if (key.studioOriginal) 1280
                else if (original) if (maximumThumbnailPixel < 1024) 1024 else 2048
                else maximumThumbnailPixel,
                key.maxPixel,
            )
        if (size !in 1..2048) return null
        return thumbnailCache.frame(
            attachment.relativePath + (if (key.gallery) "@gallery:" else "") +
                (if (key.studioOriginal) "@studio:" else if (original) "@original:" else "@") +
                size
        )
    }

    // Объединяет одинаковые запросы к фото генерации по размеру, виду и разрешению сети. Каждый
    // вызывающий экран получает ответ со своим номером.
    fun thumbnail(
        attachment: Attachment,
        key: ThumbnailKey,
        ticket: Int,
        localOnly: Boolean = false,
        priority: Int = 1,
        progress: (Double) -> Unit = {},
        localMiss: () -> Unit = {},
        completion: (Int, Result<PreparedImage>) -> Unit,
    ): Cancellation {
        if (!studioOriginals) return loadThumbnail(attachment, key, ticket, localOnly, priority,
            progress, { localMiss(); true }, completion)
        val identity = attachment.relativePath + "@${key.maxPixel}:${key.studioOriginal}:${key.gallery}:$localOnly"
        return thumbnailRequests.submit(identity, priority, progress,
            start = { priority, willDownload, progress, done ->
                loadThumbnail(attachment, key, ticket, localOnly, priority, progress,
                    willDownload) { _, result -> done(result) }
            },
            completion = { completion(ticket, it) },
            localMiss = localMiss,
        )
    }

    // Проверяет путь и память, затем ищет местный файл, при необходимости скачивает и готовит
    // нужный размер. Отказ и отмена возвращают память, а пригодный кадр можно использовать
    // повторно.
    private fun loadThumbnail(
        attachment: Attachment, key: ThumbnailKey, ticket: Int, localOnly: Boolean,
        priority: Int, progress: (Double) -> Unit, willDownload: () -> Boolean,
        completion: (Int, Result<PreparedImage>) -> Unit,
    ): Cancellation {
        checkMainThread()
        val token = MediaCancellation()
        val original = key.studioOriginal || key.maxPixel > 1024
        val size =
            minOf(
                if (key.studioOriginal) 1280
                else if (original) if (maximumThumbnailPixel < 1024) 1024 else 2048
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
            attachment.relativePath + (if (key.gallery) "@gallery:" else "") +
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
        val remote = remoteFile
        val cachedFile = remoteCached
        val gallery = if (key.gallery) galleryDisk else null
        val galleryEpoch = gallery?.epoch()
        val relativePath = attachment.relativePath
        val fetch =
            MediaCancellation().also {
                it.onProgress { value -> mainHandler.post { progress(value) } }
            }
        val request =
            imageWorkQueue.submit(
                priority = priority,
                fetch = fetch,
                local = {
                    val display = gallery?.galleryFile(relativePath)
                    if (display != null) display to "image/jpeg"
                    else if (cachedFile != null) cachedFile(attachment, !original, fetch)
                    else if (localOnly || remote == null || priority <= 0) {
                        if (file.isFile) file to attachment.mime else null
                    } else null
                },
                remote = {
                    if (localOnly) throw MediaException(MediaError.UNAVAILABLE)
                    remote?.invoke(attachment, !original, fetch) ?: (file to attachment.mime)
                },
                willDownload = willDownload,
                admit = {
                    if (closed || fetch.isCancelled) throw MediaException(MediaError.CANCELLED)
                    if (!thumbnailCache.makeRoom(reserve) && !tinyPreviewCache.makeRoom(reserve) &&
                        !videoPlayback.makeRoom(reserve))
                        throw MediaException(MediaError.UNAVAILABLE)
                    imageMemoryBudget.reserve(reserve) ?: throw MediaException(MediaError.UNAVAILABLE)
                },
                decode = { resolved, charge ->
                    try {
                        val video = resolved.second.startsWith("video/")
                        val normal = if (video) MediaPreviewGenerator.normalFile(
                            store.root, attachment.relativePath, resolved.first, resolved.second,
                        ) else resolved.first
                        if (fetch.isCancelled) throw MediaException(MediaError.CANCELLED)
                        val image = decodeImage(normal, size)
                        try {
                            if (gallery != null && galleryEpoch != null) {
                                if (gallery.epoch() != galleryEpoch || fetch.isCancelled)
                                    throw MediaException(MediaError.CANCELLED)
                                if (!resolved.first.name.endsWith("-gallery.jpg") &&
                                    (!image.hasAlpha() || attachment.mime.startsWith("video/") || attachment.mime == "image/jpeg")
                                ) runCatching {
                                    val bytes = ByteArrayOutputStream()
                                    if (image.compress(Bitmap.CompressFormat.JPEG, 78, bytes))
                                        gallery.storeGalleryFile(bytes.toByteArray(), relativePath, galleryEpoch)
                                }
                            }
                            charge.shrink(image.allocationByteCount)
                            ImagePixels(image, charge)
                        } catch (error: Throwable) {
                            image.recycle()
                            throw error
                        }
                    } catch (error: Throwable) { throw map(error) }
                },
            ) { result ->
                fetch.cancel()
                if (closed) {
                    result.getOrNull()?.closeOwner()
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

    // Проверяет малую картинку и использует её байты, путь и вид медиа в ключе. Видео и фото
    // могут дать разное размытие для одного входа.
    private fun tinyPreviewKey(attachment: Attachment): String? {
        val text = dev.kirill.kandinsky.chat.storage.MediaPreviewStorage.tiny(attachment.tinyPreview)
            ?: return null
        val variant = if (attachment.mime.startsWith("video/")) "@tiny-video:" else "@tiny:"
        return attachment.relativePath + variant + text
    }

    // Возвращает готовое маленькое изображение из памяти для ленты генерации. На экране оно может
    // появиться без новой очереди.
    fun cachedTinyPreview(attachment: Attachment): PreparedImage? {
        checkMainThread()
        if (closed || !studioOriginals) return null
        return tinyPreviewKey(attachment)?.let { tinyPreviewCache.frame(it) }
    }

    // Проверяет предельные размеры маленькой картинки, выделяет память и сглаживает её в фоне.
    // При отмене или ошибке освобождает рисунок и резерв.
    fun tinyPreview(
        attachment: Attachment,
        completion: (Result<PreparedImage>) -> Unit,
    ): Cancellation {
        cachedTinyPreview(attachment)?.let { frame ->
            val token = MediaCancellation()
            token.onCancel { frame.close() }
            mainHandler.post {
                if (token.finish()) completion(Result.success(frame))
                else { frame.close(); completion(Result.failure(MediaException(MediaError.CANCELLED))) }
            }
            return token
        }
        val text =
            dev.kirill.kandinsky.chat.storage.MediaPreviewStorage.tiny(attachment.tinyPreview)
        val key = tinyPreviewKey(attachment)
        val reserve = 64 * 64 * 8 + 4096
        val charge =
            if (!closed && text != null && key != null &&
                (tinyPreviewCache.makeRoom(reserve) || thumbnailCache.makeRoom(reserve) ||
                    videoPlayback.makeRoom(reserve)))
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
                    val bitmap = BitmapFactory.decodeByteArray(
                        data, 0, data.size,
                        BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                            inMutable = true
                        },
                    ) ?: throw MediaException(MediaError.CORRUPT)
                    try {
                        MediaPreviewGenerator.softenTinyPreview(bitmap, video = attachment.mime.startsWith("video/"))
                        charge.shrink(bitmap.allocationByteCount)
                        ImagePixels(bitmap, charge, isTinyPreview = true)
                    } catch (error: Throwable) {
                        bitmap.recycle()
                        throw error
                    }
                } catch (t: Throwable) {
                    charge.close()
                    throw t
                }
            },
            dispose = { it.closeOwner() },
        ) { result ->
            result.fold(
                { pixels ->
                    if (closed) {
                        pixels.closeOwner()
                        completion(Result.failure(MediaException(MediaError.CANCELLED)))
                        return@fold
                    }
                    val frame = if (studioOriginals) {
                        tinyPreviewCache.insert(pixels, checkNotNull(key))
                        tinyPreviewCache.frame(key)
                    } else {
                        pixels.frame().also { pixels.closeOwner() }
                    }
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

    // Готовит источник из сохранённого файла или сети и связывает чтение с отменой. Поздний
    // закрытый запрос освобождает источник сразу после получения.
    fun playbackSource(
        attachment: Attachment,
        localOnly: Boolean = false,
        callback: (Result<MediaPlaybackSource>) -> Unit,
    ): Cancellation {
        if (localOnly) {
            val cached = remoteCached
            val token = MediaCancellation()
            val request = mediaDecodeQueue.submit(
                work = {
                    val file = if (cached != null) cached(attachment, false, token)?.first
                    else fileURL(attachment.relativePath).takeIf { it.isFile }
                    if (token.isCancelled || file == null) {
                        token.cancel()
                        throw MediaException(MediaError.UNAVAILABLE)
                    }
                    MediaPlaybackSource(file = file, onClose = token::cancel)
                },
                dispose = { it.close() },
                completion = callback,
            )
            return Cancellation { token.cancel(); request.cancel() }
        }
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

    // Допускает раннее чтение только для подходящего сетевого видео нужного размера. После
    // подготовки закрывает источник, а отмена прекращает чтение раньше.
    fun prepareStudioVideo(attachment: Attachment): Cancellation? {
        checkMainThread()
        if (
            !studioOriginals ||
                closed ||
                recovering ||
                !attachment.relativePath.startsWith("remote/") ||
                attachment.mime != "video/mp4" ||
                attachment.byteSize !in 1L..(64L * 1024 * 1024)
        )
            return null
        val playback = remotePlayback ?: return null
        return videoPreparation.acquire(attachment.relativePath) { fetch ->
            playback(attachment, fetch).use { source ->
                fetch.onCancel { source.close() }
                if (fetch.isCancelled) throw MediaException(MediaError.CANCELLED)
                source.prepareForPlayback(fetch)
            }
        }
    }

    // Просит полный исходник без сообщения о промежуточном ходе. Подходит для вызова, которому
    // нужен только готовый файл.
    fun ensureOriginal(attachment: Attachment, callback: (Result<File>) -> Unit): Cancellation =
        ensureOriginal(attachment, {}, callback)

    // Запрашивает полный файл с отдельным счётчиком хода и отменой. Эта передача не должна
    // очищать уже готовые картинки соседних строк.
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

    // На время перехода включает удержание полезных кадров для возврата и восстанавливает прежний
    // режим после выхода. Большие кадры всё равно уступают место.
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

    // Оставляет подборку для возврата во время перехода или освобождает все три хранилища
    // изображений. Места просмотра видео могут жить отдельно от пикселей.
    fun releaseIdleImages() {
        checkMainThread()
        if (navigating) thumbnailCache.retainNavigationImages()
        else {
            thumbnailCache.clear()
            tinyPreviewCache.clear()
            videoPlayback.clearFrames()
        }
    }

    // Даёт экрану отдельный номер действия при очистке. Владелец сможет убрать эту подписку перед
    // своим закрытием.
    fun registerInvalidation(action: () -> Unit): UUID {
        checkMainThread()
        val id = UUID.randomUUID()
        invalidationHandlers[id] = action
        return id
    }

    // Снимает указанную подписку в потоке экрана. Закрытая поверхность больше не получает просьбу
    // об очистке.
    fun unregisterInvalidation(id: UUID) {
        checkMainThread()
        invalidationHandlers.remove(id)
    }

    // Убирает ранние работы, сообщает владельцам об очистке и уменьшает будущий размер кадров и
    // общий предел памяти. Это помогает пережить предупреждение системы.
    fun trimMemory() {
        videoPreparation.clear()
        checkMainThread()
        maximumThumbnailPixel = 512
        invalidationHandlers.values.toList().forEach { it() }
        thumbnailCache.clear()
        tinyPreviewCache.clear()
        videoPlayback.clearFrames()
        imageMemoryBudget.limit = 24 * 1024 * 1024
    }

    // Снимает подписки и закрывает очереди, сохранённые кадры, диск и поток тихого видео. Новая
    // операция после этого не должна держать прежние файлы.
    fun close() {
        checkMainThread()
        if (closed) return
        closed = true
        videoPlayback.close()
        galleryDisk.close()
        seenGalleryPaths.clear()
        invalidationHandlers.values.toList().forEach { it() }
        invalidationHandlers.clear()
        mediaDecodeQueue.close()
        imageWorkQueue.close()
        thumbnailRequests.clear()
        tinyPreviewQueue.close()
        videoPreparation.close()
        thumbnailCache.clear()
        tinyPreviewCache.clear()
        previewThread?.quitSafely()
        previewThread = null
    }

    companion object {
        private val STUDIO_PHOTO_MIMES = setOf("image/png", "image/jpeg", "image/webp")

        // Убирает временные файлы и готовые копии без ссылок из проверенных папок. Сначала
        // отзывает производные кадры, иначе удалённый исходник остался бы виден.
        private fun deleteUnreferencedFiles(root: File, referencedPaths: Set<String>) {
            for (directoryName in listOf("staging", "objects", "selections")) {
                val directory = ownedDirectory(root, directoryName)
                for (file in directory.listFiles() ?: emptyArray()) {
                    if (!file.isFile) continue
                    val relative = "$directoryName/" + file.name
                    if (directoryName != "staging" && relative in referencedPaths) continue
                    if (directoryName != "staging") VideoCoverDiskCache.revoke(root, relative)
                    if (!file.delete()) throw MediaException(MediaError.IO)
                }
            }
        }

        // Собирает пути из истории, выбранных исходников и ожидающих отправок, в том числе из
        // старых черновиков. Очистка учитывает их все.
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
                        // Добавляет пути переданных вложений к общему списку. Одна отправка может
                        // содержать несколько файлов, которые ещё нельзя удалять.
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

        // Сводит ошибки базы, разбора и системы к понятным причинам. Различает повреждение,
        // запрет доступа и нехватку диска или памяти.
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

        // Создаёт каталог только внутри проверенного места для медиа. Ссылка на другую папку
        // вызывает отказ до записи.
        private fun ownedDirectory(root: File, name: String): File {
            val base = File(root.canonicalFile, "media")
            val dir = File(base, name)
            if (base.canonicalFile != base || dir.canonicalFile != dir)
                throw MediaException(MediaError.CORRUPT)
            if (!dir.isDirectory && !dir.mkdirs()) throw MediaException(MediaError.IO)
            return dir
        }

        // Отклоняет пустые и недопустимые части пути, затем проверяет итоговое расположение. Файл
        // за пределами папки владельца не читается этим путём.
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

        // Подтверждает перенос файла записью изменений каталога и закрывает системный указатель.
        // Иначе сбой питания мог бы оставить байты без нужного имени.
        private fun syncDirectory(directory: File) {
            val directoryDescriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(directoryDescriptor)
            } finally {
                Os.close(directoryDescriptor)
            }
        }

        // Копирует байты ограниченными порциями, при необходимости уменьшает фото, проверяет файл
        // и публикует через перенос. Временные файлы удаляются при любом исходе.
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

        // Проверяет реальный размер, ориентацию фото или дорожки и длительность ролика. Неверное
        // содержимое не проходит только по названию файла.
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

        // Читает фото или подготовленный кадр в пределах нужной стороны. Если готовое
        // изображение больше допустимого, освобождает его и возвращает ошибку.
        private fun decodeImage(file: File, maxPixel: Int): Bitmap {
            val result =
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
