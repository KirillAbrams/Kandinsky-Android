package dev.kirill.kandinsky.chat.chatlist

import dev.kirill.kandinsky.chat.network.StudioThread
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class StudioHistorySectionsTest {
    private fun row(id: Long, time: Long, pinned: Boolean = false) =
        StudioThread(id, "Чат $id", 0, 0, pinned, false, time)

    @Test fun calendarDaysIncludeTheWholeTwentyFiveHourYesterday() {
        val now = Instant.parse("2026-11-02T12:00:00Z")
        val rows = listOf(
            row(1, Instant.parse("2026-11-01T04:30:00Z").toEpochMilli()),
            row(2, Instant.parse("2026-11-02T06:00:00Z").toEpochMilli()),
            row(3, 0), row(4, 10, true), row(5, 20, true),
        )
        val groups = StudioHistorySections.make(rows, now, ZoneId.of("America/New_York"))
        assertEquals(listOf("Закреплённые", "Сегодня", "Вчера", "Ранее"), groups.map { it.title })
        assertEquals(listOf(listOf(5L, 4L), listOf(2L), listOf(1L), listOf(3L)), groups.map { it.threads.map { row -> row.id } })
    }

    @Test fun aNewRequestPromotesAnOldChatAndEmptyGroupsStayHidden() {
        val now = Instant.parse("2026-11-15T12:00:00Z")
        val groups = StudioHistorySections.make(listOf(row(2, 1), row(1, now.toEpochMilli())), now, ZoneId.of("UTC"))
        assertEquals(listOf("Сегодня", "Ранее"), groups.map { it.title })
        assertEquals(1L, groups.first().threads.first().id)
    }
}
