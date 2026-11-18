package dev.kirill.kandinsky.chat.attachments

import android.os.SystemClock
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import dev.kirill.kandinsky.chat.support.AppFlowDriver
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAttachmentFlowTest {
    @Test
    fun recentPhotoCanBeRemovedAndRecentVideoPlaysWithoutSendingAPrompt() {
        val ui = AppFlowDriver()
        ui.launch()
        ui.openChat("Плотная медиалента")
        ui.find(By.res("chat.compose")).click()

        fun pickRecent(title: String) {
            ui.find(By.res("composer.plus")).click()
            val choice = By.desc(title)
            if (!ui.device.wait(Until.hasObject(choice), 5000)) {
                val x = ui.device.displayWidth / 2
                ui.device.swipe(
                    x,
                    ui.device.displayHeight * 4 / 5,
                    x,
                    ui.device.displayHeight / 3,
                    30,
                )
            }
            ui.find(choice).click()
        }

        pickRecent("Использовать загруженное фото")
        val remove = By.res("studio.reference.remove")
        ui.find(remove).click()
        assertTrue(ui.device.wait(Until.gone(remove), 5000))

        pickRecent("Посмотреть загруженное видео")
        val remaining = By.res("studio.upload.remaining")
        val first = ui.find(remaining).text
        assertTrue(first.isNotBlank())
        val deadline = SystemClock.uptimeMillis() + 9000
        var next = first
        while (next == first && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(200)
            next = ui.find(remaining).text
        }
        assertNotEquals(first, next)
        ui.find(By.res("studio.upload.expand")).click()
        ui.find(By.res("studio.upload.collapse")).click()
        ui.device.pressBack()
        ui.find(By.res("composer.input"))
        ui.openChat("Плотная медиалента")
    }
}
