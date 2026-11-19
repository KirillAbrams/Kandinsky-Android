// Показывает ход передачи и кнопку отмены. Перерисовка и вращение зависят от
// видимости, чтобы скрытая кнопка не гоняла кадры.

package dev.kirill.kandinsky.chat.media

import android.content.Context
import android.graphics.*
import android.view.View

class MediaProgressControl(context: Context) : View(context) {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val rect = android.graphics.RectF()
    private var progress: Double? = null
    private var phase = 0f

    // Фиксирует имя кнопки отмены для средств чтения экрана.
    override fun onInitializeAccessibilityNodeInfo(
        info: android.view.accessibility.AccessibilityNodeInfo
    ) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.viewIdResourceName = "media.cancel"
    }

    // Создаёт скрытый доступный для нажатия элемент с начальной подписью отмены. Кнопка появится
    // только при нужной передаче.
    init {
        visibility = GONE
        isClickable = true
        contentDescription = "Отменить загрузку"
    }

    // Запоминает ход и видимость. Если кнопку видно, просит показать новое значение.
    fun update(value: Double?, visible: Boolean) {
        progress = value
        visibility = if (visible) VISIBLE else GONE
        if (visible) invalidate()
    }

    // Рисует фон, дугу и знак действия по текущему размеру. Назначает следующее вращение только
    // пока видна загрузка без известного процента.
    override fun onDraw(c: android.graphics.Canvas) {
        val d = width / 48f
        paint.style = android.graphics.Paint.Style.FILL
        paint.color = 0x6B000000
        c.drawCircle(width / 2f, height / 2f, width / 2f, paint)
        paint.color = Color.WHITE
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = 2.5f * d
        paint.strokeCap = android.graphics.Paint.Cap.ROUND
        rect.set(4 * d, 4 * d, width - 4 * d, height - 4 * d)
        c.drawArc(
            rect,
            if (progress == null) phase else -90f,
            ((progress ?: 0.72) * 360).toFloat(),
            false,
            paint,
        )
        c.drawLine(18 * d, 18 * d, 30 * d, 30 * d, paint)
        c.drawLine(30 * d, 18 * d, 18 * d, 30 * d, paint)
        if (progress == null && isShown) {
            phase = (android.os.SystemClock.uptimeMillis() % 1000) * 0.36f
            postInvalidateDelayed(42)
        }
    }

    // Передаёт нажатие стандартной обработке элемента и отмечает его принятым. Так срабатывают
    // обычное действие и средства доступности.
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
