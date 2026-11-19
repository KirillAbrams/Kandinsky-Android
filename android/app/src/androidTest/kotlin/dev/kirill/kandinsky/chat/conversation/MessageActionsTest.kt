// Проверяет копирование и удаление через хранилище Андроида, включая соседнюю точку чтения и
// состояние после открытия базы снова.

package dev.kirill.kandinsky.chat.conversation

import android.app.Activity
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.network.NetworkCredentials
import dev.kirill.kandinsky.chat.network.NetworkJournal
import dev.kirill.kandinsky.chat.network.NetworkSchema
import dev.kirill.kandinsky.chat.storage.SQLiteChatRepository
import dev.kirill.kandinsky.chat.support.RepositoryHarness
import dev.kirill.kandinsky.chat.support.awaitResult
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class MessageActionsTest {
    // Сравнивает выбор соседней строки после удаления точки и неизменность точки, которую не
    // удаляли. Правка истории не должна сбрасывать позицию без причины.
    @Test
    fun deletedReadingAnchorChoosesNeighbor() {
        val rows = (1L..3L).map { IndexedRow(it, OrderKey(it, it), 44.0, 1) }
        val anchor = ReadingAnchor(2, 0.4, 17, -4.0, false)
        val next =
            dev.kirill.kandinsky.chat.rendering.ReadingAnchorResolver.afterRemoving(
                anchor,
                rows,
                setOf(1, 2),
            )
        assertEquals(3L, next.messageID)
        assertEquals(0.0, next.fraction, 0.0)
        assertNull(next.textUTF16Offset)
        assertEquals(
            1L,
            dev.kirill.kandinsky.chat.rendering.ReadingAnchorResolver.afterRemoving(
                    anchor,
                    rows,
                    setOf(2, 3),
                )
                .messageID,
        )
        assertEquals(
            anchor,
            dev.kirill.kandinsky.chat.rendering.ReadingAnchorResolver.afterRemoving(
                anchor,
                rows,
                setOf(1),
            ),
        )
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    // Передаёт действие в поток окна с ожиданием. Состояние и обработчики используются там же,
    // где в обычном приложении.
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    // Проверяет точное копирование текста, удаление ссылки из черновика и сохранность следующего
    // сообщения. После нового открытия база всё ещё отдаёт оставшуюся строку.
    @Test
    fun promptCopyAndRemovalPreserveDraftAndFollowingHistory() {
        var harness =
            RepositoryHarness.open(
                File(instrumentation.targetContext.cacheDir, "actions-" + UUID.randomUUID())
            )
        val source = "Поиск 👨‍👩‍👧‍👦 é العربية"
        val id =
            awaitResult<MessageDelta> {
                    harness.repo.send(SendCommand("source", 2, text = source), it)
                }
                .inserted
                .first()
                .id
        val state = ConversationState(harness.repo)
        awaitResult<Unit> { callback ->
            main { state.open(2, callback) }
            Cancellation {}
        }
        var copied = ""
        val actions = MessageActions(2, harness.repo, state) { copied = it }
        assertEquals(
            source,
            awaitResult<String> { callback ->
                main { actions.copy(id, callback) }
                Cancellation {}
            },
        )
        assertEquals(source, copied)
        main { state.editDraft(state.draft.copy(text = "Следующий запрос")) }
        awaitResult<Unit> { callback ->
            main { state.send(callback) }
            Cancellation {}
        }
        main { state.editDraft(state.draft.copy(replyToID = id)) }
        awaitResult<Unit> { callback ->
            main { state.flush(callback) }
            Cancellation {}
        }
        awaitResult<MessageDelta> { callback ->
            main { actions.delete(id, callback) }
            Cancellation {}
        }
        assertNull(state.draft.replyToID)
        val page = awaitResult<HistoryPage> { harness.repo.history(PageRequest(2), it) }
        assertEquals(1, page.rows.size)
        assertEquals("Следующий запрос", page.rows.first().text)
        main { state.close() }
        harness = harness.reopen()
        assertEquals(
            "Следующий запрос",
            awaitResult<HistoryPage> { harness.repo.history(PageRequest(2), it) }.rows.first().text,
        )
        assertEquals("ok", harness.integrity())
        harness.close()
    }

    @Test
    fun staleMessageActionsDoNotChangeClipboardOrAnotherChat() {
        val root = File(instrumentation.targetContext.cacheDir, "stale-actions-${UUID.randomUUID()}")
        val harness = RepositoryHarness.open(root)
        val state = ConversationState(harness.repo)
        try {
            awaitResult<Unit> { callback ->
                harness.store.submit({ database ->
                    database.execSQL(
                        "INSERT INTO chats(id,title,avatarKind,updatedSortKey,unreadCount,pinned) " +
                            "VALUES(3,'Другой чат','initial',0,0,0)"
                    )
                }, callback)
            }
            val source = awaitResult<MessageDelta> {
                harness.repo.send(SendCommand("other-chat", 3, text = "Из другого чата"), it)
            }.inserted.single()
            val copies = mutableListOf<String>()
            val actions = MessageActions(2, harness.repo, state, copies::add)
            val copyFailure = LinkedBlockingQueue<Throwable>()
            val deleteFailure = LinkedBlockingQueue<Throwable>()
            main {
                actions.copy(source.id) { result -> result.exceptionOrNull()?.let(copyFailure::add) }
                actions.delete(source.id) { result -> result.exceptionOrNull()?.let(deleteFailure::add) }
            }
            assertEquals(RepoError.NOT_FOUND, (copyFailure.poll(5, TimeUnit.SECONDS) as? RepoException)?.reason)
            assertEquals(RepoError.NOT_FOUND, (deleteFailure.poll(5, TimeUnit.SECONDS) as? RepoException)?.reason)
            assertTrue(copies.isEmpty())
            assertEquals(
                listOf(source.id),
                awaitResult<HistoryPage> { harness.repo.history(PageRequest(3), it) }.rows.map { it.id },
            )
        } finally {
            main { state.close() }
            harness.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun cancellingCopyWhileHistoryIsQueuedLeavesTheClipboardAlone() {
        val root = File(instrumentation.targetContext.cacheDir, "cancel-copy-${UUID.randomUUID()}")
        val harness = RepositoryHarness.open(root)
        val state = ConversationState(harness.repo)
        val release = CountDownLatch(1)
        try {
            val message = awaitResult<MessageDelta> {
                harness.repo.send(SendCommand("copy-later", 2, text = "Текст для копирования"), it)
            }.inserted.single()
            val entered = CountDownLatch(1)
            harness.store.submit({
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }) {}
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val copies = mutableListOf<String>()
            val completions = mutableListOf<Result<String>>()
            val actions = MessageActions(2, harness.repo, state, copies::add)
            main { actions.copy(message.id, completions::add).cancel() }
            release.countDown()
            awaitResult<List<IndexedRow>> { harness.repo.index(2, it) }
            main {
                assertTrue(copies.isEmpty())
                assertTrue(completions.isEmpty())
            }
            assertEquals(
                message.text,
                awaitResult<String> { callback ->
                    main { actions.copy(message.id, callback) }
                    Cancellation {}
                },
            )
            assertEquals(listOf(message.text), copies)
        } finally {
            release.countDown()
            main { state.close() }
            harness.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun refusedDeletionKeepsTheMessageAndItsQueuedSendUntilRetry() {
        val root = File(instrumentation.targetContext.cacheDir, "refused-deletion-${UUID.randomUUID()}")
        val harness = RepositoryHarness.open(root)
        val repository = SQLiteChatRepository(harness.store, NetworkJournal(2))
        val state = ConversationState(repository)
        val changes = mutableListOf<Long>()
        try {
            awaitResult<Unit> { harness.store.submit(NetworkSchema::install, it) }
            val operation = UUID.randomUUID().toString()
            val message = awaitResult<MessageDelta> {
                repository.send(SendCommand(operation, 2, text = "Запрос ещё ждёт отправки"), it)
            }.inserted.single()
            val actions = MessageActions(2, repository, state) {
                fail("Удаление не должно обращаться к буферу обмена")
            }
            main { actions.onDelta = { changes.addAll(it.removedIDs) } }
            awaitResult<Unit> { callback ->
                harness.store.submit({ database ->
                    database.execSQL("""
                        CREATE TRIGGER coverage_refused_deletion BEFORE DELETE ON messages
                        BEGIN SELECT RAISE(ABORT,'write refused'); END
                    """.trimIndent())
                }, callback)
            }
            fun delete(): MessageDelta = awaitResult { callback ->
                main { actions.delete(message.id, callback) }
                Cancellation {}
            }

            val failure = runCatching { delete() }.exceptionOrNull()
            assertEquals(RepoError.INVALID_INPUT, (failure as? RepoException)?.reason)
            assertTrue(changes.isEmpty())
            val retained = awaitResult<HistoryPage> { repository.history(PageRequest(2), it) }.rows.single()
            assertEquals(message.id, retained.id)
            assertEquals(MessageDelivery.PENDING, retained.delivery)
            assertEquals(1, harness.rawScalar("SELECT count(*) FROM net_outbox WHERE action='send'"))
            assertEquals(0, harness.rawScalar("SELECT count(*) FROM net_outbox WHERE action='delete'"))
            assertEquals(0, harness.rawScalar("SELECT count(*) FROM net_messages WHERE tombstone=1"))

            awaitResult<Unit> { callback ->
                harness.store.submit({ it.execSQL("DROP TRIGGER coverage_refused_deletion") }, callback)
            }
            assertEquals(listOf(message.id), delete().removedIDs)
            assertEquals(listOf(message.id), changes)
            assertTrue(awaitResult<HistoryPage> { repository.history(PageRequest(2), it) }.rows.isEmpty())
            assertEquals(1, harness.rawScalar("SELECT count(*) FROM net_outbox WHERE action='delete'"))
            assertEquals(1, harness.rawScalar("SELECT count(*) FROM net_messages WHERE tombstone=1"))
            assertEquals("ok", harness.integrity())
        } finally {
            main { state.close() }
            harness.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun pendingMessageIsKeptOnCancelAndRemovedAfterConfirmation() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".cleanup"))
        check(NetworkCredentials(context).load() == null)
        val device = UiDevice.getInstance(instrumentation)
        device.wakeUp()
        val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val activity = instrumentation.startActivitySync(intent) as Activity
        val root = File(context.cacheDir, "pending-action-${UUID.randomUUID()}")
        val harness = RepositoryHarness.open(root)
        val repository = SQLiteChatRepository(harness.store, NetworkJournal(42))
        val state = ConversationState(repository)
        val changes = LinkedBlockingQueue<MessageDelta>()
        var controller: ConversationUIController? = null
        try {
            awaitResult<Unit> { harness.store.submit(NetworkSchema::install, it) }
            awaitResult<Unit> { callback ->
                main { state.open(2, callback) }
                Cancellation {}
            }
            val command = SendCommand(UUID.randomUUID().toString(), 2, text = "Ещё не отправлено")
            val sent = awaitResult<MessageDelta> { repository.send(command, it) }.inserted.single()
            assertEquals(MessageDelivery.PENDING, sent.delivery)
            main {
                controller = ConversationUIController(activity, 2, repository, state) { changes.add(it) }
            }

            fun openConfirmation() {
                main { checkNotNull(controller).menu(sent) }
                val action = checkNotNull(device.wait(Until.findObject(By.res("actions.1")), 5000))
                assertEquals("Отменить отправку", action.text)
                assertEquals("android.widget.Button", action.className)
                action.click()
                assertTrue(device.wait(Until.hasObject(By.text("Отменить отправку?")), 5000))
            }

            openConfirmation()
            checkNotNull(device.wait(Until.findObject(By.res("android:id/button2")), 5000)).click()
            assertTrue(device.wait(Until.gone(By.text("Отменить отправку?")), 5000))
            val kept = awaitResult<HistoryPage> { repository.history(PageRequest(2), it) }
            assertEquals(sent.id, kept.rows.single().id)
            assertTrue(changes.isEmpty())

            openConfirmation()
            checkNotNull(device.wait(Until.findObject(By.res("android:id/button1")), 5000)).click()
            val removed = checkNotNull(changes.poll(5, TimeUnit.SECONDS))
            assertEquals(listOf(sent.id), removed.removedIDs)
            val remaining = awaitResult<HistoryPage> { repository.history(PageRequest(2), it) }
            assertTrue(remaining.rows.isEmpty())
        } finally {
            main {
                controller?.close()
                state.close()
                activity.finish()
            }
            harness.close()
            root.deleteRecursively()
        }
    }


    @Test
    fun aFailedReportCanBeRetriedWithAnotherReason() {
        withController { device, controller ->
            val media = Attachment("image", "result.jpg", "image/jpeg", 300, 10, 10, 1, null, "")
            val message = Message(
                id = 17L,
                chatID = 2L,
                order = OrderKey(17L, 17L),
                direction = MessageDirection.INCOMING,
                savedAtMs = 0,
                kind = MessageKind.PHOTO,
                text = "",
                caption = "",
                replyToID = null,
                revision = 1,
                attachments = listOf(media),
            )
            val requests = LinkedBlockingQueue<ReportAttempt>()
            main {
                controller.reportGeneration = { id, reason, finish ->
                    requests.add(ReportAttempt(id, reason, finish))
                }
                controller.menu(message.copy(attachments = emptyList()))
            }
            assertFalse(device.hasObject(By.text("Пожаловаться на результат")))
            main { controller.menu(message) }
            val reason = checkNotNull(device.wait(
                Until.findObject(By.text(StudioReportReason.HARMFUL.title)), 5000,
            ))
            reason.click()
            val first = checkNotNull(requests.poll(5, TimeUnit.SECONDS))
            assertEquals(message.id, first.id)
            assertEquals(StudioReportReason.HARMFUL, first.reason)

            main { controller.menu(message) }
            assertFalse(device.hasObject(By.text("Пожаловаться на результат")))
            assertTrue(requests.isEmpty())
            main { first.finish(Result.failure(IllegalStateException("Сеть недоступна"))) }
            assertTrue(device.wait(Until.hasObject(By.text("Жалобу не удалось отправить")), 5000))
            checkNotNull(device.wait(Until.findObject(By.res("android:id/button1")), 5000)).click()

            main { controller.menu(message) }
            checkNotNull(device.wait(
                Until.findObject(By.text(StudioReportReason.OTHER.title)), 5000,
            )).click()
            val second = checkNotNull(requests.poll(5, TimeUnit.SECONDS))
            assertEquals(message.id, second.id)
            assertEquals(StudioReportReason.OTHER, second.reason)
            main { second.finish(Result.success(Unit)) }
            assertTrue(device.wait(Until.hasObject(By.text("Жалоба отправлена")), 5000))
            assertTrue(requests.isEmpty())
        }
    }

    @Test
    fun anUnansweredGenerationActionBlocksOnlyItsOwnRow() {
        withController { _, controller ->
            val actions = mutableListOf<Pair<MessageID, StudioGenerationAction>>()
            val removals = mutableListOf<Pair<MessageID, String?>>()
            val completions = mutableListOf<(Result<Unit>) -> Unit>()
            main {
                controller.generationAction = { id, action, operation, finish ->
                    assertNull(operation)
                    actions += id to action
                    completions += finish
                }
                controller.deleteGenerationContent = { id, blob, finish ->
                    removals += id to blob
                    finish(Result.success(Unit))
                }
                controller.performGenerationAction(17, StudioGenerationAction.CANCEL)
                controller.performGenerationAction(17, StudioGenerationAction.RETRY)
                controller.removeGenerationContent(17, "first")
                controller.removeGenerationContent(18, "second")
                assertEquals(listOf(17L to StudioGenerationAction.CANCEL), actions)
                assertEquals(listOf(18L to "second"), removals)

                completions.single()(Result.failure(IllegalStateException("Соединение потеряно")))
                controller.removeGenerationContent(17, "first")
                assertEquals(listOf(18L to "second", 17L to "first"), removals)

                controller.close()
                controller.performGenerationAction(19, StudioGenerationAction.CHECK)
                controller.removeGenerationContent(19, null)
                assertEquals(1, actions.size)
                assertEquals(2, removals.size)
            }
        }
    }

    private data class ReportAttempt(
        val id: MessageID,
        val reason: StudioReportReason,
        val finish: (Result<Unit>) -> Unit,
    )

    private fun withController(action: (UiDevice, ConversationUIController) -> Unit) {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".cleanup"))
        check(NetworkCredentials(context).load() == null)
        val device = UiDevice.getInstance(instrumentation)
        device.wakeUp()
        val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val activity = instrumentation.startActivitySync(intent) as Activity
        val root = File(context.cacheDir, "result-actions-${UUID.randomUUID()}")
        val harness = RepositoryHarness.open(root)
        val state = ConversationState(harness.repo)
        try {
            var controller: ConversationUIController? = null
            main { controller = ConversationUIController(activity, 2, harness.repo, state) {} }
            val surface = checkNotNull(controller)
            try {
                action(device, surface)
            } finally {
                main { surface.close() }
            }
        } finally {
            main {
                state.close()
                activity.finish()
            }
            harness.close()
            root.deleteRecursively()
        }
    }
}
