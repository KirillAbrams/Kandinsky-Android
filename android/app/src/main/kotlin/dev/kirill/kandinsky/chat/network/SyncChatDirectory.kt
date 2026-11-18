package dev.kirill.kandinsky.chat.network

import android.net.Uri
import dev.kirill.kandinsky.chat.domain.*
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal class SyncChatDirectory(private val sync: NetworkSync) {
    private var refreshing = false
    private var refreshPending = false

    fun refreshStudioDirectory() {
        refreshPending = true
        if (refreshing || sync.closed || !sync.active) return
        refreshPending = false
        refreshing = true
        val connection = sync.connectionEpoch
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

    fun openDirect(username: String, threadID: UUID? = null, completion: (Result<Long>) -> Unit) {
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
                    JSONObject().put("username", username).put("id", threadID?.toString()),
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

    fun cachedStudioRecent(completion: (Result<StudioRecentPage>) -> Unit) =
        sync.store.submit(sync.cache::studioRecent, completion)

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

    fun admitStudioThread(thread: StudioThread, completion: (Result<Unit>) -> Unit) {
        sync.worker.post {
            if (sync.closed) return@post
            mergeStudioThread(thread, completion)
        }
    }

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
