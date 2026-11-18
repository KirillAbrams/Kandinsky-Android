package dev.kirill.kandinsky.chat.network

import org.json.JSONObject

data class StudioThread(
    val id: Long,
    val title: String,
    val lastSeq: Long,
    val unreadCount: Long,
    val pinned: Boolean,
    val archived: Boolean,
) {
    fun wire() =
        JSONObject()
            .put("id", id)
            .put("title", title)
            .put("lastSeq", lastSeq)
            .put("unreadCount", unreadCount)
            .put("pinned", pinned)
            .put("archived", archived)

    companion object {
        fun from(value: JSONObject) =
            StudioThread(
                value.getLong("id"),
                value.getString("title"),
                value.getLong("lastSeq"),
                value.getLong("unreadCount"),
                value.getBoolean("pinned"),
                value.getBoolean("archived"),
            )
    }
}

data class StudioRecentPage(val threads: List<StudioThread>, val nextOffset: Int?) {
    companion object {
        const val CATALOG_LIMIT = 200
    }
}
