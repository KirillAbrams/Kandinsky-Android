package dev.kirill.kandinsky.chat.network

import android.database.sqlite.SQLiteDatabase
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.storage.*
import org.json.JSONArray
import org.json.JSONObject

internal fun NetworkStore.applyInitialSnapshot(db: SQLiteDatabase, json: JSONObject): List<MessageDelta> =
    NetworkStore.transaction(db) {
        require(json.getInt("version") == 1 && json.getLong("userId") == userID)
        val touched = linkedMapOf<Long, MutableSet<Long>>()
        val removed = mergeChatRows(db, json, touched, StudioRecentPage.CATALOG_LIMIT)
        val messages = json.getJSONArray("messages")
        require(messages.length() <= 128)
        for (i in 0 until messages.length()) {
            val message = messages.getJSONObject(i)
            merge(db, message)?.let {
                touched.getOrPut(message.getLong("chatId")) { linkedSetOf() }.add(it)
            }
        }
        state(db, "cursor", json.getLong("cursor").toString())
        rememberStudioRecent(db, json)
        deltas(db, touched, removed, MessageChangeReason.SYNC)
    }

internal fun NetworkStore.applyEventBatch(db: SQLiteDatabase, json: JSONObject): List<MessageDelta> =
    NetworkStore.transaction(db) {
        val events = json.getJSONArray("events")
        require(events.length() <= 128)
        val current = cursor(db)
        val next =
            SyncSequence.next(
                current,
                (0 until events.length()).map { events.getJSONObject(it).getLong("seq") },
            )
        val touched = linkedMapOf<Long, MutableSet<Long>>()
        val removed = linkedMapOf<Long, MutableList<Long>>()
        json.optJSONArray("chats")?.let { catalog ->
            require(catalog.length() <= 128)
            val missing = JSONArray()
            for (i in 0 until catalog.length()) {
                val entry = catalog.getJSONObject(i)
                if (json.getLong("cursor") > current) rememberStudioDirectory(db, entry)
                val exists =
                    db.rawQuery(
                            "SELECT id FROM chats WHERE id=?",
                            arrayOf(entry.getLong("id").toString()),
                        )
                        .use { it.moveToFirst() }
                if (!exists) missing.put(entry)
            }
            if (missing.length() > 0)
                mergeChatRows(db, JSONObject().put("chats", missing), touched).forEach {
                    (chat, ids) ->
                    removed.getOrPut(chat) { arrayListOf() }.addAll(ids)
                }
        }
        for (i in 0 until events.length()) {
            val event = events.getJSONObject(i)
            if (event.getLong("seq") <= current) continue
            val chat = event.getLong("chatId")
            val kind = event.getString("kind")
            if (kind == "kandinsky.retired") {
                retireStudioThread(db, chat)?.let { ids ->
                    touched[chat] = linkedSetOf()
                    removed.getOrPut(chat) { arrayListOf() }.addAll(ids)
                }
                continue
            }
            val ids = touched.getOrPut(chat) { linkedSetOf() }
            removed
                .getOrPut(chat) { arrayListOf() }
                .addAll(ChatServerState.applyState(db, chat, event.optJSONObject("chatState")))
            event.optJSONArray("removedMedia")?.let { media ->
                require(media.length() <= 10)
                for (j in 0 until media.length()) {
                    val item = media.getJSONObject(j)
                    val removal =
                        requireNotNull(
                            StudioMediaRemoval.parse(
                                item.getString("blobId"),
                                item.getString("previewBlobId"),
                            )
                        )
                    state(db, removal.key, removal.preview)
                }
            }
            when (kind) {
                "message" -> {
                    val message = event.getJSONObject("message")
                    val known =
                        db.rawQuery(
                                "SELECT 1 FROM net_messages WHERE serverID=?",
                                arrayOf(message.getLong("id").toString()),
                            )
                            .use { it.moveToFirst() }
                    merge(db, message)?.let { ids.add(it) }
                    val summarySeq =
                        db.rawQuery(
                                "SELECT value FROM net_state WHERE key=?",
                                arrayOf("summarySeq:$chat"),
                            )
                            .use { if (it.moveToFirst()) it.getString(0).toLong() else 0L }
                    if (
                        !known &&
                            message.getLong("senderId") != userID &&
                            message.getLong("seq") > summarySeq
                    )
                        db.execSQL(
                            "UPDATE chats SET unreadCount=unreadCount+1 WHERE id=?",
                            arrayOf(chat),
                        )
                }
                "deleted" -> {
                    val server = event.getLong("messageId")
                    db.execSQL(
                        "INSERT OR IGNORE INTO net_tombstones VALUES(?,?)",
                        arrayOf(server, chat),
                    )
                    db.delete(
                        "net_pending_content",
                        "serverID=? AND chatID=?",
                        arrayOf(server.toString(), chat.toString()),
                    )
                    val localID =
                        db.rawQuery(
                                "SELECT localID FROM net_messages WHERE serverID=?",
                                arrayOf(server.toString()),
                            )
                            .use { if (it.moveToFirst()) it.getLong(0) else null }
                    if (localID != null) {
                        db.execSQL(
                            "UPDATE net_messages SET tombstone=1 WHERE localID=?",
                            arrayOf(localID),
                        )
                        db.delete("messages", "id=?", arrayOf(localID.toString()))
                        removed.getOrPut(chat) { arrayListOf() }.add(localID)
                    }
                }
                else -> error("Unsupported persistent event")
            }
        }
        require(next == json.getLong("cursor"))
        state(db, "cursor", next.toString())
        deltas(db, touched, removed, MessageChangeReason.SYNC)
    }

internal fun NetworkStore.applyHistoryPage(db: SQLiteDatabase, chat: Long, json: JSONObject): List<MessageDelta> =
    NetworkStore.transaction(db) {
        val rows = json.getJSONArray("messages")
        require(rows.length() <= 128)
        val ids = linkedSetOf<Long>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            require(row.getLong("chatId") == chat)
            merge(db, row)?.let(ids::add)
        }
        db.execSQL(
            "UPDATE net_chats SET hasOlder=? WHERE chatID=?",
            arrayOf(if (json.getBoolean("hasOlder")) 1 else 0, chat),
        )
        deltas(db, mapOf(chat to ids), emptyMap(), MessageChangeReason.HISTORY)
    }
