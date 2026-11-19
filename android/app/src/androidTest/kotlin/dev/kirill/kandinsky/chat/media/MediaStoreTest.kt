// Проверяем хранилище изображений и роликов на андроиде. Сценарии сверяют чёткие кадры, очередь и
// сохранение файлов, а также проверяют, что ошибки и закрытие возвращают занятую память.

package dev.kirill.kandinsky.chat.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.media.kernel.*
import dev.kirill.kandinsky.chat.network.NetworkMedia
import dev.kirill.kandinsky.chat.network.NetworkSchema
import dev.kirill.kandinsky.chat.network.StudioDraftStorage
import dev.kirill.kandinsky.chat.rendering.*
import dev.kirill.kandinsky.chat.storage.ChatRepository
import dev.kirill.kandinsky.chat.storage.ChatStorageCodec
import dev.kirill.kandinsky.chat.support.*
import dev.kirill.kandinsky.chat.support.TestFixtures
import java.io.File
import java.nio.ByteBuffer
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MediaStoreTest {
    @Test
    fun stagedVideosKeepTheirRotationAndTheirOriginalBytes() {
        val root = File(context.cacheDir, "rotated-source-" + UUID.randomUUID())
        val harness = RepositoryHarness.open(root)
        val media = MediaStore(harness.store)
        val original = File(root, "fixture.mp4")
        try {
            TestFixtures.assets.open("media/video-01.mp4").use { input ->
                original.outputStream().use { output -> input.copyTo(output) }
            }
            for ((rotation, orientation) in listOf(90 to 6, 180 to 3, 270 to 8)) {
                val rotated = File(root, "fixture-$rotation.mp4")
                val extractor = android.media.MediaExtractor()
                val muxer = android.media.MediaMuxer(
                    rotated.absolutePath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
                )
                try {
                    extractor.setDataSource(original.absolutePath)
                    val track = (0 until extractor.trackCount).first {
                        extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)
                            ?.startsWith("video/") == true
                    }
                    val format = extractor.getTrackFormat(track)
                    extractor.selectTrack(track)
                    val output = muxer.addTrack(format)
                    muxer.setOrientationHint(rotation)
                    muxer.start()
                    val buffer = ByteBuffer.allocate(1024 * 1024)
                    val info = android.media.MediaCodec.BufferInfo()
                    do {
                        buffer.clear()
                        val length = extractor.readSampleData(buffer, 0)
                        if (length < 0) break
                        info.set(0, length, extractor.sampleTime, extractor.sampleFlags)
                        muxer.writeSampleData(output, buffer, info)
                    } while (extractor.advance())
                    muxer.stop()
                } finally {
                    muxer.release()
                    extractor.release()
                }
                val imported = awaitResult<AttachmentDraft> { reply ->
                    main { media.stageSelection({ rotated.inputStream() }, "video/mp4", reply) }
                    Cancellation {}
                }
                assertEquals(orientation, imported.orientation)
                assertEquals(256, imported.rawWidth)
                assertEquals(256, imported.rawHeight)
                assertTrue((imported.durationMs ?: 0) > 0)
                assertArrayEquals(rotated.readBytes(), media.fileURL(imported.relativePath).readBytes())
            }
        } finally {
            main { media.close() }
            harness.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun recoveryKeepsOriginalsReferencedByTheNewChatAndUnconfirmedRequests() {
        val root = File(context.cacheDir, "pending-media-" + UUID.randomUUID())
        val harness = RepositoryHarness.open(root)
        val media = MediaStore(harness.store)
        main { media.pendingReferences = NetworkMedia::pendingReferences }
        try {
            val files = List(5) { stage(media) }
            fun send(index: Int) = SendCommand(
                UUID.randomUUID().toString(), 2, MessageKind.PHOTO,
                attachments = listOf(files[index]),
            )

            val draft = StudioDraftRecord(
                UUID.randomUUID(),
                Draft(
                    attachmentDrafts = listOf(files[0]), pendingSend = send(1),
                    pendingMediaBatch = listOf(send(2)),
                ),
            )
            val queued = send(3)
            val payload = ChatStorageCodec.commandString(queued)
            val expected = files.take(4).map { it.relativePath }.toSet()
            awaitResult<Unit> { callback ->
                harness.store.submit({ db ->
                    NetworkSchema.install(db)
                    StudioDraftStorage.write(db, draft)
                    db.execSQL(
                        "INSERT INTO net_outbox(id,action,payload,payloadBytes) VALUES(?,'send',?,?)",
                        arrayOf<Any>(queued.operationID, payload, payload.toByteArray().size),
                    )
                    assertEquals(expected, NetworkMedia.pendingReferences(db))
                }, callback)
            }
            recover(media)
            for (file in files.take(4)) {
                assertTrue(media.fileURL(file.relativePath).isFile)
            }
            assertFalse(media.fileURL(files.last().relativePath).exists())
        } finally {
            main { media.close() }
            harness.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun recoveryKeepsAnOriginalFromAnOlderUnsentChatDraft() {
        val root = File(context.cacheDir, "legacy-pending-media-" + UUID.randomUUID())
        val harness = RepositoryHarness.open(root)
        val media = MediaStore(harness.store)
        try {
            val kept = stage(media)
            val abandoned = stage(media)
            val oldDraft = Draft(pendingSend = SendCommand(
                UUID.randomUUID().toString(), 2, MessageKind.PHOTO,
                attachments = listOf(kept),
            ))
            val original = media.fileURL(kept.relativePath)
            val bytes = original.readBytes()
            awaitResult<Unit> { callback -> harness.repo.saveDraft(2, oldDraft, callback) }

            recover(media)
            assertArrayEquals(bytes, original.readBytes())
            assertFalse(media.fileURL(abandoned.relativePath).exists())
        } finally {
            main { media.close() }
            harness.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun discardingRefusesToFollowAReplacedOriginalOutsideItsMediaRoot() {
        val root = File(context.cacheDir, "discard-link-" + UUID.randomUUID())
        val harness = RepositoryHarness.open(root)
        val media = MediaStore(harness.store)
        try {
            val unchanged = stage(media)
            val replaced = stage(media)
            val target = media.fileURL(replaced.relativePath)
            val outside = File(root, "unrelated.jpg")
            assertTrue(target.renameTo(outside))
            val bytes = outside.readBytes()
            android.system.Os.symlink(outside.path, target.path)

            val failure = runCatching {
                awaitResult<Unit> { callback ->
                    main { media.discardUnpublishedImports(listOf(unchanged, replaced), callback) }
                    Cancellation {}
                }
            }.exceptionOrNull() as? MediaException
            assertEquals(MediaError.CORRUPT, failure?.reason)
            assertArrayEquals(bytes, outside.readBytes())
            assertTrue(media.fileURL(unchanged.relativePath).isFile)
        } finally {
            main { media.close() }
            harness.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun localPlaybackKeepsItsFileAndCancellationClosesALateStream() {
        val root = File(context.cacheDir, "local-and-cancelled-playback-" + UUID.randomUUID())
        val harness = RepositoryHarness.open(root)
        val media = MediaStore(harness.store)
        val running = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = AtomicInteger()
        var request: Cancellation? = null
        try {
            val draft = awaitResult<AttachmentDraft> { callback ->
                main {
                    media.stageSelection({ TestFixtures.assets.open("media/video-01.mp4") }, "video/mp4", callback)
                }
                Cancellation {}
            }
            val original = media.fileURL(draft.relativePath)
            val video = attachment(draft)
            main { media.remotePlayback = { _, _ -> error("Местный запуск не обращается к сети") } }

            awaitResult<MediaPlaybackSource> { callback ->
                main { media.playbackSource(video, localOnly = true, callback) }
                Cancellation {}
            }.use { source -> assertEquals(original, source.file) }
            val missing = runCatching {
                awaitResult<MediaPlaybackSource> { callback ->
                    main { media.playbackSource(video.copy(relativePath = "objects/missing.mp4"), true, callback) }
                    Cancellation {}
                }
            }.exceptionOrNull() as? MediaException
            assertEquals(MediaError.UNAVAILABLE, missing?.reason)

            main {
                media.remotePlayback = { source, token ->
                    assertEquals(video.id, source.id)
                    token.onCancel { cancelled.countDown() }
                    running.countDown()
                    check(release.await(8, TimeUnit.SECONDS))
                    MediaPlaybackSource(file = original, onClose = { closed.incrementAndGet() })
                }
            }
            val completed = CountDownLatch(1)
            val completion = AtomicReference<Result<MediaPlaybackSource>>()
            main {
                request = media.playbackSource(video) {
                    completion.set(it)
                    completed.countDown()
                }
            }
            assertTrue(running.await(5, TimeUnit.SECONDS))
            main { request?.cancel() }
            assertTrue(cancelled.await(3, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            val error = completion.get().exceptionOrNull() as? MediaException
            assertEquals(MediaError.CANCELLED, error?.reason)
            assertEquals(1, closed.get())
            assertTrue(original.isFile)
        } finally {
            release.countDown()
            main {
                request?.cancel()
                media.close()
            }
            harness.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun cancellingALocalVideoLookupReleasesItsFileAndPreventsPlayback() {
        val root = File(context.cacheDir, "cancel-local-video-" + UUID.randomUUID())
        val harness = RepositoryHarness.open(root)
        val media = MediaStore(harness.store)
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val result = AtomicReference<Result<MediaPlaybackSource>>()
        var opening: Cancellation? = null
        try {
            val bytes = TestFixtures.assets.open(
                "real-media/backend-990da02cd31e46cfb8fd630c293f3489.mp4"
            ).use { it.readBytes() }
            val draft = awaitResult<AttachmentDraft> { callback ->
                main { media.stageSelection({ bytes.inputStream() }, "video/mp4", callback) }
                Cancellation {}
            }
            val video = attachment(draft)
            val original = media.fileURL(draft.relativePath)
            main {
                media.remotePlayback = { _, _ -> error("Местному просмотру не нужна сеть") }
                media.remoteCached = { requested, preview, token ->
                    assertEquals(video.id, requested.id)
                    assertFalse(preview)
                    token.onCancel { released.countDown() }
                    entered.countDown()
                    check(resume.await(5, TimeUnit.SECONDS))
                    original to video.mime
                }
                opening = media.playbackSource(video, localOnly = true) {
                    result.set(it)
                    completed.countDown()
                }
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            main { opening?.cancel() }
            assertTrue(released.await(3, TimeUnit.SECONDS))
            resume.countDown()
            assertTrue(completed.await(3, TimeUnit.SECONDS))
            val outcome = requireNotNull(result.get())
            outcome.getOrNull()?.close()
            assertEquals(MediaError.CANCELLED, (outcome.exceptionOrNull() as? MediaException)?.reason)
            main { media.remoteCached = null }
            awaitResult<MediaPlaybackSource> { callback ->
                main { media.playbackSource(video, localOnly = true, callback) }
                Cancellation {}
            }.use { source -> assertEquals(original, source.file) }
            assertArrayEquals(bytes, original.readBytes())
        } finally {
            resume.countDown()
            main {
                opening?.cancel()
                media.close()
            }
            harness.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun cancellingBeforeDeliveryRemovesTheImportedFileAndKeepsPublishedMedia() {
        val root = File(context.cacheDir, "cancel-import-before-delivery-" + UUID.randomUUID())
        val harness = RepositoryHarness.open(root)
        val media = MediaStore(harness.store)
        try {
            val fixture = "real-media/backend-824d66cce0d64409ad4888a13792c7d4.jpg"
            val saved = stage(media) { TestFixtures.assets.open(fixture) }
            val original = media.fileURL(saved.relativePath)
            val bytes = original.readBytes()
            val published = original.parentFile!!
            val response = CompletableFuture<Result<AttachmentDraft>>()
            var pendingFile: File? = null
            main {
                val request = media.stage({ TestFixtures.assets.open(fixture) }, "image/jpeg") {
                    response.complete(it)
                }
                // Даём рабочему потоку записать файл, но откладываем доставку его результата.
                val deadline = SystemClock.uptimeMillis() + 3_000
                while (pendingFile == null && SystemClock.uptimeMillis() < deadline) {
                    pendingFile = published.listFiles()?.firstOrNull { it != original && it.isFile }
                    if (pendingFile == null) SystemClock.sleep(5)
                }
                request.cancel()
            }
            val cancelled = response.get(5, TimeUnit.SECONDS)
            assertEquals(MediaError.CANCELLED, (cancelled.exceptionOrNull() as? MediaException)?.reason)
            assertFalse("Отмена должна убрать записанную, но ещё не выданную копию",
                requireNotNull(pendingFile).exists())
            assertEquals(listOf(original.name), published.list().orEmpty().toList())
            assertArrayEquals(bytes, original.readBytes())
        } finally {
            main { media.close() }
            harness.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun closedStoreRejectsNewFileWorkWithoutReadingTheOriginalAgain() {
        val root = File(context.cacheDir, "closed-media-" + UUID.randomUUID())
        val harness = RepositoryHarness.open(root)
        val media = MediaStore(harness.store)
        try {
            val original = stage(media)
            val attachment = attachment(original)
            val savedBytes = media.fileURL(original.relativePath).readBytes()
            main { media.close() }
            val stagingFailure = runCatching {
                stage(media) { error("Хранилище закрыто и не должно открывать новый файл") }
            }.exceptionOrNull() as? MediaException
            assertEquals(MediaError.CANCELLED, stagingFailure?.reason)
            val recoveryFailure = runCatching { recover(media) }.exceptionOrNull() as? MediaException
            assertEquals(MediaError.UNAVAILABLE, recoveryFailure?.reason)
            val thumbnailFailure = runCatching {
                awaitResult<PreparedImage> { callback ->
                    main {
                        media.thumbnail(attachment, ThumbnailKey(128), 7) { ticket, result ->
                            assertEquals(7, ticket)
                            callback(result)
                        }
                    }
                    Cancellation {}
                }
            }.exceptionOrNull() as? MediaException
            assertEquals(MediaError.CANCELLED, thumbnailFailure?.reason)
            assertTrue(savedBytes.contentEquals(media.fileURL(original.relativePath).readBytes()))
        } finally {
            main { media.close() }
            harness.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun discardingAnInvalidSelectionLeavesBothRealFilesInPlace() {
        val root = File(context.cacheDir, "discard-media-" + UUID.randomUUID())
        val harness = RepositoryHarness.open(root)
        val media = MediaStore(harness.store)
        try {
            val keep = stage(media)
            val other = stage(media)
            val keepFile = media.fileURL(keep.relativePath)
            val otherFile = media.fileURL(other.relativePath)
            val inconsistent = other.copy(relativePath = keep.relativePath)
            val failure = runCatching {
                awaitResult<Unit> { callback ->
                    main { media.discardUnpublishedImports(listOf(other, inconsistent), callback) }
                    Cancellation {}
                }
            }.exceptionOrNull() as? MediaException
            assertEquals(MediaError.CORRUPT, failure?.reason)
            assertTrue(keepFile.isFile)
            assertTrue(otherFile.isFile)
            awaitResult<Unit> { callback ->
                main { media.discardUnpublishedImport(other, callback) }
                Cancellation {}
            }
            assertTrue(keepFile.isFile)
            assertFalse(otherFile.exists())
        } finally {
            main { media.close() }
            harness.close()
            root.deleteRecursively()
        }
    }

    // Берём одиннадцать старых маленьких кадров и измеряем перепады яркости до и после
    // подготовки. Ступеньки должны сгладиться, а размеры и общая яркость остаться близкими.
    @Test
    fun legacyInlineJpegsDisplaySoftGradients() {
        val h = RepositoryHarness.open(File(context.cacheDir, "soft-inline-preview-" + UUID.randomUUID()))
        val media = MediaStore(h.store)
        val records = TestFixtures.assets.open("real-media/tiny-preview-probe.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONArray("records") }

        // Считываем точки и переводим цвет в приближение яркости, учитывая каналы с разным весом.
        // Так можно сравнить видимые ступеньки независимо от оттенка.
        fun luminance(bitmap: Bitmap): IntArray {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return pixels.map { c ->
                (3 * android.graphics.Color.red(c) + 6 * android.graphics.Color.green(c) +
                    android.graphics.Color.blue(c)) / 10
            }.toIntArray()
        }
        // Складываем резкие перепады яркости по горизонтали и вертикали у внутренних точек. Чем
        // меньше результат, тем мягче меняются соседние тона.
        fun curvature(data: IntArray, width: Int, height: Int): Int {
            var total = 0
            for (y in 1 until height - 1) for (x in 1 until width - 1) {
                val i = y * width + x
                val center = 2 * data[i]
                total += kotlin.math.abs(data[i - 1] + data[i + 1] - center)
                total += kotlin.math.abs(data[i - width] + data[i + width] - center)
            }
            return total
        }

        // Измеряем общий перепад яркости между соседними точками. Сравнение до и после
        // показывает, насколько изменился мелкий контраст.
        fun energy(data: IntArray, width: Int, height: Int): Long {
            var total = 0L
            for (y in 0 until height - 1) for (x in 0 until width - 1) {
                val i = y * width + x
                val dx = data[i + 1] - data[i]
                val dy = data[i + width] - data[i]
                total += dx * dx + dy * dy
            }
            return total
        }

        try {
            assertEquals(11, records.length())
            for (video in listOf(false, true)) {
                var worstRatio = 0.0
                val energyRatios = mutableListOf<Double>()
                for (index in 0 until records.length()) {
                    val text = records.getJSONObject(index).getString("tinyPreview")
                    val bytes = android.util.Base64.decode(text, android.util.Base64.NO_WRAP)
                    val input = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                    try {
                        val asset = Attachment(
                            id = "soft-preview-$index",
                            relativePath = "remote/soft-preview-$index." + (if (video) "mp4" else "jpg"),
                            mime = if (video) "video/mp4" else "image/jpeg", byteSize = 1000, rawWidth = 900, rawHeight = 600,
                            orientation = 1, durationMs = null, thumbnailKey = "", tinyPreview = text,
                        )
                        val prepared = awaitResult<PreparedImage> { callback ->
                            main { media.tinyPreview(asset, callback) }
                            Cancellation {}
                        }
                        prepared.use { frame ->
                            val image = checkNotNull(frame.bitmap)
                            assertTrue(frame.isTinyPreview)
                            assertEquals(input.width, image.width)
                            assertEquals(input.height, image.height)
                            val before = luminance(input)
                            val after = luminance(image)
                            val originalCurvature = curvature(before, input.width, input.height)
                            val displayCurvature = curvature(after, image.width, image.height)
                            assertTrue(originalCurvature > 0)
                            val ratio = displayCurvature.toDouble() / originalCurvature
                            worstRatio = maxOf(worstRatio, ratio)
                            val inputEnergy = energy(before, input.width, input.height)
                            assertTrue(inputEnergy > 0)
                            energyRatios += energy(after, image.width, image.height).toDouble() / inputEnergy
                            assertTrue("The old JPEG's pixel steps must soften before scaling: $ratio", ratio <= 0.55)
                            assertTrue(kotlin.math.abs(before.sum() - after.sum()) <= before.size * 6)
                        }
                    } finally {
                        input.recycle()
                    }
                }
                val median = energyRatios.sorted()[energyRatios.size / 2]
                println("KANDINSKY_SOFT_PREVIEW video=$video median_energy=$median worst_curvature=$worstRatio")
                val target = if (video) 0.025 else 0.045
                assertTrue("Fine contrast in the incoming placeholder must be lower than before: $median",
                    median <= target)
            }
        } finally {
            main { media.close() }
            h.close()
        }
    }

    // Готовим размытый кадр и скрываем приложение вне перехода. Проверяем нулевую память и
    // отсутствие маленького кадра при следующем поиске.
    @Test
    fun leavingStudioOutsideNavigationReleasesCachedBlurPixels() {
        val h = RepositoryHarness.open(File(context.cacheDir, "background-blur-" + UUID.randomUUID()))
        val media = MediaStore(h.store, studioOriginals = true)
        val draft = stage(media)
        val asset = attachment(draft).copy(tinyPreview = draft.tinyPreview)
        try {
            val frame = awaitResult<PreparedImage> { callback ->
                main { media.tinyPreview(asset, callback) }
                Cancellation {}
            }
            main {
                frame.close()
                assertTrue(media.bytesUsed > 0)
                media.releaseIdleImages()
                assertEquals("Background must release blurred pixels too", 0, media.bytesUsed)
                assertNull(media.cachedTinyPreview(asset))
            }
        } finally {
            main { media.close() }
            h.close()
        }
    }

    // Замещаем подготовку прежнего ближайшего ролика новой. Проверяем, что готовые соседние
    // рисунки ещё доступны и их размеры ограничены.
    @Test
    fun videoPreparationChangesNearestRowWithoutDroppingPreparedPosters() {
        val h = RepositoryHarness.open(File(context.cacheDir, "video-nearest-" + UUID.randomUUID()))
        val media = MediaStore(h.store, studioOriginals = true)
        val draft = stage(media)
        val photo = media.fileURL(draft.relativePath)
        val pixelLimit = MediaImagePrefetch.ALBUM_MAX_PIXEL
        val a = attachment(draft).copy(id = "near-a", mime = "video/mp4", byteSize = 1024,
            durationMs = 1000, relativePath = "remote/" + "a".repeat(32) + ".mp4")
        val b = a.copy(id = "near-b", relativePath = "remote/" + "b".repeat(32) + ".mp4")
        main { media.remoteFile = { _, _, _ -> photo to "image/jpeg" } }
        for (asset in listOf(a, b)) {
            awaitResult<PreparedImage> { callback ->
                main { media.thumbnail(asset, ThumbnailKey(pixelLimit), 0) { _, result -> callback(result) } }
                Cancellation {}
            }.close()
        }
        val entered = CountDownLatch(1)
        val changed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val prefetch = MediaImagePrefetch()
        try {
            main {
                media.remotePlayback = { asset, _ ->
                    val first = asset.id == a.id
                    if (!first) changed.countDown()
                    val cache = MediaRangeCache(h.store.root, (if (first) "a" else "b").repeat(32),
                        1024, File(h.store.root, "media/${asset.relativePath}")) { _, _, token ->
                        if (first) {
                            entered.countDown()
                            token.onCancel { release.countDown() }
                            release.await(3, TimeUnit.SECONDS)
                            throw MediaException(MediaError.CANCELLED)
                        }
                        ByteArray(1024) { 7 }
                    }
                    MediaPlaybackSource(ranges = cache)
                }
                prefetch.update(listOf(a, b).map { MediaImagePrefetch.Item(it, pixelLimit) }, media)
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            main { prefetch.update(listOf(b, a).map { MediaImagePrefetch.Item(it, pixelLimit) }, media) }
            assertTrue("Changed nearest video must replace previous preparation",
                changed.await(1, TimeUnit.SECONDS))
            main { for (asset in listOf(a, b)) {
                val frame = prefetch.preview(MediaImagePrefetch.Item(asset, pixelLimit), media)
                assertNotNull(frame)
                frame?.use {
                    val pixels = checkNotNull(it.bitmap)
                    assertTrue(maxOf(pixels.width, pixels.height) <= pixelLimit)
                }
                assertNull("The album should only prepare its own display size",
                    prefetch.preview(MediaImagePrefetch.Item(asset), media))
            } }
        } finally {
            release.countDown()
            main { prefetch.clear(); media.close() }
            h.close()
        }
    }

    // Отмечаем заранее прочитанные части длинного ролика и сверяем их предел. После этого читаем
    // начало и конец без нового получения того же диапазона.
    @Test
    fun videoPreparationWarmsBoundedStartAndTailForTheNextPlayer() {
        val h = RepositoryHarness.open(File(context.cacheDir, "video-warm-" + UUID.randomUUID()))
        val media = MediaStore(h.store, studioOriginals = true)
        val bytes = ByteArray(2 * 1024 * 1024 + 17) { (it % 251).toByte() }
        val blob = "a".repeat(32)
        val asset = Attachment("range-warm", "remote/$blob.mp4", "video/mp4", bytes.size.toLong(),
            640, 480, 1, 8000L, blob)
        val fetched = Collections.synchronizedList(mutableListOf<Pair<Long, Long>>())
        val warmed = CountDownLatch(3)
        var request: Cancellation? = null
        try {
            main {
                media.remotePlayback = { _, _ ->
                    val cache = MediaRangeCache(h.store.root, blob, bytes.size.toLong(),
                        File(h.store.root, "media/${asset.relativePath}")) { start, end, _ ->
                        fetched.add(start to end)
                        warmed.countDown()
                        bytes.copyOfRange(start.toInt(), end.toInt() + 1)
                    }
                    MediaPlaybackSource(ranges = cache)
                }
                request = media.prepareStudioVideo(asset)
                assertNotNull("Video preparation must use ranged provider", request)
            }
            assertTrue(warmed.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(0L, 1L, 8L), fetched.map { it.first / MediaRangeCache.CHUNK_SIZE })
            assertTrue(fetched.sumOf { it.second - it.first + 1 } <= 3 * 256 * 1024)
            Thread.sleep(50)
            val cache = MediaRangeCache(h.store.root, blob, bytes.size.toLong(),
                File(h.store.root, "media/${asset.relativePath}")) { _, _, _ ->
                fail("The next player must reuse prepared parts")
                byteArrayOf()
            }
            assertArrayEquals(bytes.copyOfRange(0, 10), cache.read(0, 10, MediaCancellation()))
            assertArrayEquals(bytes.takeLast(10).toByteArray(),
                cache.read(bytes.size - 10L, 10, MediaCancellation()))
            cache.close()
        } finally {
            main { request?.cancel(); media.close() }
            h.close()
        }
    }

    // Удерживаем подготовку чужого файла и показываем другое фото. Видимый запрос быстро получает
    // место, а старый получает отмену.
    @Test
    fun foregroundPhotoPreemptsOtherPrefetch() {
        val h = RepositoryHarness.open(File(context.cacheDir, "prefetch-priority-" + UUID.randomUUID()))
        val media = MediaStore(h.store, studioOriginals = true)
        val draft = stage(media)
        val original = media.fileURL(draft.relativePath)
        val a = attachment(draft).copy(id = "a", relativePath = "remote/prefetch-a.jpg")
        val b = a.copy(id = "b", relativePath = "remote/visible-b.jpg")
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val release = CountDownLatch(1)
        val foreground = CountDownLatch(1)
        val cancelledAt = java.util.concurrent.atomic.AtomicLong()
        val visibleAt = java.util.concurrent.atomic.AtomicLong()
        var background: Cancellation? = null
        try {
            main {
                media.remoteFile = { attachment, _, token ->
                    if (attachment.id == "a") {
                        started.countDown()
                        token.onCancel {
                            cancelledAt.compareAndSet(0, System.nanoTime())
                            stopped.countDown(); release.countDown()
                        }
                        check(release.await(4, TimeUnit.SECONDS) && !token.isCancelled)
                    } else visibleAt.set(System.nanoTime())
                    original to "image/jpeg"
                }
                background = media.thumbnail(a, ThumbnailKey(512), 0, priority = 0) { _, result ->
                    result.getOrNull()?.close()
                }
            }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            main {
                media.thumbnail(b, ThumbnailKey(512), 0) { _, result ->
                    result.getOrNull()?.let { it.close(); foreground.countDown() }
                }
            }
            assertTrue("Foreground transfer must preempt other prefetch", stopped.await(1, TimeUnit.SECONDS))
            assertTrue(foreground.await(1, TimeUnit.SECONDS))
            val delay = (visibleAt.get() - cancelledAt.get()) / 1_000_000.0
            println("KANDINSKY_PREFETCH_FOREGROUND_ADMISSION milliseconds=$delay")
            assertTrue(delay >= 0 && delay < 150)
        } finally {
            release.countDown()
            main { background?.cancel(); media.close() }
            h.close()
        }
    }

    // Подключаем видимую строку к уже начатому чтению. Получатели должны разделить одну карту
    // точек и один запрос.
    @Test
    fun visiblePhotoJoinsPrefetchWithoutAnotherDownloadOrDecode() {
        val h = RepositoryHarness.open(File(context.cacheDir, "prefetch-share-" + UUID.randomUUID()))
        val media = MediaStore(h.store, studioOriginals = true)
        val draft = stage(media)
        val original = media.fileURL(draft.relativePath)
        val asset = attachment(draft).copy(id = "shared", relativePath = "remote/shared.jpg")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(2)
        val calls = AtomicInteger()
        val frames = mutableListOf<PreparedImage>()
        try {
            main {
                media.remoteFile = { _, _, token ->
                    calls.incrementAndGet()
                    started.countDown()
                    token.onCancel { release.countDown() }
                    check(release.await(4, TimeUnit.SECONDS))
                    original to "image/jpeg"
                }
                media.thumbnail(asset, ThumbnailKey(512), 0, priority = 0) { _, result ->
                    result.getOrNull()?.let { frames.add(it) }; done.countDown()
                }
            }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            main {
                media.thumbnail(asset, ThumbnailKey(512), 0) { _, result ->
                    result.getOrNull()?.let { frames.add(it) }; done.countDown()
                }
            }
            release.countDown()
            assertTrue(done.await(2, TimeUnit.SECONDS))
            main {
                assertEquals("Visibility must promote existing work", 1, calls.get())
                assertEquals(2, frames.size)
                assertSame(frames[0].bitmap, frames[1].bitmap)
            }
        } finally {
            release.countDown()
            main { frames.forEach { it.close() }; media.close() }
            h.close()
        }
    }

    // Сначала показываем подготовленную маленькую картинку при задержанном исходнике. Доставка
    // остаётся активной до появления чёткого фото.
    @Test
    fun preparedStudioBlurDoesNotFinishDeliveryBeforeOriginalPixels() {
        val h = RepositoryHarness.open(File(context.cacheDir, "studio-blur-" + UUID.randomUUID()))
        val media = MediaStore(h.store, studioOriginals = true)
        val draft = stage(media)
        val value = attachment(draft).copy(
            relativePath = "remote/studio-blur.jpg", tinyPreview = checkNotNull(draft.tinyPreview),
        )
        val tiny = awaitResult<PreparedImage> { callback ->
            main { media.tinyPreview(value, callback) }
            Cancellation {}
        }
        val release = CountDownLatch(1)
        val original = media.fileURL(draft.relativePath)
        var slot: MediaImageSlot? = null
        try {
            main {
                tiny.close()
                media.remoteFile = { _, _, token ->
                    token.onCancel { release.countDown() }
                    check(release.await(5, TimeUnit.SECONDS))
                    original to "image/jpeg"
                }
                val image = MediaImageSlot(media) {}
                slot = image
                image.bind(value, android.graphics.RectF(0f, 0f, 300f, 200f),
                    kind = MessageKind.PHOTO, studioPhotoDelivery = true)
                val first = image.preview()
                try {
                    assertNotNull("Generated photo must show own prepared blur before original", first)
                    assertTrue("Blur is not original delivery completion", image.awaitingStudioPhoto)
                } finally { first?.close() }
            }
            release.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var waiting = true
            while (waiting && System.nanoTime() < deadline) {
                main { waiting = checkNotNull(slot).awaitingStudioPhoto }
                Thread.sleep(10)
            }
            main {
                assertFalse(checkNotNull(slot).awaitingStudioPhoto)
                val full = checkNotNull(checkNotNull(slot).preview())
                try { assertTrue(checkNotNull(full.bitmap).width > 64) }
                finally { full.close() }
            }
        } finally {
            release.countDown()
            main { slot?.close(); media.close() }
            h.close()
        }
    }
    // Заполняем запас больше шестидесяти четырёх кадров и проверяем его предел. Изменённый
    // исходник не может взять старую картинку, а после снятия удерживаемой копии память
    // освобождается.
    @Test
    fun studioTinyCacheHas64EntriesAndReleasesOwnersOnWarningAndClose() {
        val h = RepositoryHarness.open(File(context.cacheDir, "tiny-bound-" + UUID.randomUUID()))
        val media = MediaStore(h.store, studioOriginals = true)
        val draft = stage(media)
        var last: Attachment? = null
        var one = 0
        try {
            for (index in 0 until 80) {
                val value = attachment(draft).copy(
                    id = "tiny-$index", relativePath = "remote/tiny-$index.jpg",
                    tinyPreview = checkNotNull(draft.tinyPreview),
                )
                val frame = awaitResult<PreparedImage> { callback ->
                    main { media.tinyPreview(value, callback) }
                    Cancellation {}
                }
                main {
                    one = checkNotNull(frame.bitmap).allocationByteCount
                    frame.close()
                    assertTrue(media.bytesUsed <= 64 * one)
                }
                last = value
            }
            main {
                val value = checkNotNull(last)
                assertNull(media.cachedTinyPreview(value.copy(tinyPreview = "AA==")))
                val held = checkNotNull(media.cachedTinyPreview(value))
                media.trimMemory()
                assertNull(media.cachedTinyPreview(value))
                assertEquals(one, media.bytesUsed)
                held.close()
                assertEquals(0, media.bytesUsed)
                media.close()
                assertNull(media.cachedTinyPreview(value))
            }
        } finally { main { media.close() }; h.close() }
    }

    @Test
    fun cancellingCachedImagesBeforeDeliveryKeepsTheFramesAvailableToTheNextRow() {
        val root = File(context.cacheDir, "cancel-warm-media-" + UUID.randomUUID())
        val harness = RepositoryHarness.open(root)
        val media = MediaStore(harness.store, studioOriginals = true)
        try {
            val draft = stage(media)
            val asset = attachment(draft).copy(tinyPreview = checkNotNull(draft.tinyPreview))
            val size = ThumbnailKey(256)

            fun load(cancel: Boolean, tiny: Boolean, image: Attachment = asset): Result<PreparedImage> =
                awaitResult { receive ->
                    main {
                        val deliver: (Result<PreparedImage>) -> Unit = { receive(Result.success(it)) }
                        val request =
                            if (tiny) media.tinyPreview(image, deliver)
                            else media.thumbnail(image, size, 18, localOnly = true) { ticket, frame ->
                                assertEquals(18, ticket)
                                deliver(frame)
                            }
                        if (cancel) request.cancel()
                    }
                    Cancellation {}
                }

            for (tiny in listOf(false, true)) {
                val first = load(cancel = false, tiny = tiny).getOrThrow()
                val expected = checkNotNull(first.bitmap)
                main { first.close() }
                val cancelled = load(cancel = true, tiny = tiny)
                cancelled.getOrNull()?.let { unexpected -> main { unexpected.close() } }
                assertEquals(MediaError.CANCELLED, (cancelled.exceptionOrNull() as? MediaException)?.reason)
                val returned = load(cancel = false, tiny = tiny).getOrThrow()
                main {
                    try { assertSame(expected, returned.bitmap) }
                    finally { returned.close() }
                }
            }
            val cachedBytes = media.bytesUsed
            val damaged = asset.copy(tinyPreview = "/9j/AA==")
            val damage = load(cancel = false, tiny = true, image = damaged)
            damage.getOrNull()?.let { unexpected -> main { unexpected.close() } }
            assertTrue("Оборванная миниатюра не должна становиться кадром", damage.isFailure)
            main {
                assertEquals(cachedBytes, media.bytesUsed)
                assertNull(media.cachedTinyPreview(damaged))
                media.trimMemory()
                assertEquals(0, media.bytesUsed)
            }
        } finally {
            main { media.close() }
            harness.close()
            root.deleteRecursively()
        }
    }

    // Готовим кадр заранее и удерживаем следующее чтение. При первом показе уже должен быть тот
    // же маленький рисунок без ожидания новой очереди.
    @Test
    fun warmBlurredPreviewIsPresentDuringFirstBindWithTinyDecoderBlocked() {
        val h = RepositoryHarness.open(File(context.cacheDir, "first-blur-" + UUID.randomUUID()))
        val media = MediaStore(h.store, studioOriginals = true)
        val draft = stage(media)
        val value = attachment(draft).copy(
            relativePath = "remote/fast-scroll-preview.jpg",
            tinyPreview = checkNotNull(draft.tinyPreview),
        )
        val first = awaitResult<PreparedImage> { callback ->
            main { media.tinyPreview(value, callback) }
            Cancellation {}
        }
        val expected = checkNotNull(first.bitmap)
        main { first.close() }
        val queue = MediaStore::class.java.getDeclaredField("tinyPreviewQueue").let {
            it.isAccessible = true
            it.get(media) as MediaDecodeQueue
        }
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val network = CountDownLatch(1)
        val original = media.fileURL(draft.relativePath)
        try {
            main {
                queue.submit(work = {
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    0
                }, dispose = {}, completion = {})
                media.remoteFile = { _, _, token ->
                    token.onCancel { network.countDown() }
                    check(network.await(5, TimeUnit.SECONDS))
                    original to "image/jpeg"
                }
            }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            for (kind in listOf(MessageKind.PHOTO, MessageKind.VIDEO)) main {
                val slot = MediaImageSlot(media) {}
                slot.bind(value, android.graphics.RectF(0f, 0f, 300f, 200f),
                    kind = kind, showControls = false)
                val shown = slot.preview()
                try {
                    assertNotNull("Own prepared blur must be visible before any queue turn", shown)
                    if (shown != null) {
                        val pixels = checkNotNull(shown.bitmap)
                        assertSame(expected, pixels)
                        assertTrue(maxOf(pixels.width, pixels.height) <= 64)
                    }
                } finally { shown?.close(); slot.close() }
            }
        } finally {
            release.countDown()
            network.countDown()
            main { media.close() }
            h.close()
        }
    }
    // Заполняем память соседними кадрами и показываем четыре чётких результата. Проверяем
    // качество, общий предел и быстрый возврат уже готового альбома.
    @Test
    fun visibleAlbumDoesNotFailBehindPreparedNeighbors() {
        val h = RepositoryHarness.open(File(context.cacheDir, "neighbors-" + UUID.randomUUID()))
        val media = MediaStore(h.store, studioOriginals = true)
        val scene = RetainedChatScene()
        val folder = File(h.root, "media/remote").apply { mkdirs() }
        val original = File(folder, "reference.png")
        val source = TestFixtures.assets.open("media/" + photoName()).use {
            checkNotNull(BitmapFactory.decodeStream(it))
        }
        val scaled = Bitmap.createScaledBitmap(source, 1280, 1280, true)
        try {
            original.outputStream().use {
                assertTrue(scaled.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            if (source !== scaled) source.recycle()
            scaled.recycle()
        }
        val photos = (0 until 7).map { index ->
            val name = "pressure-$index.png"
            original.copyTo(File(folder, name))
            Attachment(name, "remote/$name", "image/png", original.length(),
                1280, 1280, 1, null, name)
        }
        // Собираем фрагмент на заданной высоте и отмечаем, относится ли он к альбому. Сцена
        // получает различимые соседние и видимые файлы.
        fun fragment(id: Long, attachments: List<Attachment>, y: Float, album: Boolean): SceneFragment {
            val generation = if (album) StudioGeneration(
                StudioGeneration.Stage.SUCCEEDED, "image", "1:1", null, null,
                slots = attachments.map {
                    StudioGeneration.Slot(it.id, StudioGeneration.Stage.SUCCEEDED, it.thumbnailKey, 100)
                },
            ) else null
            val row = Message(id, 1, OrderKey(id, id), MessageDirection.INCOMING, 0,
                MessageKind.PHOTO, "", "", null, 1, attachments, studioGeneration = generation)
            val prepared = PreparedText(row, TextLayoutKey(id, 1, 300, 17, 3))
            return SceneFragment(prepared, 0,
                android.graphics.RectF(0f, y, prepared.width, y + prepared.height))
        }
        val neighbors = (0 until 3).map {
            fragment((it + 1).toLong(), listOf(photos[it]), -1900f + it * 600, false)
        }
        // Читаем готовность в потоке экрана до предельного срока. Ускоренная проверка не
        // принимает неоконченный фоновый расчёт за готовность.
        fun waitFor(predicate: () -> Boolean): Boolean {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (System.nanoTime() < deadline) {
                var ready = false
                main { ready = predicate() }
                if (ready) return true
                Thread.sleep(5)
            }
            return false
        }
        try {
            main {
                media.remoteFile = { a, preview, _ ->
                    check(!preview)
                    File(h.root, "media/" + a.relativePath) to "image/png"
                }
                scene.mediaStore = media
                scene.viewport = android.graphics.RectF(0f, 0f, 390f, 650f)
                scene.imageViewport = scene.viewport
                scene.imagePreparationViewport = android.graphics.RectF(0f, -2300f, 390f, 800f)
                scene.apply(SceneSnapshot(scene.ticket, neighbors))
            }
            assertTrue("First prepare all three neighbors", waitFor {
                photos.take(3).all { a ->
                    media.cachedThumbnail(a, checkNotNull(media.studioPhotoKey(a)))?.let {
                        it.close(); true
                    } ?: false
                }
            })
            val visible = fragment(10, photos.takeLast(4), 0f, true)
            main { scene.apply(SceneSnapshot(scene.ticket, neighbors + visible)) }
            val ready = waitFor { (0 until 4).all { scene.imageReady(10, it) } }
            main {
                println("KANDINSKY_PREFETCH_PRESSURE ready=" + (0 until 4).map { scene.imageReady(10, it) } +
                    " bytes=${media.bytesUsed} peak=${media.peakBytesUsed}")
                assertTrue("All four visible originals must fit after speculative neighbors", ready)
                repeat(4) { index ->
                    val frame = checkNotNull(scene.preview(10, index))
                    try { assertEquals(1280, frame.bitmap?.width) } finally { frame.close() }
                }
                assertTrue(media.peakBytesUsed <= 48 * 1024 * 1024)
            }
            for (photo in photos.take(3)) {
                val frame = awaitResult<PreparedImage> { callback ->
                    main {
                        media.thumbnail(photo, checkNotNull(media.studioPhotoKey(photo)), 0,
                            localOnly = true, priority = 0) { _, result -> callback(result) }
                    }
                    Cancellation {}
                }
                frame.close()
            }
            main {
                for (photo in photos.takeLast(4)) {
                    val cached = media.cachedThumbnail(photo, checkNotNull(media.studioPhotoKey(photo)))
                    assertNotNull("A speculative decode must not drop a displayed cache owner", cached)
                    cached?.close()
                }
                media.withNavigationCache { scene.advanceGeneration(); media.releaseIdleImages() }
                scene.apply(SceneSnapshot(scene.ticket, listOf(visible)))
                assertEquals("All warmed album originals must bind immediately after returning",
                    listOf(true, true, true, true), (0 until 4).map { scene.imageReady(10, it) })
            }
        } finally {
            main { scene.close(); media.close() }
            h.close()
        }
    }

    // Удерживаем два сетевых чтения и отдельно запрашиваем местный чёткий кадр. Срок проверяет,
    // что он не встал за чужими передачами.
    @Test
    fun localSharpDecodeDoesNotWaitBehindUnrelatedNetworkReads() {
        val h = RepositoryHarness.open(File(context.cacheDir, "local-priority-" + UUID.randomUUID()))
        val media = MediaStore(h.store)
        val draft = stage(media) { TestFixtures.assets.open("media/" + photoName()) }
        val file = File(h.root, "media/" + draft.relativePath)
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val requests = mutableListOf<Cancellation>()
        try {
            main {
                media.remoteFile = { _, _, token ->
                    started.countDown()
                    token.onCancel { release.countDown() }
                    release.await(1, TimeUnit.SECONDS)
                    file to "image/jpeg"
                }
                repeat(2) { index ->
                    val a = Attachment("blocked-$index", "remote/blocked-$index.jpg", "image/jpeg",
                        file.length(), 1024, 1024, 1, null, "blocked-$index")
                    requests += media.thumbnail(a, ThumbnailKey(256), 0) { _, result ->
                        result.getOrNull()?.close()
                    }
                }
            }
            assertTrue("Both network reads must start before timing", started.await(3, TimeUnit.SECONDS))
            val begin = System.nanoTime()
            val image = awaitResult<PreparedImage> { callback ->
                main { media.thumbnail(attachment(draft), ThumbnailKey(256), 1, localOnly = true) {
                    _, result -> callback(result)
                } }
                Cancellation {}
            }
            val elapsed = (System.nanoTime() - begin) / 1_000_000.0
            image.close()
            println("KANDINSKY_LOCAL_BEHIND_NETWORK milliseconds=$elapsed")
            assertTrue("Local pixels waited $elapsed ms behind network", elapsed < 150)
        } finally {
            release.countDown()
            main { requests.forEach { it.cancel() }; media.close() }
            h.close()
        }
    }

    // Даём повреждённый оригинал и ждём отказ чтения. Промежуточное изображение после ошибки не
    // должно занимать память.
    @Test
    fun corruptOriginalReleasesItsDecodeMemory() {
        checkRejectedOriginal(pressureDuringFetch = false)
    }

    // Во время получения большого исходника уменьшаем предел памяти. Следующее чтение должно
    // отказать до выделения карты точек.
    @Test
    fun memoryWarningDuringFetchRejectsAnOversizedDecodeWithoutAllocatingPixels() {
        checkRejectedOriginal(pressureDuringFetch = true)
    }

    // Удерживаем получение файла и по сценарию меняем доступную память. После выпуска проверяем
    // точную причину отказа и отсутствие занятого рисунка.
    private fun checkRejectedOriginal(pressureDuringFetch: Boolean) {
        val h = RepositoryHarness.open(File(context.cacheDir, "rejected-original-" + UUID.randomUUID()))
        val media = MediaStore(h.store, studioOriginals = true)
        val file = File(h.root, "original.jpg")
        if (pressureDuringFetch) {
            TestFixtures.assets.open("media/" + photoName()).use { source ->
                file.outputStream().use { source.copyTo(it) }
            }
        } else file.writeText("Invalid image data")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(if (pressureDuringFetch) 1 else 0)
        val finished = CountDownLatch(1)
        var failure: Throwable? = null
        val edge = if (pressureDuringFetch) 2048 else 1024
        val photo = Attachment(
            "rejected", "remote/rejected.jpg", "image/jpeg", file.length(),
            edge, edge, 1, null, "rejected",
        )
        try {
            main {
                media.remoteFile = { _, _, _ ->
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    file to "image/jpeg"
                }
                media.thumbnail(photo, ThumbnailKey(edge, studioOriginal = !pressureDuringFetch), 1) { _, result ->
                    failure = result.exceptionOrNull()
                    result.getOrNull()?.close()
                    finished.countDown()
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            if (pressureDuringFetch) main { media.trimMemory() }
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertEquals(
                if (pressureDuringFetch) MediaError.UNAVAILABLE else MediaError.CORRUPT,
                (failure as? MediaException)?.reason,
            )
            main { assertEquals(0, media.bytesUsed) }
        } finally {
            release.countDown()
            main { media.close() }
            h.close()
        }
    }

    // Показываем ход генерации и готовый ответ с ещё не прочитанным фото. Тот же индикатор
    // остаётся до чёткого кадра и снимается после него.
    @Test
    fun studioPhotoKeepsTheSameIndicatorUntilPixelsArePresented() {
        val h =
            RepositoryHarness.open(File(context.cacheDir, "studio-delivery-" + UUID.randomUUID()))
        val media = MediaStore(h.store, studioOriginals = true)
        val file = File(h.root, "studio-delivery.jpg")
        TestFixtures.assets.open("media/" + photoName()).use { source ->
            file.outputStream().use { source.copyTo(it) }
        }
        val gate = CountDownLatch(1)
        val displayed = CountDownLatch(1)
        val scene = RetainedChatScene()
        val photo =
            Attachment(
                "net-1-result-first",
                "remote/first.jpg",
                "image/jpeg",
                file.length(),
                1024,
                1024,
                1,
                null,
                "first",
            )
        // Строим две стадии одной карточки по признаку готовности. Рамка и номер ячейки остаются
        // общими, чтобы сравнить её индикатор.
        fun snapshot(ready: Boolean): SceneSnapshot {
            val cells =
                listOf(
                    StudioGeneration.Slot(
                        "first",
                        if (ready) StudioGeneration.Stage.SUCCEEDED
                        else StudioGeneration.Stage.RUNNING,
                        if (ready) "first" else null,
                        if (ready) 100 else 94,
                    ),
                    StudioGeneration.Slot("second", StudioGeneration.Stage.QUEUED, null, null),
                )
            val generation =
                StudioGeneration(
                    StudioGeneration.Stage.RUNNING,
                    "image",
                    "1:1",
                    null,
                    null,
                    slots = cells,
                )
            val row =
                Message(
                    1,
                    2,
                    OrderKey(1, 1),
                    MessageDirection.INCOMING,
                    0,
                    if (ready) MessageKind.PHOTO else MessageKind.TEXT,
                    "",
                    "",
                    null,
                    if (ready) 2 else 1,
                    if (ready) listOf(photo) else emptyList(),
                    studioGeneration = generation,
                )
            val prepared = PreparedText(row, TextLayoutKey(1, row.revision, 300, 17, 1))
            return SceneSnapshot(
                scene.ticket,
                listOf(
                    SceneFragment(
                        prepared,
                        0,
                        android.graphics.RectF(0f, 0f, prepared.width, prepared.height),
                    )
                ),
            )
        }
        lateinit var before: SceneSnapshot
        lateinit var after: SceneSnapshot
        lateinit var indicator: StudioProgressView
        try {
            main {
                val container = android.widget.FrameLayout(context)
                media.remoteFile = { _, preview, _ ->
                    check(!preview && gate.await(8, TimeUnit.SECONDS))
                    file to "image/jpeg"
                }
                scene.mediaStore = media
                scene.movingContainer = container
                scene.viewport = android.graphics.RectF(0f, 0f, 400f, 650f)
                scene.onImageReady = { if (scene.imageReady(1)) displayed.countDown() }
                before = snapshot(false)
                after = snapshot(true)
                scene.apply(before)
                indicator = container.getChildAt(0) as StudioProgressView
                scene.apply(after)
                assertSame(indicator, container.getChildAt(0))
                assertFalse(scene.imageReady(1))
            }
            gate.countDown()
            assertTrue("Actual photo pixels are presented", displayed.await(6, TimeUnit.SECONDS))
            main {
                scene.apply(after)
                assertNull(indicator.parent)
            }
        } finally {
            gate.countDown()
            main {
                scene.close()
                media.close()
            }
            h.close()
        }
    }

    // Открываем новый чат с четырьмя фото, пока старый альбом ещё держит свои кадры. После
    // очистки памяти повторяем и сверяем детализацию и предел.
    @Test
    fun fourPhotoAlbumsCanOverlapDuringChatNavigation() {
        val h =
            RepositoryHarness.open(File(context.cacheDir, "album-navigation-" + UUID.randomUUID()))
        val media = MediaStore(h.store, studioOriginals = true)
        val original = File(h.root, "album.png")
        val source = TestFixtures.assets.open("media/" + photoName()).use {
            checkNotNull(BitmapFactory.decodeStream(it))
        }
        val pixels = Bitmap.createScaledBitmap(source, 1024, 1024, true)
        try {
            original.outputStream().use {
                assertTrue(pixels.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            pixels.recycle()
            if (source !== pixels) source.recycle()
        }
        val gate = CountDownLatch(1)
        val old = RetainedChatScene()
        val next = RetainedChatScene()
        val reopened = RetainedChatScene()
        val afterPressure = RetainedChatScene()
        // Создаём четыре вложения с отдельными номерами для указанной сцены. Старые и новые
        // рисунки можно различить во время перекрытия.
        fun snapshot(scene: RetainedChatScene, id: Long, prefix: String): SceneSnapshot {
            val photos =
                (0 until 4).map { index ->
                    val key = "$prefix-$index"
                    Attachment(
                        key,
                        "remote/$key.png",
                        "image/png",
                        original.length(),
                        1024,
                        1024,
                        1,
                        null,
                        key,
                    )
                }
            val generation =
                StudioGeneration(
                    StudioGeneration.Stage.SUCCEEDED,
                    "image",
                    "1:1",
                    null,
                    null,
                    slots =
                        photos.map {
                            StudioGeneration.Slot(
                                it.id,
                                StudioGeneration.Stage.SUCCEEDED,
                                it.thumbnailKey,
                                100,
                            )
                        },
                )
            val row =
                Message(
                    id,
                    id,
                    OrderKey(id, id),
                    MessageDirection.INCOMING,
                    0,
                    MessageKind.PHOTO,
                    "",
                    "",
                    null,
                    1,
                    photos,
                    studioGeneration = generation,
                )
            val prepared = PreparedText(row, TextLayoutKey(id, 1, 300, 17, 3))
            return SceneSnapshot(
                scene.ticket,
                listOf(
                    SceneFragment(
                        prepared,
                        0,
                        android.graphics.RectF(0f, 0f, prepared.width, prepared.height),
                    )
                ),
            )
        }
        val oldReady = CountDownLatch(1)
        val nextReady = CountDownLatch(1)
        val pressureReady = CountDownLatch(1)
        // Читаем готовность каждой из четырёх ячеек по очереди. Это обнаружит отсутствие
        // отдельного кадра даже у видимого альбома.
        fun ready(scene: RetainedChatScene, id: Long) = (0 until 4).map { scene.imageReady(id, it) }
        try {
            main {
                media.remoteFile = { attachment, preview, _ ->
                    check(!preview)
                    if (attachment.id.startsWith("new")) check(gate.await(8, TimeUnit.SECONDS))
                    original to "image/png"
                }
                for (scene in listOf(old, next)) {
                    scene.mediaStore = media
                    scene.viewport = android.graphics.RectF(0f, 0f, 390f, 650f)
                }
                old.onImageReady = { if (ready(old, 1).all { it }) oldReady.countDown() }
                next.onImageReady = { if (ready(next, 2).all { it }) nextReady.countDown() }
                old.apply(snapshot(old, 1, "old"))
            }
            assertTrue(
                "The outgoing album has four displayed photos",
                oldReady.await(5, TimeUnit.SECONDS),
            )
            main { next.apply(snapshot(next, 2, "new")) }
            gate.countDown()
            assertTrue(
                "The incoming album has four displayed photos while the old chat retains its frames",
                nextReady.await(5, TimeUnit.SECONDS),
            )
            main {
                assertTrue(next.loadActions().isEmpty())
                // Берём кадры каждой ячейки и сверяем размер оригинального рисунка. Копии после
                // сравнения закрываются и не влияют на следующий замер.
                fun assertOriginalDetail(scene: RetainedChatScene, id: Long) {
                    repeat(4) { index ->
                        val frame = checkNotNull(scene.preview(id, index))
                        try {
                            assertEquals(
                                "Album photo $index must retain original detail",
                                1024,
                                frame.bitmap?.width,
                            )
                            assertEquals(1024, frame.bitmap?.height)
                        } finally {
                            frame.close()
                        }
                    }
                }
                assertOriginalDetail(old, 1)
                assertOriginalDetail(next, 2)
                next.apply(snapshot(next, 2, "new"))
                assertOriginalDetail(next, 2)
                reopened.mediaStore = media
                reopened.viewport = android.graphics.RectF(0f, 0f, 390f, 650f)
                reopened.apply(snapshot(reopened, 2, "new"))
                assertTrue(ready(reopened, 2).all { it })
                assertOriginalDetail(reopened, 2)
                assertTrue(media.peakBytesUsed <= 48 * 1024 * 1024)
                old.close()
                next.close()
                reopened.close()
                media.trimMemory()
                afterPressure.mediaStore = media
                afterPressure.viewport = android.graphics.RectF(0f, 0f, 390f, 650f)
                afterPressure.onImageReady = {
                    if (ready(afterPressure, 2).all { it }) pressureReady.countDown()
                }
                afterPressure.apply(snapshot(afterPressure, 2, "new"))
            }
            assertTrue(
                "Four originals reload after memory cleanup",
                pressureReady.await(5, TimeUnit.SECONDS),
            )
            main {
                repeat(4) { index ->
                    val frame = checkNotNull(afterPressure.preview(2, index))
                    try {
                        assertEquals(1024, frame.bitmap?.width)
                        assertEquals(1024, frame.bitmap?.height)
                    } finally {
                        frame.close()
                    }
                }
                assertTrue(media.bytesUsed <= 24 * 1024 * 1024)
            }
        } finally {
            gate.countDown()
            main {
                old.close()
                next.close()
                reopened.close()
                afterPressure.close()
                media.close()
            }
            h.close()
        }
    }

    // Проверяем обычный предел и возможность занять четыре места для видимой карточки
    // результатов. Пятое место уже недоступно.
    @Test
    fun visibleStudioAlbumCanReserveFourVideoPreviews() {
        main {
            val leases = mutableListOf<PlaybackLease>()
            try {
                repeat(2) { leases += checkNotNull(MediaAdmission.reservePreview {}) }
                assertNull(MediaAdmission.reservePreview {})
                repeat(2) {
                    leases += checkNotNull(MediaAdmission.reservePreview(studioAlbum = true) {})
                }
                assertEquals(4, MediaAdmission.previewOwnerCount)
                assertNull(MediaAdmission.reservePreview(studioAlbum = true) {})
            } finally {
                leases.forEach { it.release() }
            }
        }
    }

    // Сохраняем выбранный файл и попытку отправки, затем проверяем повреждённое намерение. Живые
    // ссылки сохраняются, а восстановление убирает только осиротевшие файлы.
    @Test
    fun selectionIntentProtectsOriginalsAndCorruptionStopsRecoveryBeforeDeletion() {
        val h =
            RepositoryHarness.open(
                File(context.cacheDir, "selection-ownership-" + UUID.randomUUID())
            )
        val media = MediaStore(h.store)
        // Создаём выбранное вложение через штатную подготовку в отдельном каталоге. Это даёт
        // реальную ссылку для проверки восстановления и очистки.
        fun selection() =
            awaitResult<AttachmentDraft> { cb ->
                main {
                    media.stageSelection(
                        { TestFixtures.assets.open("media/" + photoName()) },
                        "image/jpeg",
                        cb,
                    )
                }
                Cancellation {}
            }
        val selected = selection()
        val orphan = selection()
        val camera = stage(media)
        val command =
            SendCommand(
                UUID.randomUUID().toString(),
                2,
                MessageKind.PHOTO,
                attachments = listOf(selected),
            )
        try {
            awaitResult<Unit> {
                h.repo.saveDraft(
                    2,
                    Draft(text = "Текущий", revision = 5, pendingMediaBatch = listOf(command)),
                    it,
                )
            }
            awaitResult<Unit> { h.repo.saveDraft(2, Draft(text = "Устаревший", revision = 4), it) }
            val current = awaitResult<Draft> { h.repo.draft(2, it) }
            assertEquals("Текущий", current.text)
            assertEquals(listOf(command), current.pendingMediaBatch)
            val invalid = command.copy(attachments = emptyList())
            val error =
                runCatching {
                        awaitResult<Unit> {
                            h.repo.saveDraft(
                                2,
                                Draft(
                                    text = "Недопустимый",
                                    revision = 6,
                                    pendingMediaBatch = listOf(invalid),
                                ),
                                it,
                            )
                        }
                    }
                    .exceptionOrNull()
            assertEquals(RepoError.INVALID_INPUT, (error as? RepoException)?.reason)
            assertEquals(current, awaitResult<Draft> { h.repo.draft(2, it) })
            val payload =
                awaitResult<String> { cb ->
                    h.store.submit(
                        { db ->
                            val payload =
                                db.rawQuery(
                                        "SELECT payload FROM media_selection_intents WHERE chatID=2",
                                        null,
                                    )
                                    .use {
                                        it.moveToFirst()
                                        it.getString(0)
                                    }
                            db.execSQL(
                                "UPDATE media_selection_intents SET payload='{' WHERE chatID=2"
                            )
                            payload
                        },
                        cb,
                    )
                }
            assertTrue(
                "Malformed intent cannot authorize a deletion sweep",
                runCatching { recover(media) }.isFailure,
            )
            for (asset in listOf(selected, orphan, camera)) assertTrue(
                media.fileURL(asset.relativePath).isFile
            )
            awaitResult<Unit> { cb ->
                h.store.submit(
                    { db ->
                        db.execSQL(
                            "UPDATE media_selection_intents SET payload=? WHERE chatID=2",
                            arrayOf(payload),
                        )
                        Unit
                    },
                    cb,
                )
            }
            awaitResult<Unit> { cb ->
                main { media.discardUnpublishedImports(listOf(selected, orphan, camera), cb) }
                Cancellation {}
            }
            assertTrue(media.fileURL(selected.relativePath).isFile)
            for (asset in listOf(orphan, camera)) assertFalse(
                media.fileURL(asset.relativePath).exists()
            )
            recover(media)
            assertTrue(media.fileURL(selected.relativePath).isFile)
            awaitResult<Unit> { h.repo.saveDraft(2, Draft(revision = 6), it) }
            recover(media)
            assertFalse(media.fileURL(selected.relativePath).exists())
        } finally {
            main { media.close() }
            h.close()
        }
    }

    private val i = InstrumentationRegistry.getInstrumentation()
    private val context = i.targetContext

    // Выполняем команду хранилища в потоке экрана. Проверка дожидается окончания команды перед
    // чтением состояния.
    private fun main(action: () -> Unit) = i.runOnMainSync(action)

    // Находим имя настоящего фото в описании материалов. Подготовка использует тот же исходник во
    // всех сценариях.
    private fun photoName(): String {
        val a =
            JSONObject(
                    TestFixtures.assets.open("manifest.json").bufferedReader().use { it.readText() }
                )
                .getJSONArray("files")
        return (0 until a.length())
            .map { a.getJSONObject(it) }
            .first { it.getString("mime") == "image/jpeg" }
            .getString("fileName")
    }

    // Просим обработать выбранное фото и ждём результат фоновой подготовки. Можно передать другой
    // источник, чтобы проверить ошибку чтения.
    private fun stage(
        media: MediaStore,
        open: () -> java.io.InputStream = { TestFixtures.assets.open("media/" + photoName()) },
    ): AttachmentDraft = awaitResult { cb ->
        main { media.stage(open, "image/jpeg", cb) }
        Cancellation {}
    }

    // Запускаем восстановление временного хранилища и дожидаемся ответа. Только затем проверяем
    // существование файлов.
    private fun recover(media: MediaStore) {
        awaitResult<Unit> { cb ->
            main { media.recover(cb) }
            Cancellation {}
        }
    }

    // Отправляем одно подготовленное фото с заданным номером команды. Тот же номер позволяет
    // проверить повтор после потери ответа.
    private fun sendPhoto(repo: ChatRepository, draft: AttachmentDraft, token: String): MessageDelta =
        awaitResult { cb ->
            repo.send(SendCommand(token, 2, MessageKind.PHOTO, attachments = listOf(draft)), cb)
        }

    // Собираем вложение из сведений черновика без нового обращения к файлу. Связь с исходником и
    // поворот сохраняются.
    private fun attachment(d: AttachmentDraft) =
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

    // После появления чёткого фото даём маленький рисунок того же результата. Виден должен
    // остаться прежний чёткий кадр.
    @Test
    fun transferredPhotoIsNotReplacedByALateMicroPreview() {
        val h = RepositoryHarness.open(File(context.cacheDir, "preview-order-" + UUID.randomUUID()))
        val media = MediaStore(h.store)
        val draft = stage(media)
        val a = attachment(draft).copy(tinyPreview = checkNotNull(draft.tinyPreview))
        val full =
            awaitResult<PreparedImage> { cb ->
                main { media.thumbnail(a, ThumbnailKey(1024), 0) { _, value -> cb(value) } }
                Cancellation {}
            }
        val expected = checkNotNull(full.bitmap)
        assertTrue(maxOf(expected.width, expected.height) > 64)
        val held = CountDownLatch(1)
        val file = media.fileURL(a.relativePath)
        lateinit var slot: MediaImageSlot
        try {
            main {
                media.releaseIdleImages()
                media.remoteFile = { _, _, token ->
                    token.onCancel { held.countDown() }
                    held.await(5, TimeUnit.SECONDS)
                    file to "image/jpeg"
                }
                slot = MediaImageSlot(media) {}
                slot.bind(
                    a,
                    android.graphics.RectF(0f, 0f, 260f, 200f),
                    maxPixel = 1024,
                    showControls = false,
                )
                slot.seed(checkNotNull(full.retained()))
            }
            val barrier =
                awaitResult<PreparedImage> { cb ->
                    main { media.tinyPreview(a, cb) }
                    Cancellation {}
                }
            main {
                barrier.close()
                val shown = checkNotNull(slot.preview())
                try {
                    assertSame(
                        "A late tiny JPEG must not downgrade the transferred photo",
                        expected,
                        shown.bitmap,
                    )
                } finally {
                    shown.close()
                }
            }
        } finally {
            held.countDown()
            main {
                slot.close()
                full.close()
                media.close()
            }
            h.close()
        }
    }

    // Отменяем ожидающий ручной запуск при ещё занятом месте предпросмотра. Разрешение камере
    // выдаётся только после реального закрытия прежнего владельца.
    @Test
    fun cancelledManualReservationAndCaptureStillWaitForRetiringNativePreview() {
        val first = MediaAdmission()
        val replacement = MediaAdmission()
        lateinit var lifetime: PreviewLifetime
        main {
            val lease = checkNotNull(MediaAdmission.reservePreview { lifetime.retire() })
            lifetime = PreviewLifetime(lease)
            lifetime.begin()
        }
        try {
            val cancelled = CountDownLatch(1)
            val replies = AtomicInteger()
            lateinit var request: Cancellation
            main {
                request =
                    first.requestPlayback {
                        assertTrue(it.isFailure)
                        replies.incrementAndGet()
                        cancelled.countDown()
                    }
                assertEquals(1, MediaAdmission.previewOwnerCount)
                assertNull(MediaAdmission.reservePreview {})
            }
            main { request.cancel() }
            assertTrue(cancelled.await(2, TimeUnit.SECONDS))
            assertEquals(1, replies.get())
            val granted = CountDownLatch(1)
            var capture: CaptureLease? = null
            main {
                replacement.acquire(CaptureKind.PHOTO) {
                    assertTrue(it.isSuccess)
                    capture = it.getOrThrow()
                    granted.countDown()
                }
            }
            assertFalse(
                "Capture cannot overtake a retiring native preview",
                granted.await(100, TimeUnit.MILLISECONDS),
            )
            main {
                assertNull(capture)
                assertEquals(1, MediaAdmission.previewOwnerCount)
                lifetime.end()
            }
            assertTrue(granted.await(2, TimeUnit.SECONDS))
            main {
                assertEquals(0, MediaAdmission.previewOwnerCount)
                capture!!.release()
            }
        } finally {
            main {
                lifetime.retire()
                first.close()
                replacement.close()
            }
        }
    }

    // Готовим маленький кадр для ленты и открываем полноэкранный просмотр. Сверяем порядок
    // запросов и большой результат, который должен прийти из оригинала.
    @Test
    fun viewerDecodeUsesOriginalAndDoesNotReuseTimelinePreview() {
        val h =
            RepositoryHarness.open(File(context.cacheDir, "viewer-original-" + UUID.randomUUID()))
        val media = MediaStore(h.store)
        // Пишем картинку с заранее заданными размерами во временный каталог. Разные размеры
        // помогают отличить оригинал от кадра ленты.
        fun imageFile(name: String, w: Int, height: Int): File {
            val f = File(h.root, name)
            val b = Bitmap.createBitmap(w, height, Bitmap.Config.ARGB_8888)
            b.eraseColor(android.graphics.Color.RED)
            f.outputStream().use { assertTrue(b.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            b.recycle()
            return f
        }
        val original = imageFile("original.jpg", 2048, 1536)
        val preview = imageFile("preview.jpg", 256, 192)
        val modes = Collections.synchronizedList(mutableListOf<Boolean>())
        val a =
            Attachment(
                "viewer-original",
                "remote/viewer-original.jpg",
                "image/jpeg",
                100,
                2048,
                1536,
                1,
                null,
                "viewer-original",
            )
        try {
            main {
                media.remoteFile = { _, isPreview, _ ->
                    modes.add(isPreview)
                    (if (isPreview) preview else original) to "image/jpeg"
                }
            }
            val small =
                awaitResult<PreparedImage> { cb ->
                    main { media.thumbnail(a, ThumbnailKey(1024), 0) { _, value -> cb(value) } }
                    Cancellation {}
                }
            assertEquals(256, small.bitmap!!.width)
            main { small.close() }
            val full =
                awaitResult<PreparedImage> { cb ->
                    main { media.thumbnail(a, ThumbnailKey(2048), 1) { _, value -> cb(value) } }
                    Cancellation {}
                }
            try {
                assertEquals(
                    "The viewer needs the original instead of the timeline cache",
                    2048,
                    full.bitmap!!.width,
                )
                assertEquals(listOf(true, false), modes.toList())
                assertTrue(media.peakBytesUsed <= 48 * 1024 * 1024)
            } finally {
                main { full.close() }
            }
        } finally {
            main { media.close() }
            h.close()
        }
    }

    // Показываем оригинальное фото в ленте и галерее, затем возвращаемся. Проверяем прежнюю
    // детализацию и ограниченный расход памяти.
    @Test
    fun studioPhotoTimelineAndGalleryReuseOriginalPixels() {
        val h =
            RepositoryHarness.open(File(context.cacheDir, "studio-original-" + UUID.randomUUID()))
        val media = MediaStore(h.store, studioOriginals = true)
        // Создаём подготовленный файл известного размера. Его ширина и высота служат исходными
        // значениями для сверки рисунка.
        fun fixture(name: String, width: Int, height: Int): File {
            val file = File(h.root, name)
            val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                file.outputStream().use {
                    assertTrue(image.compress(Bitmap.CompressFormat.JPEG, 95, it))
                }
            } finally {
                image.recycle()
            }
            return file
        }
        val original = fixture("studio-full.jpg", 1280, 768)
        val preview = fixture("studio-small.jpg", 256, 154)
        val photo =
            Attachment(
                "studio",
                "remote/studio.jpg",
                "image/jpeg",
                200_000,
                1280,
                768,
                1,
                null,
                "studio",
            )
        val requests = Collections.synchronizedList(mutableListOf<Boolean>())
        val displayed = CountDownLatch(1)
        lateinit var timeline: MediaImageSlot
        lateinit var gallery: MediaImageSlot
        lateinit var restored: MediaImageSlot
        val restoredReady = CountDownLatch(1)
        val cell = android.graphics.RectF(0f, 0f, 350f, 210f)
        main {
            media.remoteFile = { _, small, _ ->
                requests.add(small)
                (if (small) preview else original) to "image/jpeg"
            }
            timeline =
                MediaImageSlot(media) {
                    if (timeline.ready && !timeline.loading) displayed.countDown()
                }
            gallery = MediaImageSlot(media) {}
            restored = MediaImageSlot(media) {
                if (restored.ready && !restored.loading) restoredReady.countDown()
            }
            timeline.bind(
                photo,
                cell,
                maxPixel = 512,
                kind = dev.kirill.kandinsky.chat.domain.MessageKind.PHOTO,
            )
        }
        try {
            assertTrue(displayed.await(5, TimeUnit.SECONDS))
            main {
                val first = checkNotNull(timeline.preview())
                try {
                    assertEquals(1280, first.bitmap!!.width)
                    assertEquals(768, first.bitmap!!.height)
                    gallery.bind(photo, cell, maxPixel = 384, showControls = false)
                    assertTrue(gallery.ready)
                    val reopened = checkNotNull(gallery.preview())
                    try {
                        assertSame(first.bitmap, reopened.bitmap)
                    } finally {
                        reopened.close()
                    }
                    assertEquals(listOf(false), requests.toList())
                    assertTrue(media.peakBytesUsed <= 48 * 1024 * 1024)
                } finally {
                    first.close()
                }
                gallery.close()
                timeline.close()
                media.trimMemory()
                restored.bind(photo, cell, maxPixel = 512, kind = MessageKind.PHOTO)
            }
            assertTrue(restoredReady.await(5, TimeUnit.SECONDS))
            main {
                val frame = checkNotNull(restored.preview())
                try {
                    assertEquals(1280, frame.bitmap?.width)
                    assertEquals(768, frame.bitmap?.height)
                    assertTrue(media.bytesUsed <= 24 * 1024 * 1024)
                } finally {
                    frame.close()
                }
            }
        } finally {
            main {
                gallery.close()
                timeline.close()
                restored.close()
                media.close()
            }
            h.close()
        }
    }

    // Сохраняем маленький кадр вместе с сообщением и повторяем старую команду с неполными
    // сведениями. Номер сообщения сохраняется, а кнопка передачи правильно исчезает после чёткого
    // кадра.
    @Test
    fun metadataSurvivesStorageLegacyRetryAndBlockedThumbnail() {
        val h = RepositoryHarness.open(File(context.cacheDir, "metadata-" + UUID.randomUUID()))
        val media = MediaStore(h.store)
        val draft = stage(media)
        val tiny = checkNotNull(draft.tinyPreview)
        assertTrue(tiny.length <= 4096)
        val bytes = android.util.Base64.decode(tiny, android.util.Base64.NO_WRAP)
        val image = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        assertTrue(maxOf(image.width, image.height) <= 64)
        image.recycle()
        val first = sendPhoto(h.repo, draft, "preview-retry")
        val saved = first.inserted.first().attachments.first()
        assertEquals(tiny, saved.tinyPreview)
        val second = sendPhoto(h.repo, draft.copy(tinyPreview = null, waveform = null), "preview-retry")
        assertEquals(first.inserted.first().id, second.inserted.first().id)
        val release = CountDownLatch(1)
        val ready = CountDownLatch(1)
        val slot = AtomicReference<MediaImageSlot>()
        main {
            media.remoteFile = { _, _, token ->
                token.onCancel { release.countDown() }
                release.await(5, TimeUnit.SECONDS)
                throw MediaException(MediaError.CANCELLED)
            }
            val s = MediaImageSlot(media) { if (slot.get()?.ready == true) ready.countDown() }
            slot.set(s)
            s.bind(saved, android.graphics.RectF(0f, 0f, 200f, 140f))
        }
        assertTrue("Tiny preview must bypass blocked I/O", ready.await(3, TimeUnit.SECONDS))
        main {
            val s = slot.get()
            assertTrue(s.loading)
            assertTrue(s.performLoadAction(100f, 70f))
            assertFalse(s.loading)
            assertEquals("Повторить загрузку", s.loadAction()?.second)
            media.remoteFile = null
            assertTrue(s.performLoadAction(100f, 70f))
        }
        val end = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < end) {
            var loading = true
            main { loading = slot.get().loading }
            if (!loading) break
            Thread.sleep(10)
        }
        main {
            val s = slot.get()
            assertFalse(s.loading)
            assertNull(s.loadAction())
            val full = s.preview()
            assertTrue((full?.bitmap?.width ?: 0) > 64)
            full?.close()
            s.close()
        }
        release.countDown()
        main { media.close() }
        h.close()
    }

    // Удерживаем место воспроизведения при запросе камеры. Выдаём его только после подтверждения
    // остановки. Поздний ответ закрытому владельцу возвращает одну отмену.
    @Test
    fun cancellingCameraAfterReservationReturnsTheSlotToAnotherScreen() {
        val first = MediaAdmission()
        val second = MediaAdmission()
        val abandoned = CompletableFuture<Result<CaptureLease>>()
        val competing = CompletableFuture<Result<CaptureLease>>()
        val next = CompletableFuture<Result<CaptureLease>>()
        val reservedBeforeCancellation = AtomicInteger()
        try {
            main {
                val requested = first.acquire(CaptureKind.PHOTO) { abandoned.complete(it) }
                // Подтверждение остановки уже в очереди. Закрываем экран до ответа о выдаче камеры.
                Handler(Looper.getMainLooper()).post {
                    reservedBeforeCancellation.set(first.captureActiveCount)
                    val queuedRejection = second.acquire(CaptureKind.PHOTO) { competing.complete(it) }
                    queuedRejection.cancel()
                    requested.cancel()
                }
            }
            val abandonedError = abandoned.get(5, TimeUnit.SECONDS).exceptionOrNull()
            val competingError = competing.get(5, TimeUnit.SECONDS).exceptionOrNull()
            assertEquals(1, reservedBeforeCancellation.get())
            assertEquals(MediaError.CANCELLED, (abandonedError as? MediaException)?.reason)
            assertEquals(MediaError.CANCELLED, (competingError as? MediaException)?.reason)
            main {
                assertEquals(0, first.captureActiveCount)
                second.acquire(CaptureKind.PHOTO) { next.complete(it) }
            }
            val lease = next.get(5, TimeUnit.SECONDS).getOrThrow()
            main {
                assertEquals(1, second.captureActiveCount)
                lease.release()
                assertEquals(0, second.captureActiveCount)
            }
        } finally {
            main {
                first.close()
                second.close()
            }
        }
    }

    @Test
    fun captureAdmissionWaitsForProvenReleaseAndRejectsLateGrant() {
        val admission = MediaAdmission()
        val stopped = AtomicReference<() -> Unit>()
        main { admission.installPlaybackStopper { stopped.set(it) } }
        val playback =
            awaitResult<PlaybackLease> { cb ->
                main { admission.requestPlayback(cb) }
                Cancellation {}
            }
        val capture = AtomicReference<CaptureLease>()
        val granted = CountDownLatch(1)
        main {
            admission.acquire(CaptureKind.PHOTO) {
                capture.set(it.getOrThrow())
                granted.countDown()
            }
        }
        assertTrue(
            runCatching {
                    awaitResult<CaptureLease> { cb ->
                        main { admission.acquire(CaptureKind.PHOTO, cb) }
                        Cancellation {}
                    }
                }
                .isFailure
        )
        main { stopped.get().invoke() }
        assertNull(capture.get())
        assertEquals(1, admission.playbackActiveCount)
        main { playback.release() }
        assertTrue(granted.await(2, TimeUnit.SECONDS))
        assertEquals(1, admission.captureActiveCount)
        main {
            capture.get().release()
            admission.close()
        }
        val late = MediaAdmission()
        val lateStop = AtomicReference<() -> Unit>()
        main { late.installPlaybackStopper { lateStop.set(it) } }
        val owner =
            awaitResult<PlaybackLease> { cb ->
                main { late.requestPlayback(cb) }
                Cancellation {}
            }
        val cancelled = CountDownLatch(1)
        val replies = AtomicInteger()
        main {
            late.acquire(CaptureKind.PHOTO) {
                assertTrue(it.isFailure)
                replies.incrementAndGet()
                cancelled.countDown()
            }
            late.close()
            lateStop.get().invoke()
            owner.release()
        }
        assertTrue(cancelled.await(2, TimeUnit.SECONDS))
        assertEquals(1, replies.get())
        assertEquals(0, late.captureActiveCount)
    }

    // Имитируем ошибку записи и потерю ответа после сохранения. Оригинал со ссылкой остаётся,
    // повтор не удваивает сообщение, а база проходит проверку целостности.
    @Test
    fun crashRecoveryDiskFullAndLostReplyPreserveReferencedOriginal() {
        val h = RepositoryHarness.open(File(context.cacheDir, "media-" + UUID.randomUUID()))
        val media = MediaStore(h.store)
        val original = stage(media)
        val first = sendPhoto(h.repo, original, "media-one")
        assertEquals(original.relativePath, first.inserted.first().attachments.first().relativePath)
        for (fault in listOf(RepoFault.BEFORE_RENAME, RepoFault.AFTER_RENAME)) {
            h.faults.arm(fault)
            assertTrue(runCatching { stage(media) }.isFailure)
            recover(media)
            assertTrue(File(h.root, "media/" + original.relativePath).isFile)
            assertEquals(1, File(h.root, "media/objects").listFiles()!!.size)
        }
        val full = stage(media)
        h.faults.arm(RepoFault.STORAGE_FULL)
        assertTrue(runCatching { sendPhoto(h.repo, full, "media-full") }.isFailure)
        recover(media)
        assertFalse(File(h.root, "media/" + full.relativePath).exists())
        val lost = stage(media)
        h.faults.arm(RepoFault.AFTER_COMMIT_BEFORE_REPLY)
        assertTrue(runCatching { sendPhoto(h.repo, lost, "media-lost") }.isFailure)
        recover(media)
        assertTrue(sendPhoto(h.repo, lost, "media-lost").inserted.isNotEmpty())
        assertEquals(2, h.messageCount(2))
        assertEquals("ok", h.integrity())
        main { media.close() }
        h.close()
    }

    // Читаем уменьшенное настоящее фото и отдельно проверяем повреждение, запрет доступа и старый
    // кадр. Ошибки различаются, а непринятый рисунок освобождается.
    @Test
    fun realImageDownsampleCorruptionPermissionAndStaleMailbox() {
        val h = RepositoryHarness.open(File(context.cacheDir, "media-" + UUID.randomUUID()))
        val media = MediaStore(h.store)
        val large = File(h.root, "huge.jpg")
        val bitmap = Bitmap.createBitmap(4032, 3024, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.RED)
        large.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        bitmap.recycle()
        val draft = stage(media) { large.inputStream() }
        val decoded =
            awaitResult<PreparedImage> { cb ->
                main {
                    media.thumbnail(attachment(draft), ThumbnailKey(1024), 42) { ticket, result ->
                        assertEquals(42, ticket)
                        cb(result)
                    }
                }
                Cancellation {}
            }
        val image = decoded.bitmap!!
        assertTrue(maxOf(image.width, image.height) <= 1024)
        assertEquals(1, decoded.orientation)
        assertNotNull(
            PhotoPresentation.filling(
                image.width,
                image.height,
                1,
                200.0,
                200.0,
            )
        )
        main {
            val mailbox =
                MediaSurfaceMailbox<PreparedImage>(
                    schedule = { android.os.Handler(android.os.Looper.getMainLooper()).post(it) },
                    render = {},
                )
            val binding = mailbox.begin()!!
            mailbox.clear()
            assertFalse(mailbox.offer(binding, 1, decoded))
            decoded.close()
            assertEquals(1, decoded.closeCount)
            mailbox.close()
            mailbox.flush()
        }
        assertTrue(runCatching { stage(media) { "not an image".byteInputStream() } }.isFailure)
        val permission =
            runCatching { stage(media) { throw SecurityException("test inaccessible source") } }
                .exceptionOrNull()
        assertTrue(
            permission is MediaException && permission.reason == MediaError.PERMISSION_DENIED
        )
        assertTrue(media.peakBytesUsed <= 48 * 1024 * 1024)
        main { media.close() }
        h.close()
    }

    private class NativeDecodeProbe(val context: android.content.Context, val name: String) {
        var bitmap: Bitmap? =
            TestFixtures.assets.open("media/" + name).use { BitmapFactory.decodeStream(it) }
        var closeCount = 0
            private set

        // Отмечаем первое закрытие проверочного рисунка и освобождаем его память. Счётчик
        // подтверждает уход результата после отмены.
        @Synchronized
        fun close() {
            if (closeCount == 0) {
                closeCount = 1
                bitmap?.recycle()
                bitmap = null
            }
        }
    }

    // Занимаем оба чтения и переполняем очередь. Она должна удержать шестнадцать ожидающих и
    // закрыть возвращённые рисунки по одному разу.
    @Test
    fun decodeQueueHasTwoWorkersAndSixteenPending() {
        val queue = MediaDecodeQueue()
        val hold = CountDownLatch(1)
        val started = CountDownLatch(2)
        val finished = CountDownLatch(21)
        val probes = Collections.synchronizedList(mutableListOf<NativeDecodeProbe>())
        val name = photoName()
        main {
            repeat(21) {
                queue.submit(
                    work = {
                        val p = NativeDecodeProbe(context, name)
                        probes.add(p)
                        started.countDown()
                        hold.await()
                        p
                    },
                    dispose = { it.close() },
                ) { result ->
                    result.onSuccess {
                        it.close()
                        fail("closed queue published")
                    }
                    finished.countDown()
                }
            }
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        assertEquals(2, queue.runningCount)
        assertEquals(16, queue.pendingCount)
        main { queue.close() }
        hold.countDown()
        assertTrue(finished.await(3, TimeUnit.SECONDS))
        assertEquals(2, probes.size)
        assertTrue(probes.all { it.closeCount == 1 })
    }

    // Создаём картинки с разной записанной ориентацией и сверяем углы и размеры после чтения.
    // Двойное отражение или поворот сразу изменят ожидаемые цвета.
    @Test
    fun allEightEXIFOrientationsAreAppliedOnce() {
        val h = RepositoryHarness.open(File(context.cacheDir, "orient-" + UUID.randomUUID()))
        val media = MediaStore(h.store)
        val raw = Bitmap.createBitmap(40, 24, Bitmap.Config.ARGB_8888)
        val colors =
            intArrayOf(
                android.graphics.Color.RED,
                android.graphics.Color.GREEN,
                android.graphics.Color.BLUE,
                android.graphics.Color.YELLOW,
            )
        for (y in 0 until 24) for (x in 0 until 40) raw.setPixel(
            x,
            y,
            colors[(if (y < 12) 0 else 2) + (if (x < 20) 0 else 1)],
        )
        val expected =
            arrayOf(
                intArrayOf(0, 1, 2, 3),
                intArrayOf(1, 0, 3, 2),
                intArrayOf(3, 2, 1, 0),
                intArrayOf(2, 3, 0, 1),
                intArrayOf(0, 2, 1, 3),
                intArrayOf(2, 0, 3, 1),
                intArrayOf(3, 1, 2, 0),
                intArrayOf(1, 3, 0, 2),
            )
        for (orientation in 1..8) {
            val file = File(h.root, "orientation-$orientation.jpg")
            file.outputStream().use {
                assertTrue(raw.compress(Bitmap.CompressFormat.JPEG, 100, it))
            }
            android.media.ExifInterface(file.path).apply {
                setAttribute(android.media.ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
            val draft = stage(media) { file.inputStream() }
            assertEquals(40, draft.rawWidth)
            assertEquals(24, draft.rawHeight)
            assertEquals(orientation, draft.orientation)
            val decoded =
                awaitResult<PreparedImage> { cb ->
                    main {
                        media.thumbnail(attachment(draft), ThumbnailKey(1024), orientation) {
                            _,
                            result ->
                            cb(result)
                        }
                    }
                    Cancellation {}
                }
            val image = decoded.bitmap!!
            assertEquals(if (orientation >= 5) 24 else 40, image.width)
            assertEquals(if (orientation >= 5) 40 else 24, image.height)
            assertEquals(1, decoded.orientation)
            val corners =
                listOf(
                    4 to 4,
                    image.width - 5 to 4,
                    4 to image.height - 5,
                    image.width - 5 to image.height - 5,
                )
            for ((n, p) in corners.withIndex()) {
                val actual = image.getPixel(p.first, p.second)
                val reference = colors[expected[orientation - 1][n]]
                for (shift in intArrayOf(16, 8, 0)) assertEquals(
                    "orientation $orientation corner $n component $shift",
                    ((reference ushr shift) and 255).toDouble(),
                    ((actual ushr shift) and 255).toDouble(),
                    25.0,
                )
            }
            decoded.close()
        }
        raw.recycle()
        main { media.close() }
        h.close()
    }

    // Проверяем отказ доступа к исходнику и запрет выхода за рабочий каталог при восстановлении.
    // Файл в другом каталоге сохраняет содержимое.
    @Test
    fun unreadableSourceAndRecoveryCannotFollowDirectorySymlink() {
        val h = RepositoryHarness.open(File(context.cacheDir, "permission-" + UUID.randomUUID()))
        val media = MediaStore(h.store)
        val denied = File(h.root, "denied.jpg")
        TestFixtures.assets.open("media/" + photoName()).use { input ->
            denied.outputStream().use { input.copyTo(it) }
        }
        android.system.Os.chmod(denied.path, 0)
        try {
            val error = runCatching { stage(media) { denied.inputStream() } }.exceptionOrNull()
            assertTrue(
                "native EACCES maps to permission denied",
                error is MediaException && error.reason == MediaError.PERMISSION_DENIED,
            )
        } finally {
            android.system.Os.chmod(
                denied.path,
                android.system.OsConstants.S_IRUSR or android.system.OsConstants.S_IWUSR,
            )
        }
        val outside = File(h.root, "preserved")
        outside.mkdirs()
        val original = File(outside, "original.txt")
        original.writeText("keep original")
        val objects = File(h.root, "media/objects")
        assertTrue(objects.delete())
        android.system.Os.symlink(outside.path, objects.path)
        val error = runCatching { recover(media) }.exceptionOrNull()
        assertTrue(error is MediaException && error.reason == MediaError.CORRUPT)
        assertEquals("keep original", original.readText())
        assertTrue(objects.delete())
        main { media.close() }
        h.close()
    }

    @Test
    fun wrongMediaTypeCannotPublishAFileWithoutTheRequiredTrack() {
        val h = RepositoryHarness.open(File(context.cacheDir, "invalid-tracks-" + UUID.randomUUID()))
        val media = MediaStore(h.store)
        val video = "bounds/video-over-one-minute.mp4"
        val audio = "media/voice-00.m4a"
        fun stageAs(asset: String, mime: String): AttachmentDraft = awaitResult { done ->
            main { media.stage({ TestFixtures.assets.open(asset) }, mime, done) }
            Cancellation {}
        }
        try {
            val original = stageAs(video, "video/mp4")
            val file = media.fileURL(original.relativePath)
            val expected = TestFixtures.assets.open(video).use { it.readBytes() }

            for ((asset, mime) in listOf(audio to "video/mp4", video to "audio/mp4")) {
                val error = assertThrows(MediaException::class.java) { stageAs(asset, mime) }
                assertEquals(MediaError.CORRUPT, error.reason)
            }

            assertEquals(listOf(file.name), file.parentFile?.list()?.toList())
            assertArrayEquals(expected, file.readBytes())
        } finally {
            main { media.close() }
            h.close()
            h.root.deleteRecursively()
        }
    }

    // Готовим несколько настоящих видов вложений, сохраняем сообщения и читаем их первые кадры.
    // Хранилище должно сохранить вид и число файлов.
    @Test
    fun nativeMediaFormatsStagePersistAndDecodePosters() {
        val h = RepositoryHarness.open(File(context.cacheDir, "formats-" + UUID.randomUUID()))
        val media = MediaStore(h.store)
        val files =
            JSONObject(
                    TestFixtures.assets.open("manifest.json").bufferedReader().use { it.readText() }
                )
                .getJSONArray("files")
        val formats =
            listOf(
                "video/mp4" to MessageKind.VIDEO,
                "audio/mp4" to MessageKind.VOICE,
                "image/gif" to MessageKind.GIF,
                "image/png" to MessageKind.STICKER,
            )
        for ((mime, kind) in formats) {
            val name =
                (0 until files.length())
                    .map { files.getJSONObject(it) }
                    .first { it.getString("mime") == mime }
                    .getString("fileName")
            val d =
                awaitResult<AttachmentDraft> { cb ->
                    main { media.stage({ TestFixtures.assets.open("media/" + name) }, mime, cb) }
                    Cancellation {}
                }
            if (mime.startsWith("audio/") || mime.startsWith("video/"))
                assertTrue((d.durationMs ?: 0) > 0)
            if (!mime.startsWith("audio/")) {
                val frame =
                    awaitResult<PreparedImage> { cb ->
                        main {
                            media.thumbnail(attachment(d), ThumbnailKey(1024), 1) { _, r -> cb(r) }
                        }
                        Cancellation {}
                    }
                assertNotNull(frame.bitmap)
                frame.close()
            }
            awaitResult<MessageDelta> { cb ->
                h.repo.send(SendCommand("format-" + mime, 2, kind, attachments = listOf(d)), cb)
            }
        }
        val rows = awaitResult<HistoryPage> { h.repo.history(PageRequest(2), it) }.rows
        assertEquals(formats.map { it.second }, rows.map { it.kind })
        assertTrue(rows.all { it.attachments.size == 1 })
        main { media.close() }
        h.close()
    }

    // Подставляем готовый рисунок в системные команды показа и закрываем хранилище. Рисунок живёт
    // до снятия слоя и затем освобождается один раз.
    @Test
    fun nativeDisplayListLeaseSurvivesCacheCloseUntilDetach() {
        val h = RepositoryHarness.open(File(context.cacheDir, "lease-" + UUID.randomUUID()))
        val media = MediaStore(h.store)
        val d = stage(media)
        val frame =
            awaitResult<PreparedImage> { cb ->
                main { media.thumbnail(attachment(d), ThumbnailKey(1024), 1) { _, r -> cb(r) } }
                Cancellation {}
            }
        main {
            val node = android.graphics.RenderNode("native image lease")
            val bitmap = frame.bitmap!!
            val canvas = node.beginRecording(bitmap.width, bitmap.height)
            canvas.drawBitmap(bitmap, 0f, 0f, android.graphics.Paint())
            node.endRecording()
            assertTrue(node.hasDisplayList())
            media.close()
            assertNotNull(frame.bitmap)
            assertFalse(bitmap.isRecycled)
            assertTrue(media.bytesUsed > 0)
            node.discardDisplayList()
            frame.close()
            assertEquals(0, media.bytesUsed)
            assertEquals(1, frame.closeCount)
        }
        h.close()
    }

    // Закрываем историю после подготовки фото и сверяем расход памяти. Неиспользуемые рисунки
    // должны уйти вместе с чатом.
    @Test
    fun chatCloseReleasesIdleDecodedCache() {
        val h = RepositoryHarness.open(File(context.cacheDir, "chat-cache-" + UUID.randomUUID()))
        lateinit var media: MediaStore
        lateinit var c: MediaCoordinator
        lateinit var admission: MediaAdmission
        lateinit var host: dev.kirill.kandinsky.chat.rendering.ChatScrollHost
        main {
            media = MediaStore(h.store)
            admission = MediaAdmission()
            c = MediaCoordinator(admission, media)
            host = dev.kirill.kandinsky.chat.rendering.ChatScrollHost(context)
            host.configurePlayback(c, media)
        }
        val d = stage(media)
        val f =
            awaitResult<PreparedImage> { cb ->
                main { media.thumbnail(attachment(d), ThumbnailKey(1024), 1) { _, r -> cb(r) } }
                Cancellation {}
            }
        try {
            main {
                f.close()
                assertTrue(media.bytesUsed > 0)
                host.close()
                assertEquals(
                    "Leaving the chat must release its idle decoded cache",
                    0,
                    media.bytesUsed,
                )
            }
        } finally {
            main {
                host.close()
                c.close()
                media.close()
                admission.close()
            }
            h.close()
        }
    }

    // Читаем много разных кадров подряд и проверяем предел памяти. Быстрая прокрутка не должна
    // сохранять все уже просмотренные изображения.
    @Test
    fun serialScrollBurstKeepsOnlySmallDecodedHotSet() {
        val h = RepositoryHarness.open(File(context.cacheDir, "hot-set-" + UUID.randomUUID()))
        lateinit var media: MediaStore
        main { media = MediaStore(h.store) }
        var one = 0
        try {
            repeat(24) {
                val d = stage(media)
                val f =
                    awaitResult<PreparedImage> { cb ->
                        main {
                            media.thumbnail(attachment(d), ThumbnailKey(1024), 1) { _, r -> cb(r) }
                        }
                        Cancellation {}
                    }
                main {
                    one = f.bitmap!!.allocationByteCount
                    f.close()
                }
            }
            main {
                assertTrue(
                    "Serial scroll must not retain every decoded image: ${media.bytesUsed}",
                    media.bytesUsed <= 16 * one,
                )
            }
        } finally {
            main { media.close() }
            h.close()
        }
    }
}
