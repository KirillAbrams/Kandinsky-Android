// Рисует стрелку назад или знак закрытия на экране просмотра медиа. Обе кнопки используют одни
// правила цвета и размера.

package dev.kirill.kandinsky.chat.media

import android.graphics.*
import android.graphics.drawable.Drawable
import androidx.core.graphics.withTranslation

internal class MediaNavigationDrawable(private val back: Boolean = false) : Drawable() {
    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = if (back) 2f else 1f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    // Масштабирует знак и ставит его по центру. Выбирает стрелку или крест, сохраняя пропорции
    // при изменении рамки.
    override fun draw(canvas: Canvas) {
        val size = minOf(bounds.width(), bounds.height()) * (if (back) 0.5f else 1f)
        canvas.withTranslation(bounds.exactCenterX() - size / 2, bounds.exactCenterY() - size / 2) {
            canvas.scale(size / 24, size / 24)
            paint.style = Paint.Style.STROKE
            if (back) {
                val path = Path()
                path.moveTo(15f, 4f)
                path.lineTo(7f, 12f)
                path.lineTo(15f, 20f)
                canvas.drawPath(path, paint)
            } else {
                canvas.drawLine(6f, 6f, 18f, 18f, paint)
                canvas.drawLine(18f, 6f, 6f, 18f, paint)
            }
        }
    }

    // Меняет прозрачность краски и назначает перерисовку. Иначе кнопка продолжала бы показывать
    // прежнюю яркость.
    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    // Передаёт новый цветовой фильтр краске и обновляет знак. Рисунок сразу учитывает оформление
    // владельца.
    override fun setColorFilter(filter: ColorFilter?) {
        paint.colorFilter = filter
        invalidateSelf()
    }

    // Сообщает, что фон вокруг штрихов прозрачен. Система не должна считать всю рамку
    // закрашенной.
    @Deprecated("Drawable translucency is intrinsic")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
