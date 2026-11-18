// Здесь перечислены операции, которыми экран пользуется для чтения и записи чата. Экран получает
// результат через вызов и может отменить ожидание, если уже ушёл со страницы.

package dev.kirill.kandinsky.chat.storage

import dev.kirill.kandinsky.chat.domain.*

interface ChatRepository {
    // Просит сбросить отметки о непрочитанных сообщениях в выбранном чате. Результат нужен, чтобы
    // список показывал верный счётчик.
    fun markRead(chatID: ChatID, callback: (Result<Unit>) -> Unit): Cancellation

    // Возвращает краткие карточки чатов по строке поиска. Без такого запроса экран должен был бы
    // открывать каждый чат ради списка.
    fun chats(matching: String, callback: (Result<List<ChatSummary>>) -> Unit): Cancellation

    // Запрашивает одну порцию сообщений у нужной границы истории. Ограничивает работу при
    // открытии очень длинного чата.
    fun history(request: PageRequest, callback: (Result<HistoryPage>) -> Unit): Cancellation

    // Возвращает порядок строк и их краткие признаки. По ним прокрутка находит нужное сообщение
    // до чтения его содержимого.
    fun index(chatID: ChatID, callback: (Result<List<IndexedRow>>) -> Unit): Cancellation

    // Передаёт команду записи и получает изменения истории. Один и тот же номер команды позволяет
    // повторить запрос без второго сообщения.
    fun send(command: SendCommand, callback: (Result<MessageDelta>) -> Unit): Cancellation

    // Запрашивает удаление по номеру сообщения. Возвращает изменения, чтобы экран снял нужную
    // строку и обновил соседние.
    fun delete(messageID: MessageID, callback: (Result<MessageDelta>) -> Unit): Cancellation

    // Ищет текст в выбранном чате, при продолжении берёт сообщения до указанной границы. Так
    // совпадения не возвращаются повторно на каждой порции.
    fun search(
        chatID: ChatID,
        text: String,
        before: OrderKey?,
        callback: (Result<List<MessageID>>) -> Unit,
    ): Cancellation

    // Сохраняет текущий ввод и вложения чата. Иначе при уходе с экрана несохранённый текст
    // пропадёт.
    fun saveDraft(chatID: ChatID, draft: Draft, callback: (Result<Unit>) -> Unit): Cancellation

    // Восстанавливает ввод и сведения об уже начатой отправке. Экран получает состояние одного
    // чата и может продолжить действие после возврата.
    fun draft(chatID: ChatID, callback: (Result<Draft>) -> Unit): Cancellation

    // Хранит номер строки, смещение внутри неё и признак чтения у конца. Эти сведения помогают
    // вернуть пользователя к тому же месту после закрытия.
    fun savePosition(
        chatID: ChatID,
        anchor: ReadingAnchor,
        callback: (Result<Unit>) -> Unit,
    ): Cancellation

    // Читает точку чтения для выбранного чата. Пустой ответ сообщает, что приложение должно
    // применить обычный порядок первого открытия.
    fun position(chatID: ChatID, callback: (Result<ReadingAnchor?>) -> Unit): Cancellation
}
