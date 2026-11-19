// Проверки просмотра вложений на андроиде через видимое окно и настоящие жесты. Они сверяют
// изображения, подписи, размер, перелистывание и возврат проигрывателя после закрытия.

package dev.kirill.kandinsky.chat.media

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.results.MediaGalleryController
import dev.kirill.kandinsky.chat.results.PhotoViewerController
import dev.kirill.kandinsky.chat.support.*
import dev.kirill.kandinsky.chat.support.TestFixtures
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class PhotoViewerInteractionTest {
    // Ждём доступное поле переписки или форму входа, пока приложение восстанавливает стартовый
    // экран. Одно появление окна ещё не означает, что оно готово к проверке.
    private fun awaitApplicationContent(device: UiDevice) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (
                device.hasObject(By.res("composer.input")) ||
                    device.hasObject(By.res("account.username"))
            ) return
            SystemClock.sleep(50)
        }
        fail("The native conversation or account screen must finish opening before the viewer test")
    }

    // Листаем большие фото и измеряем готовые рисунки страниц. Неактивные изображения занимают
    // меньше памяти, чтобы следующий оригинал поместился.
    @Test
    fun pagingLargeOriginalsReleasesInactiveResolutionBeforeTheNextDecode() {
        val i = InstrumentationRegistry.getInstrumentation()
        val context = i.targetContext
        val device = UiDevice.getInstance(i)
        device.wakeUp()
        Configurator.getInstance().waitForIdleTimeout = 0
        val activity =
            i.startActivitySync(
                context.packageManager.getLaunchIntentForPackage(context.packageName)!!.apply {
                    addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                    )
                }
            ) as android.app.Activity
        awaitApplicationContent(device)
        val h = RepositoryHarness.open(File(context.cacheDir, "gallery-large-" + UUID.randomUUID()))
        lateinit var media: MediaStore
        val pages = mutableListOf<PhotoViewerController>()
        // Читаем ширину текущего кадра и сразу возвращаем своё удержание.
        fun width(index: Int): Int =
            pages[index].retainedPreview()?.use { it.bitmap?.width ?: 0 } ?: 0
        // Ждём заданного состояния страницы в потоке экрана. Ограниченное ожидание даёт фоновому
        // чтению закончиться и показывает этап при ошибке.
        fun state(label: String, predicate: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + 5000
            while (SystemClock.uptimeMillis() < end) {
                var ok = false
                i.runOnMainSync { ok = predicate() }
                if (ok) return
                SystemClock.sleep(10)
            }
            var detail = ""
            i.runOnMainSync {
                detail = "Ширина кадров ${pages.indices.map(::width)}, занято ${media.bytesUsed} байт"
            }
            fail("$label. $detail")
        }
        i.runOnMainSync { media = MediaStore(h.store) }
        try {
            val files =
                org.json
                    .JSONObject(
                        TestFixtures.assets.open("manifest.json").bufferedReader().use {
                            it.readText()
                        }
                    )
                    .getJSONArray("files")
            val name =
                (0 until files.length())
                    .map { files.getJSONObject(it) }
                    .first { it.getString("mime") == "image/jpeg" }
                    .getString("fileName")
            val source = File(h.root, "gallery-large.jpg")
            val photo =
                TestFixtures.assets.open("media/$name").use {
                    android.graphics.BitmapFactory.decodeStream(it)
                }!!
            val large = Bitmap.createBitmap(2048, 2048, Bitmap.Config.ARGB_8888)
            android.graphics
                .Canvas(large)
                .drawBitmap(
                    photo,
                    null,
                    android.graphics.Rect(0, 0, 2048, 2048),
                    android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
                )
            source.outputStream().use {
                assertTrue(large.compress(Bitmap.CompressFormat.JPEG, 95, it))
            }
            large.recycle()
            photo.recycle()
            val attachments =
                (0 until 3).map {
                    val draft =
                        awaitResult<AttachmentDraft> { cb ->
                            i.runOnMainSync {
                                media.stage({ source.inputStream() }, "image/jpeg", cb)
                            }
                            Cancellation {}
                        }
                    Attachment(
                        draft.id,
                        draft.relativePath,
                        draft.mime,
                        draft.byteSize,
                        draft.rawWidth,
                        draft.rawHeight,
                        draft.orientation,
                        draft.durationMs,
                        draft.thumbnailKey,
                        tinyPreview = draft.tinyPreview,
                    )
                }
            i.runOnMainSync {
                val host = android.widget.FrameLayout(activity)
                activity.setContentView(host)
                for ((index, attachment) in attachments.withIndex()) {
                    val page = PhotoViewerController(activity, attachment, media, embedded = true)
                    pages += page
                    host.addView(
                        page.pageView().apply {
                            translationX = index * device.displayWidth.toFloat()
                        },
                        android.widget.FrameLayout.LayoutParams(-1, -1),
                    )
                }
            }
            state("All neighbours have actual preview pixels") {
                pages.indices.all { width(it) > 0 }
            }
            i.runOnMainSync { pages[0].setPageActive(true) }
            state("The first real original reaches the native surface") { width(0) == 2048 }
            for (index in 1..2) {
                i.runOnMainSync {
                    pages[index - 1].setPageActive(false)
                    pages[index].setPageActive(true)
                }
                state(
                    "Selected original ${index+1} must not be blocked by a full-resolution neighbour"
                ) {
                    width(index) == 2048
                }
            }
            for (index in 1 downTo 0) {
                i.runOnMainSync {
                    media.releaseIdleImages()
                    pages[index + 1].setPageActive(false)
                    pages[index].setPageActive(true)
                }
                state("Reverse paging restores evicted original ${index+1}") {
                    width(index) == 2048
                }
            }
            i.runOnMainSync {
                for (index in 1..2) assertTrue(
                    "Neighbours retain lightweight pixels",
                    width(index) in 1..384,
                )
            }
            i.runOnMainSync {
                assertTrue(media.peakBytesUsed <= 48 * 1024 * 1024)
                pages.forEach { it.close() }
                media.close()
            }
            state("Closing retires every actual image lease") { media.bytesUsed == 0 }
        } finally {
            i.runOnMainSync {
                pages.forEach { it.close() }
                media.close()
                activity.finish()
            }
            h.close()
        }
    }

    // Готовим три соседних рисунка и отменяем чтение текущего оригинала. Свайп и обновление
    // истории не должны сдвинуть страницу или потерять соседние кадры.
    @Test
    fun adjacentPreviewsSurviveCancellingOnlyTheSelectedOriginal() {
        val i = InstrumentationRegistry.getInstrumentation()
        val context = i.targetContext
        val device = UiDevice.getInstance(i)
        device.wakeUp()
        Configurator.getInstance().waitForIdleTimeout = 0
        val activity =
            i.startActivitySync(
                context.packageManager.getLaunchIntentForPackage(context.packageName)!!.apply {
                    addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                    )
                }
            ) as android.app.Activity
        awaitApplicationContent(device)
        val h =
            RepositoryHarness.open(File(context.cacheDir, "gallery-cancel-" + UUID.randomUUID()))
        val admission = MediaAdmission()
        lateinit var media: MediaStore
        lateinit var player: MediaCoordinator
        var viewer: MediaGalleryController? = null
        val originals = java.util.concurrent.atomic.AtomicInteger()
        val cancelled = java.util.concurrent.atomic.AtomicInteger()
        // Проверяем очередное условие галереи из потока экрана до установленного срока. Сообщаем
        // об ошибке, если состояние так и не пришло.
        fun state(label: String, predicate: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + 5000
            while (SystemClock.uptimeMillis() < end) {
                var ok = false
                i.runOnMainSync { ok = predicate() }
                if (ok) return
                SystemClock.sleep(10)
            }
            fail(label)
        }
        // Собираем вложенные виды галереи. По ним можно найти страницы и кнопки, не завися от
        // глубины их размещения.
        fun descendants(view: View): List<View> =
            if (view is ViewGroup)
                (0 until view.childCount).flatMap {
                    listOf(view.getChildAt(it)) + descendants(view.getChildAt(it))
                }
            else emptyList()
        i.runOnMainSync {
            media = MediaStore(h.store)
            player = MediaCoordinator(admission, media)
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        try {
            val files =
                org.json
                    .JSONObject(
                        TestFixtures.assets.open("manifest.json").bufferedReader().use {
                            it.readText()
                        }
                    )
                    .getJSONArray("files")
            val names =
                (0 until files.length())
                    .map { files.getJSONObject(it) }
                    .filter { it.getString("mime") == "image/jpeg" }
                    .take(3)
                    .map { it.getString("fileName") }
            assertEquals(3, names.size)
            val sources = mutableMapOf<String, File>()
            val ids = mutableListOf<Long>()
            for ((index, name) in names.withIndex()) {
                val draft =
                    awaitResult<AttachmentDraft> { cb ->
                        i.runOnMainSync {
                            media.stage(
                                { TestFixtures.assets.open("media/" + name) },
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
                                    caption = "Фотография ${index+1}",
                                    attachments = listOf(draft),
                                ),
                                it,
                            )
                        }
                        .inserted
                        .single()
                ids += row.id
                val path = "remote/gallery-$index.jpg"
                sources[path] = media.fileURL(draft.relativePath)
                awaitResult<Unit> { cb ->
                    h.store.submit(
                        { db ->
                            db.execSQL(
                                "UPDATE attachments SET relativePath=? WHERE id=?",
                                arrayOf(path, draft.id),
                            )
                        },
                        cb,
                    )
                }
            }
            i.runOnMainSync {
                media.remoteFile = { a, preview, token ->
                    val file = checkNotNull(sources[a.relativePath])
                    if (!preview) {
                        originals.incrementAndGet()
                        val gate = java.util.concurrent.CountDownLatch(1)
                        token.onCancel {
                            cancelled.incrementAndGet()
                            gate.countDown()
                        }
                        check(gate.await(8, java.util.concurrent.TimeUnit.SECONDS))
                    }
                    file to "image/jpeg"
                }
            }
            val all =
                awaitResult<HistoryPage> {
                    h.repo.history(PageRequest(2, limit = 10, visualOnly = true), it)
                }
            val seed = all.rows.single { it.id == ids[1] }
            lateinit var native: View
            lateinit var pager: ViewGroup
            i.runOnMainSync {
                val before = WindowInspector.getGlobalWindowViews().toSet()
                viewer =
                    MediaGalleryController(
                        activity,
                        seed,
                        h.repo,
                        media,
                        player,
                        InlinePlayerSurface(activity, player),
                    ) {}
                viewer!!.show()
                native = WindowInspector.getGlobalWindowViews().first { it !in before }
                pager =
                    descendants(native).filterIsInstance<ViewGroup>().first {
                        it.javaClass.simpleName == "Pager"
                    }
            }
            // Рисуем содержимое страницы и ищем видимые точки. Это проверяет настоящий рисунок,
            // поскольку наличие контейнера ещё не означает наличие изображения.
            fun hasPixels(page: View): Boolean {
                val content = (page as ViewGroup).getChildAt(0)
                if (content.width == 0 || content.height == 0) return false
                val b = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(b)
                canvas.scale(32f / content.width, 32f / content.height)
                content.draw(canvas)
                val values = IntArray(1024)
                b.getPixels(values, 0, 32, 0, 0, 32, 32)
                b.recycle()
                return values.any {
                    Color.red(it) > 20 || Color.green(it) > 20 || Color.blue(it) > 20
                }
            }
            state("All three native page surfaces contain actual preview pixels") {
                pager.childCount == 3 &&
                    (0 until pager.childCount).all { hasPixels(pager.getChildAt(it)) }
            }
            assertEquals("Neighbours fetch thumbnails only", 1, originals.get())
            device.findObject(By.res("media.cancel")).click()
            state("The central cross cancels the original I/O") { cancelled.get() == 1 }
            i.runOnMainSync {
                val centre = pager.scrollX
                val partial = (centre * 1.4).toInt()
                pager.scrollTo(partial, 0)
                viewer!!.apply(
                    MessageDelta(
                        updated = listOf(seed),
                        chat = ChatSummary(2, "QA", "", 0, 0, 0, false),
                        reason = MessageChangeReason.SYNC,
                    )
                )
                assertEquals(
                    "A live receipt must not reset an in-flight horizontal gesture",
                    partial,
                    pager.scrollX,
                )
                pager.scrollTo(centre, 0)
            }
            val middleCaption = "Фотография 2"
            device.swipe(
                device.displayWidth * 4 / 5,
                device.displayHeight / 2,
                device.displayWidth / 5,
                device.displayHeight / 2,
                12,
            )
            assertTrue(device.wait(Until.hasObject(By.text("Фотография 3")), 5000))
            state("Only the new original starts") { originals.get() == 2 }
            device.swipe(
                device.displayWidth / 5,
                device.displayHeight / 2,
                device.displayWidth * 4 / 5,
                device.displayHeight / 2,
                12,
            )
            assertTrue(device.wait(Until.hasObject(By.text(middleCaption)), 5000))
            assertTrue(device.hasObject(By.res("media.retry")))
            assertEquals(2, originals.get())
            i.runOnMainSync {
                assertEquals(3, pager.childCount)
                assertTrue((0 until pager.childCount).all { hasPixels(pager.getChildAt(it)) })
                viewer!!.close()
            }
            // При возвращении в чат кэш может держать чёткие кадры.
            i.runOnMainSync { media.releaseIdleImages() }
            state("Closing and clearing the idle cache releases every decoded image lease") {
                media.bytesUsed == 0
            }
            assertEquals(2, cancelled.get())
        } finally {
            i.runOnMainSync {
                viewer?.close()
                player.close()
                media.close()
                admission.close()
                activity.finish()
            }
            h.close()
        }
    }

    // Открываем фото и перелистываем на видео и следующее фото. Сверяем одно непрозрачное окно,
    // одну поверхность, сохранённый кадр ролика и возврат к месту чтения.
    @Test
    fun photoVideoPhotoPagesReuseOneOpaqueWindowAndOnePlayer() {
        val i = InstrumentationRegistry.getInstrumentation()
        val context = i.targetContext
        val device = UiDevice.getInstance(i)
        device.wakeUp()
        Configurator.getInstance().waitForIdleTimeout = 0
        val activity =
            i.startActivitySync(
                context.packageManager.getLaunchIntentForPackage(context.packageName)!!.apply {
                    addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                    )
                }
            ) as android.app.Activity
        awaitApplicationContent(device)
        val h = RepositoryHarness.open(File(context.cacheDir, "gallery-pages-" + UUID.randomUUID()))
        val admission = MediaAdmission()
        lateinit var media: MediaStore
        lateinit var player: MediaCoordinator
        lateinit var host: dev.kirill.kandinsky.chat.rendering.ChatScrollHost
        i.runOnMainSync {
            media = MediaStore(h.store)
            player = MediaCoordinator(admission, media)
            host = dev.kirill.kandinsky.chat.rendering.ChatScrollHost(activity)
            activity.setContentView(host)
            host.configureMedia(media)
            host.configurePlayback(player, media)
        }
        try {
            val files =
                org.json
                    .JSONObject(
                        TestFixtures.assets.open("manifest.json").bufferedReader().use {
                            it.readText()
                        }
                    )
                    .getJSONArray("files")
            val photoName =
                (0 until files.length())
                    .map { files.getJSONObject(it) }
                    .first { it.getString("mime") == "image/jpeg" }
                    .getString("fileName")
            // Подготавливаем указанный файл с нужным видом во временном хранилище. Так фото и
            // видео проходят обычный путь приложения.
            fun stage(path: String, mime: String) =
                awaitResult<AttachmentDraft> { cb ->
                    i.runOnMainSync {
                        media.stage({ TestFixtures.assets.open(path) }, mime, cb)
                    }
                    Cancellation {}
                }
            val photo = stage("media/$photoName", "image/jpeg")
            val video = stage(
                "real-media/backend-20c898a2957b4f58afa593749fee3cbd.mp4",
                "video/mp4",
            )
            val first =
                awaitResult<MessageDelta> {
                        h.repo.send(
                            SendCommand(
                                UUID.randomUUID().toString(),
                                2,
                                MessageKind.PHOTO,
                                caption = "Первое фото",
                                attachments = listOf(photo),
                            ),
                            it,
                        )
                    }
                    .inserted
                    .single()
            val second =
                awaitResult<MessageDelta> {
                        h.repo.send(
                            SendCommand(
                                UUID.randomUUID().toString(),
                                2,
                                MessageKind.VIDEO,
                                caption = "Второе: видео",
                                attachments = listOf(video),
                            ),
                            it,
                        )
                    }
                    .inserted
                    .single()
            val third =
                awaitResult<MessageDelta> {
                        h.repo.send(
                            SendCommand(
                                UUID.randomUUID().toString(),
                                2,
                                MessageKind.PHOTO,
                                caption = "Третье фото",
                                attachments = listOf(photo.copy(id = UUID.randomUUID().toString())),
                            ),
                            it,
                        )
                    }
                    .inserted
                    .single()
            val fourth =
                awaitResult<MessageDelta> {
                    h.repo.send(
                        SendCommand(
                            UUID.randomUUID().toString(),
                            2,
                            MessageKind.PHOTO,
                            caption = "Четвёртое фото",
                            attachments = listOf(photo.copy(id = UUID.randomUUID().toString())),
                        ),
                        it,
                    )
                }.inserted.single()
            awaitResult<Unit> { cb ->
                i.runOnMainSync { host.open(2, h.repo, null, cb) }
                Cancellation {}
            }
            lateinit var galleryWindow: View
            var anchor: ReadingAnchor? = null
            i.runOnMainSync {
                anchor = host.readingAnchor()
                val before = WindowInspector.getGlobalWindowViews().toSet()
                host.onMessageTap?.invoke(first, android.graphics.RectF())
                galleryWindow = WindowInspector.getGlobalWindowViews().first { it !in before }
            }
            assertTrue(
                "The tapped media opens one actual paged viewer",
                device.wait(Until.hasObject(By.res("viewer.pages")), 5000),
            )
            assertTrue(device.hasObject(By.text(first.caption)))
            // Проводим пальцем от правого края к левому через середину галереи. Удерживаем
            // одинаковую длину и число шагов для сравнения переходов.
            fun swipeNext() {
                device.swipe(
                    device.displayWidth * 4 / 5,
                    device.displayHeight / 2,
                    device.displayWidth / 5,
                    device.displayHeight / 2,
                    12,
                )
            }
            // Выполняем обратный жест по той же высоте и длине. Это проверяет возврат к ранее
            // показанному вложению.
            fun swipeBack() {
                device.swipe(
                    device.displayWidth / 5,
                    device.displayHeight / 2,
                    device.displayWidth * 4 / 5,
                    device.displayHeight / 2,
                    12,
                )
            }
            // Повторяем проверку проигрывателя до срока из потока экрана. Подпись помогает
            // отличить ожидание первого кадра от ожидания паузы.
            fun waitForPlayer(message: String, predicate: () -> Boolean) {
                val deadline = SystemClock.uptimeMillis() + 5_000
                var matches = false
                while (!matches && SystemClock.uptimeMillis() < deadline) {
                    i.runOnMainSync { matches = predicate() }
                    if (!matches) SystemClock.sleep(20)
                }
                assertTrue(message, matches)
            }
            val sessionField = MediaCoordinator::class.java.getDeclaredField("session")
                .apply { isAccessible = true }
            swipeNext()
            assertTrue(
                "Horizontal paging selects the actual video",
                device.wait(Until.hasObject(By.text(second.caption)), 5000),
            )
            val ready = java.util.concurrent.CountDownLatch(1)
            i.runOnMainSync {
                if (player.isReady) ready.countDown()
                else player.onChange = { if (player.isReady) ready.countDown() }
            }
            assertTrue(ready.await(8, java.util.concurrent.TimeUnit.SECONDS))
            i.runOnMainSync {
                assertEquals(second.id, player.currentMessageID)
                assertEquals(1, player.activePlayerCount)
                assertTrue(WindowInspector.getGlobalWindowViews().contains(galleryWindow))
            }
            // Находим нужную поверхность среди вложенных видов окна. Видимые подписи и рисунок
            // затем сверяем с состоянием общего проигрывателя.
            fun descendants(view: View): List<View> =
                if (view is ViewGroup) (0 until view.childCount).flatMap {
                    listOf(view.getChildAt(it)) + descendants(view.getChildAt(it))
                } else emptyList()
            val surfaceDeadline = SystemClock.uptimeMillis() + 5_000
            var decodedFrame = false
            while (!decodedFrame && SystemClock.uptimeMillis() < surfaceDeadline) {
                i.runOnMainSync {
                    decodedFrame = descendants(galleryWindow).filterIsInstance<InlinePlayerSurface>()
                        .any { it.hasVideoFrame }
                }
                if (!decodedFrame) Thread.sleep(20)
            }
            assertTrue("The player presents a real frame before paging away", decodedFrame)
            waitForPlayer("The playback position has visibly advanced") { player.positionMs >= 420 }
            val originalSession = checkNotNull(sessionField.get(player))
            swipeNext()
            assertTrue(device.wait(Until.hasObject(By.text(third.caption)), 5000))
            i.runOnMainSync {
                val gallery = checkNotNull(host.mediaGallery)
                val pages = gallery.javaClass.getDeclaredField("pages").apply { isAccessible = true }
                    .get(gallery) as Map<*, *>
                val adjacent = checkNotNull(pages["${second.id}/0"])
                val photoPage = adjacent.javaClass.getDeclaredField("photo").apply { isAccessible = true }
                    .get(adjacent) as PhotoViewerController
                val key = VideoPlaybackKey(second.id, second.attachments.single())
                checkNotNull(media.videoPlayback.cover(key)).use { paused ->
                    checkNotNull(photoPage.retainedPreview()).use { shown ->
                        assertSame("The adjacent page must use the saved video frame before starting", paused.bitmap, shown.bitmap)
                    }
                }
            }
            var parkedPosition = 0L
            i.runOnMainSync {
                assertTrue(WindowInspector.getGlobalWindowViews().contains(galleryWindow))
                assertEquals(1, player.peakNativePlayers)
                assertSame(originalSession, sessionField.get(player))
                assertFalse(player.isPlaying)
                parkedPosition = player.positionMs
            }
            SystemClock.sleep(200)
            i.runOnMainSync {
                assertEquals(parkedPosition.toDouble(), player.positionMs.toDouble(), 90.0)
            }
            i.runOnMainSync {
                val pager =
                    descendants(galleryWindow).filterIsInstance<ViewGroup>().first {
                        it.javaClass.simpleName == "Pager"
                    }
                val stride = pager.width + (20 * activity.resources.displayMetrics.density).toInt()
                pager.scrollTo((stride * 0.3).toInt(), 0)
                val previous =
                    (0 until pager.childCount).map { pager.getChildAt(it) }.first { it.left == 0 }
                val visualX = previous.left - pager.scrollX
                val now = SystemClock.uptimeMillis()
                val down =
                    android.view.MotionEvent.obtain(
                        now,
                        now,
                        android.view.MotionEvent.ACTION_DOWN,
                        pager.width * 0.8f,
                        pager.height * 0.5f,
                        0,
                    )
                pager.dispatchTouchEvent(down)
                down.recycle()
                assertTrue(
                    "A new reverse gesture commits the nearest visible page",
                    descendants(galleryWindow).filterIsInstance<android.widget.TextView>().any {
                        it.text.toString() == second.caption
                    },
                )
                assertEquals(
                    "Rebasing preserves the pixels under the finger",
                    visualX,
                    previous.left - pager.scrollX,
                )
                val cancel =
                    android.view.MotionEvent.obtain(
                        now,
                        now + 1,
                        android.view.MotionEvent.ACTION_CANCEL,
                        pager.width * 0.8f,
                        pager.height * 0.5f,
                        0,
                    )
                pager.dispatchTouchEvent(cancel)
                cancel.recycle()
            }
            waitForPlayer("The same session resumes from its paused position") {
                player.isPlaying && player.positionMs > parkedPosition + 120
            }
            i.runOnMainSync { assertSame(originalSession, sessionField.get(player)) }
            swipeNext()
            assertTrue(device.wait(Until.hasObject(By.text(third.caption)), 5000))
            swipeNext()
            assertTrue(device.wait(Until.hasObject(By.text(fourth.caption)), 5000))
            waitForPlayer("A second page away frees the player") { player.activePlayerCount == 0 }
            val key = VideoPlaybackKey(second.id, second.attachments.single())
            var saved = 0L
            i.runOnMainSync { saved = media.videoPlayback.position(key) }
            val duration = checkNotNull(video.durationMs)
            assertTrue("The saved position $saved leaves time to observe playback up to $duration",
                saved in 1 until duration - 500)
            swipeBack()
            assertTrue(device.wait(Until.hasObject(By.text(third.caption)), 5000))
            waitForPlayer("A local return seeks before selecting the video") {
                player.currentMessageID == second.id && player.isReady && !player.isPlaying &&
                    kotlin.math.abs(player.positionMs - saved) < 140
            }
            val preparedSession = checkNotNull(sessionField.get(player))
            assertNotSame(originalSession, preparedSession)
            swipeBack()
            assertTrue(device.wait(Until.hasObject(By.text(second.caption)), 5000))
            waitForPlayer("The restored session advances from the saved position") {
                player.isPlaying && player.positionMs > saved + 120
            }
            i.runOnMainSync { assertSame(preparedSession, sessionField.get(player)) }
            swipeNext()
            assertTrue(device.wait(Until.hasObject(By.text(third.caption)), 5000))
            swipeNext()
            assertTrue(device.wait(Until.hasObject(By.text(fourth.caption)), 5000))
            waitForPlayer("Paging away releases the restored decoder") { player.activePlayerCount == 0 }
            val lookup = java.util.concurrent.CountDownLatch(1)
            val finishLookup = java.util.concurrent.CountDownLatch(1)
            i.runOnMainSync {
                media.remoteCached = { attachment, preview, _ ->
                    if (attachment.id == second.attachments.single().id && !preview) {
                        lookup.countDown()
                        finishLookup.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    }
                    null
                }
            }
            try {
                swipeBack()
                assertTrue(device.wait(Until.hasObject(By.text(third.caption)), 5000))
                assertTrue(lookup.await(3, java.util.concurrent.TimeUnit.SECONDS))
                swipeBack()
                assertTrue(device.wait(Until.hasObject(By.text(second.caption)), 5000))
            } finally {
                finishLookup.countDown()
            }
            waitForPlayer("A cache miss during selection falls back to the ordinary source") {
                player.currentMessageID == second.id && player.isPlaying && player.positionMs > 300
            }
            i.runOnMainSync { media.remoteCached = null }
            swipeNext()
            assertTrue(device.wait(Until.hasObject(By.text(third.caption)), 5000))
            device.findObject(By.res("photo.close")).click()
            assertTrue(device.wait(Until.gone(By.res("viewer.pages")), 5000))
            i.runOnMainSync { assertEquals(anchor?.messageID, host.readingAnchor().messageID) }
        } finally {
            i.runOnMainSync {
                host.close()
                player.close()
                media.close()
                admission.close()
                activity.finish()
            }
            h.close()
        }
    }

    // Открываем фото и проверяем чёрный фон в верхней области экрана, затем двигаем изображение.
    // Переписка не должна проступить у выреза или при перетаскивании.
    @Test
    fun galleryBackingCoversTheDisplayCutoutAndStaysBlackDuringDrag() {
        val i = InstrumentationRegistry.getInstrumentation()
        val context = i.targetContext
        val device = UiDevice.getInstance(i)
        device.wakeUp()
        val activity =
            i.startActivitySync(
                context.packageManager.getLaunchIntentForPackage(context.packageName)!!.apply {
                    addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                    )
                }
            ) as android.app.Activity
        awaitApplicationContent(device)
        val h = RepositoryHarness.open(File(context.cacheDir, "photo-cutout-" + UUID.randomUUID()))
        lateinit var media: MediaStore
        var viewer: PhotoViewerController? = null
        i.runOnMainSync {
            media = MediaStore(h.store)
            activity.setContentView(
                android.widget.FrameLayout(activity).apply { setBackgroundColor(Color.RED) }
            )
        }
        try {
            val files =
                org.json
                    .JSONObject(
                        TestFixtures.assets.open("manifest.json").bufferedReader().use {
                            it.readText()
                        }
                    )
                    .getJSONArray("files")
            val name =
                (0 until files.length())
                    .map { files.getJSONObject(it) }
                    .first { it.getString("mime") == "image/jpeg" }
                    .getString("fileName")
            val d =
                awaitResult<AttachmentDraft> { cb ->
                    i.runOnMainSync {
                        media.stage({ TestFixtures.assets.open("media/" + name) }, "image/jpeg", cb)
                    }
                    Cancellation {}
                }
            val a =
                Attachment(
                    d.id,
                    d.relativePath,
                    d.mime,
                    d.byteSize,
                    d.rawWidth,
                    d.rawHeight,
                    d.orientation,
                    d.durationMs,
                    d.thumbnailKey,
                )
            var window: View? = null
            i.runOnMainSync {
                val before = WindowInspector.getGlobalWindowViews().toSet()
                viewer = PhotoViewerController(activity, a, media)
                viewer!!.show()
                window = WindowInspector.getGlobalWindowViews().first { it !in before }
            }
            assertTrue(device.wait(Until.hasObject(By.res("photo.close")), 5000))
            i.waitForIdleSync()
            i.runOnMainSync {
                val location = IntArray(2)
                window!!.getLocationOnScreen(location)
                assertEquals(
                    "The opaque gallery window extends into the status/cutout area",
                    0,
                    location[1],
                )
            }
            // Окно уже есть в дереве, но снимок ещё может содержать кадр предыдущего окна.
            // Ждём фон внизу, чтобы отдельно проверить верхнюю область выреза на том же кадре.
            fun captureGalleryFrame(): Bitmap {
                val deadline = SystemClock.uptimeMillis() + 5000
                while (SystemClock.uptimeMillis() < deadline) {
                    val frame = checkNotNull(i.uiAutomation.takeScreenshot())
                    if (frame.getPixel(frame.width / 5, frame.height * 11 / 12) == Color.BLACK) {
                        return frame
                    }
                    frame.recycle()
                    SystemClock.sleep(16)
                }
                throw AssertionError("The gallery background did not appear on the display")
            }
            val bitmap = captureGalleryFrame()
            try {
                assertEquals(
                    "The chat cannot show through the top of the gallery",
                    Color.BLACK,
                    bitmap.getPixel(bitmap.width / 5, 2),
                )
            } finally {
                bitmap.recycle()
            }
            device.swipe(
                device.displayWidth / 2,
                device.displayHeight / 2,
                device.displayWidth / 2,
                device.displayHeight / 2 + device.displayHeight / 12,
                20,
            )
            val moved = checkNotNull(i.uiAutomation.takeScreenshot())
            try {
                assertEquals(Color.BLACK, moved.getPixel(moved.width / 5, 2))
            } finally {
                moved.recycle()
            }
        } finally {
            i.runOnMainSync {
                viewer?.close()
                media.close()
                activity.finish()
            }
            h.close()
        }
    }

    // Нажимаем фото в истории и ждём отдельное окно. В нём должна сохраниться подпись исходного
    // сообщения.
    @Test
    fun photoTapPreservesTheCaptionInTheOriginalViewer() {
        val i = InstrumentationRegistry.getInstrumentation()
        val context = i.targetContext
        val device = UiDevice.getInstance(i)
        device.wakeUp()
        val activity =
            i.startActivitySync(
                context.packageManager.getLaunchIntentForPackage(context.packageName)!!.apply {
                    addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                    )
                }
            ) as android.app.Activity
        awaitApplicationContent(device)
        val h = RepositoryHarness.open(File(context.cacheDir, "photo-caption-" + UUID.randomUUID()))
        val admission = MediaAdmission()
        lateinit var media: MediaStore
        lateinit var player: MediaCoordinator
        lateinit var host: dev.kirill.kandinsky.chat.rendering.ChatScrollHost
        i.runOnMainSync {
            media = MediaStore(h.store)
            player = MediaCoordinator(admission, media)
            host = dev.kirill.kandinsky.chat.rendering.ChatScrollHost(activity)
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity.setContentView(host)
            host.configureMedia(media)
            host.configurePlayback(player, media)
        }
        try {
            val files =
                org.json
                    .JSONObject(
                        TestFixtures.assets.open("manifest.json").bufferedReader().use {
                            it.readText()
                        }
                    )
                    .getJSONArray("files")
            val name =
                (0 until files.length())
                    .map { files.getJSONObject(it) }
                    .first { it.getString("mime") == "image/jpeg" }
                    .getString("fileName")
            val draft =
                awaitResult<AttachmentDraft> { cb ->
                    i.runOnMainSync {
                        media.stage({ TestFixtures.assets.open("media/" + name) }, "image/jpeg", cb)
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
                                caption = "A readable photo caption",
                                attachments = listOf(draft),
                            ),
                            it,
                        )
                    }
                    .inserted
                    .single()
            awaitResult<Unit> { cb ->
                i.runOnMainSync {
                    host.open(2, h.repo, null, cb)
                    host.setMediaVisible(true)
                }
                Cancellation {}
            }
            i.runOnMainSync { host.onMessageTap?.invoke(row, android.graphics.RectF()) }
            assertTrue(device.wait(Until.hasObject(By.res("photo.close")), 5000))
            assertTrue(
                "Opening the original must preserve the message caption",
                device.hasObject(By.text(row.caption)),
            )
            device.findObject(By.res("photo.close")).click()
        } finally {
            i.runOnMainSync {
                host.close()
                player.close()
                media.close()
                admission.close()
                activity.finish()
            }
            h.close()
        }
    }

    // Открываем два результата и проверяем автоматический показ без ручных кнопок. При выходе в
    // чате не остаётся проигрывателя, а скрытое действие загрузки не срабатывает.
    @Test
    fun studioVideoAutoplaysWithoutTransportAndClosesWithoutAnInlinePlayer() {
        val i = InstrumentationRegistry.getInstrumentation()
        val context = i.targetContext
        val device = UiDevice.getInstance(i)
        device.wakeUp()
        Configurator.getInstance().waitForIdleTimeout = 0
        val activity =
            i.startActivitySync(
                context.packageManager.getLaunchIntentForPackage(context.packageName)!!.apply {
                    addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                    )
                }
            ) as android.app.Activity
        awaitApplicationContent(device)
        val h =
            RepositoryHarness.open(File(context.cacheDir, "studio-autoplay-" + UUID.randomUUID()))
        val admission = MediaAdmission()
        lateinit var media: MediaStore
        lateinit var player: MediaCoordinator
        lateinit var surface: InlinePlayerSurface
        var viewer: MediaGalleryController? = null
        val returned = java.util.concurrent.CountDownLatch(1)
        i.runOnMainSync {
            media = MediaStore(h.store, studioOriginals = true)
            player = MediaCoordinator(admission, media)
            surface = InlinePlayerSurface(activity, player)
        }
        try {
            // Готовим местный видеоматериал через обычную подготовку вложения. Несколько имён
            // дают раздельные результаты для листания.
            fun stage(name: String) =
                awaitResult<AttachmentDraft> { cb ->
                    i.runOnMainSync {
                        media.stage({ TestFixtures.assets.open("media/$name") }, "video/mp4", cb)
                    }
                    Cancellation {}
                }
            val first = stage("video-01.mp4")
            val second = stage("video-00.mp4")
            val delta =
                awaitResult<MessageDelta> {
                    h.repo.send(
                        SendCommand(
                            UUID.randomUUID().toString(),
                            2,
                            MessageKind.VIDEO,
                            attachments = listOf(first, second),
                        ),
                        it,
                    )
                }
            val row = delta.inserted.single()
            assertEquals(2, row.attachments.size)
            val controls = {
                (0 until surface.childCount).map(surface::getChildAt).filter {
                    it is MediaProgressControl ||
                        it.createAccessibilityNodeInfo().viewIdResourceName == "player.pause"
                }
            }
            // Ждём наступления этапа воспроизведения или закрытия и читаем состояние в потоке
            // экрана. При промахе видно, какое наблюдение не подтвердилось.
            fun waitFor(description: String, check: () -> Boolean) {
                val end = SystemClock.uptimeMillis() + 8000
                do {
                    var ready = false
                    i.runOnMainSync { ready = check() }
                    if (ready) return
                    SystemClock.sleep(20)
                } while (SystemClock.uptimeMillis() < end)
                fail(description)
            }
            i.runOnMainSync {
                viewer =
                    MediaGalleryController(
                        activity,
                        row,
                        h.repo,
                        media,
                        player,
                        surface,
                        attachmentIndex = 1,
                    ) {
                        returned.countDown()
                    }
                viewer!!.onDeleteResult = { _, _ -> fail("Closing a video must never delete it") }
                viewer!!.show()
                assertTrue("Transport controls are absent before the first frame", controls().isEmpty())
            }
            waitFor("Video starts without a tap") {
                player.isPlaying && player.positionMs > 0 && surface.hasVideoFrame
            }
            i.runOnMainSync {
                assertTrue(
                    "Transport controls remain absent after playback starts",
                    controls().isEmpty(),
                )
                assertEquals(1, player.peakNativePlayers)
                assertEquals(second.id, player.currentTarget?.attachment?.id)
                val refresh = MessageDelta(updated = listOf(row), chat = delta.chat)
                player.apply(refresh)
                viewer!!.apply(refresh)
                assertTrue(
                    "Refreshing an album must leave its selected video running",
                    player.isPlaying,
                )
                viewer!!.resumeAutoplay()
                assertEquals(1, player.activePlayerCount)
                assertEquals(second.id, player.currentTarget?.attachment?.id)
                player.stop()
                assertNull(player.currentMessageID)
            }
            waitFor("В фоне прежний сеанс освобождает проигрыватель") {
                player.activePlayerCount == 0 && !player.isPlaying
            }
            i.runOnMainSync { viewer!!.resumeAutoplay() }
            waitFor("После возврата виден кадр того же результата") {
                player.currentTarget?.attachment?.id == second.id &&
                    player.isPlaying && surface.hasVideoFrame
            }
            i.runOnMainSync {
                viewer!!.resumeAutoplay()
                assertEquals(1, player.activePlayerCount)
                assertEquals(1, player.peakNativePlayers)
                viewer!!.close()
            }
            assertTrue(returned.await(3, java.util.concurrent.TimeUnit.SECONDS))
            waitFor("No manual player returns to the chat") {
                player.activePlayerCount == 0 &&
                    player.currentMessageID == null &&
                    surface.parent == null
            }
            i.runOnMainSync {
                val poster = MediaImageSlot(media) {}
                val video =
                    Attachment(
                        "video",
                        "remote/video.mp4",
                        "video/mp4",
                        100,
                        640,
                        384,
                        1,
                        5000,
                        "video",
                    )
                try {
                    poster.bind(
                        video,
                        android.graphics.RectF(0f, 0f, 260f, 150f),
                        kind = MessageKind.VIDEO,
                        showControls = false,
                    )
                    assertTrue(poster.loading)
                    assertNull(poster.loadAction())
                    assertFalse(poster.performLoadAction(130f, 75f))
                } finally {
                    poster.close()
                }
            }
        } finally {
            i.runOnMainSync {
                viewer?.close()
                player.close()
                media.close()
                admission.close()
                activity.finish()
            }
            h.close()
        }
    }

    // Открываем видео касанием, проверяем подпись, перематываем и ставим на паузу. После возврата
    // в ленту сохраняются тот же проигрыватель и позиция без нового запуска.
    @Test
    fun videoTapOpensFullScreenWithCaptionAndReturnsTheSamePausedNativeOutput() {
        val i = InstrumentationRegistry.getInstrumentation()
        val context = i.targetContext
        val device = UiDevice.getInstance(i)
        device.wakeUp()
        val activity =
            i.startActivitySync(
                context.packageManager.getLaunchIntentForPackage(context.packageName)!!.apply {
                    addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                    )
                }
            ) as android.app.Activity
        awaitApplicationContent(device)
        val h = RepositoryHarness.open(File(context.cacheDir, "video-route-" + UUID.randomUUID()))
        val admission = MediaAdmission()
        lateinit var media: MediaStore
        lateinit var player: MediaCoordinator
        lateinit var host: dev.kirill.kandinsky.chat.rendering.ChatScrollHost
        i.runOnMainSync {
            media = MediaStore(h.store)
            player = MediaCoordinator(admission, media)
            host = dev.kirill.kandinsky.chat.rendering.ChatScrollHost(activity)
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity.setContentView(host)
            host.configureMedia(media)
            host.configurePlayback(player, media)
        }
        try {
            val draft =
                awaitResult<AttachmentDraft> { cb ->
                    i.runOnMainSync {
                        media.stage(
                            { TestFixtures.assets.open("media/video-00.mp4") },
                            "video/mp4",
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
                                MessageKind.VIDEO,
                                caption = "A real video caption",
                                attachments = listOf(draft),
                            ),
                            it,
                        )
                    }
                    .inserted
                    .single()
            awaitResult<Unit> { cb ->
                i.runOnMainSync {
                    host.open(2, h.repo, null, cb)
                    host.setMediaVisible(true)
                }
                Cancellation {}
            }
            val initiallyPlaying = java.util.concurrent.CountDownLatch(1)
            i.runOnMainSync {
                player.observeChanges {
                    if (player.isReady && player.positionMs >= 250) initiallyPlaying.countDown()
                }
                player.play(PlaybackTarget(row.attachments.single(), row.id, row.kind), inline = true)
            }
            assertTrue(initiallyPlaying.await(8, java.util.concurrent.TimeUnit.SECONDS))
            val key = VideoPlaybackKey(row.id, row.attachments.single())
            i.runOnMainSync { host.onMessageTap?.invoke(row, android.graphics.RectF()) }
            assertTrue(
                "A video tap must open the actual full-screen viewer",
                device.wait(Until.hasObject(By.desc("Вернуть в чат")), 5000),
            )
            assertTrue(device.hasObject(By.text(row.caption)))
            val ready = java.util.concurrent.CountDownLatch(1)
            i.runOnMainSync {
                if (player.isReady) ready.countDown()
                else player.onChange = { if (player.isReady) ready.countDown() }
            }
            assertTrue(ready.await(8, java.util.concurrent.TimeUnit.SECONDS))
            val sought = java.util.concurrent.CountDownLatch(1)
            i.runOnMainSync {
                player.pause()
                player.seek(1200) { done ->
                    assertTrue(done)
                    player.pause()
                    sought.countDown()
                }
            }
            assertTrue(sought.await(3, java.util.concurrent.TimeUnit.SECONDS))
            val deadline = SystemClock.uptimeMillis() + 3000
            var saved = 0L
            var paused = 0L
            do {
                i.runOnMainSync {
                    saved = media.videoPlayback.position(key)
                    paused = player.positionMs
                }
                if (paused in 1000L..1450L && kotlin.math.abs(saved - paused) <= 30L) break
                SystemClock.sleep(20)
            } while (SystemClock.uptimeMillis() < deadline)
            assertTrue("После перемотки проигрыватель остался рядом с выбранным местом", paused in 1000L..1450L)
            assertEquals(
                "Место просмотра после перехода из ленты должно сохраниться",
                paused.toDouble(), saved.toDouble(), 30.0,
            )
            device.findObject(By.desc("Вернуть в чат")).click()
            val end = SystemClock.uptimeMillis() + 3000
            var returned = false
            while (SystemClock.uptimeMillis() < end && !returned) {
                i.runOnMainSync { returned = player.inlineVisible }
                SystemClock.sleep(20)
            }
            assertTrue(returned)
            i.runOnMainSync {
                assertEquals(1, player.activePlayerCount)
                assertEquals(1, player.peakNativePlayers)
                assertEquals(paused.toDouble(), player.positionMs.toDouble(), 120.0)
                assertFalse(player.isPlaying)
            }
        } finally {
            i.runOnMainSync {
                host.close()
                player.close()
                media.close()
                admission.close()
                activity.finish()
            }
            h.close()
        }
    }

    // Открываем выбранное движущееся изображение как исходную ссылку. Проверяем видимые точки и
    // одинаковые кадры, а проигрыватель для него не выделяется.
    @Test
    fun gifReferenceDisplaysItsStaticOriginalWithoutAMovingDecoder() {
        val i = InstrumentationRegistry.getInstrumentation()
        val context = i.targetContext
        val device = UiDevice.getInstance(i)
        device.wakeUp()
        val activity =
            i.startActivitySync(
                context.packageManager.getLaunchIntentForPackage(context.packageName)!!.apply {
                    addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                    )
                }
            ) as android.app.Activity
        awaitApplicationContent(device)
        val h = RepositoryHarness.open(File(context.cacheDir, "gif-viewer-" + UUID.randomUUID()))
        lateinit var media: MediaStore
        var viewer: PhotoViewerController? = null
        i.runOnMainSync {
            media = MediaStore(h.store)
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        // Ждём готовности видимого оригинала или освобождения в пределах времени. Это отделяет
        // работу фоновой загрузки от измерения настоящего кадра.
        fun awaitState(label: String, check: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + 5000
            while (SystemClock.uptimeMillis() < end) {
                var ok = false
                i.runOnMainSync { ok = check() }
                if (ok) return
                SystemClock.sleep(20)
            }
            fail(label)
        }
        try {
            val d =
                awaitResult<AttachmentDraft> { cb ->
                    i.runOnMainSync {
                        media.stage(
                            { TestFixtures.assets.open("catalogue-media/gif-waterfall-0.gif") },
                            "image/gif",
                            cb,
                        )
                    }
                    Cancellation {}
                }
            val a =
                Attachment(
                    d.id,
                    d.relativePath,
                    d.mime,
                    d.byteSize,
                    d.rawWidth,
                    d.rawHeight,
                    d.orientation,
                    d.durationMs,
                    d.thumbnailKey,
                )
            i.runOnMainSync {
                viewer = PhotoViewerController(activity, a, media)
                viewer!!.show()
            }
            awaitState("The GIF reference must finish decoding its static original") {
                media.cachedThumbnail(a, ThumbnailKey(maxPixel = 2048))?.let { frame ->
                    frame.close()
                    true
                } == true
            }
            i.waitForIdleSync()
            SystemClock.sleep(100)
            assertTrue(device.wait(Until.gone(By.res("media.cancel")), 5000))
            val hashes = mutableSetOf<Int>()
            val end = SystemClock.uptimeMillis() + 2000
            while (SystemClock.uptimeMillis() < end) {
                val b = checkNotNull(i.uiAutomation.takeScreenshot())
                val values = IntArray(80 * 80)
                val x = b.width / 2 - 40
                val y = b.height / 2 - 40
                b.getPixels(values, 0, 80, x, y, 80, 80)
                assertTrue("The original must contain actual visible pixels", values.toSet().size > 16)
                hashes += values.contentHashCode()
                b.recycle()
                SystemClock.sleep(60)
            }
            assertEquals("A reference image stays static in the photo viewer", 1, hashes.size)
            i.runOnMainSync { assertEquals(0, MediaAdmission.previewOwnerCount) }
            i.runOnMainSync {
                media.trimMemory()
                viewer!!.close()
            }
            awaitState("A static reference must leave no moving decoder after dismissal") {
                MediaAdmission.previewOwnerCount == 0
            }
            i.runOnMainSync {
                media.close()
                assertEquals(0, media.bytesUsed)
            }
        } finally {
            i.runOnMainSync {
                viewer?.close()
                media.close()
                activity.finish()
            }
            h.close()
        }
    }

    // Увеличиваем фото двойным нажатием, двигаем его и сравниваем видимые точки. Затем закрываем
    // просмотр, чтобы проверить возврат владения.
    @Test
    fun doubleTapZoomPanAndCloseUseTheOriginalSurface() {
        val i = InstrumentationRegistry.getInstrumentation()
        val context = i.targetContext
        val device = UiDevice.getInstance(i)
        device.wakeUp()
        Configurator.getInstance().waitForIdleTimeout = 0
        val activity =
            i.startActivitySync(
                context.packageManager.getLaunchIntentForPackage(context.packageName)!!.apply {
                    addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                    )
                }
            ) as android.app.Activity
        awaitApplicationContent(device)
        val h =
            RepositoryHarness.open(File(context.cacheDir, "photo-interaction-" + UUID.randomUUID()))
        lateinit var media: MediaStore
        var viewer: PhotoViewerController? = null
        i.runOnMainSync {
            media = MediaStore(h.store)
            activity.setContentView(
                android.widget.FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
            )
        }
        try {
            val source = File(h.root, "gradient.jpg")
            val b = Bitmap.createBitmap(1600, 800, Bitmap.Config.ARGB_8888)
            val row =
                IntArray(1600) { x -> Color.rgb(50 + x * 200 / 1599, 0, 250 - x * 200 / 1599) }
            for (y in 0 until 800) b.setPixels(row, 0, 1600, 0, y, 1600, 1)
            source.outputStream().use { assertTrue(b.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            b.recycle()
            val draft =
                awaitResult<AttachmentDraft> { cb ->
                    i.runOnMainSync { media.stage({ source.inputStream() }, "image/jpeg", cb) }
                    Cancellation {}
                }
            val a =
                Attachment(
                    draft.id,
                    draft.relativePath,
                    draft.mime,
                    draft.byteSize,
                    draft.rawWidth,
                    draft.rawHeight,
                    draft.orientation,
                    draft.durationMs,
                    draft.thumbnailKey,
                )
            var native: View? = null
            i.runOnMainSync {
                val before = WindowInspector.getGlobalWindowViews().toSet()
                viewer = PhotoViewerController(activity, a, media)
                viewer!!.show()
                val window = WindowInspector.getGlobalWindowViews().first { it !in before }
                val container = window.findViewById<ViewGroup>(android.R.id.content)
                val root = container.getChildAt(0) as ViewGroup
                native = root.getChildAt(0)
            }
            device.waitForIdle(500)
            Thread.sleep(300)
            // Получаем снимок видимого экрана с помощью системы проверки. По нему измеряем
            // размеры и сдвиг настоящего изображения.
            fun capture(): Bitmap = checkNotNull(i.uiAutomation.takeScreenshot())
            // Ищем ожидаемые цветные точки вдоль вертикальной линии и возвращаем высоту занятого
            // участка. Так проверяем видимый масштаб фото.
            fun coloredHeight(image: Bitmap, x: Int): Int {
                val ys =
                    (0 until image.height).filter { y ->
                        val color = image.getPixel(x, y)
                        Color.green(color) < 20 && maxOf(Color.red(color), Color.blue(color)) > 80
                    }
                return if (ys.isEmpty()) 0 else ys.last() - ys.first() + 1
            }
            val location = IntArray(2)
            var width = 0
            var height = 0
            i.runOnMainSync {
                native!!.getLocationOnScreen(location)
                width = native!!.width
                height = native!!.height
            }
            val cx = location[0] + width / 2
            val cy = location[1] + height / 2
            var first = capture()
            var initial = coloredHeight(first, cx)
            val firstDeadline = SystemClock.uptimeMillis() + 4000
            while (initial <= width / 3 && SystemClock.uptimeMillis() < firstDeadline) {
                first.recycle()
                SystemClock.sleep(50)
                first = capture()
                initial = coloredHeight(first, cx)
            }
            val evidence =
                File(
                    context.getExternalFilesDir("kandinsky-test-metrics"),
                    "photo-viewer-first.png",
                )
            evidence.outputStream().use { first.compress(Bitmap.CompressFormat.PNG, 100, it) }
            println(
                "PHOTO_VIEWER width=$width height=$height x=${location[0]} y=${location[1]} " +
                    "coloredHeight=$initial center=${first.getPixel(cx,cy)} ownedBytes=${media.bytesUsed}"
            )
            first.recycle()
            assertTrue("Original aspect-fit image must be visible", initial > width / 3)
            val downTime = SystemClock.uptimeMillis()
            val fingers = Array(2) { index ->
                MotionEvent.PointerProperties().apply {
                    id = index
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }
            fun touch(action: Int, count: Int, spread: Float) {
                val positions = Array(count) { index ->
                    MotionEvent.PointerCoords().apply {
                        x = cx + (if (index == 0) -spread else spread)
                        y = cy.toFloat()
                        pressure = 1f
                        size = 1f
                    }
                }
                val event = MotionEvent.obtain(
                    downTime, SystemClock.uptimeMillis(), action, count, fingers, positions,
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
                )
                try {
                    assertTrue(i.uiAutomation.injectInputEvent(event, true))
                } finally {
                    event.recycle()
                }
            }
            // Первый палец остаётся на экране, пока второй не закончит жест.
            val secondFinger = 1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
            touch(MotionEvent.ACTION_DOWN, 1, width * .12f)
            touch(MotionEvent.ACTION_POINTER_DOWN or secondFinger, 2, width * .12f)
            for (step in 1..12) {
                touch(MotionEvent.ACTION_MOVE, 2, width * (.12f + step * .027f))
            }
            touch(MotionEvent.ACTION_POINTER_UP or secondFinger, 2, width * .444f)
            touch(MotionEvent.ACTION_UP, 1, width * .444f)
            i.waitForIdleSync()
            val pinched = capture()
            val pinchedHeight = coloredHeight(pinched, cx)
            pinched.recycle()
            assertTrue("Разведение пальцев должно увеличить видимое фото", pinchedHeight > initial * 1.2)
            device.click(cx, cy)
            SystemClock.sleep(60)
            device.click(cx, cy)
            SystemClock.sleep(400)
            val restored = capture()
            val restoredHeight = coloredHeight(restored, cx)
            restored.recycle()
            assertEquals("Двойное нажатие возвращает общий вид", initial.toDouble(), restoredHeight.toDouble(), 3.0)

            device.click(cx, cy)
            SystemClock.sleep(60)
            device.click(cx, cy)
            SystemClock.sleep(400)
            val zoomed = capture()
            val expanded = coloredHeight(zoomed, cx)
            val beforePan = zoomed.getPixel(cx, cy)
            File(context.getExternalFilesDir("kandinsky-test-metrics"), "photo-viewer-zoomed.png")
                .outputStream()
                .use { zoomed.compress(Bitmap.CompressFormat.PNG, 100, it) }
            zoomed.recycle()
            assertTrue(
                "Double tap must enlarge the image, not merely hide controls",
                expanded > initial * 1.8,
            )
            device.swipe(cx, cy, cx + width / 4, cy, 20)
            SystemClock.sleep(300)
            val panned = capture()
            val afterPan = panned.getPixel(cx, cy)
            File(context.getExternalFilesDir("kandinsky-test-metrics"), "photo-viewer-panned.png")
                .outputStream()
                .use { panned.compress(Bitmap.CompressFormat.PNG, 100, it) }
            panned.recycle()
            assertTrue(
                "Panning a zoomed photo must change the original pixels under the viewport",
                kotlin.math.abs(Color.red(beforePan) - Color.red(afterPan)) > 8,
            )
            var closed = false
            i.runOnMainSync {
                viewer!!.onClose = { closed = true }
                viewer!!.close()
            }
            i.waitForIdleSync()
            assertTrue("Native dismiss must return ownership", closed)
        } finally {
            i.runOnMainSync {
                viewer?.close()
                media.close()
                activity.finish()
            }
            h.close()
        }
    }
}
