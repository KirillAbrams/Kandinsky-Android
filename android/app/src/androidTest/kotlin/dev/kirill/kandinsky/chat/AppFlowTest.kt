// Проверяем основные кнопки генерации на открытом экране разговора.

package dev.kirill.kandinsky.chat

import android.content.ClipData
import android.content.ClipboardManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Until
import dev.kirill.kandinsky.chat.network.NetworkAPI
import dev.kirill.kandinsky.chat.network.NetworkCredentials
import dev.kirill.kandinsky.chat.network.NetworkProfile
import dev.kirill.kandinsky.chat.support.AppFlowDriver
import java.net.URI
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class AppFlowTest {
    @Test
    fun anInitialThreadCreationErrorLeavesThePromptReadyToRetry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName.endsWith(".cleanup"))
        assumeTrue(URI(NetworkProfile.ORIGIN).host == "127.0.0.1")
        assumeTrue(InstrumentationRegistry.getArguments().getString("coverage-refuse-thread") == "1")
        val profile = checkNotNull(NetworkCredentials(context).load())
        val api = NetworkAPI(profile)
        val ui = AppFlowDriver()
        val draft = "FirstThreadQA" + UUID.randomUUID().toString().take(8)
        val editor = By.res("composer.input")
        val send = By.res("composer.send")
        var created: Long? = null
        try {
            ui.launch()
            ui.openChat("Длинная история")
            ui.click(By.res("chat.compose"))
            ui.find(By.res("chat.welcome"))
            ui.find(editor).text = draft
            ui.click(send)
            ui.find(By.text("Не удалось начать историю"))
            ui.click(By.res("android:id/button1"))
            assertEquals(draft, ui.find(editor).text)
            assertTrue(ui.device.wait(Until.gone(By.descStartsWith("В очереди")), 5000))
            ui.click(send)
            ui.find(By.res(Pattern.compile("message\\.[0-9]+")).desc(draft))
            ui.find(By.descStartsWith("В очереди"))
            val chats = api.json("/v1/chats").getJSONArray("chats")
            created = (0 until chats.length()).map(chats::getJSONObject)
                .single { it.getString("title") == draft }.getLong("id")
        } finally {
            try {
                created?.let { api.json("/v1/kandinsky/threads/$it", method = "DELETE") }
            } finally {
                api.close()
            }
        }
    }

    @Test
    fun theFirstPromptCanBeStoppedRetriedAndRemovedWithoutRemovingItsRetry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName.endsWith(".cleanup"))
        assumeTrue(URI(NetworkProfile.ORIGIN).host == "127.0.0.1")
        val profile = checkNotNull(NetworkCredentials(context).load())
        val api = NetworkAPI(profile)
        val ui = AppFlowDriver()
        val marker = "QueueQA" + UUID.randomUUID().toString().take(8)
        val editor = By.res("composer.input")
        val send = By.res("composer.send")
        val queued = By.res(Pattern.compile("generation\\.slot\\.[0-9]+\\..+"))
            .descStartsWith("В очереди")
        var thread: Long? = null
        try {
            ui.launch()
            ui.openChat("Длинная история")
            ui.click(By.res("chat.compose"))
            ui.find(By.res("chat.welcome"))
            ui.click(By.res("composer.model"))
            ui.click(By.descStartsWith("Image Lite"))
            ui.click(By.res("composer.settings"))
            val single = By.text("1").clickable(true)
            reveal(ui, single)
            ui.click(single)
            val apply = By.text("Готово")
            reveal(ui, apply)
            ui.click(apply)
            ui.find(editor).text = marker
            ui.click(send)
            val prompt = ui.find(By.res(Pattern.compile("message\\.[0-9]+")).desc(marker))
            val original = By.res(checkNotNull(prompt.resourceName))
            val chats = api.json("/v1/chats").getJSONArray("chats")
            thread = (0 until chats.length()).map(chats::getJSONObject)
                .single { it.getString("title") == marker }.getLong("id")

            fun stop(): String {
                val running = ui.find(queued)
                val result = checkNotNull(running.resourceName).substringAfter("generation.slot.")
                    .substringBefore('.')
                ui.click(By.res("composer.send").desc("Остановить генерацию"))
                ui.find(By.res("generation.action.retry.$result"))
                return result
            }

            val result = stop()
            ui.click(By.res("generation.action.retry.$result"))
            val replacement = stop()
            assertNotEquals(result, replacement)
            val inputBeforeCopy = ui.find(editor).text
            val clipboard = checkNotNull(context.getSystemService(ClipboardManager::class.java))
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val copied = CountDownLatch(1)
            val listener = ClipboardManager.OnPrimaryClipChangedListener {
                if (clipboard.primaryClip?.getItemAt(0)?.text?.toString() == marker) copied.countDown()
            }
            var previousClip: ClipData? = null
            instrumentation.runOnMainSync {
                previousClip = clipboard.primaryClip
                clipboard.addPrimaryClipChangedListener(listener)
            }
            try {
                ui.click(original)
                ui.click(By.text("Копировать запрос"))
                assertTrue("Текст выбранного запроса должен попасть в буфер", copied.await(5, TimeUnit.SECONDS))
                assertEquals(inputBeforeCopy, ui.find(editor).text)
            } finally {
                instrumentation.runOnMainSync {
                    clipboard.removePrimaryClipChangedListener(listener)
                    if (clipboard.primaryClip?.getItemAt(0)?.text?.toString() == marker) {
                        previousClip?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip()
                    }
                }
            }
            ui.click(original)
            ui.click(By.text("Удалить запрос"))
            ui.find(By.text("Удалить запрос?"))
            ui.click(By.res("android:id/button1"))
            assertTrue(ui.device.wait(Until.gone(original), 10_000))
            assertFalse(ui.device.hasObject(By.res("generation.action.retry.$result")))
            ui.find(By.res("generation.action.retry.$replacement"))
            ui.find(editor)
        } finally {
            try {
                thread?.let { api.json("/v1/kandinsky/threads/$it", method = "DELETE") }
            } finally {
                api.close()
            }
        }
    }

    @Test
    fun anUnsentNewChatDraftSurvivesBackgroundingAndLowMemory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName.endsWith(".cleanup"))
        assumeTrue(URI(NetworkProfile.ORIGIN).host == "127.0.0.1")
        val ui = AppFlowDriver()
        ui.launch()
        ui.openChat("Длинная история")
        ui.click(By.res("chat.compose"))
        val input = By.res("composer.input")
        ui.click(By.res("chat.welcome"))
        val prompt = "Белая магнолия у окна, мягкий дневной свет, минималистичная фотография"
        val draft = By.res("composer.input").text(prompt)
        assertEquals(prompt, ui.find(draft).text)
        ui.click(input)
        assertTrue(ui.find(By.res("composer.send")).visibleBounds.width() > 0)
        assertTrue(ui.device.pressHome())
        assertTrue(ui.device.wait(Until.gone(input), 10_000))
        val memoryWarning =
            ui.device.executeShellCommand("am send-trim-memory ${context.packageName} RUNNING_LOW")
        assertTrue(memoryWarning, memoryWarning.isBlank())
        ui.launch()
        assertEquals(prompt, ui.find(draft).text)
        assertTrue(ui.find(By.res("chat.welcome")).visibleBounds.width() > 0)
        ui.find(input).text = ""
        assertTrue(ui.device.wait(Until.gone(draft), 5_000))
    }

    @Test
    fun selectedModelControlsTheSettingsAndTheResultCountSurvivesReopening() {
        assumeTrue(URI(NetworkProfile.ORIGIN).host == "127.0.0.1")
        val ui = AppFlowDriver()
        ui.launch()
        ui.openChat("Длинная история")
        val model = By.res("composer.model")
        val settings = By.res("composer.settings")
        val parameters = By.text("Параметры")
        val duration = By.text("Длительность")
        val done = By.text("Готово")

        fun choose(name: String) {
            ui.click(model)
            ui.find(By.text("Выберите модель"))
            ui.click(By.descStartsWith(name))
        }

        choose("Video Lite")
        ui.click(settings)
        ui.find(parameters)
        reveal(ui, duration)
        ui.device.pressBack()
        assertTrue(ui.device.wait(Until.gone(parameters), 6000))

        choose("Image Lite")
        ui.click(settings)
        ui.find(parameters)
        assertFalse(ui.device.hasObject(duration))
        val count = By.text("3").clickable(true)
        reveal(ui, count)
        ui.click(count)
        assertTrue(ui.find(count).isSelected)
        reveal(ui, done)
        ui.click(done)
        assertTrue(ui.device.wait(Until.gone(parameters), 6000))
        ui.click(settings)
        reveal(ui, count)
        assertTrue(ui.find(count).isSelected)
        ui.device.pressBack()
        assertTrue(ui.device.wait(Until.gone(parameters), 6000))
    }

    private fun reveal(ui: AppFlowDriver, selector: BySelector) {
        repeat(4) {
            val visible = ui.device.wait(Until.findObject(selector), 1200)
            if (visible != null && !visible.visibleBounds.isEmpty) return
            val width = ui.device.displayWidth
            val height = ui.device.displayHeight
            ui.device.swipe(width / 2, height * 4 / 5, width / 2, height * 2 / 5, 24)
        }
        ui.find(selector)
    }

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
