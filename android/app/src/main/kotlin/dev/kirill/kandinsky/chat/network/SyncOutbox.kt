package dev.kirill.kandinsky.chat.network

import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.storage.*
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal class SyncOutbox(private val sync: NetworkSync) {
    private val payloads = OutgoingMessagePayloads(sync)
    private var sendingBatch = false
    private var outboxNeedsFlush = false
    private var pendingRequestID: String? = null
    private var pendingOperationIDs = emptyList<String>()

    private fun sendNextBatch() {
        if (sendingBatch || pendingRequestID != null || !sync.resumed) return
        sendingBatch = true
        outboxNeedsFlush = false
        val connectionID = sync.connectionEpoch
        sync.database({ db -> NetworkJournal.readyOperations(db) }) { result ->
            if (connectionID != sync.connectionEpoch) return@database
            val commands =
                result.getOrElse {
                    sync.reconnectAfterFailure(connectionID)
                    return@database
                }
            if (commands.isEmpty()) {
                sendingBatch = false
                if (outboxNeedsFlush) sendNextBatch()
                return@database
            }

            if (commands.first().first == "delete") {
                val delete = JSONObject(commands.first().second)
                sync.database({ db ->
                    db.rawQuery(
                            "SELECT serverID FROM net_messages WHERE clientID=? AND senderID=?",
                            arrayOf(
                                delete.getString("targetClientId"),
                                sync.profile.userID.toString(),
                            ),
                        )
                        .use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null } to
                        db.rawQuery(
                                "SELECT id FROM net_outbox WHERE action='delete' AND payload=?",
                                arrayOf(commands.first().second),
                            )
                            .use {
                                check(it.moveToFirst())
                                it.getString(0)
                            }
                }) { mapping ->
                    mapping.fold(
                        { (server, id) ->
                            if (server == null) {
                                sendingBatch = false
                            } else {
                                pendingRequestID = id
                                pendingOperationIDs = listOf(id)
                                sync.send(
                                    JSONObject()
                                        .put("type", "delete")
                                        .put("requestId", id)
                                        .put("chatId", delete.getLong("chatId"))
                                        .put("messageId", server)
                                )
                            }
                        },
                        { sync.reconnectAfterFailure(connectionID) },
                    )
                }
                return@database
            }
            val values = commands.map { ChatStorageCodec.readCommand(it.second) }
            payloads.prepare(values) { prepared ->
                if (connectionID != sync.connectionEpoch) return@prepare
                prepared.fold(
                    { messages ->
                        if (messages.isEmpty()) {
                            sendingBatch = false
                            if (outboxNeedsFlush) sendNextBatch()
                            return@fold
                        }
                        val request = UUID.randomUUID().toString()
                        pendingRequestID = request
                        pendingOperationIDs = messages.map { it.getString("clientId") }
                        sync.send(
                            JSONObject()
                                .put("type", "send")
                                .put("requestId", request)
                                .put("messages", JSONArray(messages))
                        )
                        sync.worker.postDelayed(
                            {
                                if (pendingRequestID == request)
                                    sync.reconnectAfterFailure(connectionID)
                            },
                            15000,
                        )
                    },
                    { error ->
                        if (error is NetworkFailure && error.status == 401) {
                            sync.sessionRejected(error.code)
                        } else if (
                            error is NetworkFailure &&
                                error.status in 400..499 &&
                                error.status !in listOf(408, 429)
                        ) {
                            sync.database({
                                sync.cache.failed(
                                    it,
                                    values.map { c -> c.operationID.lowercase() },
                                    error.code,
                                )
                            }) { result ->
                                sendingBatch = false
                                result.onSuccess { sync.emit(it) }
                                sync.publishStatus("Не отправлено: " + error.code)
                                sync.flushPendingWork()
                            }
                        } else {
                            sendingBatch = false
                            sync.reconnectAfterFailure(connectionID)
                        }
                    },
                )
            }
        }
    }

    fun accepted(value: JSONObject) {
        sync.database({ sync.cache.accepted(it, value) }) { result ->
            result.fold(
                {
                    if (value.optString("requestId") == pendingRequestID) {
                        pendingRequestID = null
                        pendingOperationIDs = emptyList()
                    }
                    sendingBatch = false
                    sync.emit(it)
                    sync.flushPendingWork()
                },
                { sync.reconnectAfterFailure(sync.connectionEpoch) },
            )
        }
    }

    fun deleted(value: JSONObject) {
        val id = value.getString("requestId")
        sync.database({ db ->
            db.delete("net_outbox", "id=? AND action='delete'", arrayOf(id))
            Unit
        }) {
            pendingRequestID = null
            sendingBatch = false
            sync.flushPendingWork()
        }
    }

    fun reject(code: String) {
        val ids = pendingOperationIDs
        sync.database({ sync.cache.failed(it, ids, code) }) { result ->
            pendingRequestID = null
            sendingBatch = false
            result.onSuccess { sync.emit(it) }
            sync.publishStatus("Не отправлено: $code")
            sync.flushPendingWork()
        }
    }

    fun matchesRequest(request: String) = request == pendingRequestID

    fun flush() {
        outboxNeedsFlush = true
        sync.readReceipts.sendDeliveryReceipt()
        sync.history.sendVisibleReadPosition()
        sendNextBatch()
    }

    fun reset() {
        pendingRequestID = null
        pendingOperationIDs = emptyList()
        sendingBatch = false
        outboxNeedsFlush = false
    }
}
