package dev.kirill.kandinsky.chat.media

import android.graphics.Bitmap

internal class VideoPlaybackCache(private val budget: ImageByteBudget) {
    private val frames = ThumbnailCache(budget, entryLimit = 4)
    private val positions = LinkedHashMap<VideoPlaybackKey, Long>()
    var generation = 0
        private set
    private var closed = false

    fun position(key: VideoPlaybackKey): Long = positions[key] ?: 0L

    fun rememberPosition(value: Long, key: VideoPlaybackKey, generation: Int) {
        if (closed || this.generation != generation || value < 0) return
        positions.remove(key)
        positions[key] = value
        while (positions.size > 256) positions.remove(positions.keys.first())
    }

    fun frame(key: VideoPlaybackKey): PreparedImage? = frames.frame(key.cacheKey)
    fun makeRoom(amount: Int) = frames.makeRoom(amount)

    // Место резервируется до копирования кадра. Снимки делят общий предел с фотографиями.
    fun rememberFrame(
        key: VideoPlaybackKey,
        generation: Int,
        width: Int,
        height: Int,
        render: (Int, Int) -> Bitmap?,
    ) {
        if (closed || this.generation != generation || width <= 0 || height <= 0) return
        val scale = minOf(1.0, 1024.0 / maxOf(width, height))
        val w = maxOf(1, (width * scale).toInt())
        val h = maxOf(1, (height * scale).toInt())
        val amount = w * h * 4
        if (!frames.makeRoom(amount)) return
        val charge = budget.reserve(amount) ?: return
        var bitmap: Bitmap? = null
        try {
            bitmap = render(w, h)
            if (bitmap == null) { charge.close(); return }
            charge.shrink(bitmap.allocationByteCount)
            frames.insert(ImagePixels(bitmap, charge), key.cacheKey)
        } catch (_: Exception) {
            bitmap?.recycle()
            charge.close()
        }
    }

    fun clearFrames() {
        generation++
        frames.clear()
    }

    fun close() {
        closed = true
        clearFrames()
        positions.clear()
    }
}
