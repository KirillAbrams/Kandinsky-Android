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

    fun arm(fault: RepoFault) {
        next.set(fault)
    }

    fun consume(fault: RepoFault) = next.compareAndSet(fault, null)
}
