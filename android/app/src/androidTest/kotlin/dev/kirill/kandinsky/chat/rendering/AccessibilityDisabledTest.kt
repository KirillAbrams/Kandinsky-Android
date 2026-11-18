// Проверяем поведение специальных возможностей, когда их службы выключены. Внутренний учёт
// записей продолжается без отправки лишних системных событий.

package dev.kirill.kandinsky.chat.rendering

import android.graphics.RectF
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class AccessibilityDisabledTest {
    // Добавляем, выбираем и удаляем доступный узел при отключённой службе. Родитель не получает
    // события, а удалённый номер не должен найти чужое сообщение.
    @Test
    fun disabledServicesSkipContentAndFocusEventsWithoutLosingNodeBookkeeping() {
        val i = InstrumentationRegistry.getInstrumentation()
        i.runOnMainSync {
            val context = i.targetContext
            assertFalse(
                "Regression requires ordinary disabled accessibility",
                context.getSystemService(AccessibilityManager::class.java).isEnabled,
            )
            var events = 0
            val parent =
                object : FrameLayout(context) {
                    // Перехватываем событие на родительском экране и прибавляем его к счётчику.
                    // Лишняя отправка становится видна, даже если система её проигнорировала бы.
                    override fun requestSendAccessibilityEvent(
                        child: View,
                        event: AccessibilityEvent,
                    ): Boolean {
                        events++
                        return true
                    }
                }
            val host = View(context)
            parent.addView(host)
            var rows = listOf(NativeAction(1000, "message.a", "A", RectF(0f, 0f, 40f, 40f)))
            val provider = NativeAccessibility(host, "test.disabled", { rows })
            provider.notifyChanged()
            assertEquals("Disabled content notification must not reach native parent", 0, events)
            assertEquals("A", provider.createAccessibilityNodeInfo(1000)?.contentDescription)
            assertTrue(
                provider.performAction(1000, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
            )
            assertEquals("Disabled focus notification must not reach native parent", 0, events)
            rows = emptyList()
            provider.notifyChanged()
            assertEquals("Removing focused node must not dispatch when disabled", 0, events)
            assertNull(provider.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY))
            rows = listOf(NativeAction(1000, "message.b", "B", RectF(0f, 0f, 40f, 40f)))
            provider.notifyChanged()
            assertNull(
                "Evicted virtual ID must not alias a different message",
                provider.createAccessibilityNodeInfo(1000),
            )
        }
    }
}
