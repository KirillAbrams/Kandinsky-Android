package dev.kirill.kandinsky.chat.conversation

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.widget.TextView
import androidx.core.view.allViews
import androidx.test.platform.app.InstrumentationRegistry
import dev.kirill.kandinsky.chat.network.NetworkCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KandinskyActionSheetTest {
    @Suppress("DEPRECATION")
    @Test
    fun distinctActionsDrawDistinctIconsAndCloseBeforeDispatch() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".cleanup"))
        check(NetworkCredentials(context).load() == null)
        val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        val activity = instrumentation.startActivitySync(
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        val labels = linkedMapOf(
            "reply" to "Ответить", "forward" to "Переслать", "copy" to "Копировать",
            "edit" to "Изменить", "save" to "Сохранить", "pin" to "Закрепить",
            "report" to "Пожаловаться", "trash" to "Удалить", "search" to "Найти",
            "archive" to "В архив", "default" to "Подтвердить",
        )
        instrumentation.runOnMainSync {
            var displayed: KandinskyActionSheet? = null
            val picked = mutableListOf<String>()
            val items = labels.map { (symbol, label) ->
                KandinskySheetAction(label, symbol, destructive = symbol == "trash") {
                    assertFalse(checkNotNull(displayed).isShowing)
                    picked += symbol
                }
            }
            try {
                val first = KandinskyActionSheet(activity, "Действия", items)
                displayed = first
                first.show()
                val window = checkNotNull(first.window).decorView
                val rows = window.allViews.filterIsInstance<TextView>()
                    .associateBy { it.text.toString() }
                val shapes = (labels.values + "Отмена").map { label ->
                    val row = rows.getValue(label)
                    val glyph = checkNotNull(row.compoundDrawables[0])
                    assertEquals(PixelFormat.TRANSLUCENT, glyph.opacity)
                    val size = 48
                    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                    try {
                        glyph.setBounds(0, 0, size, size)
                        glyph.draw(Canvas(bitmap))
                        val pixels = IntArray(size * size)
                        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
                        val opaque = pixels.indices.filter { Color.alpha(pixels[it]) > 64 }
                        assertTrue("Значок $label оставил пустое поле", opaque.isNotEmpty())
                        assertTrue(opaque.size < pixels.size)
                        opaque
                    } finally {
                        bitmap.recycle()
                    }
                }
                assertEquals(shapes.size, shapes.toSet().size)
                assertTrue(rows.getValue("Отмена").performClick())
                assertFalse(first.isShowing)
                assertTrue(picked.isEmpty())

                val second = KandinskyActionSheet(activity, "Действия", items)
                displayed = second
                second.show()
                val copy = checkNotNull(second.window).decorView.allViews.filterIsInstance<TextView>()
                    .single { it.text.toString() == labels.getValue("copy") }
                val node = copy.createAccessibilityNodeInfo()
                assertEquals("actions.2", node.viewIdResourceName)
                assertTrue(copy.performClick())
                assertEquals(listOf("copy"), picked)
            } finally {
                displayed?.dismiss()
                activity.finish()
            }
        }
    }
}
