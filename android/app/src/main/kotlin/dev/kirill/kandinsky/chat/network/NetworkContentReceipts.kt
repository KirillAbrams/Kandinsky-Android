package dev.kirill.kandinsky.chat.network

import android.database.sqlite.SQLiteDatabase
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.storage.*
import org.json.JSONObject

// Записываем просмотр на телефоне до отправки. После обрыва связи он останется в очереди.
internal fun NetworkStore.recordContentView(db: SQLiteDatabase, localID: Long): List<MessageDelta> =
    NetworkStore.transaction(db) {
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
internal fun NetworkStore.loadPendingContentViews(db: SQLiteDatabase): List<ContentBatch> {
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
internal fun NetworkStore.confirmContentViews(
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
    return NetworkStore.transaction(db) {
        val touched = linkedMapOf<Long, MutableSet<Long>>()
        ids.forEach { contentReceipt(db, it, batch.chatID, userID, touched) }
        deltas(db, touched, emptyMap(), MessageChangeReason.SYNC)
    }
}

private fun NetworkStore.contentID(value: Any?): Long =
    (value as? Number)?.toString()?.toLongOrNull()?.also { require(it > 0) }
        ?: error("Invalid content identity")

// Подтверждение просмотра может прийти раньше сообщения. Сохраняем его до загрузки истории.
internal fun NetworkStore.restoreContent(
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

private fun NetworkStore.contentReceipt(
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

internal fun NetworkStore.snapshotContent(
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

internal fun NetworkStore.eventContent(
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
