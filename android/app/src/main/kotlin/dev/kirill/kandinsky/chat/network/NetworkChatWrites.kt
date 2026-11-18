package dev.kirill.kandinsky.chat.network

import android.database.sqlite.SQLiteDatabase
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.storage.*
import org.json.JSONObject

private fun NetworkStore.studioArchive(db: SQLiteDatabase, chat: Long, archived: Boolean) {
    val flags = ChatServerState.state(db, chat)
    if (flags.optBoolean("archived") == archived) return
    flags.put("archived", archived)
    db.execSQL(
        "INSERT OR REPLACE INTO net_chat_states VALUES(?,?)",
        arrayOf<Any?>(chat, flags.toString()),
    )
}

// Скрываем старую историю на устройстве, чтобы задержавшаяся страница не вернула удалённый чат.
internal fun NetworkStore.retireChat(db: SQLiteDatabase, chat: Long): List<Long>? {
    val exists =
        db.rawQuery("SELECT id FROM chats WHERE id=?", arrayOf(chat.toString())).use {
            it.moveToFirst()
        }
    if (!exists) return null
    val flags = ChatServerState.state(db, chat)
    if (flags.optBoolean("hidden")) return null
    flags
        .put("id", chat)
        .put("version", flags.optLong("version", 0))
        .put("clearThrough", Long.MAX_VALUE)
        .put("hidden", true)
    return ChatServerState.applyState(db, chat, flags)
}

// Старые базы могут хранить границу уже прочитанной истории.
private fun legacyReadPosition(db: SQLiteDatabase, chat: Long): Long =
    db.rawQuery("SELECT value FROM net_state WHERE key=?", arrayOf("ownRead:$chat")).use {
        (if (it.moveToFirst()) it.getString(0).toLong() else 0L).also { through ->
            require(through >= 0)
        }
    }

internal fun NetworkStore.mergeChatRows(
    db: SQLiteDatabase,
    json: JSONObject,
    touched: MutableMap<Long, MutableSet<Long>>,
    limit: Int = 128,
    preserveDirectoryMetadata: Boolean = false,
): Map<Long, List<Long>> {
    val removed = linkedMapOf<Long, List<Long>>()
    val chats = json.getJSONArray("chats")
    require(chats.length() <= limit)
    for (i in 0 until chats.length()) {
        val chat = chats.getJSONObject(i)
        val id = chat.getLong("id")
        val title = chat.getString("title")
        val seq = chat.getLong("lastSeq")
        val unread = chat.getLong("unreadCount")
        val incomingState = chat.optJSONObject("chatState")
        if (
            incomingState != null &&
                incomingState.getLong("version") <
                    ChatServerState.state(db, id).optLong("version", -1)
        )
            continue
        require(seq >= 0 && unread >= 0)
        db.execSQL("INSERT OR IGNORE INTO chats(id,title) VALUES(?,?)", arrayOf(id, title))
        db.execSQL(
            "UPDATE chats SET title=?,pinned=? WHERE id=?",
            arrayOf(title, if (chat.getBoolean("pinned")) 1 else 0, id),
        )
        val previouslyRead = legacyReadPosition(db, id)
        val later =
            db.rawQuery(
                    "SELECT count(*) " +
                        "FROM net_messages n " +
                        "JOIN messages m ON m.id=n.localID " +
                        "WHERE m.chatId=? AND n.senderID<>? AND n.serverSeq>? AND " +
                        "n.serverSeq>?",
                    arrayOf(
                        id.toString(),
                        userID.toString(),
                        seq.toString(),
                        previouslyRead.toString(),
                    ),
                )
                .use {
                    it.moveToFirst()
                    it.getLong(0)
                }
        require(unread <= Long.MAX_VALUE - later)
        db.execSQL("UPDATE chats SET unreadCount=? WHERE id=?", arrayOf(unread + later, id))
        db.execSQL(
            "INSERT INTO net_chats(chatID,lastSeq,hasOlder,title) " +
                "VALUES(?,?,1,?) " +
                "ON CONFLICT(chatID) " +
                "DO UPDATE " +
                "SET lastSeq=MAX(lastSeq,excluded.lastSeq)," +
                "title=excluded.title",
            arrayOf(id, seq, title),
        )
        val prior =
            db.rawQuery("SELECT value FROM net_state WHERE key=?", arrayOf("summarySeq:$id"))
                .use { if (it.moveToFirst()) it.getString(0).toLong() else 0L }
        state(db, "summarySeq:$id", maxOf(prior, seq).toString())
        touched[id] = linkedSetOf()
        removed[id] = ChatServerState.applyState(db, id, chat.optJSONObject("chatState"))
        if (chat.has("archived")) studioArchive(db, id, chat.getBoolean("archived"))
        rememberStudioDirectory(db, chat, preserveKnown = preserveDirectoryMetadata)
        chat.optJSONObject("lastMessage")?.let { wire ->
            merge(db, wire)?.let { touched.getValue(id).add(it) }
        }
    }
    return removed
}

// Сводка не зависит от загруженной страницы сообщений и переживает закрытие приложения.
internal fun NetworkStore.rememberStudioDirectory(
    db: SQLiteDatabase, chat: JSONObject, preserveKnown: Boolean = false,
) {
    if (!chat.has("activityAtMs")) return
    val activity = chat.getLong("activityAtMs")
    require(activity >= 0)
    val key = "studioDirectory:${chat.getLong("id")}"
    if (preserveKnown && db.rawQuery("SELECT 1 FROM net_state WHERE key=?", arrayOf(key))
        .use { it.moveToFirst() }) return
    state(
        db,
        key,
        JSONObject()
            .put("activityAtMs", activity)
            .put("generating", chat.optBoolean("generating", false))
            .toString(),
    )
}

internal fun NetworkStore.mergeChatList(
    db: SQLiteDatabase,
    json: JSONObject,
    studioRecent: Boolean = false,
    preserveDirectoryMetadata: Boolean = false,
): List<MessageDelta> =
    NetworkStore.transaction(db) {
        val touched = linkedMapOf<Long, MutableSet<Long>>()
        val removed =
            mergeChatRows(
                db,
                json,
                touched,
                if (studioRecent) StudioRecentPage.CATALOG_LIMIT else 128,
                preserveDirectoryMetadata,
            )
        if (studioRecent) rememberStudioRecent(db, json)
        deltas(db, touched, removed, MessageChangeReason.SYNC)
    }

internal fun NetworkStore.rememberStudioRecent(db: SQLiteDatabase, json: JSONObject) {
    val chats = json.getJSONArray("chats")
    val order =
        listOf(System.currentTimeMillis()) +
            List(chats.length()) { chats.getJSONObject(it).getLong("id") }
    state(db, "studioRecentOrder", order.joinToString(","))
}

internal fun NetworkStore.loadRecentChats(db: SQLiteDatabase): StudioRecentPage {
    data class Entry(val thread: StudioThread, val activity: Long, val rank: Int)
    val index =
        db.rawQuery("SELECT value FROM net_state WHERE key='studioRecentOrder'", null).use {
            if (it.moveToFirst()) it.getString(0).split(',').mapNotNull(String::toLongOrNull)
            else emptyList()
        }
    val refreshedAt = index.firstOrNull() ?: 0L
    val ids = index.drop(1)
    val order = ids.withIndex().associate { it.value to it.index }
    val entries =
        db.rawQuery(
                """
        SELECT c.id,c.title,c.unreadCount,c.pinned,c.updatedSortKey,n.lastSeq,s.payload,d.value
        FROM chats c JOIN net_chats n ON n.chatID=c.id LEFT JOIN net_chat_states s ON s.chatID=c.id
        LEFT JOIN net_state d ON d.key='studioDirectory:' || c.id
        """
                    .trimIndent(),
                null,
            )
            .use { rows ->
                buildList {
                    while (rows.moveToNext()) {
                        val flags = if (rows.isNull(6)) null else JSONObject(rows.getString(6))
                        if (
                            flags != null &&
                                (flags.optBoolean("archived") ||
                                    flags.optBoolean("hidden") ||
                                    flags.optBoolean("left"))
                        )
                            continue
                        val id = rows.getLong(0)
                        val directory = if (rows.isNull(7)) null else JSONObject(rows.getString(7))
                        val activity = directory?.optLong("activityAtMs") ?: rows.getLong(4)
                        val thread =
                            StudioThread(
                                id,
                                rows.getString(1),
                                rows.getLong(5),
                                rows.getLong(2),
                                rows.getInt(3) != 0,
                                archived = false,
                                activityAtMs = activity,
                                generating = directory?.optBoolean("generating") ?: false,
                            )
                        add(
                            Entry(
                                thread,
                                activity,
                                if (activity > refreshedAt) -1 else order[id] ?: Int.MAX_VALUE,
                            )
                        )
                    }
                }
            }
    val rows =
        entries
            .sortedWith(
                compareByDescending<Entry> { it.thread.pinned }
                    .thenByDescending { it.activity }
                    .thenBy { it.rank }
                    .thenBy { it.thread.id }
            )
            .map { it.thread }
    return StudioRecentPage(rows, ids.size.takeIf { it == StudioRecentPage.CATALOG_LIMIT })
}

internal fun NetworkStore.containsUnknownChat(db: SQLiteDatabase, json: JSONObject): Boolean {
    if (json.has("chats")) return false
    val events = json.getJSONArray("events")
    require(events.length() <= 128)
    val ids =
        (0 until events.length()).map { events.getJSONObject(it).getLong("chatId") }.toSet()
    if (ids.isEmpty()) return false
    return db.rawQuery(
            "SELECT count(*) FROM chats WHERE id IN (${ids.joinToString(","){"?"}})",
            ids.map { it.toString() }.toTypedArray(),
        )
        .use {
            it.moveToFirst()
            it.getInt(0) != ids.size
        }
}
