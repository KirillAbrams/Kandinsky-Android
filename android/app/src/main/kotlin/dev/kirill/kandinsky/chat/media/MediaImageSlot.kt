package dev.kirill.kandinsky.chat.media

import android.graphics.*
import android.os.Handler
import android.os.Looper
import androidx.core.graphics.withTranslation
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.media.kernel.*

class MediaImageSlot(private val store: MediaStore, private val invalidate: () -> Unit) :
    AutoCloseable {
    val native = RenderNode("Kandinsky image")
    private val main = Handler(Looper.getMainLooper())
    private var request: Cancellation? = null
    private var tinyRequest: Cancellation? = null
    private var videoPreparation: Cancellation? = null
    private var playbackFrameRequest: Cancellation? = null
    private var playbackFrameKey: VideoPlaybackKey? = null
    private var playbackFrameSequence = 4L
    private var attachment: Attachment? = null
    private var pixelLimit = 1024
    private var loadAttempt = 0
    var loading = false
        private set

    private var failed = false
    private var transferStarted = false
    private var progress = 0.0
    val loadFailed
        get() = failed

    var showsControls = true
        private set

    private var showLoadingControl = true
    private var deferred = false
    private var canShowTinyPreview = true
    private var galleryPreview = false
    private var studioPhotoDelivery = false
    private var studioPhotoMaxPixel: Int? = null
    val awaitingStudioPhoto
        get() = studioPhotoDelivery && (!ready || displayed?.isTinyPreview == true) && !failed

    private val controlPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = android.content.res.Resources.getSystem().displayMetrics.density
    private var identity = ""
    private var mediaPath = ""
    private var binding: Long? = null
    private val contentBounds = RectF()
    private var offsetX = 0f
    private var offsetY = 0f
    private val screenLeft get() = contentBounds.left + offsetX
    private val screenTop get() = contentBounds.top + offsetY
    private var circular = false
    private var cornerRadius = 12f
    private var bottomSquare = false
    private var kind: MessageKind? = null
    private var displayed: PreparedImage? = null
    private var closed = false
    var ready = false
        private set

    private val mailbox =
        MediaSurfaceMailbox<PreparedImage>(schedule = { main.post(it) }, render = { publish(it) })
    private val invalidation = store.registerInvalidation { clear() }

    fun bind(
        attachment: Attachment,
        rect: RectF,
        circular: Boolean = false,
        maxPixel: Int = 1024,
        cornerRadius: Float = 12f,
        bottomSquare: Boolean = false,
        kind: MessageKind? = null,
        showControls: Boolean = true,
        studioPhotoDelivery: Boolean = false,
        studioPhotoMaxPixel: Int? = null,
        showLoadingControl: Boolean = true,
        localOnly: Boolean = false,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        playbackKey: VideoPlaybackKey? = null,
        galleryPreview: Boolean = false,
    ) {
        if (closed) return
        this.studioPhotoMaxPixel = studioPhotoMaxPixel
        val thumbnail = (store.studioPhotoKey(attachment, studioPhotoMaxPixel)
            ?: ThumbnailKey(maxPixel)).copy(gallery = galleryPreview)
        this.galleryPreview = galleryPreview
        val width = rect.width()
        val height = rect.height()
        val resized = contentBounds.width() != width || contentBounds.height() != height
        val previousLeft = screenLeft
        val previousTop = screenTop
        val changed =
            this.studioPhotoDelivery != studioPhotoDelivery ||
                showsControls != showControls ||
                this.showLoadingControl != showLoadingControl ||
                resized ||
                this.circular != circular ||
                this.cornerRadius != cornerRadius ||
                this.bottomSquare != bottomSquare ||
                this.kind != kind
        // Размер плитки берём до смещения в координаты экрана, чтобы дробный сдвиг его не менял.
        contentBounds.set(rect)
        this.offsetX = offsetX
        this.offsetY = offsetY
        this.circular = circular
        showsControls = showControls
        this.showLoadingControl = showLoadingControl
        this.studioPhotoDelivery = studioPhotoDelivery
        this.cornerRadius = cornerRadius
        this.bottomSquare = bottomSquare
        this.kind = kind
        this.attachment = attachment
        pixelLimit = thumbnail.maxPixel
        if (resized || identity.isEmpty()) {
            native.setPosition(
                0,
                0,
                kotlin.math.ceil(width.toDouble()).toInt().coerceAtLeast(1),
                kotlin.math.ceil(height.toDouble()).toInt().coerceAtLeast(1),
            )
        }
        if (screenLeft != previousLeft) native.translationX = screenLeft
        if (screenTop != previousTop) native.translationY = screenTop
        val key = attachment.relativePath + "@" + thumbnail.maxPixel +
            (if (galleryPreview) ":gallery" else "")
        if (identity == key && playbackFrameKey == playbackKey && (!deferred || localOnly)) {
            if (changed) record()
            return
        }
        val preserve = mediaPath == attachment.relativePath && playbackFrameKey == playbackKey &&
            displayed != null
        loadAttempt++
        request?.cancel()
        request = null
        tinyRequest?.cancel()
        tinyRequest = null
        playbackFrameRequest?.cancel()
        playbackFrameRequest = null
        playbackFrameKey = null
        videoPreparation?.cancel()
        videoPreparation = null
        identity = key
        loading = true
        failed = false
        deferred = false
        // При возврате к истории сначала ждём сохранённый кадр с устройства.
        canShowTinyPreview =
            kind == null || kind == MessageKind.STICKER || studioPhotoDelivery || store.remoteCached == null
        transferStarted = false
        progress = 0.0
        mediaPath = attachment.relativePath
        if (!preserve) ready = false
        val token = mailbox.begin(preserve) ?: return
        binding = token
        playbackFrameSequence = 4L
        if (playbackKey != null) {
            playbackFrameKey = playbackKey
            if (showSavedPlaybackFrame()) return
            record()
            // Заставка нужна только после проверки, что с прошлого просмотра кадра нет.
            playbackFrameRequest = store.videoPlayback.restoreCover(playbackKey) { result ->
                if (closed || binding != token || playbackFrameKey != playbackKey) {
                    result.getOrNull()?.close()
                    return@restoreCover
                }
                playbackFrameRequest = null
                val frame = result.getOrNull()
                if (frame != null) seedPlaybackFrame(frame)
                else if (!showSavedPlaybackFrame()) loadThumbnail(attachment, thumbnail, token, localOnly)
            }
            return
        }
        loadThumbnail(attachment, thumbnail, token, localOnly)
    }

    private fun loadThumbnail(
        attachment: Attachment,
        thumbnail: ThumbnailKey,
        token: Long,
        localOnly: Boolean,
    ) {
        val attempt = loadAttempt
        val cached = store.cachedThumbnail(attachment, thumbnail)
        if (cached != null) {
            request = null
            tinyRequest = null
            loading = false
            mailbox.offer(token, 3, cached)
            mailbox.flush()
            return
        }
        showTinyPreviewIfNeeded()
        record()
        request =
            store.thumbnail(
                attachment,
                thumbnail,
                0,
                localOnly = localOnly,
                progress = { value ->
                    if (loadAttempt == attempt && !closed) {
                        transferStarted = true
                        progress = value
                        record()
                        invalidate()
                    }
                },
                localMiss = {
                    if (loadAttempt == attempt && !closed) {
                        canShowTinyPreview = true
                        showTinyPreviewIfNeeded()
                    }
                },
            ) { _, result ->
                if (loadAttempt != attempt || closed) {
                    result.getOrNull()?.close()
                    return@thumbnail
                }
                request = null
                loading = false
                failed = result.isFailure && !localOnly
                deferred = result.isFailure && localOnly
                result.onSuccess { mailbox.offer(token, 3, it) }
                if (failed) {
                    canShowTinyPreview = true
                    showTinyPreviewIfNeeded()
                }
                record()
                invalidate()
            }
    }

    private val canPresentTinyPreview: Boolean
        get() = canShowTinyPreview &&
            (!galleryPreview || attachment?.let { !store.wasShownInGallery(it) } == true)

    private fun showTinyPreviewIfNeeded() {
        if (!canPresentTinyPreview || ready || playbackFrameRequest != null || tinyRequest != null) return
        val asset = attachment ?: return
        val token = binding ?: return
        val cached = store.cachedTinyPreview(asset)
        if (cached != null) {
            mailbox.offer(token, 1, cached)
            mailbox.flush()
        } else if (asset.tinyPreview != null) {
            tinyRequest = store.tinyPreview(asset) { result ->
                result.onSuccess { if (canPresentTinyPreview) mailbox.offer(token, 1, it) else it.close() }
            }
        }
    }

    private fun publish(value: PreparedImage?) {
        native.discardDisplayList()
        displayed = null
        ready = false
        displayed = value
        record()
        invalidate()
    }

    private fun clipPath(): Path {
        val r = if (circular) minOf(contentBounds.width(), contentBounds.height()) / 2 else cornerRadius
        val b = if (bottomSquare) minOf(r, 4 * density) else r
        return Path().apply {
            addRoundRect(
                RectF(0f, 0f, contentBounds.width(), contentBounds.height()),
                floatArrayOf(r, r, r, r, b, b, b, b),
                Path.Direction.CW,
            )
        }
    }

    private val photoMatrix = Matrix()
    private val photoMatrixValues = FloatArray(9)
    private val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private fun drawPhoto(canvas: Canvas): Boolean {
        canvas.clipPath(clipPath())
        if (kind != MessageKind.STICKER && (studioPhotoDelivery || attachment?.tinyPreview == null))
            canvas.drawColor(Color.rgb(214, 230, 219))

        val photo = displayed?.bitmap ?: return false
        val transform = PhotoPresentation.filling(
            rawWidth = photo.width,
            rawHeight = photo.height,
            orientation = 1,
            width = contentBounds.width().toDouble(),
            height = contentBounds.height().toDouble(),
        ) ?: return false
        photoMatrixValues[Matrix.MSCALE_X] = transform.a.toFloat()
        photoMatrixValues[Matrix.MSKEW_X] = transform.c.toFloat()
        photoMatrixValues[Matrix.MTRANS_X] = transform.tx.toFloat()
        photoMatrixValues[Matrix.MSKEW_Y] = transform.b.toFloat()
        photoMatrixValues[Matrix.MSCALE_Y] = transform.d.toFloat()
        photoMatrixValues[Matrix.MTRANS_Y] = transform.ty.toFloat()
        photoMatrixValues[Matrix.MPERSP_2] = 1f
        photoMatrix.setValues(photoMatrixValues)
        canvas.drawBitmap(photo, photoMatrix, photoPaint)
        return true
    }

    private fun record() {
        native.discardDisplayList()
        val c =
            native.beginRecording(
                kotlin.math.ceil(contentBounds.width().toDouble()).toInt().coerceAtLeast(1),
                kotlin.math.ceil(contentBounds.height().toDouble()).toInt().coerceAtLeast(1),
            )
        try {
            if (drawPhoto(c)) ready = true
            drawControl(c)
        } finally {
            native.endRecording()
        }
    }

    fun drawSoftware(c: Canvas) {
        val x = screenLeft
        val y = screenTop
        if (closed || c.quickReject(
                x, y, x + contentBounds.width(), y + contentBounds.height(), Canvas.EdgeType.AA,
            )
        ) return
        c.withTranslation(x, y) {
            drawPhoto(c)
            drawControl(c)
        }
    }

    fun draw(c: Canvas) {
        if (!native.hasDisplayList()) record()
        c.drawRenderNode(native)
    }

    val hasSharpFrame
        get() = ready && displayed?.isTinyPreview == false

    private val hasUsablePhoto
        get() = kind == MessageKind.PHOTO && hasSharpFrame

    private val hasTransferControl
        get() = loading && !hasUsablePhoto && !studioPhotoDelivery &&
            (progress > 0 || (showLoadingControl && (kind == null || transferStarted)))

    fun loadAction(): Pair<RectF, String>? {
        if (!showsControls || hasUsablePhoto || !failed && !hasTransferControl) return null
        val x = contentBounds.centerX() + offsetX
        val y = contentBounds.centerY() + offsetY
        val half = 24 * density
        return RectF(x - half, y - half, x + half, y + half) to
            if (loading) "Отменить загрузку" else "Повторить загрузку"
    }

    fun performLoadAction(x: Float, y: Float): Boolean {
        val action = loadAction() ?: return false
        if (closed || !action.first.contains(x, y)) return false
        if (loading) {
            loadAttempt++
            request?.cancel()
            request = null
            loading = false
            failed = true
            record()
            invalidate()
        } else
            attachment?.let {
                identity = ""
                bind(
                    it,
                    contentBounds,
                    circular,
                    pixelLimit,
                    cornerRadius,
                    bottomSquare,
                    kind,
                    showsControls,
                    studioPhotoDelivery,
                    studioPhotoMaxPixel,
                    showLoadingControl,
                    offsetX = offsetX,
                    offsetY = offsetY,
                    playbackKey = playbackFrameKey,
                    galleryPreview = galleryPreview,
                )
            }
        return true
    }

    private fun drawControl(c: Canvas) {
        if (!showsControls || hasUsablePhoto || loading && !hasTransferControl) return
        if (!loading && !failed) {
            if (kind == MessageKind.VIDEO) {
                val x = contentBounds.width() / 2
                val y = contentBounds.height() / 2
                val d = density
                val p = controlPaint
                p.style = Paint.Style.FILL
                p.color = 0x5C000000
                c.drawCircle(x, y, 24 * d, p)
                p.color = Color.WHITE
                c.drawPath(
                    Path().apply {
                        moveTo(x - 4 * d, y - 9 * d)
                        lineTo(x - 4 * d, y + 9 * d)
                        lineTo(x + 11 * d, y)
                        close()
                    },
                    p,
                )
            }
            return
        }
        val x = contentBounds.width() / 2
        val y = contentBounds.height() / 2
        val d = density
        val p = controlPaint
        p.style = Paint.Style.FILL
        p.color = 0x6B000000
        c.drawCircle(x, y, 24 * d, p)
        p.style = Paint.Style.STROKE
        p.color = Color.WHITE
        p.strokeWidth = 2 * d
        p.strokeCap = Paint.Cap.ROUND
        c.drawArc(
            RectF(x - 20 * d, y - 20 * d, x + 20 * d, y + 20 * d),
            -90f,
            ((if (loading) if (progress > 0) progress else 0.72 else 0.82) * 360).toFloat(),
            false,
            p,
        )
        if (failed) {
            c.drawLine(x - 2 * d, y - 11 * d, x + 4 * d, y - 7 * d, p)
            c.drawLine(x + 4 * d, y - 7 * d, x - 2 * d, y - 3 * d, p)
        } else {
            c.drawLine(x - 5 * d, y - 5 * d, x + 5 * d, y + 5 * d, p)
            c.drawLine(x + 5 * d, y - 5 * d, x - 5 * d, y + 5 * d, p)
        }
    }

    fun prepareOriginalVideo(attachment: Attachment) {
        if (ready && !loading && !failed && videoPreparation == null)
            videoPreparation = store.prepareStudioVideo(attachment)
    }

    fun preview(): PreparedImage? = displayed?.retained()

    fun showSavedPlaybackFrame(expectedKey: VideoPlaybackKey? = null): Boolean {
        if (closed) return false
        val key = playbackFrameKey ?: return false
        if (expectedKey != null && key != expectedKey) return false
        val frame = store.videoPlayback.cover(key) ?: return false
        if (displayed?.bitmap === frame.bitmap) frame.close()
        else seedPlaybackFrame(frame)
        return true
    }

    fun seedPlaybackFrame(image: PreparedImage) {
        val token = binding
        if (closed || token == null || image.bitmap == null) { image.close(); return }
        // Чёткий кадр уже готов, поэтому заставка и её декодирование больше не нужны.
        playbackFrameRequest?.cancel()
        playbackFrameRequest = null
        loadAttempt++
        request?.cancel()
        request = null
        tinyRequest?.cancel()
        tinyRequest = null
        loading = false
        failed = false
        deferred = false
        playbackFrameSequence++
        mailbox.offer(token, playbackFrameSequence, image)
        mailbox.flush()
    }

    fun seed(image: PreparedImage, allowDownscale: Boolean = false) {
        if (playbackFrameRequest != null || image.isTinyPreview && !canPresentTinyPreview) {
            image.close()
            return
        }
        val token = binding
        val bitmap = image.bitmap
        if (token == null || bitmap == null) {
            image.close()
            return
        }
        val edge = maxOf(bitmap.width, bitmap.height)
        val current = displayed?.bitmap
        if (hasSharpFrame && current != null &&
            (image.isTinyPreview || (!allowDownscale && edge <= maxOf(current.width, current.height)))
        ) {
            image.close()
            return
        }
        val serial = when {
            image.isTinyPreview -> 1L
            edge >= pixelLimit -> 4L
            else -> 2L
        }
        mailbox.offer(token, serial, image)
        mailbox.flush()
    }

    fun refresh() {
        loadAttempt++
        tinyRequest?.cancel()
        request?.cancel()
        playbackFrameRequest?.cancel()
        playbackFrameRequest = null
        playbackFrameKey = null
        videoPreparation?.cancel()
        videoPreparation = null
        request = null
        identity = ""
        loading = false
        failed = false
    }

    // Сразу применяет очистку в потоке экрана, чтобы прежняя системная ссылка не оставалась до
    // уведомления.
    fun clear() {
        refresh()
        mailbox.clear()
        mailbox.flush()
    }

    // Удаляет наблюдение хранилища, чтобы сброс медиа больше не обращался к закрытому владельцу.
    override fun close() {
        if (closed) return
        closed = true
        refresh()
        mailbox.close()
        mailbox.flush()
        native.discardDisplayList()
        displayed = null
        store.unregisterInvalidation(invalidation)
    }
}
