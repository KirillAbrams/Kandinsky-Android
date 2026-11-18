package dev.kirill.kandinsky.chat.network

import android.net.Uri
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

data class StudioThread(
    val id: Long,
    val title: String,
    val lastSeq: Long,
    val unreadCount: Long,
    val pinned: Boolean,
    val archived: Boolean,
) {
    /**
     * Записывает поля сводки чата в объект обмена, который понимает местное сетевое хранилище
     * Сохраняет номер, название, порядок, счётчик непрочитанного и признаки закрепления и архива
     */
    fun wire() =
        JSONObject()
            .put("id", id)
            .put("title", title)
            .put("lastSeq", lastSeq)
            .put("unreadCount", unreadCount)
            .put("pinned", pinned)
            .put("archived", archived)

    companion object {
        /**
         * Отсутствие поля или неверный вид значения оставляет ошибкой разбора вместо подстановки
         * выдуманных данных
         */
        fun from(value: JSONObject) =
            StudioThread(
                value.getLong("id"),
                value.getString("title"),
                value.getLong("lastSeq"),
                value.getLong("unreadCount"),
                value.getBoolean("pinned"),
                value.getBoolean("archived"),
            )
    }
}

data class StudioRecentPage(val threads: List<StudioThread>, val nextOffset: Int?) {
    companion object {
        const val CATALOG_LIMIT = 200
    }
}

/**
 * Владеет одним постоянным соединением открытого приложения и одной ожидающей исходящей порцией
 * Согласует местную базу, ограниченный приём событий и отдельную сетевую очередь
 */
class NetworkSync(
    val profile: NetworkProfile,
    private val store: SQLiteStore,
    val local: SQLiteChatRepository,
    val api: NetworkAPI = NetworkAPI(profile),
) {
    private val thread = HandlerThread("KandinskySync").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val io =
        ThreadPoolExecutor(
            1,
            1,
            30,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(16),
            { r -> Thread(r, "KandinskyHTTP").apply { priority = Thread.NORM_PRIORITY - 1 } },
        )
    private val eventQueue = java.util.ArrayDeque<JSONObject>()
    private var applyingEvents = false
    private val pendingFrames = AtomicInteger()
    private val cache = NetworkStore(local, profile.userID)
    private var socket: WebSocket? = null
    private var active = false
    private var closed = false
    var studioMedia: NetworkMedia? = null
    var onStudioMediaEvicted: (() -> Unit)? = null
    private var removingMedia = false

    /**
     * Забирает из базы до шестнадцати подтверждённых удалений файлов, не запуская вторую очистку
     * одновременно В сетевой рабочей очереди убирает местные копии, затем сообщает экрану об
     * изменении доступного содержимого Удаляет обработанные отметки только при совпадении
     * сохранённого значения и продолжает следующую порцию после успешной записи
     */
    private fun drainRemovedStudioMedia() {
        val media = studioMedia ?: return
        if (closed || removingMedia) return
        removingMedia = true
        database({ db ->
            db.rawQuery(
                    "SELECT key,value FROM net_state WHERE key LIKE 'studioRemoved:%' LIMIT 16",
                    null,
                )
                .use { rows ->
                    buildList {
                        while (rows.moveToNext()) StudioMediaRemoval.parse(
                                rows.getString(0).removePrefix(StudioMediaRemoval.PREFIX),
                                rows.getString(1),
                            )
                            ?.let { add(it) }
                    }
                }
        }) { result ->
            val batch = result.getOrNull()
            if (batch.isNullOrEmpty()) {
                removingMedia = false
                return@database
            }
            http({ for (item in batch) media.removeCached(item) }) { removed ->
                if (removed.isFailure) {
                    removingMedia = false
                    return@http
                }
                main.post { onStudioMediaEvicted?.invoke() }
                database({ db ->
                    for (item in batch) db.delete(
                        "net_state",
                        "key=? AND value=?",
                        arrayOf(item.key, item.preview),
                    )
                }) { saved ->
                    removingMedia = false
                    if (saved.isSuccess) drainRemovedStudioMedia()
                }
            }
        }
    }

    private var resumeTimeout: Runnable? = null
    @Volatile private var resumed = false
    val isConnected
        get() = resumed

    private var generation = 0
    private var attempt = 0
    private var pumping = false
    private var pumpDirty = false
    private var receipt: Pair<Long, List<Long>>? = null
    private var sendRequest: String? = null
    private var sendIDs = emptyList<String>()
    private var pendingRead: Pair<Long, Long>? = null
    private var currentChat: Long? = null
    private var atBottom = false
    private var readRefreshing = false
    private var readRefreshAgain = false
    private var readRefreshTask: Runnable? = null
    private val historyRequests = mutableSetOf<Long>()
    private val historyRetry = mutableMapOf<Long, Long>()
    private val visibleHistoryRefreshes = linkedMapOf<Long, Long>()
    private val ui = NetworkUIBatch { onDeltas?.invoke(it) }
    var onDeltas: ((List<MessageDelta>) -> Unit)? = null
    var onStatus: ((String) -> Unit)? = null
    var onAccountErased: (() -> Unit)? = null
    var onSessionExpired: (() -> Unit)? = null
    private var rejectedSession = false
    private val sessionErrors = setOf("Войдите в аккаунт", "invalid_session", "account_deleted")
    var onStudioRecentChanged: (() -> Unit)? = null
    @Volatile
    var contentBatchesSent = 0
        private set

    private data class ContentFlight(val request: String, val batch: ContentBatch)

    private var contentFlight: ContentFlight? = null
    private var contentTask: Runnable? = null
    private var contentTimeout: Runnable? = null
    private var contentSelecting = false
    private var contentDirty = false
    private var contentAcks = false

    /**
     * В очереди синхронизации сохраняет просмотр вложения через местную базу После записи передаёт
     * изменения экрану и планирует доставку отметки просмотра серверу, при ошибке сообщает о
     * несохранённом просмотре
     */
    fun consume(messageID: MessageID) {
        worker.post {
            if (closed) return@post
            database({ cache.queueContent(it, messageID) }) { result ->
                result.fold(
                    {
                        emit(it)
                        scheduleContent()
                    },
                    { status("Не удалось сохранить просмотр") },
                )
            }
        }
    }

    /**
     * Отмечает, что среди местных просмотров может быть новая работа для отправки Пробует запустить
     * доставку через общий проверяющий вход, который учитывает соединение и уже ожидаемое
     * подтверждение
     */
    private fun scheduleContent() {
        contentDirty = true
        startContent()
    }

    /**
     * Начинает отправку просмотров только при открытом приложении, восстановленном соединении и
     * поддержке подтверждений сервером Через короткую паузу выбирает одну сохранённую порцию и
     * связывает её с новым номером запроса Не допускает второй порции до подтверждения первой, а
     * через пятнадцать секунд без ответа запускает восстановление связи Номер поколения соединения
     * отсекает запоздалые действия после смены подключения
     */
    private fun startContent() {
        if (
            !contentDirty ||
                !active ||
                !resumed ||
                !contentAcks ||
                closed ||
                contentFlight != null ||
                contentTask != null ||
                contentSelecting
        )
            return
        val ticket = generation
        val task = Runnable {
            contentTask = null
            if (ticket != generation || !active || !resumed || closed) return@Runnable
            contentDirty = false
            contentSelecting = true
            database({ cache.pendingContent(it).firstOrNull() }) { result ->
                if (ticket != generation) return@database
                contentSelecting = false
                result.fold(
                    { batch ->
                        if (batch == null) {
                            startContent()
                            return@fold
                        }
                        val request = UUID.randomUUID().toString()
                        // Одна ожидающая порция ограничивает работу соединения и связывает повтор с
                        // конкретным запросом
                        // Следующая порция начнётся после подтверждения этой
                        contentFlight = ContentFlight(request, batch)
                        val packet =
                            JSONObject()
                                .put("type", "content_read")
                                .put("requestId", request)
                                .put("chatId", batch.chatID)
                                .put("messageIds", JSONArray(batch.messageIDs))
                                .toString()
                        if (socket?.send(packet) != true) {
                            retry(ticket)
                            return@fold
                        }
                        contentBatchesSent++
                        val deadline = Runnable {
                            if (ticket == generation && contentFlight?.request == request)
                                retry(ticket)
                        }
                        contentTimeout = deadline
                        worker.postDelayed(deadline, 15000)
                    },
                    { retry(ticket) },
                )
            }
        }
        contentTask = task
        worker.postDelayed(task, 100)
    }

    /**
     * Снимает запланированную проверку срока подтверждения просмотров, если она ещё ожидается
     * Очищает сведения о текущей порции, освобождая место для следующей отправки
     */
    private fun clearContentFlight() {
        contentTimeout?.let { worker.removeCallbacks(it) }
        contentTimeout = null
        contentFlight = null
    }

    /**
     * Переводит изменение видимости приложения в последовательную очередь синхронизации При выходе
     * на экран включает подключение, при уходе в фон закрывает его, а завершённую службу больше не
     * запускает
     */
    fun foreground(value: Boolean) {
        worker.post {
            if (closed) return@post
            active = value
            if (value) connect() else disconnect()
        }
    }

    /**
     * Передаёт строку состояния подключения в главный поток приложения Экран получает её через
     * установленный обработчик без прямого изменения представлений из рабочей очереди
     */
    private fun status(value: String) {
        main.post { onStatus?.invoke(value) }
    }

    /**
     * Начинает новое подключение только для активной незакрытой службы без уже открытого соединения
     * Проверяет готовность местной базы и выбирает начальную загрузку либо сверку списка
     * существующих чатов Сохраняет полученные данные до открытия постоянного соединения и
     * отбрасывает ответы прежнего поколения подключения Отказ входа обрабатывает отдельно, а
     * остальные сбои передаёт восстановлению с задержкой
     */
    private fun connect() {
        if (!active || closed || socket != null) return
        generation++
        val ticket = generation
        status("Подключение…")
        database({ cache.initialized(it) }) { ready ->
            if (ticket != generation || !active) return@database
            if (ready.isFailure) {
                retry(ticket)
                return@database
            }
            http({
                api.initialize()
                api.json(if (ready.getOrThrow()) "/v1/chats" else "/v1/bootstrap")
            }) { result ->
                if (ticket != generation || !active) return@http
                result.fold(
                    { bootstrap ->
                        if (bootstrap == null) openSocket(ticket)
                        else
                            database({
                                if (ready.getOrThrow())
                                    cache.mergeChats(it, bootstrap, studioRecent = true)
                                else cache.bootstrap(it, bootstrap)
                            }) { applied ->
                                if (ticket == generation) {
                                    applied.fold(
                                        {
                                            emit(it)
                                            main.post { onStudioRecentChanged?.invoke() }
                                            openSocket(ticket)
                                        },
                                        { retry(ticket) },
                                    )
                                }
                            }
                    },
                    { error ->
                        if (error is NetworkFailure && error.status == 401)
                            sessionRejected(error.code)
                        else retry(ticket)
                    },
                )
            }
        }
    }

    /**
     * Открывает постоянное соединение для текущего поколения и ставит предел ожидания подтверждения
     * восстановления Подключает обработчики открытия, входящих сообщений, ошибок и закрытия к
     * очереди синхронизации Ограничивает размер входящего сообщения и число ещё не обработанных
     * сообщений, чтобы приём не рос без предела
     */
    private fun openSocket(ticket: Int) {
        if (ticket != generation || !active) return
        val deadline = Runnable { if (!resumed) retry(ticket) }
        resumeTimeout = deadline
        worker.postDelayed(deadline, 15000)
        socket =
            api.socket(
                object : WebSocketListener() {
                    /**
                     * После открытия соединения проверяет, что оно относится к ещё действующему
                     * поколению Читает сохранённый курсор событий и отправляет его серверу для
                     * продолжения, а устаревшее соединение сразу отменяет
                     */
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        worker.post {
                            if (ticket != generation) {
                                webSocket.cancel()
                                return@post
                            }
                            database({ cache.cursor(it) }) { result ->
                                if (ticket == generation) {
                                    result.fold(
                                        {
                                            send(
                                                JSONObject().put("type", "resume").put("cursor", it)
                                            )
                                        },
                                        { retry(ticket) },
                                    )
                                }
                            }
                        }
                    }

                    /**
                     * Отклоняет сообщение больше шестидесяти четырёх килобайт и переполнение
                     * очереди из шестидесяти четырёх ожидающих сообщений Допустимое содержимое
                     * разбирает в очереди синхронизации только для текущего поколения соединения
                     * После обработки обязательно уменьшает счётчик ожидания, а ошибку разбора
                     * переводит в восстановление связи
                     */
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (text.toByteArray().size > 65536) {
                            webSocket.cancel()
                            return
                        }
                        if (pendingFrames.incrementAndGet() > 64) {
                            pendingFrames.decrementAndGet()
                            webSocket.cancel()
                            return
                        }
                        worker.post {
                            try {
                                if (ticket == generation) handle(JSONObject(text))
                            } catch (_: Exception) {
                                retry(ticket)
                            } finally {
                                pendingFrames.decrementAndGet()
                            }
                        }
                    }

                    /**
                     * Передаёт отказ постоянного соединения в последовательную очередь
                     * синхронизации Запрашивает восстановление с номером поколения, чтобы ошибка
                     * старого соединения не закрыла новое
                     */
                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        worker.post { retry(ticket) }
                    }

                    /**
                     * Отвечает на начатое сервером закрытие обычным кодом завершения соединения
                     * После этого ставит проверку восстановления в рабочую очередь с привязкой к
                     * поколению
                     */
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                        worker.post { retry(ticket) }
                    }

                    /**
                     * После полного закрытия соединения передаёт запрос восстановления в рабочую
                     * очередь Общая процедура проверит актуальность поколения и необходимость
                     * подключения при текущем состоянии приложения
                     */
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        worker.post { retry(ticket) }
                    }
                }
            )
    }

    /**
     * Разбирает вид сообщения постоянного соединения и направляет его в обработку событий,
     * подтверждений, присутствия или ошибок После восстановления включает объявленные сервером
     * возможности и возобновляет доставку накопленных операций Подтверждённые отправки и просмотры
     * сначала сохраняет в базе, затем освобождает ожидание и запускает следующую работу
     * Окончательные ошибки связывает с соответствующим запросом, а временный отказ и потерю сессии
     * обрабатывает отдельными путями
     */
    private fun handle(value: JSONObject) {
        when (value.getString("type")) {
            "resumed" -> {
                drainRemovedStudioMedia()
                resumeTimeout?.let { worker.removeCallbacks(it) }
                resumeTimeout = null
                resumed = true
                attempt = 0
                contentAcks =
                    value.optJSONObject("features")?.optBoolean("contentReceiptAcks") == true
                scheduleContent()
                status("В сети")
                kickInternal()
                currentChat?.let(::refreshVisibleHistory)
            }
            "events" -> {
                check(eventQueue.size < 64)
                eventQueue.addLast(value)
                drainEvents()
            }
            "content_applied" -> {
                val flight = contentFlight
                if (
                    flight != null &&
                        value.optString("requestId").lowercase(java.util.Locale.ROOT) ==
                            flight.request
                ) {
                    val ticket = generation
                    database({ cache.confirmContent(it, value, flight.request, flight.batch) }) {
                        result ->
                        if (ticket != generation || contentFlight?.request != flight.request)
                            return@database
                        result.fold(
                            {
                                emit(it)
                                clearContentFlight()
                                scheduleContent()
                            },
                            { retry(ticket) },
                        )
                    }
                }
            }
            "accepted" ->
                database({ cache.accepted(it, value) }) { result ->
                    result.fold(
                        {
                            if (value.optString("requestId") == sendRequest) {
                                sendRequest = null
                                sendIDs = emptyList()
                            }
                            pumping = false
                            emit(it)
                            kickInternal()
                        },
                        { retry(generation) },
                    )
                }
            "applied" -> {
                val sent = receipt
                if (sent != null && value.getLong("cursor") == sent.first) {
                    database({ db ->
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
                        kickInternal()
                    }
                }
            }
            "deleted" -> {
                val id = value.getString("requestId")
                database({ db ->
                    db.delete("net_outbox", "id=? AND action='delete'", arrayOf(id))
                    Unit
                }) {
                    sendRequest = null
                    pumping = false
                    kickInternal()
                }
            }
            "error" -> {
                val code = value.getString("code")
                if (code == "account_deleted" || code == "invalid_session") {
                    sessionRejected(code)
                } else if (value.optBoolean("retryable", false)) {
                    retry(generation)
                } else if (
                    contentFlight?.request ==
                        value.optString("requestId").lowercase(java.util.Locale.ROOT)
                ) {
                    val flight = contentFlight!!
                    val ticket = generation
                    database({ db ->
                        NetworkStore.transaction(db) {
                            for (id in flight.batch.messageIDs) db.execSQL(
                                "UPDATE net_pending_content SET errorCode=? WHERE serverID=? AND chatID=?",
                                arrayOf(code, id, flight.batch.chatID),
                            )
                        }
                    }) { result ->
                        if (ticket != generation) return@database
                        result.fold(
                            {
                                clearContentFlight()
                                scheduleContent()
                                status("Не удалось подтвердить просмотр")
                            },
                            { retry(ticket) },
                        )
                    }
                } else if (value.optString("requestId") == sendRequest) {
                    val ids = sendIDs
                    database({ cache.failed(it, ids, code) }) { result ->
                        sendRequest = null
                        pumping = false
                        result.onSuccess { emit(it) }
                        status("Не отправлено: $code")
                        kickInternal()
                    }
                } else retry(generation)
            }
        }
    }

    /**
     * Применяет по одной порции входящих событий, сохраняя порядок даже при асинхронном обращении к
     * базе Если события содержат неизвестные чаты, сначала получает и сохраняет их сводки
     * Продолжение допускается только для действующего поколения соединения, при ошибке запускается
     * восстановление
     */
    private fun drainEvents() {
        if (applyingEvents || eventQueue.isEmpty()) return
        applyingEvents = true
        val value = eventQueue.first
        val ticket = generation
        /**
         * сообщений Передаёт изменения экрану, запускает удаление отозванных файлов и снимает
         * порцию с очереди после успешного применения Затем возобновляет исходящую работу и
         * обработку следующей порции, если соединение всё ещё актуально
         */
        fun apply() {
            database({ db ->
                val changes = cache.events(db, value)
                changes to cache.needsReadRefresh(db)
            }) { result ->
                if (ticket == generation)
                    result.fold(
                        { (changes, refresh) ->
                            emit(changes)
                            drainRemovedStudioMedia()
                            if (refresh) refreshReadSummaries()
                            eventQueue.removeFirst()
                            applyingEvents = false
                            kickInternal()
                            drainEvents()
                        },
                        { retry(ticket) },
                    )
            }
        }
        database({ cache.hasUnknownChats(it, value) }) { unknown ->
            if (ticket == generation)
                unknown.fold(
                    { missing ->
                        if (!missing) apply()
                        else
                            http({ api.json("/v1/chats") }) { rows ->
                                if (ticket == generation)
                                    rows.fold(
                                        { json ->
                                            database({ cache.mergeChats(it, json) }) { merged ->
                                                if (ticket == generation)
                                                    merged.fold(
                                                        {
                                                            emit(it)
                                                            apply()
                                                        },
                                                        { retry(ticket) },
                                                    )
                                            }
                                        },
                                        { retry(ticket) },
                                    )
                            }
                    },
                    { retry(ticket) },
                )
        }
    }

    /**
     * Создаёт или повторно открывает серверный чат с необязательным постоянным номером перед первой
     * отправкой Проверяет полученную запись и положительный номер, затем сохраняет чат в местном
     * списке Завершение возвращает в главный поток после записи, поэтому экран не открывает
     * неподтверждённую местную запись
     */
    fun openDirect(username: String, threadID: UUID? = null, completion: (Result<Long>) -> Unit) {
        worker.post {
            if (closed) {
                main.post { completion(Result.failure(IllegalStateException("Session closed"))) }
                return@post
            }
            http({
                api.json(
                    "/v1/demo/chats",
                    JSONObject().put("username", username).put("id", threadID?.toString()),
                )
            }) { result ->
                result.fold(
                    { response ->
                        val chat = response.optJSONObject("chat")
                        val id = chat?.optLong("id", 0L) ?: 0L
                        if (chat == null || id <= 0)
                            main.post {
                                completion(
                                    Result.failure(IllegalStateException("Неверный ответ истории"))
                                )
                            }
                        else
                            database({
                                cache.mergeChats(
                                    it,
                                    JSONObject().put("chats", JSONArray().put(chat)),
                                )
                            }) { merged ->
                                merged.onSuccess { emit(it) }
                                main.post { completion(merged.map { id }) }
                            }
                    },
                    { main.post { completion(Result.failure(it)) } },
                )
            }
        }
    }

    /**
     * В отдельной сетевой очереди получает список недавних исходных файлов владельца Преобразует
     * номер, вид и размер каждого файла в запись выбора и возвращает список в главный поток
     */
    fun recentStudioUploads(completion: (Result<List<StudioUpload>>) -> Unit) {
        worker.post {
            if (closed) return@post
            http({
                val rows = api.json("/v1/kandinsky/uploads/recent").getJSONArray("uploads")
                List(rows.length()) { index ->
                    val row = rows.getJSONObject(index)
                    StudioUpload(
                        row.getString("blobId"),
                        row.getString("mime"),
                        row.getLong("byteSize"),
                    )
                }
            }) { result ->
                main.post { completion(result) }
            }
        }
    }

    /**
     * уменьшенной копией Такая запись позволяет подготовке сообщения повторно использовать
     * загрузку, результат сохранения возвращается в главный поток
     */
    fun rememberStudioUpload(
        attachmentID: String,
        blobID: String,
        previewID: String? = null,
        completion: (Result<Unit>) -> Unit,
    ) {
        worker.post {
            database({ db ->
                db.execSQL(
                    "INSERT OR REPLACE INTO net_uploads(attachmentID,blobID,previewBlobID) VALUES(?,?,?)",
                    arrayOf(attachmentID, blobID, previewID),
                )
            }) { result ->
                main.post { completion(result) }
            }
        }
    }

    /**
     * Получает постоянный номер загрузки из номера устройства и местного вложения, чтобы повтор
     * использовал тот же исходник Синхронно передаёт файл как исходник генерации с поддержкой
     * отмены и обновления хода передачи Для изображения после успешной передачи отдельно загружает
     * уменьшенную копию, если операция ещё не отменена
     */
    fun uploadStudioSource(
        draft: AttachmentDraft,
        file: File,
        transfer: dev.kirill.kandinsky.chat.media.MediaCancellation,
    ): Pair<String, String?> {
        val id =
            UUID.nameUUIDFromBytes((profile.deviceID + ":" + draft.id).toByteArray()).toString()
        val blob =
            api.upload(file, draft.mime, id, studioSource = true, transfer = transfer)
                .getString("blobId")
        val preview =
            if (draft.mime.startsWith("image/") && !transfer.isCancelled)
                NetworkPreview.upload(store.root, draft, api, profile.deviceID)
            else null
        return blob to preview
    }

    /**
     * Запрашивает серверный каталог моделей вне главного потока и разбирает их названия, описания и
     * ограничения Для увеличения разрешения видео оставляет обычные настройки пустыми и отдельно
     * учитывает настройки режима с исходным файлом Возвращает готовый список или ошибку в главный
     * поток для обновления выбора модели
     */
    fun studioModels(completion: (Result<List<StudioModel>>) -> Unit) {
        worker.post {
            if (closed) return@post
            http({
                val values = api.json("/v1/kandinsky/models").getJSONArray("models")
                List(values.length()) { index ->
                    val value = values.getJSONObject(index)
                    val id = value.getString("id")
                    StudioModel(
                        id,
                        value.getString("name"),
                        value.getString("description"),
                        if (id == "video_sr") null
                        else studioCapabilities(value.getJSONObject("capabilities")),
                        value.optJSONObject("reference_capabilities")?.let(::studioCapabilities),
                    )
                }
            }) { result ->
                main.post { completion(result) }
            }
        }
    }

    /**
     * режима Добавляет поддержку исключаемого содержимого и необязательные границы силы следования
     * запросу Эти данные нужны экрану настроек, сами входящие серверные задания здесь не создаются
     */
    private fun studioCapabilities(value: JSONObject): StudioCapabilities {
        /**
         * варианта сохраняет номер, подпись и доступную таблицу размеров по пропорциям, оставляя её
         * пустой при отсутствии
         */
        fun choices(key: String): List<StudioChoice> {
            val array = value.getJSONArray(key)
            return List(array.length()) { index ->
                val choice = array.getJSONObject(index)
                val sizes = choice.optJSONObject("sizes")
                StudioChoice(
                    choice.getString("id"),
                    choice.getString("name"),
                    sizes?.let { entries ->
                        entries.keys().asSequence().associateWith { aspect ->
                            val dimensions = entries.getJSONArray(aspect)
                            List(dimensions.length()) { dimensions.getInt(it) }
                        }
                    } ?: emptyMap(),
                )
            }
        }
        val aspects = value.getJSONArray("aspects")
        val durations = value.getJSONArray("duration_seconds")
        val guidance =
            value.optJSONObject("guidance")?.let {
                StudioGuidance(it.getDouble("min"), it.getDouble("max"), it.getDouble("default"))
            }
        return StudioCapabilities(
            choices("quality"),
            choices("resolutions"),
            List(aspects.length()) { aspects.getString(it) },
            List(durations.length()) { durations.getInt(it) },
            value.getInt("max_count"),
            value.getBoolean("negative_prompt"),
            guidance,
        )
    }

    /**
     * Читает сохранённый каталог недавних чатов через очередь местной базы Возвращает местную
     * порцию без сетевого ожидания для немедленного открытия боковой истории
     */
    fun cachedStudioRecent(completion: (Result<StudioRecentPage>) -> Unit) =
        store.submit(cache::studioRecent, completion)

    /**
     * размером порции Кодирует текстовые параметры адреса, разбирает полученные записи в сетевой
     * очереди и возвращает результат в главный поток
     */
    fun studioThreads(
        view: String,
        query: String,
        offset: Int,
        limit: Int,
        completion: (Result<List<StudioThread>>) -> Unit,
    ) {
        val path =
            "/v1/kandinsky/threads?view=${Uri.encode(view)}&q=${Uri.encode(query)}&offset=$offset&limit=$limit"
        worker.post {
            if (closed) return@post
            http({
                val array = api.json(path).getJSONArray("threads")
                List(array.length()) { StudioThread.from(array.getJSONObject(it)) }
            }) { result ->
                main.post { completion(result) }
            }
        }
    }

    /**
     * Принимает подтверждённую запись открываемого чата в очереди синхронизации Если служба ещё
     * работает, передаёт запись общей процедуре объединения с местным списком
     */
    fun admitStudioThread(thread: StudioThread, completion: (Result<Unit>) -> Unit) {
        worker.post {
            if (closed) return@post
            mergeStudioThread(thread, completion)
        }
    }

    /**
     * Объединяет одну серверную сводку чата с местными данными через общую запись каталога После
     * успешного сохранения передаёт изменения экрану, а итог операции возвращает в главный поток
     */
    private fun mergeStudioThread(thread: StudioThread, completion: (Result<Unit>) -> Unit) {
        database({
            cache.mergeChats(it, JSONObject().put("chats", JSONArray().put(thread.wire())))
        }) { result ->
            result.onSuccess { emit(it) }
            main.post { completion(result.map { Unit }) }
        }
    }

    /**
     * Передаёт серверу выбранные изменения чата и разбирает подтверждённую сводку из ответа При
     * успехе объединяет её с местной записью, при отказе возвращает ошибку без применения
     * неподтверждённого изменения
     */
    fun changeStudioThread(id: Long, change: JSONObject, completion: (Result<Unit>) -> Unit) {
        worker.post {
            if (closed) return@post
            http({ StudioThread.from(api.json("/v1/kandinsky/threads/$id", change, "PATCH")) }) {
                result ->
                result.fold(
                    { mergeStudioThread(it, completion) },
                    { error -> main.post { completion(Result.failure(error)) } },
                )
            }
        }
    }

    /**
     * Сначала запрашивает удаление чата на сервере, затем применяет подтверждённое закрытие в
     * местной сделке Формирует изменения удалённых сообщений для экрана и возвращает результат
     * после местного сохранения
     */
    fun deleteStudioThread(id: Long, completion: (Result<Unit>) -> Unit) {
        worker.post {
            if (closed) return@post
            http({
                api.json("/v1/kandinsky/threads/$id", method = "DELETE")
                Unit
            }) { result ->
                result.fold(
                    {
                        database({ db ->
                            NetworkStore.transaction(db) {
                                val removed = cache.retireStudioThread(db, id)
                                if (removed == null) emptyList()
                                else
                                    cache.deltas(
                                        db,
                                        mapOf(id to emptySet()),
                                        mapOf(id to removed),
                                        MessageChangeReason.SYNC,
                                    )
                            }
                        }) { applied ->
                            applied.onSuccess { emit(it) }
                            main.post { completion(applied.map { Unit }) }
                        }
                    },
                    { error -> main.post { completion(Result.failure(error)) } },
                )
            }
        }
    }

    /**
     * Ставит пробуждение исходящей работы в последовательную очередь синхронизации Сам вызывающий
     * поток не читает базу и не отправляет сообщения напрямую
     */
    fun kick() {
        worker.post { kickInternal() }
    }

    /**
     * Сверяет счётчики непрочитанного только при активном восстановленном соединении Объединяет
     * повторные запросы во время уже идущей сверки и через короткую паузу проверяет, нужна ли
     * загрузка списка чатов Сохраняет серверные сводки в базе и отбрасывает результаты, пришедшие
     * после смены поколения подключения
     */
    private fun refreshReadSummaries() {
        if (!active || !resumed || closed) return
        if (readRefreshing) {
            readRefreshAgain = true
            return
        }
        readRefreshing = true
        val ticket = generation
        /**
         * Завершает текущую сверку непрочитанных только для того поколения соединения, которое её
         * начало Снимает признаки выполнения и запускает ещё одну сверку, если она запрашивалась в
         * ожидании и предыдущая завершилась успешно
         */
        fun finish(success: Boolean) {
            if (ticket != generation) return
            val again = readRefreshAgain && success
            readRefreshing = false
            readRefreshAgain = false
            if (again) refreshReadSummaries()
        }
        val task = Runnable {
            readRefreshTask = null
            if (ticket != generation || !active || !resumed) return@Runnable
            database({ cache.needsReadRefresh(it) }) { needed ->
                if (ticket != generation) return@database
                if (needed.getOrNull() != true) {
                    finish(needed.isSuccess)
                    return@database
                }
                http({ api.json("/v1/chats") }) { response ->
                    if (ticket != generation) return@http
                    response.fold(
                        { json ->
                            database({ cache.mergeChats(it, json) }) { applied ->
                                if (ticket == generation) {
                                    applied.onSuccess { emit(it) }
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
        worker.postDelayed(task, 100)
    }

    /**
     * При активном восстановленном соединении отмечает наличие работы в исходящей очереди Пробует
     * доставить подтверждения, обновить границу чтения и отправить следующую сохранённую команду
     */
    private fun kickInternal() {
        if (!resumed || !active) return
        pumpDirty = true
        receipts()
        readVisible()
        pump()
    }

    /**
     * Выбирает из местной очереди ограниченную порцию команд без окончательной ошибки, не допуская
     * двух одновременных отправок Для удаления подготавливает отдельное обращение, а запросы
     * объединяет с учётом числа и объёма Перед отправкой сообщений подготавливает файлы и связи
     * ответов, затем ждёт подтверждение конкретного номера запроса Временные сбои восстанавливают
     * соединение, а окончательный отказ сохраняет ошибку у соответствующих местных операций
     */
    private fun pump() {
        if (pumping || sendRequest != null || !resumed) return
        pumping = true
        pumpDirty = false
        val ticket = generation
        database({ db -> NetworkJournal.readyOperations(db) }) { result ->
            if (ticket != generation) return@database
            val commands =
                result.getOrElse {
                    retry(ticket)
                    return@database
                }
            if (commands.isEmpty()) {
                pumping = false
                if (pumpDirty) pump()
                return@database
            }

            if (commands.first().first == "delete") {
                val delete = JSONObject(commands.first().second)
                database({ db ->
                    db.rawQuery(
                            "SELECT serverID FROM net_messages WHERE clientID=? AND senderID=?",
                            arrayOf(delete.getString("targetClientId"), profile.userID.toString()),
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
                                pumping = false
                            } else {
                                sendRequest = id
                                sendIDs = listOf(id)
                                send(
                                    JSONObject()
                                        .put("type", "delete")
                                        .put("requestId", id)
                                        .put("chatId", delete.getLong("chatId"))
                                        .put("messageId", server)
                                )
                            }
                        },
                        { retry(ticket) },
                    )
                }
                return@database
            }
            val values = commands.map { ChatStorageCodec.readCommand(it.second) }
            prepare(values) { prepared ->
                if (ticket != generation) return@prepare
                prepared.fold(
                    { messages ->
                        if (messages.isEmpty()) {
                            pumping = false
                            if (pumpDirty) pump()
                            return@fold
                        }
                        val request = UUID.randomUUID().toString()
                        sendRequest = request
                        sendIDs = messages.map { it.getString("clientId") }
                        send(
                            JSONObject()
                                .put("type", "send")
                                .put("requestId", request)
                                .put("messages", JSONArray(messages))
                        )
                        worker.postDelayed({ if (sendRequest == request) retry(ticket) }, 15000)
                    },
                    { error ->
                        if (error is NetworkFailure && error.status == 401) {
                            sessionRejected(error.code)
                        } else if (
                            error is NetworkFailure &&
                                error.status in 400..499 &&
                                error.status !in listOf(408, 429)
                        ) {
                            database({
                                cache.failed(
                                    it,
                                    values.map { c -> c.operationID.lowercase() },
                                    error.code,
                                )
                            }) { result ->
                                pumping = false
                                result.onSuccess { emit(it) }
                                status("Не отправлено: " + error.code)
                                kickInternal()
                            }
                        } else {
                            pumping = false
                            retry(ticket)
                        }
                    },
                )
            }
        }
    }

    /**
     * Одним чтением базы получает уже загруженные вложения и серверные номера сообщений, на которые
     * отвечают исходящие команды В сетевой очереди передаёт недостающие файлы и собирает сообщения,
     * оставляя ответы на ещё не принятые сообщения ждать следующей попытки Записывает полученные
     * соответствия файлов в базу перед возвратом подготовленной порции Сетевое ожидание проходит
     * вне сделки базы, поэтому длительная загрузка не удерживает её занятой
     */
    private fun prepare(
        commands: List<SendCommand>,
        completion: (Result<List<JSONObject>>) -> Unit,
    ) {
        // Читаем связи загрузок и ответов за одно обращение к базе, не удерживая сделку во время
        // сетевого ожидания
        database({ db ->
            val uploads = hashMapOf<String, Pair<String, String?>>()
            db.rawQuery("SELECT attachmentID,blobID,previewBlobID FROM net_uploads", null).use {
                while (it.moveToNext()) uploads[it.getString(0)] =
                    it.getString(1) to (if (it.isNull(2)) null else it.getString(2))
            }
            val replies = hashMapOf<Long, Long>()
            for (c in commands) c.replyToID?.let { id ->
                db.rawQuery(
                        "SELECT serverID FROM net_messages WHERE localID=?",
                        arrayOf(id.toString()),
                    )
                    .use { if (it.moveToFirst() && !it.isNull(0)) replies[id] = it.getLong(0) }
            }
            uploads to replies
        }) { snapshot ->
            snapshot.fold(
                { (uploads, replies) ->
                    http({
                        commands
                            .filter { it.replyToID == null || replies.containsKey(it.replyToID) }
                            .map { command ->
                                val attachments =
                                    command.attachments.map { a ->
                                        val upload =
                                            uploads[a.id]
                                                ?: run {
                                                    val file =
                                                        File(store.root, "media/" + a.relativePath)
                                                    val result =
                                                        api.upload(
                                                            file,
                                                            a.mime,
                                                            UUID.nameUUIDFromBytes(
                                                                    (profile.deviceID + ":" + a.id)
                                                                        .toByteArray()
                                                                )
                                                                .toString(),
                                                            studioSource =
                                                                command.generationOptions != null &&
                                                                    (a.mime.startsWith("image/") ||
                                                                        a.mime == "video/mp4"),
                                                        )
                                                    result.getString("blobId") to
                                                        NetworkPreview.upload(
                                                            store.root,
                                                            a,
                                                            api,
                                                            profile.deviceID,
                                                        )
                                                }
                                        JSONObject()
                                            .put("id", a.id)
                                            .put("blobId", upload.first)
                                            .put("previewBlobId", upload.second ?: JSONObject.NULL)
                                            .put("mime", a.mime)
                                            .put("byteSize", a.byteSize)
                                            .put("rawWidth", a.rawWidth)
                                            .put("rawHeight", a.rawHeight)
                                            .put("orientation", a.orientation)
                                            .put("durationMs", a.durationMs ?: JSONObject.NULL)
                                            .put("thumbnailKey", a.thumbnailKey)
                                            .apply {
                                                a.tinyPreview?.let { put("tinyPreview", it) }
                                                a.waveform?.let {
                                                    put("waveform", org.json.JSONArray(it))
                                                }
                                            }
                                    }
                                val reply =
                                    command.replyToID?.let {
                                        replies[it] ?: error("Reply is not accepted yet")
                                    }
                                JSONObject()
                                    .put("clientId", command.operationID.lowercase())
                                    .put("chatId", command.chatID)
                                    .put("kind", command.kind.name.lowercase())
                                    .put("text", command.text)
                                    .put("caption", command.caption)
                                    .put("replyToId", reply ?: JSONObject.NULL)
                                    .put("attachments", JSONArray(attachments))
                                    .apply {
                                        command.generationOptions?.let {
                                            put(
                                                "generationOptions",
                                                ChatStorageCodec.generationOptions(it),
                                            )
                                        }
                                    }
                            }
                    }) { result ->
                        result.fold(
                            { messages ->
                                database({ db ->
                                    NetworkStore.transaction(db) {
                                        for (message in messages) {
                                            val list = message.getJSONArray("attachments")
                                            for (i in 0 until list.length()) {
                                                val a = list.getJSONObject(i)
                                                db.execSQL(
                                                    "INSERT OR REPLACE INTO net_uploads(attachmentID,blobID,previewBlobID) VALUES(?,?,?)",
                                                    arrayOf(
                                                        a.getString("id"),
                                                        a.getString("blobId"),
                                                        a.optString("previewBlobId").takeUnless {
                                                            it == "null"
                                                        },
                                                    ),
                                                )
                                            }
                                        }
                                    }
                                }) { saved ->
                                    completion(saved.map { messages })
                                }
                            },
                            { completion(Result.failure(it)) },
                        )
                    }
                },
                { completion(Result.failure(it)) },
            )
        }
    }

    /**
     * Читает текущий курсор событий, последний подтверждённый курсор и до ста двадцати восьми
     * ожидающих номеров сообщений При новых данных отправляет одно подтверждение применения и
     * сохраняет его состав до ответа сервера Пока прежнее подтверждение ожидается, вторую порцию не
     * отправляет
     */
    private fun receipts() {
        if (receipt != null) return
        database({ db ->
            val cursor = cache.cursor(db)
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
                if (receipt == null && resumed && (cursor > acked || ids.isNotEmpty())) {
                    receipt = cursor to ids
                    send(
                        JSONObject()
                            .put("type", "applied")
                            .put("cursor", cursor)
                            .put("messageIds", JSONArray(ids))
                    )
                }
            }
        }
    }

    /**
     * Запоминает видимый чат и следование концу истории, чтобы при необходимости отправить границу
     * прочитанного По местной базе проверяет, осталось ли меньше тридцати двух сообщений перед
     * видимой опорой и есть ли более ранняя серверная история При приближении к границе заранее
     * загружает страницу, объединяет её с местной базой и после сетевой ошибки выдерживает паузу
     * перед повтором
     */
    fun viewport(chat: Long, anchor: ReadingAnchor) {
        worker.post {
            currentChat = chat
            atBottom = anchor.followsBottom
            if (atBottom) readVisible()
            val localID = anchor.messageID ?: return@post
            if (chat in historyRequests || System.currentTimeMillis() < (historyRetry[chat] ?: 0))
                return@post
            historyRequests.add(chat)
            database({ db ->
                val before =
                    db.rawQuery(
                            "SELECT MIN(serverSeq) FROM net_messages n JOIN messages m ON m.id=n.localID WHERE m.chatId=? AND serverSeq>0",
                            arrayOf(chat.toString()),
                        )
                        .use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
                val count =
                    db.rawQuery(
                            "SELECT count(*) FROM messages WHERE chatId=? AND sortKey<(SELECT sortKey FROM messages WHERE id=?)",
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
                if (before == null || !active) {
                    historyRequests.remove(chat)
                    return@database
                }
                val ticket = generation
                http({ api.json("/v1/chats/$chat/history?limit=128&before=$before") }) { page ->
                    if (ticket != generation) {
                        historyRequests.remove(chat)
                        return@http
                    }
                    page.fold(
                        { json ->
                            database({ cache.history(it, chat, json) }) { applied ->
                                historyRequests.remove(chat)
                                applied.onSuccess { emit(it) }
                            }
                        },
                        {
                            historyRequests.remove(chat)
                            historyRetry[chat] = System.currentTimeMillis() + 5000
                        },
                    )
                }
            }
        }
    }

    /**
     * При смене видимого чата сбрасывает ожидаемую границу чтения Запускает сверку сохранённой
     * истории вновь открытого чата
     */
    fun visibleChat(chat: Long?) {
        worker.post {
            val changed = currentChat != chat
            if (changed) {
                pendingRead = null
            }
            currentChat = chat
            atBottom = false
            if (changed && chat != null) refreshVisibleHistory(chat)
        }
    }

    /**
     * Сверяет последние сообщения открываемого чата, объединяя обращения к одной истории При
     * восстановленном соединении повторно использует успешную сверку моложе девяноста секунд и
     * держит до тридцати двух отметок таких чатов Полученную страницу сохраняет в базе и передаёт
     * изменения экрану, а ошибку показывает только для всё ещё видимого чата
     */
    private fun refreshVisibleHistory(chat: Long) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (resumed && visibleHistoryRefreshes[chat]?.let { now - it < 90_000 } == true) return
        if (!historyRequests.add(chat)) return
        http({ api.json("/v1/chats/$chat/history?limit=128") }) { page ->
            page.fold(
                { json ->
                    database({ cache.history(it, chat, json) }) { result ->
                        historyRequests.remove(chat)
                        result.onSuccess {
                            visibleHistoryRefreshes.remove(chat)
                            visibleHistoryRefreshes[chat] = android.os.SystemClock.elapsedRealtime()
                            if (visibleHistoryRefreshes.size > 32)
                                visibleHistoryRefreshes.remove(visibleHistoryRefreshes.keys.first())
                            emit(it)
                        }
                    }
                },
                {
                    historyRequests.remove(chat)
                    if (currentChat == chat) status("История временно недоступна")
                },
            )
        }
    }

    /**
     * Для видимого чата у конца истории читает последний серверный порядок сообщения Повторно
     * проверяет видимость после чтения базы и отправляет новую положительную границу только один
     * раз до её изменения
     */
    private fun readVisible() {
        val chat = currentChat ?: return
        if (!atBottom || !resumed) return
        database({ db ->
            db.rawQuery("SELECT lastSeq FROM net_chats WHERE chatID=?", arrayOf(chat.toString()))
                .use { if (it.moveToFirst()) it.getLong(0) else 0L }
        }) { result ->
            result.onSuccess { through ->
                if (through > 0 && currentChat == chat && atBottom) {
                    if (pendingRead == (chat to through)) return@onSuccess
                    pendingRead = chat to through
                    send(
                        JSONObject().put("type", "read").put("chatId", chat).put("through", through)
                    )
                }
            }
        }
    }

    /**
     * Ищет последнее входящее сообщение выбранного чата со статусом ожидания или выполнения в
     * сохранённых дополнительных данных Использует поиск в компактной текстовой записи, доступный и
     * на устройствах без специальных средств разбора таких данных в базе Возвращает местный номер
     * найденного сообщения либо отсутствие активного задания в главный поток
     */
    fun activeGeneration(chat: Long, completion: (Result<MessageID?>) -> Unit) {
        worker.post {
            if (closed) return@post
            database({ db ->
                // Поиск в компактной записи работает и на устройствах без встроенного разбора таких
                // данных в базе
                db.rawQuery(
                        """
                SELECT m.id FROM messages m JOIN net_message_metadata n ON n.localID=m.id
                WHERE m.chatId=? AND m.direction='incoming'
                  AND (instr(n.payload, '"kandinskyStatus":"queued"')>0
                    OR instr(n.payload, '"kandinskyStatus":"running"')>0)
                ORDER BY m.sortKey DESC,m.id DESC LIMIT 1
                """
                            .trimIndent(),
                        arrayOf(chat.toString()),
                    )
                    .use { if (it.moveToFirst()) it.getLong(0) else null }
            }) { result ->
                main.post { if (!closed) completion(result) }
            }
        }
    }

    private fun generationPath(
        db: android.database.sqlite.SQLiteDatabase,
        chat: Long,
        message: MessageID,
    ): String {
        val sql = "SELECT n.serverID FROM net_messages n JOIN messages m ON m.id=n.localID WHERE n.localID=? AND m.chatId=?"
        return db.rawQuery(sql, arrayOf(message.toString(), chat.toString())).use { row ->
            if (!row.moveToFirst() || row.isNull(0)) throw RepoException(RepoError.NOT_FOUND)
            "/v1/kandinsky/threads/$chat/generations/${row.getLong(0)}"
        }
    }

    /**
     * Проверяет состояние службы и вид номера выбранного файла перед удалением содержимого
     * генерации При наличии файла удаляет результат, при отсутствии файла удаляет запрос через
     * серверный адрес местного сообщения После успешного ответа просит открытое соединение
     * перечитать чат и передаёт итог в главный поток
     */
    fun deleteGenerationContent(
        chat: Long,
        message: MessageID,
        blob: String?,
        completion: (Result<Unit>) -> Unit,
    ) {
        worker.post {
            /**
             * Передаёт итог удаления запроса или результата обработчику в главном потоке Успех и
             * ошибка проходят одинаковым путём, чтобы вызывающий экран завершил своё ожидание
             */
            fun finish(value: Result<Unit>) {
                main.post { completion(value) }
            }
            if (closed || blob != null && !blob.matches(Regex("[a-f0-9]{32}"))) {
                finish(Result.failure(RepoException(RepoError.INVALID_INPUT)))
                return@post
            }
            database({ db -> generationPath(db, chat, message) }) { target ->
                target.fold(
                    { path ->
                        val query = blob?.let { "target=result&blob=$it" } ?: "target=prompt"
                        http({ api.json("$path?$query", method = "DELETE") }) { reply ->
                            if (reply.isSuccess && resumed && !closed)
                                send(JSONObject().put("type", "kandinsky.open").put("chatId", chat))
                            finish(reply.map { Unit })
                        }
                    },
                    { finish(Result.failure(it)) },
                )
            }
        }
    }

    /**
     * Находит серверный адрес выбранного результата и передаёт причину жалобы от владельца При
     * закрытой службе возвращает отмену, а итог сетевого обращения направляет в главный поток
     */
    fun reportGeneration(
        chat: Long,
        message: MessageID,
        reason: StudioReportReason,
        completion: (Result<Unit>) -> Unit,
    ) {
        worker.post {
            /**
             * Переносит результат отправки жалобы из очереди синхронизации в главный поток Вызывает
             * переданное завершение с тем же успехом или ошибкой без изменения данных
             */
            fun finish(result: Result<Unit>) {
                main.post { completion(result) }
            }
            if (closed) {
                finish(Result.failure(RepoException(RepoError.CANCELLED)))
                return@post
            }
            database({ db -> generationPath(db, chat, message) }) { target ->
                target.fold(
                    { path ->
                        http({
                            api.json("$path/report", JSONObject().put("reason", reason.wire))
                        }) {
                            finish(it.map { Unit })
                        }
                    },
                    { finish(Result.failure(it)) },
                )
            }
        }
    }

    /**
     * Получает серверный адрес генерации и передаёт проверку, отмену или повтор с номером операции
     * Для отдельной ячейки добавляет её номер и ожидаемую попытку, чтобы запоздалое действие не
     * повторило уже новое выполнение Ответные сообщения сохраняет в местной базе до уведомления
     * экрана и завершения действия
     */
    fun generationAction(
        chat: Long,
        message: MessageID,
        action: StudioGenerationAction,
        operation: UUID?,
        slot: StudioGeneration.Slot? = null,
        completion: (Result<Unit>) -> Unit,
    ) {
        worker.post {
            // обслуживает отказ, ошибку записи и успешное применение полученных сообщений
            fun finish(result: Result<Unit>) {
                main.post { completion(result) }
            }
            if (closed) {
                finish(Result.failure(RepoException(RepoError.CANCELLED)))
                return@post
            }
            val retryKey = if (action == StudioGenerationAction.RETRY && slot == null) "generationRetry:$chat:$message" else null
            database({ db ->
                val path = generationPath(db, chat, message)
                val operationID = retryKey?.let { cache.pendingRetry(db, it, operation) } ?: operation?.toString()
                path to operationID
            }) { target ->
                target.fold(
                    { (path, operationID) ->
                        http({
                            api.json(
                                "$path/action",
                                JSONObject()
                                    .put(
                                        "action",
                                        if (slot == null)
                                            action.name.lowercase(java.util.Locale.ROOT)
                                        else "retry_slot",
                                    )
                                    .put("operation", operationID)
                                    .put("slot", slot?.id)
                                    .put("expected_run", slot?.runId),
                            )
                        }) { reply ->
                            reply.fold(
                                { json ->
                                    database({ cache.generationRows(it, chat, json, retryKey, operationID) }) { applied ->
                                        applied.onSuccess(::emit)
                                        finish(applied.map { Unit })
                                    }
                                },
                                { finish(Result.failure(it)) },
                            )
                        }
                    },
                    { finish(Result.failure(it)) },
                )
            }
        }
    }

    /**
     * Преобразует команду в текст и проверяет предельный размер перед передачей постоянному
     * соединению При слишком большом сообщении или отказе отправки запускает восстановление
     * текущего подключения
     */
    private fun send(value: JSONObject) {
        val text = value.toString()
        if (text.toByteArray().size > 65536 || socket?.send(text) != true) retry(generation)
    }

    /**
     * Передаёт серверу подтверждённое удаление аккаунта с введённым паролем Повторный ответ об уже
     * удалённом аккаунте принимает как достигнутый результат удаления Успех или оставшуюся ошибку
     * возвращает в главный поток для продолжения местной очистки
     */
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

    /**
     * Останавливает активную синхронизацию и закрывает соединение после отказа ключа входа При
     * удалённом аккаунте вызывает обработчик местной очистки, иначе сообщает экрану о необходимости
     * нового подключения
     */
    private fun sessionRejected(code: String) {
        if (rejectedSession) return
        rejectedSession = true
        active = false
        disconnect()
        main.post {
            if (code == "account_deleted") onAccountErased?.invoke() else onSessionExpired?.invoke()
        }
    }

    // После переподключения очередь дочитает из SQLite команды без подтверждения.
    private fun retry(ticket: Int) {
        if (ticket != generation || closed || !active) return
        disconnect()
        status("Нет связи · сообщения сохранены")
        val next = generation
        val delay = minOf(30000L, 500L * (1L shl minOf(attempt++, 6))) + Random.nextLong(250)
        worker.postDelayed({ if (next == generation && active) connect() }, delay)
    }

    /**
     * Снимает таймеры восстановления и просмотров, закрывает постоянное соединение и меняет
     * поколение для отсечения старых ответов Сбрасывает временные очереди событий, ожидания
     * отправок, счётчики присутствия и действий, сохраняя постоянные операции в базе Обновляет
     * сведения для экрана после прекращения подключения
     */
    private fun disconnect() {
        resumeTimeout?.let { worker.removeCallbacks(it) }
        resumeTimeout = null
        contentTask?.let { worker.removeCallbacks(it) }
        contentTask = null
        clearContentFlight()
        contentSelecting = false
        contentDirty = false
        contentAcks = false
        eventQueue.clear()
        applyingEvents = false
        generation++
        resumed = false
        socket?.cancel()
        socket = null
        receipt = null
        sendRequest = null
        sendIDs = emptyList()
        pumping = false
        pumpDirty = false
        pendingRead = null
        readRefreshTask?.let { worker.removeCallbacks(it) }
        readRefreshTask = null
        readRefreshing = false
        readRefreshAgain = false
    }

    /**
     * Передаёт изменения сообщений из рабочей очереди в главный поток Там отдаёт их накопителю
     * экранных обновлений, который объединяет близкие изменения перед уведомлением представления
     */
    private fun emit(deltas: List<MessageDelta>) {
        main.post { ui.offer(deltas) }
    }

    /**
     * Выполняет переданное действие через последовательную очередь владельца местной базы Результат
     * возвращает в очередь синхронизации и вызывает продолжение только пока служба ещё не закрыта
     */
    private fun <T> database(
        action: (android.database.sqlite.SQLiteDatabase) -> T,
        completion: (Result<T>) -> Unit,
    ) {
        store.submit(action) { result -> worker.post { if (!closed) completion(result) } }
    }

    /**
     * Запускает сетевую работу в отдельной ограниченной очереди и собирает успех или исключение в
     * единый результат Возвращает результат в очередь синхронизации, а отказ принять новую работу
     * сообщает через то же завершение
     */
    private fun <T> http(action: () -> T, completion: (Result<T>) -> Unit) {
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

    /**
     * Снимает обработчики экрана и закрывает накопитель изменений, чтобы завершённая служба больше
     * не обновляла представления В рабочей очереди отмечает завершение, прекращает соединение,
     * останавливает сетевые задания и закрывает сетевой клиент Просит поток синхронизации закончить
     * оставшуюся допустимую работу и выйти
     */
    fun close() {
        onStudioMediaEvicted = null
        ui.close()
        onDeltas = null
        onStatus = null
        onAccountErased = null
        onSessionExpired = null
        onStudioRecentChanged = null
        worker.post {
            closed = true
            active = false
            disconnect()
            io.shutdownNow()
            api.close()
            thread.quitSafely()
        }
    }
}
