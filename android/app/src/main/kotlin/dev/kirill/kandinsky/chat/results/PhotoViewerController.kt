// Просмотрщик на андроиде показывает отдельное фото или страницу галереи. Он управляет загрузкой
// оригинала, оставляет уменьшенный кадр для соседей и возвращает сохранённый кадр видео. Масштаб
// и касания принадлежат странице, а переходы между файлами ведёт галерея.

package dev.kirill.kandinsky.chat.results

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import androidx.core.graphics.withTranslation
import androidx.core.view.isVisible
import dev.kirill.kandinsky.chat.domain.Attachment
import dev.kirill.kandinsky.chat.domain.MessageKind
import dev.kirill.kandinsky.chat.domain.Cancellation
import dev.kirill.kandinsky.chat.media.MediaImageSlot
import dev.kirill.kandinsky.chat.media.MediaNavigationDrawable
import dev.kirill.kandinsky.chat.media.MediaProgressControl
import dev.kirill.kandinsky.chat.media.MediaStore
import dev.kirill.kandinsky.chat.media.PreparedImage
import dev.kirill.kandinsky.chat.media.ThumbnailKey
import dev.kirill.kandinsky.chat.media.VideoPlaybackKey
import dev.kirill.kandinsky.chat.media.prepareOpaqueMedia
import kotlin.math.abs
import kotlin.math.max

class PhotoViewerController(
    private val activity: Activity,
    private val attachment: Attachment,
    private val media: MediaStore,
    private var preview: PreparedImage? = null,
    private val caption: String = "",
    private val embedded: Boolean = false,
    private val playbackKey: VideoPlaybackKey? = null,
) {
    var onClose: (() -> Unit)? = null
    var onRequestDismiss: (() -> Unit)? = null
    var onToggleControls: (() -> Unit)? = null
    var onPreviewReady: (() -> Unit)? = null
    private val sharedStudioPhoto = media.studioPhotoKey(attachment) != null
    private var active = !embedded
    private var closed = false
    private var pageRoot: FrameLayout? = null
    var downloadPausedByUser = false
        private set

    val pageIsZoomed
        // Передаём галерее, увеличена ли картинка. Пока масштаб больше обычного, горизонтальное
        // движение должно перемещать фото внутри страницы.
        get() = image?.isZoomed == true

    private val main = Handler(Looper.getMainLooper())
    private var dialog: Dialog? = null
    private var download: Cancellation? = null
    private var generation = 0
    private var hasOriginal = false
    private var standbyPreview: PreparedImage? = null
    private var image: PhotoContent? = null
    private var progress: MediaProgressControl? = null
    private var retry: ImageButton? = null
    private var back: ImageButton? = null
    private var captionView: TextView? = null
    private var captionShade: View? = null

    // Вложенную страницу очищаем сразу, поскольку её окно принадлежит галерее. Для
    // самостоятельного просмотра закрываем свой диалог, который снимет ресурсы после ухода.
    fun close() {
        if (embedded) cleanup() else dialog?.dismiss()
    }

    // Назначаем постоянное имя кнопкам и подписи просмотрщика. Их можно найти с помощью средств
    // доступности независимо от цвета и положения.
    private fun identity(view: View, id: String) {
        view.accessibilityDelegate =
            object : View.AccessibilityDelegate() {
                // Добавляем постоянное имя после системного описания элемента. Остальные свойства
                // кнопки и её действие остаются доступны.
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfo,
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.viewIdResourceName = id
                }
            }
    }

    // Открываем самостоятельное окно только один раз и связываем закрытие с уборкой. После показа
    // начинаем подготовку оригинала, если файл не использует общий путь фотографии генерации.
    fun show() {
        if (dialog != null || embedded || closed) return
        val d = Dialog(activity, android.R.style.Theme_Material_NoActionBar_Fullscreen)
        dialog = d
        val root = createRoot()
        d.setOnDismissListener {
            dialog = null
            cleanup()
            val callback = onClose
            onClose = null
            callback?.invoke()
        }
        d.window?.prepareOpaqueMedia()
        d.setContentView(root)
        d.show()
        d.window?.setLayout(-1, -1)
        root.requestApplyInsets()
        if (!sharedStudioPhoto) loadOriginal()
    }

    // Возвращаем готовую область вложенной страницы либо собираем её при первом запросе. Для
    // самостоятельного или уже закрытого просмотра такой вызов означает ошибку владельца.
    fun pageView(): View {
        check(embedded && !closed)
        return pageRoot ?: createRoot()
    }

    // Просим слой страницы вернуть доступный удержанный кадр. Пока слой не создан, галерея может
    // обойтись без отдельного изображения.
    fun retainedPreview(): PreparedImage? = image?.retainedPreview()

    // Передаём странице просьбу показать сохранённый кадр. Галерея пользуется этим после переноса
    // или снятия поверхности ролика.
    fun showLastVideoFrame() { image?.showLastVideoFrame() }

    // Возвращаем отметку ручной отмены при повторном создании страницы. Готовим область, чтобы
    // вместо автоматической загрузки появилась кнопка повтора.
    fun pauseOriginal() {
        if (!closed) {
            pageView()
            cancelDownload(true)
        }
    }

    // У выбранной страницы показываем готовый оригинал, начинаем его загрузку или оставляем
    // ручной повтор. Когда страница становится соседней, отменяем ненужную работу и снижаем
    // размер картинки.
    fun setPageActive(value: Boolean) {
        if (closed || active == value) return
        active = value
        if (sharedStudioPhoto) {
            retry?.visibility = if (active && image?.needsRetry == true) View.VISIBLE else View.GONE
            return
        }
        if (value) {
            if (downloadPausedByUser) retry?.visibility = View.VISIBLE
            else if (hasOriginal) {
                image?.reload()
            } else loadOriginal()
        } else {
            cancelDownload(false)
            image?.demote()
        }
    }

    // Создаём слой изображения, кнопки хода и повтора, а при необходимости подпись. Применяем
    // отступы системы к кнопке выхода и подписи, чтобы они не закрывались краями окна.
    private fun createRoot(): FrameLayout {
        val density = activity.resources.displayMetrics.density
        val root = FrameLayout(activity)
        pageRoot = root
        root.setBackgroundColor(Color.BLACK)
        val content = PhotoContent()
        image = content
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        val loading = MediaProgressControl(activity)
        progress = loading
        identity(loading, "media.cancel")
        loading.setOnClickListener { cancelDownload(true) }
        val again =
            ImageButton(activity).apply {
                setImageResource(android.R.drawable.stat_sys_download)
                setColorFilter(Color.WHITE)
                setBackgroundColor(Color.TRANSPARENT)
                visibility = View.GONE
                contentDescription = "Загрузить оригинал"
                setOnClickListener { loadOriginal() }
            }
        retry = again
        identity(again, "media.retry")
        for (control in listOf(loading, again)) root.addView(
            control,
            FrameLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt(), Gravity.CENTER),
        )
        if (caption.isNotEmpty()) {
            val shade =
                View(activity).apply {
                    background =
                        android.graphics.drawable.GradientDrawable(
                            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                            intArrayOf(Color.TRANSPARENT, 0xBF000000.toInt()),
                        )
                }
            captionShade = shade
            root.addView(
                shade,
                FrameLayout.LayoutParams(-1, (212 * density).toInt(), Gravity.BOTTOM).apply {
                    bottomMargin = (12 * density).toInt()
                },
            )
            val text =
                TextView(activity).apply {
                    this.text = caption
                    textSize = 16f
                    setTextColor(Color.WHITE)
                    setPadding(0, 0, 0, 0)
                    maxHeight = (180 * density).toInt()
                    movementMethod = android.text.method.ScrollingMovementMethod()
                    isVerticalScrollBarEnabled = false
                    setTextIsSelectable(true)
                }
            captionView = text
            identity(text, "viewer.caption")
            root.addView(
                text,
                FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                    leftMargin = (16 * density).toInt()
                    rightMargin = (16 * density).toInt()
                    bottomMargin = (24 * density).toInt()
                },
            )
        }
        val close =
            ImageButton(activity).apply {
                setImageDrawable(MediaNavigationDrawable(back = true))
                setColorFilter(Color.WHITE)
                setBackgroundColor(Color.TRANSPARENT)
                contentDescription = "Закрыть фотографию"
                visibility = if (embedded) View.GONE else View.VISIBLE
                setOnClickListener { this@PhotoViewerController.close() }
            }
        back = close
        identity(close, "photo.close")
        root.addView(
            close,
            FrameLayout.LayoutParams(
                    (44 * density).toInt(),
                    (44 * density).toInt(),
                    Gravity.TOP or Gravity.START,
                )
                .apply {
                    leftMargin = (12 * density).toInt()
                    topMargin = (8 * density).toInt()
                },
        )
        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION") val top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION") val bottom = insets.systemWindowInsetBottom
            (close.layoutParams as FrameLayout.LayoutParams)
                .apply { topMargin = top + (8 * density).toInt() }
                .also { close.layoutParams = it }
            captionView?.let { text ->
                (text.layoutParams as FrameLayout.LayoutParams)
                    .apply { bottomMargin = bottom + (24 * density).toInt() }
                    .also { text.layoutParams = it }
            }
            insets
        }
        return root
    }

    // Для текущей страницы просим оригинал из хранилища и отмечаем ход передачи. Номер попытки и
    // исходная область защищают от ответа после ухода. У фотографии генерации повтор запускает её
    // общий слой.
    private fun loadOriginal() {
        val root = pageRoot ?: return
        if (!active || closed) return
        downloadPausedByUser = false
        if (sharedStudioPhoto) {
            retry?.visibility = View.GONE
            image?.retryStudioPhoto()
            return
        }
        hasOriginal = false
        generation++
        val ticket = generation
        download?.cancel()
        retry?.visibility = View.GONE
        progress?.update(null, !media.hasLocalOriginal(attachment))
        download =
            media.ensureOriginal(
                attachment,
                progress = { value ->
                    main.post {
                        if (ticket == generation && pageRoot === root && !closed)
                            progress?.update(value.takeIf { it > 0 && it < 1 }, true)
                    }
                },
            ) { result ->
                if (ticket != generation || pageRoot !== root || closed) return@ensureOriginal
                download = null
                result.fold(
                    {
                        hasOriginal = true
                        image?.reload()
                    },
                    {
                        progress?.update(null, false)
                        retry?.visibility = View.VISIBLE
                        retry?.contentDescription = "Не удалось загрузить. Повторить"
                    },
                )
            }
    }

    // Отменяем текущую работу, сбрасываем ход передачи и делаем прежние ответы недействительными.
    // Ручная отмена оставляет кнопку, которой пользователь разрешит следующую попытку.
    private fun cancelDownload(showRetry: Boolean) {
        generation++
        download?.cancel()
        download = null
        if (showRetry) {
            downloadPausedByUser = true
            hasOriginal = false
        }
        progress?.update(null, false)
        retry?.visibility = if (showRetry) View.VISIBLE else View.GONE
    }

    // Заканчиваем загрузку и закрываем рисунок ровно один раз. Сбрасываем ссылки на кадры и
    // кнопки, а отдельное окно дополнительно отдаёт простаивающие рисунки хранилищу.
    private fun cleanup() {
        if (closed) return
        closed = true
        active = false
        cancelDownload(false)
        image?.close()
        preview?.close()
        preview = null
        standbyPreview?.close()
        standbyPreview = null
        image = null
        progress = null
        retry = null
        back = null
        captionView = null
        captionShade = null
        pageRoot = null
        if (!embedded) media.releaseIdleImages()
    }

    private inner class PhotoContent : View(activity) {
        private val slot: MediaImageSlot =
            MediaImageSlot(media) {
                if (closed) return@MediaImageSlot
                if (embedded && hasSharpFrame()) media.markShownInGallery(attachment)
                if (sharedStudioPhoto) {
                    retry?.visibility = if (active && slotFailed()) View.VISIBLE else View.GONE
                    onPreviewReady?.invoke()
                    invalidate()
                    return@MediaImageSlot
                }
                if (playbackKey != null) {
                    onPreviewReady?.invoke()
                    invalidate()
                    return@MediaImageSlot
                }
                retainStandbyPreview()
                onPreviewReady?.invoke()
                if (
                    active &&
                        hasOriginal &&
                        !originalBound &&
                        (standbyPreview != null || slotFailed())
                )
                    main.post { bind() }
                if (originalBound && !slotLoading()) {
                    progress?.update(null, false)
                    retry?.visibility = if (slotFailed()) View.VISIBLE else View.GONE
                }
                invalidate()
            }

        // Проверяем, есть ли у слоя чёткий кадр. Галерея по этому признаку запоминает, что уже
        // показывала этот файл подходящего качества.
        private fun hasSharpFrame(): Boolean = slot.hasSharpFrame

        // Читаем состояние подготовки изображения в самом слое. Пока оно истинно, не отключаем
        // ход работы по одному наличию оригинального файла.
        private fun slotLoading(): Boolean = slot.loading

        // Проверяем ошибку подготовки кадра. Готовый файл и декодированное изображение могут
        // иметь разное состояние, поэтому ошибка определяется отдельно.
        private fun slotFailed(): Boolean = slot.loadFailed

        // Объявляем рисунок доступным для касания. Нажатие может скрыть подписи даже когда на
        // странице нет отдельных кнопок.
        init {
            isClickable = true
        }

        private val fitted = RectF()
        private var scale = 1f
        private var panX = 0f
        private var panY = 0f
        val isZoomed
            // Проверяем фактический масштаб изображения с малым допуском. На обычном масштабе не
            // удерживаем жест внутри страницы.
            get() = scale > 1.01f

        // Берём удержанную копию кадра из слоя, чтобы повторно использовать её на другой
        // поверхности без чтения файла.
        fun retainedPreview(): PreparedImage? = slot.preview()

        // Заменяем видимый кадр последним сохранённым снимком конкретного видео, если он есть.
        // Страница остаётся видимой после ухода проигрывателя.
        fun showLastVideoFrame() { slot.showSavedPlaybackFrame(playbackKey) }

        val needsRetry
            // По ошибке слоя определяем, нужен ли повтор общего кадра. Тогда решение о кнопке не
            // зависит от самостоятельной загрузки оригинала.
            get() = slot.loadFailed

        // Обновляем слой фотографии генерации и повторно привязываем его к странице. Ей не нужно
        // открывать отдельный путь загрузки обычного файла.
        fun retryStudioPhoto() {
            slot.refresh()
            bind()
        }

        private var dismissY = 0f
        private var animator: ValueAnimator? = null
        private var closed = false
        private var originalBound = false
        private val zoom =
            ScaleGestureDetector(
                activity,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    // Останавливаем прежнюю анимацию и отдаём жест увеличения странице. Для видео
                    // масштабирование запрещено, чтобы не спорить с полноэкранным показом.
                    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                        animator?.cancel()
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return !attachment.mime.startsWith("video/")
                    }

                    // Умножаем текущий масштаб на изменение расстояния между пальцами. Точку под
                    // жестом передаём в общий пересчёт, чтобы картинка не уходила из-под них.
                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        setScale(scale * detector.scaleFactor, detector.focusX, detector.focusY)
                        return true
                    }
                },
            )
        private var dispatchingGesture = false
        private var confirmedTap = false

        private val gestures =
            GestureDetector(
                activity,
                object : GestureDetector.SimpleOnGestureListener() {
                    // Увеличенное фото удерживает начало жеста у себя. На обычном масштабе
                    // галерея может забрать движение для перелистывания.
                    override fun onDown(e: MotionEvent): Boolean {
                        parent?.requestDisallowInterceptTouchEvent(isZoomed)
                        return true
                    }

                    // Выполняем короткое нажатие сразу или после выхода из обработчика жестов.
                    // Так подтверждение при отпускании и подтверждение по таймеру приводят к
                    // одному действию.
                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        // Быстрое нажатие подтверждается таймером, медленное может подтвердиться
                        // прямо при отпускании. В обоих случаях действие вызывается один раз.
                        if (!dispatchingGesture) return performClick()
                        confirmedTap = true
                        return true
                    }

                    // Для фотографии плавно переходим между обычным и увеличенным видом вокруг
                    // точки нажатия. У ролика этот жест не меняет масштаб.
                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        if (attachment.mime.startsWith("video/")) return false
                        val start = scale
                        val target = if (scale > 1.05f) 1f else 3f
                        animator =
                            ValueAnimator.ofFloat(start, target).apply {
                                duration = 180
                                addUpdateListener { setScale(it.animatedValue as Float, e.x, e.y) }
                                start()
                            }
                        return true
                    }

                    // Во время увеличения перемещаем картинку в допустимых пределах. При обычном
                    // размере следим за вертикальным уходом, чтобы предложить закрытие жестом.
                    override fun onScroll(
                        first: MotionEvent?,
                        current: MotionEvent,
                        dx: Float,
                        dy: Float,
                    ): Boolean {
                        if (zoom.isInProgress) return true
                        if (scale > 1.01f) {
                            panX -= dx
                            panY -= dy
                            clampPan()
                        } else if (
                            first != null && abs(current.y - first.y) > abs(current.x - first.x)
                        ) {
                            dismissY = current.y - first.y
                        }
                        invalidate()
                        return true
                    }
                },
            )

        // Сохраняем уменьшенный кадр, выбираем новое качество слоя и запрашиваем отрисовку. Так
        // после получения оригинала страница может поднять чёткость.
        fun reload() {
            retainStandbyPreview()
            bind()
            invalidate()
        }

        // Повторно выбираем размер слоя после ухода со страницы и оставляем сохранённый
        // уменьшенный кадр. Освобождённая память доступна следующему оригиналу.
        fun demote() {
            bind()
            // Освобождаем оригинал до загрузки крупного кадра на соседней странице.
            standbyPreview?.retained()?.let { slot.seed(it, allowDownscale = true) }
        }

        // Ограничиваем масштаб и пересчитываем смещение вокруг точки жеста. Очищаем движение
        // закрытия и обновляем озвучиваемое значение масштаба.
        private fun setScale(value: Float, focusX: Float, focusY: Float) {
            val next = value.coerceIn(1f, 4f)
            val ratio = next / scale
            panX = focusX - width / 2f - (focusX - width / 2f - panX) * ratio
            panY = focusY - height / 2f - (focusY - height / 2f - panY) * ratio
            scale = next
            dismissY = 0f
            clampPan()
            contentDescription = "Фотография, масштаб ${(scale*100).toInt()}%"
            invalidate()
        }

        // Оставляем смещение в пределах увеличенного изображения. Когда по оси рисунок меньше
        // окна, фиксируем его по центру.
        private fun clampPan() {
            val x = max(0f, (fitted.width() * scale - width) / 2)
            val y = max(0f, (fitted.height() * scale - height) / 2)
            panX = panX.coerceIn(-x, x)
            panY = panY.coerceIn(-y, y)
        }

        // Считаем прямоугольник изображения по фактическому окну и ориентации файла. Для
        // изменившегося размера ограничиваем смещение и заново связываем слой.
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val iw =
                maxOf(
                    1,
                    if (attachment.orientation >= 5) attachment.rawHeight else attachment.rawWidth,
                )
            val ih =
                maxOf(
                    1,
                    if (attachment.orientation >= 5) attachment.rawWidth else attachment.rawHeight,
                )
            val ratio = minOf(w.toFloat() / iw, h.toFloat() / ih)
            fitted.set(
                (w - iw * ratio) / 2,
                (h - ih * ratio) / 2,
                (w + iw * ratio) / 2,
                (h + ih * ratio) / 2,
            )
            clampPan()
            bind()
        }

        // Берём маленький кадр из хранилища или текущего слоя и держим одну копию для возврата.
        // Слишком крупный кадр закрываем сразу, чтобы соседям не достался расход оригинала.
        private fun retainStandbyPreview() {
            val limit = if (embedded) 384 else 1024
            val candidate =
                media.cachedThumbnail(attachment, ThumbnailKey(limit, gallery = embedded)) ?: slot.preview() ?: return
            val bitmap = candidate.bitmap
            if (bitmap == null || maxOf(bitmap.width, bitmap.height) > limit) {
                candidate.close()
                return
            }
            standbyPreview?.close()
            standbyPreview = candidate
        }

        // Выбираем размер подготовленного кадра по активности страницы и готовности файла.
        // Готовое изображение, переданное лентой, используем один раз, прежде чем ждать
        // собственную подготовку.
        private fun bind() {
            if (!closed && !fitted.isEmpty) {
                originalBound = active && hasOriginal && (standbyPreview != null || slotFailed())
                slot.bind(
                    attachment,
                    fitted,
                    maxPixel = if (originalBound) 2048 else if (embedded) 384 else 1024,
                    cornerRadius = 0f,
                    kind = if (!embedded) null else if (playbackKey == null) MessageKind.PHOTO else MessageKind.VIDEO,
                    showControls = false,
                    playbackKey = playbackKey,
                    galleryPreview = embedded && !originalBound,
                )
                preview?.let {
                    preview = null
                    slot.seed(it)
                }
            }
        }

        // Применяем масштаб и смещение к рисунку, оставляя элементы управления на месте.
        // Используем подходящий путь рисования для экрана либо программной поверхности.
        override fun onDraw(c: Canvas) {
            c.withTranslation(width / 2f + panX, height / 2f + panY + dismissY) {
                c.scale(scale, scale)
                c.translate(-width / 2f, -height / 2f)
                if (c.isHardwareAccelerated) {
                    slot.draw(c)
                } else {
                    slot.drawSoftware(c)
                }
            }
        }

        // Передаём одиночное касание галерее или переключаем подпись и кнопку отдельного
        // просмотра. Обычный системный вызов нажатия при этом сохраняется.
        override fun performClick(): Boolean {
            val handledByView = super.performClick()
            if (embedded) {
                val toggle = onToggleControls
                toggle?.invoke()
                return handledByView || toggle != null
            }
            val closeButton = back ?: return handledByView
            val showing = !closeButton.isVisible
            closeButton.isVisible = showing
            captionView?.isVisible = showing
            captionShade?.isVisible = showing
            return true
        }

        // Разводим касания увеличения, одиночное нажатие и вертикальный выход. После отпускания
        // закрываем окно при достаточном сдвиге, иначе плавно возвращаем изображение.
        override fun onTouchEvent(event: MotionEvent): Boolean {
            // При втором нажатии распознаватель сначала сообщает о двойном жесте
            // Прежнюю анимацию нужно снять до передачи нового касания
            if (event.actionMasked == MotionEvent.ACTION_DOWN) animator?.cancel()
            zoom.onTouchEvent(event)
            dispatchingGesture = true
            try {
                gestures.onTouchEvent(event)
            } finally {
                dispatchingGesture = false
            }
            if (confirmedTap) {
                confirmedTap = false
                performClick()
            }
            if (
                event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                if (event.actionMasked == MotionEvent.ACTION_UP && abs(dismissY) > height * 0.16f) {
                    if (embedded) onRequestDismiss?.invoke() else this@PhotoViewerController.close()
                } else if (dismissY != 0f) {
                    val start = dismissY
                    animator =
                        ValueAnimator.ofFloat(start, 0f).apply {
                            duration = 180
                            addUpdateListener {
                                dismissY = it.animatedValue as Float
                                invalidate()
                            }
                            start()
                        }
                }
            }
            return true
        }

        // Один раз останавливаем анимацию и отпускаем слой изображения. Удалённая страница больше
        // не рисует кадры.
        fun close() {
            if (closed) return
            closed = true
            animator?.cancel()
            animator = null
            slot.close()
        }

        // Снимаем слой при уходе области из окна независимо от источника закрытия. Позднее
        // обновление не должно рисовать в уже снятой странице.
        override fun onDetachedFromWindow() {
            close()
            super.onDetachedFromWindow()
        }
    }
}
