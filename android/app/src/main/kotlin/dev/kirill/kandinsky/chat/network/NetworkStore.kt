package dev.kirill.kandinsky.chat.network

import android.database.sqlite.SQLiteDatabase
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.storage.*
import org.json.JSONObject

class NetworkStore(val local: SQLiteChatRepository, val userID: Long) {
    fun cursor(db: SQLiteDatabase): Long =
        db.rawQuery("SELECT value FROM net_state WHERE key='cursor'", null).use {
            if (it.moveToFirst()) it.getString(0).toLong() else 0L
        }

    fun cursorAwaitingConfirmation(db: SQLiteDatabase): Long? {
        val saved = cursor(db)
        val confirmed =
            db.rawQuery("SELECT value FROM net_state WHERE key='ackedCursor'", null).use {
                if (it.moveToFirst()) it.getString(0).toLong() else -1L
            }
        return saved.takeIf { it > confirmed }
    }

    fun initialized(db: SQLiteDatabase) =
        db.rawQuery("SELECT 1 FROM net_state WHERE key='cursor'", null).use { it.moveToFirst() }

    internal fun state(db: SQLiteDatabase, key: String, value: String) {
        db.execSQL("INSERT OR REPLACE INTO net_state(key,value) VALUES(?,?)", arrayOf(key, value))
    }

    fun bootstrap(db: SQLiteDatabase, json: JSONObject): List<MessageDelta> =
        applyInitialSnapshot(db, json)

    fun retireStudioThread(db: SQLiteDatabase, chat: Long): List<Long>? = retireChat(db, chat)

    fun mergeChats(
        db: SQLiteDatabase,
        json: JSONObject,
        studioRecent: Boolean = false,
        preserveDirectoryMetadata: Boolean = false,
    ): List<MessageDelta> = mergeChatList(db, json, studioRecent, preserveDirectoryMetadata)

    fun mergeStudioDirectorySnapshot(
        db: SQLiteDatabase, recent: JSONObject, states: List<JSONObject> = emptyList(),
        startedAtCursor: Long,
    ): List<MessageDelta> {
        val preserve = cursor(db) != startedAtCursor
        val changes = mergeChats(db, recent, studioRecent = true, preserveDirectoryMetadata = preserve)
        return changes + states.flatMap { mergeChats(db, it, preserveDirectoryMetadata = preserve) }
    }

    fun studioRecent(db: SQLiteDatabase): StudioRecentPage = loadRecentChats(db)

    fun studioChatIDs(db: SQLiteDatabase): List<Long> =
        db.rawQuery("SELECT chatID FROM net_chats ORDER BY chatID", null).use { rows ->
            buildList { while (rows.moveToNext()) add(rows.getLong(0)) }
        }

    fun hasUnknownChats(db: SQLiteDatabase, json: JSONObject): Boolean =
        containsUnknownChat(db, json)

    fun events(db: SQLiteDatabase, json: JSONObject): List<MessageDelta> = applyEventBatch(db, json)

    fun directoryEvents(db: SQLiteDatabase, json: JSONObject): Pair<List<MessageDelta>, Boolean> {
        val catalog = json.optJSONArray("chats")
        require(catalog == null || catalog.length() <= 128)
        val keys =
            if (catalog == null) emptyList()
            else List(catalog.length()) {
                "studioDirectory:${catalog.getJSONObject(it).getLong("id")}"
            }
        val before = directoryState(db, keys)
        val changes = events(db, json)
        return changes to (before != directoryState(db, keys))
    }

    private fun directoryState(db: SQLiteDatabase, keys: List<String>): List<String> {
        if (keys.isEmpty()) return emptyList()
        val placeholders = keys.joinToString(",") { "?" }
        return db.rawQuery(
            "SELECT key,value FROM net_state WHERE key IN ($placeholders) ORDER BY key",
            keys.toTypedArray(),
        ).use { rows ->
            buildList { while (rows.moveToNext()) add(rows.getString(0) + ":" + rows.getString(1)) }
        }
    }

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
    ): List<MessageDelta> = saveGenerationResponse(db, chat, json, retryKey, operation)

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
