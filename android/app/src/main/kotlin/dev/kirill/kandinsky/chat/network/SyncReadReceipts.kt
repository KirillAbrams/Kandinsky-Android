package dev.kirill.kandinsky.chat.network

import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.storage.*
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal class SyncReadReceipts(private val sync: NetworkSync) {
    private var receipt: Pair<Long, List<Long>>? = null
    @Volatile
    var contentBatchesSent = 0
        private set

    private data class ContentFlight(val request: String, val batch: ContentBatch)

    private var pendingContentReceipt: ContentFlight? = null
    private var contentTask: Runnable? = null
    private var contentTimeout: Runnable? = null
    private var contentSelecting = false
    private var contentDirty = false
    private var supportsContentReceipts = false

    fun consume(messageID: MessageID) {
        sync.worker.post {
            if (sync.closed) return@post
            sync.database({ sync.cache.queueContent(it, messageID) }) { result ->
                result.fold(
                    {
                        sync.emit(it)
                        scheduleContent()
                    },
                    { sync.publishStatus("Не удалось сохранить просмотр") },
                )
            }
        }
    }

    private fun scheduleContent() {
        contentDirty = true
        startContent()
    }

    private fun startContent() {
        if (
            !contentDirty ||
                !sync.active ||
                !sync.resumed ||
                !supportsContentReceipts ||
                sync.closed ||
                pendingContentReceipt != null ||
                contentTask != null ||
                contentSelecting
        )
            return
        val connectionID = sync.connectionEpoch
        val task = Runnable {
            contentTask = null
            if (
                connectionID != sync.connectionEpoch || !sync.active || !sync.resumed || sync.closed
            )
                return@Runnable
            contentDirty = false
            contentSelecting = true
            sync.database({ sync.cache.pendingContent(it).firstOrNull() }) { result ->
                if (connectionID != sync.connectionEpoch) return@database
                contentSelecting = false
                result.fold(
                    { batch ->
                        if (batch == null) {
                            startContent()
                            return@fold
                        }
                        val request = UUID.randomUUID().toString()
                        // Без подтверждения отправим те же отметки после переподключения.
                        pendingContentReceipt = ContentFlight(request, batch)
                        val packet =
                            JSONObject()
                                .put("type", "content_read")
                                .put("requestId", request)
                                .put("chatId", batch.chatID)
                                .put("messageIds", JSONArray(batch.messageIDs))
                                .toString()
                        if (!sync.sendContentPacket(packet)) {
                            sync.reconnectAfterFailure(connectionID)
                            return@fold
                        }
                        contentBatchesSent++
                        val deadline = Runnable {
                            if (
                                connectionID == sync.connectionEpoch &&
                                    pendingContentReceipt?.request == request
                            )
                                sync.reconnectAfterFailure(connectionID)
                        }
                        contentTimeout = deadline
                        sync.worker.postDelayed(deadline, 15000)
                    },
                    { sync.reconnectAfterFailure(connectionID) },
                )
            }
        }
        contentTask = task
        sync.worker.postDelayed(task, 100)
    }

    private fun clearContentFlight() {
        contentTimeout?.let { sync.worker.removeCallbacks(it) }
        contentTimeout = null
        pendingContentReceipt = null
    }

    fun sendDeliveryReceipt() {
        if (receipt != null) return
        sync.database({ db ->
            val cursor = sync.cache.cursor(db)
            val acked =
                db.rawQuery("SELECT value FROM net_state WHERE key='ackedCursor'", null).use {
                    if (it.moveToFirst()) it.getString(0).toLong() else -1L
                }
            val ids =
                db.rawQuery(
                        "SELECT serverID FROM net_pending_receipts ORDER BY serverID LIMIT 128",
                        null,
                    )
                    .use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
            Triple(cursor, acked, ids)
        }) { result ->
            result.onSuccess { (cursor, acked, ids) ->
                if (receipt == null && sync.resumed && (cursor > acked || ids.isNotEmpty())) {
                    receipt = cursor to ids
                    sync.send(
                        JSONObject()
                            .put("type", "applied")
                            .put("cursor", cursor)
                            .put("messageIds", JSONArray(ids))
                    )
                }
            }
        }
    }

    fun confirmContent(value: JSONObject) {
        val flight = pendingContentReceipt
        if (
            flight != null &&
                value.optString("requestId").lowercase(java.util.Locale.ROOT) == flight.request
        ) {
            val connectionID = sync.connectionEpoch
            sync.database({ sync.cache.confirmContent(it, value, flight.request, flight.batch) }) {
                result ->
                if (
                    connectionID != sync.connectionEpoch ||
                        pendingContentReceipt?.request != flight.request
                )
                    return@database
                result.fold(
                    {
                        sync.emit(it)
                        clearContentFlight()
                        scheduleContent()
                    },
                    { sync.reconnectAfterFailure(connectionID) },
                )
            }
        }
    }

    fun confirmDelivery(value: JSONObject) {
        val sent = receipt
        if (sent != null && value.getLong("cursor") == sent.first) {
            sync.database({ db ->
                NetworkStore.transaction(db) {
                    for (id in sent.second) db.delete(
                        "net_pending_receipts",
                        "serverID=?",
                        arrayOf(id.toString()),
                    )
                    db.execSQL(
                        "INSERT OR REPLACE INTO net_state(key,value) VALUES('ackedCursor',?)",
                        arrayOf(sent.first.toString()),
                    )
                }
            }) {
                receipt = null
                sync.flushPendingWork()
            }
        }
    }

    fun rejectContent(code: String) {
        val flight = pendingContentReceipt!!
        val connectionID = sync.connectionEpoch
        sync.database({ db ->
            NetworkStore.transaction(db) {
                for (id in flight.batch.messageIDs) db.execSQL(
                    "UPDATE net_pending_content SET errorCode=? WHERE serverID=? AND chatID=?",
                    arrayOf(code, id, flight.batch.chatID),
                )
            }
        }) { result ->
            if (connectionID != sync.connectionEpoch) return@database
            result.fold(
                {
                    clearContentFlight()
                    scheduleContent()
                    sync.publishStatus("Не удалось подтвердить просмотр")
                },
                { sync.reconnectAfterFailure(connectionID) },
            )
        }
    }

    fun matchesRequest(request: String) = pendingContentReceipt?.request == request

    fun resume(supportsReceipts: Boolean) {
        supportsContentReceipts = supportsReceipts
        scheduleContent()
    }

    fun reset() {
        contentTask?.let { sync.worker.removeCallbacks(it) }
        contentTask = null
        clearContentFlight()
        contentSelecting = false
        contentDirty = false
        supportsContentReceipts = false
        receipt = null
    }
}
