package dev.kirill.kandinsky.chat.media

import android.graphics.RectF
import androidx.test.platform.app.InstrumentationRegistry
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.rendering.*
import dev.kirill.kandinsky.chat.support.*
import dev.kirill.kandinsky.chat.support.TestFixtures
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class MediaImageLifetimeTest {
    @Test
    fun albumPaintsEveryNativeImageAndRetiresAllLeasesOutsideViewport() {
        val i = InstrumentationRegistry.getInstrumentation()
        fun main(action: () -> Unit) = i.runOnMainSync(action)
        val h =
            RepositoryHarness.open(
                File(i.targetContext.cacheDir, "album-pixels-" + UUID.randomUUID())
            )
        val media = MediaStore(h.store, h.repo)
        val colors =
            listOf(
                android.graphics.Color.RED,
                android.graphics.Color.GREEN,
                android.graphics.Color.BLUE,
                android.graphics.Color.YELLOW,
                android.graphics.Color.MAGENTA,
            )
        val drafts =
            colors.mapIndexed { index, color ->
                val file = File(h.root, "tile-$index.jpg")
                val bitmap =
                    android.graphics.Bitmap.createBitmap(
                        96,
                        96,
                        android.graphics.Bitmap.Config.ARGB_8888,
                    )
                bitmap.eraseColor(color)
                file.outputStream().use {
                    assertTrue(
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, it)
                    )
                }
                bitmap.recycle()
                awaitResult<AttachmentDraft> { cb ->
                    main { media.stage({ file.inputStream() }, "image/jpeg", cb) }
                    Cancellation {}
                }
            }
        val row =
            awaitResult<MessageDelta> { cb ->
                    main {
                        media.commit(
                            drafts,
                            SendCommand(UUID.randomUUID().toString(), 2, MessageKind.PHOTO),
                            cb,
                        )
                    }
                    Cancellation {}
                }
                .inserted[0]
        val prepared = PreparedText(row, TextLayoutKey(row.id, 1, 300, 17, 1))
        val scene = RetainedChatScene()
        val snapshot =
            SceneSnapshot(
                scene.ticket,
                listOf(
                    SceneFragment(
                        prepared,
                        0,
                        RectF(10f, 20f, 10 + prepared.width, 20 + prepared.height),
                    )
                ),
            )
        main {
            scene.mediaStore = media
            scene.imageViewport = RectF(0f, 0f, 400f, 900f)
            scene.apply(snapshot)
        }
        val end = android.os.SystemClock.uptimeMillis() + 5000
        var ready = false
        while (!ready && android.os.SystemClock.uptimeMillis() < end) {
            main { ready = (0 until 5).all { scene.imageReady(row.id, it) } }
            Thread.sleep(10)
        }
        assertTrue("Every cell needs real decoded pixels", ready)
        main {
            val bitmap =
                android.graphics.Bitmap.createBitmap(
                    400,
                    900,
                    android.graphics.Bitmap.Config.ARGB_8888,
                )
            scene.drawSoftware(android.graphics.Canvas(bitmap))
            prepared.mediaItemRects.forEachIndexed { index, rect ->
                val pixel =
                    bitmap.getPixel((rect.centerX() + 10).toInt(), (rect.centerY() + 20).toInt())
                val color = colors[index]
                assertTrue(
                    kotlin.math.abs(
                        android.graphics.Color.red(pixel) - android.graphics.Color.red(color)
                    ) < 25
                )
                assertTrue(
                    kotlin.math.abs(
                        android.graphics.Color.green(pixel) - android.graphics.Color.green(color)
                    ) < 25
                )
                assertTrue(
                    kotlin.math.abs(
                        android.graphics.Color.blue(pixel) - android.graphics.Color.blue(color)
                    ) < 25
                )
            }
            bitmap.recycle()
            val recordings = scene.metrics.recordings
            scene.apply(snapshot)
            assertEquals(recordings, scene.metrics.recordings)
            scene.imageViewport = RectF(0f, 2000f, 400f, 2900f)
            scene.apply(snapshot)
            for (index in 0 until 5) assertNull(
                "Offscreen cells detach every decoded lease",
                scene.preview(row.id, index),
            )
            scene.close()
            media.close()
            prepared.close()
        }
        var bytes = 1
        val released = android.os.SystemClock.uptimeMillis() + 2000
        while (bytes > 0 && android.os.SystemClock.uptimeMillis() < released) {
            main { bytes = media.bytesUsed }
            Thread.sleep(10)
        }
        assertEquals(0, bytes)
        h.close()
    }

    @Test
    fun everyAlbumAttachmentHasAnOrderedNonOverlappingHitRectangle() {
        for (count in 2..10) for (width in listOf(160, 260, 340)) {
            val assets =
                (0 until count).map {
                    Attachment(
                        "tile-$it",
                        "seed/$it.jpg",
                        if (it % 3 == 1) "video/mp4" else "image/jpeg",
                        100,
                        if (it % 2 == 0) 1600 else 900,
                        if (it % 2 == 0) 900 else 1600,
                        if (it % 2 == 0) 1 else 6,
                        null,
                        "",
                    )
                }
            val row =
                Message(
                    1,
                    2,
                    OrderKey(1, 1),
                    MessageDirection.INCOMING,
                    1,
                    MessageKind.PHOTO,
                    "",
                    "Альбом",
                    null,
                    1,
                    assets,
                )
            val text = PreparedText(row, TextLayoutKey(1, 1, width, 17, 1))
            try {
                val rects = text.mediaItemRects
                assertEquals(
                    "Every attachment must remain visible and independently tappable",
                    count,
                    rects.size,
                )
                rects.forEachIndexed { index, rect ->
                    assertTrue(
                        rect.width() > 0 &&
                            rect.height() > 0 &&
                            rect.left >= 0 &&
                            rect.right <= text.width &&
                            rect.bottom < text.height
                    )
                    rects.drop(index + 1).forEach {
                        assertFalse("Album cells cannot overlap", RectF.intersects(rect, it))
                    }
                }
            } finally {
                text.close()
            }
        }
    }

    @Test
    fun openingTheKeyboardKeepsTheVisiblePhoto() {
        val i = InstrumentationRegistry.getInstrumentation()
        val context = i.targetContext
        val device = androidx.test.uiautomator.UiDevice.getInstance(i)
        device.wakeUp()
        val activity =
            i.startActivitySync(
                context.packageManager.getLaunchIntentForPackage(context.packageName)!!.apply {
                    addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                    )
                }
            ) as dev.kirill.kandinsky.chat.app.MainActivity
        assertTrue(
            device.wait(
                androidx.test.uiautomator.Until.hasObject(
                    androidx.test.uiautomator.By.pkg(i.targetContext.packageName)
                ),
                15000,
            )
        )
        val h =
            RepositoryHarness.open(File(context.cacheDir, "keyboard-photo-" + UUID.randomUUID()))
        lateinit var runtime: LocalRuntime
        lateinit var state: dev.kirill.kandinsky.chat.conversation.ConversationState
        lateinit var chrome: dev.kirill.kandinsky.chat.conversation.ConversationChrome
        lateinit var scene: RetainedChatScene
        i.runOnMainSync {
            runtime = LocalRuntime(activity, h.store)
            state = dev.kirill.kandinsky.chat.conversation.ConversationState(h.repo)
        }
        try {
            val draft =
                awaitResult<AttachmentDraft> { cb ->
                    i.runOnMainSync {
                        runtime.media.stage(
                            { TestFixtures.assets.open("media/photo-000.jpg") },
                            "image/jpeg",
                            cb,
                        )
                    }
                    Cancellation {}
                }
            val row =
                awaitResult<MessageDelta> {
                        h.repo.send(
                            SendCommand(
                                UUID.randomUUID().toString(),
                                2,
                                MessageKind.PHOTO,
                                attachments = listOf(draft),
                            ),
                            it,
                        )
                    }
                    .inserted
                    .single()
            awaitResult<Unit> { cb ->
                i.runOnMainSync { state.open(2, cb) }
                Cancellation {}
            }
            awaitResult<Unit> { cb ->
                i.runOnMainSync {
                    chrome =
                        dev.kirill.kandinsky.chat.conversation.ConversationChrome(
                            activity,
                            2,
                            state,
                            runtime,
                        ) {}
                    activity.setContentView(chrome)
                    chrome.openHistory(h.repo, null, cb)
                }
                Cancellation {}
            }
            i.runOnMainSync {
                val host =
                    dev.kirill.kandinsky.chat.conversation.ConversationChrome::class
                        .java
                        .getDeclaredField("timeline")
                        .apply { isAccessible = true }
                        .get(chrome) as ChatScrollHost
                scene =
                    ChatScrollHost::class
                        .java
                        .getDeclaredField("scene")
                        .apply { isAccessible = true }
                        .get(host) as RetainedChatScene
                chrome.setMediaVisible(true)
            }
            val end = android.os.SystemClock.uptimeMillis() + 5000
            var ready = false
            while (!ready && android.os.SystemClock.uptimeMillis() < end) {
                i.runOnMainSync { ready = scene.imageReady(row.id) }
                android.os.SystemClock.sleep(20)
            }
            assertTrue("Actual photo pixels must be ready before checking retention", ready)
            device
                .wait(
                    androidx.test.uiautomator.Until.findObject(
                        androidx.test.uiautomator.By.res("composer.input").enabled(true)
                    ),
                    5000,
                )!!
                .click()
            i.runOnMainSync {
                assertTrue(
                    "Opening a keyboard must preserve photos in the still-visible chat",
                    scene.imageReady(row.id),
                )
            }
            device.pressBack()
        } finally {
            i.runOnMainSync {
                activity.setContentView(android.widget.FrameLayout(activity))
                state.close()
                runtime.playback.close()
                runtime.media.close()
                runtime.admission.close()
                activity.finish()
            }
            h.close()
        }
    }

    // Готовность изображения не должна вызывать повторную запись текста.
    @Test
    fun decodedNativeImageDoesNotRerecordTextAndClosesWithVisibleScene() {
        val i = InstrumentationRegistry.getInstrumentation()
        val context = i.targetContext
        fun main(action: () -> Unit) = i.runOnMainSync(action)
        val h = RepositoryHarness.open(File(context.cacheDir, "slot-" + UUID.randomUUID()))
        lateinit var media: MediaStore
        main { media = MediaStore(h.store, h.repo) }
        val draft =
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
        val delta =
            awaitResult<MessageDelta> { cb ->
                main {
                    media.commit(
                        listOf(draft),
                        SendCommand(
                            UUID.randomUUID().toString(),
                            2,
                            MessageKind.PHOTO,
                            caption = "Подпись",
                        ),
                        cb,
                    )
                }
                Cancellation {}
            }
        val row = delta.inserted[0]
        val text = PreparedText(row, TextLayoutKey(row.id, 1, 260, 17, 1))
        val rect = text.mediaRect!!
        assertTrue(rect.height() > 100)
        assertTrue(text.accessibleLines(0f, 1000f, 128)[0].frame.top > rect.bottom)
        val ready = CountDownLatch(1)
        val scene = RetainedChatScene()
        fun snapshot(y: Float) =
            SceneSnapshot(
                scene.ticket,
                listOf(SceneFragment(text, 0, RectF(0f, y, text.width, y + text.height))),
            )
        var keptOccludedImage = true
        var recordings = 0
        main {
            scene.mediaStore = media
            scene.onImageReady = { if (scene.imageReady(row.id)) ready.countDown() }
            scene.apply(snapshot(0f))
            recordings = scene.metrics.recordings
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        main {
            assertTrue(media.bytesUsed > 0)
            for (n in 0 until 1000) scene.apply(snapshot((n % 30).toFloat()))
            assertEquals(recordings, scene.metrics.recordings)
            scene.mediaVisible = false
            scene.apply(snapshot(0f))
            keptOccludedImage = scene.imageReady(row.id)
            scene.mediaVisible = true
            scene.apply(snapshot(0f))
            scene.close()
            media.close()
            assertEquals(
                "Visible native fragments detach synchronously",
                0,
                scene.metrics.liveFragments,
            )
        }
        val deadline = android.os.SystemClock.uptimeMillis() + 1000
        var remaining = Int.MAX_VALUE
        while (remaining > 0 && android.os.SystemClock.uptimeMillis() < deadline) {
            main { remaining = media.bytesUsed }
            if (remaining > 0) Thread.sleep(10)
        }
        assertEquals("Cancelled quality upgrade must release its pixel lease", 0, remaining)
        text.close()
        h.close()
        assertFalse("An opaque viewer must retire image owners behind it", keptOccludedImage)
    }
}
