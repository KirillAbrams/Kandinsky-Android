// Готовит разделы недавних чатов для боковой панели Андроида. Границы дней зависят от часового
// пояса устройства.

package dev.kirill.kandinsky.chat.chatlist

import dev.kirill.kandinsky.chat.network.StudioThread
import java.time.Instant
import java.time.ZoneId

data class StudioHistorySection(val title: String, val threads: List<StudioThread>)

object StudioHistorySections {
    // Считает начало дней в переданном поясе, упорядочивает чаты по времени и номеру и пропускает
    // пустые группы. Фиксированные сутки дали бы ошибку в день перевода часов.
    fun make(
        rows: List<StudioThread>,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<StudioHistorySection> {
        val date = now.atZone(zone).toLocalDate()
        val today = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val yesterday = date.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val ordered =
            rows.sortedWith(
                compareByDescending<StudioThread> { it.activityAtMs }.thenByDescending { it.id }
            )
        val groups = listOf<Pair<String, (StudioThread) -> Boolean>>(
            "Закреплённые" to { it.pinned },
            "Сегодня" to { !it.pinned && it.activityAtMs >= today },
            "Вчера" to { !it.pinned && it.activityAtMs >= yesterday && it.activityAtMs < today },
            "Ранее" to { !it.pinned && it.activityAtMs < yesterday },
        )
        return groups.mapNotNull { (title, matches) ->
            ordered.filter(matches).takeIf { it.isNotEmpty() }?.let { StudioHistorySection(title, it) }
        }
    }
}
