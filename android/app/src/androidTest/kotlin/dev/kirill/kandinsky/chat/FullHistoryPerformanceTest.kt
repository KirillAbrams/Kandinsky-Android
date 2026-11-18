package dev.kirill.kandinsky.chat

import android.content.Intent
import android.graphics.RenderNode
import android.os.*
import android.view.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.kirill.kandinsky.chat.app.MainActivity
import dev.kirill.kandinsky.chat.media.MediaStore
import dev.kirill.kandinsky.chat.rendering.*
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class FullHistoryPerformanceTest {
    @Test
    fun frameDataCorrelationKeepsTheOriginalDeadlineAfterResync() {
        org.junit.Assume.assumeTrue(Build.VERSION.SDK_INT >= 33)
        val clock = MeasuredFrameTimelineClock()
        clock.remember(120, longArrayOf(130, 7, 140, 8, 150, 9))
        assertEquals(
            "Original deadline selects the original ID, even if a later timeline is now preferred",
            7L,
            clock.idFor(120, 130),
        )
        assertEquals(9L, clock.idFor(120, 150))
        assertEquals(-1L, clock.idFor(120, 160))
        assertEquals(-1L, clock.idFor(119, 130))
    }

    @Test
    fun traverseAndReturn() {
        val i = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(i)
        val originalRotation = device.displayRotation
        val autoRotate =
            android.provider.Settings.System.getInt(
                i.targetContext.contentResolver,
                android.provider.Settings.System.ACCELEROMETER_ROTATION,
                0,
            ) == 1
        var restored = false
        try {
            assertTrue(
                "The benchmark must fix its portrait viewport",
                i.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_0),
            )
            val until = SystemClock.uptimeMillis() + 5000
            while (
                device.displayRotation != Surface.ROTATION_0 && SystemClock.uptimeMillis() < until
            ) SystemClock.sleep(20)
            assertEquals(Surface.ROTATION_0, device.displayRotation)
            runTraversal()
        } finally {
            restored =
                i.uiAutomation.setRotation(
                    if (autoRotate) android.app.UiAutomation.ROTATION_UNFREEZE else originalRotation
                )
            val report =
                File(
                    i.targetContext.getExternalFilesDir(null),
                    "kandinsky-full-history/summary.json",
                )
            if (report.exists())
                report.writeText(
                    JSONObject(report.readText())
                        .put("orientationLock", "portrait for benchmark only")
                        .put("originalRotation", originalRotation)
                        .put("autoRotatePrior", autoRotate)
                        .put("rotationPolicyRestored", restored)
                        .toString()
                )
            assertTrue("Restore the prior rotation policy", restored)
        }
    }

    // Сохраняет ограниченные измерения ресурсов и наблюдает освобождение истории после закрытия.
    private fun runTraversal() {
        val i = InstrumentationRegistry.getInstrumentation()
        val d = UiDevice.getInstance(i)
        val previousIdle = Configurator.getInstance().waitForIdleTimeout
        Configurator.getInstance().waitForIdleTimeout = 0
        val out =
            File(i.targetContext.getExternalFilesDir(null), "kandinsky-full-history").apply {
                mkdirs()
            }
        val clock =
            if (
                Build.VERSION.SDK_INT >= 33 &&
                    InstrumentationRegistry.getArguments().getString("frame-clock") != "off"
            )
                MeasuredFrameTimelineClock()
            else null
        val phase = AtomicReference("setup")
        val phases = JSONArray()
        var success = false
        val frameThread = HandlerThread("Kandinsky-FullHistory-FrameObserver").apply { start() }
        val frames = File(out, "frames.csv").bufferedWriter(bufferSize = 131072)
        frames.write(
            "phase,intendedVsyncNs,totalNs,deadlineNs,gpuNs,firstDraw,frameTimelineVsyncId,vsyncNs,lostSincePrevious\n"
        )
        val snapshots = File(out, "viewports.jsonl").bufferedWriter(bufferSize = 32768)
        val dropped = AtomicLong()
        val count = AtomicLong()
        val ups = AtomicLong()
        val touches = AtomicLong()
        val inputLedger = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val activeDown = AtomicLong(-1)
        val receivedOwnUp = AtomicLong(-1)
        val foreignTouches = AtomicLong()
        var host: ChatScrollHost? = null
        var closedHost: WeakReference<ChatScrollHost>? = null
        val fields =
            ChatScrollHost::class
                .java
                .declaredFields
                .associateBy { it.name }
                .also { it.values.forEach { f -> f.isAccessible = true } }
        fun field(name: String) = fields.getValue(name).get(checkNotNull(host))
        fun index() = field("index") as HistoryIndex
        fun activityState(v: View) = (v.context as? MainActivity)?.isForeground == true
        fun sample(): JSONObject {
            lateinit var result: JSONObject
            i.runOnMainSync {
                val h = host
                result =
                    JSONObject()
                        .put("phase", phase.get())
                        .put("ns", System.nanoTime())
                        .put(
                            "javaUsed",
                            Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
                        )
                        .put("javaHeapSize", Runtime.getRuntime().totalMemory())
                        .put("nativeAllocated", Debug.getNativeHeapAllocatedSize())
                if (h != null) {
                    result
                        .put("windowFocused", h.hasWindowFocus())
                        .put("activityForeground", activityState(h))
                    val idx = index()
                    val offset = fields.getValue("offset").getDouble(h)
                    val range = idx.visible(offset, h.height.toDouble(), 0)
                    val loaded = field("loaded") as Map<*, *>
                    val scene = field("scene") as RetainedChatScene
                    val nodesField =
                        RetainedChatScene::class.java.getDeclaredField("nodes").apply {
                            isAccessible = true
                        }
                    val nodes =
                        (nodesField.get(scene) as Map<*, *>).values.filterIsInstance<MessageNode>()
                    val ready =
                        nodes
                            .filter {
                                it.fragment.frame.bottom > 0 && it.fragment.frame.top < h.height
                            }
                            .map { it.fragment.id.messageID }
                            .toSet()
                    val ids = range.map { idx.rows[it].messageID }
                    result
                        .put("offset", offset)
                        .put("height", h.height)
                        .put("totalHeight", idx.totalHeight)
                        .put("rows", idx.rows.size)
                        .put("first", range.first)
                        .put("last", range.last)
                        .put("loaded", loaded.size)
                        .put("visibleRows", ids.size)
                        .put("visibleLoadedRows", ids.count { loaded.containsKey(it) })
                        .put("visiblePreparedRows", ids.count { it in ready })
                        .put("fragments", scene.metrics.liveFragments)
                        .put("animatedOwners", scene.metrics.activeFrameClocks)
                        .put("pageRequests", field("pageRequests"))
                        .put("pageLoading", field("pageLoading"))
                        .put("textCacheBytes", (field("cache") as FragmentLayoutCache).bytes)
                        .put("imageBytes", (field("viewerMedia") as? MediaStore)?.bytesUsed ?: 0)
                } else result.put("closedHostCollected", closedHost?.get() == null)
            }
            if (phase.get().startsWith("closed_")) {
                val memory = Debug.MemoryInfo()
                Debug.getMemoryInfo(memory)
                result
                    .put("pssKiB", memory.totalPss)
                    .put("memoryStats", JSONObject(memory.memoryStats))
            }
            snapshots.write(result.toString())
            snapshots.newLine()
            snapshots.flush()
            File(out, "progress.json").writeText(result.toString())
            return result
        }
        val listener =
            Window.OnFrameMetricsAvailableListener { _, metrics, lost ->
                dropped.addAndGet(lost.toLong())
                if (count.incrementAndGet() <= 500000) {
                    val currentPhase = phase.get()
                    val intendedVsync = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
                    val totalDuration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                    val deadline = metrics.getMetric(FrameMetrics.DEADLINE)
                    val gpuDuration = metrics.getMetric(FrameMetrics.GPU_DURATION)
                    val firstDraw = metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME)
                    val frameId =
                        if (Build.VERSION.SDK_INT >= 36) {
                            metrics.getMetric(FrameMetrics.FRAME_TIMELINE_VSYNC_ID)
                        } else {
                            clock?.idFor(
                                metrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP),
                                metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP) +
                                    metrics.getMetric(FrameMetrics.DEADLINE),
                            ) ?: -1
                        }
                    val vsync = metrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP)
                    frames.write(
                        "$currentPhase,$intendedVsync,$totalDuration,$deadline," +
                            "$gpuDuration,$firstDraw,$frameId,$vsync,$lost\n"
                    )
                }
            }
        val activity =
            i.startActivitySync(
                i.targetContext.packageManager
                    .getLaunchIntentForPackage(i.targetContext.packageName)!!
                    .apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        putExtra(
                            "metrics",
                            InstrumentationRegistry.getArguments().getString("app-metrics") != "off",
                        )
                    }
            ) as MainActivity
        lateinit var prior: Window.Callback
        lateinit var owned: Window.Callback
        i.runOnMainSync {
            prior = checkNotNull(activity.window.callback)
            owned =
                object : Window.Callback by prior {
                    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                        if (
                            event.source and InputDevice.SOURCE_TOUCHSCREEN ==
                                InputDevice.SOURCE_TOUCHSCREEN
                        ) {
                            if (event.actionMasked != MotionEvent.ACTION_MOVE) {
                                inputLedger.add(
                                    "${SystemClock.uptimeMillis()},${event.actionMasked},${event.downTime}," +
                                        "${event.x},${event.y},device=${event.deviceId},source=${event.source}," +
                                        "flags=${event.flags}"
                                )
                                while (inputLedger.size > 80) inputLedger.poll()
                            }
                            touches.incrementAndGet()
                            if (event.actionMasked == MotionEvent.ACTION_UP) {
                                ups.incrementAndGet()
                                if (event.downTime == activeDown.get())
                                    receivedOwnUp.set(event.downTime)
                            }
                            if (
                                phase.get().startsWith("full_") &&
                                    event.downTime != activeDown.get()
                            )
                                foreignTouches.incrementAndGet()
                        }
                        return prior.dispatchTouchEvent(event)
                    }
                }
            activity.window.callback = owned
            clock?.start()
            activity.window.addOnFrameMetricsAvailableListener(
                listener,
                Handler(frameThread.looper),
            )
        }
        fun find(id: String) =
            d.wait(Until.findObject(By.res(id).enabled(true)), 15000) ?: error("Missing $id")
        var disabledBlurNodes = emptyList<Pair<RenderNode, Float>>()
        fun controlBlur() {
            if (InstrumentationRegistry.getArguments().getString("blur") != "off") return
            i.runOnMainSync {
                val root = checkNotNull(host).parent
                disabledBlurNodes =
                    listOf("headerGlass", "composerGlass").map { name ->
                        val property =
                            root.javaClass.getDeclaredField(name).apply { isAccessible = true }
                        val material = property.get(root)
                        val gpuProperty =
                            material.javaClass.getDeclaredField("gpu").apply { isAccessible = true }
                        val gpu = checkNotNull(gpuProperty.get(material))
                        val node =
                            gpu.javaClass
                                .getDeclaredField("node")
                                .apply { isAccessible = true }
                                .get(gpu) as RenderNode
                        val radius =
                            gpu.javaClass
                                .getDeclaredField("radius")
                                .apply { isAccessible = true }
                                .getFloat(gpu)
                        node.setRenderEffect(null)
                        node to radius
                    }
                (root as View).invalidate()
            }
        }
        val gestureDuration =
            (InstrumentationRegistry.getArguments().getString("gesture-duration-ms") ?: "96")
                .toLong()
                .coerceIn(80, 2000)
        val gestureIdle =
            (InstrumentationRegistry.getArguments().getString("gesture-idle-ms") ?: "800")
                .toLong()
                .coerceIn(0, 1500)
        var injectedGestures = 0
        fun fling(forward: Boolean) {
            val location = IntArray(2)
            var bounds = android.graphics.RectF()
            var centerX = 0f
            i.runOnMainSync {
                checkNotNull(host).let {
                    assertTrue(
                        "Kandinsky must own foreground before injecting",
                        it.hasWindowFocus() && activityState(it),
                    )
                    it.getLocationOnScreen(location)
                    bounds = it.gestureViewport
                    centerX = location[0] + bounds.centerX()
                }
            }
            val from = location[1] + bounds.top + bounds.height() * (if (forward) .9f else .1f)
            val to = location[1] + bounds.top + bounds.height() * (if (forward) .1f else .9f)
            val receipt = ups.get()
            var down = 0L
            val steps = (gestureDuration / 8).toInt().coerceAtLeast(10)
            for (n in 0..steps) {
                if (n > 0) {
                    val wait = down + n * gestureDuration / steps - SystemClock.uptimeMillis()
                    if (wait > 0) SystemClock.sleep(wait)
                }
                val now = SystemClock.uptimeMillis()
                if (n == 0) {
                    down = now
                    activeDown.set(down)
                }
                val e =
                    MotionEvent.obtain(
                            down,
                            now,
                            if (n == 0) MotionEvent.ACTION_DOWN
                            else if (n == steps) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE,
                            centerX,
                            from + (to - from) * n / steps.toFloat(),
                            0,
                        )
                        .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                val accepted =
                    try {
                        i.uiAutomation.injectInputEvent(e, n == 0 || n == steps)
                    } finally {
                        e.recycle()
                    }
                assertTrue("Every real-time system input must be accepted", accepted)
            }
            val end = SystemClock.uptimeMillis() + 2000
            while (receivedOwnUp.get() != down && SystemClock.uptimeMillis() < end) SystemClock
                .sleep(2)
            if (receivedOwnUp.get() != down || foreignTouches.get() != 0L) {
                val detail =
                    sample()
                        .put("expectedDownTime", down)
                        .put("receivedOwnUp", receivedOwnUp.get())
                        .put("ledger", JSONArray(inputLedger.toList()))
                detail.put(
                    "interactive",
                    (i.targetContext.getSystemService(android.content.Context.POWER_SERVICE)
                            as PowerManager)
                        .isInteractive,
                )
                File(out, "input-failure.json").writeText(detail.toString())
            }
            assertEquals("Own UP must reach the real Window.Callback", down, receivedOwnUp.get())
            assertEquals("Unowned input events invalidate this benchmark", 0L, foreignTouches.get())
            injectedGestures++
            if (gestureIdle > 0) SystemClock.sleep(gestureIdle)
        }
        fun traverse(name: String, forward: Boolean, maxSeconds: Long, requiredEndpoint: Boolean) {
            phase.set(name)
            Trace.beginAsyncSection("Kandinsky.Full." + name, 2)
            val start = System.nanoTime()
            val before = injectedGestures
            val initial = sample()
            var last = initial
            val end = SystemClock.uptimeMillis() + maxSeconds * 1000
            var reached = false
            try {
                while (SystemClock.uptimeMillis() < end) {
                    fling(forward)
                    last = sample()
                    reached =
                        if (forward)
                            last.getDouble("totalHeight") -
                                last.getInt("height") -
                                last.getDouble("offset") < 2 &&
                                last.getInt("last") == last.getInt("rows") - 1
                        else last.getDouble("offset") < 2 && last.getInt("first") == 0
                    if (reached) break
                }
                if (requiredEndpoint)
                    assertTrue(
                        "Full direction must reach the actual history endpoint: " + last,
                        reached,
                    )
            } finally {
                Trace.endAsyncSection("Kandinsky.Full." + name, 2)
                phases.put(
                    JSONObject()
                        .put("name", name)
                        .put("startNs", start)
                        .put("endNs", System.nanoTime())
                        .put("gestures", injectedGestures - before)
                        .put("start", initial)
                        .put("end", last)
                        .put("endpointReached", reached)
                        .put("requiredEndpoint", requiredEndpoint)
                )
            }
        }
        try {
            find("chats.list")
            lateinit var database: File
            i.runOnMainSync {
                val store =
                    activity.javaClass
                        .getDeclaredField("store")
                        .apply { isAccessible = true }
                        .get(activity) as dev.kirill.kandinsky.chat.storage.SQLiteStore
                database = File(store.root, "chat.sqlite3")
            }
            val heavyChat =
                android.database.sqlite.SQLiteDatabase.openDatabase(
                        database.path,
                        null,
                        android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
                    )
                    .use { db ->
                        db.rawQuery(
                                "SELECT id " +
                                    "FROM chats " +
                                    "WHERE id=4 OR title=? " +
                                    "ORDER BY CASE WHEN id=4 THEN 0 ELSE 1 END LIMIT 1",
                                arrayOf("Большой чат · 10 000"),
                            )
                            .use {
                                assertTrue(
                                    "The active profile must contain the real heavy room",
                                    it.moveToFirst(),
                                )
                                it.getLong(0)
                            }
                    }
            find("chat.row.$heavyChat")
            phase.set("chat_list")
            sample()
            SystemClock.sleep(3000)
            find("chat.row.$heavyChat").click()
            find("composer.input")
            val historyDeadline = SystemClock.uptimeMillis() + 20000
            while (SystemClock.uptimeMillis() < historyDeadline) {
                var ready = false
                i.runOnMainSync {
                    fun locate(v: View): ChatScrollHost? {
                        if (v is ChatScrollHost) return v
                        if (v is ViewGroup)
                            for (n in 0 until v.childCount) locate(v.getChildAt(n))?.let {
                                return it
                            }
                        return null
                    }
                    host = locate(activity.window.decorView)
                    ready = host != null && index().rows.size >= 10000
                }
                if (ready) break
                SystemClock.sleep(20)
            }
            i.runOnMainSync {
                fun locate(v: View): ChatScrollHost? {
                    if (v is ChatScrollHost) return v
                    if (v is ViewGroup)
                        for (n in 0 until v.childCount) locate(v.getChildAt(n))?.let {
                            return it
                        }
                    return null
                }
                host = checkNotNull(locate(activity.window.decorView))
                assertTrue(index().rows.size >= 10000)
                val backwardOnly =
                    InstrumentationRegistry.getArguments().getString("direction") == "backward"
                checkNotNull(host)
                    .goTo(
                        if (backwardOnly) index().rows.last().messageID
                        else index().rows.first().messageID
                    )
            }
            phase.set("warmup")
            sample()
            SystemClock.sleep(3000)
            controlBlur()
            val calibration =
                InstrumentationRegistry.getArguments().getString("calibrate") == "true"
            if (calibration)
                traverse(
                    "calibration_forward",
                    true,
                    (InstrumentationRegistry.getArguments().getString("calibration-seconds")
                            ?: "30")
                        .toLong(),
                    false,
                )
            else {
                if (InstrumentationRegistry.getArguments().getString("direction") != "backward")
                    traverse("full_forward", true, 1200, true)
                traverse("full_backward", false, 1200, true)
            }
            i.runOnMainSync { clock?.close() }
            phase.set("before_close")
            sample()
            SystemClock.sleep(3000)
            closedHost = WeakReference(host)
            find("chat.back").click()
            host = null
            find("chat.row.$heavyChat")
            phase.set("closed_5s")
            SystemClock.sleep(5000)
            sample()
            phase.set("closed_30s")
            SystemClock.sleep(25000)
            sample()
            success = true
        } finally {
            phase.set("cleanup")
            i.runOnMainSync {
                disabledBlurNodes.forEach { (node, radius) ->
                    node.setRenderEffect(
                        android.graphics.RenderEffect.createBlurEffect(
                            radius * .25f,
                            radius * .25f,
                            android.graphics.Shader.TileMode.CLAMP,
                        )
                    )
                }
                disabledBlurNodes = emptyList()
                clock?.close()
                activity.window.removeOnFrameMetricsAvailableListener(listener)
                if (activity.window.callback === owned) activity.window.callback = prior
            }
            frameThread.quitSafely()
            frameThread.join(5000)
            frames.close()
            snapshots.close()
            File(out, "summary.json")
                .writeText(
                    JSONObject()
                        .put("successful", success)
                        .put("api", Build.VERSION.SDK_INT)
                        .put("pid", Process.myPid())
                        .put(
                            "blur",
                            InstrumentationRegistry.getArguments().getString("blur") ?: "on",
                        )
                        .put(
                            "gestures",
                            "real-time UiAutomation.injectInputEvent; unobscured gestureViewport",
                        )
                        .put("phases", phases)
                        .put("frameReports", count.get())
                        .put("droppedReports", dropped.get())
                        .put("frameCapacity", 500000)
                        .put(
                            "appMetricsEnabled",
                            InstrumentationRegistry.getArguments().getString("app-metrics") != "off",
                        )
                        .put("gestureDurationMs", gestureDuration)
                        .put("gestureIdleMs", gestureIdle)
                        .put(
                            "vsyncIdSource",
                            if (Build.VERSION.SDK_INT >= 36) "FrameMetrics API36"
                            else if (clock != null)
                                "FrameData candidates matched by corrected timestamp and original " +
                                    "FrameMetrics deadline; incomplete on this OEM"
                            else "external gfxinfo correlation required",
                        )
                        .put("vsyncClockCallbacks", clock?.callbacks ?: 0)
                        .put("vsyncClockBookkeepingNs", clock?.bookkeepingNs ?: 0)
                        .put("foreignTouchEvents", foreignTouches.get())
                        .put("injectedGestures", injectedGestures)
                        .put("windowUpReceipts", ups.get())
                        .put("windowTouchReceipts", touches.get())
                        .toString()
                )
            Configurator.getInstance().waitForIdleTimeout = previousIdle
        }
    }
}

@android.annotation.TargetApi(33)
private class MeasuredFrameTimelineClock : Choreographer.VsyncCallback, AutoCloseable {
    private val lock = Any()
    private val ids = android.util.LongSparseArray<LongArray>(2048)
    private var choreographer: Choreographer? = null
    private var active = false
    var callbacks = 0L
        private set

    var bookkeepingNs = 0L
        private set

    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (active) return
        active = true
        choreographer = Choreographer.getInstance()
        choreographer!!.postVsyncCallback(this)
    }

    override fun onVsync(data: Choreographer.FrameData) {
        if (!active) return
        val start = System.nanoTime()
        val time = data.frameTimeNanos
        val offered = data.frameTimelines
        val candidates = LongArray(offered.size * 2)
        for (n in offered.indices) {
            candidates[n * 2] = offered[n].deadlineNanos
            candidates[n * 2 + 1] = offered[n].vsyncId
        }
        remember(time, candidates)
        callbacks++
        bookkeepingNs += System.nanoTime() - start
        choreographer?.postVsyncCallback(this)
    }

    // Под защитой от одновременного доступа сохраняет варианты соответствия для одного времени
    // кадра.
    fun remember(time: Long, candidates: LongArray) {
        synchronized(lock) {
            ids.put(time, candidates)
            if (ids.size() > 2048) ids.removeAt(0)
        }
    }

    fun idFor(time: Long, deadline: Long): Long =
        synchronized(lock) {
            val candidates = ids[time] ?: return@synchronized -1L
            for (n in candidates.indices step 2) if (candidates[n] == deadline)
                return@synchronized candidates[n + 1]
            -1L
        }

    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        active = false
        choreographer?.removeVsyncCallback(this)
        choreographer = null
    }
}
