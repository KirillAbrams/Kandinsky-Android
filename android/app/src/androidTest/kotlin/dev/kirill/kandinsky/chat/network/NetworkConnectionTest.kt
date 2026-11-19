// Проверяем повтор команды после обрыва, сохранение событий, завершение сеанса и действия с
// результатами. Ответы и соединение задаются внутри проверки, данные пишутся в отдельную
// временную базу.

package dev.kirill.kandinsky.chat.network

import android.content.Intent
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import androidx.core.view.allViews
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.kirill.kandinsky.chat.attachments.AttachmentPicker
import dev.kirill.kandinsky.chat.attachments.ChatAttachmentCoordinator
import dev.kirill.kandinsky.chat.conversation.ConversationChrome
import dev.kirill.kandinsky.chat.conversation.ConversationServices
import dev.kirill.kandinsky.chat.conversation.ConversationState
import dev.kirill.kandinsky.chat.domain.AttachmentDraft
import dev.kirill.kandinsky.chat.domain.Cancellation
import dev.kirill.kandinsky.chat.domain.ChatSummary
import dev.kirill.kandinsky.chat.domain.Draft
import dev.kirill.kandinsky.chat.domain.HistoryPage
import dev.kirill.kandinsky.chat.domain.KandinskyOptions
import dev.kirill.kandinsky.chat.domain.MessageDelivery
import dev.kirill.kandinsky.chat.domain.MessageKind
import dev.kirill.kandinsky.chat.domain.MessageDelta
import dev.kirill.kandinsky.chat.domain.MessageChangeReason
import dev.kirill.kandinsky.chat.domain.PageRequest
import dev.kirill.kandinsky.chat.domain.ReadingAnchor
import dev.kirill.kandinsky.chat.domain.RepoError
import dev.kirill.kandinsky.chat.domain.RepoException
import dev.kirill.kandinsky.chat.domain.SendCommand
import dev.kirill.kandinsky.chat.domain.StudioGeneration
import dev.kirill.kandinsky.chat.domain.StudioGenerationAction
import dev.kirill.kandinsky.chat.domain.StudioModel
import dev.kirill.kandinsky.chat.domain.StudioReportReason
import dev.kirill.kandinsky.chat.domain.StudioTransfer
import dev.kirill.kandinsky.chat.media.MediaAdmission
import dev.kirill.kandinsky.chat.media.MediaCoordinator
import dev.kirill.kandinsky.chat.media.MediaStore
import dev.kirill.kandinsky.chat.rendering.ChatScrollHost
import dev.kirill.kandinsky.chat.rendering.NativeAccessibility
import dev.kirill.kandinsky.chat.rendering.NativeAction
import dev.kirill.kandinsky.chat.rendering.PreparedText
import dev.kirill.kandinsky.chat.rendering.appendRowAccessibility
import dev.kirill.kandinsky.chat.rendering.layoutKey
import dev.kirill.kandinsky.chat.rendering.performRowTap
import dev.kirill.kandinsky.chat.rendering.rowLeft
import dev.kirill.kandinsky.chat.storage.SQLiteChatRepository
import dev.kirill.kandinsky.chat.support.RepositoryHarness
import dev.kirill.kandinsky.chat.support.TestFixtures
import dev.kirill.kandinsky.chat.support.awaitResult
import java.io.File
import java.io.IOException
import java.net.SocketException
import java.net.URI
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Buffer
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test

class NetworkConnectionTest {
    @Test
    fun aCommittedSendIsRecoveredAfterItsAcknowledgementWasLost() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val configured = InstrumentationRegistry.getArguments().getString("coverage-drop-ack-operation")
        assumeTrue(context.packageName.endsWith(".cleanup"))
        assumeTrue(URI(NetworkProfile.ORIGIN).host == "127.0.0.1")
        assumeNotNull(configured)
        val operation = UUID.fromString(checkNotNull(configured)).toString()
        val profile = checkNotNull(NetworkCredentials(context).load())
        val root = File(context.cacheDir, "dropped-acknowledgement-$operation")
        val harness = RepositoryHarness.open(root)
        val repository = SQLiteChatRepository(harness.store, NetworkJournal(profile.userID))
        val sync = NetworkSync(profile, harness.store, repository)
        val statuses = LinkedBlockingQueue<String>()
        val updated = CountDownLatch(1)
        val prompt = "AckQA-" + operation.take(8)
        var temporaryChat: Long? = null
        fun awaitStatus(expected: String, seconds: Long) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
            var remaining = deadline - System.nanoTime()
            while (remaining > 0) {
                if (statuses.poll(remaining, TimeUnit.NANOSECONDS) == expected) return
                remaining = deadline - System.nanoTime()
            }
            fail("Синхронизация не сообщила состояние: $expected")
        }

        try {
            awaitResult<Unit> { harness.store.submit(NetworkSchema::install, it) }
            val chat = awaitNetwork<Long> { sync.createStudioThread(UUID.randomUUID(), it) }
            temporaryChat = chat
            sync.onStatus = { statuses.add(it) }
            sync.foreground(true)
            awaitStatus("В сети", 10)

            val sent = awaitResult { callback ->
                repository.send(SendCommand(
                    operation, chat, text = prompt,
                    generationOptions = KandinskyOptions(model = "image", count = 1),
                ), callback)
            }
            val localID = sent.inserted.single().id
            sync.onDeltas = { deltas ->
                if (deltas.flatMap { it.inserted + it.updated }.any {
                        it.id == localID && it.delivery == MessageDelivery.ACCEPTED
                    }
                ) updated.countDown()
            }
            assertEquals(1, harness.rawScalar("SELECT count(*) FROM net_outbox"))
            sync.kick()
            awaitStatus("Нет связи · сообщения сохранены", 23)
            awaitStatus("В сети", 8)
            assertTrue(updated.await(8, TimeUnit.SECONDS))
            assertEquals(0, harness.rawScalar("SELECT count(*) FROM net_outbox"))
            val history = awaitResult<HistoryPage> { repository.history(PageRequest(chat), it) }.rows
            assertEquals(listOf(localID), history.filter { it.text == prompt }.map { it.id })
            val server = sync.api.json("/v1/chats/$chat/history?limit=16").getJSONArray("messages")
            val matching = (0 until server.length()).map(server::getJSONObject).count {
                it.getString("kind") == "text" && it.getString("text") == prompt
            }
            assertEquals(1, matching)
        } finally {
            try {
                temporaryChat?.let { chat -> awaitNetwork<Unit> { sync.deleteStudioThread(chat, it) } }
            } finally {
                sync.close()
                harness.close()
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun outgoingPayloadWaitsForAcceptedReplyAndUsesSavedAttachments() = withConnection { fixture ->
        val photo = AttachmentDraft(
            id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", relativePath = "photo.jpg",
            mime = "image/jpeg", byteSize = 42, rawWidth = 18, rawHeight = 26, orientation = 6,
            thumbnailKey = "thumb", tinyPreview = "small",
        )
        val voice = AttachmentDraft(
            id = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", relativePath = "voice.m4a",
            mime = "audio/mp4", byteSize = 60, durationMs = 750, waveform = listOf(0, 128, 255),
        )
        val photoBlob = "3".repeat(32)
        val previewBlob = "4".repeat(32)
        val voiceBlob = "5".repeat(32)
        awaitResult<Unit> { callback ->
            fixture.harness.store.submit({ db ->
                db.execSQL(
                    "INSERT INTO net_messages(localID,serverID,clientID,senderID) " +
                        "VALUES (81,901,'accepted',7),(82,NULL,'pending',7)",
                )
                db.execSQL(
                    "INSERT INTO net_uploads(attachmentID,blobID,previewBlobID) VALUES (?,?,?),(?,?,NULL)",
                    arrayOf(photo.id, photoBlob, previewBlob, voice.id, voiceBlob),
                )
            }, callback)
        }
        val pending = SendCommand("FIRST", 2, text = "Ждём", replyToID = 82)
        val reply = SendCommand(
            "SECOND", 2, kind = MessageKind.PHOTO, text = "Ответ", caption = "Подпись",
            replyToID = 81, attachments = listOf(photo, voice),
            generationOptions = KandinskyOptions(model = "video", seconds = 1, resolution = "compact"),
        )
        val plain = SendCommand("THIRD", 2, text = "Обычный текст")
        val payloads = OutgoingMessagePayloads(fixture.sync)
        val messages = awaitNetwork<List<JSONObject>> {
            payloads.prepare(listOf(pending, reply, plain), it)
        }

        assertEquals(listOf("second", "third"), messages.map { it.getString("clientId") })
        val first = messages.first()
        assertEquals(2L, first.getLong("chatId"))
        assertEquals(901L, first.getLong("replyToId"))
        assertEquals("Ответ", first.getString("text"))
        assertEquals("Подпись", first.getString("caption"))
        val options = first.getJSONObject("generationOptions")
        assertEquals("video", options.getString("model"))
        assertEquals(1, options.getInt("duration_seconds"))
        assertEquals("compact", options.getString("resolution"))
        val assets = first.getJSONArray("attachments")
        assertEquals(2, assets.length())
        val firstAsset = assets.getJSONObject(0)
        assertEquals(photoBlob, firstAsset.getString("blobId"))
        assertEquals(previewBlob, firstAsset.getString("previewBlobId"))
        assertEquals(18, firstAsset.getInt("rawWidth"))
        assertEquals(6, firstAsset.getInt("orientation"))
        assertEquals("small", firstAsset.getString("tinyPreview"))
        val secondAsset = assets.getJSONObject(1)
        assertEquals(voiceBlob, secondAsset.getString("blobId"))
        assertTrue(secondAsset.isNull("previewBlobId"))
        assertEquals(750L, secondAsset.getLong("durationMs"))
        assertEquals("[0,128,255]", secondAsset.getJSONArray("waveform").toString())
        assertTrue(messages.last().isNull("replyToId"))
        assertFalse(messages.last().has("generationOptions"))
        assertEquals(0, messages.last().getJSONArray("attachments").length())
        assertTrue(fixture.requests.isEmpty())
        assertEquals(2, fixture.harness.rawScalar("SELECT count(*) FROM net_uploads"))
    }

    @Test
    fun outgoingGifUploadIsStoredForTheNextPreparation() = withConnection { fixture ->
        val blob = "8".repeat(32)
        val id = "CCCCCCCC-CCCC-CCCC-CCCC-CCCCCCCCCCCC"
        val path = "/v1/uploads/cccccccc-cccc-cccc-cccc-cccccccccccc"
        val file = File(fixture.harness.store.root, "media/source.gif")
        file.parentFile?.mkdirs()
        TestFixtures.assets.open("media/gif-00.gif").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val failFirstRequest = java.util.concurrent.atomic.AtomicBoolean(true)
        fixture.respond = { request ->
            assertEquals(path, request.url.encodedPath)
            assertEquals("PUT", request.method)
            assertEquals("2", request.header("x-kandinsky-chat"))
            assertEquals("image/gif", request.body?.contentType()?.toString())
            if (failFirstRequest.getAndSet(false)) 503 to "{}"
            else 201 to JSONObject().put("blobId", blob).toString()
        }

        val attachment = AttachmentDraft(id, "source.gif", "image/gif", file.length())
        val command = SendCommand("SEND-GIF", 2, kind = MessageKind.GIF, attachments = listOf(attachment))
        val payloads = OutgoingMessagePayloads(fixture.sync)
        val failure = runCatching {
            awaitNetwork<List<JSONObject>> { payloads.prepare(listOf(command), it) }
        }.exceptionOrNull()
        assertEquals(503, (failure as? NetworkFailure)?.status)
        assertEquals(0, fixture.harness.rawScalar("SELECT count(*) FROM net_uploads"))

        repeat(2) {
            val messages = awaitNetwork<List<JSONObject>> { payloads.prepare(listOf(command), it) }
            val assets = messages.single().getJSONArray("attachments")
            assertEquals(1, assets.length())
            val sent = assets.getJSONObject(0)
            assertEquals(blob, sent.getString("blobId"))
            assertTrue(sent.isNull("previewBlobId"))
        }

        assertEquals(listOf(path, path), fixture.requests.toList())
        assertEquals(1, fixture.harness.rawScalar("SELECT count(*) FROM net_uploads WHERE blobID='$blob'"))
    }

    @Test
    fun outgoingPreparationReportsUnreadableReferencesWithoutUploading() = withConnection { fixture ->
        awaitResult<Unit> { callback ->
            fixture.harness.store.submit({ it.execSQL("DROP TABLE net_uploads") }, callback)
        }
        val command = SendCommand("WAIT", 2, text = "Временный файл недоступен")
        val payloads = OutgoingMessagePayloads(fixture.sync)
        val failure = runCatching {
            awaitNetwork<List<JSONObject>> { payloads.prepare(listOf(command), it) }
        }.exceptionOrNull()

        assertEquals(RepoError.IO, (failure as? RepoException)?.reason)
        assertTrue(fixture.requests.isEmpty())
    }

    @Test
    fun outgoingGenerationUploadLinksThePreviewToTheSourcePhoto() = withConnection { fixture ->
        val original = "2".repeat(32)
        val preview = "8".repeat(32)
        val id = "DDDDDDDD-DDDD-DDDD-DDDD-DDDDDDDDDDDD"
        val originalPath = "/v1/uploads/dddddddd-dddd-dddd-dddd-dddddddddddd"
        val file = File(fixture.harness.store.root, "media/source.jpg")
        file.parentFile?.mkdirs()
        TestFixtures.assets.open("media/photo-000.jpg").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        fixture.respond = { request ->
            assertEquals("PUT", request.method)
            assertEquals("image/jpeg", request.body?.contentType()?.toString())
            val blob = if (request.url.encodedPath == originalPath) {
                assertEquals("1", request.header("x-kandinsky-source"))
                assertEquals("2", request.header("x-kandinsky-chat"))
                assertNull(request.header("x-kandinsky-parent"))
                original
            } else {
                assertNull(request.header("x-kandinsky-source"))
                assertEquals(original, request.header("x-kandinsky-parent"))
                preview
            }
            201 to JSONObject().put("blobId", blob).toString()
        }

        val attachment = AttachmentDraft(id, "source.jpg", "image/jpeg", file.length())
        val command = SendCommand(
            "ANIMATE", 2, kind = MessageKind.PHOTO, attachments = listOf(attachment),
            generationOptions = KandinskyOptions(model = "video"),
        )
        val payloads = OutgoingMessagePayloads(fixture.sync)
        val messages = awaitNetwork<List<JSONObject>> { payloads.prepare(listOf(command), it) }
        val sent = messages.single().getJSONArray("attachments").getJSONObject(0)

        assertEquals(original, sent.getString("blobId"))
        assertEquals(preview, sent.getString("previewBlobId"))
        assertEquals(2, fixture.requests.size)
        assertEquals(originalPath, fixture.requests.first())
        assertEquals(1, fixture.harness.rawScalar(
            "SELECT count(*) FROM net_uploads WHERE blobID='$original' AND previewBlobID='$preview'",
        ))
    }

    @Test
    fun visibleHistoryPagesOnceAtTheStoredServerSequence() = withConnection { fixture ->
        fixture.connect()
        val chat = 2L
        val path = "/v1/chats/$chat/history"
        val queries = LinkedBlockingQueue<String>()
        val latest = wireMessage(52, SendCommand("new", chat), "Новое").put("senderId", 3)
        val older = wireMessage(51, SendCommand("old", chat), "Старое").put("senderId", 3)
        fixture.respond = { request ->
            assertEquals(path, request.url.encodedPath)
            assertEquals("GET", request.method)
            assertEquals("128", request.url.queryParameter("limit"))
            val before = request.url.queryParameter("before")
            queries.add(before ?: "latest")
            val batch = if (before == null) JSONArray().put(latest) else JSONArray().put(older).put(latest)
            200 to JSONObject().put("messages", batch).put("hasOlder", before == null).toString()
        }

        fixture.sync.visibleChat(chat)
        fixture.sync.visibleChat(chat)
        assertEquals("latest", queries.poll(5, TimeUnit.SECONDS))
        fixture.waitUntil("Записана свежая страница") { fixture.history().rows.size == 1 }
        val row = fixture.history().rows.single()
        assertEquals("Новое", row.text)
        assertEquals(1, fixture.harness.rawScalar("SELECT hasOlder FROM net_chats WHERE chatID=2"))

        val anchor = ReadingAnchor(messageID = row.id, followsBottom = false)
        fixture.sync.viewport(chat, anchor)
        fixture.sync.viewport(chat, anchor)
        assertEquals("52", queries.poll(5, TimeUnit.SECONDS))
        fixture.waitUntil("Записана предыдущая страница") { fixture.history().rows.size == 2 }
        val saved = fixture.history().rows
        assertEquals(setOf("Старое", "Новое"), saved.map { it.text }.toSet())
        assertEquals(row.id, saved.single { it.text == "Новое" }.id)
        assertEquals(0, fixture.harness.rawScalar("SELECT hasOlder FROM net_chats WHERE chatID=2"))
        assertEquals(2, fixture.requests.count { it == path })
    }

    @Test
    fun visibleHistoryReportsAnUnavailablePageWithoutReplacingCachedRows() = withConnection { fixture ->
        fixture.connect()
        val chat = 2L
        val path = "/v1/chats/$chat/history"
        val row = wireMessage(52, SendCommand("saved", chat), "Сохранённое сообщение")
        val oldPage = JSONObject().put("messages", JSONArray().put(row)).put("hasOlder", true)
        awaitResult { callback ->
            fixture.harness.store.submit({ fixture.sync.cache.history(it, chat, oldPage) }, callback)
        }
        val previousId = fixture.history().rows.single().id
        fixture.respond = { request ->
            assertEquals(path, request.url.encodedPath)
            404 to "{}"
        }

        fixture.sync.visibleChat(chat)
        fixture.expectStatus("История временно недоступна")

        assertEquals(1, fixture.requests.count { it == path })
        assertEquals(previousId, fixture.history().rows.single().id)
        assertEquals(1, fixture.harness.rawScalar("SELECT hasOlder FROM net_chats WHERE chatID=2"))
    }

    @Test
    fun chatCreationReusesTheDraftIdAndStoresOnlyAValidServerChat() = withConnection { fixture ->
        val draft = UUID.fromString("71d7cbae-da2a-4c14-9e98-4e2e442d9b08")
        val requested = LinkedBlockingQueue<String>()
        val path = "/v1/demo/chats"
        fixture.respond = { request ->
            assertEquals(path, request.url.encodedPath)
            assertEquals("POST", request.method)
            val buffer = Buffer()
            requireNotNull(request.body).writeTo(buffer)
            requested.add(JSONObject(buffer.readUtf8()).getString("id"))
            when (requested.size) {
                1 -> 409 to "{}"
                2 -> 201 to "{}"
                else -> 201 to """
                    {"chat":{"id":35,"title":"Новый чат","lastSeq":0,"unreadCount":0,"pinned":false}}
                """.trimIndent()
            }
        }

        fun create() = awaitNetwork<Long> { fixture.sync.createStudioThread(draft, it) }
        val rejected = runCatching(::create).exceptionOrNull() as? NetworkFailure
        assertEquals(409, rejected?.status)
        val malformed = runCatching(::create).exceptionOrNull()
        assertTrue(malformed is IllegalStateException)
        assertEquals(0, fixture.harness.rawScalar("SELECT count(*) FROM net_chats"))
        assertEquals(35L, create())
        assertEquals(1, fixture.harness.rawScalar("SELECT count(*) FROM net_chats WHERE chatID=35"))
        assertEquals(List(3) { draft.toString() }, requested.toList())
    }

    @Test
    fun archivedSearchPreservesTheSearchTextAndPageBoundaries() = withConnection { fixture ->
        val path = "/v1/kandinsky/threads"
        fixture.respond = { request ->
            assertEquals(path, request.url.encodedPath)
            assertEquals("GET", request.method)
            assertEquals("archive", request.url.queryParameter("view"))
            assertEquals("C++ & фото", request.url.queryParameter("q"))
            assertEquals("16", request.url.queryParameter("offset"))
            assertEquals("8", request.url.queryParameter("limit"))
            200 to """
                {"threads":[{"id":42,"title":"C++ & фото","lastSeq":9,"unreadCount":0,
                "pinned":false,"archived":true}]}
            """.trimIndent()
        }

        val threads = awaitNetwork<List<StudioThread>> {
            fixture.sync.studioThreads("archive", "C++ & фото", 16, 8, it)
        }

        val thread = threads.single()
        assertEquals(42L, thread.id)
        assertEquals("C++ & фото", thread.title)
        assertTrue(thread.archived)
        assertEquals(listOf(path), fixture.requests.toList())
    }

    @Test
    fun generationModelsKeepServerOptionsAndRejectAnIncompleteModel() = withConnection { fixture ->
        val path = "/v1/kandinsky/models"
        val catalog = """
            {
              "models": [
                {
                  "id":"video", "name":"Видео", "description":"Создание ролика",
                  "capabilities":{
                    "quality":[{"id":"fast","name":"Быстро"}],
                    "resolutions":[{"id":"medium","name":"Среднее","sizes":{"9:16":[576,1024]}}],
                    "aspects":["9:16","16:9"], "duration_seconds":[1,5], "max_count":4,
                    "negative_prompt":true, "guidance":{"min":1,"max":8,"default":4.5}
                  },
                  "reference_capabilities":{
                    "quality":[], "resolutions":[], "aspects":["9:16"], "duration_seconds":[1],
                    "max_count":1, "negative_prompt":false
                  }
                },
                {"id":"video_sr","name":"Улучшение видео","description":"Увеличение размера"}
              ]
            }
        """.trimIndent()
        val invalid = """{"models":[{"id":"video","name":"Видео","description":"Без настроек"}]}"""
        fixture.respond = { request ->
            assertEquals(path, request.url.encodedPath)
            assertEquals("GET", request.method)
            200 to if (fixture.requests.size == 1) catalog else invalid
        }

        fun load() = awaitNetwork<List<StudioModel>>(fixture.sync::studioModels)

        val models = load()
        assertEquals(listOf("video", "video_sr"), models.map { it.id })
        val video = checkNotNull(models.first().capabilities)
        assertEquals(listOf("fast"), video.quality.map { it.id })
        assertEquals("576 × 1024", video.resolutions.single().label("9:16"))
        assertEquals("Среднее", video.resolutions.single().label("16:9"))
        assertEquals(listOf(1, 5), video.durations)
        assertEquals(4, video.maxCount)
        assertEquals(4.5, checkNotNull(video.guidance).default, 0.0)
        assertTrue(video.negativePrompt)
        assertEquals(1, models.first().referenceCapabilities?.maxCount)
        assertNull(models.first().referenceCapabilities?.guidance)
        assertNull(models.last().capabilities)

        val failure = runCatching(::load).exceptionOrNull()
        assertTrue(failure is org.json.JSONException)
        assertEquals(listOf(path, path), fixture.requests.toList())
    }

    @Test
    fun chatChangesAndRemovalAreAppliedAfterServerConfirmation() = withConnection { fixture ->
        val id = 2L
        val path = "/v1/kandinsky/threads/$id"
        val row = wireMessage(52, SendCommand("saved", id), "Сохранённое сообщение")
        val page = JSONObject().put("messages", JSONArray().put(row)).put("hasOlder", false)
        val original = JSONObject().put("id", id).put("title", "До изменения").put("lastSeq", 52)
            .put("unreadCount", 0).put("pinned", false).put("archived", false)
        awaitResult { callback ->
            fixture.harness.store.submit({ db ->
                fixture.sync.cache.mergeChats(db, JSONObject().put("chats", JSONArray().put(original)))
                fixture.sync.cache.history(db, id, page)
            }, callback)
        }
        val originalId = fixture.history().rows.single().id
        val methods = LinkedBlockingQueue<String>()
        fixture.respond = { request ->
            assertEquals(path, request.url.encodedPath)
            methods.add(request.method)
            when (methods.size) {
                1, 4 -> 409 to "{}"
                2, 3 -> {
                    val buffer = Buffer()
                    requireNotNull(request.body).writeTo(buffer)
                    val archived = JSONObject(buffer.readUtf8()).getBoolean("archived")
                    assertEquals(methods.size == 2, archived)
                    200 to JSONObject(original.toString()).put("archived", archived)
                        .put("title", "Подтверждённый архив").toString()
                }
                else -> 204 to ""
            }
        }
        fun archive(archived: Boolean) = awaitNetwork<Unit> {
            fixture.sync.changeStudioThread(id, JSONObject().put("archived", archived), it)
        }
        fun remove() = awaitNetwork<Unit> { fixture.sync.deleteStudioThread(id, it) }
        val repository = NetworkChatRepository(fixture.repository, fixture.sync)
        fun savedChat(): ChatSummary? =
            awaitResult<List<ChatSummary>> { repository.chats("", it) }.firstOrNull { it.id == id }

        assertEquals(409, (runCatching { archive(true) }.exceptionOrNull() as? NetworkFailure)?.status)
        assertFalse(checkNotNull(savedChat()).archived)
        archive(true)
        val moved = checkNotNull(savedChat())
        assertTrue(moved.archived)
        assertEquals("Подтверждённый архив", moved.title)
        archive(false)
        assertFalse(checkNotNull(savedChat()).archived)

        assertEquals(409, (runCatching(::remove).exceptionOrNull() as? NetworkFailure)?.status)
        assertEquals(originalId, fixture.history().rows.single().id)
        remove()
        assertNull(savedChat())
        assertEquals(0, fixture.harness.rawScalar("SELECT count(*) FROM messages WHERE chatId=2"))
        assertEquals(listOf("PATCH", "PATCH", "PATCH", "DELETE", "DELETE"), methods.toList())
    }

    @Test
    fun uploadProgressIgnoresLaterPacketsFromAReplacedConnection() = withConnection { fixture ->
        val original = fixture.connect()
        val id = "71d7cbae-da2a-4c14-9e98-4e2e442d9b08"
        val device = "2dc2f71b-1ba1-4c16-b85b-3305bcdbee53"
        val updates = LinkedBlockingQueue<List<StudioTransfer>>()
        fixture.sync.onStudioTransfersChanged = { updates.add(fixture.sync.studioTransfers) }
        fun transfer(bytes: Int) = JSONObject().put("id", id).put("deviceId", device)
            .put("received", bytes).put("total", 300).put("mime", "video/mp4")
        fun packet(vararg transfers: JSONObject) = frame("kandinsky.uploads").put(
            "uploads", JSONArray(transfers.toList()),
        )

        original.receive(packet(transfer(75)))
        val first = checkNotNull(updates.poll(5, TimeUnit.SECONDS)).single()
        assertEquals(id, first.id)
        assertEquals(0.25, first.progress, 0.0)
        original.receive(packet(transfer(225), transfer(225)))
        fixture.expectStatus("Нет связи · сообщения сохранены")
        fixture.expectStatus("Подключение…")
        val replacement = fixture.takeSocket()
        replacement.open()
        replacement.frame("resume")
        replacement.receive(frame("resumed"))
        fixture.expectStatus("В сети")

        original.receive(packet(transfer(225)))
        replacement.receive(packet(transfer(150)))
        fixture.waitUntil("Отобразился текущий ход загрузки") {
            updates.any { it.singleOrNull()?.received == 150L }
        }
        assertFalse(updates.flatten().any { it.received == 225L })
        updates.clear()
        replacement.receive(packet())
        fixture.waitUntil("Завершённая загрузка перестала отображаться") {
            updates.poll()?.isEmpty() == true && fixture.sync.studioTransfers.isEmpty()
        }
    }

    @Test
    fun incomingChatListChangesReachTheMainThreadAfterTheyAreStored() = withConnection { fixture ->
        val socket = fixture.connect()
        val delivered = LinkedBlockingQueue<Boolean>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            fixture.sync.onStudioRecentChanged = {
                delivered.add(Looper.myLooper() == Looper.getMainLooper())
            }
        }
        val updated = StudioThread(2, "Генерации", 0, 0, false, false, 5000, true)
        val changed = JSONObject()
            .put("seq", 1).put("chatId", 2).put("kind", "deleted").put("messageId", 9000)
        socket.receive(frame("events")
            .put("cursor", 1)
            .put("chats", JSONArray().put(updated.wire()))
            .put("events", JSONArray().put(changed)))
        assertEquals("The chat drawer may update only on the main thread", true,
            delivered.poll(5, TimeUnit.SECONDS))
        val recent = awaitResult<StudioRecentPage> { callback ->
            fixture.harness.store.submit({ db -> fixture.sync.cache.studioRecent(db) }, callback)
        }.threads.single { it.id == 2L }
        assertEquals(5000L, recent.activityAtMs)
        assertTrue(recent.generating)
    }

    @Test
    fun directoryFailureKeepsSavedChatsAndRefreshesThemAfterReconnect() = withConnection { fixture ->
        val releaseFailure = CountDownLatch(1)
        fixture.directoryReply = { path ->
            if (path == "/v1/chats") {
                check(releaseFailure.await(5, TimeUnit.SECONDS))
                503 to """{"error":{"code":"unavailable"}}"""
            } else null
        }
        val socket = try {
            fixture.connect()
        } finally {
            releaseFailure.countDown()
        }
        fixture.expectStatus("Нет связи · сообщения сохранены")
        assertTrue(socket.cancelled)
        val previous = awaitResult<List<ChatSummary>> { fixture.repository.chats("", it) }
        assertEquals("Генерации", previous.single { it.id == 2L }.title)

        val updated = StudioThread(2, "Обновлённая история", 0, 0, false, true, 5000, false)
        val snapshot = JSONObject().put("chats", JSONArray().put(updated.wire())).toString()
        fixture.directoryReply = { 200 to snapshot }
        fixture.expectStatus("Подключение…")
        val replacement = fixture.takeSocket()
        replacement.open()
        assertEquals(0L, replacement.frame("resume").getLong("cursor"))
        replacement.receive(frame("resumed"))
        fixture.expectStatus("В сети")
        val recent = awaitResult<List<ChatSummary>> { fixture.repository.chats("", it) }
        assertEquals("Обновлённая история", recent.single { it.id == 2L }.title)
        assertEquals(1, fixture.requests.count { it == "/v1/bootstrap" })
    }

    @Test
    fun composerShowsTheWeightedProgressOfOtherDevices() = withConnection { fixture ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        device.wakeUp()
        val socket = fixture.connect()
        val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val activity = instrumentation.startActivitySync(intent)
        val state = ConversationState(fixture.repository)
        val media = MediaStore(fixture.harness.store)
        val admission = MediaAdmission()
        var playback: MediaCoordinator? = null
        lateinit var chrome: ConversationChrome

        fun upload(deviceID: String, received: Long, total: Long) = JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("deviceId", deviceID)
            .put("received", received)
            .put("total", total)
            .put("mime", "image/jpeg")

        fun publish(vararg uploads: JSONObject) {
            socket.receive(frame("kandinsky.uploads").put("uploads", JSONArray(uploads.toList())))
        }

        try {
            instrumentation.runOnMainSync {
                val player = MediaCoordinator(admission, media)
                playback = player
                chrome = ConversationChrome(
                    activity, 2, state, ConversationServices(fixture.sync, media, admission, player), {},
                )
                activity.setContentView(chrome)
            }
            awaitNetwork<Unit> { callback ->
                instrumentation.runOnMainSync { state.open(2, callback) }
            }
            awaitNetwork<Unit> { callback ->
                instrumentation.runOnMainSync { chrome.openHistory(fixture.repository, null, callback) }
            }
            val indicator = By.res("studio.upload.progress")
            assertFalse(device.hasObject(indicator))
            val ownDevice = fixture.sync.profile.deviceID.uppercase()
            val otherDevice = UUID.randomUUID().toString()
            val own = upload(ownDevice, 90, 100)
            publish(own, upload(otherDevice, 20, 50), upload(otherDevice, 80, 150))
            assertTrue(device.wait(Until.hasObject(
                By.res("studio.upload.progress").desc("Загрузка на другом устройстве: 50%"),
            ), 5_000))

            publish(own)
            assertTrue(device.wait(Until.gone(indicator), 5_000))
            assertEquals(ownDevice, fixture.sync.studioTransfers.single().deviceId)
        } finally {
            instrumentation.runOnMainSync {
                state.close()
                playback?.close()
                media.close()
                admission.close()
                activity.finish()
            }
        }
    }

    @Test
    fun restoredAttachmentKeepsTheComposerModelCompatibleWithItsFile() = withConnection { fixture ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val activity = instrumentation.startActivitySync(intent)
        val state = ConversationState(fixture.repository)
        val media = MediaStore(fixture.harness.store)
        val admission = MediaAdmission()
        var playback: MediaCoordinator? = null
        val models = LinkedBlockingQueue<String>()

        fun stage(file: String, mime: String) = awaitNetwork<AttachmentDraft> { callback ->
            instrumentation.runOnMainSync {
                media.stageSelection({ TestFixtures.assets.open("real-media/$file") }, mime, callback)
            }
        }
        fun tapSend() {
            checkNotNull(device.wait(Until.findObject(By.res("composer.send").enabled(true)), 5_000)).click()
        }
        try {
            assertTrue(device.wait(Until.hasObject(By.res("account.username")), 5_000))
            val video = stage("backend-990da02cd31e46cfb8fd630c293f3489.mp4", "video/mp4")
            val photo = stage("backend-824d66cce0d64409ad4888a13792c7d4.jpg", "image/jpeg")
            val restored = Draft(text = "Восстановленный запрос", attachmentDrafts = listOf(video),
                generationOptions = KandinskyOptions(model = "image"))
            instrumentation.runOnMainSync {
                val player = MediaCoordinator(admission, media).also { playback = it }
                ConversationChrome(
                    activity, 0, state, ConversationServices(fixture.sync, media, admission, player), {},
                ).also { composer ->
                    composer.setOnApplyWindowInsetsListener { _, insets ->
                        @Suppress("DEPRECATION")
                        composer.updateInsets(insets.systemWindowInsetTop, insets.systemWindowInsetBottom)
                        insets
                    }
                    activity.setContentView(composer)
                    composer.requestApplyInsets()
                    composer.onFirstSend = { models.add(it.model) }
                    composer.openStudioDraft(restored)
                }
            }
            assertTrue(device.wait(Until.hasObject(By.desc("Удалить исходное видео")), 5_000))
            tapSend()
            assertEquals("video_sr", models.poll(5, TimeUnit.SECONDS))

            val cleared = restored.copy(
                generationOptions = KandinskyOptions(model = "video_sr"), attachmentDrafts = emptyList(),
            )
            instrumentation.runOnMainSync { state.editDraft(cleared) }
            tapSend()
            assertEquals("image", models.poll(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { state.editDraft(cleared.copy(attachmentDrafts = listOf(photo))) }
            assertTrue(device.wait(Until.hasObject(By.desc("Удалить исходное фото")), 5_000))
            tapSend()
            assertEquals("video", models.poll(5, TimeUnit.SECONDS))
            assertEquals("Восстановленный запрос", state.draft.text)
            assertTrue(fixture.requests.isEmpty())
        } finally {
            instrumentation.runOnMainSync {
                state.close()
                activity.finish()
                playback?.close()
                media.close()
                admission.close()
            }
        }
    }

    @Test
    fun offlineSourceUploadKeepsThePhotoAndWaitsForAReconnectBeforeRetrying() = withConnection { fixture ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val activity = instrumentation.startActivitySync(intent)
        val media = MediaStore(fixture.harness.store)
        val admission = MediaAdmission()
        val state = ConversationState(fixture.repository)
        var coordinator: ChatAttachmentCoordinator? = null
        val attempts = AtomicInteger()
        val errors = mutableListOf<String>()
        try {
            awaitNetwork<Unit> { callback -> instrumentation.runOnMainSync { state.open(2, callback) } }
            val photo = awaitNetwork<AttachmentDraft> { callback ->
                instrumentation.runOnMainSync {
                    media.stageStudioPhoto({ TestFixtures.assets.open("media/photo-000.jpg") }, callback)
                }
            }
            val file = media.fileURL(photo.relativePath)
            val original = file.readBytes()
            val path = "/v1/uploads/" + photo.id.lowercase()
            fixture.respond = { request ->
                assertEquals(path, request.url.encodedPath)
                assertEquals("1", request.header("x-kandinsky-source"))
                assertEquals("2", request.header("x-kandinsky-chat"))
                attempts.incrementAndGet()
                throw SocketException("Проверочный обрыв связи")
            }
            val options = KandinskyOptions(model = "video")
            val draft = Draft(text = "Отправка после обрыва", attachmentDrafts = listOf(photo),
                generationOptions = options)
            awaitNetwork<Unit> { callback ->
                instrumentation.runOnMainSync {
                    state.editDraft(draft)
                    state.flush(callback)
                }
            }
            instrumentation.runOnMainSync {
                coordinator = ChatAttachmentCoordinator(
                    activity, activity as AttachmentPicker, fixture.sync, media, admission, state,
                    isActive = { !activity.isFinishing }, options = { options }, selectOptions = {},
                    showError = errors::add, previewVideo = { _, _, _ -> fail("Ожидается фото") },
                    refresh = {}, refreshProgress = {},
                )
                checkNotNull(coordinator).resumePendingUpload()
            }
            val source = checkNotNull(coordinator)
            fun awaitAttempt(number: Int) {
                fixture.waitUntil("Передача завершилась при недоступном сервере") {
                    var idle = false
                    instrumentation.runOnMainSync { idle = !source.isBusy }
                    attempts.get() == number && idle
                }
            }
            awaitAttempt(1)
            instrumentation.runOnMainSync {
                source.resumePendingUpload()
                assertFalse(source.isBusy)
                source.resumePendingUpload(reconnected = true)
            }
            awaitAttempt(2)
            val saved = awaitResult<Draft> { fixture.repository.draft(2, it) }
            assertEquals(draft.text, saved.text)
            assertEquals(listOf(photo.id), saved.attachmentDrafts.map { it.id })
            assertTrue(original.contentEquals(file.readBytes()))
            assertEquals(0, fixture.harness.rawScalar("SELECT count(*) FROM net_uploads"))
            assertEquals(listOf(path, path), fixture.requests.toList())
            instrumentation.runOnMainSync { assertTrue(errors.isEmpty()) }
        } finally {
            instrumentation.runOnMainSync {
                coordinator?.close(discardDraft = false)
                state.close()
                media.close()
                admission.close()
                activity.finish()
            }
        }
    }

    @Test
    fun deletedMediaIsRemovedFromLocalStorageAcrossMultipleCleanupBatches() = withConnection { fixture ->
        val socket = fixture.connect()
        val h = fixture.harness
        fixture.sync.studioMedia = NetworkMedia(h.store, fixture.sync.api)
        val bytes = "Данные только для проверки".toByteArray()
        fun cached(relative: String): File = File(h.root, "media/$relative").apply {
            check(parentFile!!.isDirectory || parentFile!!.mkdirs())
            writeBytes(bytes)
        }

        val unrelatedBlob = "f".repeat(32)
        val retained = cached("remote/$unrelatedBlob.jpg")
        val paths = mutableListOf<File>()
        val rows = (1..17).map { index ->
            val blob = index.toString(16).padStart(32, '0')
            val preview = (100 + index).toString(16).padStart(32, '0')
            paths += cached("remote/$blob.jpg")
            paths += cached("previews/$preview.jpg")
            JSONObject().put("blobId", blob).put("previewBlobId", preview)
        }
        fun event(seq: Int, media: List<JSONObject>) = JSONObject()
            .put("seq", seq)
            .put("chatId", 2)
            .put("kind", "deleted")
            .put("messageId", 80 + seq)
            .put("removedMedia", JSONArray(media))

        val packet = frame("events").put("cursor", 2).put("events", JSONArray(listOf(
            event(1, rows.take(9)), event(2, rows.drop(9)),
        )))
        val notifications = AtomicInteger()
        val evicted = CountDownLatch(2)
        fixture.sync.onStudioMediaEvicted = {
            notifications.incrementAndGet()
            evicted.countDown()
        }
        socket.receive(packet)
        assertTrue(evicted.await(8, TimeUnit.SECONDS))
        assertEquals(2, socket.frame("applied").getInt("cursor"))
        assertTrue(paths.none { it.exists() })
        assertTrue(bytes.contentEquals(retained.readBytes()))
        val pending = "SELECT count(*) FROM net_state WHERE key LIKE 'studioRemoved:%'"
        fixture.waitUntil("Отметки обеих порций удалены") { h.rawScalar(pending) == 0 }
        assertEquals(2, h.rawScalar("SELECT count(*) FROM net_tombstones"))
        assertEquals(2, notifications.get())
        assertTrue(fixture.requests.none { it.startsWith("/v1/media/") })
    }

    @Test
    fun refreshingManyChatsKeepsOnlyTheLastThirtyTwoHistoryChecks() = withConnection { fixture ->
        fixture.connect()
        val chatIds = 100L..132L
        val catalog = JSONArray(chatIds.map { id ->
            JSONObject().put("id", id).put("title", "Чат $id").put("lastSeq", 0)
                .put("unreadCount", 0).put("pinned", false)
        })
        awaitResult { callback ->
            fixture.harness.store.submit({ db ->
                fixture.sync.cache.mergeChats(db, JSONObject().put("chats", catalog))
            }, callback)
        }
        val delivered = LinkedBlockingQueue<Long>()
        fixture.sync.onDeltas = { batch ->
            batch.filter { it.reason == MessageChangeReason.HISTORY && it.chat.id in chatIds }
                .forEach { delivered.add(it.chat.id) }
        }
        fixture.respond = { request ->
            assertEquals("GET", request.method)
            assertEquals("128", request.url.queryParameter("limit"))
            200 to """{"messages":[],"hasOlder":false}"""
        }
        for (id in chatIds) {
            fixture.sync.visibleChat(id)
            assertEquals(id, delivered.poll(5, TimeUnit.SECONDS))
        }

        fixture.sync.visibleChat(101)
        fixture.sync.visibleChat(100)
        assertEquals(100L, delivered.poll(5, TimeUnit.SECONDS))
        assertEquals(1, fixture.requests.count { it == "/v1/chats/101/history" })
        assertEquals(2, fixture.requests.count { it == "/v1/chats/100/history" })
    }

    // Разрываем связь с ожидающей командой и открываем новую. Команда повторяется с прежним
    // номером, а ответ от старого соединения не меняет итоговую запись.
    @Test
    fun reconnectReplaysTheSavedCommandAndIgnoresAnOldConnectionReply() = withConnection { fixture ->
        val original = fixture.connect()
        val command = SendCommand(UUID.randomUUID().toString(), 2, text = "Сохрани этот запрос")
        val saved = awaitResult<MessageDelta> { fixture.repository.send(command, it) }.inserted.single()
        fixture.sync.kick()
        val sent = original.frame("send")
        val message = sent.getJSONArray("messages").getJSONObject(0)
        assertEquals(command.operationID, message.getString("clientId"))
        assertEquals(command.text, message.getString("text"))
        assertEquals(1, fixture.outboxCount())
        original.fail()
        fixture.expectStatus("Нет связи · сообщения сохранены")
        assertEquals(MessageDelivery.PENDING, fixture.history().rows.single().delivery)

        fixture.expectStatus("Подключение…")
        val replacement = fixture.takeSocket()
        replacement.open()
        assertEquals(0, replacement.frame("resume").getInt("cursor"))
        original.receive(accepted(sent, 101, command, "Поздний ответ"))
        replacement.receive(frame("resumed"))
        fixture.expectStatus("В сети")
        val replayed = replacement.frame("send")
        assertNotEquals(sent.getString("requestId"), replayed.getString("requestId"))
        assertEquals(message.toString(), replayed.getJSONArray("messages").getJSONObject(0).toString())
        assertEquals(MessageDelivery.PENDING, fixture.history().rows.single().delivery)

        replacement.receive(accepted(replayed, 102, command, command.text))
        fixture.awaitOutboxEmpty()
        val delivered = fixture.history().rows.single()
        assertEquals(saved.id, delivered.id)
        assertEquals(command.text, delivered.text)
        assertEquals(MessageDelivery.ACCEPTED, delivered.delivery)
        assertEquals(102, fixture.harness.rawScalar("SELECT serverID FROM net_messages"))
        assertEquals(1, fixture.requests.count { it == "/v1/bootstrap" })
        assertTrue("/v1/chats" in fixture.requests)
    }

    @Test
    fun unreadableOutboxPayloadKeepsTheRequestAndSendsItOnceRestored() = withConnection { fixture ->
        val original = fixture.connect()
        val command = SendCommand(UUID.randomUUID().toString(), 2, text = "Запрос при восстановлении очереди")
        val message = awaitResult<MessageDelta> { fixture.repository.send(command, it) }.inserted.single()
        val replacement = fixture.reconnectAfterUnreadableOperation("send", "{") {
            assertTrue(original.cancelled)
            val pending = fixture.history().rows.single()
            assertEquals(message.id, pending.id)
            assertEquals(MessageDelivery.PENDING, pending.delivery)
        }
        val sent = replacement.frame("send")
        val outgoing = sent.getJSONArray("messages").getJSONObject(0)
        assertEquals(command.operationID, outgoing.getString("clientId"))
        assertEquals(command.text, outgoing.getString("text"))
        replacement.receive(accepted(sent, 103, command, command.text))
        fixture.awaitOutboxEmpty()
        val accepted = fixture.history().rows.single()
        assertEquals(message.id, accepted.id)
        assertEquals(MessageDelivery.ACCEPTED, accepted.delivery)
    }

    @Test
    fun incompleteDeletionStaysQueuedUntilItsOriginalTargetIsRestored() = withConnection { fixture ->
        val original = fixture.connect()
        val command = SendCommand(UUID.randomUUID().toString(), 2, text = "Запрос для удаления")
        val message = awaitResult<MessageDelta> { fixture.repository.send(command, it) }.inserted.single()
        fixture.sync.kick()
        original.receive(accepted(original.frame("send"), 104, command, command.text))
        fixture.awaitOutboxEmpty()
        awaitResult<MessageDelta> { fixture.repository.delete(message.id, it) }

        val incomplete = JSONObject().put("targetClientId", command.operationID).toString()
        val retry = fixture.reconnectAfterUnreadableOperation("delete", incomplete) {
            assertTrue(original.cancelled)
            assertTrue(fixture.history().rows.isEmpty())
        }
        val deletion = retry.frame("delete")
        assertEquals(2, deletion.getInt("chatId"))
        assertEquals(104, deletion.getInt("messageId"))
        retry.receive(frame("deleted").put("requestId", deletion.getString("requestId")))
        fixture.awaitOutboxEmpty()
        assertTrue(fixture.history().rows.isEmpty())
        assertEquals(1, fixture.harness.rawScalar("SELECT count(*) FROM net_messages WHERE tombstone=1"))
    }

    @Test
    fun anOpenSocketWithoutASendReplyIsReplacedAndTheCommandIsRetried() = withConnection { fixture ->
        val socket = fixture.connect()
        val command = SendCommand(UUID.randomUUID().toString(), 2, text = "Ответ не пришёл")
        val saved = awaitResult<MessageDelta> { fixture.repository.send(command, it) }.inserted.single()
        fixture.sync.kick()
        val sent = socket.frame("send")
        assertFalse(socket.cancelled)
        assertEquals(1, fixture.outboxCount())

        fixture.expectStatus("Нет связи · сообщения сохранены", timeoutSeconds = 25)
        assertTrue(socket.cancelled)
        assertEquals(MessageDelivery.PENDING, fixture.history().rows.single().delivery)
        fixture.expectStatus("Подключение…")
        val replacement = fixture.takeSocket()
        replacement.open()
        replacement.frame("resume")
        replacement.receive(frame("resumed"))
        fixture.expectStatus("В сети")
        val replayed = replacement.frame("send")
        assertNotEquals(sent.getString("requestId"), replayed.getString("requestId"))
        assertEquals(sent.getJSONArray("messages").toString(), replayed.getJSONArray("messages").toString())

        replacement.receive(accepted(replayed, 103, command, command.text))
        fixture.awaitOutboxEmpty()
        val delivered = fixture.history().rows.single()
        assertEquals(saved.id, delivered.id)
        assertEquals(MessageDelivery.ACCEPTED, delivered.delivery)
    }

    @Test
    fun socketLimitsCloseTheOldChannelAndResumeFromTheSavedCursor() = withConnection { fixture ->
        val initial = fixture.connect()
        val command = SendCommand(UUID.randomUUID().toString(), 2, text = "Сохранённая строка")
        val incoming = wireMessage(51, command, command.text).put("senderId", 3)
        initial.receive(frame("events").put("cursor", 1).put(
            "events", JSONArray().put(JSONObject().put("seq", 1L).put("chatId", 2L)
                .put("kind", "message").put("message", incoming)),
        ))
        assertEquals(1, initial.frame("applied").getInt("cursor"))
        initial.receive(frame("applied").put("cursor", 1))
        fixture.waitUntil("Первая строка записана и отмечена") { fixture.cursors() == (1L to "1") }
        val unknownEvent = frame("coverage.unknown")
        initial.receive(unknownEvent.put("detail", "я".repeat(33_000)))
        assertTrue("Ограничение проверяет размер в байтах", initial.cancelled)
        initial.fail()
        fixture.expectStatus("Нет связи · сообщения сохранены")
        fixture.expectStatus("Подключение…")
        val replacement = fixture.takeSocket()
        replacement.open()
        assertEquals(1, replacement.frame("resume").getInt("cursor"))
        replacement.receive(frame("resumed"))
        fixture.expectStatus("В сети")

        val workerStopped = CountDownLatch(1)
        val resumeWorker = CountDownLatch(1)
        fixture.sync.worker.post {
            workerStopped.countDown()
            check(resumeWorker.await(5, TimeUnit.SECONDS))
        }
        try {
            assertTrue(workerStopped.await(3, TimeUnit.SECONDS))
            repeat(64) { replacement.receive(frame("coverage.unknown")) }
            assertFalse(replacement.cancelled)
            replacement.receive(frame("coverage.unknown"))
            assertTrue("В очереди не может быть больше 64 непрочитанных пакетов", replacement.cancelled)
            replacement.fail()
        } finally {
            resumeWorker.countDown()
        }
        fixture.expectStatus("Нет связи · сообщения сохранены")
        fixture.expectStatus("Подключение…")
        val finalSocket = fixture.takeSocket()
        val cancelledBeforeLateOpen = replacement.cancellations.get()
        replacement.open()
        fixture.waitUntil("Позднее открытие относится к уже закрытому каналу") {
            replacement.cancellations.get() > cancelledBeforeLateOpen
        }
        finalSocket.open()
        assertEquals(1, finalSocket.frame("resume").getInt("cursor"))
        finalSocket.receive(frame("resumed"))
        fixture.expectStatus("В сети")
        assertFalse(finalSocket.cancelled)
        assertEquals(command.text, fixture.history().rows.single().text)
    }

    @Test
    fun aDeleteIsReplayedWithItsStoredIdUntilTheServerConfirmsIt() = withConnection { fixture ->
        val opening = fixture.connect()
        val command = SendCommand(UUID.randomUUID().toString(), 2, text = "Временный запрос")
        val saved = awaitResult<MessageDelta> { fixture.repository.send(command, it) }.inserted.single()
        fixture.sync.kick()
        opening.receive(accepted(opening.frame("send"), 82, command, command.text))
        fixture.awaitOutboxEmpty()

        val removed = awaitResult<MessageDelta> { fixture.repository.delete(saved.id, it) }
        assertEquals(listOf(saved.id), removed.removedIDs)
        fixture.sync.kick()
        val first = opening.frame("delete")
        val requestId = first.getString("requestId")
        assertEquals(82, first.getInt("messageId"))
        assertEquals(2, first.getInt("chatId"))
        assertEquals(1, fixture.outboxCount())
        opening.fail()

        fixture.expectStatus("Нет связи · сообщения сохранены")
        fixture.expectStatus("Подключение…")
        val replacement = fixture.takeSocket()
        replacement.open()
        replacement.frame("resume")
        replacement.receive(frame("resumed"))
        fixture.expectStatus("В сети")
        val replay = replacement.frame("delete")
        assertEquals(requestId, replay.getString("requestId"))
        assertEquals(82, replay.getInt("messageId"))
        assertEquals(1, fixture.outboxCount())

        replacement.receive(frame("deleted").put("requestId", requestId))
        fixture.awaitOutboxEmpty()
        assertTrue(fixture.history().rows.isEmpty())
        assertEquals(1, fixture.harness.rawScalar("SELECT count(*) FROM net_messages WHERE tombstone=1"))
    }

    @Test
    fun aPermanentUploadRefusalKeepsTheErrorAndLetsTheNextCommandSend() = withConnection { fixture ->
        val socket = fixture.connect()
        val attachmentId = UUID.randomUUID().toString()
        val file = File(fixture.harness.store.root, "media/rejected.gif")
        checkNotNull(file.parentFile).mkdirs()
        TestFixtures.assets.open("media/gif-00.gif").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val refusedPath = "/v1/uploads/$attachmentId"
        fixture.respond = { request ->
            assertEquals(refusedPath, request.url.encodedPath)
            422 to """{"error":{"code":"upload_refused"}}"""
        }
        val refused = SendCommand(
            UUID.randomUUID().toString(), 2, kind = MessageKind.GIF,
            attachments = listOf(AttachmentDraft(attachmentId, file.name, "image/gif", file.length())),
        )
        val saved = awaitResult<MessageDelta> { fixture.repository.send(refused, it) }.inserted.single()
        fixture.sync.kick()
        fixture.expectStatus("Не отправлено: upload_refused")

        assertEquals(saved.id, fixture.history().rows.single().id)
        assertEquals(MessageDelivery.FAILED, fixture.history().rows.single().delivery)
        assertEquals(1, fixture.harness.rawScalar("SELECT count(*) FROM net_outbox WHERE errorCode='upload_refused'"))
        assertEquals(0, fixture.harness.rawScalar("SELECT count(*) FROM net_uploads"))

        val next = SendCommand(UUID.randomUUID().toString(), 2, text = "Следующая отправка")
        awaitResult<MessageDelta> { fixture.repository.send(next, it) }
        fixture.sync.kick()
        val allowed = socket.frame("send")
        val messages = allowed.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals(next.operationID, messages.getJSONObject(0).getString("clientId"))
        socket.receive(accepted(allowed, 83, next, next.text))
        fixture.waitUntil("Следующая строка подтверждена") {
            fixture.history().rows.any { it.text == next.text && it.delivery == MessageDelivery.ACCEPTED }
        }
        assertEquals(1, fixture.outboxCount())
        assertEquals(1, fixture.requests.count { it == refusedPath })
    }

    // Передаём событие и сверяем запись в истории, затем подаём неверное и верное подтверждения.
    // Новое соединение должно продолжить с подтверждённого номера без повторной строки.
    @Test
    fun cursorMovesOnlyAfterEventsAreSavedAndTheServerConfirmsTheirReceipt() = withConnection { fixture ->
        val socket = fixture.connect()
        val command = SendCommand(UUID.randomUUID().toString(), 2, text = "Ответ в истории")
        val incoming = wireMessage(51, command, command.text).put("senderId", 3)
        val packet = frame("events").put("cursor", 1).put(
            "events",
            JSONArray().put(JSONObject().put("seq", 1L).put("chatId", 2L)
                .put("kind", "message").put("message", incoming)),
        )
        socket.receive(packet)
        assertEquals(1, socket.frame("applied").getInt("cursor"))
        assertEquals(command.text, fixture.history().rows.single().text)
        assertEquals(1L to "0", fixture.cursors())

        socket.receive(frame("applied").put("cursor", 50))
        socket.receive(frame("applied").put("cursor", 1))
        fixture.waitUntil("Сервер подтвердил курсор 1") { fixture.cursors() == (1L to "1") }
        socket.receive(packet)
        socket.serverClosing()
        fixture.expectStatus("Нет связи · сообщения сохранены")
        fixture.expectStatus("Подключение…")
        val replacement = fixture.takeSocket()
        replacement.open()
        assertEquals(1, replacement.frame("resume").getInt("cursor"))
        assertEquals("Ответ в истории", fixture.history().rows.single().text)
    }

    @Test
    fun rejectedSendFailsOnlyTheMatchingOperation() = withConnection { fixture ->
        val socket = fixture.connect()
        val first = SendCommand(UUID.randomUUID().toString(), 2, text = "Отклонённый запрос")
        val second = SendCommand(UUID.randomUUID().toString(), 2, text = "Следующий запрос")
        awaitResult<MessageDelta> { fixture.repository.send(first, it) }
        fixture.sync.kick()
        val sent = socket.frame("send")
        awaitResult<MessageDelta> { fixture.repository.send(second, it) }
        fixture.sync.kick()
        socket.receive(frame("error").put("code", "invalid_request")
            .put("requestId", sent.getString("requestId")))
        fixture.expectStatus("Не отправлено: invalid_request")
        val next = socket.frame("send")
        assertEquals(second.operationID, next.getJSONArray("messages").getJSONObject(0).getString("clientId"))
        socket.receive(accepted(next, 210, second, second.text))
        fixture.waitUntil("Подтверждён следующий запрос") {
            fixture.history().rows.any { it.text == second.text && it.delivery == MessageDelivery.ACCEPTED }
        }
        assertEquals(1, fixture.outboxCount())
        assertEquals(1, fixture.harness.rawScalar("SELECT count(*) FROM net_outbox WHERE errorCode='invalid_request'"))

        val rows = fixture.history().rows.associateBy { it.text }
        assertEquals(MessageDelivery.FAILED, rows.getValue(first.text).delivery)
        assertEquals(MessageDelivery.ACCEPTED, rows.getValue(second.text).delivery)
        assertTrue(fixture.sync.isConnected)
    }

    @Test
    fun incomingMessageFetchesItsUnknownChatBeforeAcknowledging() = withConnection { fixture ->
        val socket = fixture.connect()
        val previousFetches = fixture.requests.count { it == "/v1/chats" }
        val chatID = 83L
        val title = "Чат с другого устройства"
        val newChat = JSONObject().put("id", chatID).put("title", title).put("lastSeq", 1)
            .put("unreadCount", 0).put("pinned", false)
        fixture.chatDirectoryResponse = JSONObject().put("chats", JSONArray().put(newChat)).toString()
        val request = SendCommand(UUID.randomUUID().toString(), chatID, text = "Первое сообщение")
        val message = wireMessage(310, request, request.text).put("senderId", 3).put("chatId", chatID)
        val events = JSONArray().put(JSONObject()
            .put("seq", 1).put("chatId", chatID).put("kind", "message").put("message", message))
        socket.receive(frame("events").put("cursor", 1).put("events", events))

        assertEquals(1, socket.frame("applied").getInt("cursor"))
        val saved = awaitResult<HistoryPage> { fixture.repository.history(PageRequest(chatID), it) }
        val matching = awaitResult<List<ChatSummary>> { fixture.repository.chats(title, it) }
        assertEquals(chatID, matching.single().id)
        assertEquals(title, matching.single().title)
        assertEquals(request.text, saved.rows.single().text)
        assertEquals(previousFetches + 1, fixture.requests.count { it == "/v1/chats" })
        assertEquals(1L to "0", fixture.cursors())
    }

    @Test
    fun accountRemovalAcceptsAnAlreadyRemovedAccountButRejectsTheWrongPassword() =
        withConnection { fixture ->
            var count = 0
            fixture.respond = { request ->
                assertEquals("DELETE", request.method)
                assertEquals("/v1/kandinsky/account", request.url.encodedPath)
                val body = okio.Buffer()
                checkNotNull(request.body).writeTo(body)
                val password = JSONObject(body.readUtf8()).getString("password")
                when (++count) {
                    1 -> {
                        assertEquals("Неверный пароль", password)
                        403 to """{"error":{"code":"invalid_password"}}"""
                    }
                    2 -> {
                        assertEquals("Проверочный пароль", password)
                        401 to """{"error":{"code":"account_deleted"}}"""
                    }
                    else -> error("Отправлен лишний запрос удаления")
                }
            }
            val rejected = runCatching {
                awaitResult<Unit> { callback ->
                    fixture.sync.eraseStudioAccount("Неверный пароль", callback)
                    Cancellation {}
                }
            }.exceptionOrNull() as? NetworkFailure
            assertEquals("invalid_password", checkNotNull(rejected).code)
            awaitResult<Unit> { callback ->
                fixture.sync.eraseStudioAccount("Проверочный пароль", callback)
                Cancellation {}
            }
            assertEquals(2, count)
        }

    @Test
    fun repeatedUserActionsSendOneActivityUpdateAndHandleARevokedSession() = withConnection { fixture ->
        val expired = LinkedBlockingQueue<Unit>()
        fixture.sync.onSessionExpired = { expired.add(Unit) }
        fixture.sync.onAccountErased = { fail("Отзыв входа не удаляет аккаунт") }
        fixture.respond = { request ->
            assertEquals("/v1/kandinsky/activity", request.url.encodedPath)
            assertEquals("POST", request.method)
            401 to """{"error":{"code":"invalid_session"}}"""
        }
        assertTrue(fixture.requests.isEmpty())
        repeat(3) { fixture.sync.userInteraction() }
        assertNotNull(expired.poll(5, TimeUnit.SECONDS))
        fixture.sync.userInteraction()
        assertEquals(listOf("/v1/kandinsky/activity"), fixture.requests.toList())
        assertFalse(fixture.sync.isConnected)
    }

    // Дважды передаём сообщение об истёкшем сеансе. Соединение закрывается и сигнал об окончании
    // приходит один раз.
    @Test
    fun terminalSessionRejectionClosesSocketAndNotifiesOnce() = withConnection { fixture ->
        val socket = fixture.connect()
        val expired = LinkedBlockingQueue<Unit>()
        fixture.sync.onSessionExpired = { expired.add(Unit) }
        val denied = frame("error").put("code", "invalid_session")
        socket.receive(denied)
        socket.receive(denied)
        checkNotNull(expired.poll(5, TimeUnit.SECONDS)) { "Сеанс должен завершиться" }
        assertTrue(socket.cancelled)
        assertFalse(fixture.sync.isConnected)
        assertEquals(0, expired.size)
    }

    // Возвращаем признак удалённого аккаунта на первом запросе. Проверяем сигнал об удалении и
    // отсутствие попытки открыть постоянное соединение.
    @Test
    fun deletedAccountDuringBootstrapNeverOpensAConnection() = withConnection { fixture ->
        fixture.httpFailure = 401 to "account_deleted"
        val erased = LinkedBlockingQueue<Unit>()
        fixture.sync.onAccountErased = { erased.add(Unit) }
        fixture.sync.foreground(true)
        fixture.expectStatus("Подключение…")
        checkNotNull(erased.poll(5, TimeUnit.SECONDS)) { "Удаление аккаунта должно быть замечено" }
        assertTrue(fixture.sockets.isEmpty())
        assertEquals(listOf("/v1/bootstrap"), fixture.requests.toList())
        assertFalse(fixture.sync.isConnected)
    }

    @Test
    fun aTemporaryBootstrapFailureAndAnOrderlySocketCloseCanRecover() = withConnection { fixture ->
        val before = fixture.cursors()
        fixture.httpFailure = 503 to "temporary_unavailable"
        fixture.sync.foreground(true)
        fixture.expectStatus("Подключение…")
        fixture.expectStatus("Нет связи · сообщения сохранены")
        assertTrue(fixture.sockets.isEmpty())
        assertEquals(before, fixture.cursors())

        fixture.httpFailure = null
        fixture.expectStatus("Подключение…")
        val ready = fixture.takeSocket()
        ready.open()
        assertEquals(0, ready.frame("resume").getInt("cursor"))
        ready.receive(frame("resumed"))
        fixture.expectStatus("В сети")
        assertEquals(2, fixture.requests.count { it == "/v1/bootstrap" })

        ready.serverClosed()
        fixture.expectStatus("Нет связи · сообщения сохранены")
        fixture.expectStatus("Подключение…")
        val next = fixture.takeSocket()
        next.open()
        assertEquals(0, next.frame("resume").getInt("cursor"))
        next.receive(frame("resumed"))
        fixture.expectStatus("В сети")
        assertEquals(2, fixture.requests.count { it == "/v1/bootstrap" })
        assertTrue("/v1/chats" in fixture.requests)
    }

    // Теряем первый ответ на повтор задания и отправляем запрос снова. Он использует сохранённую
    // операцию, а отдельная повторная попытка результата передаёт номер места и ожидаемого
    // запуска.
    @Test
    fun generationRetryKeepsTheSavedOperationUntilItsReplyArrives() = withConnection { fixture ->
        val socket = fixture.connect()
        val command = SendCommand(UUID.randomUUID().toString(), 2, text = "")
        val failed = wireMessage(52, command, "Не удалось создать").put("senderId", 0).put(
            "metadata",
            JSONObject().put("kandinskyStatus", "failed").put("kandinskyModel", "image"),
        )
        socket.receive(frame("events").put("cursor", 1).put(
            "events",
            JSONArray().put(JSONObject().put("seq", 1L).put("chatId", 2L)
                .put("kind", "message").put("message", failed)),
        ))
        assertEquals(1, socket.frame("applied").getInt("cursor"))
        val row = fixture.history().rows.single()
        assertEquals(StudioGeneration.Stage.FAILED, row.studioGeneration?.stage)
        assertNull(fixture.activeGeneration())

        val requestBodies = LinkedBlockingQueue<String>()
        val actionPath = "/v1/kandinsky/threads/2/generations/52/action"
        val running = JSONObject(failed.toString()).put("revision", 2).put("text", "Генерация").put(
            "metadata",
            JSONObject().put("kandinskyStatus", "running").put("kandinskyModel", "image")
                .put("kandinskyProgress", 21),
        )
        val reply = JSONObject().put("messages", JSONArray().put(running)).toString()
        fixture.respond = { request ->
            check(request.url.encodedPath == actionPath)
            check(request.method == "POST")
            val buffer = Buffer()
            requireNotNull(request.body).writeTo(buffer)
            requestBodies.add(buffer.readUtf8())
            if (requestBodies.size == 1) throw IOException("Проверочная потеря ответа")
            200 to reply
        }
        val original = UUID.randomUUID()
        // Запрашиваем повтор для всей генерации или одного результата и получаем итог
        // асинхронного вызова. Ошибка первого запроса становится значением для последующих
        // проверок.
        fun retry(operation: UUID, slot: StudioGeneration.Slot? = null): Result<Unit> = runCatching {
            awaitNetwork<Unit> {
                fixture.sync.generationAction(2, row.id, StudioGenerationAction.RETRY, operation, slot, it)
            }
        }
        assertTrue(retry(original).exceptionOrNull() is IOException)
        assertEquals(1, fixture.harness.rawScalar("SELECT count(*) FROM net_state WHERE key LIKE 'generationRetry:%'"))
        assertTrue(retry(UUID.randomUUID()).isSuccess)
        assertEquals(0, fixture.harness.rawScalar("SELECT count(*) FROM net_state WHERE key LIKE 'generationRetry:%'"))
        assertEquals(row.id, fixture.activeGeneration())
        assertEquals(21, fixture.history().rows.single().studioGeneration?.progress)

        val slotID = UUID.randomUUID().toString()
        val previousRun = UUID.randomUUID().toString()
        val slot = StudioGeneration.Slot(
            slotID, StudioGeneration.Stage.FAILED, null, null, runId = previousRun,
        )
        val slotRetry = UUID.randomUUID()
        assertTrue(retry(slotRetry, slot).isSuccess)
        assertEquals(3, requestBodies.size)
        val (first, second, third) = requestBodies.map(::JSONObject)
        assertEquals("retry", first.getString("action"))
        assertEquals(original.toString(), first.getString("operation"))
        assertEquals(first.toString(), second.toString())
        assertEquals("retry_slot", third.getString("action"))
        assertEquals(slotRetry.toString(), third.getString("operation"))
        assertEquals(slotID, third.getString("slot"))
        assertEquals(previousRun, third.getString("expected_run"))
    }

    @Test
    fun historyAccessibilityRetriesOnlyTheSelectedGenerationSlot() = withConnection { fixture ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.packageName.endsWith(".cleanup"))
        assumeTrue(NetworkCredentials(context).load() == null)
        val slot = UUID.randomUUID().toString()
        val blockedSlot = UUID.randomUUID().toString()
        val oldRun = UUID.randomUUID().toString()
        val newRun = UUID.randomUUID().toString()
        val target = "/v1/kandinsky/threads/2/generations/52/action"
        val command = SendCommand(UUID.randomUUID().toString(), 2, text = "")
        fun result(revision: Int, status: String, run: String): JSONObject {
            val text = if (status == "failed") "Не удалось создать" else "Генерация"
            return wireMessage(52, command, text).put("senderId", 0).put("revision", revision).put(
                "metadata", JSONObject().put("kandinskyStatus", status).put("kandinskyModel", "image")
                    .put("kandinskySlots", JSONArray().put(
                        JSONObject().put("id", slot).put("status", status).put("runId", run),
                    ).put(JSONObject().put("id", blockedSlot).put("status", "failed").put("moderated", true))),
            )
        }
        awaitResult { reply ->
            fixture.harness.store.submit({ database ->
                fixture.sync.cache.generationRows(database, 2, JSONObject().put("messages",
                    JSONArray().put(result(1, "failed", oldRun))))
            }, reply)
        }
        val original = fixture.history().rows.single()
        assertEquals(oldRun, original.studioGeneration?.slots?.single { it.id == slot }?.runId)
        val requests = LinkedBlockingQueue<JSONObject>()
        fixture.respond = { request ->
            check(request.url.encodedPath == target && request.method == "POST")
            val body = Buffer()
            requireNotNull(request.body).writeTo(body)
            requests.add(JSONObject(body.readUtf8()))
            200 to JSONObject().put("messages", JSONArray().put(result(2, "running", newRun))).toString()
        }
        val launch = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val activity = instrumentation.startActivitySync(launch)
        val media = MediaStore(fixture.harness.store)
        val admission = MediaAdmission()
        val state = ConversationState(fixture.repository)
        val saved = CountDownLatch(1)
        lateinit var playback: MediaCoordinator
        lateinit var chrome: ConversationChrome
        try {
            instrumentation.runOnMainSync {
                playback = MediaCoordinator(admission, media)
                chrome = ConversationChrome(activity, 2, state, ConversationServices(fixture.sync, media, admission, playback), {})
                activity.setContentView(chrome)
                fixture.sync.onDeltas = { deltas ->
                    deltas.forEach(chrome::apply)
                    saved.countDown()
                }
            }
            awaitNetwork<Unit> { reply -> instrumentation.runOnMainSync { state.open(2, reply) } }
            awaitNetwork<Unit> { reply ->
                instrumentation.runOnMainSync { chrome.openHistory(fixture.repository, null, reply) }
            }
            instrumentation.runOnMainSync {
                val history = chrome.allViews.filterIsInstance<ChatScrollHost>().single()
                history.layout(0, 0, 390, 844)
                val layout = PreparedText(original, history.layoutKey(original))
                history.cache.insert(layout)
                val nodes = mutableListOf<NativeAction>()
                history.appendRowAccessibility(original, layout, 16f, 30f, nodes)
                val retry = nodes.single { it.id == "generation.slot.${original.id}.$slot" }
                assertTrue(retry.button)
                assertEquals("Повторить генерацию", retry.label)
                val controls = NativeAccessibility(history, "history", { nodes })
                val blocked = nodes.single { it.id == "generation.slot.${original.id}.$blockedSlot" }
                assertFalse(blocked.button)
                assertFalse(controls.performAction(blocked.key, AccessibilityNodeInfo.ACTION_CLICK, null))
                val cell = checkNotNull(layout.generationCard).retryCells.single { it.slot.id == slot }
                val top = checkNotNull(history.index.top(original.id)) - history.offset
                val route = checkNotNull(history.onStudioRetrySlot)
                val activated = mutableListOf<String>()
                history.onStudioRetrySlot = { message, selected ->
                    activated += selected.id
                    route(message, selected)
                }
                assertTrue(history.performRowTap(
                    history.rowLeft(original, layout) + cell.frame.centerX(),
                    top.toFloat() + cell.frame.centerY(),
                ))
                assertEquals(listOf(slot), activated)
                assertTrue(controls.performAction(retry.key, AccessibilityNodeInfo.ACTION_CLICK, null))
                assertEquals(listOf(slot, slot), activated)
            }
            val sent = checkNotNull(requests.poll(5, TimeUnit.SECONDS))
            assertEquals("retry_slot", sent.getString("action"))
            assertEquals(slot, sent.getString("slot"))
            assertEquals(oldRun, sent.getString("expected_run"))
            UUID.fromString(sent.getString("operation"))
            assertTrue("Новый запуск должен сохраниться", saved.await(5, TimeUnit.SECONDS))
            assertEquals(newRun, fixture.history().rows.single().studioGeneration?.slots?.single { it.id == slot }?.runId)
            assertTrue(requests.isEmpty())
        } finally {
            instrumentation.runOnMainSync {
                fixture.sync.onDeltas = null
                state.close()
                activity.finish()
                playback.close()
                media.close()
                admission.close()
            }
        }
    }

    // Сверяем удаление результата и запроса, а также жалобу с исходной серверной записью. Чужой
    // чат или неизвестный файл должны остановить действие до запроса.
    @Test
    fun generationActionsKeepTheLocalMessageAndTheServerTargetAligned() = withConnection { fixture ->
        val command = SendCommand(UUID.randomUUID().toString(), 2, text = "")
        val message = wireMessage(53, command, "").put("senderId", 0)
        awaitResult { callback ->
            fixture.harness.store.submit({ db ->
                fixture.sync.cache.generationRows(db, 2, JSONObject().put("messages", JSONArray().put(message)))
            }, callback)
        }
        val row = fixture.history().rows.single()
        val requests = LinkedBlockingQueue<Request>()
        fixture.respond = { request ->
            requests.add(request)
            204 to ""
        }
        // Просим удалить содержимое выбранного сообщения с номером чата и при наличии номером
        // файла. Сохраняем результат вызова для проверки отказа или нужного обращения.
        fun delete(chat: Long, blob: String?): Result<Unit> = runCatching {
            awaitNetwork<Unit> { fixture.sync.deleteGenerationContent(chat, row.id, blob, it) }
        }
        // Просим отправить жалобу на сохранённую генерацию из указанного чата. Сверяем, дошла ли
        // нужная причина и правильный номер до проверочного обработчика.
        fun report(chat: Long): Result<Unit> = runCatching {
            awaitNetwork<Unit> { fixture.sync.reportGeneration(chat, row.id, StudioReportReason.INAPPROPRIATE, it) }
        }

        assertEquals(RepoError.INVALID_INPUT, (delete(2, "wrong").exceptionOrNull() as RepoException).reason)
        assertEquals(RepoError.NOT_FOUND, (delete(12, null).exceptionOrNull() as RepoException).reason)
        assertEquals(RepoError.NOT_FOUND, (report(12).exceptionOrNull() as RepoException).reason)
        assertTrue(requests.isEmpty())
        val blob = "f".repeat(32)
        assertTrue(delete(2, blob).isSuccess)
        assertTrue(delete(2, null).isSuccess)
        assertTrue(report(2).isSuccess)

        val path = "/v1/kandinsky/threads/2/generations/53"
        val (result, prompt, complaint) = requests.toList()
        assertEquals(listOf("DELETE", "DELETE", "POST"), requests.map { it.method })
        assertEquals(listOf(path, path, "$path/report"), requests.map { it.url.encodedPath })
        assertEquals("target=result&blob=$blob", result.url.encodedQuery)
        assertEquals("target=prompt", prompt.url.encodedQuery)
        val body = Buffer()
        requireNotNull(complaint.body).writeTo(body)
        assertEquals("inappropriate", JSONObject(body.readUtf8()).getString("reason"))
    }

    @Test
    fun sourcePhotoKeepsItsBytesAndIdentifierAcrossLossPauseAndRefusal() =
        withConnection { fixture ->
            val instrument = InstrumentationRegistry.getInstrumentation()
            val context = instrument.targetContext
            val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            val activity = instrument.startActivitySync(
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
            val state = ConversationState(fixture.repository)
            val media = MediaStore(fixture.harness.store)
            val admission = MediaAdmission()
            val uploads = AtomicInteger()
            val originalBlob = "3".repeat(32)
            val previewBlob = "4".repeat(32)
            val attempts = LinkedBlockingQueue<Boolean>()
            val errors = LinkedBlockingQueue<String>()
            val transferProgress = LinkedBlockingQueue<Double>()
            val pausedRequest = CountDownLatch(1)
            val releasePausedRequest = CountDownLatch(1)
            lateinit var coordinator: ChatAttachmentCoordinator
            var closeAttachments: (() -> Unit)? = null
            try {
                awaitNetwork<Unit> { callback -> instrument.runOnMainSync { state.open(2, callback) } }
                val photo = awaitNetwork<AttachmentDraft> { callback ->
                    instrument.runOnMainSync {
                        media.stageStudioPhoto({ TestFixtures.assets.open("media/photo-000.jpg") }, callback)
                    }
                }
                val originalPath = "/v1/uploads/${photo.id.lowercase()}"
                val photoFile = media.fileURL(photo.relativePath)
                val bytes = photoFile.readBytes()
                val draftText = "Фото для продолжения после обрыва"
                fixture.respond = sourceUpload@{ request ->
                    assertEquals("PUT", request.method)
                    val body = Buffer()
                    requireNotNull(request.body).writeTo(body)
                    val blob = if (request.url.encodedPath == originalPath) {
                        assertEquals("2", request.header("x-kandinsky-chat"))
                        assertTrue(bytes.contentEquals(body.readByteArray()))
                        when (val attempt = uploads.incrementAndGet()) {
                            1 -> throw SocketException("Связь оборвалась")
                            2 -> {
                                pausedRequest.countDown()
                                assertTrue(releasePausedRequest.await(8, TimeUnit.SECONDS))
                                throw IOException("Передача остановлена")
                            }
                            3 -> return@sourceUpload 422 to """{"error":{"code":"upload_refused"}}"""
                            4 -> Unit
                            else -> fail("Лишняя попытка передачи $attempt")
                        }
                        originalBlob
                    } else {
                        assertEquals(originalBlob, request.header("x-kandinsky-parent"))
                        assertTrue(body.size > 0)
                        previewBlob
                    }
                    201 to JSONObject().put("blobId", blob).toString()
                }
                instrument.runOnMainSync {
                    state.editDraft(state.draft.copy(text = draftText, attachmentDrafts = listOf(photo)))
                    coordinator = ChatAttachmentCoordinator(
                        activity = activity,
                        picker = activity as AttachmentPicker,
                        network = fixture.sync,
                        media = media,
                        admission = admission,
                        state = state,
                        isActive = { !activity.isFinishing },
                        options = { KandinskyOptions(model = "video") },
                        selectOptions = {},
                        showError = { errors.add(it) },
                        previewVideo = { _, _, _ -> fail("Снимок не открывает просмотр видео") },
                        refresh = { if (!coordinator.isBusy) attempts.add(true) },
                        refreshProgress = { transferProgress.add(coordinator.progress) },
                    )
                    closeAttachments = { coordinator.close(discardDraft = false) }
                    coordinator.resumePendingUpload()
                }
                assertTrue(attempts.poll(8, TimeUnit.SECONDS) == true)
                assertEquals(1, uploads.get())
                assertEquals(0, fixture.harness.rawScalar("SELECT count(*) FROM net_uploads"))
                val saved = awaitResult { fixture.repository.draft(2, it) }
                assertEquals(draftText, saved.text)
                assertEquals(listOf(photo.id), saved.attachmentDrafts.map { it.id })
                assertTrue(bytes.contentEquals(photoFile.readBytes()))

                instrument.runOnMainSync {
                    coordinator.resumePendingUpload()
                    assertFalse(coordinator.isBusy)
                    coordinator.resumePendingUpload(reconnected = true)
                }
                assertTrue(pausedRequest.await(8, TimeUnit.SECONDS))
                instrument.runOnMainSync {
                    assertTrue(coordinator.isUploading)
                    coordinator.pausePendingUpload()
                    assertFalse(coordinator.isUploading)
                }
                assertTrue(attempts.poll(8, TimeUnit.SECONDS) == true)
                assertEquals(2, uploads.get())
                releasePausedRequest.countDown()
                assertTrue(errors.isEmpty())
                assertEquals(0, fixture.harness.rawScalar("SELECT count(*) FROM net_uploads"))
                val paused = awaitResult { fixture.repository.draft(2, it) }
                assertEquals(listOf(photo.id), paused.attachmentDrafts.map { it.id })
                assertTrue(bytes.contentEquals(photoFile.readBytes()))

                instrument.runOnMainSync { coordinator.resumePendingUpload(reconnected = true) }
                assertTrue(attempts.poll(8, TimeUnit.SECONDS) == true)
                assertEquals(3, uploads.get())
                assertEquals("Не удалось загрузить фото", errors.poll())
                assertEquals(0, fixture.harness.rawScalar("SELECT count(*) FROM net_uploads"))
                val refused = awaitResult { fixture.repository.draft(2, it) }
                assertEquals(draftText, refused.text)
                assertEquals(listOf(photo.id), refused.attachmentDrafts.map { it.id })
                assertTrue(bytes.contentEquals(photoFile.readBytes()))

                instrument.runOnMainSync { coordinator.resumePendingUpload(reconnected = true) }
                assertTrue(attempts.poll(8, TimeUnit.SECONDS) == true)
                assertEquals(4, uploads.get())
                assertEquals(List(4) { originalPath }, fixture.requests.filter { it == originalPath })
                assertEquals(5, fixture.requests.size)
                assertEquals(1, fixture.harness.rawScalar(
                    "SELECT count(*) FROM net_uploads WHERE blobID='$originalBlob' AND previewBlobID='$previewBlob'",
                ))
                assertTrue(transferProgress.any { it > 0 })
                assertTrue(errors.isEmpty())
                val confirmed = awaitResult { fixture.repository.draft(2, it) }
                assertEquals(draftText, confirmed.text)
                assertEquals(listOf(photo.id), confirmed.attachmentDrafts.map { it.id })
            } finally {
                releasePausedRequest.countDown()
                instrument.runOnMainSync {
                    closeAttachments?.invoke()
                    state.close()
                    media.close()
                    admission.close()
                    activity.finish()
                }
            }
        }

    // Открываем недавнюю фотографию и используем её как пример для черновика. Подтверждённый
    // исходник повторно используется без второй загрузки, а удаление из формы очищает выбор.
    @Test
    fun recentPhotoBecomesTheDraftReferenceWithoutASecondUpload() = withConnection { fixture ->
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val blob = "3".repeat(32)
        val corruptBlob = "5".repeat(32)
        val videoBlob = "7".repeat(32)
        val photo = TestFixtures.assets.open("media/photo-000.jpg").use { it.readBytes() }
        val corrupt = "Непригодные байты изображения".toByteArray()
        val video = TestFixtures.assets.open("media/video-01.mp4").use { it.readBytes() }
        val recent = JSONObject().put(
            "uploads",
            JSONArray().put(
                JSONObject().put("blobId", blob).put("mime", "image/jpeg").put("byteSize", photo.size),
            ).put(
                JSONObject().put("blobId", corruptBlob).put("mime", "image/jpeg")
                    .put("byteSize", corrupt.size),
            ).put(
                JSONObject().put("blobId", videoBlob).put("mime", "video/mp4").put("byteSize", video.size),
            ),
        )
        val recentPath = "/v1/kandinsky/uploads/recent"
        val sourcePath = "/v1/kandinsky/threads/2/sources/$blob"
        val assetPath = "/v1/media/$blob"
        val corruptSource = "/v1/kandinsky/threads/2/sources/$corruptBlob"
        val corruptAsset = "/v1/media/$corruptBlob"
        val videoSource = "/v1/kandinsky/threads/2/sources/$videoBlob"
        val videoAsset = "/v1/media/$videoBlob"
        val modelsPath = "/v1/kandinsky/models"
        fixture.mediaReplies = mapOf(assetPath to photo, corruptAsset to corrupt, videoAsset to video)
        fixture.respond = { request ->
            when (request.url.encodedPath) {
                recentPath -> {
                    check(request.method == "GET")
                    200 to recent.toString()
                }
                sourcePath, corruptSource, videoSource -> {
                    check(request.method == "POST")
                    204 to ""
                }
                modelsPath -> {
                    check(request.method == "GET")
                    200 to """{"models":[{"id":"video_sr","name":"Улучшение видео","description":"Увеличение размера"}]}"""
                }
                else -> error("Неожиданный запрос ${request.url.encodedPath}")
            }
        }

        val device = UiDevice.getInstance(instrument)
        val configurator = Configurator.getInstance()
        val previousIdleTimeout = configurator.waitForIdleTimeout
        val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        val activity = instrument.startActivitySync(
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        val media = MediaStore(fixture.harness.store)
        val admission = MediaAdmission()
        val state = ConversationState(fixture.repository)
        lateinit var chrome: ConversationChrome
        var closePlayback: (() -> Unit)? = null
        // Ждём включённую кнопку по постоянной метке и нажимаем её. Если кнопка не появилась,
        // проверка покажет, на каком действии остановился экран.
        fun click(id: String) {
            val item = checkNotNull(device.wait(Until.findObject(By.res(id).enabled(true)), 5_000)) {
                "Не появился доступный элемент $id. Запросы: ${fixture.requests}"
            }
            item.click()
        }
        fun chooseRecent(blobID: String) {
            click("composer.plus")
            val recentID = "studio.recent.$blobID"
            checkNotNull(device.wait(Until.findObject(By.res(recentID).enabled(true)), 5_000))
            instrument.runOnMainSync {
                val tile = WindowInspector.getGlobalWindowViews().asSequence()
                    .flatMap { it.allViews }
                    .single { it.isShown && it.createAccessibilityNodeInfo().viewIdResourceName == recentID }
                assertTrue(tile.performClick())
            }
        }
        try {
            configurator.waitForIdleTimeout = 0
            instrument.runOnMainSync {
                val player = MediaCoordinator(admission, media)
                closePlayback = player::close
                chrome = ConversationChrome(
                    activity, 2, state, ConversationServices(fixture.sync, media, admission, player), {},
                )
                activity.setContentView(chrome)
            }
            awaitNetwork<Unit> { callback -> instrument.runOnMainSync { state.open(2, callback) } }
            awaitNetwork<Unit> { callback ->
                instrument.runOnMainSync { chrome.openHistory(fixture.repository, null, callback) }
            }
            instrument.runOnMainSync {
                chrome.restoreGenerationOptions(
                    KandinskyOptions(model = "image", aspect = "3:5", count = 3, seed = 21),
                )
            }

            chooseRecent(blob)
            val referenceControl = device.wait(Until.findObject(By.res("studio.reference.remove")), 5_000)
            checkNotNull(referenceControl) {
                val failure = device.findObject(By.res("studio.attachment.error"))?.text
                val chooserStillShown = device.hasObject(By.res("studio.recent.$blob"))
                val stored = awaitResult { fixture.repository.draft(2, it) }
                "Готовое фото не появилось: запросы=${fixture.requests}, " +
                    "ошибка=$failure, панель=$chooserStillShown, сохранённых файлов=${stored.attachmentDrafts.size}"
            }
            awaitNetwork<Unit> { callback -> instrument.runOnMainSync { state.flush(callback) } }
            val draft = awaitResult { fixture.repository.draft(2, it) }
            val photoReference = draft.attachmentDrafts.single()
            assertEquals("image/jpeg", photoReference.mime)
            assertTrue(photo.contentEquals(media.fileURL(photoReference.relativePath).readBytes()))
            assertEquals(
                1,
                fixture.harness.rawScalar("SELECT count(*) FROM net_uploads WHERE blobID='$blob'"),
            )
            val photoOptions = KandinskyOptions(model = "video", aspect = "3:5", count = 3, seed = 21)
            assertEquals(photoOptions, draft.generationOptions)
            instrument.runOnMainSync { assertEquals(photoOptions, chrome.generationOptions) }
            assertEquals(listOf(recentPath, sourcePath, assetPath), fixture.requests.toList())
            chooseRecent(corruptBlob)
            assertTrue(device.wait(Until.hasObject(
                By.res("studio.attachment.error").desc("Не удалось открыть файл"),
            ), 5_000))
            val afterFailure = awaitResult { fixture.repository.draft(2, it) }
            assertEquals(listOf(photoReference.id), afterFailure.attachmentDrafts.map { it.id })
            assertTrue(photo.contentEquals(media.fileURL(photoReference.relativePath).readBytes()))
            assertEquals(
                0,
                fixture.harness.rawScalar("SELECT count(*) FROM net_uploads WHERE blobID='$corruptBlob'"),
            )
            click("studio.reference.remove")
            assertTrue(device.wait(Until.gone(By.res("studio.reference.remove")), 5_000))
            awaitNetwork<Unit> { callback -> instrument.runOnMainSync { state.flush(callback) } }
            val cleared = awaitResult { fixture.repository.draft(2, it) }
            assertTrue(cleared.attachmentDrafts.isEmpty())
            val photoRequests = listOf(recentPath, sourcePath, assetPath, recentPath, corruptSource, corruptAsset)
            assertEquals(photoRequests, fixture.requests.toList())

            chooseRecent(videoBlob)
            click("studio.upload.enhance")
            checkNotNull(device.wait(Until.findObject(By.res("studio.reference.remove").enabled(true)), 5_000))
            awaitNetwork<Unit> { callback -> instrument.runOnMainSync { state.flush(callback) } }
            val upgraded = awaitResult { fixture.repository.draft(2, it) }
            val videoReference = upgraded.attachmentDrafts.single()
            assertEquals("video/mp4", videoReference.mime)
            assertEquals("video_sr", upgraded.generationOptions?.model)
            assertTrue(video.contentEquals(media.fileURL(videoReference.relativePath).readBytes()))
            assertEquals(1, fixture.harness.rawScalar(
                "SELECT count(*) FROM net_uploads WHERE blobID='$videoBlob'",
            ))
            val expectedRequests = photoRequests + listOf(recentPath, videoSource, videoAsset, modelsPath)
            assertEquals(expectedRequests, fixture.requests.toList())
            click("studio.reference.remove")
            assertTrue(device.wait(Until.gone(By.res("studio.reference.remove")), 5_000))
            awaitNetwork<Unit> { callback -> instrument.runOnMainSync { state.flush(callback) } }
            val withoutVideo = awaitResult { fixture.repository.draft(2, it) }
            assertTrue(withoutVideo.attachmentDrafts.isEmpty())
            assertEquals("image", withoutVideo.generationOptions?.model)
            assertEquals(expectedRequests, fixture.requests.toList())
        } finally {
            configurator.waitForIdleTimeout = previousIdleTimeout
            instrument.runOnMainSync {
                activity.finish()
                state.close()
                closePlayback?.invoke()
                media.close()
                admission.close()
            }
        }
    }

    // Дожидаемся сетевого действия через общий проверочный помощник, подставляя пустую отмену.
    // Так проверка получает ошибку или значение через один вызов.
    private fun <T> awaitNetwork(register: ((Result<T>) -> Unit) -> Unit): T =
        awaitResult { callback ->
            register(callback)
            Cancellation {}
        }

    // Требуем отдельную проверочную установку без сохранённых учётных данных. Открываем временную
    // базу и гарантированно закрываем связь и удаляем свою папку после сценария.
    private fun withConnection(action: (ConnectionFixture) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".cleanup"))
        check(NetworkCredentials(context).load() == null)
        val root = File(context.cacheDir, "network-connection-${UUID.randomUUID()}")
        val harness = RepositoryHarness.open(root)
        val fixture = ConnectionFixture(harness)
        try {
            action(fixture)
        } finally {
            fixture.sync.close()
            harness.close()
            root.deleteRecursively()
        }
    }

    private class ConnectionFixture(val harness: RepositoryHarness) {
        val requests = LinkedBlockingQueue<String>()
        val sockets = LinkedBlockingQueue<ScriptedSocket>()
        private val statuses = LinkedBlockingQueue<String>()
        val repository = SQLiteChatRepository(harness.store, NetworkJournal(1))
        @Volatile var httpFailure: Pair<Int, String>? = null
        @Volatile var directoryReply: ((String) -> Pair<Int, String>?)? = null
        @Volatile var chatDirectoryResponse: String? = null
        @Volatile var respond: ((Request) -> Pair<Int, String>)? = null
        @Volatile var mediaReplies: Map<String, ByteArray> = emptyMap()
        private val profile = NetworkProfile(
            1, UUID.randomUUID().toString(), "Проверка связи", "a".repeat(64), Long.MAX_VALUE,
        )
        private val chat = """{"id":2,"title":"Генерации","lastSeq":0,"unreadCount":0,"pinned":false}"""
        private val catalog = """{"chats":[$chat]}"""
        private val bootstrap = """{"version":1,"userId":1,"cursor":0,"messages":[],"chats":[$chat]}"""
        private val transport = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            requests.add(path)
            mediaReplies[path]?.let { file ->
                check(request.method == "GET")
                return@addInterceptor Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(200).message("Проверочный файл")
                    .body(file.toResponseBody("image/jpeg".toMediaType())).build()
            }
            val failure = httpFailure
            val (status, response) = if (failure == null) {
                when (path) {
                    "/v1/bootstrap" -> 200 to bootstrap
                    "/v1/chats" -> directoryReply?.invoke(path) ?: (200 to (chatDirectoryResponse ?: catalog))
                    "/v1/chats/states" -> directoryReply?.invoke(path) ?: (200 to catalog)
                    else -> requireNotNull(respond) { "Неожиданный запрос $path" }(request)
                }
            } else failure.first to JSONObject()
                .put("error", JSONObject().put("code", failure.second)).toString()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(status).message("Проверочный ответ")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val sync = NetworkSync(
            profile, harness.store, repository,
            NetworkAPI(profile, transport) { request, listener ->
                ScriptedSocket(request, listener).also { sockets.add(it) }
            },
        ).also { sync -> sync.onStatus = { statuses.add(it) } }

        // Устанавливаем сетевую схему в открытой проверочной базе. Без неё очередь и
        // подтверждения не имели бы подготовленных таблиц.
        init {
            awaitResult<Unit> { cb -> harness.store.submit(NetworkSchema::install, cb) }
        }

        // Ждём созданное пробное соединение до пяти секунд и сверяем путь подключения. Это
        // позволяет точно указать, с каким соединением работает сценарий.
        fun takeSocket(): ScriptedSocket = checkNotNull(sockets.poll(5, TimeUnit.SECONDS)) {
            "Ожидалось подключение к WebSocket. Запросы: $requests. Состояния: $statuses"
        }.also { assertEquals("/v1/ws", it.request().url.encodedPath) }

        // Поднимаем пробное соединение, проверяем первый запрос и доступные возможности.
        // Переходим к сценарию, когда клиент сообщил готовность и номер применённых событий.
        fun connect(): ScriptedSocket {
            sync.foreground(true)
            expectStatus("Подключение…")
            val socket = takeSocket()
            socket.open()
            val handshake = socket.frame("resume")
            assertEquals(0, handshake.getInt("cursor"))
            assertTrue(handshake.getJSONObject("features").getBoolean("chatDirectory"))
            assertTrue(handshake.getJSONObject("features").getBoolean("uploadProgress"))
            socket.receive(frame("resumed"))
            expectStatus("В сети")
            assertEquals(0, socket.frame("applied").getInt("cursor"))
            socket.receive(frame("applied").put("cursor", 0))
            waitUntil("Подтверждено начало истории") { cursors() == (0L to "0") }
            return socket
        }

        // Ждём следующий статус связи из очереди и сравниваем его с ожидаемым. Проверяем порядок
        // состояний независимо от скорости основного потока.
        fun expectStatus(expected: String, timeoutSeconds: Long = 5) {
            assertEquals(expected, statuses.poll(timeoutSeconds, TimeUnit.SECONDS))
        }

        // Читаем страницу чата через обычное хранилище. Так проверка видит то, что дошло до
        // записанной истории.
        fun history(): HistoryPage = awaitResult { repository.history(PageRequest(2), it) }

        // Получаем номер активного задания проверочного чата через сетевую модель. По результату
        // видно, был ли запрос на повтор ещё в работе.
        fun activeGeneration(): Long? = awaitResult { callback ->
            sync.activeGeneration(2, callback)
            Cancellation {}
        }

        // Считаем записи очереди отправки в местной базе. Неподтверждённая команда должна
        // оставаться там до нужного ответа.
        fun outboxCount(): Int = harness.rawScalar("SELECT count(*) FROM net_outbox")

        fun reconnectAfterUnreadableOperation(
            action: String, invalidPayload: String, whileOffline: () -> Unit,
        ): ScriptedSocket {
            val payload = awaitResult<String> { callback ->
                harness.store.submit({ db ->
                    val saved = db.rawQuery("SELECT payload FROM net_outbox WHERE action=?", arrayOf(action))
                        .use {
                            check(it.moveToFirst() && it.count == 1)
                            it.getString(0)
                        }
                    db.execSQL("UPDATE net_outbox SET payload=? WHERE action=?", arrayOf(invalidPayload, action))
                    saved
                }, callback)
            }
            sync.kick()
            expectStatus("Нет связи · сообщения сохранены")
            assertEquals(1, outboxCount())
            whileOffline()
            awaitResult<Unit> { callback ->
                harness.store.submit({ db ->
                    db.execSQL("UPDATE net_outbox SET payload=? WHERE action=?", arrayOf(payload, action))
                }, callback)
            }
            expectStatus("Подключение…")
            return takeSocket().also { replacement ->
                replacement.open()
                assertEquals(0, replacement.frame("resume").getInt("cursor"))
                replacement.receive(frame("resumed"))
                expectStatus("В сети")
            }
        }

        // Ждём, пока ответ освободит очередь. Проверка завершается ошибкой, если подтверждение
        // так и не дошло до базы.
        fun awaitOutboxEmpty() = waitUntil("Очередь получила подтверждение") { outboxCount() == 0 }

        // Читаем сохранённый номер и отдельно номер, подтверждённый сервером, в очереди базы.
        // Одно значение не должно подменять другое.
        fun cursors(): Pair<Long, String?> = awaitResult { cb ->
            harness.store.submit({ db ->
                val saved = sync.cache.cursor(db)
                val acknowledged = db.rawQuery("SELECT value FROM net_state WHERE key='ackedCursor'", null)
                    .use { if (it.moveToFirst()) it.getString(0) else null }
                saved to acknowledged
            }, cb)
        }

        // Проверяем условие с паузами до пяти секунд. При превышении времени выдаём описание
        // условия, которое не выполнилось.
        fun waitUntil(description: String, ready: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                if (ready()) return
                Thread.sleep(20)
            }
            error(description)
        }
    }

    private class ScriptedSocket(
        private val originalRequest: Request,
        private val listener: WebSocketListener,
    ) : WebSocket {
        private val outbound = LinkedBlockingQueue<String>()
        @Volatile var cancelled = false
            private set
        val cancellations = AtomicInteger()

        // Возвращаем исходный запрос открытия соединения. Проверка может сверить его адрес без
        // настоящего выхода в сеть.
        override fun request() = originalRequest
        // Возвращаем ноль для размера очереди передачи. Проверочная подмена кладёт текст в
        // собственную очередь наблюдений без оценки сетевого буфера.
        override fun queueSize() = 0L
        // Кладём текст клиента в очередь проверки, пока соединение не отменено. После отмены
        // возвращаем отказ, чтобы старый канал не принимал команды.
        override fun send(text: String) = !cancelled && outbound.add(text)
        // Останавливаем проверку при попытке передать двоичный пакет. Для этих сценариев
        // ожидаются только текстовые сообщения.
        override fun send(bytes: ByteString) = error("Ожидался текстовый пакет")
        // Принимаем запрос на закрытие подменного соединения. Состояние принудительной отмены
        // учитывается отдельно.
        override fun close(code: Int, reason: String?) = true
        // Отмечаем соединение отменённым. После этого передача через него перестаёт приниматься.
        override fun cancel() {
            cancellations.incrementAndGet()
            cancelled = true
        }

        // Вызываем обычный обработчик открытия с ответом о смене протокола. Это запускает
        // настоящий обмен клиента без удалённого соединения.
        fun open() = listener.onOpen(this, Response.Builder().request(originalRequest)
            .protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build())
        // Сообщаем клиенту об обрыве как об ошибке чтения или записи. Проверка может управлять
        // моментом потери связи.
        fun fail() = listener.onFailure(this, IOException("Проверочный обрыв соединения"), null)
        // Передаём сигнал закрытия по причине перезапуска. Так проверяем обработку объявленного
        // разрыва отдельно от внезапной ошибки.
        fun serverClosing() = listener.onClosing(this, 1001, "Проверочный перезапуск")
        fun serverClosed() = listener.onClosed(this, 1000, "Проверочное завершение")
        // Преобразуем объект в текст и передаём обработчику входящего сообщения. Клиент проходит
        // обычный разбор ответа.
        fun receive(value: JSONObject) = listener.onMessage(this, value.toString())

        // Берём очередной исходящий текст и сверяем вид пакета. Отсутствие или нарушение порядка
        // останавливает проверку.
        fun frame(type: String): JSONObject {
            val raw = checkNotNull(outbound.poll(5, TimeUnit.SECONDS)) { "Ожидался пакет $type" }
            return JSONObject(raw).also { assertEquals(type, it.getString("type")) }
        }
    }

    private companion object {
        // Создаём пустой пакет с указанным видом. Остальные поля добавляет конкретный проверочный
        // сценарий.
        fun frame(type: String) = JSONObject().put("type", type)

        // Формируем подтверждение с номером исходного запроса и одной принятой строкой. Связь с
        // запросом важна для точного удаления команды из очереди.
        fun accepted(request: JSONObject, serverID: Long, command: SendCommand, text: String) =
            frame("accepted").put("requestId", request.getString("requestId"))
                .put("messages", JSONArray().put(wireMessage(serverID, command, text)))

        // Собираем строку истории с номером на сервере и номером операции клиента. Такой ответ
        // позволяет проверить, что местная и серверная записи объединяются.
        fun wireMessage(serverID: Long, command: SendCommand, text: String) = JSONObject()
            .put("id", serverID).put("chatId", command.chatID).put("seq", serverID)
            .put("senderId", 1).put("clientId", command.operationID).put("kind", "text")
            .put("text", text).put("caption", "").put("replyToId", JSONObject.NULL)
            .put("savedAtMs", 1000).put("revision", 1).put("attachments", JSONArray())
    }
}
