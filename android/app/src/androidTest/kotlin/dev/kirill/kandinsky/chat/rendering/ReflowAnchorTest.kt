// Проверяем точное место чтения внутри длинного текста при смене ширины настоящего окна.

package dev.kirill.kandinsky.chat.rendering

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.kirill.kandinsky.chat.app.MainActivity
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.support.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ReflowAnchorTest {
    // Открываем длинное сообщение с составными знаками и меняем ширину экрана. После нового
    // переноса сверяем номер сообщения и положение среди знаков.
    @Test
    fun actualResizeRetainsLogicalUnicodePosition() {
        val i = InstrumentationRegistry.getInstrumentation()
        val h =
            RepositoryHarness.open(File(i.targetContext.cacheDir, "reflow-" + UUID.randomUUID()))
        val text = "مرحبا 👨‍👩‍👧‍👦 café é Unicode line ".repeat(600)
        awaitResult<MessageDelta> { cb -> h.repo.send(SendCommand("reflow", 2, text = text), cb) }
        val intent =
            Intent(i.targetContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        val activity = i.startActivitySync(intent)
        assertTrue(
            UiDevice.getInstance(i)
                .wait(Until.hasObject(By.pkg(i.targetContext.packageName)), 15000)
        )
        lateinit var host: ChatScrollHost
        lateinit var parent: FrameLayout
        i.runOnMainSync {
            host = ChatScrollHost(activity)
            parent = FrameLayout(activity)
            parent.addView(host, FrameLayout.LayoutParams(360, 700))
            activity.setContentView(parent)
        }
        awaitResult<Unit> { cb ->
            i.runOnMainSync {
                host.open(2, h.repo, ReadingAnchor(messageID = 1, followsBottom = false), cb)
            }
            Cancellation {}
        }
        // Ждём условие не дольше шести секунд и читаем состояние на основном потоке. Если новая
        // разметка не появилась, проверка сообщает об этом явно.
        fun waitFor(condition: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + 6000
            while (SystemClock.uptimeMillis() < end) {
                var ok = false
                i.runOnMainSync { ok = condition() }
                if (ok) return
                SystemClock.sleep(20)
            }
            i.runOnMainSync { assertTrue(condition()) }
        }
        waitFor { host.metrics.liveFragments > 0 }
        lateinit var before: ReadingAnchor
        var recorded = 0
        i.runOnMainSync {
            val t = SystemClock.uptimeMillis()
            for ((action, y, delay) in
                listOf(
                    Triple(MotionEvent.ACTION_DOWN, 600f, 0L),
                    Triple(MotionEvent.ACTION_MOVE, 150f, 100L),
                    Triple(MotionEvent.ACTION_CANCEL, 150f, 110L),
                )) {
                val e = MotionEvent.obtain(t, t + delay, action, 180f, y, 0)
                host.onTouchEvent(e)
                e.recycle()
            }
            before = host.readingAnchor()
            assertNotNull(before.textUTF16Offset)
            assertTrue(before.textUTF16Offset!! > 0)
            recorded = host.metrics.recordings
            host.layoutParams = FrameLayout.LayoutParams(480, 700)
            host.requestLayout()
            parent.requestLayout()
        }
        waitFor { host.metrics.recordings > recorded && host.metrics.liveFragments > 0 }
        val menu = CountDownLatch(1)
        var menuMessage: Long? = null
        var shortTaps = 0
        var pressedAt = 0L
        var x = 0f
        var y = 0f
        try {
            i.runOnMainSync {
                val after = host.readingAnchor()
                assertEquals(before.messageID, after.messageID)
                assertEquals(
                    "Logical UTF16 survives actual new layout",
                    before.textUTF16Offset,
                    after.textUTF16Offset,
                )
                x = host.width / 2f
                y = host.height / 2f
                assertEquals(before.messageID, host.hit(x, y)?.first?.id)
                host.onMessageLongPress = { row, _ ->
                    menuMessage = row.id
                    menu.countDown()
                }
                host.onMessageTap = { _, _ -> shortTaps++ }
                pressedAt = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(pressedAt, pressedAt, MotionEvent.ACTION_DOWN, x, y, 0)
                host.onTouchEvent(down)
                down.recycle()
            }
            assertTrue("Holding touches the same message after reflow", menu.await(4, TimeUnit.SECONDS))
            i.runOnMainSync {
                val up = MotionEvent.obtain(
                    pressedAt, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0,
                )
                host.onTouchEvent(up)
                up.recycle()
                assertEquals(before.messageID, menuMessage)
                assertEquals(0, shortTaps)
            }
        } finally {
            i.runOnMainSync {
                host.close()
                activity.finish()
            }
            h.close()
        }
    }
}
