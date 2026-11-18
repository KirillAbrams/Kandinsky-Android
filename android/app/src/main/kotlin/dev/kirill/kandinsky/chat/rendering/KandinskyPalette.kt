package dev.kirill.kandinsky.chat.rendering

import android.graphics.*

object KandinskyPalette {
    val blue = Color.rgb(0, 132, 255)
    val secondary = Color.rgb(116, 116, 116)
    val green = Color.rgb(126, 174, 91)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val metrics = Paint.FontMetrics()
    private val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val avatarMatrix = Matrix()
    private val avatarShader =
        LinearGradient(
            0f,
            0f,
            0f,
            1f,
            Color.rgb(163, 167, 180),
            Color.rgb(134, 139, 149),
            Shader.TileMode.CLAMP,
        )
    val studioInk = Color.rgb(22, 24, 22)
    val studioMuted = Color.rgb(116, 121, 116)
    val studioSheet = Color.rgb(239, 245, 240)
    val studioSheetHandle = Color.rgb(64, 73, 68)
    val studioSurface = Color.rgb(244, 244, 242)
    val studioBorder = Color.rgb(229, 232, 226)
    val studioOutgoing = Color.rgb(217, 241, 228)
    private val studioLogoPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val studioTitlePaint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = studioInk
            typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        }
    private val studioPolygons =
        listOf(
                floatArrayOf(0f, 6f, 6f, 0f, 6f, 12f, 0f, 18f),
                floatArrayOf(0f, 18f, 6f, 12f, 6f, 18f, 0f, 24f),
                floatArrayOf(6f, 12f, 18f, 0f, 24f, 0f, 12f, 12f),
                floatArrayOf(6f, 12f, 12f, 12f, 24f, 24f, 18f, 24f),
            )
            .map { vertices ->
                Path().apply {
                    moveTo(vertices[0], vertices[1])
                    for (index in 2 until vertices.size step 2) lineTo(
                        vertices[index],
                        vertices[index + 1],
                    )
                    close()
                }
            }

    fun studioBrand(
        canvas: Canvas,
        x: Float,
        top: Float,
        scale: Float,
        large: Boolean = false,
        centered: Boolean = true,
    ) {
        val side = (if (large) 32f else 24f) * scale
        val pointSize = if (large) 25f else 20f
        val title = "Kandinsky"
        val gap = 9 * scale
        studioTitlePaint.textSize = pointSize * scale
        studioTitlePaint.letterSpacing = -0.7f / pointSize
        // В исходном оформлении верхняя панель, боковое меню и приветствие имеют разное положение
        // строки названия
        val titleWidth = (if (large) 109.95f else 86.70f) * scale
        val baselineOffset =
            when {
                large -> 24.67f
                centered -> 19.64f
                else -> 18.50f
            }
        val left = if (centered) x - (side + gap + titleWidth) / 2 else x
        val save = canvas.save()
        canvas.translate(left, top)
        canvas.scale(side / 24, side / 24)
        for ((index, path) in studioPolygons.withIndex()) {
            studioLogoPaint.color =
                if (index == 1) Color.rgb(25, 207, 161) else Color.rgb(56, 227, 178)
            canvas.drawPath(path, studioLogoPaint)
        }
        canvas.restoreToCount(save)
        val titleSave = canvas.save()
        canvas.translate(left + side + gap, top + baselineOffset * scale)
        // Системное рисование и исходный клиент по-разному измеряют видимые границы того же шрифта
        val paintedTitleWidth = titleWidth + if (large) 0f else 0.67f * scale
        canvas.scale(paintedTitleWidth / studioTitlePaint.measureText(title), 1f)
        canvas.drawText(title, 0f, 0f, studioTitlePaint)
        canvas.restoreToCount(titleSave)
    }

    fun avatar(c: Canvas, rect: RectF) {
        avatarMatrix.setScale(1f, rect.height())
        avatarMatrix.postTranslate(rect.left, rect.top)
        avatarShader.setLocalMatrix(avatarMatrix)
        avatarPaint.shader = avatarShader
        c.drawOval(rect, avatarPaint)
    }

    fun text(
        c: Canvas,
        value: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int = Color.BLACK,
        bold: Boolean = false,
        center: Boolean = false,
    ) {
        textPaint.color = color
        textPaint.textSize = size
        textPaint.typeface = if (bold) medium else Typeface.DEFAULT
        textPaint.textAlign = if (center) Paint.Align.CENTER else Paint.Align.LEFT
        textPaint.getFontMetrics(metrics)
        c.drawText(value, x, y - metrics.ascent, textPaint)
    }
}
