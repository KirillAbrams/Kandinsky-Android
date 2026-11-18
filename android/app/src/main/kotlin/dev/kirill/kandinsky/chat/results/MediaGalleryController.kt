package dev.kirill.kandinsky.chat.results

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.media.InlinePlayerSurface
import dev.kirill.kandinsky.chat.media.MediaCoordinator
import dev.kirill.kandinsky.chat.media.MediaNavigationDrawable
import dev.kirill.kandinsky.chat.media.MediaStore
import dev.kirill.kandinsky.chat.media.PlaybackTarget
import dev.kirill.kandinsky.chat.media.PreparedImage
import dev.kirill.kandinsky.chat.media.prepareOpaqueMedia
import dev.kirill.kandinsky.chat.storage.ChatRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

class MediaGalleryController(
    private val activity: Activity,
    seed: Message,
    private val repository: ChatRepository,
    private val media: MediaStore,
    private val playback: MediaCoordinator,
    private val surface: InlinePlayerSurface,
    private var seedPreview: PreparedImage? = null,
    attachmentIndex: Int = 0,
    private val onReturn: () -> Unit,
) {
    var onDeleteResult: ((MessageID, String) -> Unit)? = null

    private class Page(
        var entry: MediaGalleryEntry,
        val photo: PhotoViewerController,
        val view: View,
    ) {
        var slot = 1
    }

    private val model = MediaGalleryWindow(seed, attachmentIndex)
    private val seedID = model.selected?.id
    private val d = activity.resources.displayMetrics.density
    private val owner = Any()
    private val previousOwner = playback.presentationOwner
    private val pages = linkedMapOf<String, Page>()
    private val cancelled = mutableSetOf<String>()
    private var dialog: Dialog? = null
    private var request: Cancellation? = null
    private var pendingPage: HistoryPage? = null
    private var lastRequestID: String? = null
    private var observer: UUID? = null
    private var activeVideo: MediaGalleryEntry? = null
    private val studioPlayback
        get() = onDeleteResult != null

    private var closing = false
    private var cleaned = false
    private var appeared = false
    private var startingVideo = false
    private var controlsHidden = false
    private val root =
        FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            clipChildren = true
        }
    private val pager = Pager()
    private val title =
        TextView(activity).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        }
    private val caption =
        TextView(activity).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            maxHeight = (180 * d).toInt()
            setPadding(0, 0, 0, 0)
            movementMethod = android.text.method.ScrollingMovementMethod()
            isVerticalScrollBarEnabled = false
            setTextIsSelectable(true)
        }
    private val topShade =
        View(activity).apply {
            background =
                GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0x99000000.toInt(), Color.TRANSPARENT),
                )
        }
    private val bottomShade =
        View(activity).apply {
            background =
                GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.TRANSPARENT, 0xC7000000.toInt()),
                )
        }
    private val closeButton =
        ImageButton(activity).apply {
            setImageDrawable(MediaNavigationDrawable())
            setPadding(0, 0, 0, 0)
            setColorFilter(Color.WHITE)
            background = null
            setOnClickListener { close() }
        }
    private val moreButton =
        TextView(activity).apply {
            text = "⋯"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = "Действия с результатом"
            setOnClickListener { confirmResultRemoval() }
        }
    private val retry =
        ImageButton(activity).apply {
            setImageResource(android.R.drawable.stat_sys_download)
            setColorFilter(Color.WHITE)
            background =
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x66000000)
                }
            visibility = View.GONE
            contentDescription = "Повторить загрузку"
            setOnClickListener { retryVideo() }
        }
    private val date = SimpleDateFormat("d MMMM, HH:mm", Locale("ru", "RU"))
    private var topInset = 0
    private var bottomInset = 0

    private fun id(view: View, name: String) {
        view.accessibilityDelegate =
            object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfo,
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.viewIdResourceName = name
                }
            }
    }

    fun show() {
        if (dialog != null || closing) return
        id(root, "media.viewer")
        id(pager, "viewer.pages")
        id(title, "viewer.item")
        id(caption, "viewer.caption")
        id(retry, "media.retry")
        id(moreButton, "viewer.result.actions")
        root.addView(pager, FrameLayout.LayoutParams(-1, -1))
        root.addView(topShade, FrameLayout.LayoutParams(-1, (100 * d).toInt(), Gravity.TOP))
        root.addView(bottomShade, FrameLayout.LayoutParams(-1, (212 * d).toInt(), Gravity.BOTTOM))
        root.addView(
            title,
            FrameLayout.LayoutParams(-1, (32 * d).toInt(), Gravity.TOP).apply {
                leftMargin = (16 * d).toInt()
                rightMargin = (84 * d).toInt()
            },
        )
        root.addView(
            caption,
            FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                leftMargin = (16 * d).toInt()
                rightMargin = (16 * d).toInt()
            },
        )
        root.addView(
            closeButton,
            FrameLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt(), Gravity.TOP or Gravity.END)
                .apply { rightMargin = (12 * d).toInt() },
        )
        root.addView(
            moreButton,
            FrameLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt(), Gravity.TOP or Gravity.END)
                .apply { rightMargin = (60 * d).toInt() },
        )
        root.addView(
            retry,
            FrameLayout.LayoutParams((48 * d).toInt(), (48 * d).toInt(), Gravity.CENTER),
        )
        val next =
            object : Dialog(activity, android.R.style.Theme_Material_NoActionBar_Fullscreen) {
                override fun dismiss() {
                    if (dialog === this) close() else super.dismiss()
                }
            }
        dialog = next
        next.setOnDismissListener {
            if (dialog === next) {
                dialog = null
                finishClose(true)
            }
        }
        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION") val top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION") val bottom = insets.systemWindowInsetBottom
            topInset = top
            bottomInset = bottom
            layoutControls()
            insets
        }
        observer = playback.observeChanges { playerChanged() }
        reconcile()
        next.window?.prepareOpaqueMedia()
        next.setContentView(root)
        next.show()
        next.window?.setLayout(-1, -1)
        root.requestApplyInsets()
        appeared = true
        activate()
        loadNeighbours()
    }

    private fun layoutControls() {
        (closeButton.layoutParams as FrameLayout.LayoutParams)
            .apply { topMargin = topInset + (4 * d).toInt() }
            .also { closeButton.layoutParams = it }
        (moreButton.layoutParams as FrameLayout.LayoutParams)
            .apply { topMargin = topInset + (4 * d).toInt() }
            .also { moreButton.layoutParams = it }
        (title.layoutParams as FrameLayout.LayoutParams)
            .apply {
                topMargin = topInset + (10 * d).toInt()
                rightMargin = ((if (moreButton.visibility == View.VISIBLE) 118 else 84) * d).toInt()
            }
            .also { title.layoutParams = it }
        val bottom = bottomInset + ((if (model.selected?.isVideo == true) 68 else 24) * d).toInt()
        (caption.layoutParams as FrameLayout.LayoutParams)
            .apply { bottomMargin = bottom }
            .also { caption.layoutParams = it }
        (bottomShade.layoutParams as FrameLayout.LayoutParams)
            .apply { bottomMargin = maxOf(0, bottom - (8 * d).toInt()) }
            .also { bottomShade.layoutParams = it }
        (topShade.layoutParams as FrameLayout.LayoutParams)
            .apply { height = topInset + (70 * d).toInt() }
            .also { topShade.layoutParams = it }
    }

    private fun reconcile() {
        if (closing) return
        val selected =
            model.selected
                ?: run {
                    close()
                    return
                }
        updateResultMenu()
        val wanted = model.neighbours
        val byID = wanted.associateBy { it.id }
        for ((key, page) in pages.toMap()) if (byID[key]?.hasSameMedia(page.entry) != true) {
            if (page.photo.downloadPausedByUser) cancelled.add(key)
            if (surface.parent === page.view) retireVideo()
            page.photo.close()
            pager.removeView(page.view)
            pages.remove(key)
        }
        for (entry in wanted) {
            val existing = pages[entry.id]
            if (existing != null) {
                existing.entry = entry
                continue
            }
            val initial = if (entry.id == seedID) seedPreview.also { seedPreview = null } else null
            val photo =
                PhotoViewerController(activity, entry.attachment, media, initial, embedded = true)
            val view = photo.pageView()
            id(view, "viewer.page." + entry.id)
            val page = Page(entry, photo, view)
            pages[entry.id] = page
            pager.addView(view)
            photo.onRequestDismiss = { close() }
            photo.onToggleControls = { toggleControls() }
            photo.onPreviewReady = {
                if (
                    !closing &&
                        model.selected?.id == entry.id &&
                        entry.isVideo &&
                        !surface.hasVideoFrame
                )
                    surface.setPoster(pages[entry.id]?.photo?.retainedPreview())
            }
            if (entry.id in cancelled) photo.pauseOriginal()
        }
        cancelled.retainAll(model.entries.map { it.id }.toSet())
        model.selectedIndex?.let { selectedIndex ->
            for (page in pages.values) {
                val index = model.entries.indexOfFirst { it.id == page.entry.id }
                if (index >= 0) page.slot = index - selectedIndex + 1
            }
        }
        pager.relayoutAndCentre()
        caption.text = selected.caption
        caption.visibility =
            if (controlsHidden || selected.caption.isEmpty()) View.GONE else View.VISIBLE
        title.text =
            (if (selected.isVideo) "Видео" else "Фото") +
                " · " +
                date.format(Date(selected.savedAtMs))
        closeButton.contentDescription =
            if (selected.isVideo) "Вернуть в чат" else "Закрыть фотографию"
        id(closeButton, if (selected.isVideo) "player.inline" else "photo.close")
        layoutControls()
        activate()
    }

    // Освобождаем оригинал предыдущей страницы до загрузки следующего, чтобы не держать оба в
    // памяти.
    private fun activate() {
        if (!appeared || closing) return
        val selected = model.selected ?: return
        // Прежний оригинал освобождает ресурсы до получения памяти следующей страницей
        for (page in pages.values) if (page.entry.id != selected.id || selected.isVideo)
            page.photo.setPageActive(false)
        if (!selected.isVideo) pages[selected.id]?.photo?.setPageActive(true)
        if (!selected.isVideo) {
            retireVideo()
            retry.visibility = View.GONE
            return
        }
        val page = pages[selected.id] ?: return
        if (activeVideo?.id == selected.id && activeVideo?.hasSameMedia(selected) == true) return
        val reuse =
            !studioPlayback &&
                playback.currentMessageID == selected.messageID &&
                playback.currentTarget?.attachment?.relativePath == selected.attachment.relativePath
        if (reuse) {
            surface.beginPresentationMove()
            (surface.parent as? ViewGroup)?.removeView(surface)
            activeVideo = null
        } else retireVideo()
        activeVideo = selected
        playback.presentationOwner = owner
        surface.circular(false)
        surface.aspect(
            if (selected.attachment.orientation >= 5) selected.attachment.rawHeight
            else selected.attachment.rawWidth,
            if (selected.attachment.orientation >= 5) selected.attachment.rawWidth
            else selected.attachment.rawHeight,
        )
        (page.view as ViewGroup).addView(surface, FrameLayout.LayoutParams(-1, -1))
        if (!reuse || !surface.hasVideoFrame) surface.setPoster(page.photo.retainedPreview())
        else surface.setPoster(null)
        if (!studioPlayback && selected.id in cancelled) {
            surface.visibility = View.GONE
            retry.visibility = View.VISIBLE
            return
        }
        startingVideo = true
        if (!reuse)
            playback.play(
                PlaybackTarget(
                    selected.attachment,
                    selected.messageID,
                    selected.kind,
                    loopPreview = studioPlayback,
                )
            )
        playback.setInlineVisible(false)
        surface.visibility = View.VISIBLE
        surface.update()
        surface.finishPresentationMove()
        startingVideo = false
        playerChanged()
    }

    private fun retireVideo(stop: Boolean = true) {
        val had = activeVideo != null
        activeVideo = null
        surface.detachPlayer()
        (surface.parent as? ViewGroup)?.removeView(surface)
        if (had && stop && playback.presentationOwner === owner) playback.stop()
    }

    fun resumeAutoplay() {
        if (!studioPlayback || closing || activeVideo == null || playback.currentMessageID != null)
            return
        activeVideo = null
        activate()
    }

    private fun playerChanged() {
        if (closing) return
        val selected = model.selected ?: return
        if (!selected.isVideo || activeVideo?.id != selected.id) return
        surface.update()
        val stopped = !startingVideo && playback.currentMessageID == null
        val failed = playback.lastError != null || selected.id in cancelled || stopped
        retry.visibility = if (failed && !studioPlayback) View.VISIBLE else View.GONE
        surface.visibility = if (failed) View.GONE else View.VISIBLE
        if (!failed && !surface.hasVideoFrame)
            surface.setPoster(pages[selected.id]?.photo?.retainedPreview())
    }

    private fun retryVideo() {
        val selected = model.selected ?: return
        if (!selected.isVideo) return
        cancelled.remove(selected.id)
        retireVideo()
        activate()
    }

    private fun canDelete(entry: MediaGalleryEntry) =
        onDeleteResult != null &&
            entry.isIncoming &&
            entry.attachment.relativePath.startsWith("remote/")

    private fun updateResultMenu() {
        moreButton.visibility =
            if (!controlsHidden && model.selected?.let(::canDelete) == true) View.VISIBLE
            else View.GONE
    }

    private fun confirmResultRemoval() {
        val entry = model.selected?.takeIf(::canDelete) ?: return
        android.app.AlertDialog.Builder(activity)
            .setTitle("Действия с результатом")
            .setItems(arrayOf("Удалить результат")) { _, _ ->
                onDeleteResult?.invoke(entry.messageID, entry.attachment.thumbnailKey)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun toggleControls() {
        controlsHidden = !controlsHidden
        updateResultMenu()
        val v = if (controlsHidden) View.GONE else View.VISIBLE
        title.visibility = v
        closeButton.visibility = v
        topShade.visibility = v
        bottomShade.visibility =
            if (controlsHidden || caption.text.isEmpty()) View.GONE else View.VISIBLE
        caption.visibility =
            if (controlsHidden || caption.text.isEmpty()) View.GONE else View.VISIBLE
    }

    private var requestGeneration = 0

    // Запрашивает ограниченную визуальную историю вокруг выбранного элемента при нехватке соседей.
    private fun loadNeighbours() {
        val selected = model.selected ?: return
        if (closing || request != null || !model.needsPage || lastRequestID == selected.id) return
        lastRequestID = selected.id
        val generation = ++requestGeneration
        request =
            repository.history(
                PageRequest(
                    selected.chatID,
                    selected.order,
                    PageDirection.AROUND,
                    32,
                    visualOnly = true,
                )
            ) { result ->
                if (closing || generation != requestGeneration) return@history
                request = null
                result.fold(
                    { page ->
                        if (pager.busy) pendingPage = page
                        else {
                            model.merge(page)
                            reconcile()
                        }
                    },
                    { lastRequestID = null },
                )
            }
    }

    private fun settled() {
        if (closing || pager.stride <= 0) return
        val slot = (pager.scrollX.toDouble() / pager.stride).roundToInt()
        pages.values.firstOrNull { it.slot == slot }?.let { model.select(it.entry.id) }
        pendingPage?.let {
            pendingPage = null
            model.merge(it)
        }
        reconcile()
        loadNeighbours()
    }

    private fun resumeGesture() {
        if (closing || pager.stride <= 0) return
        val offset = pager.scrollX
        val slot = (offset.toDouble() / pager.stride).roundToInt()
        val target = pages.values.firstOrNull { it.slot == slot } ?: return
        if (target.entry.id == model.selected?.id) return
        val shift = (slot - 1) * pager.stride
        if (!model.select(target.entry.id)) return
        pendingPage?.let {
            pendingPage = null
            model.merge(it)
        }
        reconcile()
        pager.scrollTo(offset - shift, 0)
        loadNeighbours()
    }

    fun apply(delta: MessageDelta) {
        if (closing || delta.chat.id != model.chatID) return
        requestGeneration++
        request?.cancel()
        request = null
        pendingPage = null
        lastRequestID = null
        model.apply(delta)
        if (model.selected == null) {
            close()
            return
        }
        if (!pager.busy) {
            reconcile()
            loadNeighbours()
        }
    }

    fun close(returnToChat: Boolean = true) {
        if (closing) return
        closing = true
        request?.cancel()
        request = null
        pendingPage = null
        pager.cancelMotion()
        val old = dialog
        dialog = null
        old?.dismiss()
        finishClose(returnToChat)
    }

    private fun finishClose(returnToChat: Boolean) {
        if (cleaned) return
        cleaned = true
        closing = true
        request?.cancel()
        request = null
        pager.cancelMotion()
        observer?.let { playback.removeChangeObserver(it) }
        observer = null
        val returning =
            returnToChat &&
                !studioPlayback &&
                model.selected?.isVideo == true &&
                playback.currentMessageID == model.selected?.messageID &&
                playback.presentationOwner === owner
        if (returning) {
            surface.beginPresentationMove()
            (surface.parent as? ViewGroup)?.removeView(surface)
            activeVideo = null
        } else retireVideo()
        surface.visibility = View.VISIBLE
        if (playback.presentationOwner === owner) playback.presentationOwner = previousOwner
        for (page in pages.values) {
            page.photo.close()
            pager.removeView(page.view)
        }
        pages.clear()
        seedPreview?.close()
        seedPreview = null
        media.releaseIdleImages()
        onReturn()
        surface.finishPresentationMove()
    }

    private inner class Pager : ViewGroup(activity) {
        private val scroller = OverScroller(activity)
        private val config = ViewConfiguration.get(activity)
        private var velocity: VelocityTracker? = null
        private var dragging = false
        private var settling = false
        private var multi = false
        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var oldStride = 0
        val stride
            get() = width + (20 * d).toInt()

        val busy
            get() = dragging || !scroller.isFinished || width > 0 && abs(scrollX - stride) > 1

        init {
            clipChildren = true
            setWillNotDraw(false)
        }

        // Точно измеряет каждую удерживаемую страницу по ширине и высоте этой области
        override fun onMeasure(w: Int, h: Int) {
            setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h))
            for (page in pages.values) page.view.measure(
                MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY),
            )
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val fraction = if (oldStride > 0) scrollX.toDouble() / oldStride else 1.0
            layoutPages()
            scrollTo((fraction * stride).roundToInt(), 0)
            oldStride = stride
        }

        private fun layoutPages() {
            for (page in pages.values) {
                val x = page.slot * stride
                page.view.layout(x, 0, x + width, height)
            }
        }

        fun relayoutAndCentre() {
            if (width > 0) {
                for (page in pages.values) page.view.measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
                )
                layoutPages()
                scrollTo(stride, 0)
                oldStride = stride
            }
            requestLayout()
            invalidate()
        }

        fun cancelMotion() {
            scroller.abortAnimation()
            dragging = false
            settling = false
            velocity?.recycle()
            velocity = null
        }

        // Не позволяет перелистнуть за первый или последний элемент модели
        private fun bounds(): IntRange {
            val selected = model.selectedIndex ?: 0
            return (if (selected > 0) 0 else stride)..(if (selected + 1 < model.entries.size)
                    stride * 2
                else stride)
        }

        private fun begin(e: MotionEvent) {
            downX = e.x
            downY = e.y
            lastX = e.x
            multi = false
            velocity?.recycle()
            velocity = VelocityTracker.obtain()
            velocity?.addMovement(e)
            dragging = !scroller.isFinished
            scroller.abortAnimation()
            settling = false
            resumeGesture()
        }

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    begin(e)
                    return dragging
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    multi = true
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (
                        !multi &&
                            e.pointerCount == 1 &&
                            pages[model.selected?.id]?.photo?.pageIsZoomed != true &&
                            abs(e.x - downX) > config.scaledTouchSlop &&
                            abs(e.x - downX) > abs(e.y - downY) * 1.1f
                    ) {
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) {
                        velocity?.recycle()
                        velocity = null
                    }
                }
            }
            return false
        }

        // После отмены возвращается к центру и запускает короткое завершение движения.
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (velocity == null) begin(e)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    velocity?.addMovement(e)
                    val next = (scrollX + lastX - e.x).roundToInt().coerceIn(bounds())
                    scrollTo(next, 0)
                    lastX = e.x
                    return true
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    velocity?.addMovement(e)
                    velocity?.computeCurrentVelocity(
                        1000,
                        config.scaledMaximumFlingVelocity.toFloat(),
                    )
                    val speed = velocity?.xVelocity ?: 0f
                    var slot =
                        if (e.actionMasked == MotionEvent.ACTION_CANCEL) 1
                        else if (abs(speed) > config.scaledMinimumFlingVelocity) {
                            if (speed < 0) ceil(scrollX.toDouble() / stride).toInt()
                            else floor(scrollX.toDouble() / stride).toInt()
                        } else (scrollX.toDouble() / stride).roundToInt()
                    slot = slot.coerceIn(0, 2)
                    val target = (slot * stride).coerceIn(bounds())
                    dragging = false
                    velocity?.recycle()
                    velocity = null
                    scroller.startScroll(scrollX, 0, target - scrollX, 0, 180)
                    settling = true
                    postInvalidateOnAnimation()
                    return true
                }
            }
            return true
        }

        override fun computeScroll() {
            if (scroller.computeScrollOffset()) {
                scrollTo(scroller.currX, 0)
                postInvalidateOnAnimation()
            } else if (settling) {
                settling = false
                settled()
            }
        }
    }
}
