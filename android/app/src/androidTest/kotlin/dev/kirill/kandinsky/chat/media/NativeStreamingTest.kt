package dev.kirill.kandinsky.chat.media

import androidx.test.platform.app.InstrumentationRegistry
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.network.*
import dev.kirill.kandinsky.chat.support.*
import dev.kirill.kandinsky.chat.support.TestFixtures
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class NativeStreamingTest {
    private val i = InstrumentationRegistry.getInstrumentation()
    private val context = i.targetContext

    private fun main(action: () -> Unit) = i.runOnMainSync(action)

    @Test
    fun realServerRoundAndVideoAutoplayShowNativeFramesAndReuseRangeCache() {
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
            ) as android.app.Activity
        assertTrue(
            device.wait(
                androidx.test.uiautomator.Until.hasObject(
                    androidx.test.uiautomator.By.pkg(i.targetContext.packageName)
                ),
                15000,
            )
        )
        val profile = checkNotNull(NetworkCredentials(context).load())
        val h = RepositoryHarness.open(File(context.cacheDir, "real-autoplay-" + UUID.randomUUID()))
        lateinit var media: MediaStore
        lateinit var root: android.widget.FrameLayout
        main {
            media = MediaStore(h.store, h.repo)
            root = android.widget.FrameLayout(activity)
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity.setContentView(root)
        }
        val requests = AtomicInteger()
        val api = NetworkAPI(profile) { requests.incrementAndGet() }
        api.initialize()
        try {
            val data = api.json("/v1/chats/1000/history?limit=128")
            val cache = NetworkStore(h.repo, profile.userID)
            val deltas =
                awaitResult<List<MessageDelta>> { cb ->
                    h.store.submit(
                        { db ->
                            NetworkSchema.install(db)
                            db.execSQL(
                                "INSERT OR IGNORE INTO chats(id,title) VALUES(1000,'Public media')"
                            )
                            cache.history(db, 1000, data)
                        },
                        cb,
                    )
                }
            val expected =
                mapOf(
                    MessageKind.VIDEO to
                        "2ee693f559b0269434294bb11d042c056f9625007b6d8b41dca9851779596bf3",
                    MessageKind.ROUND to
                        "3c12cd48b694d7776178394aaef872bc94c13eb885fb421d23cc2fc81aec1a94",
                )
            val rows = deltas.flatMap { it.updated }
            val selected =
                listOf(MessageKind.VIDEO, MessageKind.ROUND).map { kind ->
                    checkNotNull(
                        rows.firstOrNull { row ->
                            row.kind == kind &&
                                row.attachments.firstOrNull()?.let {
                                    File(it.relativePath).nameWithoutExtension
                                } == expected[kind]
                        }
                    )
                }
            val remote = NetworkMedia(h.store, api)
            main { media.remotePlayback = { a, token -> remote.playback(a, token) } }
            fun waitFor(label: String, seconds: Int = 5, check: () -> Boolean) {
                val end = android.os.SystemClock.uptimeMillis() + seconds * 1000
                while (android.os.SystemClock.uptimeMillis() < end) {
                    var ready = false
                    main { ready = check() }
                    if (ready) return
                    android.os.SystemClock.sleep(20)
                }
                throw AssertionError(label)
            }
            fun exercise(): List<Int> {
                val owners = mutableListOf<SilentVideoPreview>()
                val prepared = mutableListOf<dev.kirill.kandinsky.chat.rendering.PreparedText>()
                val stamps = mutableListOf<android.graphics.RenderNode>()
                try {
                    main {
                        val density = context.resources.displayMetrics.density
                        for ((n, row) in selected.withIndex()) {
                            val owner = SilentVideoPreview(activity, media) {}
                            owners += owner
                            val p =
                                dev.kirill.kandinsky.chat.rendering.PreparedText(
                                    row,
                                    dev.kirill.kandinsky.chat.rendering.TextLayoutKey(
                                        row.id,
                                        row.revision,
                                        (160 * density).toInt(),
                                        (17 * density).toInt(),
                                        density.toInt(),
                                        density,
                                    ),
                                )
                            prepared += p
                            val stamp = android.graphics.RenderNode("remote autoplay test stamp")
                            stamp.beginRecording(1, 1)
                            stamp.endRecording()
                            stamps += stamp
                            root.addView(
                                owner,
                                android.widget.FrameLayout.LayoutParams(
                                        (160 * density).toInt(),
                                        (160 * density).toInt(),
                                    )
                                    .apply {
                                        leftMargin = ((8 + n * 180) * density).toInt()
                                        topMargin = (80 * density).toInt()
                                    },
                            )
                            owner.bind(
                                row.attachments.first(),
                                android.graphics.RectF(
                                    (8 + n * 180) * density,
                                    80 * density,
                                    (168 + n * 180) * density,
                                    240 * density,
                                ),
                                n == 1,
                                12 * density,
                                false,
                                stamp,
                                p,
                            )
                        }
                    }
                    waitFor("Actual remote video and round must become native moving outputs", 35) {
                        owners.all { it.active }
                    }
                    val hashes = listOf(mutableSetOf<Int>(), mutableSetOf<Int>())
                    val end = android.os.SystemClock.uptimeMillis() + 4000
                    while (
                        android.os.SystemClock.uptimeMillis() < end && hashes.any { it.size < 3 }
                    ) {
                        main {
                            for (n in owners.indices) {
                                val texture =
                                    (0 until owners[n].childCount)
                                        .map { owners[n].getChildAt(it) }
                                        .filterIsInstance<android.view.TextureView>()
                                        .single()
                                texture.getBitmap(64, 64)?.let { b ->
                                    val pixels = IntArray(64 * 64)
                                    b.getPixels(pixels, 0, 64, 0, 0, 64, 64)
                                    hashes[n] += pixels.contentHashCode()
                                    b.recycle()
                                }
                            }
                        }
                        android.os.SystemClock.sleep(60)
                    }
                    hashes.forEach {
                        assertTrue("Remote source pixels must actually change", it.size >= 3)
                    }
                    return hashes.map { it.size }
                } finally {
                    main {
                        owners.forEach { it.close() }
                        prepared.forEach { it.close() }
                        stamps.forEach { it.discardDisplayList() }
                    }
                    waitFor("All remote native preview owners must retire") {
                        MediaAdmission.previewOwnerCount == 0
                    }
                }
            }
            val coldFrames = exercise()
            android.os.SystemClock.sleep(150)
            val cold = requests.get()
            val warmFrames = exercise()
            android.os.SystemClock.sleep(150)
            val warm = requests.get() - cold
            assertEquals(
                "A repeated short autoplay must reuse already-fetched range chunks",
                0,
                warm,
            )
            File(context.getExternalFilesDir(null), "kandinsky-live-autoplay-origin.json")
                .writeText(
                    org.json
                        .JSONObject()
                        .put("source", "real HTTPS range cache")
                        .put("coldRequests", cold)
                        .put("warmRequests", warm)
                        .put("coldUniqueNativeFrames", org.json.JSONArray(coldFrames))
                        .put("warmUniqueNativeFrames", org.json.JSONArray(warmFrames))
                        .put(
                            "blobs",
                            org.json.JSONArray(
                                listOf(expected[MessageKind.VIDEO], expected[MessageKind.ROUND])
                            ),
                        )
                        .toString(2)
                )
        } finally {
            api.close()
            main {
                media.close()
                assertEquals(0, media.bytesUsed)
                activity.finish()
            }
            h.close()
        }
    }

    @Test
    fun realServerCatalogueUsesVerifiedOriginalsAndWarmCacheMakesNoRequests() {
        val profile = checkNotNull(NetworkCredentials(context).load())
        val h = RepositoryHarness.open(File(context.cacheDir, "real-origin-" + UUID.randomUUID()))
        lateinit var media: MediaStore
        main { media = MediaStore(h.store, h.repo) }
        val requests = AtomicInteger()
        val api = NetworkAPI(profile) { requests.incrementAndGet() }
        api.initialize()
        try {
            val history = api.json("/v1/chats/1000/history?limit=128")
            val cache = NetworkStore(h.repo, profile.userID)
            val deltas =
                awaitResult<List<MessageDelta>> { cb ->
                    h.store.submit(
                        { db ->
                            NetworkSchema.install(db)
                            db.execSQL(
                                "INSERT OR IGNORE INTO chats(id,title) VALUES(1000,'Public media')"
                            )
                            cache.history(db, 1000, history)
                        },
                        cb,
                    )
                }
            val remote = NetworkMedia(h.store, api)
            main { media.remoteFile = { a, preview, token -> remote.resolve(a, preview, token) } }
            val expected =
                mapOf(
                    MessageKind.PHOTO to
                        setOf(
                            "64097b9124f0eb1c7a477eeafc4093e34515c207bb54ea7df638904c0ccd1cc6",
                            "80931cf63e6aad52b113b08b6f15c72eeb2e3854d0d1caf52b6425a4918e4135",
                            "fb8f3fe6f15d6bbe6f6cd5afafbeadade3dfd6dc8de6e0b5bc8c596ba8a8a2c4",
                        ),
                    MessageKind.VIDEO to
                        setOf(
                            "2ee693f559b0269434294bb11d042c056f9625007b6d8b41dca9851779596bf3",
                            "47b00d53ce24ae5c71315c54f1090b9290a4a8e594018709fd62e6f3a0e1b6d6",
                        ),
                    MessageKind.ROUND to
                        setOf("3c12cd48b694d7776178394aaef872bc94c13eb885fb421d23cc2fc81aec1a94"),
                    MessageKind.VOICE to
                        setOf("4375964b68975f7fb010e1d82db521821e2ccd6a1075c3bdd3c8e97a17071ced"),
                    MessageKind.GIF to
                        setOf(
                            "be5ef71edf955f2b5eff67697bc1283268268847d09705d34d713b5fcb9e33bd",
                            "b84fee7d9ed4944c69ee514ed5b0fe1e762f181ac8a3fdd165691db5ca43b645",
                            "be6d1163f5c3650133f7ec1c69756830c8dee6461d2a682da60906665f880181",
                        ),
                )
            // При истечении ожидания отменяет запрос, иначе возвращает файл или передаёт полученную
            // ошибку.
            fun original(a: Attachment): File {
                val done = CountDownLatch(1)
                val result = java.util.concurrent.atomic.AtomicReference<Result<File>>()
                val calls = AtomicInteger()
                var token: Cancellation? = null
                main {
                    token =
                        media.ensureOriginal(a) {
                            assertEquals(1, calls.incrementAndGet())
                            result.set(it)
                            done.countDown()
                        }
                }
                val finished = done.await(35, TimeUnit.SECONDS)
                if (!finished) token?.cancel()
                assertTrue("Actual HTTPS original must finish or fail within35s", finished)
                return result.get().getOrThrow()
            }
            val verified = mutableListOf<Attachment>()
            for (kind in
                listOf(
                    MessageKind.PHOTO,
                    MessageKind.VIDEO,
                    MessageKind.ROUND,
                    MessageKind.VOICE,
                    MessageKind.GIF,
                )) {
                val message =
                    deltas
                        .flatMap { it.updated }
                        .firstOrNull {
                            it.kind == kind &&
                                it.attachments.firstOrNull()?.let { a ->
                                    File(a.relativePath).nameWithoutExtension in
                                        expected.getValue(kind)
                                } == true
                        }
                assertNotNull("Real server catalogue is not active for $kind", message)
                val a = message!!.attachments.first()
                val file = original(a)
                assertTrue(file.path.startsWith(h.root.path + "/media/remote/"))
                assertEquals(
                    File(a.relativePath).nameWithoutExtension,
                    MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") {
                        "%02x".format(it)
                    },
                )
                verified += a
            }
            val cold = requests.get()
            assertTrue(cold >= 6)
            for (a in verified) original(a)
            assertEquals(
                "Warm originals must come from disk without another HTTP request",
                cold,
                requests.get(),
            )
            File(context.getExternalFilesDir(null), "kandinsky-live-media-origin.json")
                .writeText(
                    org.json
                        .JSONObject()
                        .put("originals", verified.size)
                        .put("coldRequests", cold)
                        .put("warmRequests", requests.get() - cold)
                        .put("source", "HTTPS_SHA256")
                        .put(
                            "blobs",
                            org.json.JSONArray(
                                verified.map { File(it.relativePath).nameWithoutExtension }
                            ),
                        )
                        .toString(2)
                )
        } finally {
            api.close()
            main {
                media.close()
                assertEquals(0, media.bytesUsed)
            }
            h.close()
        }
    }

    @Test
    fun nativePlayerPreparesAndSeeksOnPartialMP4() {
        val h = RepositoryHarness.open(File(context.cacheDir, "stream-" + UUID.randomUUID()))
        val media = MediaStore(h.store, h.repo)
        val admission = MediaAdmission()
        lateinit var player: MediaCoordinator
        main { player = MediaCoordinator(admission, media) }
        try {
            val source = File(h.root, "authored.mp4")
            source.outputStream().use { out ->
                TestFixtures.assets.open("media/video-00.mp4").use { it.copyTo(out) }
                out.write(ByteBuffer.allocate(4).putInt(4 * 1024 * 1024).array())
                out.write("free".toByteArray())
                out.write(ByteArray(4 * 1024 * 1024 - 8))
            }
            val blob =
                MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString("") {
                    "%02x".format(it.toInt() and 255)
                }
            val counter = AtomicInteger()
            val original = File(h.root, "media/remote/$blob.mp4")
            main {
                media.remotePlayback = { _, _ ->
                    MediaPlaybackSource(
                        ranges =
                            MediaRangeCache(h.root, blob, source.length(), original) {
                                start,
                                end,
                                token ->
                                if (token.isCancelled) throw MediaException(MediaError.CANCELLED)
                                counter.incrementAndGet()
                                RandomAccessFile(source, "r").use {
                                    it.seek(start)
                                    ByteArray((end - start + 1).toInt()).also { data ->
                                        it.readFully(data)
                                    }
                                }
                            }
                    )
                }
            }
            val ready = CountDownLatch(1)
            main {
                player.onChange = {
                    if (player.isReady || player.lastError != null) ready.countDown()
                }
                player.play(
                    PlaybackTarget(
                        Attachment(
                            "stream-test",
                            "remote/$blob.mp4",
                            "video/mp4",
                            source.length(),
                            320,
                            240,
                            1,
                            2000,
                            "",
                        ),
                        51,
                    )
                )
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            assertNull(player.lastError)
            assertTrue(player.isReady)
            main { player.pause() }
            val seek = CountDownLatch(1)
            main {
                player.seek(1200) {
                    assertTrue(it)
                    seek.countDown()
                }
            }
            assertTrue(seek.await(4, TimeUnit.SECONDS))
            assertTrue(kotlin.math.abs(player.positionMs - 1200) < 100)
            assertTrue(
                "Playback must not wait for the full original",
                counter.get().toLong() * MediaRangeCache.CHUNK_SIZE < source.length(),
            )
            assertFalse(original.exists())
            val stopped = CountDownLatch(1)
            main { player.stop { stopped.countDown() } }
            assertTrue(stopped.await(3, TimeUnit.SECONDS))
            assertEquals(0, player.activePlayerCount)
            assertEquals(1, player.peakNativePlayers)
        } finally {
            main {
                player.close()
                media.close()
                admission.close()
            }
            h.close()
        }
    }

    @Test
    fun hundredPlaybackSeekStopCyclesKeepOneNativeOwner() {
        val h = RepositoryHarness.open(File(context.cacheDir, "player-soak-" + UUID.randomUUID()))
        val media = MediaStore(h.store, h.repo)
        val admission = MediaAdmission()
        lateinit var player: MediaCoordinator
        main { player = MediaCoordinator(admission, media) }
        try {
            val directory = File(h.root, "media/objects").apply { mkdirs() }
            for (name in listOf("video-00.mp4", "voice-00.m4a")) TestFixtures.assets
                .open("media/$name")
                .use { input -> File(directory, name).outputStream().use { input.copyTo(it) } }
            for (n in 0 until 100) {
                val voice = n % 3 == 0
                val name = if (voice) "voice-00.m4a" else "video-00.mp4"
                val file = File(directory, name)
                val a =
                    Attachment(
                        "soak-$n",
                        "objects/$name",
                        if (voice) "audio/mp4" else "video/mp4",
                        file.length(),
                        320,
                        240,
                        1,
                        2000,
                        "",
                    )
                val ready = CountDownLatch(1)
                main {
                    player.onChange = {
                        if (player.isReady || player.lastError != null) ready.countDown()
                    }
                    player.play(
                        PlaybackTarget(
                            a,
                            1000 + n.toLong(),
                            if (voice) MessageKind.VOICE
                            else if (n % 3 == 1) MessageKind.ROUND else MessageKind.VIDEO,
                        )
                    )
                }
                if (n % 7 != 0) {
                    assertTrue("Prepare cycle $n", ready.await(5, TimeUnit.SECONDS))
                    assertNull(player.lastError)
                    assertEquals(1, player.activePlayerCount)
                    val seek = CountDownLatch(1)
                    main {
                        player.pause()
                        player.seek(1200) {
                            assertTrue(it)
                            seek.countDown()
                        }
                    }
                    assertTrue("Seek cycle $n", seek.await(3, TimeUnit.SECONDS))
                }
                val stopped = CountDownLatch(1)
                main { player.stop { stopped.countDown() } }
                assertTrue("Stop cycle $n", stopped.await(3, TimeUnit.SECONDS))
                assertEquals(0, player.activePlayerCount)
            }
            assertEquals(1, player.peakNativePlayers)
            main { media.releaseIdleImages() }
            assertEquals(0, media.bytesUsed)
        } finally {
            main {
                player.close()
                media.close()
                admission.close()
            }
            h.close()
        }
    }
}
