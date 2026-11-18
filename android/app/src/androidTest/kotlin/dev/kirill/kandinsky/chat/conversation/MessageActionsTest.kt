// Проверяет копирование и удаление через хранилище Андроида, включая соседнюю точку чтения и
// состояние после открытия базы снова.

package dev.kirill.kandinsky.chat.conversation

import androidx.test.platform.app.InstrumentationRegistry
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.support.RepositoryHarness
import dev.kirill.kandinsky.chat.support.awaitResult
import java.io.File
import java.util.UUID
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
}
