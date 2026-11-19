// Проверяем раскрытие видео в отдельный просмотр и возврат к той же переписке.

package dev.kirill.kandinsky.chat

import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import dev.kirill.kandinsky.chat.support.AppFlowDriver
import java.util.regex.Pattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFlowTest {
    @Test
    fun returningAfterSlowAndFastSwipesKeepsTheSameVideoInTheGallery() {
        val ui = AppFlowDriver()
        ui.launch()
        ui.openChat("Плотная медиалента")
        ui.visibleResult("На весь экран").click()
        val closeVideo = By.res("player.inline")
        val closePhoto = By.res("photo.close")
        ui.find(closeVideo)
        val pages = By.res(Pattern.compile("viewer\\.page\\..+"))
        val width = ui.device.displayWidth
        val height = ui.device.displayHeight
        val x = width / 2
        val y = height / 2

        fun selectedPage(): String {
            ui.device.waitForIdle()
            val visible = ui.device.findObjects(pages).single { it.visibleBounds.contains(x, y) }
            return checkNotNull(visible.resourceName)
        }

        fun swipe(towardsLeft: Boolean, steps: Int) {
            val left = width * 17 / 100
            val right = width * 83 / 100
            val start = if (towardsLeft) right else left
            val end = if (towardsLeft) left else right
            assertTrue(ui.device.swipe(start, y, end, y, steps))
        }

        val video = selectedPage()
        var towardsPhoto = true
        swipe(towardsPhoto, 55)
        if (!ui.device.wait(Until.hasObject(closePhoto), 3_000)) {
            if (selectedPage() != video) {
                swipe(false, 55)
                ui.find(closeVideo)
                assertEquals(video, selectedPage())
            }
            towardsPhoto = false
            swipe(towardsPhoto, 55)
        }
        ui.find(closePhoto)
        val photo = selectedPage()
        assertNotEquals(video, photo)
        swipe(!towardsPhoto, 9)
        ui.find(closeVideo)
        assertEquals(video, selectedPage())
        swipe(towardsPhoto, 9)
        ui.find(closePhoto)
        assertEquals(photo, selectedPage())
        swipe(!towardsPhoto, 55)
        ui.find(closeVideo)
        assertEquals(video, selectedPage())
        ui.click(closeVideo)
        ui.find(By.res("composer.input"))
    }

    // Находим раскрываемое видео в существующей медиаленте, открываем и закрываем проигрыватель.
    // Он должен уйти с экрана, а форма разговора снова быть видна.
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
