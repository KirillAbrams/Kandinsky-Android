// Обновляет сообщения и очередь по ответам о принятии и отказе. Эти изменения сохраняются вместе,
// чтобы после перезапуска не осталось противоречивых сведений.

package dev.kirill.kandinsky.chat.network

import android.database.sqlite.SQLiteDatabase
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.storage.*
import java.util.Locale
import org.json.JSONObject

// Сверяет номер операции и владельца, затем добавляет местной строке номер и порядок сервера.
// Убирает принятую отправку в той же записи, сохраняя одно сообщение вместо нового.
internal fun NetworkStore.confirmSentMessages(db: SQLiteDatabase, json: JSONObject): List<MessageDelta> =
    NetworkStore.transaction(db) {
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
                    }
                }
            db.delete("net_outbox", "id=? AND action='send'", arrayOf(client))
        }
        deltas(db, touched, emptyMap(), MessageChangeReason.SYNC)
    }

// Отмечает ограниченную порцию команд отклонённой, обновляет их сообщения и убирает связанные
// удаления. Экран получает точный список строк, у которых появилась ошибка.
internal fun NetworkStore.recordSendFailure(db: SQLiteDatabase, ids: List<String>, code: String): List<MessageDelta> =
    NetworkStore.transaction(db) {
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
