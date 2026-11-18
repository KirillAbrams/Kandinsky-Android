// Здесь собраны параметры чтения истории, её границы и причины отказа хранилища.
// Проверки могут назначить сбой на нужном шаге, чтобы проверить повтор операции после отказа.

package dev.kirill.kandinsky.chat.domain

import java.util.concurrent.atomic.AtomicReference

enum class RepoError {
    INVALID_INPUT,
    NOT_FOUND,
    STORAGE_FULL,
    IO,
    CORRUPT,
    CANCELLED,
}

class RepoException(val reason: RepoError) : RuntimeException(reason.name)

enum class PageDirection {
    OLDER,
    NEWER,
    AROUND,
}

data class PageRequest(
    val chatID: ChatID,
    val anchor: OrderKey? = null,
    val direction: PageDirection = PageDirection.OLDER,
    val limit: Int = 128,
    val visualOnly: Boolean = false,
)

data class HistoryPage(val rows: List<Message>, val before: OrderKey?, val after: OrderKey?)

fun interface Cancellation {
    // Останавливает начатую работу, когда запрос уже не нужен.
    // Хранилище может прекратить чтение или передачу вместо ожидания полного результата.
    fun cancel()
}

enum class RepoFault {
    BEFORE_COMMIT,
    AFTER_COMMIT_BEFORE_REPLY,
    BEFORE_RENAME,
    AFTER_RENAME,
    STORAGE_FULL,
}

class RepositoryFaults {
    private val next = AtomicReference<RepoFault?>()

    // Назначает следующий сбой отдельной записью, которую другие потоки видят целиком.
    // Проверка может воспроизвести отказ именно на выбранном шаге.
    fun arm(fault: RepoFault) {
        next.set(fault)
    }

    // Снимает отметку, только если дошли до назначенного шага.
    // Сравнение и снятие происходят вместе, поэтому два потока не применят один сбой дважды.
    fun consume(fault: RepoFault) = next.compareAndSet(fault, null)
}
