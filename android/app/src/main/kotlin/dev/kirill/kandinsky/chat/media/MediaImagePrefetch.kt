package dev.kirill.kandinsky.chat.media

import dev.kirill.kandinsky.chat.domain.Attachment
import dev.kirill.kandinsky.chat.domain.Cancellation

// Соседние сообщения оставляют вытесняемый кэш, без скрытого владельца полного рисунка.
internal class MediaImagePrefetch {
    private class Entry {
        var request: Cancellation? = null
        var tinyRequest: Cancellation? = null
        var tiny: PreparedImage? = null
        var closed = false
        fun close() {
            closed = true
            request?.cancel()
            tinyRequest?.cancel()
            tiny?.close()
            tiny = null
        }
    }
    private val entries = mutableMapOf<String, Entry>()

    private fun identity(attachment: Attachment, key: ThumbnailKey) =
        attachment.relativePath + "@${key.maxPixel}:${key.studioOriginal}"

    fun preview(attachment: Attachment, store: MediaStore): PreparedImage? {
        val key = store.studioPhotoKey(attachment) ?: ThumbnailKey(1024)
        val entry = entries[identity(attachment, key)] ?: return null
        return store.cachedThumbnail(attachment, key) ?: entry.tiny?.retained()
    }

    fun update(attachments: List<Attachment>, store: MediaStore) {
        val wanted = mutableSetOf<String>()
        for (attachment in attachments.take(4)) {
            val key = store.studioPhotoKey(attachment) ?: ThumbnailKey(1024)
            val identity = identity(attachment, key)
            if (!wanted.add(identity)) continue
            if (identity !in entries) {
                val entry = Entry()
                entries[identity] = entry
                val cached = store.cachedThumbnail(attachment, key)
                if (cached != null) { cached.close(); continue }
                entry.tinyRequest = store.tinyPreview(attachment) { result ->
                    result.onSuccess { if (!entry.closed) entry.tiny = it else it.close() }
                }
                entry.request = store.thumbnail(attachment, key, 0, localOnly = true, priority = 0) { _, result ->
                    result.getOrNull()?.close()
                }
            }
        }
        for (identity in entries.keys.toList()) if (identity !in wanted) {
            entries.remove(identity)?.close()
        }
    }

    fun clear() {
        entries.values.forEach { it.close() }
        entries.clear()
    }
}
