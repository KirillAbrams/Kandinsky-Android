// Проверяет подпись и кнопки при просмотре отдельной фотографии.

package dev.kirill.kandinsky.chat.media

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.inspector.WindowInspector
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.kirill.kandinsky.chat.domain.Attachment
import dev.kirill.kandinsky.chat.domain.AttachmentDraft
import dev.kirill.kandinsky.chat.domain.Cancellation
import dev.kirill.kandinsky.chat.domain.Message
import dev.kirill.kandinsky.chat.domain.MessageDirection
import dev.kirill.kandinsky.chat.domain.MessageKind
import dev.kirill.kandinsky.chat.domain.OrderKey
import dev.kirill.kandinsky.chat.rendering.ChatScrollHost
import dev.kirill.kandinsky.chat.rendering.KandinskyMediaAction
import dev.kirill.kandinsky.chat.rendering.attachPlayback
import dev.kirill.kandinsky.chat.rendering.performMediaAction
import dev.kirill.kandinsky.chat.results.PhotoViewerController
import dev.kirill.kandinsky.chat.support.RepositoryHarness
import dev.kirill.kandinsky.chat.support.TestFixtures
import dev.kirill.kandinsky.chat.support.awaitResult
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StandalonePhotoViewerFlowTest {
    @Test
    fun standaloneChatPhotoRetriesTheOriginalAndReturnsToTheSameHost() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".cleanup"))
        val device = UiDevice.getInstance(instrumentation)
        device.wakeUp()
        val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val activity = instrumentation.startActivitySync(intent) as Activity
        val folder = File(context.cacheDir, "photo-retry-" + UUID.randomUUID())
        val harness = RepositoryHarness.open(folder)
        val source = File(folder, "original.jpg")
        TestFixtures.assets.open("media/photo-000.jpg").use { input ->
            source.outputStream().use { output -> input.copyTo(output) }
        }
        val attempts = AtomicInteger()
        val retryStarted = CountDownLatch(1)
        val finishRetry = CountDownLatch(1)
        val sharpFrameDisplayed = CountDownLatch(1)
        var media: MediaStore? = null
        var coordinator: MediaCoordinator? = null
        var admission: MediaAdmission? = null
        var host: ChatScrollHost? = null

        try {
            val id = UUID.randomUUID().toString()
            val attachment = Attachment(
                id = id,
                relativePath = "remote/0123456789abcdef0123456789abcdef.jpg",
                mime = "image/jpeg",
                byteSize = source.length(),
                rawWidth = 0,
                rawHeight = 0,
                orientation = 1,
                durationMs = null,
                thumbnailKey = id,
            )
            instrumentation.runOnMainSync {
                val store = MediaStore(harness.store)
                media = store
                store.remoteFile = { _, preview, transfer ->
                    if (!preview) {
                        val attempt = attempts.incrementAndGet()
                        if (attempt == 1) throw MediaException(MediaError.UNAVAILABLE)
                        if (attempt == 2) {
                            transfer.reportProgress(0.4)
                            retryStarted.countDown()
                            if (!finishRetry.await(10, TimeUnit.SECONDS)) {
                                throw MediaException(MediaError.CANCELLED)
                            }
                        }
                    }
                    source to "image/jpeg"
                }
                val owners = MediaAdmission()
                admission = owners
                val playback = MediaCoordinator(owners, store)
                coordinator = playback
                val chat = ChatScrollHost(activity)
                host = chat
                activity.setContentView(chat)
                chat.configureMedia(store)
                chat.attachPlayback(playback, store)
                val row = Message(
                    id = 11, chatID = 2, order = OrderKey(11, 11),
                    direction = MessageDirection.INCOMING, savedAtMs = 0,
                    kind = MessageKind.PHOTO, text = "", caption = "Отдельный снимок",
                    replyToID = null, revision = 1, attachments = listOf(attachment),
                )
                assertNull(chat.repository)
                val frame = RectF(0f, 0f, 260f, 150f)
                chat.performMediaAction(KandinskyMediaAction.EXPAND, row, frame)
                val presented = checkNotNull(chat.photoViewer)
                assertFalse(chat.scene.mediaVisible)
                chat.performMediaAction(KandinskyMediaAction.EXPAND, row, frame)
                assertTrue(chat.photoViewer === presented)
                presented.onPreviewReady = {
                    presented.retainedPreview()?.use { image ->
                        if (attempts.get() >= 2 && !image.isTinyPreview) {
                            sharpFrameDisplayed.countDown()
                        }
                    }
                }
            }

            val retry = By.res("media.retry").desc("Не удалось загрузить. Повторить")
            val progress = By.res("media.cancel")
            val retryButton = checkNotNull(device.wait(Until.findObject(retry), 8000))
            assertTrue(device.wait(Until.gone(progress), 4000))
            retryButton.click()
            assertTrue(retryStarted.await(8, TimeUnit.SECONDS))
            assertTrue(device.wait(Until.hasObject(progress), 4000))
            finishRetry.countDown()
            assertTrue(sharpFrameDisplayed.await(8, TimeUnit.SECONDS))
            assertTrue(device.wait(Until.gone(progress), 4000))
            assertTrue(device.wait(Until.gone(By.res("media.retry")), 4000))
            checkNotNull(device.wait(Until.findObject(By.res("photo.close")), 4000)).click()
            assertTrue(device.wait(Until.gone(By.res("photo.close")), 6_000))
            instrumentation.runOnMainSync {
                val chat = checkNotNull(host)
                assertNull(chat.photoViewer)
                assertTrue(chat.scene.mediaVisible)
            }
        } finally {
            finishRetry.countDown()
            instrumentation.runOnMainSync {
                host?.close()
                coordinator?.close()
                admission?.close()
                media?.close()
                activity.finish()
            }
            harness.close()
            folder.deleteRecursively()
        }
    }

    @Test
    fun aTapTogglesTheCaptionAndDoubleTapZoomsBeforeASwipeClosesThePhoto() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".cleanup"))
        val device = UiDevice.getInstance(instrumentation)
        device.wakeUp()
        val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val activity = instrumentation.startActivitySync(intent) as Activity
        val folder = File(context.cacheDir, "caption-" + UUID.randomUUID())
        val harness = RepositoryHarness.open(folder)
        var media: MediaStore? = null
        var viewer: PhotoViewerController? = null

        try {
            instrumentation.runOnMainSync { media = MediaStore(harness.store) }
            val localMedia = checkNotNull(media)
            val draft = awaitResult<AttachmentDraft> { result ->
                instrumentation.runOnMainSync {
                    localMedia.stage({ TestFixtures.assets.open("media/photo-000.jpg") }, "image/jpeg", result)
                }
                Cancellation {}
            }
            val attachment = Attachment(
                draft.id, draft.relativePath, draft.mime, draft.byteSize, draft.rawWidth,
                draft.rawHeight, draft.orientation, draft.durationMs, draft.thumbnailKey,
            )
            val caption = "Оригинальная подпись к фотографии."
            val displayed = CountDownLatch(1)
            val closed = CountDownLatch(1)
            lateinit var photoWindow: View
            instrumentation.runOnMainSync {
                val previousWindows = WindowInspector.getGlobalWindowViews().toSet()
                val presented = PhotoViewerController(activity, attachment, localMedia, caption = caption)
                viewer = presented
                presented.onPreviewReady = {
                    presented.retainedPreview()?.let {
                        it.close()
                        displayed.countDown()
                    }
                }
                presented.onClose = { closed.countDown() }
                presented.show()
                photoWindow = WindowInspector.getGlobalWindowViews().first { it !in previousWindows }
            }
            assertTrue(displayed.await(8, TimeUnit.SECONDS))
            val label = By.res("viewer.caption")
            val close = By.res("photo.close")
            assertEquals(caption, checkNotNull(device.wait(Until.findObject(label), 6000)).text)
            val bounds = Rect()
            val focusDeadline = SystemClock.uptimeMillis() + 5000
            while (bounds.isEmpty && SystemClock.uptimeMillis() < focusDeadline) {
                instrumentation.runOnMainSync {
                    if (photoWindow.hasWindowFocus()) {
                        val origin = IntArray(2)
                        photoWindow.getLocationOnScreen(origin)
                        bounds.set(
                            origin[0], origin[1], origin[0] + photoWindow.width,
                            origin[1] + photoWindow.height,
                        )
                    }
                }
                if (bounds.isEmpty) SystemClock.sleep(20)
            }
            assertFalse("Просмотрщик должен получить ввод до первого касания", bounds.isEmpty)

            fun tapPhoto() {
                assertTrue(device.click(bounds.centerX(), bounds.centerY()))
            }

            tapPhoto()
            assertTrue(device.wait(Until.gone(label), 6000))
            assertTrue(device.wait(Until.gone(close), 6000))
            tapPhoto()
            assertEquals(caption, checkNotNull(device.wait(Until.findObject(label), 6000)).text)
            fun doubleTapPhoto() {
                val x = bounds.exactCenterX()
                val y = bounds.exactCenterY()
                repeat(2) { index ->
                    val started = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(
                            started, SystemClock.uptimeMillis(), action, x, y, 0,
                        )
                        try {
                            event.source = InputDevice.SOURCE_TOUCHSCREEN
                            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
                        } finally {
                            event.recycle()
                        }
                    }
                    if (index == 0) SystemClock.sleep(85)
                }
            }

            fun verticalSwipe(distancePercent: Int) {
                val x = bounds.centerX()
                val height = bounds.height()
                val from = bounds.top + height / 3
                assertTrue(device.swipe(x, from, x, from + height * distancePercent / 100, 36))
            }

            doubleTapPhoto()
            assertTrue(device.wait(Until.hasObject(By.desc("Фотография, масштаб 300%")), 6000))
            instrumentation.runOnMainSync { assertTrue(checkNotNull(viewer).pageIsZoomed) }
            verticalSwipe(32)
            assertTrue(device.hasObject(close))
            doubleTapPhoto()
            assertTrue(device.wait(Until.hasObject(By.desc("Фотография, масштаб 100%")), 6000))
            instrumentation.runOnMainSync { assertTrue(!checkNotNull(viewer).pageIsZoomed) }
            verticalSwipe(6)
            assertTrue(device.hasObject(close))
            verticalSwipe(32)
            assertTrue(closed.await(6, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync {
                viewer?.close()
                media?.close()
                activity.finish()
            }
            harness.close()
            folder.deleteRecursively()
        }
    }
}
