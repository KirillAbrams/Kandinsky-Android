package dev.kirill.kandinsky.chat.media

import android.media.MediaDataSource
import java.io.File

class MediaPlaybackSource(
    val file: File? = null,
    val ranges: MediaRangeCache? = null,
    private val onClose: () -> Unit = {},
) : AutoCloseable {
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    fun prepareForPlayback(token: MediaCancellation) {
        val cache = ranges ?: return
        val chunk = MediaRangeCache.CHUNK_SIZE.toLong()
        for (offset in listOf(0L, chunk, (cache.length - 1) / chunk * chunk).distinct()) {
            if (offset < cache.length) cache.read(offset, 1, token)
        }
    }
    val dataSource: MediaDataSource? =
        ranges?.let { cache ->
            object : MediaDataSource() {
                // Размер относится к целому источнику, даже когда на диске сохранены только
                // отдельные участки
                override fun getSize(): Long = cache.length

                override fun readAt(
                    position: Long,
                    buffer: ByteArray,
                    offset: Int,
                    size: Int,
                ): Int {
                    if (position >= cache.length) return -1
                    require(offset >= 0 && size >= 0 && offset <= buffer.size - size)
                    val data = cache.read(position, minOf(size, 65536), MediaCancellation())
                    data.copyInto(buffer, offset)
                    return data.size
                }

                // Новые чтения больше не должны использовать освобождённые ресурсы хранилища
                override fun close() {
                    cache.close()
                }
            }
        }

    // Атомарная отметка закрытия не позволяет повторному вызову ещё раз освободить тот же ресурс
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            ranges?.close()
            onClose()
        }
    }
}
