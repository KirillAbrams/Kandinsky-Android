package dev.kirill.kandinsky.chat.media

import android.app.Activity
import android.os.Debug
import android.os.SystemClock
import android.view.TextureView
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.kirill.kandinsky.chat.app.MainActivity
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.support.*
import dev.kirill.kandinsky.chat.support.TestFixtures
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VideoSurfaceSoakTest {
    @Test
    fun failedPreviewRetriesOnlyAfterLeavingAndReturning() {
        val activity = emptyActivity()
        val h = RepositoryHarness.open(File(context.cacheDir, "failed-preview-" + UUID.randomUUID()))
        lateinit var media: MediaStore
        lateinit var root: FrameLayout
        main { media = MediaStore(h.store, h.repo); root = FrameLayout(activity); activity.setContentView(root) }
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val pool = SilentVideoPreviewPool()
        try {
            val d = awaitResult<AttachmentDraft> { cb ->
                main { media.stage({ TestFixtures.assets.open("media/video-00.mp4") }, "video/mp4", cb) }
                Cancellation {}
            }
            val a = Attachment(d.id, d.relativePath, d.mime, d.byteSize, d.rawWidth, d.rawHeight,
                d.orientation, d.durationMs, d.thumbnailKey)
            val key = VideoPlaybackKey(78004, a)
            lateinit var first: SilentVideoPreview
            lateinit var prepared: dev.kirill.kandinsky.chat.rendering.PreparedText
            val stamp = android.graphics.RenderNode("Failure retry fixture")
            fun bind(owner: SilentVideoPreview) {
                owner.bind(a, android.graphics.RectF(0f, 0f, 600f, 400f), false, 12f, false,
                    stamp, prepared, playbackKey = key)
            }
            main {
                val file = media.fileURL(d.relativePath)
                media.remotePlayback = { _, _ ->
                    if (attempts.incrementAndGet() == 1) throw MediaException(MediaError.UNAVAILABLE)
                    MediaPlaybackSource(file = file)
                }
                val row = Message(78004, 2, OrderKey(1, 78004), MessageDirection.INCOMING, 0,
                    MessageKind.VIDEO, "", "", null, 1, listOf(a))
                prepared = dev.kirill.kandinsky.chat.rendering.PreparedText(row,
                    dev.kirill.kandinsky.chat.rendering.TextLayoutKey(row.id, 1, 300, 17, 3))
                pool.beginFrame(listOf(key), 2)
                first = checkNotNull(pool.preview(key) { SilentVideoPreview(activity, media) {}.also { root.addView(it) } })
                bind(first)
            }
            waitFor("The failed request returns its native lease") { attempts.get() == 1 && MediaAdmission.previewOwnerCount == 0 }
            main {
                repeat(10) {
                    pool.beginFrame(listOf(key), 2)
                    val owner = checkNotNull(pool.preview(key) { SilentVideoPreview(activity, media) {} })
                    assertSame(first, owner)
                    bind(owner)
                }
            }
            SystemClock.sleep(100)
            assertEquals("Failure must not start an automatic request loop", 1, attempts.get())
            lateinit var returned: SilentVideoPreview
            main {
                pool.beginFrame(emptyList(), 2)
                assertTrue("A failed owner leaves the retained set when scrolled away", first.closed)
                pool.beginFrame(listOf(key), 2)
                returned = checkNotNull(pool.preview(key) { SilentVideoPreview(activity, media) {}.also { root.addView(it) } })
                assertNotSame(first, returned)
                bind(returned)
            }
            waitFor("Returning opens actual native video again") { returned.active }
            assertEquals(2, attempts.get())
            main { prepared.close(); stamp.discardDisplayList() }
        } finally {
            main { pool.clear(); media.close(); activity.finish() }
            waitFor("All retry preview leases retire") { MediaAdmission.previewOwnerCount == 0 }
            h.close()
        }
    }

    @Test
    fun evictedVideoReturnsOnItsOwnRenderedFrameAndSavedPlayhead() {
        val activity = emptyActivity()
        val h = RepositoryHarness.open(File(context.cacheDir, "evicted-video-" + UUID.randomUUID()))
        lateinit var media: MediaStore
        lateinit var scene: dev.kirill.kandinsky.chat.rendering.RetainedChatScene
        lateinit var root: FrameLayout
        main {
            media = MediaStore(h.store, h.repo)
            root = FrameLayout(activity)
            activity.setContentView(root)
            scene = dev.kirill.kandinsky.chat.rendering.RetainedChatScene()
            scene.mediaStore = media
            scene.movingContainer = root
            scene.viewport = android.graphics.RectF(0f, 0f, 1080f, 2000f)
            scene.imageViewport = scene.viewport
        }
        try {
            val d = awaitResult<AttachmentDraft> { cb ->
                main { media.stage({ TestFixtures.assets.open("media/video-00.mp4") }, "video/mp4", cb) }
                Cancellation {}
            }
            val a = Attachment(d.id, d.relativePath, d.mime, d.byteSize, d.rawWidth, d.rawHeight,
                d.orientation, d.durationMs, d.thumbnailKey)
            fun show(id: Long): SilentVideoPreview {
                val row = Message(id, 2, OrderKey(id, id), MessageDirection.INCOMING, 0,
                    MessageKind.VIDEO, "", "", null, 1, listOf(a))
                val prepared = dev.kirill.kandinsky.chat.rendering.PreparedText(row,
                    dev.kirill.kandinsky.chat.rendering.TextLayoutKey(id, 1, 300, 17, 3))
                val fragment = dev.kirill.kandinsky.chat.rendering.SceneFragment(prepared, 0,
                    android.graphics.RectF(36f, 400f, 36f + prepared.width, 400f + prepared.height))
                scene.apply(dev.kirill.kandinsky.chat.rendering.SceneSnapshot(scene.ticket, listOf(fragment)))
                val nodes = scene.javaClass.getDeclaredField("nodes").apply { isAccessible = true }.get(scene) as Map<*, *>
                return checkNotNull((nodes.values.single() as dev.kirill.kandinsky.chat.rendering.MessageNode).video)
            }
            fun session(owner: SilentVideoPreview): Any = SilentVideoPreview::class.java
                .getDeclaredField("session").apply { isAccessible = true }.get(owner)!!
            lateinit var first: SilentVideoPreview
            main { first = show(78001) }
            waitFor("Use an actual rendered video") { first.active }
            lateinit var native: Any
            main { native = session(first) }
            val until = SystemClock.elapsedRealtime() + 4000
            while (nativePosition(native) < 700 && SystemClock.elapsedRealtime() < until) SystemClock.sleep(20)
            main { show(78002) }
            val paused = nativePosition(native)
            assertTrue("Save a nonzero actual time before eviction", paused >= 650)
            lateinit var expected: android.graphics.Bitmap
            main { expected = checkNotNull((first.getChildAt(0) as TextureView).bitmap) }
            main { show(78003); assertTrue("Third distinct message evicts paused decoder", first.closed) }
            SystemClock.sleep(200)
            lateinit var returned: SilentVideoPreview
            main {
                returned = show(78001)
                assertNotSame(first, returned)
                val frame = scene.preview(78001)
                assertNotNull("First binding turn shows its saved frame while replacement prepares", frame)
                try {
                    val actual = checkNotNull(frame?.bitmap)
                    val wanted = android.graphics.Bitmap.createScaledBitmap(expected, 32, 18, true)
                    val shown = android.graphics.Bitmap.createScaledBitmap(actual, 32, 18, true)
                    var difference = 0L
                    for (y in 0 until 18) for (x in 0 until 32) {
                        val left = wanted.getPixel(x, y); val right = shown.getPixel(x, y)
                        difference += kotlin.math.abs(android.graphics.Color.red(left) - android.graphics.Color.red(right))
                        difference += kotlin.math.abs(android.graphics.Color.green(left) - android.graphics.Color.green(right))
                        difference += kotlin.math.abs(android.graphics.Color.blue(left) - android.graphics.Color.blue(right))
                    }
                    val mean = difference.toDouble() / (32 * 18 * 3)
                    println("KANDINSKY_VIDEO_RETURN_FRAME mean=$mean saved=$paused")
                    assertTrue("Saved decoded scene must replace the initial poster: $mean", mean < 4)
                    if (wanted !== expected) wanted.recycle()
                    if (shown !== actual) shown.recycle()
                } finally { frame?.close(); expected.recycle() }
            }
            waitFor("Replacement native output becomes ready") { returned.active }
            lateinit var next: Any
            main { next = session(returned) }
            val resumed = nativePosition(next)
            assertEquals("Replacement seeks to this message's saved time before presentation",
                paused.toDouble(), resumed.toDouble(), 150.0)
        } finally {
            main { scene.close(); media.close(); activity.finish() }
            waitFor("Native leases all retire") { MediaAdmission.previewOwnerCount == 0 }
            h.close()
        }
    }

    // Проверку запускаем без сохранённого профиля, чтобы она не открыла сеанс на рабочем сервере.
    private fun emptyActivity(): MainActivity {
        val context = i.targetContext
        check(context.packageName.endsWith(".cleanup")) { "Нужна отдельная проверочная установка" }
        check(dev.kirill.kandinsky.chat.network.NetworkCredentials(context).load() == null) {
            "Проверка требует пустую установку без аккаунта"
        }
        val device = UiDevice.getInstance(i)
        device.wakeUp()
        androidx.test.uiautomator.Configurator.getInstance().waitForIdleTimeout = 0
        val activity =
            i.startActivitySync(
                context.packageManager.getLaunchIntentForPackage(context.packageName)!!.apply {
                    addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                    )
                }
            ) as MainActivity
        val until = SystemClock.uptimeMillis() + 5000
        var ready = false
        while (!ready && SystemClock.uptimeMillis() < until) {
            main {
                val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                ready =
                    activity.isForeground &&
                        !activity.isDestroyed &&
                        !activity.isFinishing &&
                        (0 until content.childCount).any {
                            content.getChildAt(it) is
                                dev.kirill.kandinsky.chat.account.AccountEntryView
                        }
            }
            if (!ready) SystemClock.sleep(10)
        }
        if (!ready)
            main {
                val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                val children =
                    (0 until content.childCount).map { content.getChildAt(it).javaClass.name }
                println(
                    "KANDINSKY_CACHE_HOST children=$children foreground=${activity.isForeground}" +
                        " focus=${activity.hasWindowFocus()} destroyed=${activity.isDestroyed}" +
                        " finishing=${activity.isFinishing}"
                )
            }
        if (!ready) {
            main { activity.finish() }
            i.waitForIdleSync()
            error("Не открылась пустая форма входа")
        }
        return activity
    }

    private fun nativePosition(native: Any): Long {
        val worker = native.javaClass.getDeclaredField("worker")
            .apply { isAccessible = true }.get(native) as android.os.Handler
        var value = -1L
        val done = CountDownLatch(1)
        worker.post {
            val player = native.javaClass.getDeclaredField("native")
                .apply { isAccessible = true }.get(native) as android.media.MediaPlayer?
            value = player?.currentPosition?.toLong() ?: -1L
            done.countDown()
        }
        assertTrue(done.await(2, TimeUnit.SECONDS))
        return value
    }

    private val i = InstrumentationRegistry.getInstrumentation()
    private val context = i.targetContext

    private fun main(action: () -> Unit) = i.runOnMainSync(action)

    private fun waitFor(description: String, seconds: Long = 5, check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + seconds * 1000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            main { ready = check() }
            if (ready) return
            SystemClock.sleep(20)
        }
        throw AssertionError(description)
    }

    @Test
    fun scrollAwayAndBackKeepsNativeVideoFrameSessionAndPausedPlayhead() {
        val activity = emptyActivity()
        val h = RepositoryHarness.open(File(context.cacheDir, "scroll-continuity-" + UUID.randomUUID()))
        lateinit var media: MediaStore
        lateinit var scene: dev.kirill.kandinsky.chat.rendering.RetainedChatScene
        lateinit var root: FrameLayout
        main {
            media = MediaStore(h.store, h.repo)
            root = FrameLayout(activity)
            activity.setContentView(root)
            scene = dev.kirill.kandinsky.chat.rendering.RetainedChatScene()
            scene.mediaStore = media
            scene.movingContainer = root
        }
        try {
            val d = awaitResult<AttachmentDraft> { cb ->
                main { media.stage({ TestFixtures.assets.open("media/video-00.mp4") }, "video/mp4", cb) }
                Cancellation {}
            }
            val a = Attachment(d.id, d.relativePath, d.mime, d.byteSize, d.rawWidth, d.rawHeight,
                d.orientation, d.durationMs, d.thumbnailKey)
            val row = Message(77001, 2, OrderKey(1, 77001), MessageDirection.INCOMING, 0,
                MessageKind.VIDEO, "", "", null, 1, listOf(a))
            lateinit var prepared: dev.kirill.kandinsky.chat.rendering.PreparedText
            lateinit var fragment: dev.kirill.kandinsky.chat.rendering.SceneFragment
            val viewport = android.graphics.RectF(0f, 0f, 1080f, 2000f)
            fun show(rect: android.graphics.RectF) {
                scene.viewport = rect
                scene.imageViewport = rect
                scene.apply(dev.kirill.kandinsky.chat.rendering.SceneSnapshot(scene.ticket, listOf(fragment)))
            }
            lateinit var owner: SilentVideoPreview
            main {
                prepared = dev.kirill.kandinsky.chat.rendering.PreparedText(row,
                    dev.kirill.kandinsky.chat.rendering.TextLayoutKey(77001, 1, 300, 17, 3))
                fragment = dev.kirill.kandinsky.chat.rendering.SceneFragment(prepared, 0,
                    android.graphics.RectF(36f, 400f, 36f + prepared.width, 400f + prepared.height))
                show(viewport)
                owner = root.getChildAt(0) as SilentVideoPreview
            }
            waitFor("Real native preview must render before scrolling") { owner.active }
            SystemClock.sleep(600)
            lateinit var native: Any
            main {
                native = SilentVideoPreview::class.java.getDeclaredField("session")
                    .apply { isAccessible = true }.get(owner)!!
                show(android.graphics.RectF(viewport).apply { offset(0f, 5000f) })
                assertFalse("Leaving autoplay visibility must pause, not destroy, retained video", owner.closed)
                assertSame(native, SilentVideoPreview::class.java.getDeclaredField("session")
                    .apply { isAccessible = true }.get(owner))
            }
            val paused = nativePosition(native)
            SystemClock.sleep(500)
            assertEquals("Paused native playhead remains still", paused.toDouble(), nativePosition(native).toDouble(), 80.0)
            val before = owner.frameCallbacks
            SystemClock.sleep(500)
            main {
                assertEquals("Paused preview must stop rendering new decoded frames", before, owner.frameCallbacks)
                show(viewport)
                val nodes = scene.javaClass.getDeclaredField("nodes").apply { isAccessible = true }.get(scene) as Map<*, *>
                val node = nodes.values.single() as dev.kirill.kandinsky.chat.rendering.MessageNode
                assertSame("First return reuses the exact output", owner, node.video)
                assertSame(native, SilentVideoPreview::class.java.getDeclaredField("session")
                    .apply { isAccessible = true }.get(owner))
                assertTrue("No tiny while owned output is ready", owner.active)
            }
            waitFor("The same output resumes decoded video frames") { owner.frameCallbacks > before + 1 }
        } finally {
            main { scene.close(); media.close(); activity.finish() }
            waitFor("All actual native preview owners retire") { MediaAdmission.previewOwnerCount == 0 }
            h.close()
        }
    }

    @Test
    fun cachedRoundPlaybackKeepsRowAndFollowingMessageStable() {
        val activity = emptyActivity()
        val h =
            RepositoryHarness.open(
                File(context.cacheDir, "round-cached-height-" + UUID.randomUUID())
            )
        val admission = MediaAdmission()
        lateinit var media: MediaStore
        lateinit var c: MediaCoordinator
        lateinit var host: dev.kirill.kandinsky.chat.rendering.ChatScrollHost
        main {
            media = MediaStore(h.store, h.repo)
            c = MediaCoordinator(admission, media)
            host = dev.kirill.kandinsky.chat.rendering.ChatScrollHost(activity)
            activity.setContentView(host)
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            host.configureMedia(media)
            host.configurePlayback(c, media)
        }
        val states = JSONArray()
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
            val round =
                awaitResult<MessageDelta> {
                        h.repo.send(
                            SendCommand(
                                UUID.randomUUID().toString(),
                                2,
                                MessageKind.ROUND,
                                attachments = listOf(draft),
                            ),
                            it,
                        )
                    }
                    .inserted
                    .single()
            val next =
                awaitResult<MessageDelta> {
                        h.repo.send(
                            SendCommand(
                                UUID.randomUUID().toString(),
                                2,
                                text = "after-round-height-oracle",
                            ),
                            it,
                        )
                    }
                    .inserted
                    .single()
            awaitResult<Unit> { cb ->
                main {
                    host.open(2, h.repo, null, cb)
                    host.setMediaVisible(true)
                }
                Cancellation {}
            }
            fun layout(): dev.kirill.kandinsky.chat.rendering.PreparedText? {
                val retained =
                    host.javaClass.getDeclaredField("scene").apply { isAccessible = true }.get(host)
                val nodes =
                    retained.javaClass
                        .getDeclaredField("nodes")
                        .apply { isAccessible = true }
                        .get(retained) as Map<*, *>
                return nodes.values
                    .filterIsInstance<dev.kirill.kandinsky.chat.rendering.MessageNode>()
                    .firstOrNull { it.fragment.id.messageID == round.id }
                    ?.fragment
                    ?.prepared
            }
            waitFor("Compact round must be laid out before playback") {
                layout() != null && host.loadedCount == 2
            }
            lateinit var compact: dev.kirill.kandinsky.chat.rendering.PreparedText
            lateinit var index: dev.kirill.kandinsky.chat.rendering.HistoryIndex
            var compactHeight = 0.0
            var followingTop = 0.0
            main {
                compact = checkNotNull(layout())
                index =
                    host.javaClass.getDeclaredField("index").apply { isAccessible = true }.get(host)
                        as dev.kirill.kandinsky.chat.rendering.HistoryIndex
                compactHeight = checkNotNull(index.height(round.id))
                followingTop = checkNotNull(index.top(next.id))
                assertEquals(
                    (compact.height + 6 * context.resources.displayMetrics.density).toDouble(),
                    compactHeight,
                    0.5,
                )
            }
            repeat(3) { cycle ->
                main {
                    c.play(PlaybackTarget(round.attachments[0], round.id, MessageKind.ROUND))
                }
                waitFor("Cycle $cycle: native video starts") { c.isReady && c.isPlaying }
                SystemClock.sleep(150)
                main {
                    assertSame("Playback keeps the retained layout", compact, layout())
                    assertEquals(compactHeight, checkNotNull(index.height(round.id)), 0.5)
                    assertEquals(followingTop, checkNotNull(index.top(next.id)), 0.5)
                    assertEquals(1, c.activePlayerCount)
                    c.pause()
                    c.stop()
                }
                val deadline = SystemClock.elapsedRealtime() + 1000
                var restored = false
                while (SystemClock.elapsedRealtime() < deadline && !restored) {
                    main {
                        restored =
                            layout() === compact &&
                                kotlin.math.abs((index.height(round.id) ?: 0.0) - compactHeight) <
                                    0.5
                    }
                    if (!restored) SystemClock.sleep(10)
                }
                main {
                    states.put(
                        JSONObject()
                            .put("cycle", cycle)
                            .put("compactHeight", compactHeight)
                            .put("indexedAfterStop", index.height(round.id))
                            .put("followingBefore", followingTop)
                            .put("followingAfter", index.top(next.id))
                    )
                    assertSame(
                        "Reuse compact cached layout, not a replacement that masks the bug",
                        compact,
                        layout(),
                    )
                    assertTrue("Cached compact drawing must also restore indexed height", restored)
                    assertEquals(compactHeight, checkNotNull(index.height(round.id)), 0.5)
                    assertEquals(
                        "Following message must return without an expansion gap",
                        followingTop,
                        checkNotNull(index.top(next.id)),
                        0.5,
                    )
                }
                waitFor("Manual player retires after stop") { c.activePlayerCount == 0 }
            }
        } finally {
            File(context.getExternalFilesDir(null), "media-qa")
                .apply { mkdirs() }
                .resolve("round-cached-height.json")
                .writeText(JSONObject().put("cycles", states).toString(2))
            main {
                host.close()
                c.close()
                media.close()
                admission.close()
                activity.finish()
            }
            i.waitForIdleSync()
            waitFor("Retire round layout probe") {
                MediaAdmission.previewOwnerCount == 0 && c.activePlayerCount == 0
            }
            h.close()
        }
    }

    @Test
    fun mutedRoundPreviewCrossesTwoNativeEndsWithoutExtraOwners() {
        val activity = emptyActivity()
        lateinit var root: FrameLayout
        main {
            root = FrameLayout(activity)
            activity.setContentView(root)
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val h = RepositoryHarness.open(File(context.cacheDir, "preview-loop-" + UUID.randomUUID()))
        lateinit var media: MediaStore
        var owner: SilentVideoPreview? = null
        var prepared: dev.kirill.kandinsky.chat.rendering.PreparedText? = null
        val stamp = android.graphics.RenderNode("Muted round loop test stamp")
        main { media = MediaStore(h.store, h.repo) }
        try {
            val d =
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
            val row =
                Message(
                    993,
                    2,
                    OrderKey(1, 993),
                    MessageDirection.INCOMING,
                    0,
                    MessageKind.ROUND,
                    "",
                    "",
                    null,
                    1,
                    listOf(a),
                )
            main {
                prepared =
                    dev.kirill.kandinsky.chat.rendering.PreparedText(
                        row,
                        dev.kirill.kandinsky.chat.rendering.TextLayoutKey(
                            993,
                            1,
                            500,
                            54,
                            1,
                            context.resources.displayMetrics.density,
                        ),
                    )
                owner = SilentVideoPreview(activity, media) {}
                root.addView(owner, FrameLayout.LayoutParams(500, 500))
                owner!!.bind(
                    a,
                    android.graphics.RectF(0f, 0f, 500f, 500f),
                    true,
                    0f,
                    false,
                    stamp,
                    prepared!!,
                )
            }
            waitFor("Muted native output must present frames") {
                owner!!.active && owner!!.frameCallbacks >= 3
            }
            var session: PlayerSession? = null
            main {
                session =
                    SilentVideoPreview::class
                        .java
                        .getDeclaredField("session")
                        .apply { isAccessible = true }
                        .get(owner) as PlayerSession
            }
            val nativeField =
                PlayerSession::class.java.getDeclaredField("native").apply { isAccessible = true }
            val nativeWorker =
                PlayerSession::class
                    .java
                    .getDeclaredField("worker")
                    .apply { isAccessible = true }
                    .get(session) as android.os.Handler
            var last = 0
            var wraps = 0
            val deadline = SystemClock.elapsedRealtime() + 8500
            while (SystemClock.elapsedRealtime() < deadline && wraps < 2) {
                var position = 0
                val sampled = CountDownLatch(1)
                nativeWorker.post {
                    position =
                        (nativeField.get(session) as android.media.MediaPlayer).currentPosition
                    sampled.countDown()
                }
                assertTrue(
                    "Native time sampled on its owning worker",
                    sampled.await(2, TimeUnit.SECONDS),
                )
                if (last > 1400 && position < 500) wraps++
                last = position
                main { assertEquals(1, MediaAdmission.previewOwnerCount) }
                SystemClock.sleep(20)
            }
            assertEquals("Muted preview must cross two native end boundaries", 2, wraps)
            main {
                assertTrue(owner!!.frameCallbacks >= 30)
                owner!!.close()
            }
            waitFor("The looping preview must retire its native owner") {
                MediaAdmission.previewOwnerCount == 0
            }
        } finally {
            main {
                owner?.close()
                prepared?.close()
                stamp.discardDisplayList()
                media.close()
                activity.finish()
            }
            i.waitForIdleSync()
            waitFor("Preview lease drains") { MediaAdmission.previewOwnerCount == 0 }
            h.close()
        }
    }

    @Test
    fun stampIsDrawnAboveTheFirstActualVideoFrame() {
        val activity = emptyActivity()
        lateinit var root: FrameLayout
        main {
            root = FrameLayout(activity)
            activity.setContentView(root)
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val h = RepositoryHarness.open(File(context.cacheDir, "preview-stamp-" + UUID.randomUUID()))
        lateinit var media: MediaStore
        var owner: SilentVideoPreview? = null
        var prepared: dev.kirill.kandinsky.chat.rendering.PreparedText? = null
        val stamp = android.graphics.RenderNode("Independent four-colour foreground stamp")
        val colors =
            intArrayOf(
                0xffff00ff.toInt(),
                0xff14dc5a.toInt(),
                0xffffff00.toInt(),
                0xff00ffff.toInt(),
            )
        main { media = MediaStore(h.store, h.repo) }
        try {
            val d =
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
            val row =
                awaitResult<MessageDelta> {
                        h.repo.send(
                            SendCommand(
                                UUID.randomUUID().toString(),
                                2,
                                MessageKind.VIDEO,
                                attachments = listOf(d),
                            ),
                            it,
                        )
                    }
                    .inserted
                    .single()
            main {
                prepared =
                    dev.kirill.kandinsky.chat.rendering.PreparedText(
                        row,
                        dev.kirill.kandinsky.chat.rendering.TextLayoutKey(
                            row.id,
                            row.revision,
                            500,
                            54,
                            1,
                            context.resources.displayMetrics.density,
                        ),
                    )
                stamp.setPosition(0, 0, 500, 500)
                stamp.translationY = 100f
                val canvas = stamp.beginRecording(500, 500)
                val paint = android.graphics.Paint()
                try {
                    repeat(4) { n ->
                        paint.color = colors[n]
                        val x = 225f + (n % 2) * 25
                        val y = 225f + (n / 2) * 25
                        canvas.drawRect(x, y, x + 25, y + 25, paint)
                    }
                } finally {
                    stamp.endRecording()
                }
                owner = SilentVideoPreview(activity, media) {}
                root.addView(owner, FrameLayout.LayoutParams(500, 500).apply { topMargin = 100 })
                owner!!.bind(
                    a,
                    android.graphics.RectF(0f, 100f, 500f, 600f),
                    false,
                    12f,
                    false,
                    stamp,
                    prepared!!,
                )
            }
            waitFor("The source must publish actual native frames") {
                owner!!.active && owner!!.frameCallbacks >= 3
            }
            val committed = CountDownLatch(1)
            main {
                root.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
                root.postInvalidateOnAnimation()
            }
            assertTrue(committed.await(5, TimeUnit.SECONDS))
            val location = IntArray(2)
            main { owner!!.getLocationOnScreen(location) }
            // Поэтому отдельно читаются пиксели итогового экрана.
            fun matches(actual: Int, expected: Int) =
                kotlin.math.abs(
                    android.graphics.Color.red(actual) - android.graphics.Color.red(expected)
                ) < 4 &&
                    kotlin.math.abs(
                        android.graphics.Color.green(actual) -
                            android.graphics.Color.green(expected)
                    ) < 4 &&
                    kotlin.math.abs(
                        android.graphics.Color.blue(actual) - android.graphics.Color.blue(expected)
                    ) < 4
            var shown = false
            var pixels = emptyList<Int>()
            val until = SystemClock.elapsedRealtime() + 2000
            while (!shown && SystemClock.elapsedRealtime() < until) {
                val screen = i.uiAutomation.takeScreenshot()
                try {
                    pixels =
                        (0..3).map { n ->
                            screen.getPixel(
                                location[0] + 237 + (n % 2) * 25,
                                location[1] + 237 + (n / 2) * 25,
                            )
                        }
                    shown = pixels.indices.all { matches(pixels[it], colors[it]) }
                } finally {
                    screen.recycle()
                }
                if (!shown) SystemClock.sleep(16)
            }
            val diagnostic = File(context.getExternalFilesDir(null), "media-qa").apply { mkdirs() }
            diagnostic
                .resolve("stamp-visible.json")
                .writeText(
                    JSONObject()
                        .put("presented", shown)
                        .put("pixels", JSONArray(pixels.map { Integer.toHexString(it) }))
                        .put("expected", JSONArray(colors.map { Integer.toHexString(it) }))
                        .toString(2)
                )
            assertTrue(
                "A foreground stamp must be presented above actual native video output",
                shown,
            )
            val displaced = JSONArray()
            for (top in listOf(260, 180, 420)) {
                main {
                    stamp.translationY = top.toFloat()
                    // Принудительная новая раскладка могла бы скрыть устаревший сдвиг рисунка.
                    owner!!.bind(
                        a,
                        android.graphics.RectF(0f, top.toFloat(), 500f, top + 500f),
                        false,
                        12f,
                        false,
                        stamp,
                        prepared!!,
                    )
                }
                val frame = CountDownLatch(1)
                main {
                    root.viewTreeObserver.registerFrameCommitCallback { frame.countDown() }
                    root.postInvalidateOnAnimation()
                }
                assertTrue("Displaced native frame must commit", frame.await(5, TimeUnit.SECONDS))
                main { owner!!.getLocationOnScreen(location) }
                var aligned = false
                val deadline = SystemClock.elapsedRealtime() + 1500
                while (!aligned && SystemClock.elapsedRealtime() < deadline) {
                    val screen = i.uiAutomation.takeScreenshot()
                    try {
                        pixels =
                            (0..3).map { n ->
                                screen.getPixel(
                                    location[0] + 237 + (n % 2) * 25,
                                    location[1] + 237 + (n / 2) * 25,
                                )
                            }
                        aligned = pixels.indices.all { matches(pixels[it], colors[it]) }
                    } finally {
                        screen.recycle()
                    }
                    if (!aligned) SystemClock.sleep(16)
                }
                displaced.put(
                    JSONObject()
                        .put("top", top)
                        .put("aligned", aligned)
                        .put("pixels", JSONArray(pixels.map { Integer.toHexString(it) }))
                )
                diagnostic
                    .resolve("stamp-moving.json")
                    .writeText(JSONObject().put("positions", displaced).toString(2))
                assertTrue(
                    "All four foreground pixels must follow native preview after moving to $top; got $pixels",
                    aligned,
                )
            }
        } finally {
            main {
                owner?.close()
                prepared?.close()
                stamp.discardDisplayList()
                media.close()
                activity.finish()
            }
            i.waitForIdleSync()
            waitFor("Retire stamp probe source") { MediaAdmission.previewOwnerCount == 0 }
            h.close()
        }
    }

    @Test
    fun changingSourceWithBothPreviewSlotsOccupiedRetiresOldNativeOutput() {
        val activity = emptyActivity()
        lateinit var root: FrameLayout
        main {
            root = FrameLayout(activity)
            activity.setContentView(root)
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val h =
            RepositoryHarness.open(File(context.cacheDir, "preview-rebind-" + UUID.randomUUID()))
        lateinit var media: MediaStore
        val owners = mutableListOf<SilentVideoPreview>()
        var prepared: dev.kirill.kandinsky.chat.rendering.PreparedText? = null
        val stamp = android.graphics.RenderNode("Preview rebind stamp")
        main { media = MediaStore(h.store, h.repo) }
        try {
            fun stage() =
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
            fun attachment(d: AttachmentDraft) =
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
            val d = stage()
            val a = attachment(d)
            val next = attachment(stage())
            assertNotEquals(a.relativePath, next.relativePath)
            val row =
                awaitResult<MessageDelta> {
                        h.repo.send(
                            SendCommand(
                                UUID.randomUUID().toString(),
                                2,
                                MessageKind.ROUND,
                                attachments = listOf(d),
                            ),
                            it,
                        )
                    }
                    .inserted
                    .single()
            main {
                prepared =
                    dev.kirill.kandinsky.chat.rendering.PreparedText(
                        row,
                        dev.kirill.kandinsky.chat.rendering.TextLayoutKey(
                            row.id,
                            row.revision,
                            500,
                            54,
                            1,
                            context.resources.displayMetrics.density,
                        ),
                    )
                repeat(2) { n ->
                    val owner = SilentVideoPreview(activity, media) {}
                    owners += owner
                    root.addView(
                        owner,
                        FrameLayout.LayoutParams(500, 500).apply {
                            leftMargin = n * 500
                            topMargin = 100
                        },
                    )
                    owner.bind(
                        a,
                        android.graphics.RectF(n * 500f, 100f, n * 500f + 500, 600f),
                        n == 0,
                        12f,
                        false,
                        stamp,
                        prepared!!,
                    )
                }
            }
            waitFor("Both actual native outputs must be ready") { owners.all { it.active } }
            main {
                assertEquals(2, MediaAdmission.previewOwnerCount)
                owners[0].bind(
                    next,
                    android.graphics.RectF(0f, 100f, 500f, 600f),
                    true,
                    12f,
                    false,
                    stamp,
                    prepared!!,
                )
                assertTrue(
                    "A changed source must retire its previous native owner even when both slots are occupied",
                    owners[0].closed,
                )
                assertFalse(owners[0].active)
                assertNull(owners[0].parent)
                assertTrue(owners[1].active)
            }
        } finally {
            main {
                owners.forEach { it.close() }
                prepared?.close()
                stamp.discardDisplayList()
                media.close()
                activity.finish()
            }
            i.waitForIdleSync()
            waitFor("All source reservations must retire") { MediaAdmission.previewOwnerCount == 0 }
            h.close()
        }
    }

    @Test
    fun hundredSilentNativePreviewLifecyclesReleaseOwnersAndOutputs() {
        val activity = emptyActivity()
        lateinit var root: FrameLayout
        main {
            root = FrameLayout(activity)
            activity.setContentView(root)
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val h = RepositoryHarness.open(File(context.cacheDir, "preview-soak-" + UUID.randomUUID()))
        val admission = MediaAdmission()
        lateinit var media: MediaStore
        main { media = MediaStore(h.store, h.repo) }
        val samples = JSONArray()
        var frames = 0
        var completed = 0
        var captures = 0
        var peak = 0
        fun sample(phase: String) {
            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            samples.put(
                JSONObject()
                    .put("phase", phase)
                    .put("cycle", completed)
                    .put("pssKiB", info.totalPss)
                    .put("nativeHeapBytes", Debug.getNativeHeapAllocatedSize())
                    .put(
                        "javaUsedBytes",
                        Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
                    )
            )
        }
        try {
            val d =
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
            val row =
                awaitResult<MessageDelta> {
                        h.repo.send(
                            SendCommand(
                                UUID.randomUUID().toString(),
                                2,
                                MessageKind.ROUND,
                                attachments = listOf(d),
                            ),
                            it,
                        )
                    }
                    .inserted
                    .single()
            sample("before")
            repeat(100) { cycle ->
                lateinit var owner: SilentVideoPreview
                lateinit var prepared: dev.kirill.kandinsky.chat.rendering.PreparedText
                val stamp = android.graphics.RenderNode("Preview soak stamp")
                main {
                    prepared =
                        dev.kirill.kandinsky.chat.rendering.PreparedText(
                            row,
                            dev.kirill.kandinsky.chat.rendering.TextLayoutKey(
                                row.id,
                                row.revision,
                                600,
                                54,
                                1,
                                context.resources.displayMetrics.density,
                            ),
                        )
                    owner = SilentVideoPreview(activity, media) {}
                    root.addView(owner, FrameLayout.LayoutParams(500, 500))
                    owner.bind(
                        a,
                        android.graphics.RectF(0f, 0f, 500f, 500f),
                        cycle % 2 == 0,
                        12f,
                        false,
                        stamp,
                        prepared,
                    )
                }
                waitFor("Actual native preview frame in cycle $cycle") {
                    owner.active && owner.frameCallbacks > 0
                }
                main {
                    val texture =
                        (0 until owner.childCount)
                            .map { owner.getChildAt(it) }
                            .filterIsInstance<TextureView>()
                            .single()
                    val image = checkNotNull(texture.getBitmap(16, 16))
                    try {
                        assertTrue(
                            "Native output must contain actual opaque source pixels",
                            (0 until 16).any { image.getPixel(it, it) ushr 24 > 0 },
                        )
                        frames++
                    } finally {
                        image.recycle()
                    }
                    peak = maxOf(peak, MediaAdmission.previewOwnerCount)
                }
                if (cycle % 3 == 0) {
                    val ready = CountDownLatch(1)
                    var capture: CaptureLease? = null
                    main {
                        admission.acquire(CaptureKind.PHOTO) {
                            capture = it.getOrThrow()
                            ready.countDown()
                        }
                    }
                    assertTrue(ready.await(5, TimeUnit.SECONDS))
                    main {
                        assertEquals(0, MediaAdmission.previewOwnerCount)
                        capture!!.release()
                    }
                    captures++
                } else main { if (cycle % 3 == 1) media.trimMemory() else owner.close() }
                waitFor("Actual native retirement in cycle $cycle") {
                    MediaAdmission.previewOwnerCount == 0
                }
                main {
                    assertTrue(owner.closed)
                    assertNull(owner.parent)
                    assertFalse(owner.active)
                    prepared.close()
                    stamp.discardDisplayList()
                }
                completed++
                if (completed % 10 == 0) sample("cycle")
            }
            main {
                media.close()
                admission.close()
            }
            h.close()
            sample("closed_0s")
            SystemClock.sleep(5000)
            sample("closed_5s")
            SystemClock.sleep(25000)
            sample("closed_30s")
            assertEquals(100, completed)
            assertEquals(100, frames)
            assertTrue(peak <= 2)
        } finally {
            main {
                media.close()
                admission.close()
                activity.finish()
            }
            i.waitForIdleSync()
            h.close()
            File(context.getExternalFilesDir(null), "media-qa")
                .apply { mkdirs() }
                .resolve("preview-hundred.json")
                .writeText(
                    JSONObject()
                        .put("cycles", completed)
                        .put("nativeFramesVerified", frames)
                        .put("captureRetirements", captures)
                        .put("peakPreviewOwners", peak)
                        .put("forcedGC", false)
                        .put("samples", samples)
                        .toString(2)
                )
        }
    }

    // Готовый непрозрачный показ не должен заново рисовать скрытый под ним предварительный кадр или
    // перехватывать нажатие его загрузкой.
    @Test
    fun visibleRoundAndVideoAutoplayMutedActualFramesAndRetireBeforeManualPlayer() {
        val activity = emptyActivity()
        val h = RepositoryHarness.open(File(context.cacheDir, "silent-native-" + UUID.randomUUID()))
        val admission = MediaAdmission()
        lateinit var media: MediaStore
        lateinit var c: MediaCoordinator
        lateinit var host: dev.kirill.kandinsky.chat.rendering.ChatScrollHost
        val audio =
            context.getSystemService(android.content.Context.AUDIO_SERVICE)
                as android.media.AudioManager
        val focusLoss = java.util.concurrent.atomic.AtomicInteger()
        val competitor =
            android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                .setOnAudioFocusChangeListener(
                    { value -> if (value < 0) focusLoss.incrementAndGet() },
                    android.os.Handler(android.os.Looper.getMainLooper()),
                )
                .build()
        main {
            media = MediaStore(h.store, h.repo)
            c = MediaCoordinator(admission, media)
            host = dev.kirill.kandinsky.chat.rendering.ChatScrollHost(activity)
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity.setContentView(host)
            host.configureMedia(media)
            host.configurePlayback(c, media)
            assertEquals(
                android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
                audio.requestAudioFocus(competitor),
            )
        }
        try {
            val d =
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
            val first =
                awaitResult<MessageDelta> {
                        h.repo.send(
                            SendCommand(
                                UUID.randomUUID().toString(),
                                2,
                                MessageKind.ROUND,
                                attachments = listOf(d),
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
                                attachments = listOf(d.copy(id = UUID.randomUUID().toString())),
                            ),
                            it,
                        )
                    }
                    .inserted
                    .single()
            awaitResult<Unit> { cb ->
                main {
                    host.open(2, h.repo, null, cb)
                    host.setMediaVisible(true)
                }
                Cancellation {}
            }
            fun textures(view: android.view.View): List<TextureView> =
                if (view is TextureView) listOf(view)
                else if (view is android.view.ViewGroup)
                    (0 until view.childCount).flatMap { textures(view.getChildAt(it)) }
                else emptyList()
            waitFor("Visible round and video must create native outputs without a user play call") {
                textures(host).count { it.isAvailable && it.alpha > 0.5f } == 2
            }
            lateinit var outputs: List<TextureView>
            main {
                outputs = textures(host).filter { it.isAvailable && it.alpha > 0.5f }
                assertEquals(2, MediaAdmission.previewOwnerCount)
            }
            for (output in outputs) {
                val hashes = mutableSetOf<Int>()
                val until = SystemClock.elapsedRealtime() + 3000
                while (hashes.size < 3 && SystemClock.elapsedRealtime() < until) {
                    main {
                        output.getBitmap(64, 64)?.let { pixels ->
                            val values = IntArray(64 * 64)
                            pixels.getPixels(values, 0, 64, 0, 0, 64, 64)
                            hashes += values.contentHashCode()
                            pixels.recycle()
                        }
                    }
                    SystemClock.sleep(60)
                }
                assertTrue("Actual visible decoded pixels must move", hashes.size >= 3)
            }
            lateinit var retained: dev.kirill.kandinsky.chat.rendering.RetainedChatScene
            main {
                retained =
                    host.javaClass.getDeclaredField("scene").apply { isAccessible = true }.get(host)
                        as dev.kirill.kandinsky.chat.rendering.RetainedChatScene
            }
            waitFor("Retained native posters must be available for fallback") {
                retained.imageReady(first.id) && retained.imageReady(second.id)
            }
            fun posterDraws(): Int {
                val bitmap =
                    android.graphics.Bitmap.createBitmap(
                        64,
                        128,
                        android.graphics.Bitmap.Config.ARGB_8888,
                    )
                val canvas =
                    object : android.graphics.Canvas(bitmap) {
                        var bitmaps = 0

                        override fun drawBitmap(
                            image: android.graphics.Bitmap,
                            matrix: android.graphics.Matrix,
                            paint: android.graphics.Paint?,
                        ) {
                            bitmaps++
                            super.drawBitmap(image, matrix, paint)
                        }
                    }
                try {
                    canvas.scale(64f / host.width, 128f / host.height)
                    retained.drawSoftware(canvas)
                    return canvas.bitmaps
                } finally {
                    bitmap.recycle()
                }
            }
            main {
                assertEquals(
                    "An opaque ready native output must not redraw its covered poster",
                    0,
                    posterDraws(),
                )
            }
            main {
                val nodes =
                    retained.javaClass
                        .getDeclaredField("nodes")
                        .apply { isAccessible = true }
                        .get(retained) as Map<*, *>
                for (node in
                    nodes.values
                        .filterIsInstance<dev.kirill.kandinsky.chat.rendering.MessageNode>()
                        .filter { it.video?.active == true }) {
                    val f = node.fragment
                    val rect =
                        android.graphics.RectF(checkNotNull(f.prepared.mediaRect)).apply {
                            offset(f.frame.left, f.frame.top)
                        }
                    val image = checkNotNull(node.image)
                    image.bind(
                        first.attachments.first(),
                        rect,
                        circular = f.prepared.message.kind == MessageKind.ROUND,
                        maxPixel = 512,
                        kind = f.prepared.message.kind,
                    )
                    assertTrue(
                        "Exercise an asynchronous poster upgrade below a ready native output",
                        image.loading,
                    )
                    assertFalse(
                        "An invisible loading control below a ready video must not consume its playback tap",
                        retained.performLoadAction(rect.centerX(), rect.centerY()),
                    )
                }
            }
            val granted = CountDownLatch(1)
            var capture: CaptureLease? = null
            main {
                admission.acquire(CaptureKind.PHOTO) { result ->
                    capture = result.getOrThrow()
                    granted.countDown()
                }
            }
            assertTrue(granted.await(5, TimeUnit.SECONDS))
            main {
                assertEquals(0, MediaAdmission.previewOwnerCount)
                assertEquals(
                    "Retiring native outputs must restore their retained posters before capture",
                    2,
                    posterDraws(),
                )
                capture!!.release()
            }
            assertEquals("Silent previews must not steal audio focus", 0, focusLoss.get())
            main { audio.abandonAudioFocusRequest(competitor) }
            val ready = CountDownLatch(1)
            main {
                c.onChange = { if (c.isReady || c.lastError != null) ready.countDown() }
                c.play(PlaybackTarget(first.attachments.first(), first.id, MessageKind.ROUND))
            }
            assertTrue(ready.await(8, TimeUnit.SECONDS))
            main {
                assertNull(c.lastError)
                assertEquals(0, MediaAdmission.previewOwnerCount)
                assertEquals(1, c.activePlayerCount)
            }
            main { host.setMediaVisible(false) }
        } finally {
            main {
                host.close()
                c.close()
                media.close()
                admission.close()
                audio.abandonAudioFocusRequest(competitor)
                activity.finish()
            }
            waitFor("Closing native previews must release reservations and decoded images") {
                MediaAdmission.previewOwnerCount == 0 &&
                    c.activePlayerCount == 0 &&
                    media.bytesUsed == 0
            }
            h.close()
        }
    }
}
