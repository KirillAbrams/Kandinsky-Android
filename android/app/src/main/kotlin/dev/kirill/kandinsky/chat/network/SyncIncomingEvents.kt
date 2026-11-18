package dev.kirill.kandinsky.chat.network

import org.json.JSONObject

internal class SyncIncomingEvents(private val sync: NetworkSync) {
    private val eventQueue = java.util.ArrayDeque<JSONObject>()
    private var applyingEvents = false

    fun receive(value: JSONObject) {
        when (value.getString("type")) {
            "resumed" -> sync.connectionResumed(value)
            "events" -> {
                check(eventQueue.size < 64)
                eventQueue.addLast(value)
                drainEvents()
            }
            "content_applied" -> sync.readReceipts.confirmContent(value)
            "accepted" -> sync.outbox.accepted(value)
            "applied" -> sync.readReceipts.confirmDelivery(value)
            "deleted" -> sync.outbox.deleted(value)
            "error" -> handleError(value)
        }
    }

    private fun handleError(value: JSONObject) {
        val code = value.getString("code")
        val request = value.optString("requestId")
        when {
            code == "account_deleted" || code == "invalid_session" -> sync.sessionRejected(code)
            value.optBoolean("retryable", false) -> sync.reconnectAfterFailure(sync.connectionEpoch)
            sync.readReceipts.matchesRequest(request.lowercase(java.util.Locale.ROOT)) ->
                sync.readReceipts.rejectContent(code)
            sync.outbox.matchesRequest(request) -> sync.outbox.reject(code)
            else -> sync.reconnectAfterFailure(sync.connectionEpoch)
        }
    }

    fun reset() {
        eventQueue.clear()
        applyingEvents = false
    }

    // Записываем порции событий по очереди, чтобы следующая порция не попала в базу раньше
    // предыдущей.
    private fun drainEvents() {
        if (applyingEvents || eventQueue.isEmpty()) return
        applyingEvents = true
        val value = eventQueue.first
        val connectionID = sync.connectionEpoch
        fun apply() {
            sync.database({ db ->
                val changes = sync.cache.events(db, value)
                changes to sync.cache.needsReadRefresh(db)
            }) { result ->
                if (connectionID == sync.connectionEpoch)
                    result.fold(
                        { (changes, refresh) ->
                            sync.emit(changes)
                            sync.attachments.drainRemovedStudioMedia()
                            if (refresh) sync.history.refreshReadSummaries()
                            eventQueue.removeFirst()
                            applyingEvents = false
                            sync.flushPendingWork()
                            drainEvents()
                        },
                        { sync.reconnectAfterFailure(connectionID) },
                    )
            }
        }
        sync.database({ sync.cache.hasUnknownChats(it, value) }) { unknown ->
            if (connectionID == sync.connectionEpoch)
                unknown.fold(
                    { missing ->
                        if (!missing) apply()
                        else
                            sync.http({ sync.api.json("/v1/chats") }) { rows ->
                                if (connectionID == sync.connectionEpoch)
                                    rows.fold(
                                        { json ->
                                            sync.database({ sync.cache.mergeChats(it, json) }) {
                                                merged ->
                                                if (connectionID == sync.connectionEpoch)
                                                    merged.fold(
                                                        {
                                                            sync.emit(it)
                                                            apply()
                                                        },
                                                        { sync.reconnectAfterFailure(connectionID) },
                                                    )
                                            }
                                        },
                                        { sync.reconnectAfterFailure(connectionID) },
                                    )
                            }
                    },
                    { sync.reconnectAfterFailure(connectionID) },
                )
        }
    }
}
