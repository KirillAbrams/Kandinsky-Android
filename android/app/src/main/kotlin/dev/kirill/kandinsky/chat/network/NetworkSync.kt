package dev.kirill.kandinsky.chat.network

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.storage.*
import java.io.File
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.json.JSONObject

class NetworkSync(
    val profile: NetworkProfile,
    internal val store: SQLiteStore,
    val local: SQLiteChatRepository,
    val api: NetworkAPI = NetworkAPI(profile),
) {
    private val thread = HandlerThread("KandinskySync").apply { start() }
    internal val worker = Handler(thread.looper)
    internal val main = Handler(Looper.getMainLooper())
    private val io =
        ThreadPoolExecutor(
            1,
            1,
            30,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(16),
            { r -> Thread(r, "KandinskyHTTP").apply { priority = Thread.NORM_PRIORITY - 1 } },
        )
    internal val cache = NetworkStore(local, profile.userID)
    internal var active = false
        private set

    internal var closed = false
        private set

    var studioMedia: NetworkMedia? = null
    var onStudioMediaEvicted: (() -> Unit)? = null
    private val connection = SyncConnection(this)
    val isConnected
        get() = connection.resumed

    internal val resumed
        get() = connection.resumed

    internal val connectionEpoch
        get() = connection.connectionEpoch

    internal fun reconnectAfterFailure(connectionID: Int) =
        connection.reconnectAfterFailure(connectionID)

    internal fun send(value: JSONObject) = connection.send(value)

    internal fun connectionResumed() = connection.connectionResumed()

    private val ui = NetworkUIBatch { onDeltas?.invoke(it) }
    var onDeltas: ((List<MessageDelta>) -> Unit)? = null
    var onStatus: ((String) -> Unit)? = null
    var onAccountErased: (() -> Unit)? = null
    var onSessionExpired: (() -> Unit)? = null
    private var rejectedSession = false
    private val sessionErrors = setOf("Войдите в аккаунт", "invalid_session", "account_deleted")
    var onStudioRecentChanged: (() -> Unit)? = null
    var studioTransfers: List<StudioTransfer> = emptyList()
        internal set

    var onStudioTransfersChanged: (() -> Unit)? = null
    internal val outbox = SyncOutbox(this)
    internal val cursorAcknowledgement = SyncCursorAcknowledgement(this)
    internal val history = SyncHistory(this)
    internal val attachments = SyncAttachments(this)
    private val modelCatalog = SyncModelCatalog(this)
    private val generationActions = SyncGenerationActions(this)
    private val chatDirectory = SyncChatDirectory(this)
    internal val incomingEvents = SyncIncomingEvents(this)

    fun savedStudioDraft(completion: (Result<StudioDraftRecord?>) -> Unit) =
        attachments.savedStudioDraft(completion)

    fun beginStudioDraft(
        record: StudioDraftRecord,
        completion: (Result<StudioDraftRecord?>) -> Unit,
    ) = attachments.beginStudioDraft(record, completion)

    fun saveStudioDraft(draft: Draft, identity: UUID, completion: (Result<Unit>) -> Unit) =
        attachments.saveStudioDraft(draft, identity, completion)

    fun finishStudioDraft(identity: UUID, completion: (Result<Unit>) -> Unit) =
        attachments.finishStudioDraft(identity, completion)

    fun hasStudioUpload(attachmentID: String, completion: (Result<Boolean>) -> Unit) =
        store.submit(
            { db ->
                db.rawQuery("SELECT 1 FROM net_uploads WHERE attachmentID=?", arrayOf(attachmentID))
                    .use { it.moveToFirst() }
            },
            completion,
        )

    fun openDirect(username: String, threadID: UUID? = null, completion: (Result<Long>) -> Unit) =
        chatDirectory.openDirect(username, threadID, completion)

    internal fun refreshStudioDirectory() = chatDirectory.refreshStudioDirectory()

    fun cachedStudioRecent(completion: (Result<StudioRecentPage>) -> Unit) =
        chatDirectory.cachedStudioRecent(completion)

    fun studioThreads(
        view: String,
        query: String,
        offset: Int,
        limit: Int,
        completion: (Result<List<StudioThread>>) -> Unit,
    ) = chatDirectory.studioThreads(view, query, offset, limit, completion)

    fun admitStudioThread(thread: StudioThread, completion: (Result<Unit>) -> Unit) =
        chatDirectory.admitStudioThread(thread, completion)

    fun changeStudioThread(id: Long, change: JSONObject, completion: (Result<Unit>) -> Unit) =
        chatDirectory.changeStudioThread(id, change, completion)

    fun deleteStudioThread(id: Long, completion: (Result<Unit>) -> Unit) =
        chatDirectory.deleteStudioThread(id, completion)

    fun recentStudioUploads(completion: (Result<List<StudioUpload>>) -> Unit) =
        attachments.recentStudioUploads(completion)

    fun rememberStudioUpload(
        attachmentID: String,
        blobID: String,
        previewID: String? = null,
        completion: (Result<Unit>) -> Unit,
    ) = attachments.rememberStudioUpload(attachmentID, blobID, previewID, completion)

    fun uploadStudioSource(
        draft: AttachmentDraft,
        file: File,
        transfer: dev.kirill.kandinsky.chat.media.MediaCancellation,
        chat: Long,
    ): Pair<String, String?> = attachments.uploadStudioSource(draft, file, transfer, chat)

    fun studioModels(completion: (Result<List<StudioModel>>) -> Unit) =
        modelCatalog.studioModels(completion)

    fun activeGeneration(chat: Long, completion: (Result<MessageID?>) -> Unit) =
        generationActions.activeGeneration(chat, completion)

    fun deleteGenerationContent(
        chat: Long,
        message: MessageID,
        blob: String?,
        completion: (Result<Unit>) -> Unit,
    ) = generationActions.deleteGenerationContent(chat, message, blob, completion)

    fun reportGeneration(
        chat: Long,
        message: MessageID,
        reason: StudioReportReason,
        completion: (Result<Unit>) -> Unit,
    ) = generationActions.reportGeneration(chat, message, reason, completion)

    fun generationAction(
        chat: Long,
        message: MessageID,
        action: StudioGenerationAction,
        operation: UUID?,
        slot: StudioGeneration.Slot? = null,
        completion: (Result<Unit>) -> Unit,
    ) = generationActions.generationAction(chat, message, action, operation, slot, completion)

    fun viewport(chat: Long, anchor: ReadingAnchor) = history.viewport(chat, anchor)

    fun visibleChat(chat: Long?) = history.visibleChat(chat)

    private var nextActivityAtMs = 0L

    fun userInteraction() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now < nextActivityAtMs) return
        nextActivityAtMs = now + 30_000
        worker.post { if (!closed) http({ api.json("/v1/kandinsky/activity", JSONObject()) }) {} }
    }

    fun foreground(value: Boolean) {
        worker.post {
            if (closed) return@post
            active = value
            if (value) connection.connect() else connection.disconnect()
        }
    }

    internal fun publishStatus(value: String) {
        main.post { onStatus?.invoke(value) }
    }

    fun kick() {
        worker.post { flushPendingWork() }
    }

    internal fun flushPendingWork() {
        if (resumed && active) outbox.flush()
    }

    fun eraseStudioAccount(password: String, completion: (Result<Unit>) -> Unit) {
        worker.post {
            if (closed) return@post
            http({
                try {
                    api.json(
                        "/v1/kandinsky/account",
                        JSONObject().put("password", password),
                        "DELETE",
                    )
                } catch (error: NetworkFailure) {
                    if (error.status != 401 || error.code != "account_deleted") throw error
                }
                Unit
            }) { result ->
                main.post { completion(result) }
            }
        }
    }

    internal fun sessionRejected(code: String) {
        if (rejectedSession) return
        rejectedSession = true
        active = false
        connection.disconnect()
        main.post {
            if (code == "account_deleted") onAccountErased?.invoke() else onSessionExpired?.invoke()
        }
    }

    internal fun emit(deltas: List<MessageDelta>) {
        main.post { ui.offer(deltas) }
    }

    internal fun <T> database(
        action: (android.database.sqlite.SQLiteDatabase) -> T,
        completion: (Result<T>) -> Unit,
    ) {
        store.submit(action) { result -> worker.post { if (!closed) completion(result) } }
    }

    internal fun <T> http(action: () -> T, completion: (Result<T>) -> Unit) {
        try {
            io.execute {
                val result = runCatching(action)
                worker.post {
                    if (!closed) {
                        val error = result.exceptionOrNull() as? NetworkFailure
                        if (error?.status == 401 && error.code in sessionErrors) {
                            sessionRejected(error.code)
                        }
                        completion(result)
                    }
                }
            }
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            completion(Result.failure(error))
        }
    }

    fun close() {
        onStudioMediaEvicted = null
        ui.close()
        onDeltas = null
        onStatus = null
        onAccountErased = null
        onSessionExpired = null
        onStudioRecentChanged = null
        onStudioTransfersChanged = null
        studioTransfers = emptyList()
        worker.post {
            closed = true
            active = false
            connection.disconnect()
            io.shutdownNow()
            api.close()
            thread.quitSafely()
        }
    }
}
