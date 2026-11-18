package dev.kirill.kandinsky.chat.network

import android.content.Intent
import android.view.inspector.WindowInspector
import androidx.core.view.allViews
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.kirill.kandinsky.chat.conversation.ConversationChrome
import dev.kirill.kandinsky.chat.conversation.ConversationServices
import dev.kirill.kandinsky.chat.conversation.ConversationState
import dev.kirill.kandinsky.chat.domain.Cancellation
import dev.kirill.kandinsky.chat.domain.HistoryPage
import dev.kirill.kandinsky.chat.domain.KandinskyOptions
import dev.kirill.kandinsky.chat.domain.MessageDelivery
import dev.kirill.kandinsky.chat.domain.MessageDelta
import dev.kirill.kandinsky.chat.domain.PageRequest
import dev.kirill.kandinsky.chat.domain.RepoError
import dev.kirill.kandinsky.chat.domain.RepoException
import dev.kirill.kandinsky.chat.domain.SendCommand
import dev.kirill.kandinsky.chat.domain.StudioGeneration
import dev.kirill.kandinsky.chat.domain.StudioGenerationAction
import dev.kirill.kandinsky.chat.domain.StudioReportReason
import dev.kirill.kandinsky.chat.media.MediaAdmission
import dev.kirill.kandinsky.chat.media.MediaCoordinator
import dev.kirill.kandinsky.chat.media.MediaStore
import dev.kirill.kandinsky.chat.storage.SQLiteChatRepository
import dev.kirill.kandinsky.chat.support.RepositoryHarness
import dev.kirill.kandinsky.chat.support.TestFixtures
import dev.kirill.kandinsky.chat.support.awaitResult
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkConnectionTest {
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
        fun delete(chat: Long, blob: String?): Result<Unit> = runCatching {
            awaitNetwork<Unit> { fixture.sync.deleteGenerationContent(chat, row.id, blob, it) }
        }
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
    fun recentPhotoBecomesTheDraftReferenceWithoutASecondUpload() = withConnection { fixture ->
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val blob = "3".repeat(32)
        val photo = TestFixtures.assets.open("media/photo-000.jpg").use { it.readBytes() }
        val recent = JSONObject().put(
            "uploads",
            JSONArray().put(
                JSONObject().put("blobId", blob).put("mime", "image/jpeg").put("byteSize", photo.size),
            ),
        )
        val recentPath = "/v1/kandinsky/uploads/recent"
        val sourcePath = "/v1/kandinsky/threads/2/sources/$blob"
        val assetPath = "/v1/media/$blob"
        fixture.mediaReplies = mapOf(assetPath to photo)
        fixture.respond = { request ->
            when (request.url.encodedPath) {
                recentPath -> {
                    check(request.method == "GET")
                    200 to recent.toString()
                }
                sourcePath -> {
                    check(request.method == "POST")
                    204 to ""
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
        fun click(id: String) {
            val item = checkNotNull(device.wait(Until.findObject(By.res(id).enabled(true)), 5_000)) {
                "Не появился доступный элемент $id. Запросы: ${fixture.requests}"
            }
            item.click()
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

            click("composer.plus")
            val recentID = "studio.recent.$blob"
            checkNotNull(device.wait(Until.findObject(By.res(recentID).enabled(true)), 5_000))
            instrument.runOnMainSync {
                val tile = WindowInspector.getGlobalWindowViews().asSequence()
                    .flatMap { it.allViews }
                    .single {
                        it.isShown && it.createAccessibilityNodeInfo().viewIdResourceName == recentID
                    }
                assertTrue(tile.performClick())
            }
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
            instrument.runOnMainSync {
                assertEquals(
                    KandinskyOptions(model = "video", aspect = "3:5", count = 3, seed = 21),
                    chrome.generationOptions,
                )
            }
            assertEquals(listOf(recentPath, sourcePath, assetPath), fixture.requests.toList())
            click("studio.reference.remove")
            assertTrue(device.wait(Until.gone(By.res("studio.reference.remove")), 5_000))
            awaitNetwork<Unit> { callback -> instrument.runOnMainSync { state.flush(callback) } }
            val cleared = awaitResult { fixture.repository.draft(2, it) }
            assertTrue(cleared.attachmentDrafts.isEmpty())
            assertEquals(listOf(recentPath, sourcePath, assetPath), fixture.requests.toList())
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

    private fun <T> awaitNetwork(register: ((Result<T>) -> Unit) -> Unit): T =
        awaitResult { callback ->
            register(callback)
            Cancellation {}
        }

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
                    "/v1/chats", "/v1/chats/states" -> 200 to catalog
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

        init {
            awaitResult<Unit> { cb -> harness.store.submit(NetworkSchema::install, cb) }
        }

        fun takeSocket(): ScriptedSocket = checkNotNull(sockets.poll(5, TimeUnit.SECONDS)) {
            "Ожидалось подключение к WebSocket. Запросы: $requests. Состояния: $statuses"
        }.also { assertEquals("/v1/ws", it.request().url.encodedPath) }

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

        fun expectStatus(expected: String) {
            assertEquals(expected, statuses.poll(5, TimeUnit.SECONDS))
        }

        fun history(): HistoryPage = awaitResult { repository.history(PageRequest(2), it) }

        fun activeGeneration(): Long? = awaitResult { callback ->
            sync.activeGeneration(2, callback)
            Cancellation {}
        }

        fun outboxCount(): Int = harness.rawScalar("SELECT count(*) FROM net_outbox")

        fun awaitOutboxEmpty() = waitUntil("Очередь получила подтверждение") { outboxCount() == 0 }

        fun cursors(): Pair<Long, String?> = awaitResult { cb ->
            harness.store.submit({ db ->
                val saved = sync.cache.cursor(db)
                val acknowledged = db.rawQuery("SELECT value FROM net_state WHERE key='ackedCursor'", null)
                    .use { if (it.moveToFirst()) it.getString(0) else null }
                saved to acknowledged
            }, cb)
        }

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

        override fun request() = originalRequest
        override fun queueSize() = 0L
        override fun send(text: String) = !cancelled && outbound.add(text)
        override fun send(bytes: ByteString) = error("Ожидался текстовый пакет")
        override fun close(code: Int, reason: String?) = true
        override fun cancel() { cancelled = true }

        fun open() = listener.onOpen(this, Response.Builder().request(originalRequest)
            .protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build())
        fun fail() = listener.onFailure(this, IOException("Проверочный обрыв соединения"), null)
        fun serverClosing() = listener.onClosing(this, 1001, "Проверочный перезапуск")
        fun receive(value: JSONObject) = listener.onMessage(this, value.toString())

        fun frame(type: String): JSONObject {
            val raw = checkNotNull(outbound.poll(5, TimeUnit.SECONDS)) { "Ожидался пакет $type" }
            return JSONObject(raw).also { assertEquals(type, it.getString("type")) }
        }
    }

    private companion object {
        fun frame(type: String) = JSONObject().put("type", type)

        fun accepted(request: JSONObject, serverID: Long, command: SendCommand, text: String) =
            frame("accepted").put("requestId", request.getString("requestId"))
                .put("messages", JSONArray().put(wireMessage(serverID, command, text)))

        fun wireMessage(serverID: Long, command: SendCommand, text: String) = JSONObject()
            .put("id", serverID).put("chatId", command.chatID).put("seq", serverID)
            .put("senderId", 1).put("clientId", command.operationID).put("kind", "text")
            .put("text", text).put("caption", "").put("replyToId", JSONObject.NULL)
            .put("savedAtMs", 1000).put("revision", 1).put("attachments", JSONArray())
    }
}
