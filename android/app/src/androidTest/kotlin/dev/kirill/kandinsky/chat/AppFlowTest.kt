// Проверяем основные кнопки генерации на открытом экране разговора.

package dev.kirill.kandinsky.chat

import androidx.test.uiautomator.By
import dev.kirill.kandinsky.chat.support.AppFlowDriver
import org.junit.Assert.assertTrue
import org.junit.Test

class AppFlowTest {
    // Открываем чат через обычный список и находим кнопки по их постоянным меткам. Проверяем
    // видимую ширину, чтобы скрытый элемент не выдал себя за доступную кнопку.
    @Test
    fun conversationExposesGenerationControls() {
        val ui = AppFlowDriver()
        ui.launch()
        ui.openChat("Длинная история")
        for (id in
            listOf(
                "composer.plus",
                "composer.model",
                "composer.settings",
                "composer.input",
                "composer.send",
            )) {
            assertTrue(ui.find(By.res(id)).visibleBounds.width() > 0)
        }
    }
}
