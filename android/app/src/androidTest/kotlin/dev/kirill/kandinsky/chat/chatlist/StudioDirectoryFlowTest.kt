// Проверяет, что команды списка меняют одну и ту же карточку до и после переноса в архив.

package dev.kirill.kandinsky.chat.chatlist

import android.view.inputmethod.EditorInfo
import android.view.inspector.WindowInspector
import android.view.Surface
import android.widget.EditText
import androidx.core.view.allViews
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import dev.kirill.kandinsky.chat.network.NetworkAPI
import dev.kirill.kandinsky.chat.network.NetworkCredentials
import dev.kirill.kandinsky.chat.network.NetworkFailure
import dev.kirill.kandinsky.chat.network.NetworkProfile
import dev.kirill.kandinsky.chat.support.AppFlowDriver
import java.net.URI
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class StudioDirectoryFlowTest {
    @Test
    fun keyboardSearchKeepsTheQueryAndReturnsFocusToHistory() {
        val ui = AppFlowDriver()
        ui.launch()
        ui.click(By.desc("История запросов"))
        val search = By.res("chats.search.field")
        ui.click(search)
        ui.find(search).text = "Длинная"
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val editor = WindowInspector.getGlobalWindowViews().asSequence()
                .flatMap { it.allViews }.filterIsInstance<EditText>()
                .single { it.isShown && it.createAccessibilityNodeInfo().viewIdResourceName == "chats.search.field" }
            assertTrue(editor.hasFocus())
            editor.onEditorAction(EditorInfo.IME_ACTION_SEARCH)
            assertFalse(editor.hasFocus())
        }
        assertEquals("Длинная", ui.find(search).text)
        val rows = By.res("chats.rows")
        ui.find(By.text("Длинная история").hasAncestor(rows))
        assertTrue(ui.device.wait(Until.gone(By.text("Плотная медиалента").hasAncestor(rows)), 10_000))
    }

    @Test
    fun cancellingThenConfirmingRemovalDeletesOnlyTheSelectedChat() =
        withNewChat { id, title ->
            val ui = AppFlowDriver()
            ui.launch()
            ui.openChat(title)
            val input = By.res("composer.input")
            val draft = "Черновик удаляемого чата"
            val row = By.res("chat.row.$id")
            val actions = By.res("chats.actions.$id")
            val confirmation = By.text("Удалить чат?")
            ui.find(input).text = draft
            ui.find(By.res("composer.input").text(draft))

            fun requestDeletion() {
                ui.click(By.desc("История запросов"))
                ui.click(actions)
                ui.click(By.text("Удалить"))
                ui.find(confirmation)
            }

            requestDeletion()
            ui.click(By.res("android:id/button2"))
            assertTrue(ui.device.wait(Until.gone(confirmation), 5000))
            assertEquals(title, ui.find(row).text)
            ui.click(row)
            assertEquals(draft, ui.find(By.res("composer.input").text(draft)).text)
            requestDeletion()
            ui.click(By.res("android:id/button1"))
            assertTrue(ui.device.wait(Until.gone(row), 10_000))
            ui.openChat("Длинная история")
            assertTrue(ui.device.wait(Until.gone(By.res("composer.input").text(draft)), 5000))

            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val profile = checkNotNull(NetworkCredentials(context).load())
            val api = NetworkAPI(profile)
            try {
                val error = runCatching { api.json("/v1/chats/$id/history?limit=1") }
                    .exceptionOrNull() as? NetworkFailure
                assertEquals(410, error?.status)
            } finally {
                api.close()
            }
        }

    @Test
    fun licensesAreReadableWithoutLeavingTheChatDirectory() {
        val ui = AppFlowDriver()
        ui.launch()
        ui.click(By.desc("История запросов"))
        ui.click(By.res("chats.manageAccount"))
        ui.click(By.text("Лицензии"))
        val license = ui.find(By.res("android:id/message")).text
        assertTrue(license.contains("MIT License"))
        assertTrue(license.contains("Tailwind Labs"))
        ui.click(By.res("android:id/button1"))
        ui.find(By.res("chats.search.field"))
    }

    @Test
    fun renamingPinningAndArchivingKeepTheSameChat() = withNewChat { id, initial ->
        val renamed = "Название $id"
        val ui = AppFlowDriver()
        ui.launch()
        ui.find(By.desc("История запросов")).click()
        val row = By.res("chat.row.$id")
        val actions = By.res("chats.actions.$id")
        assertEquals(initial, ui.find(row).text)

        fun action(label: String) {
            ui.find(actions).click()
            ui.find(By.text(label)).click()
        }

        action("Переименовать")
        ui.find(By.res("chats.rename.input")).text = renamed
        ui.find(By.res("android:id/button1")).click()
        assertEquals(renamed, ui.find(By.res("chat.row.$id").text(renamed)).text)

        action("Закрепить")
        ui.find(By.text("Закреплённые"))
        action("Открепить")
        assertTrue(ui.device.wait(Until.gone(By.text("Закреплённые")), 10_000))

        action("В архив")
        assertTrue(ui.device.wait(Until.gone(row), 10_000))
        val archive = By.res("chats.archive")
        ui.find(archive).click()
        assertEquals(renamed, ui.find(row).text)
        action("Вернуть из архива")
        assertTrue(ui.device.wait(Until.gone(row), 10_000))
        ui.find(archive).click()
        assertEquals(renamed, ui.find(row).text)
    }

    @Test
    fun aDraftSurvivesLeavingTheAppAndOpeningTheSameChatAgain() = withNewChat { id, title ->
        val ui = AppFlowDriver()
        val input = By.res("composer.input")
        val draft = "Проверочный черновик $id"
        ui.launch()
        ui.openChat(title)
        ui.find(input).text = draft
        assertEquals(draft, ui.find(By.res("composer.input").text(draft)).text)

        assertTrue(ui.device.pressHome())
        assertTrue(ui.device.wait(Until.gone(input), 10000))
        ui.launch()
        assertEquals(draft, ui.find(By.res("composer.input").text(draft)).text)
        ui.click(By.desc("История запросов"))
        ui.click(By.res("chat.row.$id"))
        assertEquals(draft, ui.find(By.res("composer.input").text(draft)).text)
        ui.find(input).text = ""
        assertTrue(ui.device.wait(Until.gone(By.res("composer.input").text(draft)), 10000))
    }

    @Test
    fun rotatingTheActivityPreservesTheSelectedChatAndItsDraft() = withNewChat { id, title ->
        val ui = AppFlowDriver()
        ui.launch()
        ui.openChat(title)
        val originalRotation = ui.device.displayRotation
        val input = By.res("composer.input")
        val draftText = "Черновик при повороте $id"
        val draft = By.res("composer.input").text(draftText)
        ui.find(input).text = draftText
        ui.find(draft)
        try {
            ui.device.setOrientationLeft()
            assertEquals(Surface.ROTATION_90, ui.device.displayRotation)
            assertEquals(draftText, ui.find(draft).text)
            ui.device.setOrientationNatural()
            assertEquals(draftText, ui.find(draft).text)
            ui.click(By.desc("История запросов"))
            assertEquals(title, ui.find(By.res("chat.row.$id")).text)
            ui.click(By.res("chat.row.$id"))
            assertEquals(draftText, ui.find(draft).text)
        } finally {
            when (originalRotation) {
                Surface.ROTATION_90 -> ui.device.setOrientationLeft()
                Surface.ROTATION_270 -> ui.device.setOrientationRight()
                else -> ui.device.setOrientationNatural()
            }
            ui.device.unfreezeRotation()
        }
    }

    private fun withNewChat(run: (id: Long, title: String) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName.endsWith(".cleanup"))
        assumeTrue(URI(NetworkProfile.ORIGIN).host == "127.0.0.1")
        val profile = checkNotNull(NetworkCredentials(context).load()) {
            "Тесту нужен вход на отдельном местном сервере"
        }
        val api = NetworkAPI(profile)
        try {
            val identity = UUID.randomUUID().toString()
            val created = api.json("/v1/demo/chats", JSONObject().put("id", identity))
            val id = created.getJSONObject("chat").getLong("id")
            val path = "/v1/kandinsky/threads/$id"
            val title = "Новый чат " + identity.take(8)
            try {
                api.json(path, JSONObject().put("title", title), "PATCH")
                run(id, title)
            } finally {
                try {
                    api.json(path, method = "DELETE")
                } catch (failure: NetworkFailure) {
                    if (failure.status != 404) throw failure
                }
            }
        } finally {
            api.close()
        }
    }
}
