// Показывает загруженное видео Андроида в панели и на всём экране. Разворот меняет расположение
// одной поверхности и сохраняет текущее проигрывание.

package dev.kirill.kandinsky.chat.attachments

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.ComponentDialog
import androidx.activity.addCallback
import androidx.core.graphics.drawable.toDrawable
import dev.kirill.kandinsky.chat.R
import dev.kirill.kandinsky.chat.domain.Attachment
import dev.kirill.kandinsky.chat.media.InlinePlayerSurface
import dev.kirill.kandinsky.chat.media.MediaCoordinator
import dev.kirill.kandinsky.chat.rendering.KandinskyPalette
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

class StudioUploadPreview(
    private val activity: Activity,
    private val surface: InlinePlayerSurface,
    private val player: MediaCoordinator,
    private val attachment: Attachment,
    private val canEnhance: Boolean,
    private val onClose: (Boolean) -> Unit,
) {
    private val density = activity.resources.displayMetrics.density

    // Переводит размеры кнопок и рамок в точки текущего экрана. Управление остаётся удобным при
    // другой плотности.
    private fun dp(value: Int) = (value * density).roundToInt()

    private val dialog = ComponentDialog(activity, android.R.style.Theme_Material_Light_NoActionBar)
    private val root = FrameLayout(activity)
    private val scrim =
        View(activity).apply {
            contentDescription = "Закрыть предпросмотр"
            identify("studio.upload.dismiss")
            setOnClickListener { close() }
        }
    private val panel =
        FrameLayout(activity).apply {
            isClickable = true
            background =
                GradientDrawable().apply {
                    setColor(KandinskyPalette.studioSheet)
                    val radius = dp(28).toFloat()
                    cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
                }
        }
    private val handle =
        View(activity).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background =
                GradientDrawable().apply {
                    setColor(KandinskyPalette.studioSheetHandle)
                    cornerRadius = dp(2).toFloat()
                }
        }
    private val hitFrame =
        View(activity).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnClickListener { setExpanded(true) }
        }
    private val remaining =
        TextView(activity).apply {
            identify("studio.upload.remaining")
            gravity = Gravity.CENTER
            includeFontPadding = false
            textSize = 11f
            setTextColor(Color.WHITE)
            setPadding(dp(5), dp(2), dp(5), dp(2))
            background =
                GradientDrawable().apply {
                    setColor(0x99000000.toInt())
                    cornerRadius = dp(5).toFloat()
                }
        }
    private val expand =
        icon(
            R.drawable.kandinsky_result_expand,
            KandinskyPalette.studioInk,
            "На весь экран",
            "studio.upload.expand",
        ) {
            setExpanded(true)
        }
    private val collapse =
        icon(
            R.drawable.kandinsky_upload_close,
            Color.WHITE,
            "Закрыть просмотр",
            "studio.upload.collapse",
        ) {
            setExpanded(false)
        }
    private val enhance =
        TextView(activity).apply {
            text = "Улучшить видео"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(KandinskyPalette.studioInk)
            identify("studio.upload.enhance")
            setOnClickListener { close(enhancing = true) }
        }
    private var isExpanded = false
    private var closed = false
    private var topInset = 0
    private var bottomInset = 0
    private var measuredWidth = 0
    private var measuredHeight = 0

    // Передаёт прежний видеослой в окно предпросмотра и следит за размерами и системными
    // полосами. Кнопка назад возвращает с полного экрана либо закрывает панель.
    fun show() {
        surface.beginPresentationMove()
        (surface.parent as? ViewGroup)?.removeView(surface)
        root.addView(scrim, frame(-1, -1))
        root.addView(panel)

        root.addView(surface)
        root.addView(handle)
        root.addView(hitFrame)
        root.addView(remaining)
        root.addView(expand)
        root.addView(collapse)
        if (canEnhance) root.addView(enhance)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(root)
        dialog.setOnDismissListener { close() }
        dialog.onBackPressedDispatcher.addCallback(dialog) {
            if (isExpanded) setExpanded(false) else close()
        }
        dialog.window?.apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            @Suppress("DEPRECATION")
            decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            if (Build.VERSION.SDK_INT >= 30) setDecorFitsSystemWindows(false)
            @Suppress("DEPRECATION")
            statusBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION")
            navigationBarColor = Color.TRANSPARENT
        }
        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION") val top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION") val bottom = insets.systemWindowInsetBottom
            if (topInset != top || bottomInset != bottom) {
                topInset = top
                bottomInset = bottom
                layout()
            }
            insets
        }
        root.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val width = right - left
            val height = bottom - top
            if (width != measuredWidth || height != measuredHeight) {
                measuredWidth = width
                measuredHeight = height
                layout()
            }
        }
        dialog.show()
        dialog.window?.setLayout(-1, -1)
        root.requestApplyInsets()
        surface.finishPresentationMove()
        setExpanded(false)
    }

    // Обновляет остаток времени только в панели и включает разворот после готовности
    // проигрывателя. Пока видео не готово, нажатие не откроет пустой вид.
    fun update() {
        if (closed || isExpanded) return
        val duration = player.durationMs.takeIf { it > 0 } ?: attachment.durationMs ?: 0
        val seconds = ceil((duration - player.positionMs).coerceAtLeast(0) / 1000.0).toLong()
        val value = String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
        if (remaining.text != value) remaining.text = value
        val ready = player.isReady && player.lastError == null
        expand.isEnabled = ready
        hitFrame.isClickable = ready
    }

    // Скрывает элементы панели, меняет фон и закругление и перекладывает ту же поверхность.
    // Переход не требует повторной подготовки ролика.
    private fun setExpanded(expanded: Boolean) {
        if (expanded && !player.isReady) return
        isExpanded = expanded
        root.setBackgroundColor(if (expanded) Color.BLACK else 0x8A000000.toInt())
        for (view in listOf(scrim, panel, handle, hitFrame, remaining, expand, enhance)) {
            view.visibility = if (expanded) View.GONE else View.VISIBLE
        }
        collapse.visibility = if (expanded) View.VISIBLE else View.GONE

        surface.studioPreviewBackground = !expanded
        val radius = if (expanded) 0f else dp(12).toFloat()
        surface.outlineProvider =
            object : ViewOutlineProvider() {
                // Задаёт рамку обрезки с радиусом текущего режима просмотра. В панели углы
                // скруглены, а полноэкранный слой занимает прямоугольник.
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
        surface.clipToOutline = !expanded
        surface.invalidateOutline()
        layout()
        update()
    }

    // Вычисляет рамки после получения размеров окна. Для панели удерживает квадрат над нижней
    // полосой, а полный экран отдаёт видео целиком.
    private fun layout() {
        val width = measuredWidth
        val height = measuredHeight
        if (width <= 0 || height <= 0) return
        if (isExpanded) {
            surface.layoutParams = frame(-1, -1)

            collapse.layoutParams = frame(dp(48), dp(48), dp(4), topInset)
            return
        }

        val square =
            minOf(width - dp(40), height - topInset - bottomInset - dp(136)).coerceAtLeast(1)
        val top = height - bottomInset - dp(136) - square
        val left = (width - square) / 2
        val imageTop = top + dp(68)
        scrim.layoutParams = frame(-1, top.coerceAtLeast(0))
        panel.layoutParams = frame(-1, height - top, 0, top)
        handle.layoutParams = frame(dp(32), dp(4), (width - dp(32)) / 2, top + dp(22))
        surface.layoutParams = frame(square, square, left, imageTop)
        hitFrame.layoutParams = frame(square, square, left, imageTop)
        remaining.layoutParams = frame(-2, -2, left + dp(6), imageTop + dp(6))
        expand.layoutParams = frame(dp(48), dp(48), left + square - dp(48), imageTop + square)
        enhance.layoutParams = frame(dp(142), dp(48), left, imageTop + square)
    }

    // Снимает поверхность и отсоединяет проигрыватель до сообщения о результате. Повторное
    // закрытие не выполнит улучшение или отмену второй раз.
    fun close(enhancing: Boolean = false) {
        if (closed) return
        closed = true
        dialog.setOnDismissListener(null)
        (surface.parent as? ViewGroup)?.removeView(surface)
        surface.detachPlayer()
        surface.studioPreviewBackground = false
        surface.clipToOutline = false

        dialog.dismiss()
        onClose(enhancing)
    }

    // Создаёт кнопку с рисунком, доступной подписью и действием. Оформление и имя назначаются
    // вместе, чтобы системное управление нашло нужную команду.
    private fun icon(resource: Int, tint: Int, label: String, id: String, onTap: () -> Unit) =
        ImageButton(activity).apply {
            setImageResource(resource)
            setColorFilter(tint)
            background = null
            setPadding(dp(14), dp(14), dp(14), dp(14))
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = label
            identify(id)
            setOnClickListener { onTap() }
        }

    // Назначает элементу устойчивое имя через сведения доступности. Имя остаётся тем же при смене
    // состояния или подписи.
    private fun View.identify(value: String) {
        accessibilityDelegate =
            object : View.AccessibilityDelegate() {
                // Сохраняет обычные свойства элемента и добавляет заданное имя. Проверка может
                // искать кнопку без привязки к тексту.
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfo,
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.viewIdResourceName = value
                }
            }
    }

    // Переводит размер и координаты в рамку с отступами от начала и верха. Все элементы окна
    // рассчитываются от одной области.
    private fun frame(width: Int, height: Int, x: Int = 0, y: Int = 0) =
        FrameLayout.LayoutParams(width, height, Gravity.TOP or Gravity.START).apply {
            marginStart = x
            topMargin = y
        }
}
