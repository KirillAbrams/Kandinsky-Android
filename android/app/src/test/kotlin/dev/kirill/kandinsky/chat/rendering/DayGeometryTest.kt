package dev.kirill.kandinsky.chat.rendering

import dev.kirill.kandinsky.chat.domain.*
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class DayGeometryTest {
    private fun row(id: Long, h: Double, t: Long) = IndexedRow(id, OrderKey(id, id), h, 1, t)

    @Test
    fun daySpaceInsetsAndHeightChangesPreserveTheReadingAnchor() {
        val idx = HistoryIndex(32.0, TimeZone.getTimeZone("UTC"))
        idx.configureInsets(100.0, 90.0)
        val rows = listOf(row(1, 40.0, 1000), row(2, 60.0, 2000), row(3, 80.0, 86401000))
        idx.replace(rows, ReadingAnchor(), 100.0)
        assertEquals(434.0, idx.totalHeight, 0.0001)
        assertEquals(132.0, idx.top(1)!!, 0.0001)
        assertEquals(172.0, idx.top(2)!!, 0.0001)
        assertEquals(264.0, idx.top(3)!!, 0.0001)
        assertEquals(listOf(1L, 3L), idx.days(110.0, 150.0).map { it.messageID })
        val inHeader = idx.anchorAt(110.0)
        assertEquals(110.0, idx.offset(inHeader, 100.0), 0.0001)
        val anchor = idx.anchorAt(275.0)
        assertEquals(3L, anchor.messageID)
        assertEquals(325.0, idx.updateHeight(1, 90.0, anchor, 100.0), 0.0001)
        idx.replace(rows.drop(1), anchor, 100.0)
        assertEquals(2L, idx.days(0.0, 500.0).first().messageID)
        idx.replace(emptyList(), ReadingAnchor(), 100.0)
        assertEquals(0.0, idx.totalHeight, 0.0)
        assertTrue(idx.days(0.0, 100.0).isEmpty())
    }

    @Test
    fun decoratedFenwickLayoutMatchesIndependentCalendarAndLinearOracle() {
        for (zone in listOf("UTC", "America/New_York", "Asia/Kathmandu")) {
            val tz = TimeZone.getTimeZone(zone)
            val rows =
                (1L..6000L).map { row(it, 44.0 + (it % 7) * 3.25, 1793484000000L + it * 300000) }
            val index = HistoryIndex(32.0, tz)
            index.configureInsets(100.0, 90.0)
            index.replace(rows, ReadingAnchor(), 640.0)
            val tops = mutableListOf<Double>()
            val starts = mutableListOf<Double>()
            var y = 100.0
            var old: java.time.LocalDate? = null
            for (r in rows) {
                starts.add(y)
                val day = Instant.ofEpochMilli(r.savedAtMs!!).atZone(ZoneId.of(zone)).toLocalDate()
                if (day != old) y += 32
                old = day
                tops.add(y)
                y += r.estimatedHeight
            }
            assertEquals(y + 90, index.totalHeight, 0.000001)
            for (n in 0 until 1000) {
                val target = (n * 7919L % 10000) / 10000.0 * (index.totalHeight - 640)
                val expected = starts.indexOfLast { it <= target }.coerceAtLeast(0)
                assertEquals(
                    "zone=$zone offset=$target",
                    rows[expected].messageID,
                    index.anchorAt(target).messageID,
                )
                val id = rows[n * 5].messageID
                assertEquals(tops[n * 5], index.top(id)!!, 0.000001)
                val anchor = index.anchorAt(target)
                assertEquals(target, index.offset(anchor, 640.0), 0.000001)
            }
        }
    }
}
