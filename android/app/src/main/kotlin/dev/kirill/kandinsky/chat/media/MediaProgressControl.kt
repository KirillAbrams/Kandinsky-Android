package dev.kirill.kandinsky.chat.media

import android.content.Context
import android.graphics.*
import android.view.View

class MediaProgressControl(context: Context) : View(context) {
    var fillColor = 0x6B000000
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var foregroundColor = Color.WHITE
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val rect = android.graphics.RectF()
    private var progress: Double? = null
    private var phase = 0f
    private var retry = false

    override fun onInitializeAccessibilityNodeInfo(
        info: android.view.accessibility.AccessibilityNodeInfo
    ) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.viewIdResourceName = if (retry) "media.retry" else "media.cancel"
    }

    init {
        visibility = GONE
        isClickable = true
        contentDescription = "Отменить загрузку"
    }

    fun update(value: Double?, visible: Boolean, retry: Boolean = false) {
        this.retry = retry
        contentDescription = if (retry) "Повторить загрузку" else "Отменить загрузку"
        progress = value
        visibility = if (visible) VISIBLE else GONE
        if (visible) invalidate()
    }

    override fun onDraw(c: android.graphics.Canvas) {
        val d = width / 48f
        paint.style = android.graphics.Paint.Style.FILL
        paint.color = fillColor
        c.drawCircle(width / 2f, height / 2f, width / 2f, paint)
        paint.color = foregroundColor
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
        if (retry) {
            rect.set(16 * d, 16 * d, 32 * d, 32 * d)
            c.drawArc(rect, -90f, 290f, false, paint)
            c.drawLine(21 * d, 12 * d, 27 * d, 16 * d, paint)
            c.drawLine(27 * d, 16 * d, 21 * d, 19 * d, paint)
        } else {
            c.drawLine(18 * d, 18 * d, 30 * d, 30 * d, paint)
            c.drawLine(30 * d, 18 * d, 18 * d, 30 * d, paint)
        }
        if (progress == null && isShown && !retry) {
            phase = (android.os.SystemClock.uptimeMillis() % 1000) * 0.36f
            postInvalidateDelayed(42)
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
