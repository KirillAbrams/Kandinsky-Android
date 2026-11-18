// Соединяет обмен выбранного владельца с базой телефона и экраном. Запускает сетевую работу
// отдельно, а историю, очередь и вложения передаёт ответственным частям.

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
        // Показывает экрану, подтвердил ли сервер возобновление. Открытого соединения для этого
        // признака ещё недостаточно.
        get() = connection.resumed

    internal val resumed
        // Даёт внутренней очереди признак завершённого возобновления. До подтверждения нельзя
        // считать новую сеть готовой к отправке.
        get() = connection.resumed

    internal val connectionEpoch
        // Возвращает номер текущего подключения. Он позволяет отличить поздний ответ от ответа
        // действующей связи.
        get() = connection.connectionEpoch

    // Передаёт номер сбойного соединения его владельцу. Только он может отменить старое
    // подключение и назначить следующее.
    internal fun reconnectAfterFailure(connectionID: Int) =
        connection.reconnectAfterFailure(connectionID)

    // Передаёт пакет действующему соединению, где проверяются размер и результат отправки. Так
    // команды разных частей проходят одинаковое правило.
    internal fun send(value: JSONObject) = connection.send(value)

    // Передаёт подтверждение владельцу соединения. Он снимает срок ожидания и продолжает работу,
    // которую связь до этого удерживала.
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

    // Просит хранилище вложений прочитать сохранённый черновик перед восстановлением экрана.
    // Вернувшийся пользователь увидит оставленные настройки и исходник.
    fun savedStudioDraft(completion: (Result<StudioDraftRecord?>) -> Unit) =
        attachments.savedStudioDraft(completion)

    // Передаёт новую запись в хранилище черновика, которое проверяет старую. Так открытие нового
    // черновика не смешивает вложения двух отправок.
    fun beginStudioDraft(
        record: StudioDraftRecord,
        completion: (Result<StudioDraftRecord?>) -> Unit,
    ) = attachments.beginStudioDraft(record, completion)

    // Передаёт изменившийся черновик вместе с его номером. Хранилище сможет определить, к какой
    // текущей записи относятся данные.
    fun saveStudioDraft(draft: Draft, identity: UUID, completion: (Result<Unit>) -> Unit) =
        attachments.saveStudioDraft(draft, identity, completion)

    // Закрывает черновик по его номеру после завершения отправки. Без номера позднее
    // подтверждение могло бы очистить следующий черновик.
    fun finishStudioDraft(identity: UUID, completion: (Result<Unit>) -> Unit) =
        attachments.finishStudioDraft(identity, completion)

    // Проверяет в базе, есть ли сохранённая загрузка для вложения. По ответу можно решить,
    // доступен ли уже известный исходник.
    fun hasStudioUpload(attachmentID: String, completion: (Result<Boolean>) -> Unit) =
        store.submit(
            { db ->
                db.rawQuery("SELECT 1 FROM net_uploads WHERE attachmentID=?", arrayOf(attachmentID))
                    .use { it.moveToFirst() }
            },
            completion,
        )

    // Передаёт выбранный до отправки номер новому чату. Повтор той же операции может сохранить
    // этот номер, если ответ потерялся.
    fun createStudioThread(threadID: UUID, completion: (Result<Long>) -> Unit) =
        chatDirectory.createStudioThread(threadID, completion)

    // Запрашивает обновление сохранённых карточек через общий список чатов. Так события сети и
    // действия экрана пользуются одним порядком обновления.
    internal fun refreshStudioDirectory() = chatDirectory.refreshStudioDirectory()

    // Читает списочные карточки из базы. Панель может показаться сразу, пока свежий ответ ещё в
    // пути.
    fun cachedStudioRecent(completion: (Result<StudioRecentPage>) -> Unit) =
        chatDirectory.cachedStudioRecent(completion)

    // Передаёт вид списка, поиск и границы страницы владельцу карточек. Экран получает нужную
    // порцию без собственного разбора сетевого ответа.
    fun studioThreads(
        view: String,
        query: String,
        offset: Int,
        limit: Int,
        completion: (Result<List<StudioThread>>) -> Unit,
    ) = chatDirectory.studioThreads(view, query, offset, limit, completion)

    // Передаёт выбранную карточку для записи в базу. Это даёт местной истории сведения о чате до
    // открытия его сообщений.
    fun admitStudioThread(thread: StudioThread, completion: (Result<Unit>) -> Unit) =
        chatDirectory.admitStudioThread(thread, completion)

    // Передаёт изменение карточки по номеру чата. Обновление будет применено тем же кодом, что
    // обслуживает остальной список.
    fun changeStudioThread(id: Long, change: JSONObject, completion: (Result<Unit>) -> Unit) =
        chatDirectory.changeStudioThread(id, change, completion)

    // Передаёт команду удаления по номеру чата. Это сводит сетевой ответ и местную очистку к
    // одному действию интерфейса.
    fun deleteStudioThread(id: Long, completion: (Result<Unit>) -> Unit) =
        chatDirectory.deleteStudioThread(id, completion)

    // Читает недавние исходники у владельца загрузок. Экрану нужен общий список, чтобы выбрать
    // уже переданный файл.
    fun recentStudioUploads(completion: (Result<List<StudioUpload>>) -> Unit) =
        attachments.recentStudioUploads(completion)

    // Запоминает соответствие вложения оригиналу и отдельной миниатюре. После возвращения к
    // черновику можно использовать тот же загруженный файл.
    fun rememberStudioUpload(
        attachmentID: String,
        blobID: String,
        previewID: String? = null,
        completion: (Result<Unit>) -> Unit,
    ) = attachments.rememberStudioUpload(attachmentID, blobID, previewID, completion)

    // Передаёт файл, номер чата и сигнал отмены владельцу загрузки. Вызывающий код получает
    // номера исходника и миниатюры, чтобы связать их с запросом.
    fun uploadStudioSource(
        draft: AttachmentDraft,
        file: File,
        transfer: dev.kirill.kandinsky.chat.media.MediaCancellation,
        chat: Long,
    ): Pair<String, String?> = attachments.uploadStudioSource(draft, file, transfer, chat)

    // Берёт доступные модели и параметры из общего списка возможностей. Экран не держит отдельную
    // копию разрешённых настроек.
    fun studioModels(completion: (Result<List<StudioModel>>) -> Unit) =
        modelCatalog.studioModels(completion)

    // Просит найти незавершённую генерацию открытого чата. Это позволяет экрану показать действие
    // для нужной строки.
    fun activeGeneration(chat: Long, completion: (Result<MessageID?>) -> Unit) =
        generationActions.activeGeneration(chat, completion)

    // Передаёт номер строки и при необходимости номер файла владельцу действий генерации. От этих
    // данных зависит, удалится весь запрос или один результат.
    fun deleteGenerationContent(
        chat: Long,
        message: MessageID,
        blob: String?,
        completion: (Result<Unit>) -> Unit,
    ) = generationActions.deleteGenerationContent(chat, message, blob, completion)

    // Передаёт номер результата и причину жалобы. Сервер по этим данным создаёт жалобу именно
    // на выбранный результат.
    fun reportGeneration(
        chat: Long,
        message: MessageID,
        reason: StudioReportReason,
        completion: (Result<Unit>) -> Unit,
    ) = generationActions.reportGeneration(chat, message, reason, completion)

    // Передаёт запрошенное действие и номер операции или отдельной части через общий обработчик.
    // Повтор при потере ответа может использовать прежний номер.
    fun generationAction(
        chat: Long,
        message: MessageID,
        action: StudioGenerationAction,
        operation: UUID?,
        slot: StudioGeneration.Slot? = null,
        completion: (Result<Unit>) -> Unit,
    ) = generationActions.generationAction(chat, message, action, operation, slot, completion)

    // Сообщает владельцу истории положение чтения, чтобы запрашивать подходящую страницу и
    // обновлять прилегающие записи.
    fun viewport(chat: Long, anchor: ReadingAnchor) = history.viewport(chat, anchor)

    // Указывает, какой чат сейчас открыт, или сообщает о его закрытии. Так обновления истории
    // привязаны к видимому чату.
    fun visibleChat(chat: Long?) = history.visibleChat(chat)

    private var nextActivityAtMs = 0L

    // Сообщает о действии владельца в серверном сеансе с ограничением частоты. Сетевой вызов
    // выполняется в рабочей очереди и не задерживает касание.
    fun userInteraction() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now < nextActivityAtMs) return
        nextActivityAtMs = now + 30_000
        worker.post { if (!closed) http({ api.json("/v1/kandinsky/activity", JSONObject()) }) {} }
    }

    // Передаёт видимость приложения рабочему потоку. При уходе останавливает связь, при
    // возвращении даёт ей возобновиться.
    fun foreground(value: Boolean) {
        worker.post {
            if (closed) return@post
            active = value
            if (value) connection.connect() else connection.disconnect()
        }
    }

    // Передаёт изменение состояния связи в поток экрана. Сетевой поток не должен менять экран
    // напрямую.
    internal fun publishStatus(value: String) {
        main.post { onStatus?.invoke(value) }
    }

    // Ставит попытку отправки ожидающих команд в очередь связи. Это сохраняет порядок при
    // одновременных действиях.
    fun kick() {
        worker.post { flushPendingWork() }
    }

    // Отправляет очередь только при активном приложении и подтверждённой связи. Иначе команды
    // могли бы уйти до восстановления сеанса.
    internal fun flushPendingWork() {
        if (resumed && active) outbox.flush()
    }

    // Отправляет пароль для удаления и возвращает результат в поток экрана. Ответ об уже
    // удалённом аккаунте принимает, чтобы потерянное подтверждение не выглядело как отказ.
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

    // Один раз останавливает обмен и передаёт экрану причину отказа. При удалении нужна очистка
    // профиля, при истечении входа нужен повторный вход.
    internal fun sessionRejected(code: String) {
        if (rejectedSession) return
        rejectedSession = true
        active = false
        connection.disconnect()
        main.post {
            if (code == "account_deleted") onAccountErased?.invoke() else onSessionExpired?.invoke()
        }
    }

    // Передаёт готовые изменения в общую очередь обновления экрана. Это объединяет работу с
    // лентой и не даёт сетевому потоку рисовать сам.
    internal fun emit(deltas: List<MessageDelta>) {
        main.post { ui.offer(deltas) }
    }

    // Отправляет чтение или запись в хранилище и переносит результат обратно в очередь обмена.
    // Закрытый обмен не получает опоздавшее продолжение.
    internal fun <T> database(
        action: (android.database.sqlite.SQLiteDatabase) -> T,
        completion: (Result<T>) -> Unit,
    ) {
        store.submit(action) { result -> worker.post { if (!closed) completion(result) } }
    }

    // Выполняет запрос в отдельной ограниченной очереди и передаёт ответ в поток обмена.
    // Известный отказ входа останавливает сеанс, а переполнение очереди возвращает ошибку
    // вызывающему коду.
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

    // Убирает связь с экраном, останавливает подключение, запросы и рабочий поток. Иначе закрытый
    // аккаунт оставлял бы живые операции и обработчики.
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
