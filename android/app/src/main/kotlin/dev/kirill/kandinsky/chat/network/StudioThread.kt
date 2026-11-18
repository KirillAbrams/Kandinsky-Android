// Хранит краткую карточку чата и сведения о странице списка. Карточка используется и при чтении
// ответа, и при записи в местное хранилище.

package dev.kirill.kandinsky.chat.network

import org.json.JSONObject

data class StudioThread(
    val id: Long,
    val title: String,
    val lastSeq: Long,
    val unreadCount: Long,
    val pinned: Boolean,
    val archived: Boolean,
    val activityAtMs: Long = 0,
    val generating: Boolean = false,
) {
    // Собирает все поля карточки в форме, которую принимает запись чатов. Без передачи времени и
    // признака генерации локальная копия потеряла бы полученное состояние.
    fun wire() =
        JSONObject()
            .put("id", id)
            .put("title", title)
            .put("lastSeq", lastSeq)
            .put("unreadCount", unreadCount)
            .put("pinned", pinned)
            .put("archived", archived)
            .put("activityAtMs", activityAtMs)
            .put("generating", generating)

    companion object {
        // Читает обязательные номер, название и признаки, а двум поздним полям даёт исходные
        // значения при отсутствии. Это сохраняет чтение прежних ответов без подмены обязательных
        // данных.
        fun from(value: JSONObject) =
            StudioThread(
                value.getLong("id"),
                value.getString("title"),
                value.getLong("lastSeq"),
                value.getLong("unreadCount"),
                value.getBoolean("pinned"),
                value.getBoolean("archived"),
                value.optLong("activityAtMs", 0),
                value.optBoolean("generating", false),
            )
    }
}

data class StudioRecentPage(val threads: List<StudioThread>, val nextOffset: Int?) {
    companion object {
        const val CATALOG_LIMIT = 200
    }
}
