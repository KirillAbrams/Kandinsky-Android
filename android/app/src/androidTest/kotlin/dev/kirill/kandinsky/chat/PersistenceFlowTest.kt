package dev.kirill.kandinsky.chat

import androidx.test.uiautomator.By
import dev.kirill.kandinsky.chat.support.AppFlowDriver
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class PersistenceFlowTest {
    @Test fun draftStaysInItsOwnConversation() {
        val ui = AppFlowDriver()
        ui.launch()
        ui.openChat("Длинная история")
        val editor = By.res("composer.input")
        val original = ui.find(editor).text.orEmpty()
        assumeTrue(original.isEmpty() || original == "Опишите изображение или видео")
        val draft = "Проверка черновика"
        try {
            ui.find(editor).text = draft
            ui.openChat("Плотная медиалента")
            org.junit.Assert.assertNotEquals(draft, ui.find(editor).text)
            ui.openChat("Длинная история")
            assertEquals(draft, ui.find(editor).text)
        } finally {
            ui.openChat("Длинная история")
            if (ui.find(editor).text == draft) ui.find(editor).text = ""
        }
    }
}
