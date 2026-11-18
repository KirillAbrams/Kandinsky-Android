package dev.kirill.kandinsky.chat.conversation

import androidx.test.platform.app.InstrumentationRegistry
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.support.*
import dev.kirill.kandinsky.chat.support.TestFixtures
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ConversationStateTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun generationOptionsSurviveARealDatabaseReopen() {
        var h =
            RepositoryHarness.open(
                File(
                    instrumentation.targetContext.cacheDir,
                    "generation-options-" + UUID.randomUUID(),
                )
            )
        try {
            val fixture =
                """
                {"text":"Продолжить генерацию","revision":9,"attachmentDrafts":[],
                 "generationOptions":{"model":"video","aspect":"3:5","duration_seconds":1,
                 "quality":"standard","resolution":"compact","count":3,"seed":127,
                 "negative_prompt":"Keep the original scene","guidance_scale":4.0}}
            """
                    .trimIndent()
            val draft = dev.kirill.kandinsky.chat.storage.ChatStorageCodec.decodeDraft(fixture, "")
            awaitResult<Unit> { h.repo.saveDraft(2, draft, it) }
            h = h.reopen()
            val stored = awaitResult<Draft> { h.repo.draft(2, it) }
            val json =
                org.json.JSONObject(
                    dev.kirill.kandinsky.chat.storage.ChatStorageCodec.draft(stored)
                )
            val options = json.optJSONObject("generationOptions")
            assertNotNull("Параметры должны остаться в настоящей базе", options)
            assertEquals("video", options!!.getString("model"))
            assertEquals("3:5", options.getString("aspect"))
            assertEquals(1, options.getInt("duration_seconds"))
            assertEquals("standard", options.getString("quality"))
            assertEquals("compact", options.getString("resolution"))
            assertEquals(3, options.getInt("count"))
            assertEquals(127, options.getInt("seed"))
            assertEquals("Keep the original scene", options.getString("negative_prompt"))
            assertEquals(4.0, options.getDouble("guidance_scale"), 0.0)
            assertEquals("Продолжить генерацию", stored.text)
            assertEquals(9L, stored.revision)
        } finally {
            h.close()
        }
    }

    @Test
    fun scrollingSavesLatestPositionWithoutEditingOrClosing() {
        val h =
            RepositoryHarness.open(
                File(
                    instrumentation.targetContext.cacheDir,
                    "reading-position-" + UUID.randomUUID(),
                )
            )
        val state = ConversationState(h.repo)
        try {
            open(state)
            main {
                for (message in 1L..100L) {
                    state.anchor =
                        ReadingAnchor(messageID = message, fraction = 0.4, followsBottom = false)
                }
            }
            Thread.sleep(400)
            val stored = awaitResult<ReadingAnchor?> { h.repo.position(2, it) }
            assertEquals(
                "Прокрутка должна сохранять место без изменения черновика",
                state.anchor,
                stored,
            )
            assertEquals("", awaitResult<Draft> { h.repo.draft(2, it) }.text)
        } finally {
            main { state.close() }
            h.close()
        }
    }

    @Test
    fun draftWriteCannotOverwriteNewerReadingPosition() {
        val h =
            RepositoryHarness.open(
                File(instrumentation.targetContext.cacheDir, "reading-race-" + UUID.randomUUID())
            )
        val state = ConversationState(h.repo)
        try {
            open(state)
            val newer = ReadingAnchor(messageID = 2, fraction = 0.5, followsBottom = false)
            main {
                state.anchor = ReadingAnchor(messageID = 1, followsBottom = false)
                state.persistStudioDraft = { _, _, done ->
                    state.anchor = newer
                    android.os
                        .Handler(android.os.Looper.getMainLooper())
                        .postDelayed({ done(Result.success(Unit)) }, 400)
                }
            }
            awaitResult<Unit> { callback ->
                main { state.flush(callback) }
                Cancellation {}
            }
            val stored = awaitResult<ReadingAnchor?> { h.repo.position(2, it) }
            assertEquals(
                "Запись черновика не должна возвращать прежнее место чтения",
                newer,
                stored,
            )
        } finally {
            main {
                state.persistStudioDraft = null
                state.close()
            }
            h.close()
        }
    }

    @Test
    fun retrySurvivesLostReplyAndReopening() {
        val harness =
            RepositoryHarness.open(
                File(instrumentation.targetContext.cacheDir, "retry-" + UUID.randomUUID())
            )
        try {
            val id = "11111111-2222-3333-4444-555555555555"
            val state = ConversationState(harness.repo)
            val slot = StudioGeneration.Slot(id, StudioGeneration.Stage.FAILED, null, null)
            assertNull(state.beginSlotRetry(slot.copy(moderated = true)))
            val key = state.beginSlotRetry(slot)
            assertEquals("9c86e889-f9b0-862a-b44c-e97dd01a8b40", key.toString())
            assertNull(state.beginSlotRetry(slot))
            val reopened = ConversationState(harness.repo)
            assertEquals(key, reopened.beginSlotRetry(slot))
            reopened.finishSlotRetry(id)
            assertNotEquals(key, reopened.beginSlotRetry(slot.copy(runId = key.toString())))
        } finally {
            harness.close()
        }
    }

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun open(state: ConversationState) {
        awaitResult<Unit> { cb ->
            main { state.open(2, cb) }
            Cancellation {}
        }
    }

    private fun send(state: ConversationState) {
        awaitResult<Unit> { cb ->
            main { state.send(cb) }
            Cancellation {}
        }
    }

    @Test
    fun selectionSurvivesLegacyDraftRewriteAndLegacyRecovery() {
        var h =
            RepositoryHarness.open(
                File(
                    instrumentation.targetContext.cacheDir,
                    "selection-legacy-" + UUID.randomUUID(),
                )
            )
        val media = dev.kirill.kandinsky.chat.media.MediaStore(h.store, h.repo)
        val original = TestFixtures.assets.open("media/photo-000.jpg").use { it.readBytes() }
        val commands =
            (0 until 3).map { index ->
                val asset =
                    awaitResult<AttachmentDraft> { cb ->
                        main { media.stageSelection({ original.inputStream() }, "image/jpeg", cb) }
                        Cancellation {}
                    }
                SendCommand(
                    UUID.randomUUID().toString(),
                    2,
                    MessageKind.PHOTO,
                    caption = if (index == 0) "Одна подпись" else "",
                    attachments = listOf(asset),
                )
            }
        var state = ConversationState(h.repo)
        open(state)
        main { state.editDraft(Draft(text = "До отката")) }
        h.faults.arm(RepoFault.AFTER_COMMIT_BEFORE_REPLY)
        assertTrue(
            runCatching {
                    awaitResult<Unit> { cb ->
                        main { state.sendMediaBatch(commands, cb) }
                        Cancellation {}
                    }
                }
                .isFailure
        )
        main {
            state.close()
            media.close()
        }
        val root = h.root
        // Независимый образец записи прежним клиентом и очистки двух старых папок
        awaitResult<Unit> { cb ->
            h.store.submit(
                { db ->
                    val (value, revision) =
                        db.rawQuery(
                                "SELECT attachmentDraft,revision FROM drafts WHERE chatId=2",
                                null,
                            )
                            .use { c ->
                                c.moveToFirst()
                                org.json.JSONObject(c.getString(0)) to c.getLong(1)
                            }
                    val known =
                        setOf(
                            "text",
                            "attachmentDrafts",
                            "replyToID",
                            "revision",
                            "operationID",
                            "pendingSend",
                            "pendingRevision",
                        )
                    val legacy = org.json.JSONObject()
                    known.forEach { if (value.has(it)) legacy.put(it, value.get(it)) }
                    legacy.put("text", "Черновик старого клиента")
                    legacy.put("revision", revision + 1)
                    db.execSQL(
                        "UPDATE drafts SET text=?,attachmentDraft=?,revision=revision+1 WHERE chatId=2",
                        arrayOf("Черновик старого клиента", legacy.toString()),
                    )
                    val references = mutableSetOf<String>()
                    db.rawQuery("SELECT relativePath FROM attachments", null).use { c ->
                        while (c.moveToNext()) references += c.getString(0)
                    }
                    db.rawQuery(
                            "SELECT attachmentDraft FROM drafts WHERE attachmentDraft IS NOT NULL",
                            null,
                        )
                        .use { c ->
                            while (c.moveToNext()) {
                                val saved = org.json.JSONObject(c.getString(0))
                                for (items in
                                    listOf(
                                        saved.optJSONArray("attachmentDrafts"),
                                        saved
                                            .optJSONObject("pendingSend")
                                            ?.optJSONArray("attachments"),
                                    )) {
                                    if (items != null)
                                        for (index in 0 until items.length()) references +=
                                            items.getJSONObject(index).getString("relativePath")
                                }
                            }
                        }
                    for (name in listOf("staging", "objects")) for (file in
                        File(root, "media/$name").listFiles() ?: emptyArray()) {
                        if (
                            file.isFile &&
                                (name == "staging" || "objects/" + file.name !in references)
                        )
                            check(file.delete())
                    }
                    Unit
                },
                cb,
            )
        }
        h = h.reopen()
        val restoredMedia = dev.kirill.kandinsky.chat.media.MediaStore(h.store, h.repo)
        try {
            awaitResult<Unit> { cb ->
                main { restoredMedia.recover(cb) }
                Cancellation {}
            }
            commands.forEach { command ->
                val file = restoredMedia.fileURL(command.attachments[0].relativePath)
                assertTrue("Old cleanup cannot delete a pending selection original", file.isFile)
                assertArrayEquals(original, file.readBytes())
            }
            state = ConversationState(h.repo)
            open(state)
            assertEquals(
                "Intent must survive a legacy rewrite of draft JSON",
                commands.map { it.operationID },
                state.draft.pendingMediaBatch?.map { it.operationID },
            )
            send(state)
            assertEquals("Черновик старого клиента", state.draft.text)
            val rows =
                awaitResult<List<Triple<String, String, String>>> { cb ->
                    h.store.submit(
                        { db ->
                            db.rawQuery(
                                    "SELECT m.kind,m.caption,o.token " +
                                        "FROM messages m " +
                                        "JOIN operations o ON o.messageId=m.id " +
                                        "WHERE m.chatId=2 " +
                                        "ORDER BY m.id",
                                    null,
                                )
                                .use { c ->
                                    buildList {
                                        while (c.moveToNext()) add(
                                            Triple(c.getString(0), c.getString(1), c.getString(2))
                                        )
                                    }
                                }
                        },
                        cb,
                    )
                }
            assertEquals(listOf("photo", "photo", "photo"), rows.map { it.first })
            assertEquals(listOf("Одна подпись", "", ""), rows.map { it.second })
            assertEquals(commands.map { it.operationID }, rows.map { it.third })
        } finally {
            main {
                state.close()
                restoredMedia.close()
            }
            h.close()
        }
    }

    @Test
    fun mediaBatchSurvivesLostReplyAndRestartWithoutConsumingComposerDraft() {
        var h =
            RepositoryHarness.open(
                File(instrumentation.targetContext.cacheDir, "media-batch-" + UUID.randomUUID())
            )
        val media = dev.kirill.kandinsky.chat.media.MediaStore(h.store, h.repo)
        val commands =
            (0 until 3).map { index ->
                val asset =
                    awaitResult<AttachmentDraft> { cb ->
                        main {
                            media.stage(
                                { TestFixtures.assets.open("media/photo-000.jpg") },
                                "image/jpeg",
                                cb,
                            )
                        }
                        Cancellation {}
                    }
                SendCommand(
                    UUID.randomUUID().toString(),
                    2,
                    MessageKind.PHOTO,
                    caption = if (index == 0) "Подпись альбома" else "",
                    attachments = listOf(asset),
                )
            }
        awaitResult<Unit> { h.repo.saveDraft(2, Draft(text = "Мой отдельный черновик"), it) }
        awaitResult<Unit> { cb ->
            h.store.submit(
                { db ->
                    val json =
                        db.rawQuery("SELECT attachmentDraft FROM drafts WHERE chatId=2", null).use {
                            it.moveToFirst()
                            org.json.JSONObject(it.getString(0))
                        }
                    json.put(
                        "pendingMediaBatch",
                        org.json.JSONArray(
                            commands.map {
                                org.json.JSONObject(
                                    dev.kirill.kandinsky.chat.storage.ChatStorageCodec
                                        .commandString(it)
                                )
                            }
                        ),
                    )
                    db.execSQL(
                        "UPDATE drafts SET attachmentDraft=? WHERE chatId=2",
                        arrayOf(json.toString()),
                    )
                },
                cb,
            )
        }
        var state = ConversationState(h.repo)
        open(state)
        h.faults.arm(RepoFault.AFTER_COMMIT_BEFORE_REPLY)
        assertTrue(runCatching { send(state) }.isFailure)
        assertEquals("Мой отдельный черновик", state.draft.text)
        main {
            media.close()
            state.close()
        }
        h = h.reopen()
        val resumedMedia = dev.kirill.kandinsky.chat.media.MediaStore(h.store, h.repo)
        awaitResult<Unit> { cb ->
            main { resumedMedia.recover(cb) }
            Cancellation {}
        }
        commands.forEach {
            assertTrue(
                "Pending batch owns every unsent source across recovery",
                resumedMedia.fileURL(it.attachments[0].relativePath).isFile,
            )
        }
        state = ConversationState(h.repo)
        open(state)
        send(state)
        assertNull(state.lastError)
        assertEquals("Мой отдельный черновик", state.draft.text)
        assertEquals(3, h.messageCount(2))
        val rows =
            awaitResult<List<Pair<String, String>>> { cb ->
                h.store.submit(
                    { db ->
                        db.rawQuery(
                                "SELECT m.caption,o.token " +
                                    "FROM messages m " +
                                    "JOIN operations o ON o.messageId=m.id " +
                                    "WHERE m.chatId=2 " +
                                    "ORDER BY m.id",
                                null,
                            )
                            .use { c ->
                                buildList {
                                    while (c.moveToNext()) add(c.getString(0) to c.getString(1))
                                }
                            }
                    },
                    cb,
                )
            }
        assertEquals(listOf("Подпись альбома", "", ""), rows.map { it.first })
        assertEquals(commands.map { it.operationID }, rows.map { it.second })
        main {
            resumedMedia.close()
            state.close()
        }
        h.close()
    }

    @Test
    fun committedSendClearsDraftAndRestartPreservesIt() {
        var h =
            RepositoryHarness.open(
                File(instrumentation.targetContext.cacheDir, "state-" + UUID.randomUUID())
            )
        var state = ConversationState(h.repo)
        open(state)
        main { state.editDraft(Draft(text = "Привет 👋")) }
        h.faults.arm(RepoFault.BEFORE_COMMIT)
        runCatching { send(state) }
        assertEquals("Привет 👋", state.draft.text)
        assertNotNull(state.lastError)
        val token = state.draft.operationID!!
        h.faults.arm(RepoFault.AFTER_COMMIT_BEFORE_REPLY)
        runCatching { send(state) }
        assertEquals(token, state.draft.operationID)
        awaitResult<Unit> { cb ->
            main { state.flush(cb) }
            Cancellation {}
        }
        main { state.close() }
        h = h.reopen()
        state = ConversationState(h.repo)
        open(state)
        assertEquals(token, state.draft.operationID)
        send(state)
        assertEquals("", state.draft.text)
        assertNull(state.draft.operationID)
        assertEquals(1, h.messageCount(2))
        assertEquals(
            "Привет 👋",
            awaitResult<List<ChatSummary>> { h.repo.chats("", it) }.first().lastPreview,
        )
        main {
            state.editDraft(Draft(text = "Новый черновик"))
            state.anchor = ReadingAnchor(messageID = 1, fraction = 0.4, followsBottom = false)
        }
        awaitResult<Unit> { cb ->
            main { state.flush(cb) }
            Cancellation {}
        }
        main { state.close() }
        h = h.reopen()
        state = ConversationState(h.repo)
        open(state)
        assertEquals("Новый черновик", state.draft.text)
        assertEquals(1L, state.anchor?.messageID)
        assertEquals(false, state.anchor?.followsBottom)
        main { state.close() }
        h.close()
    }

    @Test
    fun newestInputIsNotClearedByEarlierCommit() {
        val h =
            RepositoryHarness.open(
                File(instrumentation.targetContext.cacheDir, "state-" + UUID.randomUUID())
            )
        val state = ConversationState(h.repo)
        open(state)
        val done = CountDownLatch(1)
        main {
            state.editDraft(Draft(text = "Первое"))
            state.send {
                assertTrue(it.isSuccess)
                done.countDown()
            }
            state.editDraft(Draft(text = "Второе"))
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals("Второе", state.draft.text)
        awaitResult<Unit> { cb ->
            main { state.flush(cb) }
            Cancellation {}
        }
        assertEquals("Второе", awaitResult<Draft> { h.repo.draft(2, it) }.text)
        main { state.close() }
        h.close()
    }

    @Test
    fun legacyDraftSurvivesCloseWhileOpenWaitsForSQLite() {
        val h =
            RepositoryHarness.open(
                File(instrumentation.targetContext.cacheDir, "state-close-" + UUID.randomUUID())
            )
        awaitResult<Unit> { h.repo.saveDraft(2, Draft(text = "legacy draft", revision = 0), it) }
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        h.store.submit({
            entered.countDown()
            assertTrue(gate.await(3, TimeUnit.SECONDS))
        }) {}
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val state = ConversationState(h.repo)
        val opened = CountDownLatch(1)
        main {
            state.open(2) { opened.countDown() }
            state.close()
        }
        gate.countDown()
        assertTrue(opened.await(5, TimeUnit.SECONDS))
        assertEquals("legacy draft", awaitResult<Draft> { h.repo.draft(2, it) }.text)
        h.close()
    }
}
