// Проверяет восстановление истории после ошибки и замену незавершённого открытия.

package dev.kirill.kandinsky.chat.rendering

import androidx.test.platform.app.InstrumentationRegistry
import dev.kirill.kandinsky.chat.domain.Cancellation
import dev.kirill.kandinsky.chat.domain.HistoryPage
import dev.kirill.kandinsky.chat.domain.IndexedRow
import dev.kirill.kandinsky.chat.domain.MessageDelta
import dev.kirill.kandinsky.chat.domain.OrderKey
import dev.kirill.kandinsky.chat.domain.PageRequest
import dev.kirill.kandinsky.chat.domain.RepoError
import dev.kirill.kandinsky.chat.domain.RepoException
import dev.kirill.kandinsky.chat.domain.SendCommand
import dev.kirill.kandinsky.chat.storage.ChatRepository
import dev.kirill.kandinsky.chat.support.RepositoryHarness
import dev.kirill.kandinsky.chat.support.awaitResult
import java.io.File
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryOpeningTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun invalidGeometryFailsTheOpeningAndTheSameHistoryCanBeOpenedAgain() {
        withHistory { host, repository ->
            val unreadable = object : ChatRepository by repository {
                override fun index(chatID: Long, callback: (Result<List<IndexedRow>>) -> Unit): Cancellation {
                    callback(Result.success(listOf(IndexedRow(9, OrderKey(9, 9), Double.NaN, 1))))
                    return Cancellation {}
                }
            }
            val backgroundErrors = LinkedBlockingQueue<Throwable>()
            instrumentation.runOnMainSync { host.onError = { backgroundErrors.add(it) } }

            assertTrue(open(host, unreadable).exceptionOrNull() is IllegalArgumentException)
            instrumentation.runOnMainSync { assertEquals(0, host.messageCount) }
            assertNull(backgroundErrors.poll())
            open(host, repository).getOrThrow()
            instrumentation.runOnMainSync {
                assertEquals(1, host.messageCount)
                host.close()
            }
            val closed = open(host, repository).exceptionOrNull() as? RepoException
            assertEquals(RepoError.CANCELLED, closed?.reason)
        }
    }

    @Test
    fun lateIndexCannotReplaceAnAlreadyReopenedHistory() {
        withHistory { host, repository ->
            var stale: ((Result<List<IndexedRow>>) -> Unit)? = null
            val delayed = object : ChatRepository by repository {
                override fun index(chatID: Long, callback: (Result<List<IndexedRow>>) -> Unit): Cancellation {
                    stale = callback
                    return Cancellation {}
                }
            }
            val first = LinkedBlockingQueue<Result<Unit>>()
            val backgroundErrors = LinkedBlockingQueue<Throwable>()
            instrumentation.runOnMainSync {
                host.onError = { backgroundErrors.add(it) }
                host.open(2, delayed, null) { first.add(it) }
            }
            open(host, repository).getOrThrow()
            val discarded = checkNotNull(first.poll(5, TimeUnit.SECONDS)).exceptionOrNull() as? RepoException
            assertEquals(RepoError.CANCELLED, discarded?.reason)
            instrumentation.runOnMainSync {
                checkNotNull(stale).invoke(Result.failure(IllegalStateException("Запоздалый ответ")))
                assertEquals(1, host.messageCount)
            }
            assertNull(first.poll())
            assertNull(backgroundErrors.poll())
        }
    }

    @Test
    fun missingChatDoesNotPreventOpeningAnExistingChat() {
        withHistory { host, repository ->
            val absent = open(host, repository, chatID = 999).exceptionOrNull()
            assertEquals(RepoError.NOT_FOUND, (absent as? RepoException)?.reason)
            instrumentation.runOnMainSync { assertEquals(0, host.messageCount) }
            open(host, repository).getOrThrow()
            instrumentation.runOnMainSync {
                assertEquals(1, host.messageCount)
                assertEquals(2L, host.chatID)
            }
        }
    }

    @Test
    fun requestsFromAnotherChatOrAfterClosureCannotReplaceALoadedPage() {
        withHistory { _, repository ->
            val window = PageWindow(2, repository)
            val loaded = LinkedBlockingQueue<Result<HistoryPage>>()
            instrumentation.runOnMainSync {
                window.request(PageRequest(2)) { loaded.add(it) }
            }
            val page = checkNotNull(loaded.poll(5, TimeUnit.SECONDS)).getOrThrow()
            assertEquals("Сохранённое сообщение", page.rows.single().text)

            instrumentation.runOnMainSync {
                val currentGeneration = window.generation
                val refusals = mutableListOf<RepoError?>()
                window.request(PageRequest(4)) { attempted ->
                    refusals.add((attempted.exceptionOrNull() as? RepoException)?.reason)
                }
                assertEquals(currentGeneration, window.generation)
                assertEquals(page.rows, window.loadedRows)
                window.close()
                window.request(PageRequest(2)) { attempted ->
                    refusals.add((attempted.exceptionOrNull() as? RepoException)?.reason)
                }
                assertEquals(listOf(RepoError.CANCELLED, RepoError.CANCELLED), refusals)
                assertTrue(window.loadedRows.isEmpty())
            }
        }
    }

    private fun open(host: ChatScrollHost, repository: ChatRepository, chatID: Long = 2): Result<Unit> {
        val result = LinkedBlockingQueue<Result<Unit>>()
        instrumentation.runOnMainSync { host.open(chatID, repository, null) { result.add(it) } }
        return checkNotNull(result.poll(5, TimeUnit.SECONDS))
    }

    private fun withHistory(run: (ChatScrollHost, ChatRepository) -> Unit) {
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "history-opening-" + UUID.randomUUID())
        val harness = RepositoryHarness.open(root)
        try {
            val command = SendCommand(UUID.randomUUID().toString(), 2, text = "Сохранённое сообщение")
            awaitResult<MessageDelta> { callback -> harness.repo.send(command, callback) }
            lateinit var host: ChatScrollHost
            instrumentation.runOnMainSync {
                host = ChatScrollHost(context)
                host.layout(0, 0, 400, 800)
            }
            try {
                run(host, harness.repo)
            } finally {
                instrumentation.runOnMainSync { host.close() }
            }
        } finally {
            harness.close()
            root.deleteRecursively()
        }
    }
}
