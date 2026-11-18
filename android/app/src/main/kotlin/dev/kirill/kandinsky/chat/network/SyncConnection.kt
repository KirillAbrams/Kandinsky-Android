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


    // У каждого подключения свой номер. По нему отбрасываем поздние ответы от уже закрытого
    // соединения.
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

    // Ограничиваем размер пакета и очередь, если база не успевает за сетью.
    private fun openSocket(connectionID: Int) {
        if (connectionID != connectionEpoch || !sync.active) return
        val deadline = Runnable { if (!resumed) reconnectAfterFailure(connectionID) }
        resumeTimeout = deadline
        sync.worker.postDelayed(deadline, 15000)
        socket =
            sync.api.socket(
                object : WebSocketListener() {
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
                                                JSONObject().put("type", "resume").put("cursor", it)
                                            )
                                        },
                                        { reconnectAfterFailure(connectionID) },
                                    )
                                }
                            }
                        }
                    }

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

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        sync.worker.post { reconnectAfterFailure(connectionID) }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                        sync.worker.post { reconnectAfterFailure(connectionID) }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        sync.worker.post { reconnectAfterFailure(connectionID) }
                    }
                }
            )
    }

    fun connectionResumed() {
        sync.attachments.drainRemovedStudioMedia()
        resumeTimeout?.let { sync.worker.removeCallbacks(it) }
        resumeTimeout = null
        resumed = true
        reconnectAttempts = 0
        sync.publishStatus("В сети")
        sync.flushPendingWork()
        sync.history.refreshCurrentChat()
    }

    // После переподключения очередь дочитает из SQLite команды без подтверждения.
    fun reconnectAfterFailure(connectionID: Int) {
        if (connectionID != connectionEpoch || sync.closed || !sync.active) return
        disconnect()
        sync.publishStatus("Нет связи · сообщения сохранены")
        val next = connectionEpoch
        val delay =
            minOf(30000L, 500L * (1L shl minOf(reconnectAttempts++, 6))) + Random.nextLong(250)
        sync.worker.postDelayed({ if (next == connectionEpoch && sync.active) connect() }, delay)
    }

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

    fun send(value: JSONObject) {
        val text = value.toString()
        if (text.toByteArray().size > 65536 || socket?.send(text) != true)
            reconnectAfterFailure(connectionEpoch)
    }
}
