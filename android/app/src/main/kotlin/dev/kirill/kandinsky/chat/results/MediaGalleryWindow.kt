package dev.kirill.kandinsky.chat.results

import dev.kirill.kandinsky.chat.domain.*

data class MediaGalleryEntry(
    val messageID: MessageID,
    val chatID: ChatID,
    val order: OrderKey,
    val kind: MessageKind,
    val caption: String,
    val savedAtMs: Long,
    val attachmentIndex: Int,
    val attachment: Attachment,
    val isIncoming: Boolean,
) {
    val id
        get() =
            "$messageID/" +
                if (attachment.id.startsWith("net-") && attachment.id.contains("-result-"))
                    attachment.id
                else attachmentIndex.toString()

    val isVideo
        get() = attachment.mime.startsWith("video/")

    fun hasSameMedia(other: MediaGalleryEntry) =
        attachment.relativePath == other.attachment.relativePath &&
            attachment.mime == other.attachment.mime &&
            attachment.byteSize == other.attachment.byteSize &&
            attachment.rawWidth == other.attachment.rawWidth &&
            attachment.rawHeight == other.attachment.rawHeight &&
            attachment.orientation == other.attachment.orientation

    companion object {
        fun entries(row: Message): List<MediaGalleryEntry> {
            if (row.kind !in listOf(MessageKind.PHOTO, MessageKind.VIDEO, MessageKind.GIF))
                return emptyList()
            return row.attachments.mapIndexedNotNull { index, a ->
                if (a.mime.startsWith("image/") || a.mime.startsWith("video/"))
                    MediaGalleryEntry(
                        row.id,
                        row.chatID,
                        row.order,
                        row.kind,
                        row.caption,
                        row.savedAtMs,
                        index,
                        a,
                        row.direction == MessageDirection.INCOMING,
                    )
                else null
            }
        }
    }
}

class MediaGalleryWindow(seed: Message, attachmentIndex: Int = 0) {
    companion object {
        const val MAXIMUM_ENTRIES = 96
        private val order = compareBy<MediaGalleryEntry>({ it.order }, { it.attachmentIndex })
    }

    val chatID = seed.chatID
    var entries = MediaGalleryEntry.entries(seed)
        private set

    private var selectedID =
        entries.firstOrNull { it.attachmentIndex == attachmentIndex }?.id
            ?: entries.firstOrNull()?.id
    val selectedIndex
        get() = entries.indexOfFirst { it.id == selectedID }.takeIf { it >= 0 }

    val selected
        get() = selectedIndex?.let { entries[it] }

    var hasOlder = true
        private set

    var hasNewer = true
        private set

    val neighbours
        get() =
            selectedIndex?.let { entries.subList(maxOf(0, it - 1), minOf(entries.size, it + 2)) }
                ?: emptyList()

    val needsPage
        get() =
            selectedIndex?.let { it < 4 && hasOlder || entries.size - 1 - it < 4 && hasNewer }
                ?: false

    init {
        trim()
    }

    fun step(delta: Int): Boolean {
        if (delta != -1 && delta != 1) return false
        val index = selectedIndex ?: return false
        if (index + delta !in entries.indices) return false
        selectedID = entries[index + delta].id
        return true
    }

    fun select(id: String): Boolean {
        if (entries.none { it.id == id }) return false
        selectedID = id
        return true
    }

    // Страницу другого чата отклоняет, чтобы просмотр не смешал разные истории.
    fun merge(page: HistoryPage): Boolean {
        if (page.rows.any { it.chatID != chatID }) return false
        val incoming = page.rows.flatMap { MediaGalleryEntry.entries(it) }.sortedWith(order)
        if (incoming.isEmpty()) {
            hasOlder = false
            hasNewer = false
            return true
        }
        if (entries.isEmpty() || incoming.first().order <= entries.first().order)
            hasOlder = page.before != null
        if (entries.isEmpty() || incoming.last().order >= entries.last().order)
            hasNewer = page.after != null
        val byID = entries.associateBy { it.id }.toMutableMap()
        for (entry in incoming) byID[entry.id] = entry
        entries = byID.values.sortedWith(order)
        trim()
        return true
    }

    fun apply(delta: MessageDelta) {
        if (delta.chat.id != chatID) return
        val previous = selected
        val changed = (delta.updated + delta.inserted).filter { it.chatID == chatID }
        val removed = (delta.removedIDs + changed.map { it.id }).toSet()
        val byID = entries.filter { it.messageID !in removed }.associateBy { it.id }.toMutableMap()
        for (row in changed) for (entry in MediaGalleryEntry.entries(row)) byID[entry.id] = entry
        entries = byID.values.sortedWith(order)
        if (selectedIndex == null)
            selectedID =
                entries.firstOrNull { previous == null || order.compare(it, previous) >= 0 }?.id
                    ?: entries.lastOrNull()?.id
        trim()
    }

    private fun trim() {
        if (entries.size <= MAXIMUM_ENTRIES) return
        val lower =
            ((selectedIndex ?: 0) - MAXIMUM_ENTRIES / 2).coerceIn(0, entries.size - MAXIMUM_ENTRIES)
        val upper = minOf(entries.size, lower + MAXIMUM_ENTRIES)
        if (lower > 0) hasOlder = true
        if (upper < entries.size) hasNewer = true
        entries = entries.subList(lower, upper).toList()
    }
}
