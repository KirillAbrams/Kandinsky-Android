// Переносит состояние чата с сервера в базу телефона. Совмещает правила списка с очисткой ленты,
// чтобы задержанные ответы не возвращали удалённое.

package dev.kirill.kandinsky.chat.network

import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

object ChatServerState {
    // Добавляет к строке чата правила скрытия, звука, архива и прочтения из базы. Иначе карточка
    // показывала бы устаревшие признаки после обмена с сервером.
    fun decorate(
        db: SQLiteDatabase,
        row: dev.kirill.kandinsky.chat.domain.ChatSummary,
    ): dev.kirill.kandinsky.chat.domain.ChatSummary {
        val state = state(db, row.id)
        return row.copy(
            archived = state.optBoolean("archived"),
            muted = state.optBoolean("muted"),
            markedUnread = state.optBoolean("markedUnread"),
            hidden = state.optBoolean("hidden") || state.optBoolean("left"),
        )
    }

    // Открывает сохранённое описание чата и закрывает чтение после получения строки. При
    // отсутствии записи даёт пустое описание, чтобы исходные значения оставались доступны.
    fun state(db: SQLiteDatabase, chat: Long): JSONObject =
        db.rawQuery("SELECT payload FROM net_chat_states WHERE chatID=?", arrayOf(chat.toString()))
            .use { if (it.moveToFirst()) JSONObject(it.getString(0)) else JSONObject() }

    // Отклоняет неподходящий номер чата, неверные границы и старые версии. Запоминает наибольшую
    // границу, чистит сообщения и ещё не показанные данные и обновляет карточку. Возвращает
    // убранные строки для обновления экрана.
    fun applyState(db: SQLiteDatabase, chat: Long, value: JSONObject?): List<Long> {
        if (value == null) return emptyList()
        val version = value.getLong("version")
        val cutoff = value.getLong("clearThrough")
        require(value.getLong("id") == chat && version >= 0 && cutoff >= 0)
        val old = state(db, chat)
        if (version < old.optLong("version", -1)) return emptyList()
        val next = JSONObject(value.toString())
        val through = maxOf(cutoff, old.optLong("clearThrough", 0))
        next.put("clearThrough", through)
        require(next.toString().toByteArray().size <= 16384)
        db.execSQL(
            "INSERT OR REPLACE INTO net_chat_states(chatID,payload) VALUES(?,?)",
            arrayOf<Any?>(chat, next.toString()),
        )
        val ids =
            db.rawQuery(
                    "SELECT m.id " +
                        "FROM messages m " +
                        "JOIN net_messages n ON n.localID=m.id " +
                        "WHERE m.chatId=? AND n.serverSeq>0 AND n.serverSeq<=?",
                    arrayOf(chat.toString(), through.toString()),
                )
                .use { buildList { while (it.moveToNext()) add(it.getLong(0)) } }
        db.execSQL(
            "INSERT OR IGNORE INTO net_tombstones SELECT n.serverID,? " +
                "FROM net_messages n " +
                "JOIN messages m ON m.id=n.localID " +
                "WHERE m.chatId=? AND n.serverID IS NOT NULL AND n.serverSeq<=?",
            arrayOf(chat, chat, through),
        )
        db.execSQL(
            "UPDATE net_messages " +
                "SET tombstone=1 " +
                "WHERE serverID IN (SELECT serverID " +
                "FROM net_tombstones " +
                "WHERE chatID=?)",
            arrayOf(chat),
        )
        db.execSQL(
            "DELETE FROM net_pending_content WHERE chatID=? AND serverID IN (SELECT serverID FROM net_tombstones)",
            arrayOf(chat),
        )
        db.execSQL(
            "DELETE FROM messages WHERE chatId=? AND id IN (SELECT localID FROM net_messages WHERE tombstone=1)",
            arrayOf(chat),
        )
        db.execSQL(
            "UPDATE chats SET pinned=?,unreadCount=? WHERE id=?",
            arrayOf(
                if (next.optString("kind") == "saved" || next.optLong("pinnedAtMs") > 0) 1 else 0,
                maxOf(0, next.optLong("unreadCount")),
                chat,
            ),
        )
        return ids
    }

    // Ищет запрет для номера сообщения и проверяет, не лежит ли оно до границы очистки. Это
    // сохраняет удаление даже при позднем приходе старых данных.
    fun isHidden(db: SQLiteDatabase, server: Long, chat: Long, seq: Long): Boolean {
        val deleted =
            db.rawQuery("SELECT 1 FROM net_tombstones WHERE serverID=?", arrayOf(server.toString()))
                .use { it.moveToFirst() }
        return deleted || seq <= state(db, chat).optLong("clearThrough", 0)
    }
}
