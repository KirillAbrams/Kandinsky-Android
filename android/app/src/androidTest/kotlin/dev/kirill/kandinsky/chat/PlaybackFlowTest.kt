package dev.kirill.kandinsky.chat

import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import dev.kirill.kandinsky.chat.support.AppFlowDriver
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFlowTest {
    @Test
    fun videoOpensInFullscreenAndReturnsToConversation() {
        val ui = AppFlowDriver()
        ui.launch()
        ui.openChat("Плотная медиалента")
        ui.visibleResult("На весь экран").click()
        val close = By.res("player.inline")
        ui.find(close).click()
        assertTrue(ui.device.wait(Until.gone(close), 5000))
        ui.find(By.res("composer.input"))
    }
}
