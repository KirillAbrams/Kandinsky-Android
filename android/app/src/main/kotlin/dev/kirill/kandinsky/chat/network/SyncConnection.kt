// Открывает постоянное соединение, восстанавливает его после разрыва и следит за размером очереди
// ответов. Номер соединения защищает от поздних ответов старого подключения.

package dev.kirill.kandinsky.chat.network

import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

internal class SyncConnection(private val sync: NetworkSync) {
    private val pendingFrames = AtomicInteger()
    private var socket: WebSocket? = null
    private var resumeTimeout: Runnable? = null
    @Volatile
    var resumed = false
        private set

    var connectionEpoch = 0
        private set

    private var reconnectAttempts = 0

    // Проверяет, что обмен включён и соединение свободно. Читает состояние базы, получает список
    // чатов или первый снимок, сохраняет его и открывает постоянное соединение. Просроченный вход
    // передаёт отдельной обработке.
    fun connect() {
        if (!sync.active || sync.closed || socket != null) return
        connectionEpoch++
        val connectionID = connectionEpoch
        sync.publishStatus("Подключение…")
        sync.database({ sync.cache.initialized(it) }) { ready ->
            if (connectionID != connectionEpoch || !sync.active) return@database
            if (ready.isFailure) {
                reconnectAfterFailure(connectionID)
                return@database
            }
            sync.http({
                sync.api.initialize()
                sync.api.json(if (ready.getOrThrow()) "/v1/chats" else "/v1/bootstrap")
            }) { result ->
                if (connectionID != connectionEpoch || !sync.active) return@http
                result.fold(
                    { bootstrap ->
                        sync.database({
                            if (ready.getOrThrow())
                                sync.cache.mergeChats(it, bootstrap, studioRecent = true)
                            else sync.cache.bootstrap(it, bootstrap)
                        }) { applied ->
                            if (connectionID == connectionEpoch) {
                                applied.fold(
                                    {
                                        sync.emit(it)
                                        sync.main.post { sync.onStudioRecentChanged?.invoke() }
                                        openSocket(connectionID)
                                    },
                                    { reconnectAfterFailure(connectionID) },
                                )
                            }
                        }
                    },
                    { error ->
                        if (error is NetworkFailure && error.status == 401)
                            sync.sessionRejected(error.code)
                        else reconnectAfterFailure(connectionID)
                    },
                )
            }
        }
    }

    // Открывает постоянное соединение для текущего номера и ждёт подтверждения возобновления с
    // пределом по времени. Без предела зависшее подключение мешало бы новой попытке.
    private fun openSocket(connectionID: Int) {
        if (connectionID != connectionEpoch || !sync.active) return
        val deadline = Runnable { if (!resumed) reconnectAfterFailure(connectionID) }
        resumeTimeout = deadline
        sync.worker.postDelayed(deadline, 15000)
        socket =
            sync.api.socket(
                object : WebSocketListener() {
                    // Закрывает опоздавшее соединение или читает последний сохранённый номер и
                    // просит продолжить с него. Одновременно запрашивает уведомления о списке и
                    // ходе загрузок.
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        sync.worker.post {
                            if (connectionID != connectionEpoch) {
                                webSocket.cancel()
                                return@post
                            }
                            sync.database({ sync.cache.cursor(it) }) { result ->
                                if (connectionID == connectionEpoch) {
                                    result.fold(
                                        {
                                            send(
                                                JSONObject()
                                                    .put("type", "resume")
                                                    .put("cursor", it)
                                                    .put(
                                                        "features",
                                                        JSONObject()
                                                            .put("chatDirectory", true)
                                                            .put("uploadProgress", true),
                                                    )
                                            )
                                        },
                                        { reconnectAfterFailure(connectionID) },
                                    )
                                }
                            }
                        }
                    }

                    // Отклоняет слишком большой ответ или переполненную очередь и передаёт пакет
                    // в обработку. Освобождает место после разбора даже при ошибке, иначе очередь
                    // могла бы остаться переполненной.
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
                        sync.worker.post {
                            try {
                                if (connectionID == connectionEpoch)
                                    sync.incomingEvents.receive(JSONObject(text))
                            } catch (_: Exception) {
                                reconnectAfterFailure(connectionID)
                            } finally {
                                pendingFrames.decrementAndGet()
                            }
                        }
                    }

                    // Передаёт отказ в очередь работы соединения вместе с его номером. Старый
                    // отказ не должен закрывать уже восстановленную связь.
                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        sync.worker.post { reconnectAfterFailure(connectionID) }
                    }

                    // Подтверждает закрытие и ставит восстановление в очередь. Так обмен может
                    // продолжиться с сохранённой позиции, если приложение ещё активно.
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                        sync.worker.post { reconnectAfterFailure(connectionID) }
                    }

                    // Сообщает об окончательном закрытии с номером соединения. Повторная
                    // обработка уже закрытого номера не запускает лишнее подключение.
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        sync.worker.post { reconnectAfterFailure(connectionID) }
                    }
                }
            )
    }

    // Останавливает ожидание подтверждения и сбрасывает задержку повтора. Продолжает очистку
    // файлов, отправку очереди и обновление открытого чата и списка.
    fun connectionResumed() {
        sync.attachments.drainRemovedStudioMedia()
        resumeTimeout?.let { sync.worker.removeCallbacks(it) }
        resumeTimeout = null
        resumed = true
        reconnectAttempts = 0
        sync.publishStatus("В сети")
        sync.flushPendingWork()
        sync.history.refreshCurrentChat()
        sync.refreshStudioDirectory()
    }

    // Разрывает только текущее активное подключение и назначает новую попытку с растущей
    // задержкой. Сохранённые команды останутся доступны для отправки после восстановления.
    fun reconnectAfterFailure(connectionID: Int) {
        if (connectionID != connectionEpoch || sync.closed || !sync.active) return
        disconnect()
        sync.publishStatus("Нет связи · сообщения сохранены")
        val next = connectionEpoch
        val delay =
            minOf(30000L, 500L * (1L shl minOf(reconnectAttempts++, 6))) + Random.nextLong(250)
        sync.worker.postDelayed({ if (next == connectionEpoch && sync.active) connect() }, delay)
    }

    // Снимает ожидание, сбрасывает состояние обмена, увеличивает номер подключения и закрывает
    // сеть. Местная очередь сбрасывает текущую отправку, чтобы продолжить неподтверждённое позже.
    fun disconnect() {
        resumeTimeout?.let { sync.worker.removeCallbacks(it) }
        resumeTimeout = null
        sync.cursorAcknowledgement.reset()
        sync.incomingEvents.reset()
        connectionEpoch++
        resumed = false
        socket?.cancel()
        socket = null
        sync.outbox.reset()
    }

    // Переводит пакет в текст и следит за размером и приёмом соединением. При отказе открывает
    // путь восстановления, иначе запрос мог бы остаться в ожидании ответа.
    fun send(value: JSONObject) {
        val text = value.toString()
        if (text.toByteArray().size > 65536 || socket?.send(text) != true)
            reconnectAfterFailure(connectionEpoch)
    }
}
