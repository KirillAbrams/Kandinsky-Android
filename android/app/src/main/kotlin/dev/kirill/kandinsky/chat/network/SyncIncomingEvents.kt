// Принимает события канала и передаёт их обработчикам, удерживая очередь порций. Порядок важен,
// чтобы отметка не перескочила через ещё не записанные сообщения.

package dev.kirill.kandinsky.chat.network

import org.json.JSONObject

internal class SyncIncomingEvents(private val sync: NetworkSync) {
    private val eventQueue = java.util.ArrayDeque<JSONObject>()
    private var applyingEvents = false

    // Выбирает действие по виду входящего сообщения. Порции истории кладёт в ограниченную
    // очередь, а подтверждение или отказ передаёт обработчику своей команды.
    fun receive(value: JSONObject) {
        when (value.getString("type")) {
            "kandinsky.catalog.changed" -> sync.refreshStudioDirectory()
            "kandinsky.uploads" -> sync.attachments.acceptStudioTransfers(value)
            "resumed" -> sync.connectionResumed()
            "events" -> {
                check(eventQueue.size < 64)
                eventQueue.addLast(value)
                drainEvents()
            }
            "accepted" -> sync.outbox.accepted(value)
            "applied" -> sync.cursorAcknowledgement.confirmSavedCursor(value)
            "deleted" -> sync.outbox.deleted(value)
            "error" -> handleError(value)
        }
    }

    // Проверяет отзыв аккаунта, возможность повтора и совпадение с текущей отправкой. Такой выбор
    // не превращает временный отказ всей связи в ошибку отдельного сообщения.
    private fun handleError(value: JSONObject) {
        val code = value.getString("code")
        val request = value.optString("requestId")
        when {
            code == "account_deleted" || code == "invalid_session" -> sync.sessionRejected(code)
            value.optBoolean("retryable", false) -> sync.reconnectAfterFailure(sync.connectionEpoch)
            sync.outbox.matchesRequest(request) -> sync.outbox.reject(code)
            else -> sync.reconnectAfterFailure(sync.connectionEpoch)
        }
    }

    // Очищает очередь и признак её записи при смене соединения. Новый канал не должен ждать
    // порции, оставшиеся от старого.
    fun reset() {
        eventQueue.clear()
        applyingEvents = false
    }

    // Берёт только первую порцию и перед записью проверяет наличие карточек. Сверяет номер
    // канала, чтобы результаты параллельного чтения не применились после переподключения в чужой
    // последовательности.
    private fun drainEvents() {
        if (applyingEvents || eventQueue.isEmpty()) return
        applyingEvents = true
        val value = eventQueue.first
        val connectionID = sync.connectionEpoch
        var fetchedUnknownChats = false
        // Записывает порцию и передаёт её изменения экрану только для того же канала. После
        // успеха убирает первую порцию и запускает следующую, иначе отметка могла бы быть
        // сдвинута раньше записи.
        fun apply() {
            sync.database({ db -> sync.cache.directoryEvents(db, value) }) { result ->
                if (connectionID == sync.connectionEpoch)
                    result.fold(
                        { (changes, directoryChanged) ->
                            sync.emit(changes)
                            if (directoryChanged || fetchedUnknownChats)
                                sync.onStudioRecentChanged?.invoke()
                            sync.attachments.drainRemovedStudioMedia()
                            eventQueue.removeFirst()
                            applyingEvents = false
                            sync.flushPendingWork()
                            drainEvents()
                        },
                        { sync.reconnectAfterFailure(connectionID) },
                    )
            }
        }
        sync.database({ sync.cache.hasUnknownChats(it, value) }) { unknown ->
            if (connectionID == sync.connectionEpoch)
                unknown.fold(
                    { missing ->
                        fetchedUnknownChats = missing
                        if (!missing) apply()
                        else
                            sync.http({ sync.api.json("/v1/chats") }) { rows ->
                                if (connectionID == sync.connectionEpoch)
                                    rows.fold(
                                        { json ->
                                            sync.database({ sync.cache.mergeChats(it, json) }) {
                                                merged ->
                                                if (connectionID == sync.connectionEpoch)
                                                    merged.fold(
                                                        {
                                                            sync.emit(it)
                                                            apply()
                                                        },
                                                        { sync.reconnectAfterFailure(connectionID) },
                                                    )
                                            }
                                        },
                                        { sync.reconnectAfterFailure(connectionID) },
                                    )
                            }
                    },
                    { sync.reconnectAfterFailure(connectionID) },
                )
        }
    }
}
