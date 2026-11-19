// Проверяем постоянство номеров объектов для управления экраном и уведомление системы при смене
// состава сообщений.

package dev.kirill.kandinsky.chat.rendering

import android.graphics.RectF
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class AccessibilityIdentityTest {
    private val i = InstrumentationRegistry.getInstrumentation()

    // Убираем соседнее сообщение и проверяем, что оставшийся объект сохранил номер. Старый номер
    // не должен открывать другое сообщение или вызывать его действие.
    @Test
    fun visibleIdentitySurvivesNeighborRemovalAndOldIDCannotActivateDifferentMessage() {
        i.runOnMainSync {
            val host = View(i.targetContext)
            var calls = 0
            var rows =
                listOf(
                    NativeAction(
                        1000,
                        "message.a",
                        "A",
                        RectF(0f, 0f, 100f, 40f),
                        action = { calls++ },
                    ),
                    NativeAction(
                        1001,
                        "message.b",
                        "B",
                        RectF(0f, 40f, 100f, 80f),
                        action = { calls++ },
                    ),
                )
            val provider = NativeAccessibility(host, "test.history", { rows })
            assertEquals("B", provider.createAccessibilityNodeInfo(1001)!!.contentDescription)
            assertEquals("A", provider.createAccessibilityNodeInfo(1000)!!.contentDescription)
            rows =
                listOf(
                    NativeAction(
                        1000,
                        "message.b",
                        "B",
                        RectF(0f, 0f, 100f, 40f),
                        action = { calls++ },
                    )
                )
            provider.notifyChanged()
            assertEquals(
                "Retained message must keep its native virtual identity",
                "B",
                provider.createAccessibilityNodeInfo(1001)?.contentDescription,
            )
            rows =
                listOf(
                    NativeAction(
                        1000,
                        "message.c",
                        "C",
                        RectF(0f, 0f, 100f, 40f),
                        action = { calls++ },
                    )
                )
            provider.notifyChanged()
            assertFalse(
                "An evicted message ID cannot activate another message",
                provider.performAction(1000, AccessibilityNodeInfo.ACTION_CLICK, null),
            )
            assertEquals(0, calls)
        }
    }

    // Следим за длинным нажатием и фокусом. После удаления строки система освобождает её фокус
    // и получает уведомление об изменившемся дереве.
    @Test
    fun structuralChangesClearRemovedFocusAndPublishSubtreeInvalidation() {
        // Проверка системных событий требует явно подключённой службы управления экраном
        i.uiAutomation
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        var enabled = false
        while (!enabled && android.os.SystemClock.uptimeMillis() < deadline) {
            i.runOnMainSync {
                enabled =
                    i.targetContext
                        .getSystemService(
                            android.view.accessibility.AccessibilityManager::class.java
                        )
                        .isEnabled
            }
            if (!enabled) android.os.SystemClock.sleep(10)
        }
        i.runOnMainSync {
            assertTrue(
                i.targetContext
                    .getSystemService(android.view.accessibility.AccessibilityManager::class.java)
                    .isEnabled
            )
            var type = 0
            val events = mutableListOf<Int>()
            val parent =
                object : FrameLayout(i.targetContext) {
                    // Сохраняем вид события у родителя и подтверждаем его приём. Это позволяет
                    // отличить изменение дерева от обычного события выбора.
                    override fun requestSendAccessibilityEvent(
                        child: View,
                        event: AccessibilityEvent,
                    ): Boolean {
                        type = event.contentChangeTypes
                        events.add(event.eventType)
                        return true
                    }
                }
            val host = View(i.targetContext)
            parent.addView(host)
            var longPresses = 0
            val menuAction = 0x01000035
            var menuCalls = 0
            var rows = listOf(NativeAction(
                1000, "message.first", "Подпись к сообщению", RectF(0f, 0f, 100f, 40f),
                longClick = { longPresses++ },
                customActions = listOf(NativeExtraAction(menuAction, "Действия") { menuCalls++ }),
            ))
            val provider = NativeAccessibility(host, "test.history", { rows })
            val id = 1000
            assertNull(provider.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY))
            assertTrue(provider.performAction(id, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null))
            val focused = checkNotNull(provider.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY))
            assertTrue(focused.isAccessibilityFocused)
            assertEquals("message.first", focused.viewIdResourceName)
            assertTrue(focused.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS })
            assertTrue(provider.performAction(id, AccessibilityNodeInfo.ACTION_LONG_CLICK, null))
            assertTrue(provider.performAction(id, menuAction, null))
            assertFalse(provider.performAction(id, menuAction + 1, null))
            assertEquals(1, longPresses)
            assertEquals(1, menuCalls)
            assertTrue(provider.performAction(id, AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null))
            assertNull(provider.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY))

            assertTrue(provider.performAction(id, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null))
            rows = emptyList()
            provider.notifyChanged()
            assertNull(provider.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY))
            assertFalse(provider.performAction(id, AccessibilityNodeInfo.ACTION_LONG_CLICK, null))
            assertEquals(AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE, type)
            assertEquals(
                listOf(
                    AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
                    AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED,
                    AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
                    AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED,
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                ),
                events,
            )
        }
    }
}
