package dev.kirill.kandinsky.chat.rendering

import dev.kirill.kandinsky.chat.domain.*

object ReadingAnchorResolver {
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
