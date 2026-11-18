// Проверяет повторное использование строки списка Андроида, доступность обеих команд и остановку
// индикатора.

package dev.kirill.kandinsky.chat.chatlist

import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.kirill.kandinsky.chat.app.MainActivity
import dev.kirill.kandinsky.chat.network.StudioThread
import org.junit.Assert.*
import org.junit.Test

class StudioDrawerCellTest {
    // Привязывает занятую, затем свободную строку и проверяет размеры кнопок, разные действия и
    // остановку индикатора. После снятия строки с окна индикатор также должен исчезнуть.
    @Test fun busyRowReusesSafelyAndKeepsIndependentActionTargets() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        instrumentation.waitForIdleSync()
        try {
            instrumentation.runOnMainSync {
                val root = FrameLayout(activity)
                activity.setContentView(root)
                val cell = StudioThreadCell(activity)
                val density = activity.resources.displayMetrics.density
                // Переводит ожидаемые размеры в точки экрана телефона. Иначе тест сравнивал бы
                // разные единицы на устройствах с другой плотностью.
                fun dp(value: Int) = (value * density + 0.5f).toInt()
                root.addView(cell, FrameLayout.LayoutParams(dp(304), dp(44)))
                var opened = 0
                var menuOpened = 0
                val active = StudioThread(1, "Длинное название в одну строку", 0, 0, true, false, 1, true)
                // Привязывает выбранный тестовый чат и выполняет измерение и раскладку. Это даёт
                // проверить итоговые рамки и обработчики сразу после повторного использования.
                fun bind(row: StudioThread) {
                    cell.bind(row, true, { opened++ }, { menuOpened++ }) { view, id -> view.tag = id }
                    cell.measure(
                        View.MeasureSpec.makeMeasureSpec(dp(304), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(dp(44), View.MeasureSpec.EXACTLY),
                    )
                    cell.layout(0, 0, dp(304), dp(44))
                }
                bind(active)
                val title = checkNotNull(cell.findViewWithTag<TextView>("chat.row.1"))
                val actions = checkNotNull(cell.findViewWithTag<ImageView>("chats.actions.1"))
                assertNotNull(actions.drawable)
                val busy = checkNotNull(cell.findViewWithTag<ProgressBar>("chats.generating.1"))
                assertEquals(View.VISIBLE, busy.visibility)
                assertEquals(1, title.maxLines)
                assertTrue(title.right < actions.left)
                assertEquals(dp(44), actions.width)
                title.performClick()
                actions.performClick()
                assertEquals(1, opened)
                assertEquals(1, menuOpened)
                bind(active.copy(id = 2, pinned = false, generating = false))
                assertEquals(View.GONE, busy.visibility)
                assertEquals(dp(16), title.left)
                assertFalse(title.contentDescription.contains("генерация"))
                bind(active)
                root.removeView(cell)
                assertEquals(View.GONE, busy.visibility)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
