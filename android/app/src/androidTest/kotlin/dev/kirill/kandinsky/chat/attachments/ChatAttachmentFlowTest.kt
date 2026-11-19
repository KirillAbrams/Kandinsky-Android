// Проверяет недавние вложения через обычную панель чата Андроида. Для запуска нужна история с
// ранее загруженными файлами.

package dev.kirill.kandinsky.chat.attachments

import android.content.ContentValues
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.inspector.WindowInspector
import androidx.core.view.allViews
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import dev.kirill.kandinsky.chat.network.NetworkAPI
import dev.kirill.kandinsky.chat.network.NetworkCredentials
import dev.kirill.kandinsky.chat.network.NetworkProfile
import dev.kirill.kandinsky.chat.support.AppFlowDriver
import dev.kirill.kandinsky.chat.support.TestFixtures
import java.net.URI
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ChatAttachmentFlowTest {
    @Test
    fun missingSystemPickerDoesNotBlockTheDraftOrTheNextAttachmentSelection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName.endsWith(".cleanup"))
        assumeTrue(URI(NetworkProfile.ORIGIN).host == "127.0.0.1")
        assumeTrue(InstrumentationRegistry.getArguments().getString("coverage_missing_picker") == "1")
        val ui = AppFlowDriver()
        ui.launch()
        ui.openChat("Длинная история")
        val editor = By.res("composer.input")
        val plus = By.res("composer.plus").enabled(true)
        val previous = ui.find(editor).text.orEmpty()
        val draft = "Черновик при недоступной галерее"
        val failure = By.res("studio.attachment.error").descContains("Проверьте разрешения")
        try {
            ui.find(editor).text = draft
            for (choice in listOf("Оживить фото из галереи", "Загрузить и посмотреть видео")) {
                ui.click(plus)
                ui.click(By.descStartsWith(choice))
                ui.find(failure)
                assertEquals(draft, ui.find(editor).text)
                ui.find(plus)
            }
        } finally {
            ui.find(editor).text = previous
        }
    }

    @Test
    fun theSystemPickerCanCancelThenCreateOneChatForAnOriginalPhotoAndVideo() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.packageName.endsWith(".cleanup"))
        assumeTrue(URI(NetworkProfile.ORIGIN).host == "127.0.0.1")
        val refusesFirstPhoto = InstrumentationRegistry.getArguments()
            .getString("coverage-refuse-first-photo") == "1"
        val credentials = NetworkCredentials(context)
        val profile = checkNotNull(credentials.load())
        val api = NetworkAPI(profile)
        val resolver = context.contentResolver
        val createdFiles = mutableListOf<Uri>()
        val shortID = UUID.randomUUID().toString().take(8)
        val ui = AppFlowDriver()
        val editor = By.res("composer.input")
        val plus = By.res("composer.plus")
        val enabledPlus = By.res("composer.plus").enabled(true)
        val removeReference = By.res("studio.reference.remove")
        val photoPicker = "Оживить фото из галереи"
        val videoPicker = "Загрузить и посмотреть видео"
        val draftText = "Этот текст переживает выбор файла"

        fun choosePicker(label: String) {
            ui.click(plus)
            ui.find(By.descStartsWith(label))
            instrumentation.runOnMainSync {
                val row = WindowInspector.getGlobalWindowViews().asSequence().flatMap { it.allViews }
                    .single { it.isShown && it.isClickable && it.contentDescription?.startsWith(label) == true }
                assertTrue(row.performClick())
            }
        }

        fun publishFile(asset: String?, displayName: String, mime: String) {
            val row = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/KandinskyCoverage")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, row))
            createdFiles += uri
            checkNotNull(resolver.openOutputStream(uri)).use { output ->
                if (asset == null) {
                    output.write("Повреждённое изображение".toByteArray())
                } else {
                    TestFixtures.assets.open(asset).use { input -> input.copyTo(output) }
                }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }

        fun findOriginal(name: String): UiObject2 {
            fun match(): UiObject2? =
                ui.device.findObject(By.text(name)) ?: ui.device.findObject(By.desc(name))

            val documents = By.pkg(java.util.regex.Pattern.compile("com\\.(google\\.)?android\\.documentsui"))
            ui.find(documents)
            ui.device.findObject(By.desc("List view"))?.click()
            match()?.let { return it }
            ui.click(By.desc("Show roots"))
            ui.click(By.text("Downloads"))
            ui.click(By.text("KandinskyCoverage"))
            ui.device.findObject(By.desc("List view"))?.click()
            val deadline = SystemClock.uptimeMillis() + 8_000
            while (SystemClock.uptimeMillis() < deadline) {
                match()?.let { return it }
                SystemClock.sleep(120)
            }
            val visible = ui.device.findObjects(documents).mapNotNull {
                it.text?.takeIf(String::isNotBlank) ?: it.contentDescription?.takeIf(String::isNotBlank)
            }.distinct().take(30)
            error("Не найден $name в загрузках. Видимые элементы: $visible")
        }

        val photoName = "kandinsky-photo-$shortID.jpg"
        val brokenPhotoName = "kandinsky-unreadable-$shortID.jpg"
        val videoName = "kandinsky-video-$shortID.mp4"
        val longVideoName = "kandinsky-long-video-$shortID.mp4"
        fun chatIDs(): Set<Long> {
            val chats = api.json("/v1/chats").getJSONArray("chats")
            return (0 until chats.length()).map { chats.getJSONObject(it).getLong("id") }.toSet()
        }

        try {
            publishFile("media/photo-000.jpg", photoName, "image/jpeg")
            publishFile(null, brokenPhotoName, "image/jpeg")
            publishFile("media/video-00.mp4", videoName, "video/mp4")
            // Повтор фрагмента Blender Developers из real-media/manifest.json, 61,5 секунды.
            publishFile("bounds/video-over-one-minute.mp4", longVideoName, "video/mp4")
            val existing = chatIDs()
            fun newlyOpenedChat(): Long? = credentials.selectedChat(profile.userID)?.takeIf { it !in existing }
            try {
                ui.launch()
                ui.openChat("Длинная история")
                ui.click(By.res("chat.compose"))
                ui.find(By.res("chat.welcome"))
                ui.find(editor).text = draftText
                choosePicker(photoPicker)
                findOriginal(photoName)
                repeat(3) {
                    if (!ui.device.hasObject(editor)) {
                        ui.device.pressBack()
                        ui.device.wait(Until.hasObject(editor), 1200)
                    }
                }
                assertEquals(draftText, ui.find(editor).text)
                assertTrue(ui.device.wait(Until.gone(removeReference), 5_000))
                assertEquals(existing, chatIDs())

                choosePicker(photoPicker)
                findOriginal(brokenPhotoName).click()
                ui.find(By.res("studio.attachment.error").descContains("Не удалось открыть файл"))
                assertEquals(draftText, ui.find(editor).text)
                assertTrue(ui.device.wait(Until.gone(removeReference), 5_000))
                ui.find(enabledPlus)
                assertEquals(existing, chatIDs())

                choosePicker(videoPicker)
                findOriginal(longVideoName).click()
                ui.find(By.res("studio.attachment.error").descContains("Выберите MP4 до 20 МБ и 60 секунд"))
                assertEquals(draftText, ui.find(editor).text)
                assertTrue(ui.device.wait(Until.gone(removeReference), 5_000))
                assertFalse(ui.device.hasObject(By.res("studio.upload.expand")))
                assertEquals(existing, chatIDs())

                choosePicker(photoPicker)
                findOriginal(photoName).click()
                ui.find(removeReference)
                if (refusesFirstPhoto) {
                    val refusal = By.res("studio.attachment.error").descContains("Фото осталось на устройстве")
                    ui.find(refusal)
                    val afterRefusal = chatIDs()
                    assertTrue(afterRefusal.containsAll(existing))
                    assertTrue((afterRefusal - existing).size <= 1)
                    assertEquals(draftText, ui.find(editor).text)
                    assertTrue(ui.device.pressHome())
                    ui.launch()
                    ui.find(removeReference)
                    assertEquals(draftText, ui.find(editor).text)
                    val deadline = SystemClock.uptimeMillis() + 10_000
                    while (newlyOpenedChat() == null && SystemClock.uptimeMillis() < deadline) {
                        SystemClock.sleep(100)
                    }
                }
                ui.find(enabledPlus)
                val chat = checkNotNull(newlyOpenedChat()) { "Первый исходник не привязан к новому чату" }
                assertEquals(existing + chat, chatIDs())
                assertEquals(draftText, ui.find(editor).text)
                ui.click(removeReference)
                assertTrue(ui.device.wait(Until.gone(removeReference), 5_000))

                choosePicker(videoPicker)
                findOriginal(videoName).click()
                ui.find(By.res("studio.upload.expand"))
                ui.find(By.res("studio.upload.remaining"))
                ui.click(By.res("studio.upload.dismiss"))
                assertEquals(draftText, ui.find(editor).text)
                assertTrue(ui.device.wait(Until.gone(removeReference), 5_000))
                assertEquals(existing + chat, chatIDs())
                assertEquals(0, api.json("/v1/chats/$chat/history?limit=1").getJSONArray("messages").length())
            } finally {
                newlyOpenedChat()?.let { api.json("/v1/kandinsky/threads/$it", method = "DELETE") }
            }
        } finally {
            createdFiles.forEach { uri -> resolver.delete(uri, null, null) }
            api.close()
        }
    }

    // Выбирает и убирает снимок, затем проверяет уменьшение оставшегося времени ролика, разворот
    // и возврат. Ввод снова виден без отправленного запроса.
    @Test
    fun recentPhotoCanBeRemovedAndRecentVideoPlaysWithoutSendingAPrompt() {
        val ui = AppFlowDriver()
        ui.launch()
        ui.openChat("Плотная медиалента")
        ui.find(By.res("chat.compose")).click()

        // Находит действие для недавнего файла, при необходимости прокручивая панель. Так тест
        // нажимает именно нужную строку.
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
