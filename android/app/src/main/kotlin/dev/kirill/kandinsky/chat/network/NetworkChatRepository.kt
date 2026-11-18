package dev.kirill.kandinsky.chat.network

import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.storage.*

class NetworkChatRepository(
    internal val local: SQLiteChatRepository,
    internal val sync: NetworkSync,
) : ChatRepository by local {
    // Будим очередь после записи в SQLite. Тогда отправка переживёт потерю связи.
    override fun send(command: SendCommand, callback: (Result<MessageDelta>) -> Unit) =
        local.send(command) { result ->
            if (result.isSuccess) sync.kick()
            callback(result)
        }

    override fun delete(messageID: MessageID, callback: (Result<MessageDelta>) -> Unit) =
        local.delete(messageID) { result ->
            if (result.isSuccess) sync.kick()
            callback(result)
        }

    override fun chats(matching: String, callback: (Result<List<ChatSummary>>) -> Unit) =
        local.chats(matching) { result ->
            result.fold(
                { rows ->
                    local.store.submit(
                        { db ->
                            rows.map { ChatServerState.decorate(db, it) }.filter { !it.hidden }
                        },
                        callback,
                    )
                },
                { callback(Result.failure(it)) },
            )
        }

    override fun markRead(chatID: ChatID, callback: (Result<Unit>) -> Unit) =
        local.markRead(chatID, callback)
}
