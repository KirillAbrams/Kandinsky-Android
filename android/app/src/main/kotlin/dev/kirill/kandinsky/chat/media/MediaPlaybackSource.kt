// Даёт проигрывателю готовый файл или доступ к отдельным частям видео. При закрытии прекращает
// чтения и снимает удержание файла.

package dev.kirill.kandinsky.chat.media

import android.media.MediaDataSource
import java.io.File

class MediaPlaybackSource(
    val file: File? = null,
    val ranges: MediaRangeCache? = null,
    private val onClose: () -> Unit = {},
) : AutoCloseable {
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    // Заранее читает разные части в начале и конце файла. Если полный файл уже есть,
    // дополнительное чтение через сеть не требуется.
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
                // Сообщает полный размер видео, даже если доступны только несколько частей.
                // Проигрыватель рассчитывает позицию по общему размеру.
                override fun getSize(): Long = cache.length

                // Проверяет границы памяти проигрывателя и читает ограниченную порцию с нужной
                // позиции. За концом возвращает признак окончания, чтобы проигрыватель не ждал
                // новые данные.
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

                // Закрывает хранилище частей по просьбе системного проигрывателя. Открытые
                // запросы больше не используют освобождённое чтение.
                override fun close() {
                    cache.close()
                }
            }
        }

    // При первом закрытии отменяет чтение частей и освобождает файл. Повторный вызов не уменьшает
    // удержание ещё раз.
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            ranges?.close()
            onClose()
        }
    }
}
