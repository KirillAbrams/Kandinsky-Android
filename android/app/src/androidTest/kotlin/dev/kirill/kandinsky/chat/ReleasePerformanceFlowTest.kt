package dev.kirill.kandinsky.chat

import android.content.Intent
import android.os.*
import android.view.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.kirill.kandinsky.chat.app.MainActivity
import java.io.File
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class ReleasePerformanceFlowTest {
    @Test
    fun nativeReleasePhases() {
        val i = InstrumentationRegistry.getInstrumentation()
        val d = UiDevice.getInstance(i)
        Configurator.getInstance().waitForIdleTimeout = 0
        val intent =
            i.targetContext.packageManager
                .getLaunchIntentForPackage(i.targetContext.packageName)!!
                .apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra("metrics", true)
                }
        val activity = i.startActivitySync(intent) as MainActivity
        val thread = HandlerThread("Kandinsky-TestFrameObserver").apply { start() }
        val lock = Any()
        val frames = mutableListOf<JSONArray>()
        val phases = JSONArray()
        val phase = java.util.concurrent.atomic.AtomicReference("setup")
        var droppedTotal = 0
        val modeChanges = JSONArray()
        val systemTouches = JSONArray()
        var systemTouchesLost = 0
        val osSamples = JSONArray()
        val continuousRequests = JSONArray()
        lateinit var precedingCallback: Window.Callback
        lateinit var ownedCallback: Window.Callback
        i.runOnMainSync {
            precedingCallback = checkNotNull(activity.window.callback)
            ownedCallback =
                object : Window.Callback by precedingCallback {
                    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                        if (
                            InstrumentationRegistry.getArguments().getString("gestures") ==
                                "os_fling"
                        )
                            synchronized(lock) {
                                if (systemTouches.length() < 4096)
                                    systemTouches.put(
                                        JSONArray()
                                            .put(phase.get())
                                            .put(event.actionMasked)
                                            .put(event.downTime)
                                            .put(event.eventTime)
                                            .put(System.nanoTime())
                                            .put(event.x)
                                            .put(event.y)
                                            .put(event.historySize)
                                    )
                                else systemTouchesLost++
                            }
                        return precedingCallback.dispatchTouchEvent(event)
                    }

                    override fun onWindowAttributesChanged(attributes: WindowManager.LayoutParams) {
                        synchronized(lock) {
                            if (modeChanges.length() < 4096)
                                modeChanges.put(
                                    JSONArray()
                                        .put(phase.get())
                                        .put(System.nanoTime())
                                        .put(attributes.preferredDisplayModeId)
                                )
                        }
                        precedingCallback.onWindowAttributesChanged(attributes)
                    }
                }
            activity.window.callback = ownedCallback
        }
        val listener =
            Window.OnFrameMetricsAvailableListener { _, m, dropped ->
                val row =
                    JSONArray()
                        .put(phase.get())
                        .put(m.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP))
                        .put(m.getMetric(FrameMetrics.TOTAL_DURATION))
                        .put(
                            if (Build.VERSION.SDK_INT >= 31) m.getMetric(FrameMetrics.DEADLINE)
                            else JSONObject.NULL
                        )
                        .put(
                            if (Build.VERSION.SDK_INT >= 31) m.getMetric(FrameMetrics.GPU_DURATION)
                            else JSONObject.NULL
                        )
                        .put(m.getMetric(FrameMetrics.VSYNC_TIMESTAMP))
                        .put(m.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION))
                        .put(m.getMetric(FrameMetrics.DRAW_DURATION))
                        .put(m.getMetric(FrameMetrics.SYNC_DURATION))
                        .put(m.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION))
                        .put(m.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION))
                        .put(
                            if (Build.VERSION.SDK_INT >= 36)
                                m.getMetric(FrameMetrics.FRAME_TIMELINE_VSYNC_ID)
                            else JSONObject.NULL
                        )
                        .put(m.getMetric(FrameMetrics.FIRST_DRAW_FRAME))
                synchronized(lock) {
                    droppedTotal += dropped
                    if (frames.size < 20000) frames.add(row)
                }
            }
        i.runOnMainSync {
            activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
        }
        fun find(id: String) =
            d.wait(Until.findObject(By.res(id).enabled(true)), 15000)
                ?: throw AssertionError("Missing enabled " + id)
        fun tap(id: String) {
            for (n in 0..2) try {
                find(id).click()
                return
            } catch (e: StaleObjectException) {
                if (n == 2) throw e
            }
        }
        fun openHistory(title: String) {
            tap("chat.back")
            val row = d.wait(Until.findObject(By.text(title)), 15000) ?: error("Нет чата $title")
            row.click()
            find("composer.input")
        }
        fun applyPolicyControl() {
            if (InstrumentationRegistry.getArguments().getString("policy") != "off") return
            i.runOnMainSync {
                var found = 0
                fun visit(v: View) {
                    if (v is dev.kirill.kandinsky.chat.rendering.ChatScrollHost) {
                        val field = v.javaClass.getDeclaredField("displayPolicy")
                        field.isAccessible = true
                        (field.get(v) as? dev.kirill.kandinsky.chat.diagnostics.ActiveDisplayPolicy)
                            ?.close()
                        field.set(v, null)
                        assertNull(field.get(v))
                        found++
                    }
                    if (v is ViewGroup) for (n in 0 until v.childCount) visit(v.getChildAt(n))
                }
                visit(activity.window.decorView)
                assertEquals(1, found)
            }
        }
        fun nativeFling(up: Boolean): Float {
            lateinit var host: dev.kirill.kandinsky.chat.rendering.ChatScrollHost
            lateinit var scroller: android.widget.OverScroller
            i.runOnMainSync {
                fun find(v: View): dev.kirill.kandinsky.chat.rendering.ChatScrollHost? {
                    if (v is dev.kirill.kandinsky.chat.rendering.ChatScrollHost) return v
                    if (v is ViewGroup)
                        for (n in 0 until v.childCount) find(v.getChildAt(n))?.let {
                            return it
                        }
                    return null
                }
                host = checkNotNull(find(activity.window.decorView))
                val f = host.javaClass.getDeclaredField("scroller")
                f.isAccessible = true
                scroller = f.get(host) as android.widget.OverScroller
            }
            var velocity = 0f
            var initial = 0
            i.runOnMainSync {
                // Подготовленные события касания используют уже прошедшие моменты времени с равным
                // шагом
                val end = SystemClock.uptimeMillis()
                val down = end - 96
                val origin = host.height * .6f
                for (n in 0..6) {
                    val action =
                        if (n == 0) MotionEvent.ACTION_DOWN
                        else if (n == 6) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
                    val event =
                        MotionEvent.obtain(
                            down,
                            down + n * 16,
                            action,
                            10f,
                            origin + (if (up) -1 else 1) * n * 30f,
                            0,
                        )
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    host.onTouchEvent(event)
                    event.recycle()
                }
                assertFalse("Actual native inertia must start", scroller.isFinished)
                velocity = scroller.currVelocity
                assertTrue(
                    "Actual native velocity must be nonzero; actual=" + velocity,
                    velocity > 100f,
                )
                initial = scroller.currY
            }
            SystemClock.sleep(400)
            i.runOnMainSync {
                assertNotEquals(
                    "SDK physics must actually advance during measured inertia",
                    initial,
                    scroller.currY,
                )
            }
            return velocity
        }
        fun osFling(up: Boolean): Float {
            lateinit var host: dev.kirill.kandinsky.chat.rendering.ChatScrollHost
            lateinit var scroller: android.widget.OverScroller
            lateinit var offsetField: java.lang.reflect.Field
            val location = IntArray(2)
            var origin = 0f
            i.runOnMainSync {
                fun locate(v: View): dev.kirill.kandinsky.chat.rendering.ChatScrollHost? {
                    if (v is dev.kirill.kandinsky.chat.rendering.ChatScrollHost) return v
                    if (v is ViewGroup)
                        for (n in 0 until v.childCount) locate(v.getChildAt(n))?.let {
                            return it
                        }
                    return null
                }
                host = checkNotNull(locate(activity.window.decorView))
                assertTrue(host.isShown && host.height > 200)
                val field = host.javaClass.getDeclaredField("scroller")
                field.isAccessible = true
                scroller = field.get(host) as android.widget.OverScroller
                offsetField =
                    host.javaClass.getDeclaredField("offset").apply { isAccessible = true }
                host.getLocationOnScreen(location)
                origin = location[1] + host.height * .6f
            }
            val touchStart = synchronized(lock) { systemTouches.length() }
            val begin = System.nanoTime()
            var downTime = 0L
            val calls = JSONArray()
            fun send(action: Int, step: Int) {
                val now = SystemClock.uptimeMillis()
                if (action == MotionEvent.ACTION_DOWN) downTime = now
                val event =
                    MotionEvent.obtain(
                        downTime,
                        now,
                        action,
                        location[0] + 10f,
                        origin + (if (up) -1 else 1) * step * 15f,
                        0,
                    )
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                val started = System.nanoTime()
                val accepted =
                    try {
                        i.uiAutomation.injectInputEvent(event, false)
                    } finally {
                        event.recycle()
                    }
                calls.put(
                    JSONArray()
                        .put(action)
                        .put(now)
                        .put(started)
                        .put(System.nanoTime())
                        .put(accepted)
                )
                assertTrue("System input injection must be accepted", accepted)
            }
            send(MotionEvent.ACTION_DOWN, 0)
            val schedule = SystemClock.uptimeMillis()
            for (n in 1..12) {
                val delay = schedule + n * 8 - SystemClock.uptimeMillis()
                if (delay > 0) SystemClock.sleep(delay)
                send(if (n == 12) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE, n)
            }
            val receiptDeadline = SystemClock.uptimeMillis() + 1000
            fun receivedUp() =
                synchronized(lock) {
                    (touchStart until systemTouches.length()).any { n ->
                        val row = systemTouches.getJSONArray(n)
                        row.getInt(1) == MotionEvent.ACTION_UP && row.getLong(2) == downTime
                    }
                }
            while (!receivedUp() && SystemClock.uptimeMillis() < receiptDeadline) SystemClock.sleep(
                2
            )
            assertTrue("OS injected UP must reach the actual window callback", receivedUp())
            var before = 0.0
            var after = 0.0
            var velocity = 0f
            i.runOnMainSync {
                assertFalse("OS input must start actual SDK inertia", scroller.isFinished)
                velocity = scroller.currVelocity
                assertTrue(
                    "OS inertia velocity must exceed100px/s; actual=" + velocity,
                    velocity > 100f,
                )
                before = offsetField.getDouble(host)
            }
            SystemClock.sleep(400)
            i.runOnMainSync { after = offsetField.getDouble(host) }
            assertTrue(
                "Actual viewport must move more than1px after system UP; before=" +
                    before +
                    " after=" +
                    after,
                (after - before) * (if (up) 1 else -1) > 1,
            )
            synchronized(lock) {
                assertTrue("Window touch observation must stay lossless", systemTouchesLost == 0)
                assertTrue("OS gesture receipt budget", osSamples.length() < 512)
                osSamples.put(
                    JSONObject()
                        .put("phase", phase.get())
                        .put("startNs", begin)
                        .put("endNs", System.nanoTime())
                        .put("downTimeMs", downTime)
                        .put("up", up)
                        .put("injected", calls)
                        .put("velocityPxPerSecond", velocity)
                        .put("offsetAfterUp", before)
                        .put("offsetAfterInertia", after)
                        .put("windowReceiptStartIndex", touchStart)
                )
            }
            return velocity
        }
        var controlledView: View? = null
        var previousViewRate = Float.NaN
        var ownedViewRate = Float.NaN
        fun applyViewRateControl() {
            if (InstrumentationRegistry.getArguments().getString("view-rate") != "highest") return
            assertTrue(Build.VERSION.SDK_INT >= 35)
            i.runOnMainSync {
                fun find(v: View): dev.kirill.kandinsky.chat.rendering.ChatScrollHost? {
                    if (v is dev.kirill.kandinsky.chat.rendering.ChatScrollHost) return v
                    if (v is ViewGroup)
                        for (n in 0 until v.childCount) find(v.getChildAt(n))?.let {
                            return it
                        }
                    return null
                }
                val view = checkNotNull(find(activity.window.decorView))
                val current = view.display.mode
                ownedViewRate =
                    view.display.supportedModes
                        .filter {
                            it.physicalWidth == current.physicalWidth &&
                                it.physicalHeight == current.physicalHeight
                        }
                        .maxOf { it.refreshRate }
                previousViewRate = view.requestedFrameRate
                view.requestedFrameRate = ownedViewRate
                controlledView = view
                assertEquals(
                    "Native test-only view-rate intervention must actually apply",
                    ownedViewRate,
                    view.requestedFrameRate,
                    .01f,
                )
            }
        }
        fun runPhase(name: String, seconds: Long, pages: Boolean) {
            phase.set(name)
            android.os.Trace.beginAsyncSection("Kandinsky.Bench." + name, 1)
            val start = System.nanoTime()
            var gestures = 0
            var osFlingCount = 0
            var nativeFlingCount = 0
            var minimumNativeVelocity = Float.MAX_VALUE
            val intervals = JSONArray()
            val end = SystemClock.uptimeMillis() + seconds * 1000
            while (SystemClock.uptimeMillis() < end) {
                val from = if (pages) 950 else 780
                val to = if (pages) 350 else 650
                val up = if (pages) gestures % 10 < 8 else gestures % 2 == 0
                val gestureX = if (name == "video_then_scroll") 10 else 288
                val t0 = System.nanoTime()
                if (
                    InstrumentationRegistry.getArguments().getString("gestures") == "native_fling"
                ) {
                    minimumNativeVelocity = minOf(minimumNativeVelocity, nativeFling(up))
                    nativeFlingCount++
                } else if (
                    InstrumentationRegistry.getArguments().getString("gestures") == "os_fling"
                ) {
                    minimumNativeVelocity = minOf(minimumNativeVelocity, osFling(up))
                    osFlingCount++
                } else
                    assertTrue(
                        d.swipe(
                            gestureX,
                            if (up) from else to,
                            gestureX,
                            if (up) to else from,
                            if (pages) 24 else 35,
                        )
                    )
                intervals.put(JSONArray().put(t0).put(System.nanoTime()))
                gestures++
            }
            if (InstrumentationRegistry.getArguments().getString("gestures") == "os_fling")
                assertEquals(
                    "OS fling driver must execute for every requested gesture",
                    gestures,
                    osFlingCount,
                )
            android.os.Trace.endAsyncSection("Kandinsky.Bench." + name, 1)
            phases.put(
                JSONObject()
                    .put("name", name)
                    .put("startNs", start)
                    .put("endNs", System.nanoTime())
                    .put("gestures", gestures)
                    .put("nativeFlingCount", nativeFlingCount)
                    .put("osFlingCount", osFlingCount)
                    .put(
                        "minimumNativeStartVelocity",
                        if (nativeFlingCount + osFlingCount > 0) minimumNativeVelocity
                        else JSONObject.NULL,
                    )
                    .put("gestureIntervalsNs", intervals)
            )
            phase.set("between")
        }
        val out = File(i.targetContext.filesDir, "task19").apply { mkdirs() }
        val mode = InstrumentationRegistry.getArguments().getString("phase")
        val mediaOnly = mode == "media"
        val pagesOnly = mode == "pages"
        val readyOnly = mode == "ready"
        try {
            find("composer.input")
            openHistory("Длинная история")
            applyPolicyControl()
            applyViewRateControl()
            if (!pagesOnly && !mediaOnly) {
                if (InstrumentationRegistry.getArguments().getString("warmup") == "3")
                    runPhase("warmup", 3, false)
                val seconds =
                    InstrumentationRegistry.getArguments()
                        .getString("seconds")
                        ?.toLong()
                        ?.coerceIn(2, 120) ?: 30
                runPhase("ready_rows", seconds, false)
                if (readyOnly) return
            }
            if (!mediaOnly) {
                runPhase("new_pages", 60, true)
                if (pagesOnly) return
                phase.set("warm_open")
                repeat(20) { openHistory("Длинная история") }
                phase.set("between")
            }
            openHistory("Плотная медиалента")
            val video =
                d.wait(Until.findObject(By.desc("На весь экран")), 15000)
                    ?: error("Нет видео в области просмотра")
            video.click()
            assertTrue(d.wait(Until.hasObject(By.res("player.inline")), 10000))
            tap("player.inline")
            runPhase("video_then_scroll", 20, false)
            d.pressHome()
        } catch (t: Throwable) {
            d.dumpWindowHierarchy(File(out, "release-media-failure.xml"))
            d.takeScreenshot(File(out, "release-media-failure.png"))
            throw t
        } finally {
            i.runOnMainSync {
                activity.window.removeOnFrameMetricsAvailableListener(listener)
                if (Build.VERSION.SDK_INT >= 35)
                    controlledView?.let {
                        if (it.requestedFrameRate == ownedViewRate)
                            it.requestedFrameRate = previousViewRate
                    }
                if (activity.window.callback === ownedCallback)
                    activity.window.callback = precedingCallback
            }
            thread.quitSafely()
            thread.join(5000)
            val data =
                JSONObject()
                    .put(
                        "columns",
                        JSONArray(
                            listOf(
                                "phase",
                                "intendedVsyncNs",
                                "totalNs",
                                "deadlineNs",
                                "gpuNs",
                                "vsyncNs",
                                "unknownDelayNs",
                                "drawNs",
                                "syncNs",
                                "commandIssueNs",
                                "swapNs",
                                "frameTimelineVsyncId",
                                "firstDraw",
                            )
                        ),
                    )
                    .put(
                        "viewRateControl",
                        InstrumentationRegistry.getArguments().getString("view-rate") ?: "normal",
                    )
                    .put(
                        "gestureSource",
                        InstrumentationRegistry.getArguments().getString("gestures")
                            ?: "UiAutomator.swipe",
                    )
                    .put(
                        "policyControl",
                        InstrumentationRegistry.getArguments().getString("policy") ?: "normal",
                    )
                    .put("pid", Process.myPid())
                    .put("api", Build.VERSION.SDK_INT)
                    .put("refreshHz", activity.window.decorView.display.refreshRate)
                    .put("droppedReports", droppedTotal)
                    .put("windowModeChanges", modeChanges)
                    .put("phases", phases)
            synchronized(lock) {
                data.put("frames", JSONArray(frames))
                if (InstrumentationRegistry.getArguments().getString("gestures") == "os_fling")
                    data.put(
                        "osInput",
                        JSONObject()
                            .put(
                                "windowTouchColumns",
                                JSONArray(
                                    listOf(
                                        "phase",
                                        "action",
                                        "downTimeMs",
                                        "eventTimeMs",
                                        "receivedNs",
                                        "x",
                                        "y",
                                        "historySize",
                                    )
                                ),
                            )
                            .put("windowTouches", systemTouches)
                            .put("lostWindowTouchReports", systemTouchesLost)
                            .put("gestures", osSamples),
                    )
            }
            val fileName =
                if (readyOnly) "native-release-frames-ready.json"
                else if (mediaOnly) "native-release-frames-media.json"
                else if (pagesOnly) "native-release-frames-pages.json"
                else "native-release-frames.json"
            File(out, fileName).writeText(data.toString())
            // Числовые отчёты сохраняет только проверочная сборка
            // Внешняя папка доступна и на имитаторе без расширенных системных прав
            i.targetContext.getExternalFilesDir("kandinsky-test-metrics")?.let { export ->
                export.mkdirs()
                File(export, fileName).writeText(data.toString())
                val metrics = File(i.targetContext.filesDir, "Kandinsky/diagnostics/latest.json")
                if (metrics.isFile)
                    metrics.copyTo(File(export, "product-metrics.json"), overwrite = true)
            }
        }
    }
}
