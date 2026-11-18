package dev.kirill.kandinsky.chat.rendering

import android.graphics.*
import android.view.*
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.media.*
import kotlin.math.*

// Для подготовленного текстового сообщения дополнительно запоминает строку, чтобы изменение
// переноса
// текста не уводило чтение.
internal fun ChatScrollHost.captureReadingPosition(viewport: Int = height): ReadingAnchor {
    pendingAnchor?.let {
        return it
    }
    var value =
        index
            .anchorAt(max(0.0, offset + overlayTop))
            .copy(followsBottom = index.totalHeight - offset - viewport < 24 * d)
    val id = value.messageID
    val row = loaded[id]
    if (id != null && row?.kind == MessageKind.TEXT) {
        val resource = cache.value(layoutKey(row))
        val top = index.top(id)
        if (resource != null && top != null)
            resource.lineAnchor((offset + overlayTop - top).toFloat())?.let {
                value =
                    value.copy(textUTF16Offset = it.first, screenDelta = it.second.toDouble())
            }
    }
    return value
}

private fun ChatScrollHost.resolved(anchor: ReadingAnchor): Double {
    if (anchor.followsBottom) return max(0.0, index.totalHeight - height)
    val id = anchor.messageID
    val row = loaded[id]
    if (id != null && row != null && anchor.textUTF16Offset != null) {
        val y = cache.value(layoutKey(row))?.yForUTF16(anchor.textUTF16Offset)
        val top = index.top(id)
        if (y != null && top != null)
            return (top + y - anchor.screenDelta - overlayTop).coerceIn(
                0.0,
                max(0.0, index.totalHeight - height),
            )
    }
    return (index.offset(anchor, max(0.0, height - overlayTop)) - overlayTop).coerceIn(
        0.0,
        max(0.0, index.totalHeight - height),
    )
}

internal fun ChatScrollHost.measuredRowHeight(resource: PreparedText) = (resource.height + 6 * d).toDouble()

internal fun ChatScrollHost.restoreCachedRowHeights(anchor: ReadingAnchor): Boolean {
    var changed = false
    for (row in loaded.values) {
        val indexedHeight = index.height(row.id) ?: continue
        val resource = cache.value(layoutKey(row)) ?: continue
        val measuredHeight = measuredRowHeight(resource)
        if (abs(indexedHeight - measuredHeight) <= 0.5) continue
        index.updateHeight(row.id, measuredHeight, anchor, height.toDouble())
        changed = true
    }
    return changed
}

// Учитывает верхнюю и нижнюю панели, чтобы содержимое не смещалось из выбранной точки.
internal fun ChatScrollHost.setGeometry(anchor: ReadingAnchor) {
    index.configureInsets(overlayTop + 8 * d, overlayBottom + 8 * d)
    val previousOffset = offset
    offset = resolved(anchor)
    mediaPreparation.rebase(offset - previousOffset)
    invalidate()
}

internal fun ChatScrollHost.applyOverlayInsets(top: Double, bottom: Double) {
    if (top == overlayTop && bottom == overlayBottom) return
    val anchor = readingAnchor()
    overlayTop = top.coerceIn(0.0, height.toDouble())
    overlayBottom = bottom.coerceIn(0.0, max(0.0, height - overlayTop))
    setGeometry(anchor)
    schedule()
}

internal fun ChatScrollHost.scrollToMessage(messageID: MessageID) {
    if (index.position(messageID) == null) return
    scroller.abortAnimation()
    pendingAnchor =
        ReadingAnchor(
            messageID = messageID,
            screenDelta =
                if (index.position(messageID) == 0)
                    max(0.0, (index.top(messageID) ?: 0.0) - overlayTop)
                else 0.0,
            followsBottom = false,
        )
    setGeometry(pendingAnchor!!)
    schedule()
    onAnchor?.invoke(pendingAnchor!!)
}
