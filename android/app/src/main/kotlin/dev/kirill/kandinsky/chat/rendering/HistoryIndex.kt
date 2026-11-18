// Указатель истории на андроиде находит сообщения по координате прокрутки и по номеру. Дерево
// частичных сумм ускоряет поиск в длинном чате, а уточнение высоты одной строки меняет лишь
// суммы, куда она входит.

package dev.kirill.kandinsky.chat.rendering

import dev.kirill.kandinsky.chat.domain.*
import java.util.TimeZone
import kotlin.math.*

data class HistoryDay(
    val messageID: MessageID,
    val timestamp: Long,
    val top: Double,
    val height: Double,
)

class HistoryIndex(
    private val dayGap: Double = 0.0,
    private val timeZone: TimeZone = TimeZone.getDefault(),
) {
    var rows: List<IndexedRow> = emptyList()
        private set

    private var tree = DoubleArray(1)
    private var leading = DoubleArray(0)
    private var positions = emptyMap<MessageID, Int>()
    private var topInset = 0.0
    private var bottomInset = 0.0
    var totalHeight = 0.0
        private set
    internal var geometryRevision = 0
        private set

    // Проверяем, что промежуток для заголовка дня конечный и неотрицательный. Иначе координаты
    // строк могли бы пойти назад или стать непригодными.
    init {
        require(dayGap.isFinite() && dayGap >= 0)
    }

    // Учитываем изменение краёв видимой области в общей высоте непустой ленты. Номер геометрии
    // меняется только при новом размере отступов.
    fun configureInsets(top: Double, bottom: Double) {
        require(top.isFinite() && bottom.isFinite() && top >= 0 && bottom >= 0)
        if (top == topInset && bottom == bottomInset) return
        if (rows.isNotEmpty()) totalHeight += top - topInset + bottom - bottomInset
        topInset = top
        bottomInset = bottom
        geometryRevision++
    }

    // Берём сохранённое время, а для старой записи без него берём временную часть порядка.
    // Разделитель можно построить в обоих случаях.
    private fun stamp(row: IndexedRow) = row.savedAtMs ?: row.order.sortKey

    // Вычисляем местный номер дня по времени и смещению часового пояса. Деление с округлением
    // вниз сохраняет правильные границы и у отрицательных меток.
    private fun day(row: IndexedRow): Long {
        val ms = stamp(row)
        val span = 86400000L
        return Math.floorDiv(ms, span) +
            Math.floorDiv(Math.floorMod(ms, span) + timeZone.getOffset(ms), span)
    }

    // Проверяем номера и высоты, сортируем строки и пересобираем быстрый поиск и суммы. Если
    // прежняя точка пропала, выбираем ближайший оставшийся порядок и вычисляем новое положение.
    fun replace(values: List<IndexedRow>, anchor: ReadingAnchor, viewport: Double): Double {
        val sorted = values.sortedBy { it.order }
        require(
            sorted.map { it.messageID }.toSet().size == sorted.size &&
                sorted.all {
                    it.messageID == it.order.messageID &&
                        it.estimatedHeight.isFinite() &&
                        it.estimatedHeight > 0
                }
        )
        val oldOrder = anchor.messageID?.let { positions[it] }?.let { rows[it].order }
        rows = sorted.toMutableList()
        geometryRevision++
        positions = sorted.mapIndexed { i, r -> r.messageID to i }.toMap()
        tree = DoubleArray(sorted.size + 1)
        leading = DoubleArray(sorted.size)
        totalHeight = if (sorted.isEmpty()) 0.0 else topInset + bottomInset
        var previous: Long? = null
        sorted.forEachIndexed { i, r ->
            if (dayGap > 0) {
                val current = day(r)
                if (previous != current) leading[i] = dayGap
                previous = current
            }
            val h = r.estimatedHeight + leading[i]
            tree[i + 1] += h
            totalHeight += h
            val parent = i + 1 + ((i + 1) and -(i + 1))
            if (parent < tree.size) tree[parent] += tree[i + 1]
        }
        var restored = anchor
        if (
            anchor.messageID != null &&
                !positions.containsKey(anchor.messageID) &&
                oldOrder != null &&
                sorted.isNotEmpty()
        ) {
            var lo = 0
            var hi = sorted.size
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (sorted[mid].order < oldOrder) lo = mid + 1 else hi = mid
            }
            restored = anchor.copy(messageID = sorted[min(lo, sorted.size - 1)].messageID)
        }
        return offset(restored, viewport)
    }

    // Читаем суммы готовых блоков до нужного числа строк. Получаем высоту вместе с верхним
    // отступом без перебора всех сообщений.
    fun prefix(count: Int): Double {
        var n = count.coerceIn(0, rows.size)
        var sum = topInset
        while (n > 0) {
            sum += tree[n]
            n -= n and -n
        }
        return sum
    }

    // Ищем место сообщения по его номеру в таблице. Пустой ответ означает, что сообщение ещё не
    // вошло в текущий указатель.
    fun position(id: MessageID) = positions[id]

    // Считаем начало сообщения с промежутком под дату. Этот размер годится для установки самого
    // сообщения, даже если перед ним нарисован заголовок.
    fun top(id: MessageID) = positions[id]?.let { prefix(it) + leading[it] }

    // Возвращаем измеренную или оценочную высоту строки без даты. Её можно напрямую сравнить с
    // высотой подготовленного рисунка.
    fun height(id: MessageID) = positions[id]?.let { rows[it].estimatedHeight }

    // Находим строку по координате, пропуская известные блоки через дерево сумм. Верх и низ
    // ограничиваем так, чтобы поиск не вернул индекс за границей.
    private fun seek(value: Double): Int {
        if (rows.isEmpty()) return 0
        val content = totalHeight - topInset - bottomInset
        val target = (value - topInset).coerceIn(0.0, max(0.0, Math.nextDown(content)))
        var index = 0
        var sum = 0.0
        var step = 1
        while (step <= rows.size / 2) step *= 2
        while (step > 0) {
            val next = index + step
            if (next < tree.size && sum + tree[next] <= target) {
                index = next
                sum += tree[next]
            }
            step /= 2
        }
        return min(index, rows.size - 1)
    }

    // Запоминаем номер сообщения, долю пройденной строки и расстояние до её верха. Так после
    // уточнения размеров можно вернуться к тому же месту.
    fun anchorAt(offset: Double): ReadingAnchor {
        if (rows.isEmpty()) return ReadingAnchor(followsBottom = false)
        val i = seek(offset)
        val r = rows[i]
        val top = prefix(i) + leading[i]
        return ReadingAnchor(
            messageID = r.messageID,
            fraction = ((offset - top) / r.estimatedHeight).coerceIn(0.0, 1.0),
            screenDelta = max(0.0, top - offset),
            followsBottom = false,
        )
    }

    // Превращаем сохранённую долю и отступ в новую координату. Положение удерживаем внутри окна,
    // а при чтении последних сообщений привязываем к низу.
    fun offset(anchor: ReadingAnchor, viewport: Double): Double {
        if (anchor.followsBottom) return max(0.0, totalHeight - max(0.0, viewport))
        val i = anchor.messageID?.let { positions[it] } ?: return 0.0
        return (prefix(i) +
                leading[i] +
                anchor.fraction.coerceIn(0.0, 1.0) * rows[i].estimatedHeight - anchor.screenDelta)
            .coerceIn(0.0, max(0.0, totalHeight - max(0.0, viewport)))
    }

    // Меняем номер редакции только для известного сообщения с прежним порядком. Его размер и
    // расположение можно переиспользовать при такой правке.
    fun refreshRevision(message: Message) {
        val i = position(message.id) ?: return
        val old = rows[i]
        if (old.order != message.order || old.revision >= message.revision) return
        (rows as MutableList<IndexedRow>)[i] = old.copy(revision = message.revision)
    }

    // Записываем новую высоту и добавляем разницу только к связанным блокам дерева. Возвращаем
    // новую координату чтения без пересборки всего списка.
    fun updateHeight(
        id: MessageID,
        height: Double,
        anchor: ReadingAnchor,
        viewport: Double,
    ): Double {
        val i = positions[id]
        require(i != null && height.isFinite() && height > 0)
        val old = rows[i]
        val delta = height - old.estimatedHeight
        if (delta == 0.0) return offset(anchor, viewport)
        geometryRevision++
        (rows as MutableList<IndexedRow>)[i] = old.copy(estimatedHeight = height)
        var n = i + 1
        while (n < tree.size) {
            tree[n] += delta
            n += n and -n
        }
        totalHeight += delta
        return offset(anchor, viewport)
    }

    // Находим две крайние строки по видимой полосе и расширяем границы на небольшой запас.
    // Жёсткий предел ограничивает объём подготовки для одного кадра.
    fun visible(
        offset: Double,
        height: Double,
        overscan: Int = 1,
        maxFragments: Int = 128,
    ): IntRange {
        if (rows.isEmpty() || height <= 0 || maxFragments <= 0) return IntRange.EMPTY
        val start = max(0, seek(offset) - max(0, overscan))
        val end =
            minOf(
                rows.size,
                seek(offset + height) + 1 + max(0, overscan),
                start + min(128, maxFragments),
            )
        return start until max(start, end)
    }

    // Выбираем разделители, чьё место пересекается с видимой областью. Только у первого сообщения
    // нового дня есть такой промежуток.
    fun days(offset: Double, height: Double): List<HistoryDay> {
        if (dayGap == 0.0) return emptyList()
        return visible(offset, height).mapNotNull { i ->
            val y = prefix(i)
            if (leading[i] > 0 && y + leading[i] > offset && y < offset + height)
                HistoryDay(rows[i].messageID, stamp(rows[i]), y, leading[i])
            else null
        }
    }
}
