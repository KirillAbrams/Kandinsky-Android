package dev.kirill.kandinsky.chat.network

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.storage.*
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class ContentBatch(val chatID: Long, val messageIDs: List<Long>)

class NetworkStore(val local: SQLiteChatRepository, val userID: Long) {
    // Записываем просмотр на телефоне до отправки. После обрыва связи он останется в очереди.
    fun queueContent(db: SQLiteDatabase, localID: Long): List<MessageDelta> =
        transaction(db) {
            val row =
                db.rawQuery(
                        "SELECT m.chatId,m.kind,n.serverID,n.senderID " +
                            "FROM messages m " +
                            "JOIN net_messages n ON n.localID=m.id " +
                            "WHERE m.id=? AND n.tombstone=0",
                        arrayOf(localID.toString()),
                    )
                    .use { c ->
                        if (c.moveToFirst() && !c.isNull(2))
                            arrayOf<Any>(c.getLong(0), c.getString(1), c.getLong(2), c.getLong(3))
                        else null
                    } ?: return@transaction emptyList()
            val chat = row[0] as Long
            val server = row[2] as Long
            if (
                server <= 0 ||
                    row[3] as Long == userID ||
                    row[1] !in listOf("voice", "round", "video", "photo", "gif")
            )
                return@transaction emptyList()
            if (
                db.rawQuery(
                        "SELECT 1 FROM net_content_receipts WHERE serverID=? AND chatID=? AND userID=?",
                        arrayOf(server.toString(), chat.toString(), userID.toString()),
                    )
                    .use { it.moveToFirst() }
            )
                return@transaction emptyList()
            db.execSQL(
                "INSERT INTO net_pending_content(serverID,chatID) " +
                    "VALUES(?,?) " +
                    "ON CONFLICT(serverID) " +
                    "DO UPDATE " +
                    "SET errorCode=NULL " +
                    "WHERE chatID=excluded.chatID",
                arrayOf(server, chat),
            )
            if (
                db.rawQuery(
                        "SELECT 1 FROM message_content WHERE messageID=? AND consumed=1",
                        arrayOf(localID.toString()),
                    )
                    .use { it.moveToFirst() }
            )
                return@transaction emptyList()
            db.execSQL(
                "INSERT OR REPLACE INTO message_content(messageID,consumed) VALUES(?,1)",
                arrayOf(localID),
            )
            db.execSQL("UPDATE messages SET revision=revision+1 WHERE id=?", arrayOf(localID))
            deltas(db, mapOf(chat to setOf(localID)), emptyMap(), MessageChangeReason.SYNC)
        }

    // Сервер принимает номера одного чата за запрос. Остальные чаты дождутся своей очереди.
    fun pendingContent(db: SQLiteDatabase): List<ContentBatch> {
        val batches = ArrayList<ContentBatch>(4)
        db.rawQuery(
                "SELECT serverID,chatID " +
                    "FROM net_pending_content " +
                    "WHERE errorCode IS NULL " +
                    "ORDER BY chatID,serverID LIMIT 128",
                null,
            )
            .use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val chat = c.getLong(1)
                    val last = batches.lastOrNull()
                    if (last != null && last.chatID == chat && last.messageIDs.size < 32) {
                        batches[batches.lastIndex] = ContentBatch(chat, last.messageIDs + id)
                    } else {
                        if (batches.size == 4) break
                        batches.add(ContentBatch(chat, listOf(id)))
                    }
                }
            }
        return batches
    }

    // Удаляем из очереди только номера из отправленной группы. Пока шёл запрос, могли появиться.
    fun confirmContent(
        db: SQLiteDatabase,
        json: JSONObject,
        requestID: String,
        batch: ContentBatch,
    ): List<MessageDelta> {
        require(json.getString("type") == "content_applied")
        val expected = java.util.UUID.fromString(requestID)
        require(java.util.UUID.fromString(json.getString("requestId")) == expected)
        require(contentID(json.opt("chatId")) == batch.chatID && batch.chatID > 0)
        require(
            batch.messageIDs.size in 1..32 &&
                batch.messageIDs.all { it > 0 } &&
                batch.messageIDs.toSet().size == batch.messageIDs.size
        )
        val values = json.getJSONArray("messageIds")
        require(values.length() == batch.messageIDs.size)
        val ids = (0 until values.length()).map { contentID(values.get(it)) }
        require(ids.toSet() == batch.messageIDs.toSet())
        return transaction(db) {
            val touched = linkedMapOf<Long, MutableSet<Long>>()
            ids.forEach { contentReceipt(db, it, batch.chatID, userID, touched) }
            deltas(db, touched, emptyMap(), MessageChangeReason.SYNC)
        }
    }

    fun cursor(db: SQLiteDatabase): Long =
        db.rawQuery("SELECT value FROM net_state WHERE key='cursor'", null).use {
            if (it.moveToFirst()) it.getString(0).toLong() else 0L
        }

    fun initialized(db: SQLiteDatabase) =
        db.rawQuery("SELECT 1 FROM net_state WHERE key='cursor'", null).use { it.moveToFirst() }

    private fun contentID(value: Any?): Long =
        (value as? Number)?.toString()?.toLongOrNull()?.also { require(it > 0) }
            ?: error("Invalid content identity")

    // Подтверждение просмотра может прийти раньше сообщения. Сохраняем его до загрузки истории.
    private fun restoreContent(
        db: SQLiteDatabase,
        server: Long,
        expectedChat: Long? = null,
    ): Pair<Long, Long>? {
        val row =
            db.rawQuery(
                    "SELECT m.id,m.chatId,m.kind,n.senderID " +
                        "FROM net_messages n " +
                        "JOIN messages m ON m.id=n.localID " +
                        "WHERE n.serverID=?",
                    arrayOf(server.toString()),
                )
                .use { c ->
                    if (c.moveToFirst())
                        arrayOf<Any>(c.getLong(0), c.getLong(1), c.getString(2), c.getLong(3))
                    else null
                } ?: return null
        val id = row[0] as Long
        val chat = row[1] as Long
        require(expectedChat == null || expectedChat == chat)
        require(
            !db.rawQuery(
                    "SELECT 1 FROM net_content_receipts WHERE serverID=? AND chatID<>? LIMIT 1",
                    arrayOf(server.toString(), chat.toString()),
                )
                .use { it.moveToFirst() }
        )
        val actor = if (row[3] as Long == userID) "userID<>?" else "userID=?"
        if (
            !db.rawQuery(
                    "SELECT 1 FROM net_content_receipts WHERE serverID=? AND chatID=? AND $actor LIMIT 1",
                    arrayOf(server.toString(), chat.toString(), userID.toString()),
                )
                .use { it.moveToFirst() }
        )
            return null
        require(row[2] in listOf("voice", "round", "video", "photo", "gif"))
        if (
            db.rawQuery(
                    "SELECT 1 FROM message_content WHERE messageID=? AND consumed=1",
                    arrayOf(id.toString()),
                )
                .use { it.moveToFirst() }
        )
            return null
        db.execSQL(
            "INSERT OR REPLACE INTO message_content(messageID,consumed) VALUES(?,1)",
            arrayOf(id),
        )
        db.execSQL("UPDATE messages SET revision=revision+1 WHERE id=?", arrayOf(id))
        return chat to id
    }

    private fun contentReceipt(
        db: SQLiteDatabase,
        server: Long,
        chat: Long,
        actor: Long,
        touched: MutableMap<Long, MutableSet<Long>>,
    ) {
        require(server > 0 && chat > 0 && actor > 0)
        require(
            !db.rawQuery(
                    "SELECT 1 FROM net_content_receipts WHERE serverID=? AND chatID<>? LIMIT 1",
                    arrayOf(server.toString(), chat.toString()),
                )
                .use { it.moveToFirst() }
        )
        db.execSQL(
            "INSERT OR IGNORE INTO net_content_receipts(serverID,userID,chatID) VALUES(?,?,?)",
            arrayOf(server, actor, chat),
        )
        if (actor == userID)
            db.delete(
                "net_pending_content",
                "serverID=? AND chatID=?",
                arrayOf(server.toString(), chat.toString()),
            )
        restoreContent(db, server, chat)?.let { (target, id) ->
            touched.getOrPut(target) { linkedSetOf() }.add(id)
        }
    }

    private fun snapshotContent(
        db: SQLiteDatabase,
        json: JSONObject,
        touched: MutableMap<Long, MutableSet<Long>>,
    ) {
        if (!json.has("contentReceipts")) return
        val rows = json.getJSONArray("contentReceipts")
        require(rows.length() <= 128)
        for (i in 0 until rows.length()) {
            val receipt = rows.getJSONObject(i)
            val server = contentID(receipt.opt("messageId"))
            val actor = contentID(receipt.opt("userId"))
            val chat =
                db.rawQuery(
                        "SELECT m.chatId FROM net_messages n JOIN messages m ON m.id=n.localID WHERE n.serverID=?",
                        arrayOf(server.toString()),
                    )
                    .use { if (it.moveToFirst()) it.getLong(0) else null }
            if (chat != null) contentReceipt(db, server, chat, actor, touched)
        }
    }

    private fun eventContent(
        db: SQLiteDatabase,
        event: JSONObject,
        chat: Long,
        touched: MutableMap<Long, MutableSet<Long>>,
    ) {
        if (!event.has("contentMessageIds")) return
        val values = event.getJSONArray("contentMessageIds")
        require(values.length() in 1..32)
        val ids = (0 until values.length()).map { contentID(values.get(it)) }
        val actor = contentID(event.opt("userId"))
        require(ids.toSet().size == ids.size)
        ids.forEach { contentReceipt(db, it, chat, actor, touched) }
    }

    private fun state(db: SQLiteDatabase, key: String, value: String) {
        db.execSQL("INSERT OR REPLACE INTO net_state(key,value) VALUES(?,?)", arrayOf(key, value))
    }

    private fun peerRead(db: SQLiteDatabase, chat: Long): Long =
        db.rawQuery("SELECT value FROM net_state WHERE key=?", arrayOf("peerRead:$chat")).use {
            (if (it.moveToFirst()) it.getString(0).toLong() else 0L).also { through ->
                require(through >= 0)
            }
        }

    private fun ownRead(db: SQLiteDatabase, chat: Long): Long =
        db.rawQuery("SELECT value FROM net_state WHERE key=?", arrayOf("ownRead:$chat")).use {
            (if (it.moveToFirst()) it.getString(0).toLong() else 0L).also { through ->
                require(through >= 0)
            }
        }

    fun needsReadRefresh(db: SQLiteDatabase): Boolean =
        db.rawQuery(
                "SELECT 1 FROM net_state WHERE key LIKE 'readRefresh:%' AND value='1' LIMIT 1",
                null,
            )
            .use { it.moveToFirst() }

    // Поздняя страница истории не должна снова сделать уже прочитанные сообщения непрочитанными.
    private fun applyOwnRead(db: SQLiteDatabase, chat: Long, through: Long) {
        require(through >= 0)
        if (through <= ownRead(db, chat)) return
        state(db, "ownRead:$chat", through.toString())
        val last =
            db.rawQuery("SELECT lastSeq FROM net_chats WHERE chatID=?", arrayOf(chat.toString()))
                .use { if (it.moveToFirst()) it.getLong(0) else 0L }
        if (through >= last) {
            db.execSQL("UPDATE chats SET unreadCount=0 WHERE id=?", arrayOf(chat))
            state(db, "readRefresh:$chat", "0")
        } else {
            // Сохранённого хвоста недостаточно для подсчёта более ранних непрочитанных сообщений
            state(db, "readRefresh:$chat", "1")
        }
    }

    private fun applyPeerRead(
        db: SQLiteDatabase,
        chat: Long,
        through: Long,
        touched: MutableMap<Long, MutableSet<Long>>,
    ) {
        require(through >= 0)
        val previous = peerRead(db, chat)
        val latest = maxOf(previous, through)
        if (latest > previous) state(db, "peerRead:$chat", latest.toString())
        val args = arrayOf(chat.toString(), userID.toString(), latest.toString())
        val predicate =
            "m.chatId=? AND n.senderID=? AND n.serverID IS NOT NULL AND n.serverSeq>0 AND " +
                "n.serverSeq<=? AND m.deliveryState<>'read'"
        db.rawQuery(
                "SELECT n.localID FROM net_messages n JOIN messages m ON m.id=n.localID WHERE $predicate",
                args,
            )
            .use {
                while (it.moveToNext()) touched.getOrPut(chat) { linkedSetOf() }.add(it.getLong(0))
            }
        db.execSQL(
            "UPDATE messages " +
                "SET deliveryState='read',revision=revision+1 " +
                "WHERE id IN (SELECT n.localID " +
                "FROM net_messages n " +
                "JOIN messages m ON m.id=n.localID " +
                "WHERE $predicate)",
            args,
        )
    }

    fun bootstrap(db: SQLiteDatabase, json: JSONObject): List<MessageDelta> =
        transaction(db) {
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
            snapshotContent(db, json, touched)
            state(db, "cursor", json.getLong("cursor").toString())
            rememberStudioRecent(db, json)
            deltas(db, touched, removed, MessageChangeReason.SYNC)
        }

    private fun studioArchive(db: SQLiteDatabase, chat: Long, archived: Boolean) {
        val flags = ChatServerState.state(db, chat)
        if (flags.optBoolean("archived") == archived) return
        flags.put("archived", archived)
        db.execSQL(
            "INSERT OR REPLACE INTO net_chat_states VALUES(?,?)",
            arrayOf<Any?>(chat, flags.toString()),
        )
    }

    // Скрываем старую историю на устройстве, чтобы задержавшаяся страница не вернула удалённый чат.
    fun retireStudioThread(db: SQLiteDatabase, chat: Long): List<Long>? {
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

    private fun mergeChatRows(
        db: SQLiteDatabase,
        json: JSONObject,
        touched: MutableMap<Long, MutableSet<Long>>,
        limit: Int = 128,
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
            val read = chat.optLongOrNull("readSeq")
            val previousRead = ownRead(db, id)
            require(seq >= 0 && unread >= 0 && (read == null || read in 0..seq))
            db.execSQL("INSERT OR IGNORE INTO chats(id,title) VALUES(?,?)", arrayOf(id, title))
            db.execSQL(
                "UPDATE chats SET title=?,pinned=? WHERE id=?",
                arrayOf(title, if (chat.getBoolean("pinned")) 1 else 0, id),
            )
            if (read == null || read >= previousRead) {
                val latest = maxOf(previousRead, read ?: 0)
                if (latest > previousRead) state(db, "ownRead:$id", latest.toString())
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
                                latest.toString(),
                            ),
                        )
                        .use {
                            it.moveToFirst()
                            it.getLong(0)
                        }
                require(unread <= Long.MAX_VALUE - later)
                db.execSQL("UPDATE chats SET unreadCount=? WHERE id=?", arrayOf(unread + later, id))
                state(db, "readRefresh:$id", "0")
            } else state(db, "readRefresh:$id", "1")
            db.execSQL(
                "INSERT INTO net_chats(chatID,lastSeq,hasOlder,peerUserID,title) " +
                    "VALUES(?,?,1,?,?) " +
                    "ON CONFLICT(chatID) " +
                    "DO UPDATE " +
                    "SET lastSeq=MAX(lastSeq,excluded.lastSeq),peerUserID=excluded.peerUserID," +
                    "title=excluded.title",
                arrayOf<Any?>(id, seq, chat.optLongOrNull("peerUserId"), title),
            )
            val prior =
                db.rawQuery("SELECT value FROM net_state WHERE key=?", arrayOf("summarySeq:$id"))
                    .use { if (it.moveToFirst()) it.getString(0).toLong() else 0L }
            state(db, "summarySeq:$id", maxOf(prior, seq).toString())
            touched[id] = linkedSetOf()
            removed[id] = ChatServerState.applyState(db, id, chat.optJSONObject("chatState"))
            if (chat.has("archived")) studioArchive(db, id, chat.getBoolean("archived"))
            chat.optLongOrNull("peerReadSeq")?.let { applyPeerRead(db, id, it, touched) }
            chat.optJSONObject("lastMessage")?.let { wire ->
                merge(db, wire)?.let { touched.getValue(id).add(it) }
            }
        }
        return removed
    }

    fun mergeChats(
        db: SQLiteDatabase,
        json: JSONObject,
        studioRecent: Boolean = false,
    ): List<MessageDelta> =
        transaction(db) {
            val touched = linkedMapOf<Long, MutableSet<Long>>()
            val removed =
                mergeChatRows(
                    db,
                    json,
                    touched,
                    if (studioRecent) StudioRecentPage.CATALOG_LIMIT else 128,
                )
            if (studioRecent) rememberStudioRecent(db, json)
            deltas(db, touched, removed, MessageChangeReason.SYNC)
        }

    private fun rememberStudioRecent(db: SQLiteDatabase, json: JSONObject) {
        val chats = json.getJSONArray("chats")
        val order =
            listOf(System.currentTimeMillis()) +
                List(chats.length()) { chats.getJSONObject(it).getLong("id") }
        state(db, "studioRecentOrder", order.joinToString(","))
    }

    fun studioRecent(db: SQLiteDatabase): StudioRecentPage {
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
            SELECT c.id,c.title,c.unreadCount,c.pinned,c.updatedSortKey,n.lastSeq,s.payload
            FROM chats c JOIN net_chats n ON n.chatID=c.id LEFT JOIN net_chat_states s ON s.chatID=c.id
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
                            val activity = rows.getLong(4)
                            val thread =
                                StudioThread(
                                    id,
                                    rows.getString(1),
                                    rows.getLong(5),
                                    rows.getLong(2),
                                    rows.getInt(3) != 0,
                                    archived = false,
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
                        .thenBy { it.rank }
                        .thenByDescending { it.activity }
                        .thenBy { it.thread.id }
                )
                .map { it.thread }
        return StudioRecentPage(rows, ids.size.takeIf { it == StudioRecentPage.CATALOG_LIMIT })
    }

    fun hasUnknownChats(db: SQLiteDatabase, json: JSONObject): Boolean {
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

    fun events(db: SQLiteDatabase, json: JSONObject): List<MessageDelta> =
        transaction(db) {
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
                val missing = JSONArray()
                for (i in 0 until catalog.length()) {
                    val entry = catalog.getJSONObject(i)
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
                    "delivered" -> {
                        val values = event.optJSONArray("messageIds") ?: JSONArray()
                        for (j in 0 until values.length()) {
                            db.rawQuery(
                                    "SELECT localID FROM net_messages WHERE serverID=? AND senderID=?",
                                    arrayOf(values.getLong(j).toString(), userID.toString()),
                                )
                                .use {
                                    if (it.moveToFirst()) {
                                        val id = it.getLong(0)
                                        ids.add(id)
                                        db.execSQL(
                                            "UPDATE messages " +
                                                "SET deliveryState='delivered',revision=revision+1 " +
                                                "WHERE id=? AND deliveryState IN ('pending','accepted')",
                                            arrayOf(id),
                                        )
                                    }
                                }
                        }
                    }
                    "read" -> {
                        if (event.getLong("userId") != userID)
                            applyPeerRead(db, chat, event.getLong("through"), touched)
                        else applyOwnRead(db, chat, event.getLong("through"))
                        eventContent(db, event, chat, touched)
                    }
                    else -> error("Unsupported persistent event")
                }
            }
            require(next == json.getLong("cursor"))
            state(db, "cursor", next.toString())
            deltas(db, touched, removed, MessageChangeReason.SYNC)
        }

    fun history(db: SQLiteDatabase, chat: Long, json: JSONObject): List<MessageDelta> =
        transaction(db) {
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
            val touched: MutableMap<Long, MutableSet<Long>> = linkedMapOf(chat to ids)
            snapshotContent(db, json, touched)
            deltas(db, touched, emptyMap(), MessageChangeReason.HISTORY)
        }

    // При потере ответа телефон повторно отправит уже сохранённый номер.
    fun pendingRetry(db: SQLiteDatabase, key: String, proposed: java.util.UUID?): String {
        val candidate = (proposed ?: java.util.UUID.randomUUID()).toString()
        db.execSQL(
            "INSERT OR IGNORE INTO net_state(key,value) VALUES(?,?)",
            arrayOf(key, candidate),
        )
        return db.rawQuery("SELECT value FROM net_state WHERE key=?", arrayOf(key)).use { row ->
            check(row.moveToFirst())
            row.getString(0)
        }
    }

    fun generationRows(
        db: SQLiteDatabase,
        chat: Long,
        json: JSONObject,
        retryKey: String? = null,
        operation: String? = null,
    ): List<MessageDelta> =
        transaction(db) {
            val messages = json.getJSONArray("messages")
            val touched = linkedSetOf<Long>()
            for (i in 0 until messages.length()) {
                val wire = messages.getJSONObject(i)
                require(wire.getLong("chatId") == chat)
                merge(db, wire)?.let(touched::add)
            }
            if (retryKey != null && operation != null) {
                db.delete("net_state", "key=? AND value=?", arrayOf(retryKey, operation))
            }
            deltas(db, mapOf(chat to touched), emptyMap(), MessageChangeReason.SYNC)
        }

    fun accepted(db: SQLiteDatabase, json: JSONObject): List<MessageDelta> =
        transaction(db) {
            val touched = linkedMapOf<Long, MutableSet<Long>>()
            val messages = json.getJSONArray("messages")
            for (i in 0 until messages.length()) {
                val row = messages.getJSONObject(i)
                val client = row.getString("clientId").lowercase(Locale.ROOT)
                db.rawQuery(
                        "SELECT n.localID,m.chatId " +
                            "FROM net_messages n " +
                            "LEFT " +
                            "JOIN messages m ON m.id=n.localID " +
                            "WHERE n.clientID=? AND n.senderID=?",
                        arrayOf(client, userID.toString()),
                    )
                    .use {
                        if (it.moveToFirst()) {
                            val id = it.getLong(0)
                            db.execSQL(
                                "UPDATE net_messages SET serverID=?,serverSeq=? WHERE localID=?",
                                arrayOf(row.getLong("id"), row.getLong("seq"), id),
                            )
                            db.execSQL(
                                "UPDATE messages " +
                                    "SET sortKey=?,savedAt=?,revision=revision+1," +
                                    "deliveryState=CASE WHEN deliveryState IN ('pending'," +
                                    "'failed') THEN 'accepted' ELSE deliveryState END " +
                                    "WHERE id=?",
                                arrayOf(row.getLong("seq"), row.getLong("savedAtMs"), id),
                            )
                            if (!it.isNull(1))
                                touched.getOrPut(it.getLong(1)) { linkedSetOf() }.add(id)
                            restoreContent(db, row.getLong("id"))?.let { (chat, local) ->
                                touched.getOrPut(chat) { linkedSetOf() }.add(local)
                            }
                        }
                    }
                db.delete("net_outbox", "id=? AND action='send'", arrayOf(client))
            }
            touched.keys.toList().forEach { chat ->
                applyPeerRead(db, chat, peerRead(db, chat), touched)
            }
            deltas(db, touched, emptyMap(), MessageChangeReason.SYNC)
        }

    fun failed(db: SQLiteDatabase, ids: List<String>, code: String): List<MessageDelta> =
        transaction(db) {
            if (ids.isEmpty()) return@transaction emptyList()
            require(ids.size <= 32)
            val placeholders = ids.joinToString(",") { "?" }
            val args = ids.toTypedArray()
            val touched = linkedMapOf<Long, MutableSet<Long>>()
            db.rawQuery(
                    "SELECT m.id,m.chatId " +
                        "FROM messages m " +
                        "JOIN net_outbox o ON o.localID=m.id " +
                        "WHERE o.id IN ($placeholders)",
                    args,
                )
                .use {
                    while (it.moveToNext()) touched
                        .getOrPut(it.getLong(1)) { linkedSetOf() }
                        .add(it.getLong(0))
                }
            db.execSQL(
                "UPDATE net_outbox SET errorCode=? WHERE id IN ($placeholders)",
                arrayOf<Any>(code, *args),
            )
            db.execSQL(
                "UPDATE messages " +
                    "SET deliveryState='failed',revision=revision+1 " +
                    "WHERE id IN (SELECT localID " +
                    "FROM net_outbox " +
                    "WHERE id IN ($placeholders))",
                args,
            )
            db.execSQL(
                "DELETE " +
                    "FROM net_outbox " +
                    "WHERE action='delete' AND localID IN (SELECT localID " +
                    "FROM net_outbox " +
                    "WHERE id IN ($placeholders))",
                args,
            )
            deltas(db, touched, emptyMap(), MessageChangeReason.SYNC)
        }

    // Местная отправка и полученное событие могут описывать одно сообщение. Сначала ищем.
    private fun merge(db: SQLiteDatabase, wire: JSONObject): Long? {
        val server = wire.getLong("id")
        val chat = wire.getLong("chatId")
        val sender = wire.getLong("senderId")
        if (ChatServerState.isHidden(db, server, chat, wire.getLong("seq"))) return null
        val client = wire.getString("clientId").lowercase(Locale.ROOT)
        val revision = wire.getInt("revision")
        val mapping =
            db.rawQuery(
                    "SELECT localID,remoteRevision,tombstone " +
                        "FROM net_messages " +
                        "WHERE serverID=? OR (clientID=? AND senderID=?)",
                    arrayOf(server.toString(), client, sender.toString()),
                )
                .use {
                    if (it.moveToFirst()) Triple(it.getLong(0), it.getInt(1), it.getInt(2) != 0)
                    else null
                }
        if (mapping?.third == true || (mapping != null && mapping.second >= revision)) return null
        val kind = wire.getString("kind")
        MessageKind.valueOf(kind.uppercase(Locale.ROOT))
        val own = sender == userID
        // Повторно используем принятую загрузку только при совпадении номера её содержимого
        val oldAttachments =
            if (mapping != null && own)
                db.rawQuery(
                        "SELECT a.relativePath,COALESCE(m.blobID,u.blobID) " +
                            "FROM attachments a " +
                            "LEFT " +
                            "JOIN net_media m ON m.attachmentID=a.id " +
                            "LEFT " +
                            "JOIN net_uploads u ON u.attachmentID=a.id " +
                            "WHERE a.messageId=? " +
                            "ORDER BY a.rowid",
                        arrayOf(mapping.first.toString()),
                    )
                    .use { c ->
                        buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
                    }
            else emptyList()
        val values =
            ContentValues().apply {
                put("chatId", chat)
                put("sortKey", wire.getLong("seq"))
                put("savedAt", wire.getLong("savedAtMs"))
                put("kind", kind)
                put("text", wire.getString("text"))
                put("caption", wire.getString("caption"))
                put("direction", if (own) "outgoing" else "incoming")
                put(
                    "searchText",
                    (wire.getString("text") + " " + wire.getString("caption")).lowercase(
                        Locale.ROOT
                    ),
                )
                val reply =
                    wire.optLongOrNull("replyToId")?.let { replyID ->
                        db.rawQuery(
                                "SELECT localID FROM net_messages WHERE serverID=?",
                                arrayOf(replyID.toString()),
                            )
                            .use { if (it.moveToFirst()) it.getLong(0) else null }
                    }
                if (reply == null) putNull("replyToId") else put("replyToId", reply)
                if (mapping == null) {
                    put("revision", 1)
                    put("deliveryState", if (own) "accepted" else "local")
                }
            }
        val id = mapping?.first ?: db.insertOrThrow("messages", null, values)
        if (mapping != null) {
            db.update("messages", values, "id=?", arrayOf(id.toString()))
            db.execSQL(
                "UPDATE messages " +
                    "SET revision=revision+1,deliveryState=CASE WHEN direction='outgoing' AND " +
                    "deliveryState IN ('pending','failed') THEN 'accepted' ELSE deliveryState " +
                    "END " +
                    "WHERE id=?",
                arrayOf(id),
            )
        }
        db.execSQL(
            "INSERT OR REPLACE INTO net_messages(localID,serverID,clientID,senderID," +
                "serverSeq,remoteRevision,tombstone) " +
                "VALUES(?,?,?,?,?,?,0)",
            arrayOf<Any?>(id, server, client, sender, wire.getLong("seq"), revision),
        )
        if (own && wire.getLong("seq") > 0 && wire.getLong("seq") <= peerRead(db, chat)) {
            db.execSQL(
                "UPDATE messages SET deliveryState='read',revision=revision+1 WHERE id=? AND deliveryState<>'read'",
                arrayOf(id),
            )
        }
        restoreContent(db, server)
        db.delete("attachments", "messageId=?", arrayOf(id.toString()))
        val attachments = wire.getJSONArray("attachments")
        require(attachments.length() <= 10)
        for (i in 0 until attachments.length()) {
            val attachment = attachments.getJSONObject(i)
            val blob = attachment.getString("blobId")
            require(blob.matches(Regex("[a-f0-9]{32}|[a-f0-9]{64}")))
            val attachmentId =
                if (attachment.has("resultJobId") && !attachment.isNull("resultJobId"))
                    "net-$server-result-$blob"
                else "net-$server-$i"
            val path =
                oldAttachments.getOrNull(i)?.takeIf { it.second == blob }?.first
                    ?: NetworkMedia.relativePath(blob, attachment.getString("mime"))
            db.execSQL(
                "INSERT INTO attachments(id,messageId,relativePath,mime,byteSize,width," +
                    "height,durationMs,thumbnailKey,orientation) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(
                    attachmentId,
                    id,
                    path,
                    attachment.getString("mime"),
                    attachment.getLong("byteSize"),
                    attachment.getInt("rawWidth"),
                    attachment.getInt("rawHeight"),
                    attachment.optLongOrNull("durationMs"),
                    blob,
                    attachment.getInt("orientation"),
                ),
            )
            MediaPreviewStorage.save(
                db,
                attachmentId,
                attachment.optString("tinyPreview").takeUnless { it == "null" },
                MediaPreviewStorage.readWave(attachment.optJSONArray("waveform")?.toString()),
            )
            db.execSQL(
                "INSERT OR REPLACE INTO net_media(attachmentID,blobID,previewBlobID,mime,byteSize) VALUES(?,?,?,?,?)",
                arrayOf<Any?>(
                    attachmentId,
                    blob,
                    attachment.optString("previewBlobId").takeUnless {
                        it.isBlank() || it == "null"
                    },
                    attachment.getString("mime"),
                    attachment.getLong("byteSize"),
                ),
            )
        }
        db.execSQL(
            "UPDATE net_chats SET lastSeq=MAX(lastSeq,?) WHERE chatID=?",
            arrayOf(wire.getLong("seq"), chat),
        )
        if (!own)
            db.execSQL(
                "INSERT OR IGNORE INTO net_pending_receipts(serverID) VALUES(?)",
                arrayOf(server),
            )
        val metadata = wire.optJSONObject("metadata") ?: JSONObject()
        db.execSQL(
            "INSERT OR REPLACE INTO net_message_metadata VALUES(?,?)",
            arrayOf<Any?>(id, studioMetadata(db, id, metadata, mapping != null).toString()),
        )
        if (own) db.delete("net_outbox", "id=? AND action='send'", arrayOf(client))
        return id
    }

    private fun studioMetadata(
        db: SQLiteDatabase,
        id: Long,
        metadata: JSONObject,
        existing: Boolean,
    ): JSONObject {
        fun active(status: String) = status == "running" || status == "queued"
        if (!existing || !active(metadata.optString("kandinskyStatus"))) return metadata
        val previous =
            db.rawQuery(
                    "SELECT payload FROM net_message_metadata WHERE localID=?",
                    arrayOf(id.toString()),
                )
                .use { if (it.moveToFirst()) JSONObject(it.getString(0)) else null }
                ?: return metadata
        if (previous.optString("kandinskyJobId") != metadata.optString("kandinskyJobId"))
            return metadata
        val next = JSONObject(metadata.toString())
        fun keepMeasured(
            old: JSONObject,
            new: JSONObject,
            statusKey: String,
            runKey: String,
            startKey: String,
            progressKey: String,
        ) {
            if (
                old.optString(statusKey) != "running" ||
                    new.optString(statusKey) != "running" ||
                    old.optString(runKey, "") != new.optString(runKey, "") ||
                    old.optLong(startKey, 0) != new.optLong(startKey, 0)
            )
                return
            val measured = old.optInt(progressKey, -1)
            if (measured in 0..99 && measured > new.optInt(progressKey, -1))
                new.put(progressKey, measured)
        }
        val slots = next.optJSONArray("kandinskySlots")
        if (slots == null) {
            keepMeasured(
                previous,
                next,
                "kandinskyStatus",
                "kandinskyRunId",
                "kandinskyStartedAtMs",
                "kandinskyProgress",
            )
        } else {
            val oldSlots = previous.optJSONArray("kandinskySlots") ?: return metadata
            val byID =
                (0 until oldSlots.length())
                    .mapNotNull { oldSlots.optJSONObject(it) }
                    .associateBy { it.optString("id") }
            for (i in 0 until slots.length()) {
                val slot = slots.optJSONObject(i) ?: continue
                val old = byID[slot.optString("id")] ?: continue
                keepMeasured(old, slot, "status", "runId", "startedAtMs", "progress")
            }
        }
        return next
    }

    fun deltas(
        db: SQLiteDatabase,
        touched: Map<Long, Set<Long>>,
        removed: Map<Long, List<Long>>,
        reason: MessageChangeReason,
    ): List<MessageDelta> {
        return touched.map { (chat, ids) ->
            db.execSQL(
                "UPDATE chats " +
                    "SET lastMessageId=(SELECT id " +
                    "FROM messages " +
                    "WHERE chatId=? " +
                    "ORDER BY sortKey DESC,id DESC LIMIT 1)," +
                    "updatedSortKey=COALESCE((SELECT savedAt " +
                    "FROM messages " +
                    "WHERE chatId=? " +
                    "ORDER BY sortKey DESC,id DESC LIMIT 1),0) " +
                    "WHERE id=?",
                arrayOf(chat, chat, chat),
            )
            val rows =
                if (ids.isEmpty()) emptyList()
                else
                    local.messages(
                        db,
                        "SELECT * FROM messages WHERE id IN (${ids.joinToString(",") { "?" }})",
                        ids.map(Long::toString).toTypedArray(),
                    )
            MessageDelta(
                updated = rows,
                removedIDs = removed[chat] ?: emptyList(),
                chat = ChatServerState.decorate(db, local.summary(db, chat)),
                reason = reason,
            )
        }
    }

    companion object {
        fun <T> transaction(db: SQLiteDatabase, action: () -> T): T {
            db.beginTransaction()
            try {
                val value = action()
                db.setTransactionSuccessful()
                return value
            } finally {
                db.endTransaction()
            }
        }
    }
}

internal fun JSONObject.optLongOrNull(key: String): Long? =
    if (isNull(key) || !has(key)) null else getLong(key)
