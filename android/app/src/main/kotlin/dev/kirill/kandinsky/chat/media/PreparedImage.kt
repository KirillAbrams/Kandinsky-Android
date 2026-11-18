// Управляет общим пределом памяти изображений, удержаниями и порядком вытеснения. Готовые пиксели
// можно разделить между экранами без новой обработки.

package dev.kirill.kandinsky.chat.media

import android.graphics.Bitmap

class ImageByteBudget {
    private val gate = Any()
    private var used = 0
    private var peak = 0
    private var maximum = 48 * 1024 * 1024
    var limit: Int
        // Читает предел байтов под общей блокировкой. Работник сверяется с актуальным
        // ограничением.
        get() = synchronized(gate) { maximum }
        // Записывает неотрицательный предел памяти. Следующая проверка не примет объём сверх
        // нового значения.
        set(value) {
            synchronized(gate) { maximum = value.coerceAtLeast(0) }
        }

    val bytes
        // Возвращает занятые байты под блокировкой, чтобы счёт отражал завершённые изменения.
        get() = synchronized(gate) { used }

    val peakBytes
        // Возвращает наибольший расход с начала учёта. По нему проверяется, помещалась ли
        // подготовка в предел.
        get() = synchronized(gate) { peak }

    // Проверяет возможность занять положительный объём. Так перед чтением можно убрать часть
    // старых картинок.
    fun canFit(value: Int) = synchronized(gate) { value > 0 && used <= maximum - value }

    // Занимает свободные байты и обновляет наибольший расход под блокировкой. Два фоновых
    // читателя не возьмут одно и то же место.
    fun reserve(value: Int): ImageByteCharge? =
        synchronized(gate) {
            if (value <= 0 || used > maximum - value) null
            else {
                used += value
                peak = maxOf(peak, used)
                ImageByteCharge(this, value)
            }
        }

    // Возвращает указанное число байтов общему счётчику и не даёт итогу стать отрицательным.
    internal fun release(value: Int) {
        synchronized(gate) { used = (used - value).coerceAtLeast(0) }
    }
}

class ImageByteCharge(private val budget: ImageByteBudget, private var amount: Int) :
    AutoCloseable {
    // Сверяет фактический размер с резервом и возвращает переплату. При превышении отказывает,
    // иначе учёт занижал бы память.
    @Synchronized
    fun shrink(actual: Int) {
        if (actual < 0 || actual > amount) throw MediaException(MediaError.UNAVAILABLE)
        val difference = amount - actual
        amount = actual
        budget.release(difference)
    }

    // Обнуляет собственный остаток перед возвратом. Следующее закрытие не освободит те же байты.
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

    // Увеличивает число удержаний и помечает рисунок переданным на показ. Выданный кадр можно
    // закрыть независимо.
    fun frame(): PreparedImage? =
        synchronized(gate) {
            if (value == null || references <= 0) null
            else {
                references++
                published = true
                PreparedImage(this)
            }
        }

    // Берёт текущий рисунок под блокировкой. После освобождения последнего удержания рисунка
    // больше нет.
    fun bitmap(): Bitmap? = synchronized(gate) { value }

    val hasOnlyCacheOwner: Boolean
        // Проверяет, осталось ли только исходное владение хранилища. Такой кадр можно вытеснить,
        // если требуется немедленно вернуть память.
        get() = synchronized(gate) { references == 1 && !ownerClosed }

    // Снимает исходное владение один раз под блокировкой. Если рисунок ещё на экране, другие
    // удержания оставляют его живым.
    fun closeOwner() {
        synchronized(gate) {
            if (ownerClosed) return
            ownerClosed = true
            releaseLocked()
        }
    }

    // Под блокировкой снимает одно выданное удержание. Последнее освобождает учтённые байты.
    fun release() {
        synchronized(gate) { releaseLocked() }
    }

    // При нуле снимает ссылку и возвращает резерв. Только ещё не показанный рисунок освобождает
    // явно, поскольку система рисования может держать свою ссылку.
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

// Запоминает отдельное удержание общих пикселей, которое экран сможет закрыть сам.
class PreparedImage internal constructor(private var pixels: ImagePixels?) : AutoCloseable {
    private val gate = Any()
    val orientation = 1
    val isTinyPreview: Boolean
        // Проверяет, остаётся ли кадр маленькой временной картинкой. Потерянное удержание не
        // выдаёт прежний признак.
        get() = synchronized(gate) { pixels?.isTinyPreview == true }
    @Volatile
    var closeCount = 0
        private set

    val bitmap: Bitmap?
        // Читает рисунок только из своего открытого удержания. Закрытый кадр не должен достать
        // пиксели из старой ссылки.
        get() = synchronized(gate) { pixels?.bitmap() }

    // Создаёт ещё одно удержание тех же пикселей без чтения файла. Подходит, когда один кадр пока
    // нужен двум элементам.
    fun retained(): PreparedImage? = synchronized(gate) { pixels?.frame() }

    // Снимает свою ссылку и только затем возвращает удержание. Повторное закрытие не уменьшает
    // общий счётчик второй раз.
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

    // Ищет ключ и выдаёт удержание. Чтение автоматически обновляет порядок вытеснения.
    fun frame(key: String) = values[key]?.frame()

    // Убирает старые кадры без читателей до появления места. Если освободить нужный объём нельзя,
    // читатель получает отказ.
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

    // Снимает владение старым значением, записывает новое и вытесняет ранние кадры по числу и
    // байтам. Уже показанные кадры остаются у своих читателей до закрытия.
    fun insert(value: ImagePixels, key: String, maximumBytes: Int = Int.MAX_VALUE) {
        values.remove(key)?.closeOwner()
        values[key] = value
        var bytes = values.values.sumOf { it.byteSize }
        val iterator = values.entries.iterator()
        while ((values.size > entryLimit || bytes > maximumBytes.coerceAtLeast(0)) && iterator.hasNext()) {
            val oldest = iterator.next().value
            bytes -= oldest.byteSize
            oldest.closeOwner()
            iterator.remove()
        }
    }

    // Убирает полные изображения и сокращает остальное до небольшого объёма для возврата. Так
    // уход из чата освобождает дорогие копии.
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

    // Закрывает владение всеми рисунками и очищает ключи. Отдельные удержания экранов сохраняются
    // только до их закрытия.
    fun clear() {
        values.values.forEach { it.closeOwner() }
        values.clear()
    }
}
