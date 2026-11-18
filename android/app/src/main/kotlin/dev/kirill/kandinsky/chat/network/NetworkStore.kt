package dev.kirill.kandinsky.chat.network

import android.database.sqlite.SQLiteDatabase
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.storage.*
import org.json.JSONObject

data class ContentBatch(val chatID: Long, val messageIDs: List<Long>)

class NetworkStore(val local: SQLiteChatRepository, val userID: Long) {
    fun queueContent(db: SQLiteDatabase, localID: Long): List<MessageDelta> =
        recordContentView(db, localID)

    fun pendingContent(db: SQLiteDatabase): List<ContentBatch> =
        loadPendingContentViews(db)

    fun confirmContent(
        db: SQLiteDatabase,
        json: JSONObject,
        requestID: String,
        batch: ContentBatch,
    ): List<MessageDelta> =
        confirmContentViews(db, json, requestID, batch)

    fun cursor(db: SQLiteDatabase): Long =
        db.rawQuery("SELECT value FROM net_state WHERE key='cursor'", null).use {
            if (it.moveToFirst()) it.getString(0).toLong() else 0L
        }

    fun initialized(db: SQLiteDatabase) =
        db.rawQuery("SELECT 1 FROM net_state WHERE key='cursor'", null).use { it.moveToFirst() }

    internal fun state(db: SQLiteDatabase, key: String, value: String) {
        db.execSQL("INSERT OR REPLACE INTO net_state(key,value) VALUES(?,?)", arrayOf(key, value))
    }

    fun needsReadRefresh(db: SQLiteDatabase): Boolean =
        hasStaleReadPositions(db)

    fun bootstrap(db: SQLiteDatabase, json: JSONObject): List<MessageDelta> =
        applyInitialSnapshot(db, json)

    fun retireStudioThread(db: SQLiteDatabase, chat: Long): List<Long>? =
        retireChat(db, chat)

    fun mergeChats(
        db: SQLiteDatabase,
        json: JSONObject,
        studioRecent: Boolean = false,
    ): List<MessageDelta> =
        mergeChatList(db, json, studioRecent)

    fun studioRecent(db: SQLiteDatabase): StudioRecentPage =
        loadRecentChats(db)

    fun hasUnknownChats(db: SQLiteDatabase, json: JSONObject): Boolean =
        containsUnknownChat(db, json)

    fun events(db: SQLiteDatabase, json: JSONObject): List<MessageDelta> =
        applyEventBatch(db, json)

    fun history(db: SQLiteDatabase, chat: Long, json: JSONObject): List<MessageDelta> =
        applyHistoryPage(db, chat, json)

    fun pendingRetry(db: SQLiteDatabase, key: String, proposed: java.util.UUID?): String =
        loadOrCreateRetryID(db, key, proposed)

    fun generationRows(
        db: SQLiteDatabase,
        chat: Long,
        json: JSONObject,
        retryKey: String? = null,
        operation: String? = null,
    ): List<MessageDelta> =
        saveGenerationResponse(db, chat, json, retryKey, operation)

    fun accepted(db: SQLiteDatabase, json: JSONObject): List<MessageDelta> =
        confirmSentMessages(db, json)

    fun failed(db: SQLiteDatabase, ids: List<String>, code: String): List<MessageDelta> =
        recordSendFailure(db, ids, code)

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
