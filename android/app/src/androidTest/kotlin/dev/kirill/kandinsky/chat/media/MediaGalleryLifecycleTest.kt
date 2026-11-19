// Проверки галереи на андроиде при удалении текущего результата и ошибке источника. Они следят,
// чтобы поздняя история не открыла закрытое окно и чтобы повтор действительно запустил скачанный
// ролик.

package dev.kirill.kandinsky.chat.media

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.core.view.allViews
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import dev.kirill.kandinsky.chat.domain.Attachment
import dev.kirill.kandinsky.chat.domain.AttachmentDraft
import dev.kirill.kandinsky.chat.domain.Cancellation
import dev.kirill.kandinsky.chat.domain.ChatSummary
import dev.kirill.kandinsky.chat.domain.HistoryPage
import dev.kirill.kandinsky.chat.domain.Message
import dev.kirill.kandinsky.chat.domain.MessageDelta
import dev.kirill.kandinsky.chat.domain.MessageDirection
import dev.kirill.kandinsky.chat.domain.MessageKind
import dev.kirill.kandinsky.chat.domain.OrderKey
import dev.kirill.kandinsky.chat.domain.PageDirection
import dev.kirill.kandinsky.chat.domain.PageRequest
import dev.kirill.kandinsky.chat.domain.SendCommand
import dev.kirill.kandinsky.chat.network.NetworkCredentials
import dev.kirill.kandinsky.chat.results.MediaGalleryController
import dev.kirill.kandinsky.chat.storage.ChatRepository
import dev.kirill.kandinsky.chat.support.AppFlowDriver
import dev.kirill.kandinsky.chat.support.RepositoryHarness
import dev.kirill.kandinsky.chat.support.TestFixtures
import dev.kirill.kandinsky.chat.support.awaitResult
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class MediaGalleryLifecycleTest {
    @Test
    fun retryingAPhotoClearsItsPauseWhenTheGalleryRecreatesThePage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".cleanup"))
        val launch = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val activity = instrumentation.startActivitySync(launch)
        val root = File(context.cacheDir, "gallery-photo-retry-${UUID.randomUUID()}")
        val harness = RepositoryHarness.open(root)
        val media = MediaStore(harness.store)
        val admission = MediaAdmission()
        var player: MediaCoordinator? = null
        var gallery: MediaGalleryController? = null
        val started = AtomicInteger()
        val cancelled = AtomicInteger()
        val ui = AppFlowDriver()
        fun awaitCount(counter: AtomicInteger, expected: Int) {
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (counter.get() < expected && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(20)
            }
            assertEquals(expected, counter.get())
        }
        try {
            val names = checkNotNull(TestFixtures.assets.list("media")).filter {
                it.startsWith("photo-") && it.endsWith(".jpg")
            }.sorted().take(4)
            assertEquals(4, names.size)
            val files = linkedMapOf<String, File>()
            for ((index, name) in names.withIndex()) {
                val draft = awaitResult<AttachmentDraft> { done ->
                    var operation: Cancellation? = null
                    instrumentation.runOnMainSync {
                        operation = media.stage({ TestFixtures.assets.open("media/$name") }, "image/jpeg", done)
                    }
                    checkNotNull(operation)
                }
                awaitResult<MessageDelta> { done ->
                    harness.repo.send(
                        SendCommand(
                            UUID.randomUUID().toString(), 2, MessageKind.PHOTO,
                            caption = "Фотография ${index + 1}", attachments = listOf(draft),
                        ),
                        done,
                    )
                }
                val path = "remote/retry-photo-$index.jpg"
                files[path] = media.fileURL(draft.relativePath)
                awaitResult<Unit> { done ->
                    harness.store.submit({ db ->
                        db.execSQL("UPDATE attachments SET relativePath=? WHERE id=?", arrayOf(path, draft.id))
                    }, done)
                }
            }
            val history = awaitResult<HistoryPage> { done ->
                harness.repo.history(PageRequest(chatID = 2, limit = 10, visualOnly = true), done)
            }
            val seed = history.rows.single { it.caption == "Фотография 2" }
            instrumentation.runOnMainSync {
                media.remoteFile = { attachment, preview, token ->
                    val file = files.getValue(attachment.relativePath)
                    if (!preview) {
                        started.incrementAndGet()
                        val gate = CountDownLatch(1)
                        token.onCancel {
                            cancelled.incrementAndGet()
                            gate.countDown()
                        }
                        check(gate.await(15, TimeUnit.SECONDS))
                    }
                    file to "image/jpeg"
                }
                val playback = MediaCoordinator(admission, media)
                player = playback
                val surface = InlinePlayerSurface(activity, playback)
                gallery = MediaGalleryController(activity, seed, harness.repo, media, playback, surface) {}
                checkNotNull(gallery).show()
            }
            val pager = ui.find(By.res("viewer.pages"))
            val bounds = pager.visibleBounds
            fun swipe(forward: Boolean, selected: Int) {
                val lowX = bounds.left + bounds.width() / 8
                val highX = bounds.right - bounds.width() / 8
                val y = bounds.centerY()
                val start = if (forward) highX else lowX
                val end = if (forward) lowX else highX
                assertTrue(ui.device.swipe(start, y, end, y, 25))
                ui.find(By.text("Фотография $selected"))
            }
            awaitCount(started, 1)
            ui.click(By.res("media.cancel"))
            awaitCount(cancelled, 1)
            swipe(true, 3)
            awaitCount(started, 2)
            swipe(true, 4)
            awaitCount(started, 3)
            swipe(false, 3)
            awaitCount(started, 4)
            swipe(false, 2)
            ui.click(By.res("media.retry"))
            awaitCount(started, 5)
            for ((index, selected) in listOf(3, 4, 3, 2).withIndex()) {
                swipe(index < 2, selected)
                awaitCount(started, index + 6)
            }
            instrumentation.runOnMainSync { gallery?.close(returnToChat = false) }
            awaitCount(cancelled, 9)
        } finally {
            instrumentation.runOnMainSync {
                gallery?.close(returnToChat = false)
                player?.close()
                media.close()
                admission.close()
                activity.finish()
            }
            harness.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun staleGallerySelectionClosesWithoutShowingAnEmptyDialog() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".cleanup"))
        val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val activity = instrumentation.startActivitySync(intent)
        val folder = File(context.cacheDir, "gallery-stale-" + UUID.randomUUID())
        val harness = RepositoryHarness.open(folder)
        val media = MediaStore(harness.store)
        val admission = MediaAdmission()
        try {
            instrumentation.runOnMainSync {
                val player = MediaCoordinator(admission, media)
                try {
                    val row = Message(
                        31, 2, OrderKey(31, 31), MessageDirection.INCOMING, 0, MessageKind.TEXT,
                        text = "Медиа уже нет", caption = "", replyToID = null, revision = 1,
                        attachments = emptyList(),
                    )
                    var returned = 0
                    val surface = InlinePlayerSurface(activity, player)
                    val gallery = MediaGalleryController(
                        activity, row, harness.repo, media, player, surface,
                    ) { returned++ }
                    val before = WindowInspector.getGlobalWindowViews().toSet()
                    gallery.show()
                    gallery.close(returnToChat = false)
                    assertEquals(1, returned)
                    assertEquals(before, WindowInspector.getGlobalWindowViews().toSet())
                    assertNull(surface.parent)
                    assertNull(player.currentMessageID)
                } finally {
                    player.close()
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                media.close()
                admission.close()
                activity.finish()
            }
            harness.close()
            folder.deleteRecursively()
        }
    }

    // Открываем результат, проверяем скрытие и возврат подписи и меню, затем удаляем его. Галерея
    // закрывается один раз и игнорирует запоздалое чтение.
    @Test
    fun selectedResultCanBeRemovedAndLateHistoryCannotReopenGallery() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".cleanup"))
        check(NetworkCredentials(context).load() == null)
        val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        val activity = instrumentation.startActivitySync(
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        val harness = RepositoryHarness.open(File(context.cacheDir, "gallery-removal-" + UUID.randomUUID()))
        val blob = "824d66cce0d64409ad4888a13792c7d4"
        val photo = Attachment(
            "net-31-result-$blob", "remote/$blob.jpg", "image/jpeg", 1967774,
            3000, 1732, 1, null, blob,
        )
        val chat = ChatSummary(2, "Студия", "", 31, 0, 0, false)
        val row = Message(
            31, chat.id, OrderKey(31, 31), MessageDirection.INCOMING, 0, MessageKind.PHOTO,
            text = "", caption = "Результат генерации", replyToID = null, revision = 1,
            attachments = listOf(photo),
        )
        val fetched = mutableListOf<PageRequest>()
        val cancelled = mutableListOf<PageRequest>()
        var lateReply: ((Result<HistoryPage>) -> Unit)? = null
        val repository = object : ChatRepository by harness.repo {
            // Перехватываем запрос галереи и сохраняем его ответ для ручной выдачи. Отдельно
            // отмечаем отмену, иначе невозможно проверить реакцию после закрытия.
            override fun history(request: PageRequest, callback: (Result<HistoryPage>) -> Unit): Cancellation {
                fetched += request
                lateReply = callback
                return Cancellation { cancelled += request }
            }
        }
        var selectedForRemoval: Pair<Long, String>? = null
        var returnCount = 0
        lateinit var gallery: MediaGalleryController
        lateinit var galleryWindow: View
        var cleanup: (() -> Unit)? = null
        try {
            val file = File(harness.root, "media/" + photo.relativePath)
            check(file.parentFile?.mkdirs() == true)
            TestFixtures.assets.open("real-media/backend-$blob.jpg").use { source ->
                file.outputStream().use { source.copyTo(it) }
            }
            assertEquals(photo.byteSize, file.length())
            instrumentation.runOnMainSync {
                val admission = MediaAdmission()
                val store = MediaStore(harness.store, studioOriginals = true)
                val playback = MediaCoordinator(admission, store)
                val surface = InlinePlayerSurface(activity, playback)
                gallery = MediaGalleryController(activity, row, repository, store, playback, surface) {
                    returnCount++
                }
                cleanup = {
                    gallery.close()
                    playback.close()
                    store.close()
                    admission.close()
                }
                gallery.onDeleteResult = { id, attachment -> selectedForRemoval = id to attachment }
                val before = WindowInspector.getGlobalWindowViews().toSet()
                gallery.show()
                galleryWindow = WindowInspector.getGlobalWindowViews().single { it !in before }
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val pager = galleryWindow.allViews.single {
                    it.createAccessibilityNodeInfo().viewIdResourceName == "viewer.pages"
                }
                val caption = galleryWindow.allViews.filterIsInstance<TextView>()
                    .single { it.text.toString() == row.caption }
                val menu = galleryWindow.allViews.single { it.contentDescription == "Действия с результатом" }
                assertTrue(pager.performClick())
                assertFalse(caption.isShown)
                assertFalse(menu.isShown)
                assertTrue(pager.performClick())
                assertTrue(caption.isShown)
                assertTrue(menu.isShown)
            }
            // Открываем нужный пункт меню и нажимаем его в потоке экрана. Проверка может пройти
            // реальный путь удаления без прямого вызова обработчика галереи.
            fun chooseAction(label: String) {
                lateinit var actions: View
                instrumentation.runOnMainSync {
                    val caption = galleryWindow.allViews.filterIsInstance<TextView>()
                    assertTrue(caption.any { it.text.toString() == row.caption && it.isShown })
                    val menu = galleryWindow.allViews.single { it.contentDescription == "Действия с результатом" }
                    val before = WindowInspector.getGlobalWindowViews().toSet()
                    assertTrue(menu.isShown)
                    assertTrue(menu.performClick())
                    actions = WindowInspector.getGlobalWindowViews().single { it !in before }
                }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    val button = actions.allViews.filterIsInstance<TextView>().single { it.text.toString() == label }
                    if (label == "Удалить результат") {
                        val list = actions.allViews.filterIsInstance<android.widget.ListView>().single()
                        assertTrue(list.performItemClick(button, 0, list.adapter.getItemId(0)))
                    } else assertTrue(button.performClick())
                }
                instrumentation.waitForIdleSync()
            }
            chooseAction("Отмена")
            assertNull(selectedForRemoval)
            chooseAction("Удалить результат")
            instrumentation.runOnMainSync {
                assertEquals(row.id to blob, selectedForRemoval)
                assertEquals(listOf(PageRequest(chat.id, row.order, PageDirection.AROUND, 32, visualOnly = true)), fetched)
                gallery.apply(MessageDelta(removedIDs = listOf(row.id), chat = chat.copy(id = 8)))
                assertEquals(0, returnCount)
                assertTrue(cancelled.isEmpty())
                gallery.apply(MessageDelta(removedIDs = listOf(row.id), chat = chat))
                assertEquals(fetched, cancelled)
                assertEquals(1, returnCount)
                checkNotNull(lateReply)(Result.success(HistoryPage(listOf(row), null, null)))
                gallery.show()
                gallery.close()
                assertEquals(1, returnCount)
                assertFalse(WindowInspector.getGlobalWindowViews().contains(galleryWindow))
            }
        } finally {
            instrumentation.runOnMainSync {
                cleanup?.invoke()
                activity.finish()
            }
            harness.close()
        }
    }

    @Test
    fun neighbouringHistoryWaitsForTheSwipeToSettleOrBeTakenOver() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".cleanup"))
        check(NetworkCredentials(context).load() == null)
        val launch = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val activity = instrumentation.startActivitySync(launch)
        val root = File(context.cacheDir, "album-page-pending-" + UUID.randomUUID())
        val harness = RepositoryHarness.open(root)
        val media = MediaStore(harness.store)
        val admission = MediaAdmission()
        lateinit var playback: MediaCoordinator
        instrumentation.runOnMainSync { playback = MediaCoordinator(admission, media) }

        fun addPhotoMessage(caption: String, count: Int): Message {
            val photos = List(count) {
                awaitResult<AttachmentDraft> { reply ->
                    instrumentation.runOnMainSync {
                        media.stage({ TestFixtures.assets.open("media/photo-000.jpg") }, "image/jpeg", reply)
                    }
                    Cancellation {}
                }
            }
            return awaitResult<MessageDelta> { reply ->
                harness.repo.send(SendCommand(
                    UUID.randomUUID().toString(), 2, kind = MessageKind.PHOTO, caption = caption,
                    attachments = photos,
                ), reply)
            }.inserted.single()
        }

        try {
            val previous = addPhotoMessage("Предыдущий снимок", 1)
            val album = addPhotoMessage("Два снимка", 2)
            val next = addPhotoMessage("Следующий снимок", 1)
            val chat = awaitResult<List<ChatSummary>> { harness.repo.chats("", it) }.single { it.id == 2L }
            fun savedDirection(value: String) {
                awaitResult<Unit> { done ->
                    harness.store.submit({ db ->
                        db.execSQL("UPDATE messages SET direction=? WHERE id=?",
                            arrayOf<Any>(value, next.id))
                    }, done)
                }
            }
            for (startAnotherSwipe in listOf(false, true)) {
                val fetched = CountDownLatch(1)
                val rejected = CountDownLatch(1)
                if (!startAnotherSwipe) savedDirection("invalid-direction")
                var deliver: (() -> Unit)? = null
                val repository = object : ChatRepository by harness.repo {
                    override fun history(
                        request: PageRequest, callback: (Result<HistoryPage>) -> Unit,
                    ): Cancellation = harness.repo.history(request) { reply ->
                        if (reply.isFailure) {
                            callback(reply)
                            rejected.countDown()
                        } else {
                            deliver = { callback(reply) }
                            fetched.countDown()
                        }
                    }
                }
                lateinit var gallery: MediaGalleryController
                lateinit var window: View
                lateinit var pager: ViewGroup
                instrumentation.runOnMainSync {
                    gallery = MediaGalleryController(activity, album, repository, media, playback,
                        InlinePlayerSurface(activity, playback)) {}
                    val before = WindowInspector.getGlobalWindowViews().toSet()
                    gallery.show()
                    window = WindowInspector.getGlobalWindowViews().single { it !in before }
                }
                try {
                    if (!startAnotherSwipe) {
                        assertTrue("Первая попытка чтения должна получить ошибку", rejected.await(5, TimeUnit.SECONDS))
                        instrumentation.waitForIdleSync()
                        instrumentation.runOnMainSync {
                            assertTrue(window.allViews.filterIsInstance<TextView>().any {
                                it.isShown && it.text.toString() == album.caption
                            })
                        }
                        savedDirection(next.direction.name.lowercase(java.util.Locale.ROOT))
                        instrumentation.runOnMainSync {
                            gallery.apply(MessageDelta(updated = listOf(album), chat = chat))
                        }
                    }
                    assertTrue("Галерея должна прочитать реальные соседние строки", fetched.await(5, TimeUnit.SECONDS))
                    instrumentation.waitForIdleSync()
                    fun hasPage(id: Long, index: Int): Boolean = pager.allViews.any {
                        it.createAccessibilityNodeInfo().viewIdResourceName == "viewer.page.$id/$index"
                    }
                    fun touch(action: Int) {
                        val now = SystemClock.uptimeMillis()
                        val event = MotionEvent.obtain(now, now, action, pager.width * 0.7f, pager.height * 0.6f, 0)
                        try {
                            if (action == MotionEvent.ACTION_DOWN) pager.onInterceptTouchEvent(event)
                            else pager.onTouchEvent(event)
                        } finally {
                            event.recycle()
                        }
                    }
                    var center = 0
                    instrumentation.runOnMainSync {
                        pager = window.allViews.filterIsInstance<ViewGroup>().single {
                            it.createAccessibilityNodeInfo().viewIdResourceName == "viewer.pages"
                        }
                        center = pager.scrollX
                        assertTrue(center > 0)
                        val swipe = center + center * 3 / 4
                        pager.scrollTo(swipe, 0)
                        checkNotNull(deliver).invoke()
                        assertEquals(swipe, pager.scrollX)
                        assertFalse(hasPage(previous.id, 0))
                        assertTrue(hasPage(album.id, 1))
                        assertFalse(hasPage(next.id, 0))
                        if (startAnotherSwipe) {
                            touch(MotionEvent.ACTION_DOWN)
                            assertEquals(swipe - center, pager.scrollX)
                            assertTrue(hasPage(next.id, 0))
                        }
                        touch(MotionEvent.ACTION_UP)
                    }
                    val end = SystemClock.uptimeMillis() + 5_000
                    var settled = false
                    while (!settled && SystemClock.uptimeMillis() < end) {
                        instrumentation.runOnMainSync { settled = pager.scrollX == center && hasPage(next.id, 0) }
                        if (!settled) SystemClock.sleep(20)
                    }
                    assertTrue("Следующий снимок должен стать доступен после окончания свайпа", settled)
                } finally {
                    instrumentation.runOnMainSync { gallery.close() }
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                playback.close()
                media.close()
                admission.close()
                activity.finish()
            }
            harness.close()
            root.deleteRecursively()
        }
    }

    // Первый запрос источника завершаем ошибкой и ждём кнопку повтора. После нажатия даём
    // настоящий файл и сверяем, что второй запрос приводит к готовому показу.
    @Test
    fun failedVideoSourceCanBeRetriedAndPlaysTheDownloadedFile() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".cleanup"))
        check(NetworkCredentials(context).load() == null)
        val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        val activity = instrumentation.startActivitySync(
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        val harness = RepositoryHarness.open(File(context.cacheDir, "gallery-retry-" + UUID.randomUUID()))
        val blob = "20c898a2957b4f58afa593749fee3cbd"
        val fixture = File(harness.root, "fixture.mp4")
        val attempts = AtomicInteger()
        val admission = MediaAdmission()
        val store = MediaStore(harness.store)
        lateinit var playback: MediaCoordinator
        lateinit var surface: InlinePlayerSurface
        lateinit var gallery: MediaGalleryController
        var cleanup: (() -> Unit)? = null
        var closed = 0
        lateinit var retry: View
        // Читаем условие на экране до предельного срока и показываем имя этапа, если он не
        // завершился. Так раннее подтверждение не принимается за готовность видео.
        fun await(description: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 8_000
            do {
                var ready = false
                instrumentation.runOnMainSync { ready = condition() }
                if (ready) return
                SystemClock.sleep(25)
            } while (SystemClock.uptimeMillis() < deadline)
            fail(description)
        }
        try {
            TestFixtures.assets.open("real-media/backend-$blob.mp4").use { source ->
                fixture.outputStream().use { source.copyTo(it) }
            }
            val video = Attachment(
                blob, "remote/$blob.mp4", "video/mp4", fixture.length(), 640, 384, 1, null, blob,
            )
            val row = Message(
                31, 2, OrderKey(31, 31), MessageDirection.INCOMING, 0, MessageKind.VIDEO,
                text = "", caption = "Ролик", replyToID = null, revision = 1,
                attachments = listOf(video),
            )
            instrumentation.runOnMainSync {
                store.remotePlayback = { requested, _ ->
                    assertEquals(video, requested)
                    if (attempts.incrementAndGet() == 1) throw MediaException(MediaError.UNAVAILABLE)
                    MediaPlaybackSource(file = fixture)
                }
                playback = MediaCoordinator(admission, store)
                cleanup = playback::close
                surface = InlinePlayerSurface(activity, playback)
                gallery = MediaGalleryController(activity, row, harness.repo, store, playback, surface) {
                    closed++
                }
                cleanup = {
                    gallery.close()
                    playback.close()
                }
                val before = WindowInspector.getGlobalWindowViews().toSet()
                gallery.show()
                val window = WindowInspector.getGlobalWindowViews().single { it !in before }
                retry = window.allViews.single { it.contentDescription == "Повторить загрузку" }
            }
            await("После ошибки загрузки должна появиться кнопка повтора") {
                retry.isShown && playback.lastError != null
            }
            assertEquals(1, attempts.get())
            instrumentation.runOnMainSync { assertTrue(retry.performClick()) }
            await("После повтора должен появиться первый кадр ролика") {
                surface.hasVideoFrame && playback.currentMessageID == row.id &&
                    playback.lastError == null && !retry.isShown
            }
            assertEquals(2, attempts.get())
            instrumentation.runOnMainSync { gallery.close(returnToChat = false) }
            await("Уход из чата освобождает проигрыватель") {
                playback.currentMessageID == null && playback.activePlayerCount == 0 &&
                    surface.parent == null
            }
            assertEquals(1, closed)
        } finally {
            instrumentation.runOnMainSync {
                cleanup?.invoke()
                store.close()
                admission.close()
                activity.finish()
            }
            harness.close()
        }
    }
}
