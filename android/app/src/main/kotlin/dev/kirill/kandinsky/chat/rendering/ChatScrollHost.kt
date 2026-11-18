// Область истории на андроиде объединяет указатель строк, фоновые расчёты, сцену и жесты. Она
// передаёт изменения к ближайшему кадру, удерживает место чтения при новой ширине и освобождает
// просмотр при уходе из окна.

package dev.kirill.kandinsky.chat.rendering

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.*
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.OverScroller
import dev.kirill.kandinsky.chat.attachments.StudioUploadPreview
import dev.kirill.kandinsky.chat.conversation.MessageAction
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.media.*
import dev.kirill.kandinsky.chat.results.MediaGalleryController
import dev.kirill.kandinsky.chat.results.PhotoViewerController
import dev.kirill.kandinsky.chat.storage.ChatRepository
import java.util.concurrent.*
import kotlin.math.*

class ChatScrollHost(context: Context) : ViewGroup(context) {
    internal var preparedRowWindow: PreparedChatRowWindow? = null
    internal var index = HistoryIndex(dayGap = 36.0 * resources.displayMetrics.density)
        // Заменяем указатель и выбрасываем прежний список подготовленных строк. Новые высоты и
        // порядок уже могут указывать на другой участок.
        set(value) {
            field = value
            preparedRowWindow = null
        }
    internal val days = ScrollingDays()
    internal var overlayTop = 0.0
    internal var overlayBottom = 0.0
    internal var pageRequests = 0
    internal val scene = RetainedChatScene()
    internal val worker = TextLayoutWorker()
    internal val cache = FragmentLayoutCache()
    internal var pages: PageWindow? = null
    internal var repository: ChatRepository? = null
    internal var chatID: ChatID? = null
    internal var loaded = emptyMap<MessageID, Message>()
        // Заменяем загруженные сообщения и сбрасываем готовый список строк. Иначе новая редакция
        // могла бы продолжить рисоваться старым рисунком.
        set(value) {
            field = value
            preparedRowWindow = null
        }
    internal val inFlight = mutableMapOf<TextLayoutKey, Cancellation>()
    internal val pendingHeights = mutableMapOf<MessageID, Pair<TextLayoutKey, Double>>()
    internal val main = Handler(Looper.getMainLooper())
    var onStudioResultDelete: ((MessageID, String) -> Unit)? = null
    var onStudioGenerationAction: ((MessageID, StudioGenerationAction) -> Unit)? = null
    var onStudioRetrySlot: ((MessageID, StudioGeneration.Slot) -> Unit)? = null
    internal var indexRefreshing = false
    internal var closed = false
    internal var queued = false
    internal var pageLoading = false
    internal var generation = 0
    internal var opening: ((Result<Unit>) -> Unit)? = null

    internal var initialFrame: ((Result<Unit>) -> Unit)? = null
    internal var initialMediaDeadline = 0L
    internal val initialTimeout = Runnable { schedule() }

    // Передаём ожидание первого кадра механизму открытия истории. Вызывающий экран получит
    // результат после проверки готовности текста и сцены.
    fun prepareInitialFrame(completion: (Result<Unit>) -> Unit) =
        awaitInitialFrame(completion)

    internal var pendingAnchor: ReadingAnchor? = null
    internal var offset = 0.0
    internal val mediaPreparation = MediaPreparationWindow()
    internal val d = resources.displayMetrics.density
    internal var layoutWidth = 0
    internal var layoutFontSize = desiredFontSize()

    // Считаем размер основного текста с учётом системного масштаба. Нижняя граница исключает
    // непригодный размер при настройке шрифта.
    private fun desiredFontSize() =
        max(
            3,
            android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_SP,
                    17f,
                    resources.displayMetrics,
                )
                .roundToInt(),
        )

    internal val scroller = OverScroller(context)
    internal var lastFlingY = 0
    internal val displayPolicy =
        (context as? android.app.Activity)?.window?.let {
            dev.kirill.kandinsky.chat.diagnostics.ActiveDisplayPolicy(it)
        }

    // Читаем наличие видимого окна у владельца истории. Если владелец не сообщает признак,
    // движущиеся вложения не получают разрешение на фоновую работу.
    internal fun isHostForeground(): Boolean = (context as? ForegroundHost)?.isForeground == true

    // Просим более частое обновление только у видимой присоединённой истории во время движения
    // или показа кадров. В покое и за другим экраном снимаем запрос.
    internal fun updateDisplayHint() {
        displayPolicy?.setActive(
            !closed &&
                scene.mediaVisible &&
                isAttachedToWindow &&
                isHostForeground() &&
                (dragging || !scroller.isFinished || scene.metrics.activeFrameClocks > 0)
        )
    }

    internal var velocity: VelocityTracker? = null
    internal var longPressHandled = false
    internal val gestures =
        GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                // Принимаем начало жеста для последующего долгого нажатия. Без него
                // распознаватель мог бы проигнорировать продолжение касания.
                override fun onDown(e: MotionEvent) = true

                // Ищем сообщение и проверяем, что чат разрешает это меню и жест не стал
                // прокруткой. Помечаем касание обработанным, чтобы отпускание не открыло вложение
                // второй раз.
                override fun onLongPress(e: MotionEvent) {
                    if (dragging) return
                    val handler = onMessageLongPress ?: return
                    val (row, frame) = hit(e.x, e.y) ?: return
                    if (
                        onStudioGenerationAction != null &&
                            !row.canReportStudioMedia &&
                            row.direction != MessageDirection.OUTGOING
                    )
                        return
                    longPressHandled = true
                    handler(row, frame)
                }
            },
        )
    internal var lastY = 0f
    internal var dragging = false
    internal var nodes = emptyList<NativeAction>()
    internal var lastAxNotice = 0L
    internal var axNoticePending = false
    internal var playback: MediaCoordinator? = null
    internal val playbackOwner = Any()
    internal var tappedMediaIndex = 0
    internal val mediaExport = KandinskyMediaExport(context)

    // Готовим значки действий и карточек по ресурсам приложения заранее. При первом появлении
    // строки отрисовке не придётся искать их по отдельности.
    init {
        KandinskyMediaAction.load(resources)
        StudioGenerationCard.load(resources)
    }

    internal var viewerMedia: MediaStore? = null
    internal var playerSurface: InlinePlayerSurface? = null
    internal var studioPreview: StudioUploadPreview? = null
    internal var photoViewer: PhotoViewerController? = null
    internal var mediaGallery: MediaGalleryController? = null
    internal var ownedPlayerChange: (() -> Unit)? = null
    internal var ownedPlayerDetach: (() -> Unit)? = null
    internal val ax =
        NativeAccessibility(
            this,
            "chat.history",
            { nodes },
            { listOfNotNull(playerSurface?.takeIf { it.parent === this }) },
        )
    internal val buildPool =
        ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(1),
            { r -> Thread(r, "Kandinsky-Index").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )

    // Передаём общий проигрыватель и хранилище владельцу показа. Полноэкранный просмотр и лента
    // могут использовать одну поверхность.
    fun configurePlayback(coordinator: MediaCoordinator, media: MediaStore) =
        attachPlayback(coordinator, media)

    // Открываем выбранный исходный ролик до отправки и передаём действия улучшения и закрытия.
    // Обычный чат при этом не становится новым источником воспроизведения.
    fun previewStudioVideo(
        attachment: Attachment,
        onEnhance: (() -> Unit)? = null,
        onClose: () -> Unit,
    ) =
        presentStudioPreview(attachment, onEnhance, onClose)

    // Принимаем размер от внешнего экрана. Длина истории измеряется указателем отдельно, поэтому
    // длинный чат не увеличивает само окно.
    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h))
    }

    // Согласуем поверхность проигрывателя после новой раскладки. Её рамка должна совпадать с
    // местом вложения на последнем кадре.
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        updateInlinePlayer()
    }

    // Передаём владельцу показа изменение общей видимости файлов. Он приостанавливает подготовку
    // и восстанавливает показ при возвращении.
    fun setMediaVisible(visible: Boolean) =
        updateMediaVisibility(visible)

    // При переходе на другой экран временно снимаем движущийся показ. Подготовленные кадры
    // используются как запас для возвращения.
    fun pauseForNavigation() =
        pauseMediaForNavigation()

    // Отдельно передаём разрешение движения поверх оставшегося видимого кадра. Это нужно при
    // открытии списка над историей.
    fun setMovingMediaVisible(visible: Boolean) =
        updateMovingMediaVisibility(visible)

    // Подключаем хранилище к сцене и обновляем получение подготовленных кадров. При смене
    // источника старый владелец не должен передавать новый рисунок.
    fun configureMedia(store: dev.kirill.kandinsky.chat.media.MediaStore) =
        attachMediaStore(store)

    var onAnchor: ((ReadingAnchor) -> Unit)? = null
    var onMessageTap: ((Message, RectF) -> Unit)? = null
    var onMessageLongPress: ((Message, RectF) -> Unit)? = null
    var onCountChanged: ((Int) -> Unit)? = null
    var onMessageAction: ((Message, MessageAction) -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null
    val messageCount
        // Возвращаем число сообщений из компактного указателя. Оно отражает всю историю, даже
        // если содержимое только нужных строк прочитано в память.
        get() = index.rows.size

    val metrics
        // Возвращаем измерения текущей сцены. Их читает диагностика прокрутки и выбора частоты
        // дисплея.
        get() = scene.metrics

    val loadedCount
        // Читаем число уникальных сообщений из окна порций. До первого окна загруженных строк ещё
        // нет.
        get() = pages?.loadedCount ?: 0

    // Разрешаем собственную отрисовку и системную доступность области истории. Кнопки
    // нарисованных строк смогут получить фокус и координаты.
    init {
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = true
    }

    // Передаём номер чата, хранилище и сохранённую точку общему открытию. Получатель результата
    // узнает, когда указатель уже готов или произошла ошибка.
    fun open(
        chatID: ChatID,
        repository: ChatRepository,
        anchor: ReadingAnchor?,
        completion: (Result<Unit>) -> Unit,
    ) =
        openHistory(chatID, repository, anchor, completion)

    // Передаём изменение сообщений владельцу истории. Он выбирает между правкой карточки на месте
    // и чтением нового указателя.
    fun apply(delta: MessageDelta) =
        applyHistoryChange(delta)

    // Просим вычислить место чтения в заданной высоте окна. При изменении размеров это позволяет
    // сначала снять точку по старому окну.
    fun readingAnchor(viewport: Int = height): ReadingAnchor =
        captureReadingPosition(viewport)

    // Передаём новые перекрытия верхней и нижней панелей. Общее восстановление сохраняет текущий
    // абзац, когда меняется полезная область.
    fun updateOverlayInsets(top: Double, bottom: Double) =
        applyOverlayInsets(top, bottom)

    val gestureViewport: RectF
        // Возвращаем область истории между панелями. Жесты и видимые действия используют эту
        // рамку, чтобы не принимать касания в закрытой части.
        get() = RectF(0f, overlayTop.toFloat(), width.toFloat(), (height - overlayBottom).toFloat())

    // Снимаем чтение по прежней высоте и проверяем ширину и шрифт. При изменении переноса
    // отменяем старую подготовку и возвращаем строку уже в новом размере.
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val anchor = readingAnchor(oldh)
        val font = desiredFontSize()
        val reflow = w != layoutWidth || font != layoutFontSize
        layoutWidth = w
        layoutFontSize = font
        if (reflow) {
            preparedRowWindow = null
            inFlight.values.forEach { it.cancel() }
            inFlight.clear()
            pendingHeights.clear()
            scene.advanceGeneration()
            cache.clear()
            pendingAnchor = anchor
        }
        setGeometry(anchor)
        schedule()
    }

    // При смене системного размера текста пересчитываем подготовленные строки и геометрию.
    // Сохраняем прежнее место, иначе увеличение шрифта сдвинуло бы абзац.
    override fun onConfigurationChanged(configuration: android.content.res.Configuration) {
        super.onConfigurationChanged(configuration)
        val font = desiredFontSize()
        if (font != layoutFontSize) {
            val anchor = readingAnchor()
            layoutFontSize = font
            preparedRowWindow = null
            inFlight.values.forEach { it.cancel() }
            inFlight.clear()
            pendingHeights.clear()
            scene.advanceGeneration()
            cache.clear()
            pendingAnchor = anchor
            setGeometry(anchor)
            schedule()
        }
    }

    // Объединяем события до ближайшего кадра через системный вызов. Закрытая лента или
    // подготовка, которая уже ждёт кадр, второй проход не назначают.
    internal fun schedule() {
        if (queued || closed) return
        queued = true
        // Применяем положение после ввода и до отрисовки этого кадра.
        postOnAnimation {
            if (queued && !closed) {
                queued = false
                render()
            }
        }
    }

    // Подготавливаем видимые строки через общий расчёт кадра. Это отдельный путь от
    // непосредственного рисования сцены на холсте.
    private fun render() =
        renderVisibleRows()

    // Рисуем готовую сцену и заголовки дня. После закрытия игнорируем поздний вызов холста, чтобы
    // не обратиться к снятым ресурсам.
    override fun onDraw(c: Canvas) {
        if (!closed) {
            scene.draw(c)
            days.draw(c)
        }
    }

    // Передаём системе доступные действия нарисованных строк и проигрывателя. В обычном дереве
    // только реальные дочерние элементы были бы видны.
    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = ax

    private val tapPosition = PointF()
    private var hasTapPosition = false

    // Передаём касание прокрутке, а распознанное короткое нажатие сохраняем для системного
    // вызова. При отпускании или отмене завершаем измерение жеста.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (handleScrollTouch(event)) {
            tapPosition.set(event.x, event.y)
            hasTapPosition = true
            performClick()
        }
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) finishScrollTouch()
        return true
    }

    // Снимаем сохранённую точку и пробуем действие строки после обычного системного нажатия. Без
    // новой точки старое касание не используется повторно.
    override fun performClick(): Boolean {
        val hasTap = hasTapPosition
        hasTapPosition = false
        val handledByView = super.performClick()
        val handledByRow = hasTap && performRowTap(tapPosition.x, tapPosition.y)
        return handledByView || handledByRow
    }

    // Просим продолжить активную инерцию системной прокрутки. Она передвинет видимую сцену и сама
    // запросит следующий кадр при необходимости.
    override fun computeScroll() =
        advanceFling()

    // Закрываем историю при снятии её области с окна. Фоновые чтения и проигрыватель больше не
    // нужны исчезнувшей ленте.
    override fun onDetachedFromWindow() {
        close()
        super.onDetachedFromWindow()
    }

    // Сбрасываем запас готовых строк и уменьшаем предел памяти рисунков. Данные остаются в
    // хранилище и снова читаются при возврате.
    fun trimMemory() {
        preparedRowWindow = null
        cache.limit = 16 * 1024 * 1024
        cache.trim()
    }

    // Один раз прекращаем ожидание открытия и закрываем галерею, кадры, запросы и инерцию.
    // Проигрыватель останавливаем только при владении, чтобы скрытие ленты не прервало чужой
    // показ.
    fun close() {
        mediaExport.close()
        onStudioGenerationAction = null
        if (closed) return
        closed = true
        finishInitialFrame(Result.failure(RepoException(RepoError.CANCELLED)))
        finishOpening(Result.failure(RepoException(RepoError.CANCELLED)))
        val gallery = mediaGallery
        mediaGallery = null
        gallery?.close(returnToChat = false)
        displayPolicy?.close()
        val viewer = photoViewer
        photoViewer = null
        viewer?.close()
        studioPreview?.close()
        if (playback?.presentationOwner === playbackOwner) playerSurface?.detachPlayer()
        (playerSurface?.parent as? ViewGroup)?.removeView(playerSurface)
        playback?.let { c ->
            if (c.onChange === ownedPlayerChange) c.onChange = null
            if (c.onDetachOutput === ownedPlayerDetach) c.onDetachOutput = null
            if (c.presentationOwner === playbackOwner) {
                c.setInlineVisible(false)
                c.stop()
            }
        }
        ownedPlayerChange = null
        ownedPlayerDetach = null
        nodes = emptyList()
        index = HistoryIndex()
        onMessageTap = null
        onMessageLongPress = null
        onMessageAction = null
        generation++
        pages?.close()
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
        pendingHeights.clear()
        worker.close()
        buildPool.shutdownNow()
        cache.clear()
        loaded = emptyMap()
        scene.close()
        viewerMedia?.releaseIdleImages()
        days.close()
        scroller.abortAnimation()
        velocity?.recycle()
        velocity = null
    }
}
