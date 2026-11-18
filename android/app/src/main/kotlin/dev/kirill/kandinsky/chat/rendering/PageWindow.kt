// Окно истории на андроиде хранит до трёх порций сообщений вокруг интересующей области. Номер
// запроса и проверка номера чата не дают устаревшему ответу подменить новую историю.

package dev.kirill.kandinsky.chat.rendering

import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.storage.ChatRepository

class PageWindow(val chatID: ChatID, private val repository: ChatRepository? = null) {
    var generation = 1
        private set

    private var closed = false
    private var request: Cancellation? = null
    private val pages = ArrayDeque<List<Message>>()
    val loadedRows: List<Message>
        // Собираем строки из всех порций, оставляя самую новую редакцию каждого сообщения, и
        // сортируем по порядку чата. Соседние запросы могут пересекаться, поэтому одинаковое
        // сообщение должно остаться одним.
        get() =
            pages
                .flatten()
                .fold(mutableMapOf<MessageID, Message>()) { byID, row ->
                    if ((byID[row.id]?.revision ?: -1) <= row.revision) byID[row.id] = row
                    byID
                }
                .values
                .sortedBy { it.order }

    val loadedCount: Int
        // Считаем уникальные сообщения после объединения. Сумма размеров порций завысила бы число
        // при пересечении.
        get() = loadedRows.size

    // Отменяем прежнее чтение и сохраняем новый номер. При ответе проверяем, что окно не закрыто
    // и запрос всё ещё последний, затем передаём результат вызывающему коду.
    fun request(value: PageRequest, callback: (Result<HistoryPage>) -> Unit): Cancellation {
        val repo = repository
        if (closed || value.chatID != chatID || repo == null) {
            callback(Result.failure(RepoException(RepoError.CANCELLED)))
            return Cancellation {}
        }
        request?.cancel()
        generation++
        val ticket = generation
        val token =
            repo.history(value) { result ->
                if (closed || ticket != generation)
                    callback(Result.failure(RepoException(RepoError.CANCELLED)))
                else {
                    result.onSuccess { apply(it, value.chatID, ticket) }
                    callback(result)
                }
            }
        request = token
        return token
    }

    // Проверяем номер запроса, номер чата и допустимое число строк. После добавления оставляем
    // три порции, поэтому частая прокрутка не копит старую историю.
    fun apply(page: HistoryPage, chatID: ChatID, generation: Int) {
        if (
            closed ||
                this.chatID != chatID ||
                this.generation != generation ||
                page.rows.size > 128 ||
                page.rows.any { it.chatID != chatID }
        )
            return
        pages.addLast(page.rows)
        while (pages.size > 3) pages.removeFirst()
    }

    // Подменяем более старую копию во всех порциях, где сообщение уже есть. Число удерживаемых
    // строк при этом не растёт.
    fun refreshKnownRow(message: Message) {
        for (i in pages.indices) {
            val page = pages[i]
            val at = page.indexOfFirst { it.id == message.id && it.revision < message.revision }
            if (at >= 0) pages[i] = page.toMutableList().also { it[at] = message }
        }
    }

    // Прежде чем использовать готовые строки, проверяем их чат, размер и отсутствие повторов.
    // Упорядочиваем их и раскладываем небольшими порциями для повторного открытия участка.
    fun seedKnownRows(rows: List<Message>) {
        if (
            closed ||
                rows.size > 384 ||
                rows.any { it.chatID != chatID } ||
                rows.map { it.id }.toSet().size != rows.size
        )
            return
        val ordered = rows.sortedBy { it.order }
        pages.clear()
        ordered.chunked(128).forEach { pages.addLast(it) }
    }

    // Закрываем окно, отменяем чтение и освобождаем порции. Новый номер не даёт уже начатому
    // ответу восстановить закрытую историю.
    fun close() {
        closed = true
        generation++
        request?.cancel()
        request = null
        pages.clear()
    }
}
