package dev.kirill.kandinsky.chat.rendering

import dev.kirill.kandinsky.chat.domain.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class HistoryIndexTest {
    private fun row(id: Long, height: Double = 44.0) = IndexedRow(id, OrderKey(id, id), height, 1)

    // После закрытия окно не должно принимать позднюю страницу.
    @Test
    fun prependDeleteResizeAndPageBounds() {
        val index = HistoryIndex()
        index.replace((1L..10000L).map { row(it) }, ReadingAnchor(), 640.0)
        var anchor = ReadingAnchor(messageID = 5000, fraction = 0.25, followsBottom = false)
        val initial = index.offset(anchor, 640.0)
        assertEquals(4999 * 44.0 + 11, initial, 0.0001)
        val shifted = index.replace(listOf(row(0)) + index.rows, anchor, 640.0)
        assertEquals(initial + 44, shifted, 0.0001)
        assertEquals(5000L, index.anchorAt(shifted).messageID)
        assertEquals(shifted + 56, index.updateHeight(100, 100.0, anchor, 640.0), 0.0001)
        val deleted = index.replace(index.rows.filter { it.messageID != 5000L }, anchor, 640.0)
        assertEquals(5001L, index.anchorAt(deleted).messageID)
        anchor = index.anchorAt(deleted)
        assertEquals(deleted, index.offset(anchor, 900.0), 0.0001)
        assertTrue(runCatching { index.replace(listOf(row(1), row(1)), anchor, 640.0) }.isFailure)
        val window = PageWindow(4)
        val generation = window.generation
        for (page in 0..4) {
            val messages =
                (0 until 128).map { n ->
                    val id = (page * 128 + n + 1).toLong()
                    Message(
                        id,
                        4,
                        OrderKey(id, id),
                        MessageDirection.INCOMING,
                        1,
                        MessageKind.TEXT,
                        "x",
                        "",
                        null,
                        1,
                        emptyList(),
                    )
                }
            window.apply(HistoryPage(messages, null, null), 4, generation)
            assertTrue(window.loadedCount <= 384)
        }
        assertEquals(257L, window.loadedRows.first().id)
        window.close()
        window.apply(HistoryPage(emptyList(), null, null), 4, generation)
        assertEquals(0, window.loadedCount)
    }

    // Слишком большой, повторный или чужой набор не должен заменить действующие строки.
    @Test
    fun seedKnownRowsPreservesBoundsAndRejectsInvalidOrClosedSeeds() {
        fun message(id: Long, chat: Long = 4) =
            Message(
                id,
                chat,
                OrderKey(id, id),
                MessageDirection.INCOMING,
                1,
                MessageKind.TEXT,
                "x",
                "",
                null,
                1,
                emptyList(),
            )
        val rows = (1L..384L).map { message(it) }
        val window = PageWindow(4)
        window.seedKnownRows(rows)
        assertEquals(rows.map { it.id }, window.loadedRows.map { it.id })
        for (invalid in
            listOf(rows + message(385), listOf(message(1), message(1)), listOf(message(1, 2)))) {
            window.seedKnownRows(invalid)
            assertEquals(rows.map { it.id }, window.loadedRows.map { it.id })
        }
        window.apply(HistoryPage(listOf(message(385)), null, null), 4, window.generation)
        assertEquals(257, window.loadedCount)
        assertEquals(129L, window.loadedRows.first().id)
        window.close()
        window.seedKnownRows(rows)
        assertEquals(0, window.loadedCount)
    }

    @Test
    fun independentLinearOracleAndHundredThousandIndex() {
        val index = HistoryIndex()
        var rows = (1L..10000L).map { row(it) }
        val oracle =
            File("../../fixtures/oracles/history-operations-v1.json").readLines().filter {
                it.contains("\"kind\"")
            }
        for (line in oracle) {
            val op =
                line
                    .trim()
                    .removeSuffix(",")
                    .removePrefix("{")
                    .removeSuffix("}")
                    .split(",")
                    .associate {
                        val parts = it.split(":", limit = 2)
                        parts[0].trim().trim('"') to parts[1].trim().trim('"')
                    }
            val id = op.getValue("id").toLong()
            rows =
                when (op.getValue("kind")) {
                    "insert" ->
                        (rows + row(id, op.getValue("height").toDouble())).sortedBy { it.order }
                    "delete" -> rows.filter { it.messageID != id }
                    else ->
                        rows.map {
                            if (it.messageID == id) row(id, op.getValue("height").toDouble())
                            else it
                        }
                }
            index.replace(rows, ReadingAnchor(), 640.0)
            assertEquals(
                op.getValue("expectedID").toLong(),
                index.anchorAt(op.getValue("offset").toDouble()).messageID,
            )
            assertEquals(op.getValue("expectedTotal").toDouble(), index.totalHeight, 0.0001)
        }
        index.replace((1L..100000L).map { row(it) }, ReadingAnchor(), 640.0)
        assertEquals(100000L, index.anchorAt(4399956.0).messageID)
        assertTrue(index.visible(1000.0, 100000.0, maxFragments = 128).count() <= 128)
        assertEquals(
            4400000.0 - 640,
            index.offset(ReadingAnchor(followsBottom = true), 640.0),
            0.0001,
        )
        index.replace(emptyList(), ReadingAnchor(), 640.0)
        assertTrue(index.visible(0.0, 640.0).isEmpty())
        assertNull(index.anchorAt(0.0).messageID)
    }
}
