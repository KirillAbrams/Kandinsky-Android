// Дополняет местное хранилище связью с очередью отправки. Чтение истории остаётся местным, а
// удачная запись команды будит сетевой обмен.

package dev.kirill.kandinsky.chat.network

import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.storage.*

class NetworkChatRepository(
    internal val local: SQLiteChatRepository,
    internal val sync: NetworkSync,
) : ChatRepository by local {
    // Записывает команду местно и будит обмен только после успеха. При отказе записи очередь не
    // получает несохранённую отправку.
    override fun send(command: SendCommand, callback: (Result<MessageDelta>) -> Unit) =
        local.send(command) { result ->
            if (result.isSuccess) sync.kick()
            callback(result)
        }

    // Убирает сообщение местным путём и после успешной записи будит обмен. Результат возвращается
    // экрану независимо от того, доступна ли сеть.
    override fun delete(messageID: MessageID, callback: (Result<MessageDelta>) -> Unit) =
        local.delete(messageID) { result ->
            if (result.isSuccess) sync.kick()
            callback(result)
        }

    // Читает совпавшие карточки и добавляет сохранённые признаки сервера в очереди базы. Затем
    // отсеивает скрытые, чтобы их не показал местный поиск.
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

    // Сохраняет прочитанную отметку обычным способом базы. Экран получает итог записи без
    // отдельного сетевого ожидания.
    override fun markRead(chatID: ChatID, callback: (Result<Unit>) -> Unit) =
        local.markRead(chatID, callback)
}
