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

    init {
        require(dayGap.isFinite() && dayGap >= 0)
    }

    // Для непустой истории сразу меняет общую высоту на разницу отступов, сохраняя высоты и порядок
    // самих сообщений
    fun configureInsets(top: Double, bottom: Double) {
        require(top.isFinite() && bottom.isFinite() && top >= 0 && bottom >= 0)
        if (top == topInset && bottom == bottomInset) return
        if (rows.isNotEmpty()) totalHeight += top - topInset + bottom - bottomInset
        topInset = top
        bottomInset = bottom
        geometryRevision++
    }

    private fun stamp(row: IndexedRow) = row.savedAtMs ?: row.order.sortKey

    private fun day(row: IndexedRow): Long {
        val ms = stamp(row)
        val span = 86400000L
        return Math.floorDiv(ms, span) +
            Math.floorDiv(Math.floorMod(ms, span) + timeZone.getOffset(ms), span)
    }

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

    // Дерево хранит суммы высот. До нужной строки можно дойти без перебора всей истории.
    fun prefix(count: Int): Double {
        var n = count.coerceIn(0, rows.size)
        var sum = topInset
        while (n > 0) {
            sum += tree[n]
            n -= n and -n
        }
        return sum
    }

    fun position(id: MessageID) = positions[id]

    // Добавляет промежуток его разделителя дня, чтобы положение относилось к самому сообщению, а не
    // к
    // заголовку перед ним.
    fun top(id: MessageID) = positions[id]?.let { prefix(it) + leading[it] }

    // Высота строки исключает заголовок даты и внешние отступы.
    fun height(id: MessageID) = positions[id]?.let { rows[it].estimatedHeight }

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

    fun offset(anchor: ReadingAnchor, viewport: Double): Double {
        if (anchor.followsBottom) return max(0.0, totalHeight - max(0.0, viewport))
        val i = anchor.messageID?.let { positions[it] } ?: return 0.0
        return (prefix(i) +
                leading[i] +
                anchor.fraction.coerceIn(0.0, 1.0) * rows[i].estimatedHeight - anchor.screenDelta)
            .coerceIn(0.0, max(0.0, totalHeight - max(0.0, viewport)))
    }

    fun refreshRevision(message: Message) {
        val i = position(message.id) ?: return
        val old = rows[i]
        if (old.order != message.order || old.revision >= message.revision) return
        (rows as MutableList<IndexedRow>)[i] = old.copy(revision = message.revision)
    }

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
