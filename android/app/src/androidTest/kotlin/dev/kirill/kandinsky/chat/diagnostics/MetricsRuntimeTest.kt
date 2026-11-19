// Сверяет записанный отчёт с исходными замерами, вместо подсчёта вызовов системного таймера.

package dev.kirill.kandinsky.chat.diagnostics

import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import dev.kirill.kandinsky.chat.app.MainActivity
import dev.kirill.kandinsky.chat.network.NetworkCredentials
import dev.kirill.kandinsky.chat.network.NetworkProfile
import dev.kirill.kandinsky.chat.support.AppFlowDriver
import java.io.File
import java.net.URI
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test

class MetricsRuntimeTest {
    @Test
    fun openingARealChatRecordsItsMetadataLoadWhenMetricsAreEnabled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName.endsWith(".cleanup"))
        assumeTrue(URI(NetworkProfile.ORIGIN).host == "127.0.0.1")
        assumeNotNull(NetworkCredentials(context).load())
        val launch = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("metrics", true)
        val enabled = MetricsRuntime.enabled
        val output = MetricsRuntime.output
        val refresh = MetricsRuntime.displayRefreshHz
        val maximum = MetricsRuntime.displayMaximumHz
        val metric = "chat_metadata_open_ms"
        val measurements = MetricsRuntime.recorder
        try {
            ActivityScenario.launch<MainActivity>(launch).use {
                val ui = AppFlowDriver()
                ui.find(By.res("composer.input"))
                val before = measurements.statistics(metric)?.count ?: 0
                ui.openChat("Плотная медиалента")
                ui.openChat("Длинная история")
                val deadline = SystemClock.uptimeMillis() + 5000
                while ((measurements.statistics(metric)?.count ?: 0) <= before &&
                    SystemClock.uptimeMillis() < deadline
                ) {
                    SystemClock.sleep(20)
                }
                val after = checkNotNull(measurements.statistics(metric))
                assertTrue(after.count > before)
                assertTrue(after.maximum > 0)
            }
        } finally {
            MetricsRuntime.enabled = enabled
            MetricsRuntime.output = output
            MetricsRuntime.displayRefreshHz = refresh
            MetricsRuntime.displayMaximumHz = maximum
        }
    }

    @Test
    fun windowProbeReportsNativeFrameDurationAndDetachesWhenClosed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".cleanup"))
        val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val activity = instrumentation.startActivitySync(intent)
        val refresh = MetricsRuntime.displayRefreshHz
        val maximum = MetricsRuntime.displayMaximumHz
        val enabled = MetricsRuntime.enabled
        val metric = "window_total_duration_ms"
        val measurements = MetricsRuntime.recorder
        val initial = measurements.statistics(metric)?.count ?: 0
        lateinit var scene: View
        var probe: WindowFrameProbe? = null
        try {
            instrumentation.runOnMainSync {
                MetricsRuntime.enabled = false
                scene = View(activity)
                activity.setContentView(scene)
                probe = WindowFrameProbe(activity.window)
            }
            for (step in 0..60) {
                if ((measurements.statistics(metric)?.count ?: 0) > initial) break
                instrumentation.runOnMainSync {
                    scene.setBackgroundColor(if (step % 2 == 0) Color.WHITE else Color.LTGRAY)
                }
                SystemClock.sleep(25)
            }
            val visible = checkNotNull(measurements.statistics(metric))
            assertTrue(visible.count > initial)
            assertTrue(visible.maximum > 0)
            assertTrue(checkNotNull(MetricsRuntime.displayRefreshHz) > 0)
            assertTrue(checkNotNull(MetricsRuntime.displayMaximumHz) >=
                checkNotNull(MetricsRuntime.displayRefreshHz))

            instrumentation.runOnMainSync {
                probe?.close()
                probe = null
            }
            SystemClock.sleep(150)
            val detached = measurements.statistics(metric)?.count
            repeat(5) { step ->
                instrumentation.runOnMainSync {
                    scene.setBackgroundColor(if (step % 2 == 0) Color.WHITE else Color.LTGRAY)
                }
                SystemClock.sleep(25)
            }
            assertEquals(detached, measurements.statistics(metric)?.count)
        } finally {
            instrumentation.runOnMainSync {
                probe?.close()
                MetricsRuntime.enabled = enabled
                MetricsRuntime.displayRefreshHz = refresh
                MetricsRuntime.displayMaximumHz = maximum
                activity.finish()
            }
        }
    }

    @Test
    fun exportSavesTheMeasurementsAndDisplayRatesTogether() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "coverage-metrics-${UUID.randomUUID()}")
        val file = File(root, "report.json")
        val enabled = MetricsRuntime.enabled
        val output = MetricsRuntime.output
        val refresh = MetricsRuntime.displayRefreshHz
        val maximum = MetricsRuntime.displayMaximumHz
        val metric = "window_total_duration_ms"
        val peak = "frame_listener_dropped_reports"
        try {
            MetricsRuntime.enabled = false
            MetricsRuntime.output = file
            MetricsRuntime.export()
            assertFalse(file.exists())

            val measurements = MetricsRuntime.recorder
            val previous = measurements.statistics(metric)?.count ?: 0
            measurements.record(metric, 4.5)
            measurements.record(metric, 12.25)
            measurements.peak(peak, 4)
            MetricsRuntime.displayRefreshHz = 60f
            MetricsRuntime.displayMaximumHz = 120f
            MetricsRuntime.enabled = true
            MetricsRuntime.export()

            val deadline = SystemClock.uptimeMillis() + 5000
            while (!file.isFile && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(20)
            }
            val report = JSONObject(file.readText())
            assertEquals(60.0, report.getDouble("displayRefreshHz"), 0.0)
            assertEquals(120.0, report.getDouble("displayMaximumHz"), 0.0)
            assertTrue(report.getJSONObject("peaks").getInt(peak) >= 4)
            val summary = report.getJSONObject("statistics").getJSONObject(metric)
            assertEquals(previous + 2, summary.getLong("count"))
            assertTrue(summary.getDouble("maximum") >= 12.25)
            val raw = report.getJSONObject("rawSamples").getJSONArray(metric)
            assertEquals(4.5, raw.getDouble(raw.length() - 2), 0.0)
            assertEquals(12.25, raw.getDouble(raw.length() - 1), 0.0)
        } finally {
            MetricsRuntime.enabled = enabled
            MetricsRuntime.output = output
            MetricsRuntime.displayRefreshHz = refresh
            MetricsRuntime.displayMaximumHz = maximum
            root.deleteRecursively()
        }
    }
}
