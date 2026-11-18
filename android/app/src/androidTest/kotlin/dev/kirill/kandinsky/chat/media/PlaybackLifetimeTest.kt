package dev.kirill.kandinsky.chat.media

import androidx.test.platform.app.InstrumentationRegistry
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.support.*
import dev.kirill.kandinsky.chat.support.TestFixtures
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaybackLifetimeTest {
    @Test
    fun videoSurfaceContainsOnlyVideoAndPoster() {
        val h = RepositoryHarness.open(File(context.cacheDir, "surface-only-" + UUID.randomUUID()))
        val media = MediaStore(h.store, h.repo)
        val admission = MediaAdmission()
        main {
            val player = MediaCoordinator(admission, media)
            val surface = InlinePlayerSurface(context, player)
            surface.update()
            assertEquals(
                listOf(android.view.TextureView::class.java, android.widget.ImageView::class.java),
                (0 until surface.childCount).map { surface.getChildAt(it).javaClass },
            )
            assertFalse(surface.isClickable)
            assertFalse(surface.hasOnClickListeners())
            surface.detachPlayer()
            player.close()
            media.close()
            admission.close()
        }
        h.close()
    }

    // Неподдержанный файл не должен занять разрешение или запустить фоновую загрузку.
    @Test
    fun audioCannotOpenPlayerOrEnableSound() {
        val h =
            RepositoryHarness.open(File(context.cacheDir, "audio-rejected-" + UUID.randomUUID()))
        val media = MediaStore(h.store, h.repo)
        val admission = MediaAdmission()
        lateinit var player: MediaCoordinator
        main {
            player = MediaCoordinator(admission, media)
            val audio =
                Attachment(
                    "unsupported-audio",
                    "objects/absent.m4a",
                    "audio/mp4",
                    42,
                    0,
                    0,
                    1,
                    1000,
                    "",
                )
            player.play(PlaybackTarget(audio, 4401, MessageKind.VOICE))
            assertNull(player.currentTarget)
            assertEquals(MediaError.UNAVAILABLE, (player.lastError as? MediaException)?.reason)
            assertEquals(0, player.activePlayerCount)
            assertTrue(player.isMuted)
            player.close()
            media.close()
            admission.close()
        }
        h.close()
    }

    private val i = InstrumentationRegistry.getInstrumentation()
    private val context = i.targetContext

    private fun main(action: () -> Unit) = i.runOnMainSync(action)

    @Test
    fun mutedRoundLoopsAcrossNativeEndAndKeepsPauseSeekAndOneOwner() {
        val root = File(context.cacheDir, "round-loop-" + UUID.randomUUID())
        val h = RepositoryHarness.open(root)
        val admission = MediaAdmission()
        lateinit var media: MediaStore
        lateinit var c: MediaCoordinator
        main {
            media = MediaStore(h.store, h.repo)
            c = MediaCoordinator(admission, media)
        }
        try {
            val draft =
                awaitResult<AttachmentDraft> { cb ->
                    main {
                        media.stage(
                            { TestFixtures.assets.open("media/video-00.mp4") },
                            "video/mp4",
                            cb,
                        )
                    }
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
            val extractor = android.media.MediaExtractor()
            try {
                extractor.setDataSource(File(root, "media/" + a.relativePath).path)
                assertTrue(
                    "Fixture contains actual AAC audio",
                    (0 until extractor.trackCount).any {
                        extractor
                            .getTrackFormat(it)
                            .getString(android.media.MediaFormat.KEY_MIME)
                            ?.startsWith("audio/") == true
                    },
                )
            } finally {
                extractor.release()
            }
            val ready = CountDownLatch(1)
            val twice = CountDownLatch(1)
            val wraps = java.util.concurrent.atomic.AtomicInteger()
            var last = 0L
            main {
                c.onChange = {
                    if (c.isReady) ready.countDown()
                    if (c.isReady && c.isPlaying) {
                        val position = c.positionMs
                        if (last > 1400 && position < 500) {
                            if (wraps.incrementAndGet() >= 2) twice.countDown()
                        }
                        last = position
                    }
                }
                c.play(PlaybackTarget(a, 991, MessageKind.ROUND))
            }
            assertTrue("Беззвучное круглое видео готово", ready.await(5, TimeUnit.SECONDS))
            main { assertTrue("Видео должно оставаться без звука", c.isMuted) }
            assertTrue(
                "Native time must cross the end twice, not merely set a loop flag",
                twice.await(8500, TimeUnit.MILLISECONDS),
            )
            main {
                assertEquals(1, c.activePlayerCount)
                assertEquals(1, c.peakNativePlayers)
                c.pause()
            }
            android.os.SystemClock.sleep(150)
            var paused = 0L
            main { paused = c.positionMs }
            android.os.SystemClock.sleep(350)
            main {
                assertEquals(paused.toDouble(), c.positionMs.toDouble(), 80.0)
                assertFalse(c.isPlaying)
            }
            val sought = CountDownLatch(1)
            main {
                c.seek(1600) { done ->
                    assertTrue(done)
                    sought.countDown()
                }
            }
            assertTrue(sought.await(3, TimeUnit.SECONDS))
            main {
                assertFalse(c.isPlaying)
                assertEquals(1600.0, c.positionMs.toDouble(), 120.0)
            }
            val again = CountDownLatch(1)
            main {
                last = c.positionMs
                c.onChange = {
                    if (c.isReady && c.isPlaying) {
                        val position = c.positionMs
                        if (last > 1400 && position < 500) again.countDown()
                        last = position
                    }
                }
                c.resume()
            }
            assertTrue("Round loops after paused seek", again.await(3, TimeUnit.SECONDS))
            val stopped = CountDownLatch(1)
            main {
                c.stop {
                    assertEquals(0, c.activePlayerCount)
                    stopped.countDown()
                }
            }
            assertTrue("Native loop owner retires", stopped.await(3, TimeUnit.SECONDS))
        } finally {
            main {
                c.onChange = null
                c.close()
                media.close()
                admission.close()
            }
            h.close()
        }
    }

    @Test
    fun regularVideoEndsWithoutLoopingAndCanSeekToReplay() {
        val h = RepositoryHarness.open(File(context.cacheDir, "video-end-" + UUID.randomUUID()))
        val admission = MediaAdmission()
        lateinit var media: MediaStore
        lateinit var c: MediaCoordinator
        main {
            media = MediaStore(h.store, h.repo)
            c = MediaCoordinator(admission, media)
        }
        try {
            val draft =
                awaitResult<AttachmentDraft> { cb ->
                    main {
                        media.stage(
                            { TestFixtures.assets.open("media/video-00.mp4") },
                            "video/mp4",
                            cb,
                        )
                    }
                    Cancellation {}
                }
            val asset =
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
            val ready = CountDownLatch(1)
            val ended = CountDownLatch(1)
            main {
                c.onChange = {
                    if (c.isReady) ready.countDown()
                    if (c.isReady && !c.isPlaying && c.positionMs >= 1800) ended.countDown()
                }
                c.play(PlaybackTarget(asset, 992, MessageKind.VIDEO))
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            assertTrue("Видео должно завершиться один раз", ended.await(5, TimeUnit.SECONDS))
            main {
                assertFalse(c.isPlaying)
                assertTrue(c.isReady)
                assertTrue(c.positionMs >= 1800)
            }
            val sought = CountDownLatch(1)
            main {
                c.seek(400) { done ->
                    assertTrue(done)
                    sought.countDown()
                }
            }
            assertTrue(sought.await(3, TimeUnit.SECONDS))
            val advanced = CountDownLatch(1)
            main {
                assertEquals(400.0, c.positionMs.toDouble(), 120.0)
                c.onChange = { if (c.isPlaying && c.positionMs > 600) advanced.countDown() }
                c.resume()
            }
            assertTrue(
                "После перемотки видно настоящее продвижение видео",
                advanced.await(5, TimeUnit.SECONDS),
            )
            main {
                assertTrue(c.isPlaying)
                assertTrue(c.positionMs > 600)
            }
        } finally {
            main {
                c.onChange = null
                c.close()
                media.close()
                admission.close()
            }
            h.close()
        }
    }

    @Test
    fun higherRevisionStopsReplacedSourceButAcknowledgementKeepsTheSameLocalFile() {
        val h =
            RepositoryHarness.open(File(context.cacheDir, "revision-player-" + UUID.randomUUID()))
        val admission = MediaAdmission()
        lateinit var media: MediaStore
        lateinit var c: MediaCoordinator
        main {
            media = MediaStore(h.store, h.repo)
            c = MediaCoordinator(admission, media)
        }
        try {
            val draft =
                awaitResult<AttachmentDraft> { cb ->
                    main {
                        media.stage(
                            { TestFixtures.assets.open("media/video-00.mp4") },
                            "video/mp4",
                            cb,
                        )
                    }
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
            val ready = CountDownLatch(1)
            main {
                c.onChange = { if (c.isReady) ready.countDown() }
                c.play(PlaybackTarget(a, 90, MessageKind.VIDEO))
            }
            assertTrue(ready.await(8, TimeUnit.SECONDS))
            val chat = ChatSummary(2, "source", "", 1, 1, 0, false)
            fun update(value: Attachment) =
                MessageDelta(
                    updated =
                        listOf(
                            Message(
                                90,
                                2,
                                OrderKey(1, 90),
                                MessageDirection.OUTGOING,
                                1,
                                MessageKind.VIDEO,
                                "",
                                "",
                                null,
                                2,
                                listOf(value),
                            )
                        ),
                    chat = chat,
                    reason = MessageChangeReason.SYNC,
                )
            main {
                c.pause()
                c.apply(update(a.copy(id = "acknowledged-id", thumbnailKey = "ack")))
                assertEquals("Same local source survives acknowledgement", 1, c.activePlayerCount)
            }
            val stopped = CountDownLatch(1)
            main {
                c.onChange = { if (c.activePlayerCount == 0) stopped.countDown() }
                c.apply(
                    update(a.copy(id = "acknowledged-id", relativePath = "remote/replaced.mp4"))
                )
                assertNull("A revised blob must not keep playing stale content", c.currentMessageID)
            }
            assertTrue("Native retirement must finish", stopped.await(3, TimeUnit.SECONDS))
        } finally {
            main {
                c.close()
                media.close()
                admission.close()
            }
            h.close()
        }
    }

    @Test
    fun singleNativeOwnerSeekLatestTargetAndCaptureAfterRelease() {
        val h = RepositoryHarness.open(File(context.cacheDir, "play-" + UUID.randomUUID()))
        val admission = MediaAdmission()
        lateinit var media: MediaStore
        lateinit var c: MediaCoordinator
        main {
            media = MediaStore(h.store, h.repo)
            c = MediaCoordinator(admission, media)
        }
        val files =
            JSONObject(
                    TestFixtures.assets.open("manifest.json").bufferedReader().use { it.readText() }
                )
                .getJSONArray("files")
        val name =
            (0 until files.length())
                .map { files.getJSONObject(it) }
                .first { it.getString("mime") == "video/mp4" }
                .getString("fileName")
        val d =
            awaitResult<AttachmentDraft> { cb ->
                main { media.stage({ TestFixtures.assets.open("media/" + name) }, "video/mp4", cb) }
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
        val ready = CountDownLatch(1)
        main {
            c.onChange = { if (c.isReady && c.currentMessageID == 1L) ready.countDown() }
            c.play(PlaybackTarget(a, 1))
        }
        assertTrue(ready.await(8, TimeUnit.SECONDS))
        main {
            assertEquals(1, c.activePlayerCount)
            assertTrue(kotlin.math.abs(c.durationMs - 2000) < 80)
        }
        val seek = CountDownLatch(1)
        main {
            c.pause()
            c.seek(1200) {
                assertTrue(it)
                seek.countDown()
            }
        }
        assertTrue("Native seek completion must arrive within 3s", seek.await(3, TimeUnit.SECONDS))
        main {
            assertFalse(c.isPlaying)
            assertTrue(
                "Seek position ${c.positionMs}ms must reach 1200ms",
                kotlin.math.abs(c.positionMs - 1200) < 120,
            )
        }
        val latest = CountDownLatch(1)
        main {
            c.onChange = { if (c.isReady && c.currentMessageID == 3L) latest.countDown() }
            c.play(PlaybackTarget(a, 2))
            c.play(PlaybackTarget(a, 3))
        }
        val switched = latest.await(8, TimeUnit.SECONDS)
        var state = ""
        main {
            state =
                listOf("stopping", "desired", "request", "session").joinToString { n ->
                    val f =
                        MediaCoordinator::class.java.getDeclaredField(n).apply {
                            isAccessible = true
                        }
                    val v = f.get(c)
                    n +
                        "=" +
                        when (v) {
                            is PlaybackTarget -> v.messageID
                            null -> "null"
                            is Boolean -> v
                            else -> v.javaClass.simpleName
                        }
                } +
                    ", native=" +
                    c.activePlayerCount +
                    ", error=" +
                    (c.lastError?.javaClass?.simpleName ?: "none")
        }
        assertTrue("Latest target must resume after native retirement: " + state, switched)
        main {
            assertEquals(1, c.activePlayerCount)
            assertEquals(1, c.peakNativePlayers)
        }
        val capture = CountDownLatch(1)
        main {
            admission.acquire(CaptureKind.PHOTO) { r ->
                assertTrue(r.isSuccess)
                assertEquals(0, c.activePlayerCount)
                r.getOrThrow().release()
                capture.countDown()
            }
        }
        assertTrue(capture.await(3, TimeUnit.SECONDS))
        main {
            c.close()
            media.close()
            admission.close()
        }
        h.close()
    }

    // Поздний ответ не должен возобновить показ.
    @Test
    fun closeDuringPrepareDoesNotResumeFromLateCallback() {
        val h = RepositoryHarness.open(File(context.cacheDir, "play-close-" + UUID.randomUUID()))
        val admission = MediaAdmission()
        lateinit var media: MediaStore
        lateinit var c: MediaCoordinator
        main {
            media = MediaStore(h.store, h.repo)
            c = MediaCoordinator(admission, media)
        }
        val files =
            JSONObject(
                    TestFixtures.assets.open("manifest.json").bufferedReader().use { it.readText() }
                )
                .getJSONArray("files")
        val name =
            (0 until files.length())
                .map { files.getJSONObject(it) }
                .first { it.getString("mime") == "video/mp4" }
                .getString("fileName")
        val d =
            awaitResult<AttachmentDraft> { cb ->
                main { media.stage({ TestFixtures.assets.open("media/" + name) }, "video/mp4", cb) }
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
        val began = CountDownLatch(1)
        main {
            c.onChange = {
                if (c.activePlayerCount == 1 && !c.isReady) {
                    c.close()
                    began.countDown()
                }
            }
            c.play(PlaybackTarget(a, 1))
        }
        assertTrue(
            "A real native player must be created before close",
            began.await(5, TimeUnit.SECONDS),
        )
        val capture = CountDownLatch(1)
        main {
            admission.acquire(CaptureKind.PHOTO) { r ->
                assertTrue(r.isSuccess)
                r.getOrThrow().release()
                capture.countDown()
            }
        }
        assertTrue(capture.await(3, TimeUnit.SECONDS))
        main {
            assertEquals(0, c.activePlayerCount)
            assertFalse(c.isPlaying)
            media.close()
            admission.close()
        }
        h.close()
    }

    @Test
    fun missingPlaybackMessageStopsNativeOwner() {
        val h = RepositoryHarness.open(File(context.cacheDir, "offscreen-" + UUID.randomUUID()))
        val admission = MediaAdmission()
        lateinit var media: MediaStore
        lateinit var c: MediaCoordinator
        lateinit var host: dev.kirill.kandinsky.chat.rendering.ChatScrollHost
        main {
            media = MediaStore(h.store, h.repo)
            c = MediaCoordinator(admission, media)
            host = dev.kirill.kandinsky.chat.rendering.ChatScrollHost(context)
            host.configurePlayback(c, media)
        }
        val files =
            JSONObject(
                    TestFixtures.assets.open("manifest.json").bufferedReader().use { it.readText() }
                )
                .getJSONArray("files")
        val name =
            (0 until files.length())
                .map { files.getJSONObject(it) }
                .first { it.getString("mime") == "video/mp4" }
                .getString("fileName")
        val d =
            awaitResult<AttachmentDraft> { cb ->
                main { media.stage({ TestFixtures.assets.open("media/" + name) }, "video/mp4", cb) }
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
        val ready = CountDownLatch(1)
        main {
            c.onChange = { if (c.isReady) ready.countDown() }
            c.play(PlaybackTarget(a, 987654321))
        }
        try {
            assertTrue(
                "Real native player must be prepared first",
                ready.await(8, TimeUnit.SECONDS),
            )
            main {
                assertEquals(1, c.activePlayerCount)
                host.layout(0, 0, 600, 1000)
            }
            val released = CountDownLatch(1)
            main {
                if (c.activePlayerCount == 0) released.countDown()
                c.onChange = { if (c.activePlayerCount == 0) released.countDown() }
            }
            assertTrue(
                "An absent message must release its native player",
                released.await(2, TimeUnit.SECONDS),
            )
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

    @Test
    fun repeatedStopCancelsEveryPendingOriginalDuringRetirement() {
        val h =
            RepositoryHarness.open(File(context.cacheDir, "cancel-retiring-" + UUID.randomUUID()))
        val admission = MediaAdmission()
        lateinit var media: MediaStore
        lateinit var c: MediaCoordinator
        val entered = Array(2) { CountDownLatch(1) }
        val gate = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val cancelled = Array(2) { java.util.concurrent.atomic.AtomicBoolean(false) }
        main {
            media = MediaStore(h.store, h.repo)
            c = MediaCoordinator(admission, media)
        }
        val fixture = File(h.root, "prepared.mp4")
        TestFixtures.assets.open("media/video-00.mp4").use { input ->
            fixture.outputStream().use { input.copyTo(it) }
        }
        main {
            media.remoteFile = { a, _, token ->
                val index = a.id.toInt()
                entered[index].countDown()
                gate.await()
                cancelled[index].set(token.isCancelled)
                finished.countDown()
                fixture to "video/mp4"
            }
        }
        try {
            main {
                for (index in 0..1) {
                    c.play(
                        PlaybackTarget(
                            Attachment(
                                index.toString(),
                                "remote/a$index.mp4",
                                "video/mp4",
                                fixture.length(),
                                0,
                                0,
                                1,
                                2000,
                                "",
                            ),
                            index.toLong(),
                        )
                    )
                    assertTrue(
                        "Controlled original request $index must enter before the next transition",
                        entered[index].await(2, TimeUnit.SECONDS),
                    )
                }
                c.stop()
            }
            gate.countDown()
            assertTrue(finished.await(3, TimeUnit.SECONDS))
            assertTrue(
                "Switching during native retirement must cancel the first original fetch",
                cancelled[0].get(),
            )
            assertTrue(
                "Stopping during native retirement must cancel the latest original fetch",
                cancelled[1].get(),
            )
        } finally {
            gate.countDown()
            main {
                c.close()
                media.close()
                admission.close()
            }
            h.close()
        }
    }
}
