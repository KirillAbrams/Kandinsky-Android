// Проверки галереи на андроиде при удалении текущего результата и ошибке источника. Они следят,
// чтобы поздняя история не открыла закрытое окно и чтобы повтор действительно запустил скачанный
// ролик.

package dev.kirill.kandinsky.chat.media

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.core.view.allViews
import androidx.test.platform.app.InstrumentationRegistry
import dev.kirill.kandinsky.chat.domain.Attachment
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
import dev.kirill.kandinsky.chat.network.NetworkCredentials
import dev.kirill.kandinsky.chat.results.MediaGalleryController
import dev.kirill.kandinsky.chat.storage.ChatRepository
import dev.kirill.kandinsky.chat.support.RepositoryHarness
import dev.kirill.kandinsky.chat.support.TestFixtures
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class MediaGalleryLifecycleTest {
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
        var cleanup: (() -> Unit)? = null
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
                val gallery = MediaGalleryController(activity, row, harness.repo, store, playback, surface) {}
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
