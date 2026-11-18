// Передаём жест удержания и фиксации записи в настоящее окно, чтобы проверить поведение после
// отпускания.

package dev.kirill.kandinsky.chat.support

import android.app.Instrumentation
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.uiautomator.*
import org.junit.Assert.assertTrue

// Ждём ухода старого окна, удерживаем нужную кнопку и проводим пальцем к фиксации. Отпускаем
// палец и проверяем, что событие дошло до системы без дальнейшего удержания.
fun lockNativeCapture(i: Instrumentation, device: UiDevice, id: String) {
    assertTrue(
        "Prior capture has left the window after native retirement",
        device.wait(Until.gone(By.res("recording.screen")), 5000),
    )
    val button =
        checkNotNull(device.wait(Until.findObject(By.res(id).enabled(true)), 10000)) {
            "Capture control $id must be available"
        }
    val rect = button.visibleBounds
    val x = rect.exactCenterX()
    val y = rect.exactCenterY()
    val down = SystemClock.uptimeMillis()
    // Создаём событие с общим временем начала, видом движения и координатами. Проверяем его
    // доставку и освобождаем объект после передачи.
    fun event(action: Int, px: Float, py: Float) {
        val value = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, px, py, 0)
        value.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            assertTrue(
                "Native capture pointer is delivered",
                i.uiAutomation.injectInputEvent(value, true),
            )
        } finally {
            value.recycle()
        }
    }
    event(MotionEvent.ACTION_DOWN, x, y)
    SystemClock.sleep(320)
    val distance = 110 * i.targetContext.resources.displayMetrics.density
    for (step in 1..8) {
        event(MotionEvent.ACTION_MOVE, x, y - distance * step / 8)
        SystemClock.sleep(20)
    }
    event(MotionEvent.ACTION_UP, x, y - distance)
}
