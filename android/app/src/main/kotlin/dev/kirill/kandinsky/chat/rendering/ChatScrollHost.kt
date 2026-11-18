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

    fun prepareInitialFrame(completion: (Result<Unit>) -> Unit) =
        awaitInitialFrame(completion)

    internal var pendingAnchor: ReadingAnchor? = null
    internal var offset = 0.0
    internal val mediaPreparation = MediaPreparationWindow()
    internal val d = resources.displayMetrics.density
    internal var layoutWidth = 0
    internal var layoutFontSize = desiredFontSize()

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

    internal fun isHostForeground(): Boolean = (context as? ForegroundHost)?.isForeground == true

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
                override fun onDown(e: MotionEvent) = true

                // Отмечает обработанный жест, чтобы отпускание пальца не открыло медиа ещё раз.
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

    fun configurePlayback(coordinator: MediaCoordinator, media: MediaStore) =
        attachPlayback(coordinator, media)

    fun previewStudioVideo(
        attachment: Attachment,
        onEnhance: (() -> Unit)? = null,
        onClose: () -> Unit,
    ) =
        presentStudioPreview(attachment, onEnhance, onClose)

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        updateInlinePlayer()
    }

    fun setMediaVisible(visible: Boolean) =
        updateMediaVisibility(visible)

    fun pauseForNavigation() =
        pauseMediaForNavigation()

    fun setMovingMediaVisible(visible: Boolean) =
        updateMovingMediaVisibility(visible)

    fun configureMedia(store: dev.kirill.kandinsky.chat.media.MediaStore) =
        attachMediaStore(store)

    var onAnchor: ((ReadingAnchor) -> Unit)? = null
    var onMessageTap: ((Message, RectF) -> Unit)? = null
    var onMessageLongPress: ((Message, RectF) -> Unit)? = null
    var onCountChanged: ((Int) -> Unit)? = null
    var onMessageAction: ((Message, MessageAction) -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null
    val messageCount
        get() = index.rows.size

    val metrics
        get() = scene.metrics

    val loadedCount
        get() = pages?.loadedCount ?: 0

    init {
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = true
    }

    fun open(
        chatID: ChatID,
        repository: ChatRepository,
        anchor: ReadingAnchor?,
        completion: (Result<Unit>) -> Unit,
    ) =
        openHistory(chatID, repository, anchor, completion)

    fun apply(delta: MessageDelta) =
        applyHistoryChange(delta)

    fun readingAnchor(viewport: Int = height): ReadingAnchor =
        captureReadingPosition(viewport)

    fun updateOverlayInsets(top: Double, bottom: Double) =
        applyOverlayInsets(top, bottom)

    val gestureViewport: RectF
        get() = RectF(0f, overlayTop.toFloat(), width.toFloat(), (height - overlayBottom).toFloat())

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

    private fun render() =
        renderVisibleRows()

    // После закрытия экрана не обращается к прежнему содержимому.
    override fun onDraw(c: Canvas) {
        if (!closed) {
            scene.draw(c)
            days.draw(c)
        }
    }

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = ax

    private val tapPosition = PointF()
    private var hasTapPosition = false

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

    override fun performClick(): Boolean {
        val hasTap = hasTapPosition
        hasTapPosition = false
        val handledByView = super.performClick()
        val handledByRow = hasTap && performRowTap(tapPosition.x, tapPosition.y)
        return handledByView || handledByRow
    }

    override fun computeScroll() =
        advanceFling()

    override fun onDetachedFromWindow() {
        close()
        super.onDetachedFromWindow()
    }

    // Сами сообщения остаются в истории и могут быть подготовлены снова при необходимости.
    fun trimMemory() {
        preparedRowWindow = null
        cache.limit = 16 * 1024 * 1024
        cache.trim()
    }

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
