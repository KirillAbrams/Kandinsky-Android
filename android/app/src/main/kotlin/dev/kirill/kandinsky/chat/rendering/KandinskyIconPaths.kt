package dev.kirill.kandinsky.chat.rendering

import android.graphics.*
import androidx.core.graphics.withTranslation

enum class IconID {
    PLUS,
    SEND,
    STOP,
    MENU,
    COMPOSE,
    TUNE,
    CHEVRON_DOWN,
}

object KandinskyIconPaths {
    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    private val paths =
        IconID.entries.associateWith { icon ->
            Path().apply {
                fun line(vararg v: Float) {
                    moveTo(v[0], v[1])
                    for (i in 2 until v.size step 2) lineTo(v[i], v[i + 1])
                }
                when (icon) {
                    IconID.MENU -> {
                        for (y in listOf(6f, 11f, 16f)) addRect(
                            3f,
                            y,
                            21f,
                            y + 2f,
                            Path.Direction.CW,
                        )
                    }
                    IconID.COMPOSE -> {
                        line(14f, 3f, 5f, 3f)
                        quadTo(3f, 3f, 3f, 5f)
                        lineTo(3f, 21f)
                        quadTo(3f, 23f, 5f, 23f)
                        lineTo(21f, 23f)
                        quadTo(23f, 23f, 23f, 21f)
                        lineTo(23f, 13f)
                        line(11f, 13f, 20f, 4f)
                        quadTo(21f, 3f, 22f, 4f)
                        lineTo(24f, 6f)
                        quadTo(25f, 7f, 24f, 8f)
                        lineTo(15f, 17f)
                        lineTo(10f, 18f)
                        close()
                    }
                    IconID.CHEVRON_DOWN -> line(6f, 9f, 12f, 15f, 18f, 9f)
                    IconID.TUNE -> {
                        line(3f, 6f, 8f, 6f)
                        line(12f, 6f, 21f, 6f)
                        line(8f, 4f, 8f, 8f)
                        line(3f, 12f, 15f, 12f)
                        line(19f, 12f, 21f, 12f)
                        line(15f, 10f, 15f, 14f)
                        line(3f, 18f, 8f, 18f)
                        line(12f, 18f, 21f, 18f)
                        line(8f, 16f, 8f, 20f)
                    }
                    IconID.PLUS -> {
                        line(
                            19f,
                            13f,
                            13f,
                            13f,
                            13f,
                            19f,
                            11f,
                            19f,
                            11f,
                            13f,
                            5f,
                            13f,
                            5f,
                            11f,
                            11f,
                            11f,
                            11f,
                            5f,
                            13f,
                            5f,
                            13f,
                            11f,
                            19f,
                            11f,
                        )
                        close()
                    }
                    IconID.SEND -> {
                        line(
                            4f,
                            12f,
                            5.41f,
                            13.41f,
                            11f,
                            7.83f,
                            11f,
                            20f,
                            13f,
                            20f,
                            13f,
                            7.83f,
                            18.59f,
                            13.41f,
                            20f,
                            12f,
                            12f,
                            4f,
                        )
                        close()
                    }
                    IconID.STOP -> {
                        addRoundRect(5f, 5f, 19f, 19f, 2f, 2f, Path.Direction.CW)
                    }
                }
            }
        }

    fun draw(c: Canvas, icon: IconID, rect: RectF, color: Int, width: Float = 1.8f) {
        val side = if (icon == IconID.COMPOSE) 26f else 24f
        c.withTranslation(rect.left, rect.top) {
            c.scale(rect.width() / side, rect.height() / side)
            paint.color = color
            paint.strokeWidth = width
            paint.style =
                when (icon) {
                    IconID.MENU,
                    IconID.PLUS,
                    IconID.SEND,
                    IconID.STOP -> Paint.Style.FILL
                    else -> Paint.Style.STROKE
                }
            c.drawPath(paths.getValue(icon), paint)
        }
    }
}
