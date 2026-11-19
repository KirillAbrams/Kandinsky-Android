// Проверяет запрос частоты окна при настоящем жесте прокрутки на Андроиде. Сравнивает настройку
// окна до, во время и после движения.

package dev.kirill.kandinsky.chat.rendering

import android.content.Intent
import android.os.SystemClock
import android.view.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.kirill.kandinsky.chat.app.MainActivity
import org.junit.Assert.*
import org.junit.Test

class ActiveDisplayPolicyTest {
    // Вычисляет самый частый режим при том же размере, запускает движение ленты и ждёт этого
    // запроса. По окончании проверяет, что окно вернуло прежнее значение.
    @Test
    fun activeNativeInertiaHintsHighestCompatibleDisplayModeAndRestoresIdleWindow() {
        val i = InstrumentationRegistry.getInstrumentation()
        val d = UiDevice.getInstance(i)
        Configurator.getInstance().waitForIdleTimeout = 0
        val intent =
            i.targetContext.packageManager
                .getLaunchIntentForPackage(i.targetContext.packageName)!!
                .apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
        val activity = i.startActivitySync(intent) as MainActivity
        try {
            dev.kirill.kandinsky.chat.support.AppFlowDriver().openChat("Длинная история")
            assertNotNull(d.wait(Until.findObject(By.res("composer.input").enabled(true)), 15000))
            // Ищет область прокрутки внутри текущего дерева окна. Проверка следит за движением
            // нужной ленты, даже если внешние контейнеры изменились.
            fun host(v: View): ChatScrollHost? {
                if (v is ChatScrollHost) return v
                if (v is ViewGroup)
                    for (n in 0 until v.childCount) host(v.getChildAt(n))?.let {
                        return it
                    }
                return null
            }
            lateinit var scroll: ChatScrollHost
            var previous = 0
            var expected = 0
            i.runOnMainSync {
                scroll = host(activity.window.decorView)!!
                // Проверяем инерцию отдельно от тихих роликов. Видимый ролик удерживает запрос
                // частоты даже после того, как прокрутка уже остановилась.
                scroll.setMovingMediaVisible(false)
                assertTrue(scroll.scroller.isFinished)
                assertEquals(0, scroll.scene.metrics.activeFrameClocks)
                scroll.updateDisplayHint()
                previous = activity.window.attributes.preferredDisplayModeId
                val display = activity.window.decorView.display
                val current = display.mode
                expected =
                    display.supportedModes
                        .filter {
                            it.physicalWidth == current.physicalWidth &&
                                it.physicalHeight == current.physicalHeight
                        }
                        .maxBy { it.refreshRate }
                        .modeId
                val t = SystemClock.uptimeMillis()
                for (n in 0..8) {
                    val action =
                        if (n == 0) MotionEvent.ACTION_DOWN
                        else if (n == 8) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
                    val e = MotionEvent.obtain(t, t + n * 16, action, 288f, 400f + n * 30f, 0)
                    e.source = InputDevice.SOURCE_TOUCHSCREEN
                    scroll.onTouchEvent(e)
                    e.recycle()
                }
            }
            var activeSeen = false
            val end = SystemClock.uptimeMillis() + 5000
            while (!activeSeen && SystemClock.uptimeMillis() < end) {
                i.runOnMainSync {
                    activeSeen = activity.window.attributes.preferredDisplayModeId == expected
                }
                SystemClock.sleep(10)
            }
            assertTrue(
                "Active native scroll must hint highest compatible mode id=" + expected,
                activeSeen,
            )
            var restored = false
            var observed = ""
            val idle = SystemClock.uptimeMillis() + 8000
            while (!restored && SystemClock.uptimeMillis() < idle) {
                i.runOnMainSync {
                    restored = activity.window.attributes.preferredDisplayModeId == previous
                    observed = "finished=${scroll.scroller.isFinished}, dragging=${scroll.dragging}, " +
                        "clocks=${scroll.scene.metrics.activeFrameClocks}, " +
                        "visible=${scroll.scene.mediaVisible}, " +
                        "windowMode=${activity.window.attributes.preferredDisplayModeId}, idleMode=$previous"
                }
                SystemClock.sleep(20)
            }
            assertTrue("Idle screen restores the preceding window mode hint. $observed", restored)
        } finally {
            i.runOnMainSync { activity.finish() }
        }
    }
}
