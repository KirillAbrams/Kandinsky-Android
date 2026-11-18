// Даты в ленте на андроиде хранят готовый рисунок подписи и фона. Прокрутка двигает его по
// указанному месту, а системе доступности отдаёт прямоугольник с тем же текстом.

package dev.kirill.kandinsky.chat.rendering

import android.graphics.*
import java.text.SimpleDateFormat
import java.util.*

class ScrollingDays {
    private class Node(val label: String, val width: Int, val height: Int, val studio: Boolean) {
        val native = RenderNode("Kandinsky calendar divider")
    }

    private val nodes = mutableMapOf<Long, Node>()
    private val font = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(100, 55, 108, 59) }
    private val studioFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(228, 240, 231) }
    private val studioInk = Color.rgb(62, 100, 84)
    private val boldFont = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val formatter = SimpleDateFormat("d MMM", Locale("ru"))
    private var minute = Long.MIN_VALUE
    private var today = Calendar.getInstance()
    val liveCount
        // Показываем число удерживаемых заголовков дня. Проверки убеждаются, что прокрутка
        // освобождает ушедшие плашки.
        get() = nodes.size

    // Обновляем понятие сегодняшнего дня, измеряем нужные даты и удаляем ушедшие. Записываем
    // новый рисунок при смене текста, размера или оформления. Для оставшихся меняем только
    // положение.
    fun apply(
        days: List<HistoryDay>,
        offset: Double,
        width: Int,
        d: Float,
        studio: Boolean = false,
    ): List<NativeAction> {
        val now = System.currentTimeMillis() / 60000
        if (now != minute) {
            minute = now
            today = Calendar.getInstance()
        }
        val wanted = days.map { it.messageID }.toSet()
        nodes.keys
            .filter { it !in wanted }
            .forEach { nodes.remove(it)?.native?.discardDisplayList() }
        return days.mapIndexed { n, day ->
            val date = Calendar.getInstance().apply { timeInMillis = day.timestamp }
            val label =
                if (
                    today.get(Calendar.YEAR) == date.get(Calendar.YEAR) &&
                        today.get(Calendar.DAY_OF_YEAR) == date.get(Calendar.DAY_OF_YEAR)
                )
                    "Сегодня"
                else formatter.format(Date(day.timestamp)).replace(".", "")
            font.textSize = (if (studio) 12 else 13) * d
            font.typeface = if (studio) Typeface.DEFAULT else boldFont
            val padding = (if (studio) 20 else 24) * d
            val w =
                minOf(
                    maxOf(1, width - (24 * d).toInt()),
                    kotlin.math.ceil(font.measureText(label) + padding).toInt(),
                )
            val h = ((if (studio) 23 else 25) * d).toInt().coerceAtLeast(1)
            var node = nodes[day.messageID]
            if (
                node == null ||
                    node.label != label ||
                    node.width != w ||
                    node.height != h ||
                    node.studio != studio
            ) {
                node?.native?.discardDisplayList()
                node = Node(label, w, h, studio)
                nodes[day.messageID] = node
                node.native.setPosition(0, 0, w, h)
                val c = node.native.beginRecording(w, h)
                try {
                    drawBadge(c, node, 0f, 0f, d)
                } finally {
                    node.native.endRecording()
                }
            }
            val x = (width - w) / 2f
            val y = (day.top - offset + (day.height - h) / 2).toFloat()
            node.native.translationX = x
            node.native.translationY = y
            NativeAction(
                2000 + n,
                "chat.day.${day.messageID}",
                label,
                RectF(x, y, x + w, y + h),
                button = false,
            )
        }
    }

    // Рисуем готовые плашки для переданной области. Им не нужно повторно измерять или составлять
    // подпись на каждом кадре.
    fun draw(c: Canvas) {
        for (node in nodes.values) c.drawRenderNode(node.native)
    }

    // Рисуем фон и центрированную дату по виду чата. Рамка из измерения дальше используется и для
    // озвучивания.
    private fun drawBadge(c: Canvas, node: Node, x: Float, y: Float, d: Float) {
        val radius = if (node.studio) 12 * d else node.height / 2f
        c.drawRoundRect(
            x,
            y,
            x + node.width,
            y + node.height,
            radius,
            radius,
            if (node.studio) studioFill else fill,
        )
        KandinskyPalette.text(
            c,
            node.label,
            x + node.width / 2f,
            y + 4 * d,
            (if (node.studio) 12 else 13) * d,
            if (node.studio) studioInk else Color.WHITE,
            bold = !node.studio,
            center = true,
        )
    }

    // Освобождаем записи рисования всех плашек. Повторное открытие соберёт только даты новой
    // видимой области.
    fun close() {
        nodes.values.forEach { it.native.discardDisplayList() }
        nodes.clear()
    }
}
