package dev.kirill.kandinsky.chat.network

import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.storage.*
import java.util.UUID
import org.json.JSONObject

internal class SyncGenerationActions(private val sync: NetworkSync) {
    fun activeGeneration(chat: Long, completion: (Result<MessageID?>) -> Unit) {
        sync.worker.post {
            if (sync.closed) return@post
            sync.database({ db ->
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
                sync.main.post { if (!sync.closed) completion(result) }
            }
        }
    }

    private fun generationPath(
        db: android.database.sqlite.SQLiteDatabase,
        chat: Long,
        message: MessageID,
    ): String {
        val sql =
            "SELECT n.serverID FROM net_messages n JOIN messages m ON m.id=n.localID WHERE n.localID=? AND m.chatId=?"
        return db.rawQuery(sql, arrayOf(message.toString(), chat.toString())).use { row ->
            if (!row.moveToFirst() || row.isNull(0)) throw RepoException(RepoError.NOT_FOUND)
            "/v1/kandinsky/threads/$chat/generations/${row.getLong(0)}"
        }
    }

    fun deleteGenerationContent(
        chat: Long,
        message: MessageID,
        blob: String?,
        completion: (Result<Unit>) -> Unit,
    ) {
        sync.worker.post {
            fun finish(value: Result<Unit>) {
                sync.main.post { completion(value) }
            }
            if (sync.closed || blob != null && !blob.matches(Regex("[a-f0-9]{32}"))) {
                finish(Result.failure(RepoException(RepoError.INVALID_INPUT)))
                return@post
            }
            sync.database({ db -> generationPath(db, chat, message) }) { target ->
                target.fold(
                    { path ->
                        val query = blob?.let { "target=result&blob=$it" } ?: "target=prompt"
                        sync.http({ sync.api.json("$path?$query", method = "DELETE") }) { reply ->
                            if (reply.isSuccess && sync.resumed && !sync.closed)
                                sync.send(
                                    JSONObject().put("type", "kandinsky.open").put("chatId", chat)
                                )
                            finish(reply.map { Unit })
                        }
                    },
                    { finish(Result.failure(it)) },
                )
            }
        }
    }

    fun reportGeneration(
        chat: Long,
        message: MessageID,
        reason: StudioReportReason,
        completion: (Result<Unit>) -> Unit,
    ) {
        sync.worker.post {
            fun finish(result: Result<Unit>) {
                sync.main.post { completion(result) }
            }
            if (sync.closed) {
                finish(Result.failure(RepoException(RepoError.CANCELLED)))
                return@post
            }
            sync.database({ db -> generationPath(db, chat, message) }) { target ->
                target.fold(
                    { path ->
                        sync.http({
                            sync.api.json("$path/report", JSONObject().put("reason", reason.wire))
                        }) {
                            finish(it.map { Unit })
                        }
                    },
                    { finish(Result.failure(it)) },
                )
            }
        }
    }

    fun generationAction(
        chat: Long,
        message: MessageID,
        action: StudioGenerationAction,
        operation: UUID?,
        slot: StudioGeneration.Slot? = null,
        completion: (Result<Unit>) -> Unit,
    ) {
        sync.worker.post {
            fun finish(result: Result<Unit>) {
                sync.main.post { completion(result) }
            }
            if (sync.closed) {
                finish(Result.failure(RepoException(RepoError.CANCELLED)))
                return@post
            }
            val retryKey =
                if (action == StudioGenerationAction.RETRY && slot == null)
                    "generationRetry:$chat:$message"
                else null
            sync.database({ db ->
                val path = generationPath(db, chat, message)
                // После потери ответа берём тот же номер попытки из базы телефона.
                val operationID =
                    retryKey?.let { sync.cache.pendingRetry(db, it, operation) }
                        ?: operation?.toString()
                path to operationID
            }) { target ->
                val (path, operationID) = target.getOrElse {
                    finish(Result.failure(it))
                    return@database
                }
                sync.http({
                    val body =
                        JSONObject()
                            .put(
                                "action",
                                if (slot == null) action.name.lowercase(java.util.Locale.ROOT)
                                else "retry_slot",
                            )
                            .put("operation", operationID)
                            .put("slot", slot?.id)
                            .put("expected_run", slot?.runId)
                    sync.api.json("$path/action", body)
                }) { reply ->
                    val response = reply.getOrElse {
                        finish(Result.failure(it))
                        return@http
                    }
                    sync.database({ db ->
                        sync.cache.generationRows(db, chat, response, retryKey, operationID)
                    }) { changes ->
                        changes.onSuccess(sync::emit)
                        finish(changes.map { Unit })
                    }
                }
            }
        }
    }
}
