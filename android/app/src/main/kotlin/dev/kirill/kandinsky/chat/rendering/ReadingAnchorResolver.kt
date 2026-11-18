// При удалении сообщения переносим место чтения к соседней записи. Пересчитываем его по списку
// строк до удаления, пока можно найти старое положение.

package dev.kirill.kandinsky.chat.rendering

import dev.kirill.kandinsky.chat.domain.*

object ReadingAnchorResolver {
    // Меняем только точку, привязанную к удалённой строке. Ищем первую оставшуюся ниже, затем
    // выше. Задаём новый номер без старого смещения внутри текста, которое относилось к другому
    // сообщению.
    fun afterRemoving(
        anchor: ReadingAnchor,
        rows: List<IndexedRow>,
        removed: Set<MessageID>,
    ): ReadingAnchor {
        val anchorMessageID = anchor.messageID
        if (anchor.followsBottom || anchorMessageID == null || anchorMessageID !in removed)
            return anchor
        val removedRowIndex = rows.indexOfFirst { it.messageID == anchorMessageID }
        if (removedRowIndex < 0) return anchor
        val nextVisibleRow =
            rows.asSequence().drop(removedRowIndex + 1).firstOrNull { it.messageID !in removed }
        val previousVisibleRow =
            rows.subList(0, removedRowIndex).asReversed().firstOrNull { it.messageID !in removed }
        return ReadingAnchor(
            messageID = (nextVisibleRow ?: previousVisibleRow)?.messageID,
            followsBottom = false,
            layoutVersion = anchor.layoutVersion,
        )
    }
}
