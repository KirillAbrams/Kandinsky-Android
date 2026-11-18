// Проверки фото и альбомов на андроиде. Они смотрят, сохраняются ли готовые точки при сдвиге и
// клавиатуре, когда появляется отмена передачи и что освобождается после закрытия сцены.

package dev.kirill.kandinsky.chat.media

import dev.kirill.kandinsky.chat.app.ChatRuntime
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
    // Удерживаем чтение местного фото и проверяем, что при возврате маленький рисунок не
    // мелькнул. Сетевой путь здесь считается ошибкой, а после разрешения чтения ждём чёткий кадр.
    @Test
    fun localPhotoReturnPrefersSavedPixelsOverInlineBlur() {
        val i = InstrumentationRegistry.getInstrumentation()
        val h = RepositoryHarness.open(File(i.targetContext.cacheDir, "local-photo-" + UUID.randomUUID()))
        val media = MediaStore(h.store, studioOriginals = true)
        val release = CountDownLatch(1)
        val queried = CountDownLatch(1)
        val rendered = CountDownLatch(1)
        var imageSlot: MediaImageSlot? = null
        try {
            val draft = awaitResult<AttachmentDraft> { complete ->
                i.runOnMainSync {
                    media.stage({ TestFixtures.assets.open("media/photo-000.jpg") }, "image/jpeg", complete)
                }
                Cancellation {}
            }
            val file = media.fileURL(draft.relativePath)
            val photo = Attachment(
                id = draft.id, relativePath = "remote/previously-viewed.jpg", mime = draft.mime,
                byteSize = draft.byteSize, rawWidth = draft.rawWidth, rawHeight = draft.rawHeight,
                orientation = draft.orientation, durationMs = null, thumbnailKey = draft.id,
                tinyPreview = checkNotNull(draft.tinyPreview),
            )
            val tiny = awaitResult<PreparedImage> { complete ->
                i.runOnMainSync { media.tinyPreview(photo, complete) }
                Cancellation {}
            }
            tiny.use {
                i.runOnMainSync {
                    media.remoteCached = { _, _, _ ->
                        queried.countDown()
                        release.await(4, TimeUnit.SECONDS)
                        file to "image/jpeg"
                    }
                    media.remoteFile = { _, _, _ -> error("A saved photo must not start a server request") }
                    val slot = MediaImageSlot(media) { if (imageSlot?.hasSharpFrame == true) rendered.countDown() }
                    imageSlot = slot
                    slot.bind(photo, RectF(0f, 0f, 190f, 180f), kind = MessageKind.PHOTO)
                    slot.seed(checkNotNull(tiny.retained()))
                }
                assertTrue(queried.await(2, TimeUnit.SECONDS))
                var showedTiny = false
                i.runOnMainSync { showedTiny = imageSlot?.preview()?.use { it.isTinyPreview } == true }
                assertFalse("A local decode must not flash the inline blur", showedTiny)
                release.countDown()
                assertTrue(rendered.await(2, TimeUnit.SECONDS))
            }
        } finally {
            release.countDown()
            i.runOnMainSync {
                imageSlot?.close()
                media.close()
            }
            h.close()
        }
    }

    // Начинаем с местной проверки без кнопки передачи. Затем даём сетевой ход, ждём управление
    // отменой и проверяем, что нажатие дошло до загрузки.
    @Test
    fun videoTransferControlWaitsForActualNetworkProgress() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val h = RepositoryHarness.open(File(instrument.targetContext.cacheDir, "video-transfer-" + UUID.randomUUID()))
        val media = MediaStore(h.store)
        val asset = Attachment(
            id = "uncached-video", relativePath = "remote/uncached.mp4", mime = "video/mp4",
            byteSize = 128, rawWidth = 96, rawHeight = 96, orientation = 1, durationMs = 1000,
            thumbnailKey = "uncached-video",
        )
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val presented = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        lateinit var slot: MediaImageSlot
        try {
            instrument.runOnMainSync {
                media.remoteFile = { _, _, token ->
                    entered.countDown()
                    release.await(3, TimeUnit.SECONDS)
                    token.onCancel { cancelled.countDown() }
                    token.reportProgress(0.0)
                    cancelled.await(3, TimeUnit.SECONDS)
                    throw MediaException(MediaError.CANCELLED)
                }
                slot = MediaImageSlot(media) {
                    if (release.count == 0L && slot.loadAction()?.second == "Отменить загрузку") {
                        presented.countDown()
                    }
                }
                slot.bind(asset, RectF(0f, 0f, 150f, 150f), kind = MessageKind.VIDEO)
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            instrument.runOnMainSync {
                assertNull("Reading local data must not present itself as a transfer", slot.loadAction())
            }
            release.countDown()
            assertTrue("A real transfer must show the cancel action", presented.await(2, TimeUnit.SECONDS))
            instrument.runOnMainSync { assertTrue(slot.performLoadAction(75f, 75f)) }
            assertTrue(cancelled.await(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            instrument.runOnMainSync { slot.close(); media.close() }
            h.close()
        }
    }

    // Показываем маленький и чёткий кадры одного фото, затем снова привязываем его. Поздний
    // маленький рисунок не перекрывает чёткий, а управление загрузкой остаётся скрытым.
    @Test
    fun rebindingPhotoKeepsSharpPixelsAndRejectsLateBlur() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val harness = RepositoryHarness.open(File(instrument.targetContext.cacheDir, "photo-return-" + UUID.randomUUID()))
        val media = MediaStore(harness.store)
        lateinit var slot: MediaImageSlot
        try {
            instrument.runOnMainSync { slot = MediaImageSlot(media) {} }
            val draft = awaitResult<AttachmentDraft> { complete ->
                instrument.runOnMainSync {
                    media.stage({ TestFixtures.assets.open("media/photo-000.jpg") }, "image/jpeg", complete)
                }
                Cancellation {}
            }
            val attachment = Attachment(
                id = draft.id, relativePath = draft.relativePath, mime = draft.mime,
                byteSize = draft.byteSize, rawWidth = draft.rawWidth, rawHeight = draft.rawHeight,
                orientation = draft.orientation, durationMs = draft.durationMs,
                thumbnailKey = draft.id, tinyPreview = checkNotNull(draft.tinyPreview),
            )
            val tiny = awaitResult<PreparedImage> { complete ->
                instrument.runOnMainSync { media.tinyPreview(attachment, complete) }
                Cancellation {}
            }
            val sharp = awaitResult<PreparedImage> { complete ->
                instrument.runOnMainSync {
                    media.thumbnail(attachment, ThumbnailKey(256), 0) { _, result -> complete(result) }
                }
                Cancellation {}
            }
            tiny.use {
                sharp.use {
                    instrument.runOnMainSync {
                        val frame = RectF(0f, 0f, 210f, 160f)
                        slot.bind(attachment, frame, maxPixel = 1024, kind = MessageKind.PHOTO)
                        slot.seed(checkNotNull(tiny.retained()))
                        slot.seed(checkNotNull(sharp.retained()))
                        assertEquals("A ready photo must replace its tiny preview immediately",
                            false, slot.preview()?.use { it.isTinyPreview })

                        slot.bind(attachment, frame, maxPixel = 512, kind = MessageKind.PHOTO)
                        slot.seed(checkNotNull(tiny.retained()))
                        assertEquals("Rebinding the same photo must not replace it with blur",
                            false, slot.preview()?.use { it.isTinyPreview })
                        assertNull("Refining visible photo pixels must not show a transfer button",
                            slot.loadAction())
                    }
                }
            }
        } finally {
            instrument.runOnMainSync { slot.close(); media.close() }
            harness.close()
        }
    }

    // Сдвигаем сцену дробными шагами. Проверяем, что готовая высота и владелец фото прежние, а
    // меняется лишь его экранное положение.
    @Test
    fun fractionalScrollingKeepsTheNativePhotoSize() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val harness = RepositoryHarness.open(
            File(instrument.targetContext.cacheDir, "photo-size-" + UUID.randomUUID())
        )
        val media = MediaStore(harness.store)
        try {
            instrument.runOnMainSync {
                val scene = RetainedChatScene()
                scene.mediaStore = media
                scene.imageViewport = RectF(0f, 0f, 400f, 900f)
                val row = Message(
                    id = 1L, chatID = 2L, order = OrderKey(1L, 1L),
                    direction = MessageDirection.INCOMING, savedAtMs = 0L,
                    kind = MessageKind.PHOTO, text = "", caption = "", replyToID = null,
                    revision = 1, attachments = listOf(
                        Attachment(
                            id = "square", relativePath = "media/square.jpg", mime = "image/jpeg",
                            byteSize = 1024, rawWidth = 96, rawHeight = 96, orientation = 1,
                            durationMs = null, thumbnailKey = "square",
                        ),
                    ),
                )
                val prepared = PreparedText(row, TextLayoutKey(1, 1, 300, 17, 1))
                val rect = checkNotNull(prepared.mediaRect)
                // Ставим подготовленную строку на заданной высоте. Меняем координату без
                // повторного измерения, чтобы заметить лишнюю переделку кадра.
                fun apply(y: Float) = scene.apply(
                    SceneSnapshot(scene.ticket, listOf(
                        SceneFragment(prepared, 0, RectF(10f, y, 10f + prepared.width, y + prepared.height))
                    ))
                )
                try {
                    apply(0f)
                    val field = RetainedChatScene::class.java.getDeclaredField("nodes")
                    field.isAccessible = true
                    val node = (field.get(scene) as Map<*, *>).values.single() as MessageNode
                    val image = checkNotNull(node.image)
                    assertEquals(296, image.native.height)
                    for (offset in listOf(214.0909119f, 215.0909119f, 216.0909119f, 0f)) {
                        apply(offset)
                        assertSame(image, node.image)
                        assertEquals("Translation must keep the recorded image height", 296, image.native.height)
                        assertEquals(rect.top + offset, image.native.translationY, 0.001f)
                    }
                } finally {
                    scene.close()
                    prepared.close()
                }
            }
        } finally {
            instrument.runOnMainSync { media.close() }
            harness.close()
        }
    }

    // Готовим набор разных ячеек и сравниваем цвета нарисованных точек. После ухода за экран все
    // кадры освобождаются, а прокрутка не перезаписывает текст.
    @Test
    fun albumPaintsEveryNativeImageAndRetiresAllLeasesOutsideViewport() {
        val i = InstrumentationRegistry.getInstrumentation()
        // Выполняем создание, сдвиг и закрытие альбома в потоке экрана. Иначе состояние слоёв
        // могло бы меняться во время проверки.
        fun main(action: () -> Unit) = i.runOnMainSync(action)
        val h =
            RepositoryHarness.open(
                File(i.targetContext.cacheDir, "album-pixels-" + UUID.randomUUID())
            )
        val media = MediaStore(h.store)
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
                        h.repo.send(
                            SendCommand(
                                UUID.randomUUID().toString(), 2, MessageKind.PHOTO,
                                attachments = drafts,
                            ),
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
            scene.draw(android.graphics.Canvas(bitmap))
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

    // Проверяем альбомы разных размеров. Каждый файл получает положительную рамку внутри строки в
    // исходном порядке, без наложения на соседей.
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

    // Открываем переписку с настоящим фото и ждём чёткий кадр, затем показываем клавиатуру. Фото
    // обязано остаться под ней в видимой истории.
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
        lateinit var runtime: ChatRuntime
        lateinit var state: dev.kirill.kandinsky.chat.conversation.ConversationState
        lateinit var chrome: dev.kirill.kandinsky.chat.conversation.ConversationChrome
        lateinit var scene: RetainedChatScene
        i.runOnMainSync {
            runtime = ChatRuntime(h.store)
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
                    chrome = activity.createConversationChrome(2, state, runtime) {}
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

    // Ждём готовый рисунок у строки с подписью и сдвигаем сцену. Текст не записывается второй
    // раз, а после скрытия отменённое улучшение качества перестаёт удерживать память.
    @Test
    fun decodedNativeImageDoesNotRerecordTextAndClosesWithVisibleScene() {
        val i = InstrumentationRegistry.getInstrumentation()
        val context = i.targetContext
        // Ставим снимки сцены и читаем их готовность в потоке экрана. В этом же потоке закрываем
        // ресурс, чтобы проверить освобождение сразу после команды.
        fun main(action: () -> Unit) = i.runOnMainSync(action)
        val h = RepositoryHarness.open(File(context.cacheDir, "slot-" + UUID.randomUUID()))
        lateinit var media: MediaStore
        main { media = MediaStore(h.store) }
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
                    h.repo.send(
                        SendCommand(
                            UUID.randomUUID().toString(),
                            2,
                            MessageKind.PHOTO,
                            caption = "Подпись",
                            attachments = listOf(draft),
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
        // Собираем одну и ту же строку с новым вертикальным положением. Редакция и размеры
        // сохраняются, поэтому меняется только положение.
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
