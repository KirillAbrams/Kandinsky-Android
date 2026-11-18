// Даём проверкам переход к известному сообщению через тот же пересчёт места чтения, которым
// пользуется история.

package dev.kirill.kandinsky.chat.rendering

import dev.kirill.kandinsky.chat.domain.MessageID
import dev.kirill.kandinsky.chat.domain.ReadingAnchor
import kotlin.math.max

// Ищем сообщение, останавливаем прежнюю прокрутку и задаём новое место чтения. У первой строки
// учитываем верхнее перекрытие, чтобы переход не спрятал её под шапкой.
internal fun ChatScrollHost.goTo(messageID: MessageID) {
    val position = index.position(messageID) ?: return
    scroller.abortAnimation()
    val anchor = ReadingAnchor(
        messageID = messageID,
        screenDelta = if (position == 0) max(0.0, (index.top(messageID) ?: 0.0) - overlayTop) else 0.0,
        followsBottom = false,
    )
    pendingAnchor = anchor
    setGeometry(anchor)
    schedule()
    onAnchor?.invoke(anchor)
}
