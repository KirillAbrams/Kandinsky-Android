package dev.kirill.kandinsky.chat.diagnostics

import android.os.*
import android.util.AtomicFile
import android.view.*
import java.io.File
import java.util.concurrent.*
import org.json.JSONObject

object MetricsRuntime {
    @Volatile var enabled = false
    val recorder = MetricsRecorder()
    @Volatile var displayRefreshHz: Float? = null
    @Volatile var displayMaximumHz: Float? = null
    var output: File? = null
    private val writer =
        ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(1),
            { r -> Thread(r, "Kandinsky-Metrics").apply { isDaemon = true } },
            ThreadPoolExecutor.DiscardOldestPolicy(),
        )

    fun export() {
        if (!enabled) return
        val file = output ?: return
        val stats = JSONObject()
        recorder.snapshot().forEach { (k, v) ->
            stats.put(
                k,
                JSONObject()
                    .put("count", v.count)
                    .put("retained", v.retained)
                    .put("p50", v.p50)
                    .put("p95", v.p95)
                    .put("p99", v.p99)
                    .put("maximum", v.maximum),
            )
        }
        val raw = JSONObject()
        recorder.rawSamples().forEach { (k, v) -> raw.put(k, org.json.JSONArray(v)) }
        val json =
            JSONObject()
                .put(
                    "scope",
                    "Emulator HWUI FrameMetrics total duration and scene CPU; not physical device FPS",
                )
                .put("statistics", stats)
                .put("rawSamples", raw)
                .put("displayRefreshHz", displayRefreshHz)
                .put("displayMaximumHz", displayMaximumHz)
                .put("peaks", JSONObject(recorder.peakSnapshot()))
                .toString()
        writer.execute {
            file.parentFile?.mkdirs()
            val atomic = AtomicFile(file)
            var stream: java.io.FileOutputStream? = null
            try {
                stream = atomic.startWrite()
                stream.write(json.toByteArray())
                atomic.finishWrite(stream)
            } catch (_: Throwable) {
                atomic.failWrite(stream)
            }
        }
    }
}

@Suppress("DEPRECATION")
class WindowFrameProbe(private val window: Window) : AutoCloseable {
    private val thread = HandlerThread("Kandinsky-FrameMetrics").apply { start() }
    private val listener =
        Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
            MetricsRuntime.recorder.record(
                "window_total_duration_ms",
                metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000.0,
            )
            MetricsRuntime.recorder.peak("frame_listener_dropped_reports", dropped)
        }

    init {
        (window.decorView.display ?: window.windowManager.defaultDisplay)?.let {
            MetricsRuntime.displayRefreshHz = it.refreshRate
            MetricsRuntime.displayMaximumHz =
                it.supportedModes.maxOfOrNull { mode -> mode.refreshRate }
        }
        window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
    }

    override fun close() {
        window.removeOnFrameMetricsAvailableListener(listener)
        thread.quitSafely()
        MetricsRuntime.export()
    }
}
