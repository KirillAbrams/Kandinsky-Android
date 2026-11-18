package dev.kirill.kandinsky.chat

import android.content.Intent
import android.graphics.Paint
import android.os.ParcelFileDescriptor
import android.util.TypedValue
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import java.io.File
import java.util.UUID
import kotlin.math.ceil
import org.junit.Assert.*
import org.junit.Test

class LargeFontFlowTest {
    @Test
    fun systemFontChangeRestoresDraftAndKeepsInputGlyphsInsideField() {
        val i = InstrumentationRegistry.getInstrumentation()
        val d = UiDevice.getInstance(i)
        Configurator.getInstance().waitForIdleTimeout = 0
        fun shell(command: String) =
            ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand(command))
                .use { String(it.readBytes()).trim() }
        val original = shell("settings get system font_scale")
        assertTrue(
            android.os.Build.FINGERPRINT.contains("generic") ||
                android.os.Build.MODEL.contains("sdk_gphone")
        )
        assertTrue(original == "null" || original.toFloatOrNull() != null)
        val intent =
            i.targetContext.packageManager.getLaunchIntentForPackage(i.targetContext.packageName)!!
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        i.targetContext.startActivity(intent)
        try {
            dev.kirill.kandinsky.chat.support.AppFlowDriver().openChat("Длинная история")
            val input = d.wait(Until.findObject(By.res("composer.input").enabled(true)), 10000)!!
            org.junit.Assume.assumeTrue(
                input.text.isNullOrEmpty() || input.text == "Опишите изображение или видео"
            )
            input.click()
            input.text = "font-scale-draft"
            d.pressBack()
            shell("settings put system font_scale 2.0")
            val deadline = android.os.SystemClock.uptimeMillis() + 12000
            while (
                i.targetContext.resources.configuration.fontScale < 1.9f &&
                    android.os.SystemClock.uptimeMillis() < deadline
            ) android.os.SystemClock.sleep(50)
            assertTrue(i.targetContext.resources.configuration.fontScale >= 1.9f)
            val large =
                d.wait(
                    Until.findObject(
                        By.res("composer.input").enabled(true).text("font-scale-draft")
                    ),
                    12000,
                )!!
            val paint =
                Paint().apply {
                    textSize =
                        TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_SP,
                            18f,
                            i.targetContext.resources.displayMetrics,
                        )
                }
            val metrics = paint.fontMetrics
            val minimum = ceil(metrics.bottom - metrics.top).toInt()
            val out = File(i.targetContext.filesDir, "task17").apply { mkdirs() }
            assertTrue(d.takeScreenshot(File(out, "android-system-font-200.png")))
            assertTrue(
                "Full native font bounding box must fit the composer input",
                large.visibleBounds.height() >= minimum,
            )
            assertTrue(d.hasObject(By.res("composer.send").enabled(true)))
        } finally {
            d.findObject(By.res("composer.input").text("font-scale-draft"))?.text = ""
            if (original == "null") shell("settings delete system font_scale")
            else shell("settings put system font_scale " + original)
        }
    }
}
