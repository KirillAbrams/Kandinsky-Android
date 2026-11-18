package dev.kirill.kandinsky.chat.media

import android.graphics.Bitmap

class ImageByteBudget {
    private val gate = Any()
    private var used = 0
    private var peak = 0
    private var maximum = 48 * 1024 * 1024
    var limit: Int
        get() = synchronized(gate) { maximum }
        set(value) {
            synchronized(gate) { maximum = value.coerceAtLeast(0) }
        }

    val bytes
        get() = synchronized(gate) { used }

    val peakBytes
        get() = synchronized(gate) { peak }

    fun canFit(value: Int) = synchronized(gate) { value > 0 && used <= maximum - value }

    fun reserve(value: Int): ImageByteCharge? =
        synchronized(gate) {
            if (value <= 0 || used > maximum - value) null
            else {
                used += value
                peak = maxOf(peak, used)
                ImageByteCharge(this, value)
            }
        }

    internal fun release(value: Int) {
        synchronized(gate) { used = (used - value).coerceAtLeast(0) }
    }
}

class ImageByteCharge(private val budget: ImageByteBudget, private var amount: Int) :
    AutoCloseable {
    @Synchronized
    fun shrink(actual: Int) {
        if (actual < 0 || actual > amount) throw MediaException(MediaError.UNAVAILABLE)
        val difference = amount - actual
        amount = actual
        budget.release(difference)
    }

    // Сначала обнуляет собственную сумму, чтобы повторный вызов не освободил её второй раз.
    @Synchronized
    override fun close() {
        val value = amount
        amount = 0
        budget.release(value)
    }
}

internal class ImagePixels(
    bitmap: Bitmap,
    private val charge: ImageByteCharge,
    val isTinyPreview: Boolean = false,
) {
    private val gate = Any()
    private var value: Bitmap? = bitmap
    private var references = 1
    private var ownerClosed = false
    private var published = false
    val byteSize = bitmap.allocationByteCount

    fun frame(): PreparedImage? =
        synchronized(gate) {
            if (value == null || references <= 0) null
            else {
                references++
                published = true
                PreparedImage(this)
            }
        }

    fun bitmap(): Bitmap? = synchronized(gate) { value }

    val hasOnlyCacheOwner: Boolean
        get() = synchronized(gate) { references == 1 && !ownerClosed }

    fun closeOwner() {
        synchronized(gate) {
            if (ownerClosed) return
            ownerClosed = true
            releaseLocked()
        }
    }

    fun release() {
        synchronized(gate) { releaseLocked() }
    }

    private fun releaseLocked() {
        references--
        if (references == 0) {
            // Явно освобождаем только пиксели, которые ещё не передавались системе рисования
            // Для показанных изображений система удерживает свои ссылки, поэтому снимаем только
            // собственное владение
            if (!published) value?.recycle()
            value = null
            charge.close()
        }
    }
}

class PreparedImage internal constructor(private var pixels: ImagePixels?) : AutoCloseable {
    private val gate = Any()
    val orientation = 1
    val isTinyPreview: Boolean
        get() = synchronized(gate) { pixels?.isTinyPreview == true }
    @Volatile
    var closeCount = 0
        private set

    val bitmap: Bitmap?
        get() = synchronized(gate) { pixels?.bitmap() }

    // Позволяет передать готовый кадр другому экрану без повторного декодирования.
    fun retained(): PreparedImage? = synchronized(gate) { pixels?.frame() }

    override fun close() {
        val p =
            synchronized(gate) {
                val p = pixels ?: return
                pixels = null
                closeCount++
                p
            }
        p.release()
    }
}

internal class ThumbnailCache(
    private val budget: ImageByteBudget,
    private val entryLimit: Int = 16,
    private val navigationBytes: Int = 8 * 1024 * 1024,
) {
    private val values = LinkedHashMap<String, ImagePixels>(16, 0.75f, true)

    fun frame(key: String) = values[key]?.frame()

    fun makeRoom(amount: Int): Boolean {
        val iterator = values.entries.iterator()
        while (!budget.canFit(amount) && iterator.hasNext()) {
            val value = iterator.next().value
            if (!value.hasOnlyCacheOwner) continue
            value.closeOwner()
            iterator.remove()
        }
        return budget.canFit(amount)
    }

    // Лишние записи вытесняет по давности, чтобы число изображений оставалось ограниченным.
    fun insert(value: ImagePixels, key: String) {
        values.remove(key)?.closeOwner()
        values[key] = value
        val iterator = values.entries.iterator()
        while (values.size > entryLimit && iterator.hasNext()) {
            iterator.next().value.closeOwner()
            iterator.remove()
        }
    }

    fun retainNavigationImages() {
        val originals = values.entries.iterator()
        while (originals.hasNext()) {
            val entry = originals.next()
            if (entry.key.contains("@original:")) {
                entry.value.closeOwner()
                originals.remove()
            }
        }
        var bytes = values.values.sumOf { it.byteSize }
        val oldest = values.entries.iterator()
        while (bytes > navigationBytes && oldest.hasNext()) {
            val value = oldest.next().value
            bytes -= value.byteSize
            value.closeOwner()
            oldest.remove()
        }
    }

    fun clear() {
        values.values.forEach { it.closeOwner() }
        values.clear()
    }
}
