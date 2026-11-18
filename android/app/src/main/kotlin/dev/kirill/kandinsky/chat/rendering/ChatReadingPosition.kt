// Место чтения на андроиде связывает видимую область с сообщением и строкой текста. При появлении
// клавиатуры, уточнении высоты строк или возвращении в чат оно возвращает нужное место даже после
// нового переноса текста.

package dev.kirill.kandinsky.chat.rendering

import android.graphics.*
import android.view.*
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.media.*
import kotlin.math.*

// Берём ожидающее место, пока лента ещё восстанавливается. В готовой истории запоминаем верхнее
// сообщение, положение внутри подготовленной строки и близость к последнему сообщению.
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

// Ставим ленту к концу, если это было запомнено. Иначе вычисляем высоту сохранённого символа по
// новой раскладке или используем положение строки, пока текст не подготовлен.
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

// Добавляем к измеренному содержимому вертикальный зазор. Высота в указателе истории должна
// совпадать с фактическим местом холста.
internal fun ChatScrollHost.measuredRowHeight(resource: PreparedText) = (resource.height + 6 * d).toDouble()

// Сверяем известные строки с уже подготовленными размерами. Обновляем только заметно отличающиеся
// высоты, чтобы не двигать ленту из-за дробного округления.
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

// Пересчитываем отступы и возвращаем точку чтения в новой системе координат. Сдвигаем вместе с
// ней окно подготовки, чтобы изменение геометрии не приняли за разгон прокрутки.
internal fun ChatScrollHost.setGeometry(anchor: ReadingAnchor) {
    index.configureInsets(overlayTop + 8 * d, overlayBottom + 8 * d)
    val previousOffset = offset
    offset = resolved(anchor)
    mediaPreparation.rebase(offset - previousOffset)
    invalidate()
}

// Запоминаем чтение до изменения панели, ограничиваем размеры отступов и восстанавливаем позицию.
// Прежние значения не требуют пересчёта кадра.
internal fun ChatScrollHost.applyOverlayInsets(top: Double, bottom: Double) {
    if (top == overlayTop && bottom == overlayBottom) return
    val anchor = readingAnchor()
    overlayTop = top.coerceIn(0.0, height.toDouble())
    overlayBottom = bottom.coerceIn(0.0, max(0.0, height - overlayTop))
    setGeometry(anchor)
    schedule()
}
