package dev.kirill.kandinsky.chat.storage

import dev.kirill.kandinsky.chat.domain.*

interface ChatRepository {
    fun markRead(chatID: ChatID, callback: (Result<Unit>) -> Unit): Cancellation

    fun chats(matching: String, callback: (Result<List<ChatSummary>>) -> Unit): Cancellation

    fun history(request: PageRequest, callback: (Result<HistoryPage>) -> Unit): Cancellation

    fun index(chatID: ChatID, callback: (Result<List<IndexedRow>>) -> Unit): Cancellation

    fun send(command: SendCommand, callback: (Result<MessageDelta>) -> Unit): Cancellation

    fun delete(messageID: MessageID, callback: (Result<MessageDelta>) -> Unit): Cancellation

    fun search(
        chatID: ChatID,
        text: String,
        before: OrderKey?,
        callback: (Result<List<MessageID>>) -> Unit,
    ): Cancellation

    fun saveDraft(chatID: ChatID, draft: Draft, callback: (Result<Unit>) -> Unit): Cancellation

    // Возвращаемые данные позволяют продолжить ввод и известную незавершённую отправку после
    // закрытия экрана.
    fun draft(chatID: ChatID, callback: (Result<Draft>) -> Unit): Cancellation

    // Опора хранит сообщение, положение внутри строки и связь с концом истории, чтобы возвращение
    // не
    // сбрасывало прокрутку.
    fun savePosition(
        chatID: ChatID,
        anchor: ReadingAnchor,
        callback: (Result<Unit>) -> Unit,
    ): Cancellation

    fun position(chatID: ChatID, callback: (Result<ReadingAnchor?>) -> Unit): Cancellation
}
