package dev.kirill.kandinsky.chat.network

import android.database.sqlite.SQLiteDatabase
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.storage.*

internal fun NetworkStore.peerRead(db: SQLiteDatabase, chat: Long): Long =
    db.rawQuery("SELECT value FROM net_state WHERE key=?", arrayOf("peerRead:$chat")).use {
        (if (it.moveToFirst()) it.getString(0).toLong() else 0L).also { through ->
            require(through >= 0)
        }
    }

internal fun NetworkStore.ownRead(db: SQLiteDatabase, chat: Long): Long =
    db.rawQuery("SELECT value FROM net_state WHERE key=?", arrayOf("ownRead:$chat")).use {
        (if (it.moveToFirst()) it.getString(0).toLong() else 0L).also { through ->
            require(through >= 0)
        }
    }

internal fun NetworkStore.hasStaleReadPositions(db: SQLiteDatabase): Boolean =
    db.rawQuery(
            "SELECT 1 FROM net_state WHERE key LIKE 'readRefresh:%' AND value='1' LIMIT 1",
            null,
        )
        .use { it.moveToFirst() }

// Поздняя страница истории не должна снова сделать уже прочитанные сообщения непрочитанными.
internal fun NetworkStore.applyOwnRead(db: SQLiteDatabase, chat: Long, through: Long) {
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

internal fun NetworkStore.applyPeerRead(
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
