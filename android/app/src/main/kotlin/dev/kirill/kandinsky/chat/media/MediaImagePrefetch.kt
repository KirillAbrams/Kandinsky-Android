package dev.kirill.kandinsky.chat.media

import dev.kirill.kandinsky.chat.domain.Attachment
import dev.kirill.kandinsky.chat.domain.Cancellation

// Соседние сообщения оставляют вытесняемый кэш, без скрытого владельца полного рисунка.
internal class MediaImagePrefetch {
    data class Item(val attachment: Attachment, val maxPixel: Int = 1024)

    companion object {
        const val ALBUM_MAX_PIXEL = 512
    }

    private class Entry(val relativePath: String) {
        var request: Cancellation? = null
        var tinyRequest: Cancellation? = null
        var tiny: PreparedImage? = null
        var video: Cancellation? = null
        var closed = false
        fun close() {
            closed = true
            request?.cancel()
            tinyRequest?.cancel()
            video?.cancel()
            tiny?.close()
            tiny = null
        }
    }
    private val entries = mutableMapOf<String, Entry>()

    private fun identity(attachment: Attachment, key: ThumbnailKey) =
        attachment.relativePath + "@${key.maxPixel}:${key.studioOriginal}"

    fun preview(item: Item, store: MediaStore): PreparedImage? {
        val (attachment, maxPixel) = item
        val key = store.studioPhotoKey(attachment) ?: ThumbnailKey(maxPixel)
        val entry = entries[identity(attachment, key)] ?: return null
        return store.cachedThumbnail(attachment, key) ?: entry.tiny?.retained()
    }

    fun update(items: List<Item>, store: MediaStore) {
        val selected = items.take(12).map { (attachment, maxPixel) ->
            val key = store.studioPhotoKey(attachment) ?: ThumbnailKey(maxPixel)
            Triple(attachment, key, identity(attachment, key))
        }
        val identities = selected.map { it.third }.toSet()
        for (identity in entries.keys.toList()) if (identity !in identities)
            entries.remove(identity)?.close()
        val wanted = mutableSetOf<String>()
        val videoPath = selected.firstOrNull { it.first.mime.startsWith("video/") }?.first?.relativePath
        for (entry in entries.values) if (entry.relativePath != videoPath) {
            entry.video?.cancel()
            entry.video = null
        }
        for ((attachment, key, identity) in selected) {
            if (!wanted.add(identity)) continue
            val existing = entries[identity]
            if (existing != null && attachment.relativePath == videoPath && existing.video == null)
                existing.video = store.prepareStudioVideo(attachment)
            if (identity !in entries) {
                val entry = Entry(attachment.relativePath)
                entries[identity] = entry
                if (attachment.relativePath == videoPath) entry.video = store.prepareStudioVideo(attachment)
                val cached = store.cachedThumbnail(attachment, key)
                if (cached != null) { cached.close(); continue }
                entry.tinyRequest = store.tinyPreview(attachment) { result ->
                    result.onSuccess { if (!entry.closed) entry.tiny = it else it.close() }
                }
                entry.request = store.thumbnail(attachment, key, 0, priority = 0) { _, result ->
                    result.getOrNull()?.close()
                }
            }
        }
    }

    fun clear() {
        entries.values.forEach { it.close() }
        entries.clear()
    }
}
