// Обновляет список чатов, соединяет сетевые ответы с местной базой и уведомляет экран. Проверяет
// номер соединения, чтобы старые ответы не применялись после восстановления связи.

package dev.kirill.kandinsky.chat.network

import android.net.Uri
import dev.kirill.kandinsky.chat.domain.*
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal class SyncChatDirectory(private val sync: NetworkSync) {
    private var refreshing = false
    private var refreshPending = false

    // Объединяет одновременные запросы обновления и запоминает известные номера и границу
    // событий. При ошибке чтения просит восстановить соединение, чтобы следующий проход начинался
    // с верного состояния.
    fun refreshStudioDirectory() {
        refreshPending = true
        if (refreshing || sync.closed || !sync.active) return
        refreshPending = false
        refreshing = true
        val connection = sync.connectionEpoch
        // Снимает признак текущей загрузки списка и запускает новый проход, если за это время
        // пришёл запрос. Иначе последнее обновление могло бы потеряться.
        fun complete() {
            refreshing = false
            if (refreshPending) refreshStudioDirectory()
        }
        sync.database({ sync.cache.studioChatIDs(it) to sync.cache.cursor(it) }) { known ->
            if (sync.closed || connection != sync.connectionEpoch) {
                complete()
                return@database
            }
            known.fold(
                { (ids, cursor) -> loadDirectory(ids, connection, cursor, ::complete) },
                {
                    complete()
                    sync.reconnectAfterFailure(connection)
                },
            )
        }
    }

    // Читает недавние чаты и отдельно проверяет известные номера, включая архивные. Передаёт
    // снимок в базу только для действующего соединения и при отсутствии более нового запроса.
    private fun loadDirectory(
        known: List<Long>, connection: Int, startedAtCursor: Long, complete: () -> Unit,
    ) {
        sync.http({
            val recent = sync.api.json("/v1/chats")
            // Архивированный чат отсутствует в недавних. Сверяем его флаги по сохранённому номеру.
            val states =
                known.chunked(128).map { ids ->
                    sync.api.json("/v1/chats/states", JSONObject().put("ids", JSONArray(ids)))
                }
            recent to states
        }) { result ->
            if (sync.closed || connection != sync.connectionEpoch) {
                complete()
                return@http
            }
            result.fold(
                { (recent, states) ->
                    if (refreshPending) {
                        complete()
                    } else {
                        sync.database({ db ->
                            sync.cache.mergeStudioDirectorySnapshot(
                                db, recent, states, startedAtCursor)
                        }) { applied ->
                            if (!sync.closed && connection == sync.connectionEpoch) {
                                applied.fold(
                                    { changes ->
                                        sync.emit(changes)
                                        sync.main.post { sync.onStudioRecentChanged?.invoke() }
                                    },
                                    { sync.reconnectAfterFailure(connection) },
                                )
                            }
                            complete()
                        }
                    }
                },
                {
                    complete()
                    sync.reconnectAfterFailure(connection)
                },
            )
        }
    }

    // Отправляет постоянный номер черновика и проверяет, что сервер вернул карточку с номером
    // чата. Сохраняет её и передаёт результат в поток экрана после записи, чтобы чат можно было
    // сразу открыть.
    fun createStudioThread(threadID: UUID, completion: (Result<Long>) -> Unit) {
        sync.worker.post {
            if (sync.closed) {
                sync.main.post {
                    completion(Result.failure(IllegalStateException("Session closed")))
                }
                return@post
            }
            sync.http({
                sync.api.json(
                    "/v1/demo/chats",
                    JSONObject().put("id", threadID.toString()),
                )
            }) { result ->
                result.fold(
                    { response ->
                        val chat = response.optJSONObject("chat")
                        val id = chat?.optLong("id", 0L) ?: 0L
                        if (chat == null || id <= 0)
                            sync.main.post {
                                completion(
                                    Result.failure(IllegalStateException("Неверный ответ истории"))
                                )
                            }
                        else
                            sync.database({
                                sync.cache.mergeChats(
                                    it,
                                    JSONObject().put("chats", JSONArray().put(chat)),
                                )
                            }) { merged ->
                                merged.onSuccess { sync.emit(it) }
                                sync.main.post { completion(merged.map { id }) }
                            }
                    },
                    { sync.main.post { completion(Result.failure(it)) } },
                )
            }
        }
    }

    // Читает последнюю сохранённую страницу вне потока экрана. Телефон может показать прежний
    // список, пока отвечает сервер.
    fun cachedStudioRecent(completion: (Result<StudioRecentPage>) -> Unit) =
        sync.store.submit(sync.cache::studioRecent, completion)

    // Кодирует раздел и поисковую строку и запрашивает одну порцию. Возвращает карточки в поток
    // экрана, чтобы чтение сети не прерывало прокрутку.
    fun studioThreads(
        view: String,
        query: String,
        offset: Int,
        limit: Int,
        completion: (Result<List<StudioThread>>) -> Unit,
    ) {
        val path =
            "/v1/kandinsky/threads?view=${Uri.encode(view)}&q=${Uri.encode(query)}&offset=$offset&limit=$limit"
        sync.worker.post {
            if (sync.closed) return@post
            sync.http({
                val array = sync.api.json(path).getJSONArray("threads")
                List(array.length()) { StudioThread.from(array.getJSONObject(it)) }
            }) { result ->
                sync.main.post { completion(result) }
            }
        }
    }

    // Принимает карточку из поиска или списка и передаёт её в общую запись. При закрытом сеансе
    // работа не меняет экран прежнего владельца.
    fun admitStudioThread(thread: StudioThread, completion: (Result<Unit>) -> Unit) {
        sync.worker.post {
            if (sync.closed) return@post
            mergeStudioThread(thread, completion)
        }
    }

    // Сохраняет карточку вместе с прежними служебными отметками списка. При успешной записи
    // отправляет экрану изменения, а ошибка возвращается вызывающему коду.
    private fun mergeStudioThread(thread: StudioThread, completion: (Result<Unit>) -> Unit) {
        sync.database({
            sync.cache.mergeChats(
                it, JSONObject().put("chats", JSONArray().put(thread.wire())),
                preserveDirectoryMetadata = true,
            )
        }) { result ->
            result.onSuccess { sync.emit(it) }
            sync.main.post { completion(result.map { Unit }) }
        }
    }

    // Отправляет изменение выбранного чата и записывает ответ общей функцией обновления карточки.
    // Если сервер отказал, передаёт ошибку и не показывает изменение как выполненное.
    fun changeStudioThread(id: Long, change: JSONObject, completion: (Result<Unit>) -> Unit) {
        sync.worker.post {
            if (sync.closed) return@post
            sync.http({
                StudioThread.from(sync.api.json("/v1/kandinsky/threads/$id", change, "PATCH"))
            }) { result ->
                result.fold(
                    { mergeStudioThread(it, completion) },
                    { error -> sync.main.post { completion(Result.failure(error)) } },
                )
            }
        }
    }

    // После подтверждения сервером убирает местный чат и собирает удалённые строки одной записью.
    // Событие после записи помогает экрану не показать прежние сообщения при возврате.
    fun deleteStudioThread(id: Long, completion: (Result<Unit>) -> Unit) {
        sync.worker.post {
            if (sync.closed) return@post
            sync.http({
                sync.api.json("/v1/kandinsky/threads/$id", method = "DELETE")
                Unit
            }) { result ->
                result.fold(
                    {
                        sync.database({ db ->
                            NetworkStore.transaction(db) {
                                val removed = sync.cache.retireStudioThread(db, id)
                                if (removed == null) emptyList()
                                else
                                    sync.cache.deltas(
                                        db,
                                        mapOf(id to emptySet()),
                                        mapOf(id to removed),
                                        MessageChangeReason.SYNC,
                                    )
                            }
                        }) { applied ->
                            applied.onSuccess { sync.emit(it) }
                            sync.main.post { completion(applied.map { Unit }) }
                        }
                    },
                    { error -> sync.main.post { completion(Result.failure(error)) } },
                )
            }
        }
    }
}
