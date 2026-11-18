// Создаёт местные таблицы сетевой связи и сохраняет отметки повторов. При обновлении чинит
// порядок прежних неотправленных сообщений без переписывания принятой истории.

package dev.kirill.kandinsky.chat.network

import android.database.sqlite.SQLiteDatabase
import androidx.core.database.sqlite.transaction

object NetworkSchema {
    // Создаёт недостающие таблицы одной записью и оставляет только известную версию. После
    // проверки переносит старые отправки. Отказ оставляет схему в прежнем состоянии вместо
    // частично готовой базы.
    fun install(database: SQLiteDatabase) {
        database.transaction {
            for (statement in statements) database.execSQL(statement)
            val version =
                database.rawQuery("SELECT value FROM net_state WHERE key='schema'", null).use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            check(version == null || version == "1") { "Unsupported network cache" }
            database.execSQL("INSERT OR IGNORE INTO net_state(key,value) VALUES('schema','1')")
            repairQueuedOrder(database)
        }
    }

    // Ищет отправки со старым порядком и без номера сервера, переносит их в конец и обновляет
    // видимые версии. Затем пересчитывает последнее сообщение только у затронутых чатов.
    private fun repairQueuedOrder(database: SQLiteDatabase) {
        val legacyRows =
            """
            FROM net_outbox o JOIN net_messages n ON n.localID=o.localID
            JOIN messages m ON m.id=o.localID
            WHERE o.action='send' AND n.serverID IS NULL AND n.tombstone=0 AND m.sortKey<>?
        """
                .trimIndent()
        val pendingOrder = Long.MAX_VALUE
        val chats =
            database
                .rawQuery("SELECT DISTINCT m.chatId $legacyRows", arrayOf(pendingOrder.toString()))
                .use { rows -> buildList { while (rows.moveToNext()) add(rows.getLong(0)) } }
        if (chats.isEmpty()) return
        database.execSQL(
            "UPDATE messages SET sortKey=?,revision=revision+1 WHERE id IN (SELECT m.id $legacyRows)",
            arrayOf(pendingOrder, pendingOrder),
        )
        for (chat in chats) {
            val tail =
                database
                    .rawQuery(
                        "SELECT id,savedAt FROM messages WHERE chatId=? ORDER BY sortKey DESC,id DESC LIMIT 1",
                        arrayOf(chat.toString()),
                    )
                    .use { if (it.moveToFirst()) it.getLong(0) to it.getLong(1) else null }
            database.execSQL(
                "UPDATE chats SET lastMessageId=?,updatedSortKey=? WHERE id=?",
                arrayOf(tail?.first, tail?.second ?: 0L, chat),
            )
        }
    }

    private val statements =
        listOf(
            "CREATE TABLE IF NOT EXISTS net_message_metadata(localID INTEGER PRIMARY KEY,payload TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS net_tombstones(serverID INTEGER PRIMARY KEY,chatID INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS net_chat_states(chatID INTEGER PRIMARY KEY,payload TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS net_pending_receipts(serverID INTEGER PRIMARY KEY)",
            "CREATE TABLE IF NOT EXISTS net_pending_content(serverID INTEGER PRIMARY KEY," +
                "chatID INTEGER NOT NULL,errorCode TEXT)",
            "CREATE INDEX IF NOT EXISTS net_pending_content_ready ON " +
                "net_pending_content(chatID,serverID) " +
                "WHERE errorCode IS NULL",
            "CREATE TABLE IF NOT EXISTS net_content_receipts(serverID INTEGER NOT NULL," +
                "userID INTEGER NOT NULL,chatID INTEGER NOT NULL,PRIMARY KEY(serverID,userID))",
            "CREATE TABLE IF NOT EXISTS net_state(key TEXT PRIMARY KEY,value TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS net_chats(chatID INTEGER PRIMARY KEY," +
                "lastSeq INTEGER NOT NULL DEFAULT 0,hasOlder INTEGER NOT NULL DEFAULT 1," +
                "peerUserID INTEGER,title TEXT NOT NULL)",
            // Соответствия переживают местное удаление, поэтому подтверждение отправки не
            // возвращает скрытую строку
            "CREATE TABLE IF NOT EXISTS net_messages(localID INTEGER PRIMARY KEY," +
                "serverID INTEGER UNIQUE,clientID TEXT NOT NULL,senderID INTEGER NOT NULL," +
                "serverSeq INTEGER NOT NULL DEFAULT 0,remoteRevision INTEGER NOT NULL DEFAULT 0," +
                "tombstone INTEGER NOT NULL DEFAULT 0,UNIQUE(senderID,clientID))",
            "CREATE TABLE IF NOT EXISTS net_outbox(id TEXT PRIMARY KEY,action TEXT NOT NULL," +
                "localID INTEGER,payload TEXT NOT NULL,payloadBytes INTEGER NOT NULL," +
                "mediaBytes INTEGER NOT NULL DEFAULT 0,errorCode TEXT)",
            "CREATE TABLE IF NOT EXISTS net_reads(chatID INTEGER PRIMARY KEY," +
                "through INTEGER NOT NULL,sentThrough INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS net_media(attachmentID TEXT PRIMARY KEY," +
                "blobID TEXT NOT NULL,previewBlobID TEXT,mime TEXT NOT NULL," +
                "byteSize INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS net_uploads(attachmentID TEXT PRIMARY KEY," +
                "blobID TEXT NOT NULL,previewBlobID TEXT)",
        )
}
