package dev.kirill.kandinsky.chat.network

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.storage.*
import java.util.Locale
import org.json.JSONObject

// Местная отправка и полученное событие могут описывать одно сообщение. Сначала ищем.
internal fun NetworkStore.merge(db: SQLiteDatabase, wire: JSONObject): Long? {
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
    val metadata = wire.optJSONObject("metadata") ?: JSONObject()
    db.execSQL(
        "INSERT OR REPLACE INTO net_message_metadata VALUES(?,?)",
        arrayOf<Any?>(id, studioMetadata(db, id, metadata, mapping != null).toString()),
    )
    if (own) db.delete("net_outbox", "id=? AND action='send'", arrayOf(client))
    return id
}

private fun NetworkStore.studioMetadata(
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
