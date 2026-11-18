// Хранит оформление и действия одной строки списка Андроида. Одну и ту же строку можно привязать
// к новому чату после прокрутки.

package dev.kirill.kandinsky.chat.chatlist

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.AbsListView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import dev.kirill.kandinsky.chat.R
import dev.kirill.kandinsky.chat.network.StudioThread
import dev.kirill.kandinsky.chat.rendering.KandinskyPalette

class StudioThreadCell(context: Context) : FrameLayout(context) {
    private val highlight = View(context)
    private val marker = ImageView(context)
    private val busy = ProgressBar(context, null, android.R.attr.progressBarStyleSmall)
    private val title = TextView(context)
    private val actions = ImageView(context)
    private var generating = false

    // Создаёт подсветку, значок, индикатор и отдельные области названия и действий. Ограничение
    // одной строкой удерживает список от скачков высоты.
    init {
        layoutParams = AbsListView.LayoutParams(-1, dp(44))
        setBackgroundColor(Color.WHITE)
        highlight.background =
            GradientDrawable().apply {
                setColor(Color.rgb(235, 249, 242))
                cornerRadius = dp(10).toFloat()
            }
        marker.setImageResource(R.drawable.heroicons_paper_clip)
        marker.imageTintList = ColorStateList.valueOf(KandinskyPalette.studioMuted)
        busy.indeterminateTintList = ColorStateList.valueOf(KandinskyPalette.studioMuted)
        busy.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        title.textSize = 15f
        title.setTextColor(KandinskyPalette.studioInk)
        title.gravity = Gravity.CENTER_VERTICAL
        title.setSingleLine()
        title.ellipsize = TextUtils.TruncateAt.END
        actions.setImageResource(R.drawable.kandinsky_drawer_more)
        actions.scaleType = ImageView.ScaleType.CENTER_INSIDE
        actions.contentDescription = "Действия с чатом"
        for (view in listOf(highlight, marker, busy, title, actions)) addView(view)
    }

    // Заменяет название, озвучивание, видимость знаков и обработчики для нового чата. Без полной
    // привязки действие могло бы обратиться к уже прокрученному чату.
    fun bind(
        row: StudioThread,
        selected: Boolean,
        open: () -> Unit,
        menu: (View) -> Unit,
        identify: (View, String) -> Unit,
    ) {
        generating = row.generating
        marker.visibility = if (row.pinned) VISIBLE else GONE
        highlight.visibility = if (selected) VISIBLE else GONE
        title.text = row.title
        title.isSelected = selected
        title.typeface = Typeface.create(Typeface.DEFAULT, if (selected) 500 else 400, false)
        title.contentDescription = if (generating) "${row.title}, генерация выполняется" else row.title
        identify(title, "chat.row.${row.id}")
        identify(actions, "chats.actions.${row.id}")
        identify(busy, "chats.generating.${row.id}")
        title.setOnClickListener { open() }
        actions.setOnClickListener { menu(actions) }
        updateAnimation()
        requestLayout()
    }

    // Включает показ индикатора при присоединении к окну, если чат занят. Повторное появление
    // строки отражает актуальное состояние.
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimation()
    }

    // Скрывает индикатор при снятии строки с окна. Вне списка ему не нужно продолжать движение.
    override fun onDetachedFromWindow() {
        busy.visibility = GONE
        super.onDetachedFromWindow()
    }

    // Проверяет наличие строки в окне и занятость. Индикатор доступен только при обоих условиях.
    private fun updateAnimation() {
        busy.visibility = if (generating && isAttachedToWindow) VISIBLE else GONE
    }

    // Назначает точные размеры значку, индикатору, названию и меню. Ширину текста считает из
    // свободного места, чтобы цели нажатий не наложились.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // Передаёт каждому элементу точную ширину и высоту не меньше единицы. Так даже тесная
        // строка имеет допустимые размеры для раскладки.
        fun measure(view: View, width: Int, height: Int) {
            view.measure(
                MeasureSpec.makeMeasureSpec(width.coerceAtLeast(1), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height.coerceAtLeast(1), MeasureSpec.EXACTLY),
            )
        }
        measure(highlight, measuredWidth - dp(16), measuredHeight - dp(2))
        measure(marker, dp(18), dp(18))
        measure(busy, dp(14), dp(14))
        measure(
            title,
            measuredWidth - titleLeft() - titleRightInset(),
            measuredHeight,
        )
        measure(actions, dp(44), measuredHeight)
    }

    // Размещает элементы по их отдельным областям и оставляет меню справа. Название остаётся
    // доступным отдельно от кнопки действий.
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top
        highlight.layout(dp(8), dp(1), width - dp(8), height - dp(1))
        marker.layout(width - dp(80), height / 2 - dp(9), width - dp(62), height / 2 + dp(9))
        busy.layout(dp(16), height / 2 - dp(7), dp(30), height / 2 + dp(7))
        title.layout(titleLeft(), 0, width - titleRightInset(), height)
        val actionsRight = width - dp(12)
        actions.layout(actionsRight - dp(44), 0, actionsRight, height)
    }

    // Подбирает левый отступ в зависимости от индикатора генерации. Без него название начиналось
    // бы поверх индикатора.
    private fun titleLeft() = dp(if (generating) 40 else 16)

    // Оставляет справа место под меню и при необходимости значок закрепления. Текст не заходит на
    // независимые кнопки.
    private fun titleRightInset() = dp(if (marker.isVisible) 86 else 64)

    // Пересчитывает размеры под плотность экрана с округлением. Зона нажатия сохраняет привычный
    // размер на разных телефонах.
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
