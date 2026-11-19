// Проверяет команды и подсказки озвучивания для результата генерации.

package dev.kirill.kandinsky.chat.rendering

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.kirill.kandinsky.chat.R
import dev.kirill.kandinsky.chat.domain.Attachment
import dev.kirill.kandinsky.chat.domain.Message
import dev.kirill.kandinsky.chat.domain.MessageDirection
import dev.kirill.kandinsky.chat.domain.MessageKind
import dev.kirill.kandinsky.chat.domain.OrderKey
import dev.kirill.kandinsky.chat.domain.StudioGeneration
import dev.kirill.kandinsky.chat.domain.StudioGenerationAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationAccessibilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun aReportFromTheSystemTargetsOnlyAnIncomingPhoto() {
        withHistory { history ->
            val photo = Attachment("photo", "photo.jpg", "image/jpeg", 300, 80, 60, 1, null, "")
            val result = Message(
                id = 71,
                chatID = 2,
                order = OrderKey(71, 71),
                direction = MessageDirection.INCOMING,
                savedAtMs = 0,
                kind = MessageKind.PHOTO,
                text = "",
                caption = "",
                replyToID = null,
                revision = 1,
                attachments = listOf(photo),
            )
            var reported: Message? = null
            history.onStudioGenerationAction = { _, _ -> }
            history.onMessageLongPress = { row, _ -> reported = row }
            val nodes = mutableListOf<NativeAction>()
            val layout = PreparedText(result, TextLayoutKey(71, 1, 320, 17, 1))
            history.appendRowAccessibility(result, layout, 16f, 30f, nodes)
            val image = nodes.single { it.id == "media.image.71" }
            val native = NativeAccessibility(history, "history", { nodes })
            val info = checkNotNull(native.createAccessibilityNodeInfo(image.key))
            assertEquals("Открыть фотографию", info.contentDescription)
            assertTrue(info.actionList.any { it.id == R.id.message_report })
            assertTrue(native.performAction(image.key, R.id.message_report, null))
            assertEquals(result, reported)

            nodes.clear()
            val sent = result.copy(direction = MessageDirection.OUTGOING)
            val outgoing = PreparedText(sent, TextLayoutKey(71, 1, 320, 17, 1))
            history.appendRowAccessibility(sent, outgoing, 16f, 30f, nodes)
            assertTrue(nodes.single { it.id == "media.image.71" }.customActions.isEmpty())
        }
    }

    @Test
    fun onlyTheFailedUnrestrictedSlotOffersRetry() {
        withHistory { history ->
            val stage = StudioGeneration.Stage.RUNNING
            val failed = StudioGeneration.Slot("failed", StudioGeneration.Stage.FAILED, null, null)
            val running = StudioGeneration.Slot("running", stage, null, 37)
            val blocked = failed.copy(id = "blocked", moderated = true)
            val generation = StudioGeneration(
                stage = stage,
                model = "image_lite",
                aspect = "1:1",
                startedAtMs = null,
                error = null,
                slots = listOf(failed, running, blocked),
            )
            val result = Message(
                id = 72,
                chatID = 2,
                order = OrderKey(72, 72),
                direction = MessageDirection.INCOMING,
                savedAtMs = 0,
                kind = MessageKind.TEXT,
                text = "",
                caption = "",
                replyToID = null,
                revision = 1,
                attachments = emptyList(),
                studioGeneration = generation,
            )
            val retried = mutableListOf<Pair<Long, String>>()
            history.onStudioRetrySlot = { id, slot -> retried += id to slot.id }
            val nodes = mutableListOf<NativeAction>()
            val layout = PreparedText(result, TextLayoutKey(72, 1, 320, 17, 1))
            history.appendRowAccessibility(result, layout, 16f, 30f, nodes)
            val retry = nodes.single { it.id == "generation.slot.72.failed" }
            val progress = nodes.single { it.id == "generation.slot.72.running" }
            val moderation = nodes.single { it.id == "generation.slot.72.blocked" }
            assertEquals("Повторить генерацию", retry.label)
            assertEquals("37%", progress.label)
            assertTrue(retry.button)
            assertFalse(progress.button)
            assertFalse(moderation.button)

            val native = NativeAccessibility(history, "history", { nodes })
            assertTrue(native.performAction(retry.key, AccessibilityNodeInfo.ACTION_CLICK, null))
            assertFalse(native.performAction(progress.key, AccessibilityNodeInfo.ACTION_CLICK, null))
            assertFalse(native.performAction(moderation.key, AccessibilityNodeInfo.ACTION_CLICK, null))
            assertEquals(listOf(72L to failed.id), retried)
        }
    }

    @Test
    fun systemActionsTargetTheCorrectVideoVoiceRetryAndPrompt() {
        withHistory { history ->
            val video = Attachment("video", "video.mp4", "video/mp4", 300, 160, 90, 1, 3000, "")
            val result = Message(
                id = 73, chatID = 2, order = OrderKey(73, 73),
                direction = MessageDirection.INCOMING, savedAtMs = 0,
                kind = MessageKind.VIDEO, text = "", caption = "",
                replyToID = null, revision = 1, attachments = listOf(video),
            )
            val failure = result.copy(
                id = 74, order = OrderKey(74, 74), kind = MessageKind.TEXT, attachments = emptyList(),
                studioGeneration = StudioGeneration(
                    stage = StudioGeneration.Stage.FAILED, model = "image_lite", aspect = "1:1",
                    startedAtMs = null, error = "Сервер не завершил запрос",
                ),
            )
            val prompt = failure.copy(
                id = 75, order = OrderKey(75, 75), direction = MessageDirection.OUTGOING,
                text = "Ночной город", studioGeneration = null,
            )
            val voice = result.copy(
                id = 76, order = OrderKey(76, 76), kind = MessageKind.VOICE,
                attachments = listOf(video.copy(id = "voice", mime = "audio/mp4")),
            )
            val opened = mutableListOf<Long>()
            val menus = mutableListOf<Long>()
            val retried = mutableListOf<Long>()
            history.onMessageTap = { row, _ -> opened += row.id }
            history.onMessageLongPress = { row, _ -> menus += row.id }
            history.onStudioResultDelete = { _, _ -> error("Открытие меню не удаляет файл") }
            history.onStudioGenerationAction = { id, action ->
                assertEquals(StudioGenerationAction.RETRY, action)
                retried += id
            }
            val nodes = mutableListOf<NativeAction>()
            for ((row, y) in listOf(result to 24f, failure to 340f, prompt to 580f, voice to 660f)) {
                val layout = PreparedText(row, TextLayoutKey(row.id, row.revision, 320, 17, 1))
                history.appendRowAccessibility(row, layout, 16f, y, nodes)
            }
            val actions = NativeAccessibility(history, "history", { nodes })
            for (id in listOf(
                "media.image.73", "media.action.expand.73", "generation.action.retry.74",
                "message.75", "media.audio.76",
            )) {
                val button = nodes.single { it.id == id }
                assertTrue(button.button)
                assertTrue(actions.performAction(button.key, AccessibilityNodeInfo.ACTION_CLICK, null))
            }
            assertEquals(listOf(73L, 73L, 76L), opened)
            assertEquals(listOf(74L), retried)
            assertEquals(listOf(75L), menus)
        }
    }

    private fun withHistory(run: (ChatScrollHost) -> Unit) {
        instrumentation.runOnMainSync {
            val history = ChatScrollHost(instrumentation.targetContext)
            try {
                history.layout(0, 0, 400, 800)
                run(history)
            } finally {
                history.close()
            }
        }
    }
}
