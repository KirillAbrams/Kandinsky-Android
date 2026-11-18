package dev.kirill.kandinsky.chat.network

import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.storage.*
import org.json.JSONObject

internal class SyncHistory(private val sync: NetworkSync) {
    private var pendingRead: Pair<Long, Long>? = null
    private var currentChat: Long? = null
    private var followsLatest = false
    private var readRefreshing = false
    private var readRefreshAgain = false
    private var readRefreshTask: Runnable? = null
    private val loadingHistory = mutableSetOf<Long>()
    private val nextHistoryRetry = mutableMapOf<Long, Long>()
    private val visibleHistoryRefreshes = linkedMapOf<Long, Long>()

    fun refreshReadSummaries() {
        if (!sync.active || !sync.resumed || sync.closed) return
        if (readRefreshing) {
            readRefreshAgain = true
            return
        }
        readRefreshing = true
        val connectionID = sync.connectionEpoch
        fun finish(success: Boolean) {
            if (connectionID != sync.connectionEpoch) return
            val again = readRefreshAgain && success
            readRefreshing = false
            readRefreshAgain = false
            if (again) refreshReadSummaries()
        }
        val task = Runnable {
            readRefreshTask = null
            if (connectionID != sync.connectionEpoch || !sync.active || !sync.resumed)
                return@Runnable
            sync.database({ sync.cache.needsReadRefresh(it) }) { needed ->
                if (connectionID != sync.connectionEpoch) return@database
                if (needed.getOrNull() != true) {
                    finish(needed.isSuccess)
                    return@database
                }
                sync.http({ sync.api.json("/v1/chats") }) { response ->
                    if (connectionID != sync.connectionEpoch) return@http
                    response.fold(
                        { json ->
                            sync.database({ sync.cache.mergeChats(it, json) }) { applied ->
                                if (connectionID == sync.connectionEpoch) {
                                    applied.onSuccess { sync.emit(it) }
                                    finish(applied.isSuccess)
                                }
                            }
                        },
                        { finish(false) },
                    )
                }
            }
        }
        readRefreshTask = task
        sync.worker.postDelayed(task, 100)
    }

    fun viewport(chat: Long, anchor: ReadingAnchor) {
        sync.worker.post {
            currentChat = chat
            followsLatest = anchor.followsBottom
            if (followsLatest) sendVisibleReadPosition()
            val localID = anchor.messageID ?: return@post
            if (
                chat in loadingHistory || System.currentTimeMillis() < (nextHistoryRetry[chat] ?: 0)
            )
                return@post
            loadingHistory.add(chat)
            sync.database({ db ->
                val before =
                    db.rawQuery(
                            "SELECT MIN(serverSeq) " +
                                "FROM net_messages n " +
                                "JOIN messages m ON m.id=n.localID " +
                                "WHERE m.chatId=? AND serverSeq>0",
                            arrayOf(chat.toString()),
                        )
                        .use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
                val count =
                    db.rawQuery(
                            "SELECT count(*) " +
                                "FROM messages " +
                                "WHERE chatId=? AND sortKey<(SELECT sortKey " +
                                "FROM messages " +
                                "WHERE id=?)",
                            arrayOf(chat.toString(), localID.toString()),
                        )
                        .use {
                            it.moveToFirst()
                            it.getInt(0)
                        }
                val older =
                    db.rawQuery(
                            "SELECT hasOlder FROM net_chats WHERE chatID=?",
                            arrayOf(chat.toString()),
                        )
                        .use { it.moveToFirst() && it.getInt(0) != 0 }
                if (older && count < 32) before else null
            }) { result ->
                val before = result.getOrNull()
                if (before == null || !sync.active) {
                    loadingHistory.remove(chat)
                    return@database
                }
                val connectionID = sync.connectionEpoch
                sync.http({ sync.api.json("/v1/chats/$chat/history?limit=128&before=$before") }) {
                    page ->
                    if (connectionID != sync.connectionEpoch) {
                        loadingHistory.remove(chat)
                        return@http
                    }
                    page.fold(
                        { json ->
                            sync.database({ sync.cache.history(it, chat, json) }) { applied ->
                                loadingHistory.remove(chat)
                                applied.onSuccess { sync.emit(it) }
                            }
                        },
                        {
                            loadingHistory.remove(chat)
                            nextHistoryRetry[chat] = System.currentTimeMillis() + 5000
                        },
                    )
                }
            }
        }
    }

    fun visibleChat(chat: Long?) {
        sync.worker.post {
            val changed = currentChat != chat
            if (changed) {
                pendingRead = null
            }
            currentChat = chat
            followsLatest = false
            if (changed && chat != null) refreshVisibleHistory(chat)
        }
    }

    private fun refreshVisibleHistory(chat: Long) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (sync.resumed && visibleHistoryRefreshes[chat]?.let { now - it < 90_000 } == true) return
        if (!loadingHistory.add(chat)) return
        sync.http({ sync.api.json("/v1/chats/$chat/history?limit=128") }) { page ->
            page.fold(
                { json ->
                    sync.database({ sync.cache.history(it, chat, json) }) { result ->
                        loadingHistory.remove(chat)
                        result.onSuccess {
                            visibleHistoryRefreshes.remove(chat)
                            visibleHistoryRefreshes[chat] = android.os.SystemClock.elapsedRealtime()
                            if (visibleHistoryRefreshes.size > 32)
                                visibleHistoryRefreshes.remove(visibleHistoryRefreshes.keys.first())
                            sync.emit(it)
                        }
                    }
                },
                {
                    loadingHistory.remove(chat)
                    if (currentChat == chat) sync.publishStatus("История временно недоступна")
                },
            )
        }
    }

    fun sendVisibleReadPosition() {
        val chat = currentChat ?: return
        if (!followsLatest || !sync.resumed) return
        sync.database({ db ->
            db.rawQuery("SELECT lastSeq FROM net_chats WHERE chatID=?", arrayOf(chat.toString()))
                .use { if (it.moveToFirst()) it.getLong(0) else 0L }
        }) { result ->
            result.onSuccess { through ->
                if (through > 0 && currentChat == chat && followsLatest) {
                    if (pendingRead == (chat to through)) return@onSuccess
                    pendingRead = chat to through
                    sync.send(
                        JSONObject().put("type", "read").put("chatId", chat).put("through", through)
                    )
                }
            }
        }
    }

    fun refreshCurrentChat() {
        currentChat?.let(::refreshVisibleHistory)
    }

    fun reset() {
        pendingRead = null
        readRefreshTask?.let { sync.worker.removeCallbacks(it) }
        readRefreshTask = null
        readRefreshing = false
        readRefreshAgain = false
    }
}
