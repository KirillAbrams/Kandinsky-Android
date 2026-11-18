package dev.kirill.kandinsky.chat.media

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.FrameLayout
import androidx.core.graphics.withClip
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.rendering.PreparedText

class SilentVideoPreview(
    context: Context,
    private val store: MediaStore?,
    private val changed: (() -> Unit)?,
) : FrameLayout(context), TextureView.SurfaceTextureListener, AutoCloseable {
    constructor(context: Context) : this(context, null, null)

    private val texture =
        TextureView(context).apply {
            isOpaque = false
            alpha = 0f
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    private val main = Handler(Looper.getMainLooper())
    private var identity: String? = null
    private var request: Cancellation? = null
    private var lifetime: PreviewLifetime? = null
    private var session: PlayerSession? = null
    private var surface: Surface? = null
    private val heldTextures = mutableListOf<SurfaceTexture>()
    private var retiring = false
    private var outputsRetired = false
    private var aspect = 1f
    private var uprightSource = false
    private var transformedAspect = -1f
    private var transformedWidth = 0
    private var transformedHeight = 0
    private var generation = 0
    private var hasFrame = false
    private var playbackKey: VideoPlaybackKey? = null
    private var cacheGeneration = 0
    private var restoring = false
    private var visible = true
    private var circular = false
    private var cornerRadius = 0f
    private var bottomSquare = false
    private var stamp: RenderNode? = null
    private val stampPlacement =
        RenderNode("Kandinsky preview foreground placement").apply { setClipToBounds(false) }
    private var prepared: PreparedText? = null
    private var localMedia = RectF()
    private var frameVersion = 0L
    private var savedCoverVersion = -1L
    private var pendingCoverVersion = -1L
    var frameCallbacks = 0
        private set

    var closed = false
        private set

    val active
        get() = !closed && !restoring && hasFrame && session != null
    val presented
        get() = active && visible
    val isPlaying
        get() = presented

    var onPausedFrame: (() -> Unit)? = null

    fun setVisible(value: Boolean) {
        if (closed || visible == value) return
        visible = value
        alpha = if (value) 1f else 0f
        if (value) {
            if (!restoring) session?.resume()
        } else {
            if (retiring) { close(); return }
            rememberFrame(deferWithCover = true)
            session?.pause { rememberPosition(it) }
            onPausedFrame?.invoke()
        }
    }

    private val invalidation = store?.registerInvalidation { close() }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(texture, LayoutParams(-1, -1))
        texture.surfaceTextureListener = this
        texture.clipToOutline = true
        texture.outlineProvider =
            object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    if (view.width <= 0 || view.height <= 0) {
                        outline.setEmpty()
                        return
                    }
                    if (circular) outline.setOval(0, 0, view.width, view.height)
                    else {
                        @Suppress("DEPRECATION")
                        outline.setConvexPath(shape(view.width.toFloat(), view.height.toFloat()))
                    }
                }
            }
    }

    // Один владелец относится к одному исходнику, поэтому при замене файла закрывается для создания
    // следующего.
    fun bind(
        attachment: Attachment,
        rect: RectF,
        circular: Boolean,
        cornerRadius: Float,
        bottomSquare: Boolean,
        stamp: RenderNode,
        prepared: PreparedText,
        studioAlbum: Boolean = false,
        playbackKey: VideoPlaybackKey? = null,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        localOnly: Boolean = false,
    ) {
        val store = store ?: return
        val stampChanged = this.stamp !== stamp
        val positionX = rect.left + offsetX
        val positionY = rect.top + offsetY
        // Рамка остаётся местной, а поверхность движется с той же точностью, что и строка.
        val pixelWidth = rect.width().toInt().coerceAtLeast(1)
        val pixelHeight = rect.height().toInt().coerceAtLeast(1)
        val sizeChanged = width != pixelWidth || height != pixelHeight
        val outlineChanged =
            this.circular != circular ||
                this.cornerRadius != cornerRadius ||
                this.bottomSquare != bottomSquare ||
                sizeChanged
        this.circular = circular
        this.cornerRadius = cornerRadius
        this.bottomSquare = bottomSquare
        this.stamp = stamp
        this.prepared = prepared
        localMedia = RectF(prepared.mediaRect ?: RectF())
        val w = if (attachment.orientation >= 5) attachment.rawHeight else attachment.rawWidth
        val h = if (attachment.orientation >= 5) attachment.rawWidth else attachment.rawHeight
        aspect = maxOf(1, w).toFloat() / maxOf(1, h)
        uprightSource = attachment.orientation == 1 && w > 0 && h > 0
        val needsMeasure =
            isLayoutRequested || measuredWidth != pixelWidth || measuredHeight != pixelHeight
        if (needsMeasure) {
            measure(
                MeasureSpec.makeMeasureSpec(pixelWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(pixelHeight, MeasureSpec.EXACTLY),
            )
        }
        if (needsMeasure || sizeChanged) {
            layout(0, 0, pixelWidth, pixelHeight)
            stampPlacement.setPosition(0, 0, pixelWidth, pixelHeight)
        }
        translationX = positionX
        translationY = positionY
        updateTextureTransform()
        if (outlineChanged) texture.invalidateOutline()
        stampPlacement.translationX = -positionX
        stampPlacement.translationY = -positionY
        if (stampChanged || !stampPlacement.hasDisplayList()) {
            val canvas =
                stampPlacement.beginRecording(width.coerceAtLeast(1), height.coerceAtLeast(1))
            try {
                canvas.drawRenderNode(stamp)
            } finally {
                stampPlacement.endRecording()
            }
            invalidate()
        }
        if (closed) return
        if (identity != null) {
            if (identity != attachment.relativePath) close()
            return
        }
        val lease = MediaAdmission.reservePreview(studioAlbum) { close() } ?: return
        val life = PreviewLifetime(lease)
        lifetime = life
        this.playbackKey = playbackKey ?: VideoPlaybackKey(0, attachment)
        cacheGeneration = store.videoPlayback.generation
        identity = attachment.relativePath
        val ticket = ++generation
        life.begin()
        request =
            store.playbackSource(attachment, localOnly = localOnly) { result ->
                try {
                    if (closed || generation != ticket) {
                        result.getOrNull()?.close()
                        return@playbackSource
                    }
                    request = null
                    result.fold(
                        { source ->
                            val next =
                                PlayerSession(
                                    store.previewWorker(),
                                    main,
                                    {},
                                    { _, _, _ -> },
                                    { ready ->
                                        if (!closed && generation == ticket)
                                            ready.fold({ duration -> restorePosition(duration, ticket) }, { failPreview() })
                                    },
                                    loop = true,
                                )
                            session = next
                            next.open(source)
                            surface?.let { next.attach(it) {} }
                        },
                        { if (localOnly) close() else failPreview() },
                    )
                } finally {
                    life.end()
                }
            }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        updateTextureTransform()
    }

    private fun updateTextureTransform() {
        if (
            width > 0 &&
                height > 0 &&
                (transformedWidth != width ||
                    transformedHeight != height ||
                    transformedAspect != aspect)
        ) {
            transformedWidth = width
            transformedHeight = height
            transformedAspect = aspect
            val viewportAspect = width.toFloat() / height
            texture.setTransform(
                Matrix().apply {
                    setScale(
                        maxOf(1f, aspect / viewportAspect),
                        maxOf(1f, viewportAspect / aspect),
                        width / 2f,
                        height / 2f,
                    )
                }
            )
        }
    }

    private fun shape(w: Float, h: Float): Path {
        val r = cornerRadius
        val b = if (bottomSquare) minOf(r, 4 * resources.displayMetrics.density) else r
        return Path().apply {
            if (circular) addOval(RectF(0f, 0f, w, h), Path.Direction.CW)
            else
                addRoundRect(
                    RectF(0f, 0f, w, h),
                    floatArrayOf(r, r, r, r, b, b, b, b),
                    Path.Direction.CW,
                )
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        drawForeground(canvas)
    }

    private fun drawForeground(canvas: Canvas) {
        if (hasFrame) {
            canvas.withClip(0, 0, width, height) {
                if (canvas.isHardwareAccelerated) {
                    if (stampPlacement.hasDisplayList()) canvas.drawRenderNode(stampPlacement)
                } else {
                    canvas.translate(-localMedia.left, -localMedia.top)
                    prepared?.drawMediaStamp(canvas)
                }
            }
        }
    }

    private fun rememberPosition(value: Long) {
        playbackKey?.let { store?.videoPlayback?.rememberPosition(value, it, cacheGeneration) }
    }

    // Передаём видимый кадр в просмотр до отсоединения поверхности.
    fun snapshotFrame(): PreparedImage? {
        rememberFrame()
        return playbackKey?.let { store?.videoPlayback?.frame(it) }
    }

    private fun rememberFrame(deferWithCover: Boolean = false) {
        val key = playbackKey ?: return
        val store = store ?: return
        if (!hasFrame || restoring || !texture.isAvailable) return
        val version = frameVersion
        val output = surface
        // Копия исходной поверхности сохраняет кадрирование для прямоугольника того же формата.
        if (
            deferWithCover && output != null && uprightSource && height > 0 &&
                kotlin.math.abs(aspect * height - width) <= 1f &&
                store.videoPlayback.refreshCoverFromSurface(
                    key, cacheGeneration, width, height, output,
                ) { saved ->
                    if (pendingCoverVersion == version) pendingCoverVersion = -1
                    if (saved) {
                        savedCoverVersion = version
                        if (!closed && !visible) onPausedFrame?.invoke()
                    }
                }
        ) {
            pendingCoverVersion = version
            return
        }
        store.videoPlayback.rememberFrame(key, cacheGeneration, width, height) { w, h ->
            texture.getBitmap(w, h)?.also { savedCoverVersion = frameVersion }
        }
    }

    private fun restorePosition(duration: Long, ticket: Int) {
        val saved = playbackKey?.let { store?.videoPlayback?.position(it) } ?: 0L
        val target = saved.coerceIn(0, maxOf(0, duration - 1))
        if (target == 0L) {
            if (visible) session?.resume()
            return
        }
        restoring = true
        session?.seek(target) { finished ->
            if (closed || generation != ticket) return@seek
            if (!finished) { failPreview(); return@seek }
            restoring = false
            if (visible) session?.resume()
        }
    }

    private fun releaseOutputs() {
        outputsRetired = true
        surface?.release()
        surface = null
        heldTextures.forEach { it.release() }
        heldTextures.clear()
    }

    private fun failPreview() {
        retireNative()
        // Пока строка видна, ошибка остаётся на месте. Новый запрос начнётся после
        // ухода и возврата, а обновление экрана не создаст непрерывных повторов.
        if (!visible) close()
    }

    private fun retireNative() {
        if (retiring) return
        retiring = true
        hasFrame = false
        texture.alpha = 0f
        invalidate()
        request?.cancel()
        request = null
        val old = session
        session = null
        val life = lifetime
        lifetime = null
        // Рабочий поток сначала снимает поверхность и освобождает проигрыватель, только его
        // подтверждение возвращает место
        if (old != null) {
            life?.begin()
            old.release(position = { value ->
                if (!restoring) rememberPosition(value)
            }) {
                releaseOutputs()
                life?.end()
                life?.retire()
                changed?.invoke()
            }
        } else {
            releaseOutputs()
            life?.retire()
            changed?.invoke()
        }
    }

    override fun close() {
        if (closed) return
        // Пока обновляется кадр паузы, ранее сохранённая чёткая обложка остаётся доступной.
        val saved = if (!visible && (savedCoverVersion == frameVersion || pendingCoverVersion == frameVersion))
            playbackKey?.let { store?.videoPlayback?.frame(it) }
        else null
        if (saved == null) rememberFrame() else saved.close()
        closed = true
        onPausedFrame = null
        generation++
        retireNative()
        (parent as? ViewGroup)?.removeView(this)
        stamp = null
        stampPlacement.discardDisplayList()
        prepared = null
        if (invalidation != null) store?.unregisterInvalidation(invalidation)
    }

    override fun onSurfaceTextureAvailable(value: SurfaceTexture, w: Int, h: Int) {
        if (store == null || closed || retiring) return
        val old = surface
        val next = Surface(value)
        surface = next
        val native = session
        if (native != null) native.attach(next) { old?.release() } else old?.release()
    }

    // Отдельное действие не требуется, поскольку преобразование обновляется при размещении самого
    // элемента.
    override fun onSurfaceTextureSizeChanged(value: SurfaceTexture, w: Int, h: Int) {}

    override fun onSurfaceTextureDestroyed(value: SurfaceTexture): Boolean {
        if (store == null) return true
        if (outputsRetired) {
            value.release()
            return false
        }
        if (heldTextures.none { it === value }) heldTextures += value
        if (!retiring) close()
        return false
    }

    override fun onSurfaceTextureUpdated(value: SurfaceTexture) {
        if (closed || retiring || texture.surfaceTexture !== value) return
        // Буфер, доставленный после скрытия, уже не виден и не меняет версию обложки паузы.
        if (restoring || !visible) return
        frameVersion++
        frameCallbacks++
        if (!hasFrame) {
            hasFrame = true
            texture.alpha = 1f
            invalidate()
            // TextureView сам обновляет следующие кадры. Ленте нужно знать, когда видео готово.
            changed?.invoke()
        }
    }
}
