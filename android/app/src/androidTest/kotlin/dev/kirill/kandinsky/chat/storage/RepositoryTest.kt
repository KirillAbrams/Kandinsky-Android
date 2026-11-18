// Проверяет чтение и запись на временной базе телефона, включая потерянный ответ, границы
// страниц, старую схему и неверные команды.

package dev.kirill.kandinsky.chat.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.support.*
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepositoryTest {
    // Готовит сообщения с одинаковым порядком времени и разными видами. Проверяет, что отбор
    // вложений не пропускает нужные строки и сохраняет порядок двух снимков альбома.
    @Test
    fun visualKeysetPagesSkipNonVisualRowsAndKeepAlbumOrder() {
        val h = harness()
        awaitResult<Unit> { cb ->
            h.store.submit(
                { db ->
                    val kinds =
                        mapOf(
                            2 to "photo",
                            4 to "voice",
                            5 to "video",
                            6 to "round",
                            7 to "gif",
                            8 to "sticker",
                            10 to "photo",
                            12 to "photo",
                        )
                    for (id in 1..13) {
                        db.execSQL(
                            "INSERT INTO messages(id,chatId,sortKey,kind,text,caption," +
                                "savedAt) " +
                                "VALUES(?,2,42,?,'','caption',42)",
                            arrayOf(id, kinds[id] ?: "text"),
                        )
                        if (id in listOf(2, 5, 7, 10, 12))
                            for (name in
                                if (id == 2) listOf("z-album", "a-album") else listOf("item-$id")) {
                                db.execSQL(
                                    "INSERT INTO attachments(id,messageId,relativePath,mime," +
                                        "byteSize,width,height) " +
                                        "VALUES(?,?,?,'image/jpeg',1,120,80)",
                                    arrayOf(name, id, "network/$name.jpg"),
                                )
                            }
                    }
                },
                cb,
            )
        }
        // Ждёт ответа очереди на одну страницу. Тест сравнивает готовые строки, поэтому пределы и
        // порядок проверяются через обычное хранилище.
        fun page(request: PageRequest) = awaitResult<HistoryPage> { h.repo.history(request, it) }
        val newest = page(PageRequest(2, limit = 2, visualOnly = true))
        assertEquals(listOf(10L, 12L), newest.rows.map { it.id })
        assertNull(newest.after)
        val previous = page(PageRequest(2, anchor = newest.before!!, limit = 2, visualOnly = true))
        assertEquals(listOf(5L, 7L), previous.rows.map { it.id })
        val oldest = page(PageRequest(2, anchor = previous.before!!, limit = 2, visualOnly = true))
        assertEquals(listOf(2L), oldest.rows.map { it.id })
        assertNull(oldest.before)
        assertEquals(listOf("z-album", "a-album"), oldest.rows.first().attachments.map { it.id })
        val newer = page(PageRequest(2, OrderKey(42, 7), PageDirection.NEWER, 2, visualOnly = true))
        assertEquals(listOf(10L, 12L), newer.rows.map { it.id })
        assertNull(newer.after)
        assertEquals(
            listOf(5L, 7L, 10L, 12L),
            page(PageRequest(2, OrderKey(42, 7), PageDirection.AROUND, 4, visualOnly = true))
                .rows
                .map { it.id },
        )
        assertEquals(13, page(PageRequest(2)).rows.size)
        assertEquals("ok", h.integrity())
        h.close()
    }

    // Открывает хранилище в папке с новым случайным именем. Номера строк и подготовленные данные
    // тогда не зависят от предыдущего запуска.
    private fun harness() =
        RepositoryHarness.open(
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
                "test-sql-" + UUID.randomUUID(),
            )
        )

    // Повторяет отправку до и после открытия базы и затем имитирует потерю ответа после записи.
    // Сверяет номера и число сообщений, чтобы повтор всегда возвращал сохранённое.
    @Test
    fun commitRetryAndLostReplySurviveReopen() {
        var h = harness()
        val command = SendCommand("send-one", 2L, text = "Привет 👋 O'Brien")
        val id = awaitResult<MessageDelta> { h.repo.send(command, it) }.inserted.first().id
        assertEquals(1L, id)
        assertEquals(id, awaitResult<MessageDelta> { h.repo.send(command, it) }.inserted.first().id)
        h = h.reopen()
        assertEquals(id, awaitResult<MessageDelta> { h.repo.send(command, it) }.inserted.first().id)
        assertEquals(1, h.messageCount(2L))
        assertEquals(
            "Привет 👋 O'Brien",
            awaitResult<List<ChatSummary>> { h.repo.chats("Alica", it) }.first().lastPreview,
        )
        h.faults.arm(RepoFault.AFTER_COMMIT_BEFORE_REPLY)
        val next = SendCommand("lost-reply", 2L, text = "Уже сохранено")
        try {
            awaitResult<MessageDelta> { h.repo.send(next, it) }
            fail("lost reply expected")
        } catch (e: RepoException) {
            assertEquals(RepoError.IO, e.reason)
        }
        h = h.reopen()
        awaitResult<MessageDelta> { h.repo.send(next, it) }
        assertEquals(2, h.messageCount(2L))
        assertEquals("ok", h.integrity())
        h.close()
    }

    // Проверяет откат при нехватке места и сбое перед записью, затем отправляет другое содержимое
    // со старым номером. История, подпись чата и черновик должны остаться прежними.
    @Test
    fun rollbackFullAndPayloadMismatchPreserveState() {
        val h = harness()
        awaitResult<MessageDelta> { h.repo.send(SendCommand("one", 2L, text = "Сохранено"), it) }
        awaitResult<Unit> { h.repo.saveDraft(2L, Draft("Черновик 👨‍👩‍👧"), it) }
        for (fault in listOf(RepoFault.BEFORE_COMMIT, RepoFault.STORAGE_FULL)) {
            h.faults.arm(fault)
            try {
                awaitResult<MessageDelta> {
                    h.repo.send(SendCommand("fail-$fault", 2L, text = "Не сохранено"), it)
                }
                fail("fault expected")
            } catch (e: RepoException) {
                assertEquals(
                    if (fault == RepoFault.STORAGE_FULL) RepoError.STORAGE_FULL else RepoError.IO,
                    e.reason,
                )
            }
        }
        try {
            awaitResult<MessageDelta> {
                h.repo.send(SendCommand("one", 2L, text = "Другой payload"), it)
            }
            fail("mismatch accepted")
        } catch (e: RepoException) {
            assertEquals(RepoError.INVALID_INPUT, e.reason)
        }
        assertEquals(1, h.messageCount(2L))
        assertEquals("Черновик 👨‍👩‍👧", awaitResult<Draft> { h.repo.draft(2L, it) }.text)
        assertEquals(
            "Сохранено",
            awaitResult<List<ChatSummary>> { h.repo.chats("", it) }.first().lastPreview,
        )
        h.close()
    }

    // Сохраняет ввод и положение чтения, открывает файл снова, ищет буквальные знаки и удаляет
    // последнее сообщение. Проверяет, что карточка показывает оставшуюся строку.
    @Test
    fun draftPositionDeleteAndLiteralSearch() {
        var h = harness()
        val id =
            awaitResult<MessageDelta> {
                    h.repo.send(SendCommand("first", 2L, text = "Поиск %_' 👋"), it)
                }
                .inserted
                .first()
                .id
        val anchor = ReadingAnchor(id, 0.4, 3, 12.0, false, 1)
        awaitResult<Unit> { h.repo.savePosition(2L, anchor, it) }
        awaitResult<Unit> {
            h.repo.saveDraft(
                2L,
                Draft("Текст", replyToID = id, revision = 7L, operationID = "pending"),
                it,
            )
        }
        h = h.reopen()
        assertEquals(anchor, awaitResult<ReadingAnchor?> { h.repo.position(2L, it) })
        assertEquals("pending", awaitResult<Draft> { h.repo.draft(2L, it) }.operationID)
        assertEquals(listOf(id), awaitResult<List<Long>> { h.repo.search(2L, "%_'", null, it) })
        val last =
            awaitResult<MessageDelta> {
                    h.repo.send(SendCommand("second", 2L, text = "Последнее"), it)
                }
                .inserted
                .first()
                .id
        awaitResult<MessageDelta> { h.repo.delete(last, it) }
        assertEquals(
            listOf(id),
            awaitResult<HistoryPage> { h.repo.history(PageRequest(2L), it) }.rows.map { it.id },
        )
        assertEquals(
            "Поиск %_' 👋",
            awaitResult<List<ChatSummary>> { h.repo.chats("", it) }.first().lastPreview,
        )
        assertEquals("ok", h.integrity())
        h.close()
    }

    // Создаёт базу старого вида и открывает её обычным способом. Новый номер версии сам по себе
    // недостаточен, поэтому проверяет также прежние сообщение, поиск и черновик.
    @Test
    fun legacyMigrationPreservesMessageAndDraft() {
        val root =
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
                "legacy-sql-" + UUID.randomUUID(),
            )
        RepositoryHarness.createLegacy(root)
        val h = RepositoryHarness.open(root)
        assertEquals(3, h.rawScalar("PRAGMA user_version"))
        val page = awaitResult<HistoryPage> { h.repo.history(PageRequest(2L), it) }
        assertEquals(listOf(7L), page.rows.map { it.id })
        assertEquals("Ранее 👋", page.rows.first().text)
        assertEquals("Старый черновик", awaitResult<Draft> { h.repo.draft(2L, it) }.text)
        assertEquals(listOf(7L), awaitResult<List<Long>> { h.repo.search(2L, "ранее", null, it) })
        assertEquals("ok", h.integrity())
        h.close()
    }

    // Читает длинную историю тремя порциями без дублей и пишет более старую версию черновика
    // последней. Проверяет, что порядок строк и новый ввод сохранились.
    @Test
    fun keysetPagesAndNewerDraftRevision() {
        val h = harness()
        for (i in 1..260) awaitResult<MessageDelta> {
            h.repo.send(SendCommand("page-$i", 2L, text = "Сообщение $i"), it)
        }
        val newest = awaitResult<HistoryPage> { h.repo.history(PageRequest(2L), it) }
        assertEquals((133L..260L).toList(), newest.rows.map { it.id })
        assertNull(newest.after)
        val middle =
            awaitResult<HistoryPage> {
                h.repo.history(PageRequest(2L, anchor = newest.before!!), it)
            }
        assertEquals((5L..132L).toList(), middle.rows.map { it.id })
        val oldest =
            awaitResult<HistoryPage> {
                h.repo.history(PageRequest(2L, anchor = middle.before!!), it)
            }
        assertEquals(listOf(1L, 2L, 3L, 4L), oldest.rows.map { it.id })
        assertNull(oldest.before)
        awaitResult<Unit> { h.repo.saveDraft(2L, Draft("Новый", revision = 2), it) }
        awaitResult<Unit> { h.repo.saveDraft(2L, Draft("Запоздалый старый", revision = 1), it) }
        assertEquals("Новый", awaitResult<Draft> { h.repo.draft(2L, it) }.text)
        h.close()
    }

    // Пытается отправить пустой текст и файл с путём выше папки. Проверяет, что ничего не
    // записалось и закрытое хранилище отвечает явной отменой.
    @Test
    fun invalidInputAndClosedStore() {
        val h = harness()
        for (command in
            listOf(
                SendCommand("blank", 2L, text = " \n "),
                SendCommand(
                    "escape",
                    2L,
                    kind = MessageKind.PHOTO,
                    attachments = listOf(AttachmentDraft("bad", "../other.jpg", "image/jpeg", 1L)),
                ),
            )) {
            try {
                awaitResult<MessageDelta> { h.repo.send(command, it) }
                fail("invalid input accepted")
            } catch (e: RepoException) {
                assertEquals(RepoError.INVALID_INPUT, e.reason)
            }
        }
        assertEquals(0, h.messageCount(2L))
        h.close()
        try {
            awaitResult<List<ChatSummary>> { h.repo.chats("", it) }
            fail("closed store query")
        } catch (e: RepoException) {
            assertEquals(RepoError.CANCELLED, e.reason)
        }
    }
}
