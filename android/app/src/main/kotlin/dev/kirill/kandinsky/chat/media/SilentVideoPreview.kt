// Показывает ролик без звука в строке и продолжает с прежней позиции. Для скрытого видео
// останавливает вывод и сохраняет кадр, а при закрытии освобождает поверхность.

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
    // Создаёт представление без хранилища и действий экрана. Такой объект не сможет начать
    // сетевой просмотр.
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
        // Проверяет наличие выведенного кадра и живого сеанса вне перемотки. Одна готовая
        // поверхность ещё не означает, что можно убрать картинку.
        get() = !closed && !restoring && hasFrame && session != null
    val presented
        // Считает видео показанным только когда оно готово и видимо. Скрытый остановленный ролик
        // не должен участвовать в показе.
        get() = active && visible
    val isPlaying
        // Возвращает для ленты признак видимого готового видео. Управление сеансом остаётся
        // внутри просмотра.
        get() = presented

    var onPausedFrame: (() -> Unit)? = null

    // На появлении продолжает сеанс после завершения перемотки. При уходе сохраняет кадр и
    // запрашивает паузу с текущей позицией.
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

    // Создаёт прозрачный слой, отключает собственные действия и настраивает обрезку. Метки и
    // действия принадлежат строке вокруг видео.
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
                // Задаёт видимую границу вывода по форме и размеру. При нулевой рамке ничего не
                // рисуется.
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

    // Переносит поверхность и отметки строки вместе с прокруткой. При первой привязке получает
    // место и источник, а поздний ответ закрывает, если строка уже ушла.
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

    // Даёт системе разместить дочерние слои и снова сверяет масштаб. Размеры могли поменяться
    // после размещения.
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        updateTextureTransform()
    }

    // Пересчитывает масштаб, только если изменились пропорции или рамка. Увеличивает нужную
    // сторону, чтобы видео сохранило пропорции и заполнило область.
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

    // Строит круглую или закруглённую прямоугольную границу. Нижние углы могут иметь отдельный
    // размер, чтобы совпасть с карточкой.
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

    // После собственно видео рисует поверх отметки строки. Иначе ролик мог бы закрыть подписи.
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        drawForeground(canvas)
    }

    // Ограничивает отметки рамкой и выбирает заранее записанный или обычный рисунок по способу
    // вывода. Пока кадра нет, этот слой не дублирует подпись.
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

    // Передаёт время хранилищу по ключу видео и номеру состояния. Запоздалый сеанс не должен
    // записывать место другого ролика.
    private fun rememberPosition(value: Long) {
        playbackKey?.let { store?.videoPlayback?.rememberPosition(value, it, cacheGeneration) }
    }

    // Берёт текущий кадр и открывает удержание для просмотра. При переходе между экранами
    // остаётся видимый кадр.
    fun snapshotFrame(): PreparedImage? {
        rememberFrame()
        return playbackKey?.let { store?.videoPlayback?.frame(it) }
    }

    // При готовом выводе сохраняет кадр из поверхности или считывает его сразу. Старая чёткая
    // копия остаётся до окончания обновления, а версия связывает результат с последним видимым
    // кадром.
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

    // Ограничивает прежнее время длиной ролика и перематывает до показа. Возобновляет только
    // видимое видео, если попытка ещё действительна.
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

    // Освобождает поверхность и все отложенные слои после отключения проигрывателя. Позднее
    // уведомление знает, что удерживать больше нечего.
    private fun releaseOutputs() {
        outputsRetired = true
        surface?.release()
        surface = null
        heldTextures.forEach { it.release() }
        heldTextures.clear()
    }

    // Останавливает системный сеанс при отказе. Пока строка видима, повтор отложен до ухода и
    // возврата, чтобы сеть не повторяла сбой в каждом кадре.
    private fun failPreview() {
        retireNative()
        // Пока строка видна, ошибка остаётся на месте. Новый запрос начнётся после
        // ухода и возврата, а обновление экрана не создаст непрерывных повторов.
        if (!visible) close()
    }

    // Снимает запрос, останавливает вывод и ждёт закрытия проигрывателя перед возвратом места.
    // Иначе следующая строка могла бы создать новый сеанс до освобождения старого.
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

    // Использует уже сохранённый кадр паузы или сохраняет последний, затем снимает вывод, отметки
    // и подписку. Повторное закрытие не повторяет эту работу.
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

    // Подключает новую поверхность к существующему сеансу и освобождает старую после ответа. Если
    // сеанс ещё готовится, поверхность ждёт его.
    override fun onSurfaceTextureAvailable(value: SurfaceTexture, w: Int, h: Int) {
        if (store == null || closed || retiring) return
        val old = surface
        val next = Surface(value)
        surface = next
        val native = session
        if (native != null) native.attach(next) { old?.release() } else old?.release()
    }

    // Принимает смену размера без отдельной операции. Размещение элемента уже пересчитало
    // преобразование.
    override fun onSurfaceTextureSizeChanged(value: SurfaceTexture, w: Int, h: Int) {}

    // Оставляет вывод на время остановки проигрывателя, если он ещё используется. После остановки
    // освобождает, чтобы кадр не записывался в закрытую поверхность.
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

    // Учитывает только новый кадр текущего видимого ролика после перемотки. Первый кадр открывает
    // слой и сообщает ленте о готовности, следующие обновляет сама система.
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
            // Поверхность видео сама обновляет следующие кадры. Ленте нужно узнать только о готовности.
            changed?.invoke()
        }
    }
}
