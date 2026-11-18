package dev.kirill.kandinsky.chat

import android.Manifest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import dev.kirill.kandinsky.chat.support.AppFlowDriver
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoCaptureFlowTest {
    @Test
    fun cancellingCameraReturnsToSameDraft() {
        val ui = AppFlowDriver()
        ui.launch()
        ui.openChat("Длинная история")
        val editor = By.res("composer.input")
        val draft = ui.find(editor).text
        ui.find(By.res("composer.plus")).click()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.CAMERA,
        )
        ui.find(By.desc("Снять фото")).click()
        ui.find(By.desc("Сделать снимок").enabled(true))
        val cancel = By.desc("Отменить съёмку")
        val bounds = ui.find(cancel).visibleBounds
        ui.device.click(bounds.centerX(), bounds.centerY())
        org.junit.Assert.assertTrue(ui.device.wait(Until.gone(cancel), 10000))
        assertEquals(draft, ui.find(editor).text)
    }
}
