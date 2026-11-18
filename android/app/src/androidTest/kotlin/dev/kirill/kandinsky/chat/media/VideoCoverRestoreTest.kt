// Проверки сохранённого кадра видео и копии просмотренного фото на андроиде. Они сверяют видимый
// рисунок при возврате, порядок чтения и записи и освобождение памяти, если работа стала
// устаревшей.

package dev.kirill.kandinsky.chat.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.os.SystemClock
import android.view.Surface
import androidx.test.platform.app.InstrumentationRegistry
import dev.kirill.kandinsky.chat.domain.Attachment
import dev.kirill.kandinsky.chat.domain.AttachmentDraft
import dev.kirill.kandinsky.chat.domain.Cancellation
import dev.kirill.kandinsky.chat.domain.MessageKind
import dev.kirill.kandinsky.chat.support.RepositoryHarness
import dev.kirill.kandinsky.chat.support.awaitResult
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class VideoCoverRestoreTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    // Удаляем исходный файл после просмотра и открываем то же фото. Чёткая копия возвращается с
    // диска без сети, а полная очистка удаляет и её.
    @Test
    fun aViewedGalleryPhotoRetainsASharpCopyAfterOriginalEviction() {
        val h = RepositoryHarness.open(File(instrumentation.targetContext.cacheDir, "gallery-disk-" + UUID.randomUUID()))
        val media = MediaStore(h.store)
        var slot: MediaImageSlot? = null
        try {
            val draft = awaitResult<AttachmentDraft> { done ->
                instrumentation.runOnMainSync {
                    media.stage({ instrumentation.context.assets.open("media/photo-000.jpg") }, "image/jpeg", done)
                }
                Cancellation {}
            }
            val photo = Attachment(
                draft.id, draft.relativePath, draft.mime, draft.byteSize, draft.rawWidth,
                draft.rawHeight, draft.orientation, null, draft.id, tinyPreview = draft.tinyPreview,
            )
            awaitResult<PreparedImage> { done ->
                instrumentation.runOnMainSync {
                    media.thumbnail(photo, ThumbnailKey(384, gallery = true), 0) { _, result -> done(result) }
                }
                Cancellation {}
            }.close()
            instrumentation.runOnMainSync {
                media.markShownInGallery(photo)
                media.releaseIdleImages()
                media.remoteFile = { _, _, _ -> error("The surviving copy must be used locally") }
            }
            assertTrue(media.fileURL(photo.relativePath).delete())
            val displayed = CountDownLatch(1)
            instrumentation.runOnMainSync {
                val page = MediaImageSlot(media) {
                    slot?.preview()?.use { frame ->
                        assertFalse("Returning must never show the inline tiny picture", frame.isTinyPreview)
                        if (!frame.isTinyPreview) displayed.countDown()
                    }
                }
                slot = page
                page.bind(photo, RectF(0f, 0f, 350f, 400f), maxPixel = 384,
                    kind = MessageKind.PHOTO, galleryPreview = true)
            }
            assertTrue("The photo is restored from the gallery copy", displayed.await(5, TimeUnit.SECONDS))
            val cache = VideoCoverDiskCache(h.store.root)
            try {
                assertNotNull(cache.galleryFile(photo.relativePath))
                VideoCoverDiskCache.revoke(h.store.root, photo.relativePath)
                assertNull(cache.galleryFile(photo.relativePath))
            } finally { cache.close() }
        } finally {
            instrumentation.runOnMainSync { slot?.close(); media.close() }
            h.close()
        }
    }

    // Оставляем начальный рисунок в памяти и сохраняем другой кадр на диске. При возврате первый
    // рисунок не мелькает, а показываются цветовые точки нужного сохранённого кадра.
    @Test
    fun returningVideoPrefersTheDiskFrameEvenWithAPosterInMemory() {
        val example = fixture()
        val h = RepositoryHarness.open(File(instrumentation.targetContext.cacheDir, "cover-slot-" + UUID.randomUUID()))
        val media = MediaStore(h.store)
        var slot: MediaImageSlot? = null
        val ready = CountDownLatch(1)
        try {
            val draft = awaitResult<AttachmentDraft> { done ->
                instrumentation.runOnMainSync {
                    media.stage({ instrumentation.context.assets.open("media/video-00.mp4") }, "video/mp4", done)
                }
                Cancellation {}
            }
            val attachment = Attachment(
                draft.id, draft.relativePath, draft.mime, draft.byteSize, draft.rawWidth, draft.rawHeight,
                draft.orientation, draft.durationMs, draft.id,
            )
            val key = VideoPlaybackKey(42, attachment)
            VideoCoverDiskCache(h.store.root).also { disk ->
                disk.store(example.image, key)
                disk.close()
            }
            val poster = awaitResult<PreparedImage> { done ->
                instrumentation.runOnMainSync {
                    media.thumbnail(attachment, ThumbnailKey(128), 0) { _, result -> done(result) }
                }
                Cancellation {}
            }
            poster.use {
                instrumentation.runOnMainSync {
                    val video = MediaImageSlot(media) { if (slot?.hasSharpFrame == true) ready.countDown() }
                    slot = video
                    video.bind(attachment, RectF(0f, 0f, 160f, 90f), maxPixel = 128,
                        kind = MessageKind.VIDEO, playbackKey = key)
                    video.seed(checkNotNull(poster.retained()))
                    video.preview().use {
                        assertNull("A poster ready in memory must not flash during disk restoration", it)
                    }
                }
                assertTrue(ready.await(3, TimeUnit.SECONDS))
            }
            instrumentation.runOnMainSync {
                val video = checkNotNull(slot)
                checkNotNull(video.preview()).use {
                    assertArrayEquals(pixelColors(example.image), pixelColors(checkNotNull(it.bitmap)))
                }
                media.videoPlayback.rememberFrame(key, media.videoPlayback.generation, 64, 64) { w, h ->
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
                }
                assertTrue(video.showSavedPlaybackFrame(key))
                checkNotNull(video.preview()).use { assertEquals(Color.RED, it.bitmap?.getPixel(5, 5)) }
            }
        } finally {
            instrumentation.runOnMainSync { slot?.close(); media.close() }
            h.close()
            example.close()
        }
    }

    // Задерживаем снимок поверхности, затем сохраняем более свежий кадр или чистим запас. Старый
    // снимок не должен переписать новый или снова занять память после сброса.
    @Test
    fun delayedSurfaceCopyCannotReplaceNewerCoverOrSurviveCacheReset() {
        val budget = ImageByteBudget()
        lateinit var texture: SurfaceTexture
        lateinit var surface: Surface
        lateinit var cache: VideoPlaybackCache
        val attachment = Attachment(
            "copy", "remote/copy.mp4", "video/mp4", 1, 64, 64, 1, 1000, "copy",
        )
        val key = VideoPlaybackKey(82001, attachment)
        instrumentation.runOnMainSync {
            texture = SurfaceTexture(false).apply { setDefaultBufferSize(64, 64) }
            surface = Surface(texture)
            val canvas = surface.lockCanvas(null)
            canvas.drawColor(Color.BLUE)
            surface.unlockCanvasAndPost(canvas)
            cache = VideoPlaybackCache(budget)
        }
        // Запоминаем кадр выбранного цвета по тому же ключу. Различимый цвет показывает, какая
        // запись в итоге победила.
        fun save(color: Int) {
            cache.rememberFrame(key, cache.generation, 64, 64) { width, height ->
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(color)
                }
            }
        }
        try {
            val superseded = CompletableFuture<Boolean>()
            instrumentation.runOnMainSync {
                save(Color.GREEN)
                assertTrue(cache.refreshCoverFromSurface(key, cache.generation, 64, 64, surface) {
                    superseded.complete(it)
                })
                save(Color.RED)
            }
            assertFalse(superseded.get(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                checkNotNull(cache.frame(key)).use { assertEquals(Color.RED, it.bitmap?.getPixel(31, 31)) }
            }

            val reset = CompletableFuture<Boolean>()
            instrumentation.runOnMainSync {
                assertTrue(cache.refreshCoverFromSurface(key, cache.generation, 64, 64, surface) {
                    reset.complete(it)
                })
                cache.clearFrames()
            }
            assertFalse(reset.get(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                assertNull(cache.frame(key))
                assertEquals(0, budget.bytes)
            }
        } finally {
            instrumentation.runOnMainSync {
                cache.close()
                surface.release()
                texture.release()
            }
        }
    }

    // Блокируем первую запись на диск и с экрана запускаем второе получение кадра. Оно должно
    // закончиться до того, как освободится запись.
    @Test
    fun aSecondCaptureDoesNotWaitForAnEarlierDiskWrite() {
        val fixture = fixture()
        val budget = ImageByteBudget()
        lateinit var cache: VideoPlaybackCache
        instrumentation.runOnMainSync { cache = VideoPlaybackCache(budget, fixture.root) }
        val diskGate = VideoCoverDiskCache::class.java.getDeclaredField("gate")
            .apply { isAccessible = true }.get(null)
        val secondCapture = CountDownLatch(1)
        // Просим сохранить копию известного кадра. Для второго вызова используем другой ключ,
        // чтобы проверка действительно наблюдала отдельный захват.
        fun capture(key: VideoPlaybackKey) {
            cache.rememberFrame(key, cache.generation, 64, 64) { _, _ ->
                fixture.image.copy(Bitmap.Config.ARGB_8888, false)
            }
        }
        try {
            val finishedWhileDiskWasBusy = synchronized(checkNotNull(diskGate)) {
                instrumentation.runOnMainSync { capture(fixture.key) }
                val deadline = SystemClock.uptimeMillis() + 2500
                var workerWaiting = false
                while (!workerWaiting && SystemClock.uptimeMillis() < deadline) {
                    workerWaiting = Thread.getAllStackTraces().any { (thread, stack) ->
                        thread.state == Thread.State.BLOCKED && stack.any {
                            it.className == VideoCoverDiskCache::class.java.name && it.methodName == "store"
                        }
                    }
                    if (!workerWaiting) SystemClock.sleep(2)
                }
                assertTrue("The first writer must reach the held disk monitor", workerWaiting)
                Handler(Looper.getMainLooper()).post {
                    capture(fixture.key.copy(messageID = fixture.key.messageID + 1))
                    secondCapture.countDown()
                }
                secondCapture.await(500, TimeUnit.MILLISECONDS)
            }
            assertTrue("Capturing on the UI thread must not wait for a disk write", finishedWhileDiskWasBusy)
        } finally {
            instrumentation.runOnMainSync { cache.close() }
            awaitReleased(budget)
            fixture.close()
        }
    }

    // Ищем отсутствующий в памяти кадр под наблюдением системной проверки доступа к диску. Сразу
    // получаем пустой ответ, а физическое чтение должно уйти из потока экрана.
    @Test
    fun frameLookupNeverReadsDiskOnTheUiThread() {
        val root = File(instrumentation.targetContext.cacheDir, "cover-restore-${UUID.randomUUID()}")
        val budget = ImageByteBudget()
        val attachment = Attachment("cover", "remote/cover.mp4", "video/mp4", 100, 64, 64, 1, 3000, "cover")
        val key = VideoPlaybackKey(81001, attachment)
        val source = instrumentation.context.assets.open(
            "real-media/backend-824d66cce0d64409ad4888a13792c7d4.jpg",
        ).use { checkNotNull(BitmapFactory.decodeStream(it)) }
        val image = Bitmap.createScaledBitmap(source, 64, 64, true)
        val disk = VideoCoverDiskCache(root)
        disk.store(image, key)
        if (image !== source) image.recycle()
        source.recycle()
        disk.close()
        val violations = CopyOnWriteArrayList<String>()
        var returnedFrame = false
        instrumentation.runOnMainSync {
            val cache = VideoPlaybackCache(budget, root)
            val policy = StrictMode.getThreadPolicy()
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder(policy)
                .detectDiskReads().penaltyListener({ it.run() }) {
                    violations += it.javaClass.simpleName
                }.build())
            try {
                val frame = cache.cover(key)
                returnedFrame = frame != null
                frame?.close()
            } finally {
                StrictMode.setThreadPolicy(policy)
                cache.close()
            }
        }
        try {
            assertFalse("An empty RAM lookup must schedule restoration separately", returnedFrame)
            assertTrue("UI lookup performed disk IO: $violations", violations.isEmpty())
            assertEquals(0, budget.bytes)
        } finally {
            root.deleteRecursively()
        }
    }

    private class Fixture(val root: File, val key: VideoPlaybackKey, val image: Bitmap) {
        // Освобождаем рисунок примера и убираем временный каталог. Другой сценарий не наследует
        // файлы и память.
        fun close() { image.recycle(); root.deleteRecursively() }
    }

    // Создаём временный каталог, ключ ролика и известное изображение. Это даёт одинаковый вход
    // для проверок чтения и записи.
    private fun fixture(): Fixture {
        val directory = File(instrumentation.targetContext.cacheDir, "cover-async-" + UUID.randomUUID())
        val attachment = Attachment("cover", "remote/cover.mp4", "video/mp4", 100, 64, 64, 1, 3000, "cover")
        val key = VideoPlaybackKey(81001, attachment)
        val source = instrumentation.context.assets.open(
            "real-media/backend-824d66cce0d64409ad4888a13792c7d4.jpg",
        ).use { checkNotNull(BitmapFactory.decodeStream(it)) }
        val scaled = Bitmap.createScaledBitmap(source, 64, 64, true)
        val image = checkNotNull(scaled.copy(Bitmap.Config.ARGB_8888, false))
        if (scaled !== source) scaled.recycle()
        source.recycle()
        val disk = VideoCoverDiskCache(directory)
        disk.store(image, key)
        disk.close()
        return Fixture(directory, key, image)
    }

    // Ждём, пока просроченные работы отпустят все точки, и проверяем нулевой счётчик. Это ловит
    // утечку даже после отменённого ответа.
    private fun awaitReleased(budget: ImageByteBudget) {
        val until = android.os.SystemClock.uptimeMillis() + 3000
        while (budget.bytes != 0 && android.os.SystemClock.uptimeMillis() < until) Thread.sleep(5)
        assertEquals("All obsolete pixels must release their byte ownership", 0, budget.bytes)
    }

    // Просим один кадр двумя владельцами одновременно. Оба получают один декодированный рисунок,
    // его точки совпадают с исходными, а следующий поиск берёт уже готовую копию из памяти.
    @Test
    fun concurrentRestoresSharePixelsAndPromoteThemToMemory() {
        val fixture = fixture()
        val budget = ImageByteBudget()
        lateinit var cache: VideoPlaybackCache
        val frames = CopyOnWriteArrayList<PreparedImage>()
        val errors = CopyOnWriteArrayList<Throwable>()
        val delivered = java.util.concurrent.CountDownLatch(2)
        try {
            instrumentation.runOnMainSync {
                cache = VideoPlaybackCache(budget, fixture.root)
                repeat(2) {
                    cache.restoreCover(fixture.key) { result ->
                        result.onSuccess { frames.add(it) }.onFailure { errors.add(it) }
                        delivered.countDown()
                    }
                }
            }
            assertTrue(delivered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("Valid real cover must restore: " + errors, errors.isEmpty())
            assertEquals(2, frames.size)
            assertSame("Concurrent owners must share one decoded bitmap", frames[0].bitmap, frames[1].bitmap)
            val restored = checkNotNull(frames[0].bitmap)
            val expectedPixels = pixelColors(fixture.image)
            val restoredPixels = pixelColors(restored)
            println("KANDINSKY_COVER_PIXELS differences=" + expectedPixels.indices.count { expectedPixels[it] != restoredPixels[it] } +
                " expectedAlpha=" + fixture.image.hasAlpha() + " restoredAlpha=" + restored.hasAlpha())
            assertArrayEquals("Every real image pixel must survive restoration", expectedPixels, restoredPixels)
            File(fixture.root, "media/video-covers").deleteRecursively()
            instrumentation.runOnMainSync {
                val cached = cache.cover(fixture.key)
                try { assertSame("Disk restoration must populate RAM", frames[0].bitmap, cached?.bitmap) }
                finally { cached?.close() }
            }
        } finally {
            frames.forEach { it.close() }
            instrumentation.runOnMainSync { cache.close() }
            awaitReleased(budget)
            fixture.close()
        }
    }

    // Чистим запас после начала чтения, но до показа. Ответ старого номера не должен появиться в
    // памяти или удержать рисунок.
    @Test
    fun clearBeforeReadDeliveryCancelsPublicationAndReleasesPixels() {
        val fixture = fixture()
        val budget = ImageByteBudget()
        lateinit var cache: VideoPlaybackCache
        val delivered = java.util.concurrent.CountDownLatch(1)
        var published = false
        try {
            instrumentation.runOnMainSync {
                cache = VideoPlaybackCache(budget, fixture.root)
                cache.restoreCover(fixture.key) { result ->
                    published = result.isSuccess
                    result.getOrNull()?.close()
                    delivered.countDown()
                }
                cache.clearFrames()
            }
            assertTrue(delivered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse("A cleared generation cannot publish an old frame", published)
            awaitReleased(budget)
            instrumentation.runOnMainSync { assertNull(cache.cover(fixture.key)) }
        } finally {
            instrumentation.runOnMainSync { cache.close() }
            awaitReleased(budget)
            fixture.close()
        }
    }

    // Удерживаем старый рисунок с диска и записываем новый кадр. После выдачи клиент и хранилище
    // должны видеть свежие точки.
    @Test
    fun diskCompletionDoesNotReplaceANewerCapturedFrame() {
        val fixture = fixture()
        val alternate = Bitmap.createBitmap(fixture.image, 0, 0, 64, 64,
            android.graphics.Matrix().apply { postRotate(180f) }, true)
        assertFalse("Real fixture must distinguish old and fresh frames", pixelColors(alternate).contentEquals(pixelColors(fixture.image)))
        val budget = ImageByteBudget()
        lateinit var cache: VideoPlaybackCache
        val delivered = java.util.concurrent.CountDownLatch(1)
        var result: PreparedImage? = null
        try {
            instrumentation.runOnMainSync {
                cache = VideoPlaybackCache(budget, fixture.root)
                cache.restoreCover(fixture.key) { value -> result = value.getOrNull(); delivered.countDown() }
                val until = android.os.SystemClock.uptimeMillis() + 2000
                while (budget.bytes == 0 && android.os.SystemClock.uptimeMillis() < until) Thread.sleep(1)
                assertTrue("Reader must own the old file before the newer capture", budget.bytes > 0)
                cache.rememberFrame(fixture.key, cache.generation, 64, 64) { _, _ ->
                    alternate.copy(Bitmap.Config.ARGB_8888, false)
                }
            }
            assertTrue(delivered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertArrayEquals("Late disk pixels cannot replace a fresh capture", pixelColors(alternate), pixelColors(checkNotNull(result?.bitmap)))
            instrumentation.runOnMainSync {
                val cached = cache.cover(fixture.key)
                try { assertArrayEquals(pixelColors(alternate), pixelColors(checkNotNull(cached?.bitmap))) }
                finally { cached?.close() }
            }
        } finally {
            result?.close()
            instrumentation.runOnMainSync { cache.close() }
            awaitReleased(budget)
            alternate.recycle()
            fixture.close()
        }
    }

    // Удаляем разрешение на кадр, когда чтение уже началось. Поздний ответ не должен снова
    // показать отозванный рисунок.
    @Test
    fun deletionAfterReadStartsCannotPublishTheRevokedCover() {
        val fixture = fixture()
        val budget = ImageByteBudget()
        lateinit var cache: VideoPlaybackCache
        val delivered = java.util.concurrent.CountDownLatch(1)
        var published = false
        try {
            instrumentation.runOnMainSync {
                cache = VideoPlaybackCache(budget, fixture.root)
                cache.restoreCover(fixture.key) { result ->
                    published = result.isSuccess
                    result.getOrNull()?.close()
                    delivered.countDown()
                }
                val until = android.os.SystemClock.uptimeMillis() + 2000
                while (budget.bytes == 0 && android.os.SystemClock.uptimeMillis() < until) Thread.sleep(1)
                assertTrue("Reader must open the cover before revocation", budget.bytes > 0)
                VideoCoverDiskCache.revoke(fixture.root, fixture.key.relativePath)
            }
            assertTrue(delivered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse("A revoked cover must not escape a pending read", published)
            instrumentation.runOnMainSync { assertNull(cache.cover(fixture.key)) }
        } finally {
            instrumentation.runOnMainSync { cache.close() }
            awaitReleased(budget)
            fixture.close()
        }
    }

    // Первый получатель успешного кадра чистит запас. Остальные должны заново проверить номер
    // перед выдачей и получить отмену старого рисунка.
    @Test
    fun firstDeliveryClearingTheCacheCancelsOtherPendingOwners() {
        val fixture = fixture()
        val budget = ImageByteBudget()
        lateinit var cache: VideoPlaybackCache
        val delivered = java.util.concurrent.CountDownLatch(2)
        var deliveries = 0
        var firstSucceeded = false
        var staleSucceeded = false
        try {
            instrumentation.runOnMainSync {
                cache = VideoPlaybackCache(budget, fixture.root)
                repeat(2) {
                    cache.restoreCover(fixture.key) { result ->
                        val first = deliveries++ == 0
                        if (first) firstSucceeded = result.isSuccess else staleSucceeded = result.isSuccess
                        result.getOrNull()?.close()
                        if (first) cache.clearFrames()
                        delivered.countDown()
                    }
                }
            }
            assertTrue(delivered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(firstSucceeded)
            assertFalse("Later owners must recheck the generation at delivery", staleSucceeded)
        } finally {
            instrumentation.runOnMainSync { cache.close() }
            awaitReleased(budget)
            fixture.close()
        }
    }

    // Копируем все точки изображения в массив для точного сравнения. Одинаковая ширина двух
    // кадров сама по себе не подтверждает совпадение.
    private fun pixelColors(image: Bitmap): IntArray = IntArray(image.width * image.height).also {
        image.getPixels(it, 0, image.width, 0, 0, image.width, image.height)
    }

    // Убираем кадр из памяти и сообщаем, что видео приближается к экрану. К моменту появления
    // подготовленный рисунок должен быть готов и совпадать с сохранённым.
    @Test
    fun preparationRestoresAnEvictedCoverBeforeItsNextVisibleLookup() {
        val fixture = fixture()
        val budget = ImageByteBudget()
        lateinit var cache: VideoPlaybackCache
        try {
            instrumentation.runOnMainSync {
                cache = VideoPlaybackCache(budget, fixture.root)
                cache.rememberFrame(fixture.key, cache.generation, 64, 64) { _, _ ->
                    fixture.image.copy(Bitmap.Config.ARGB_8888, false)
                }
                for (id in 1L..65L) {
                    cache.rememberFrame(fixture.key.copy(messageID = id), cache.generation, 64, 64) { _, _ ->
                        fixture.image.copy(Bitmap.Config.ARGB_8888, false)
                    }
                }
                assertNull(cache.cover(fixture.key))
                cache.prepareCovers(listOf(fixture.key))
            }
            var prepared = false
            val until = android.os.SystemClock.uptimeMillis() + 3000
            while (!prepared && android.os.SystemClock.uptimeMillis() < until) {
                instrumentation.runOnMainSync {
                    val frame = cache.cover(fixture.key)
                    prepared = frame != null
                    frame?.close()
                }
                if (!prepared) Thread.sleep(5)
            }
            assertTrue("An approaching video must prepare its disk cover", prepared)
            File(fixture.root, "media/video-covers").deleteRecursively()
            instrumentation.runOnMainSync {
                val frame = checkNotNull(cache.cover(fixture.key))
                try {
                    assertArrayEquals(pixelColors(fixture.image), pixelColors(checkNotNull(frame.bitmap)))
                } finally { frame.close() }
                cache.prepareCovers(emptyList())
            }
        } finally {
            instrumentation.runOnMainSync { cache.close() }
            awaitReleased(budget)
            fixture.close()
        }
    }

    // Проверяем вытеснение по числу записей и при уменьшенном объёме памяти. Самый давний кадр
    // уходит, а общий размер остаётся ограниченным.
    @Test
    fun metadataAndReducedMemoryBudgetBothBoundRetainedCovers() {
        val fixture = fixture()
        val budget = ImageByteBudget().apply { limit = 24 * 1024 * 1024 }
        lateinit var cache: VideoPlaybackCache
        try {
            instrumentation.runOnMainSync {
                cache = VideoPlaybackCache(budget)
                for (id in 1L..65L) {
                    cache.rememberFrame(fixture.key.copy(messageID = id), cache.generation, 64, 64) { _, _ ->
                        fixture.image.copy(Bitmap.Config.ARGB_8888, false)
                    }
                }
                assertNull(cache.cover(fixture.key.copy(messageID = 1)))
                val recent = checkNotNull(cache.cover(fixture.key.copy(messageID = 65)))
                recent.close()
                cache.clearFrames()
                for (id in 1L..3L) {
                    cache.rememberFrame(fixture.key.copy(messageID = id), cache.generation, 1024, 1024) { w, h ->
                        Bitmap.createScaledBitmap(fixture.image, w, h, true)
                    }
                }
                assertTrue(budget.bytes <= 8 * 1024 * 1024)
                assertNull(cache.cover(fixture.key.copy(messageID = 1)))
            }
        } finally {
            instrumentation.runOnMainSync { cache.close() }
            awaitReleased(budget)
            fixture.close()
        }
    }

    // Проверяем, что восемь лёгких кадров помещаются в прежние шестнадцать мебибайт. Крупные
    // кадры по-прежнему вытесняют старые при достижении того же предела.
    @Test
    fun smallCoversSurviveBeyondFourWithoutIncreasingThePreviousByteCeiling() {
        val fixture = fixture()
        val budget = ImageByteBudget()
        lateinit var cache: VideoPlaybackCache
        try {
            instrumentation.runOnMainSync {
                cache = VideoPlaybackCache(budget)
                for (id in 1L..8L) cache.rememberFrame(fixture.key.copy(messageID = id), cache.generation, 64, 64) { _, _ ->
                    fixture.image.copy(Bitmap.Config.ARGB_8888, false)
                }
                val first = cache.frame(fixture.key.copy(messageID = 1))
                try { assertNotNull("Eight small covers fit the existing16MiB budget", first) }
                finally { first?.close() }
                cache.clearFrames()
                for (id in 1L..5L) cache.rememberFrame(fixture.key.copy(messageID = id), cache.generation, 1024, 1024) { w, h ->
                    Bitmap.createScaledBitmap(fixture.image, w, h, true)
                }
                assertTrue("Four maximum-sized covers remain the same byte ceiling", budget.bytes <= 16 * 1024 * 1024)
                assertNull(cache.frame(fixture.key.copy(messageID = 1)))
            }
        } finally {
            instrumentation.runOnMainSync { cache.close() }
            awaitReleased(budget)
            fixture.close()
        }
    }
}
