package dev.kirill.kandinsky.chat.rendering

import android.graphics.*
import android.view.*
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.media.*
import kotlin.math.*

internal fun ChatScrollHost.handleScrollTouch(event: MotionEvent): Boolean {
    gestures.onTouchEvent(event)
    when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            scroller.abortAnimation()
            velocity?.recycle()
            velocity = VelocityTracker.obtain()
            velocity?.addMovement(event)
            lastY = event.y
            downY = event.y
            dragging = false
            longPressHandled = false
            parent.requestDisallowInterceptTouchEvent(true)
        }
        MotionEvent.ACTION_MOVE -> {
            velocity?.addMovement(event)
            if (abs(event.y - downY) > ViewConfiguration.get(context).scaledTouchSlop)
                dragging = true
            if (dragging) {
                pendingAnchor = null
                offset =
                    (offset + lastY - event.y).coerceIn(
                        0.0,
                        max(0.0, index.totalHeight - height),
                    )
                schedule()
            }
            lastY = event.y
        }
        MotionEvent.ACTION_UP -> {
            velocity?.addMovement(event)
            velocity?.computeCurrentVelocity(
                1000,
                ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat(),
            )
            if (dragging) {
                val speed = -(velocity?.yVelocity ?: 0f).toInt()
                lastFlingY = 0
                scroller.fling(0, 0, 0, speed, 0, 0, Int.MIN_VALUE / 2, Int.MAX_VALUE / 2)
                postInvalidateOnAnimation()
            } else if (!longPressHandled && !scene.performLoadAction(event.x, event.y))
                hit(event.x, event.y)?.let { (row, frame) ->
                    if (onSelectionTap?.invoke(row) != true) {
                        val prepared = cache.value(layoutKey(row))
                        val x = event.x - frame.left
                        val y = event.y - frame.top
                        val card = prepared?.generationCard
                        if (card != null) {
                            val retry = card.retryCells.firstOrNull { it.frame.contains(x, y) }
                            val control = card.controls.firstOrNull { it.frame.contains(x, y) }
                            val selected =
                                if (card.isBatch)
                                    prepared.mediaItemRects.indexOfFirst { it.contains(x, y) }
                                else -1
                            if (retry != null) onStudioRetrySlot?.invoke(row.id, retry.slot)
                            else if (control != null)
                                onStudioGenerationAction?.invoke(row.id, control.action)
                            else if (selected >= 0) activateMedia(row, frame, selected)
                            return@let
                        }
                        val button =
                            prepared?.mediaActions?.firstOrNull { it.frame.contains(x, y) }
                        if (button != null) {
                            val hit =
                                RectF(button.frame).apply { offset(frame.left, frame.top) }
                            performMediaAction(button.action, row, hit)
                        } else {
                            if (
                                onStudioResultDelete != null &&
                                    row.direction == MessageDirection.OUTGOING &&
                                    row.kind == MessageKind.TEXT
                            ) {
                                onMessageLongPress?.invoke(row, frame)
                                return@let
                            }
                            val selected =
                                prepared
                                    ?.mediaItemRects
                                    ?.indexOfFirst { it.contains(x, y) }
                                    ?.coerceAtLeast(0) ?: 0
                            activateMedia(row, frame, selected)
                        }
                    }
                }
            dragging = false
            velocity?.recycle()
            velocity = null
            updateDisplayHint()
            onAnchor?.invoke(readingAnchor())
        }
        MotionEvent.ACTION_CANCEL -> {
            dragging = false
            velocity?.recycle()
            velocity = null
            updateDisplayHint()
            onAnchor?.invoke(readingAnchor())
        }
    }
    return true
}

internal fun ChatScrollHost.advanceFling() {
    if (scroller.computeScrollOffset()) {
        pendingAnchor = null
        val delta = scroller.currY - lastFlingY
        lastFlingY = scroller.currY
        val proposed = offset + delta
        offset = proposed.coerceIn(0.0, max(0.0, index.totalHeight - height))
        if (offset != proposed) scroller.abortAnimation()
        queued = false
        renderVisibleRows()
        postInvalidateOnAnimation()
        if (scroller.isFinished) onAnchor?.invoke(readingAnchor())
    }
}

internal fun ChatScrollHost.hit(x: Float, y: Float): Pair<Message, RectF>? {
    val id = index.anchorAt(offset + y).messageID
    val row = loaded[id] ?: return null
    val top = index.top(row.id) ?: return null
    val resource = cache.value(layoutKey(row)) ?: return null
    val left = rowLeft(row, resource)
    val r =
        RectF(
            left,
            (top - offset).toFloat(),
            left + resource.width,
            (top - offset + resource.height).toFloat(),
        )
    return if (r.contains(x, y)) row to r else null
}
