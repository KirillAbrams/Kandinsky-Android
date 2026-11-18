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
