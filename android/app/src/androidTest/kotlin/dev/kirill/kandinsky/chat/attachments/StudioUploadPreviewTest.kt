// Проверяет окно видео на изолированной сборке Андроида и местном ролике. Следит за одним
// проигрывателем при развороте и за его освобождением после выбора.

package dev.kirill.kandinsky.chat.attachments

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.core.view.allViews
import androidx.test.platform.app.InstrumentationRegistry
import dev.kirill.kandinsky.chat.domain.Attachment
import dev.kirill.kandinsky.chat.domain.AttachmentDraft
import dev.kirill.kandinsky.chat.domain.Cancellation
import dev.kirill.kandinsky.chat.media.MediaAdmission
import dev.kirill.kandinsky.chat.media.MediaCoordinator
import dev.kirill.kandinsky.chat.media.MediaStore
import dev.kirill.kandinsky.chat.network.NetworkCredentials
import dev.kirill.kandinsky.chat.rendering.ChatScrollHost
import dev.kirill.kandinsky.chat.support.RepositoryHarness
import dev.kirill.kandinsky.chat.support.TestFixtures
import dev.kirill.kandinsky.chat.support.awaitResult
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class StudioUploadPreviewTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()

    // Подаёт местный ролик, проверяет первый кадр и разворот той же поверхности. После улучшения
    // или обычного закрытия сверяет вызванную команду и освобождение проигрывателя.
    @Test
    fun sourceVideoKeepsItsPlayerAcrossLayoutsAndClosesWithTheChosenAction() {
        val context = instrument.targetContext
        check(context.packageName.endsWith(".cleanup"))
        check(NetworkCredentials(context).load() == null)
        val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        val activity = instrument.startActivitySync(
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        val files = File(context.cacheDir, "studio-source-preview-" + UUID.randomUUID())
        val harness = RepositoryHarness.open(files)
        val media = MediaStore(harness.store)
        val admission = MediaAdmission()
        lateinit var host: ChatScrollHost
        lateinit var player: MediaCoordinator
        var closeHost: (() -> Unit)? = null
        var closePlayer: (() -> Unit)? = null
        var dismissals = 0
        var enhancements = 0

        try {
            val recorded = awaitResult<AttachmentDraft> { result ->
                instrument.runOnMainSync {
                    media.stageSelection(
                        { TestFixtures.assets.open("media/video-01.mp4") }, "video/mp4", result,
                    )
                }
                Cancellation {}
            }
            val video = Attachment(
                recorded.id, recorded.relativePath, recorded.mime, recorded.byteSize,
                recorded.rawWidth, recorded.rawHeight, recorded.orientation, recorded.durationMs,
                recorded.thumbnailKey, recorded.tinyPreview,
            )
            lateinit var window: View
            instrument.runOnMainSync {
                host = ChatScrollHost(activity)
                closeHost = host::close
                activity.setContentView(host)
                host.previewStudioVideo(video) { dismissals++ }
                assertEquals(1, dismissals)
                player = MediaCoordinator(admission, media)
                closePlayer = player::close
                host.configurePlayback(player, media)
                val before = WindowInspector.getGlobalWindowViews().toSet()
                host.previewStudioVideo(video, onEnhance = { enhancements++ }) { dismissals++ }
                window = WindowInspector.getGlobalWindowViews().single { it !in before }
                assertFalse(window.control("studio.upload.expand").isEnabled)
                assertFalse(window.control("studio.upload.collapse").isShown)
                host.previewStudioVideo(video) { dismissals++ }
                assertEquals(2, dismissals)
            }

            await("В предпросмотре должен появиться первый кадр") {
                host.playerSurface?.hasVideoFrame == true && window.control("studio.upload.expand").isEnabled
            }
            val surface = checkNotNull(host.playerSurface)
            lateinit var panel: ViewGroup
            instrument.runOnMainSync {
                panel = surface.parent as ViewGroup
                assertTrue(surface.width > 0)
                assertEquals(surface.width, surface.height)
                assertTrue(panel.width > surface.width)
                assertEquals(video, player.currentTarget?.attachment)
                assertEquals(1, player.activePlayerCount)
                val clock = window.control("studio.upload.remaining") as TextView
                assertTrue(clock.text.toString().matches(Regex("00:0[0-3]")))
                assertTrue(window.control("studio.upload.expand").performClick())
            }
            await("Полноэкранный ролик занимает всю область") {
                surface.width == panel.width && surface.height == panel.height &&
                    window.control("studio.upload.collapse").isShown
            }
            instrument.runOnMainSync {
                assertFalse(window.control("studio.upload.remaining").isShown)
                assertFalse(window.control("studio.upload.enhance").isShown)
                assertEquals(video, player.currentTarget?.attachment)
                assertEquals(1, player.activePlayerCount)
                assertTrue(surface.hasVideoFrame)
                assertTrue(window.control("studio.upload.collapse").performClick())
                assertTrue(window.control("studio.upload.expand").performClick())
            }
            await("Открыт полноэкранный предпросмотр") {
                window.control("studio.upload.collapse").isShown
            }
            pressBack(window)
            await("Назад из полноэкранного просмотра оставляет нижнюю панель") {
                window.control("studio.upload.expand").isShown && surface.width < panel.width
            }
            instrument.runOnMainSync {
                assertTrue(window.control("studio.upload.enhance").performClick())
            }
            await("Действие улучшения освобождает видеовыход") {
                enhancements == 1 && player.activePlayerCount == 0 && surface.parent == null
            }
            instrument.runOnMainSync {
                assertEquals(2, dismissals)
                assertNull(player.currentTarget)
                val before = WindowInspector.getGlobalWindowViews().toSet()
                host.previewStudioVideo(video) { dismissals++ }
                window = WindowInspector.getGlobalWindowViews().single { it !in before }
                assertTrue(window.allViews.none { it.accessibilityID() == "studio.upload.enhance" })
            }
            pressBack(window)
            await("Закрытие без улучшения вызывает только возврат") {
                dismissals == 3 && player.activePlayerCount == 0 && surface.parent == null
            }
            assertEquals(1, enhancements)
            assertEquals(1, player.peakNativePlayers)
        } finally {
            instrument.runOnMainSync {
                closeHost?.invoke()
                closePlayer?.invoke()
                media.close()
                admission.close()
                activity.finish()
            }
            harness.close()
            files.deleteRecursively()
        }
    }

    // Читает устойчивое имя элемента через сведения, доступные системному управлению. Обычная
    // подпись может меняться при развороте.
    private fun View.accessibilityID(): String? = createAccessibilityNodeInfo().viewIdResourceName

    // Находит единственный элемент окна по имени. Если есть две копии или элемент потерялся,
    // проверка завершится с ошибкой.
    private fun View.control(id: String): View = allViews.single { it.accessibilityID() == id }

    // Ждёт, пока окно принимает ввод, затем посылает системное действие назад. Оно не попадёт
    // случайно в чат под панелью.
    private fun pressBack(window: View) {
        await("Окно предпросмотра получает системный ввод") { window.hasWindowFocus() }
        val command = instrument.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK")
        ParcelFileDescriptor.AutoCloseInputStream(command).use {
            assertEquals("", it.bufferedReader().readText().trim())
        }
    }

    // Проверяет условие в потоке экрана до короткого предела. Долгая загрузка выдаст сообщение о
    // недостигнутом состоянии вместо бесконечного ожидания.
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        do {
            var satisfied = false
            instrument.runOnMainSync { satisfied = condition() }
            if (satisfied) return
            SystemClock.sleep(25)
        } while (SystemClock.uptimeMillis() < deadline)
        fail(message)
    }
}
