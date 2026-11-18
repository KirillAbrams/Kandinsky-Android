package dev.kirill.kandinsky.chat.media

import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.results.MediaGalleryWindow
import org.junit.Assert.*
import org.junit.Test

class MediaGalleryWindowTest {
    private fun row(
        id: Long,
        count: Int = 1,
        chat: Long = 2,
        kind: MessageKind = MessageKind.PHOTO,
    ) =
        Message(
            id,
            chat,
            OrderKey(id / 3, id),
            MessageDirection.INCOMING,
            id,
            kind,
            "",
            "Описание $id",
            null,
            1,
            (0 until count).map {
                Attachment("a-$id-$it", "network/$id-$it.jpg", "image/jpeg", 12, 120, 80, 1, 0, "")
            },
        )

    @Test
    fun mixedHistoryPreservesSelectionAndAlbumOrder() {
        val seed = row(10, 3)
        val window = MediaGalleryWindow(seed, 1)
        assertTrue(
            window.merge(
                HistoryPage(
                    listOf(row(12), row(9, kind = MessageKind.VOICE), seed, row(8), seed),
                    null,
                    null,
                )
            )
        )
        assertEquals(listOf("8/0", "10/0", "10/1", "10/2", "12/0"), window.entries.map { it.id })
        assertEquals("10/1", window.selected?.id)
        assertEquals(3, window.neighbours.size)
        assertFalse(window.select("missing"))
        assertEquals("10/1", window.selected?.id)
        assertFalse(window.merge(HistoryPage(listOf(row(14, chat = 3)), null, null)))
        assertEquals("Описание 10", window.selected?.caption)
    }

    @Test
    fun forwardReverseTraversalMatchesIndependentFlattenedHistoryAndStaysBounded() {
        val rows = (1..300).map { row(it.toLong(), 1 + it % 4) }
        val expected = (1..300).flatMap { id -> (0 until 1 + id % 4).map { "$id/$it" } }
        val window = MediaGalleryWindow(rows[69], 1)
        var reference = expected.indexOf("70/1")
        assertTrue(reference >= 0)
        fun page(id: Long): HistoryPage {
            val lower = (id.toInt() - 17).coerceIn(0, rows.size - 32)
            val upper = minOf(rows.size, lower + 32)
            return HistoryPage(
                rows.subList(lower, upper),
                if (lower > 0) rows[lower].order else null,
                if (upper < rows.size) rows[upper - 1].order else null,
            )
        }
        for (direction in listOf(1, -1)) repeat(320) {
            val selected = checkNotNull(window.selected)
            if (window.needsPage) {
                assertTrue(window.merge(page(selected.messageID)))
                assertEquals(selected.id, window.selected?.id)
            }
            reference += direction
            assertTrue(window.select(expected[reference]))
            assertEquals(expected[reference], window.selected?.id)
            assertTrue(window.entries.size <= 96)
            assertTrue(window.neighbours.size <= 3)
        }
    }

    @Test
    fun oversizedAlbumCanBeTraversedWithoutLosingTrimmedAttachments() {
        val album = row(1, 200)
        val window = MediaGalleryWindow(album, 150)
        for (index in 151 until 200) {
            if (window.needsPage) window.merge(HistoryPage(listOf(album), null, null))
            assertTrue(window.select("1/$index"))
            assertEquals("1/$index", window.selected?.id)
            assertTrue(window.entries.size <= 96)
        }
        for (index in 198 downTo 0) {
            if (window.needsPage) window.merge(HistoryPage(listOf(album), null, null))
            assertTrue(window.select("1/$index"))
            assertEquals("1/$index", window.selected?.id)
        }
        assertFalse(window.select("1/-1"))
        assertEquals("1/0", window.selected?.id)
    }

    @Test
    fun deletingSelectionChoosesAdjacentItemAndCanEmptyTheViewer() {
        val window = MediaGalleryWindow(row(3))
        window.merge(HistoryPage(listOf(row(1), row(2), row(3), row(4)), null, null))
        val chat = ChatSummary(2, "QA", "", 4, 4, 0, false)
        window.apply(MessageDelta(removedIDs = listOf(3), chat = chat))
        assertEquals("4/0", window.selected?.id)
        window.apply(MessageDelta(removedIDs = listOf(1, 2, 4), chat = chat))
        assertNull(window.selected)
        assertTrue(window.neighbours.isEmpty())
    }
}
