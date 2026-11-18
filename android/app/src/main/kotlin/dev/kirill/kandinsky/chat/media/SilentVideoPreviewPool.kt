package dev.kirill.kandinsky.chat.media

// Ближайшие ролики остаются на паузе. Видимый ролик вытесняет самый давний невидимый.
class SilentVideoPreviewPool {
    private val entries = linkedMapOf<VideoPlaybackKey, SilentVideoPreview>()
    private val order = mutableListOf<VideoPlaybackKey>()
    private var visible = emptySet<VideoPlaybackKey>()
    private var preparing: VideoPlaybackKey? = null
    private var triedPreparation = false
    private var limit = 2

    fun beginFrame(
        visibleKeys: List<VideoPlaybackKey>,
        limit: Int,
        preparing: VideoPlaybackKey? = null,
    ) {
        if (this.preparing != preparing) {
            this.preparing = preparing
            triedPreparation = false
        }
        this.limit = limit.coerceIn(0, 4)
        visible = visibleKeys.take(this.limit).toSet()
        for ((key, owner) in entries.toMap()) {
            if (owner.closed) {
                entries.remove(key)
                order.remove(key)
            } else owner.setVisible(key in visible)
        }
        while (entries.size > this.limit && evictPaused()) {}
    }

    fun preview(key: VideoPlaybackKey, create: () -> SilentVideoPreview): SilentVideoPreview? {
        val onScreen = key in visible
        if (!onScreen && (key != preparing || visible.size >= limit)) return null
        entries[key]?.takeUnless { it.closed }?.let {
            if (onScreen) {
                order.remove(key)
                order.add(key)
            }
            it.setVisible(onScreen)
            return it
        }
        if (!onScreen && triedPreparation) return null
        while (entries.size >= limit) if (!evictPaused()) return null
        if (!onScreen) triedPreparation = true
        return create().also {
            entries[key] = it
            order.remove(key)
            order.add(key)
            it.setVisible(onScreen)
        }
    }

    private fun evictPaused(): Boolean {
        val key = order.firstOrNull { it !in visible } ?: return false
        order.remove(key)
        entries.remove(key)?.close()
        return true
    }

    fun clear() {
        val old = entries.values.toList()
        entries.clear()
        order.clear()
        visible = emptySet()
        preparing = null
        triedPreparation = false
        old.forEach { it.close() }
    }
}
