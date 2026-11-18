// Нижняя панель на андроиде собирает список моделей, выбор файлов и параметры генерации.
// Ограничения приходят от модели, а форма приводит старый черновик к доступным значениям и
// проверяет числовой ключ перед сохранением.

package dev.kirill.kandinsky.chat.conversation

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.icu.text.BreakIterator
import android.os.Build
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.StaticLayout
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.graphics.withTranslation
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isEmpty
import dev.kirill.kandinsky.chat.R
import dev.kirill.kandinsky.chat.attachments.AttachmentSource
import dev.kirill.kandinsky.chat.domain.KandinskyOptions
import dev.kirill.kandinsky.chat.domain.StudioCapabilities
import dev.kirill.kandinsky.chat.domain.StudioModel
import dev.kirill.kandinsky.chat.domain.StudioUpload
import dev.kirill.kandinsky.chat.rendering.KandinskyPalette
import java.util.Locale
import kotlin.math.roundToInt

internal class StudioGenerationSheet(
    private val context: Context,
    private var options: KandinskyOptions,
) {
    private val density = context.resources.displayMetrics.density
    private val mint = Color.rgb(56, 227, 178)
    private val listText = Color.rgb(23, 29, 26)
    private val chipFill = Color.rgb(206, 233, 219)
    private val chipInk = Color.rgb(52, 76, 66)
    private val chipOutline = Color.rgb(191, 201, 195)
    private val dialog = Dialog(context)
    private var scrollControlled = false
    private val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    // Создаём диалог с затемнением, верхней ручкой и прокручиваемой областью. Жест вниз действует
    // на ручку, чтобы внутри длинной формы можно было прокручивать поля.
    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(true)
        val radius = dp(28).toFloat()
        root.background =
            GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
        val grip =
            FrameLayout(context).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setOnClickListener { dialog.dismiss() }
                var down = 0f
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            down = event.rawY
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (event.rawY - down >= dp(24)) view.performClick()
                            true
                        }
                        else -> true
                    }
                }
            }
        val handle =
            View(context).apply { background = rounded(KandinskyPalette.studioSheetHandle, 2) }
        grip.addView(handle, FrameLayout.LayoutParams(dp(32), dp(4), Gravity.CENTER))
        root.addView(grip, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)))
        val scroll =
            object : ScrollView(context) {
                // Ограничиваем высоту прокрутки долей окна и доступной высотой от системы.
                // Настройкам отдаём больше места, а коротким спискам оставляем формат нижней
                // панели.
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val screen =
                        if (Build.VERSION.SDK_INT >= 30) {
                            context
                                .getSystemService(WindowManager::class.java)
                                .currentWindowMetrics
                                .bounds
                                .height()
                        } else {
                            context.resources.displayMetrics.heightPixels
                        }
                    val cap =
                        if (scrollControlled) (screen * 0.85f).roundToInt() + dp(16)
                        else (screen * 9f / 16f).toInt() - dp(48)
                    val height =
                        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) cap
                        else minOf(cap, MeasureSpec.getSize(heightMeasureSpec))
                    super.onMeasure(
                        widthMeasureSpec,
                        MeasureSpec.makeMeasureSpec(maxOf(height, 0), MeasureSpec.AT_MOST),
                    )
                }
            }
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(body, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        dialog.setContentView(root)
    }

    // Показываем пункт каждой модели, передавая описание и отметку текущего выбора. Если список
    // пуст, сообщаем об ошибке прямо в открытом окне.
    fun showModels(models: List<StudioModel>, onSelect: (StudioModel) -> Unit) {
        body.setPadding(dp(12), 0, dp(12), dp(24))
        body.addView(
            text("Выберите модель", 23, weight = 600).apply {
                gravity = Gravity.CENTER_VERTICAL
                letterSpacing = -0.6f / 23
                minimumHeight = dp(65)
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }
        )
        if (models.isEmpty()) {
            body.addView(
                text("Не удалось загрузить модели", 15).apply {
                    setPadding(dp(16), dp(16), dp(16), dp(16))
                }
            )
        }
        for (model in models) {
            val icon =
                if (model.id == "image") R.drawable.kandinsky_attachment_photo
                else R.drawable.kandinsky_model_video
            addTile(model.name, model.description, icon, selected = model.id == options.model) {
                onSelect(model)
            }
        }
        show()
    }

    // Показываем входы в галерею, камеру и выбор видео. Подпись поясняет предел видео до открытия
    // системного выбора, а вызвавший экран получает выбранный источник.
    fun showAttachments(onSelect: (AttachmentSource) -> Unit) {
        (root.background as GradientDrawable).setColor(KandinskyPalette.studioSheet)
        addTile(
            "Оживить фото из галереи",
            "Видео из фотографии",
            R.drawable.kandinsky_attachment_photo,
        ) {
            onSelect(AttachmentSource.PHOTO)
        }
        addTile("Снять фото", "", R.drawable.kandinsky_attachment_camera) {
            onSelect(AttachmentSource.CAMERA)
        }
        addTile(
            "Загрузить и посмотреть видео",
            "MP4 до 20 МБ и 60 секунд",
            R.drawable.kandinsky_model_video,
        ) {
            onSelect(AttachmentSource.VIDEO)
        }
        show()
    }

    // Добавляем не более пяти последних ссылок на файлы в уже открытый диалог. Если окно успели
    // закрыть или файлов нет, список не меняем.
    fun appendRecentUploads(uploads: List<StudioUpload>, onSelect: (StudioUpload) -> Unit) {
        if (!dialog.isShowing || uploads.isEmpty()) return
        val divider = FrameLayout(context)
        val rule = View(context).apply { setBackgroundColor(chipOutline) }
        divider.addView(rule, FrameLayout.LayoutParams(MATCH_PARENT, dp(1), Gravity.CENTER))
        body.addView(divider, LinearLayout.LayoutParams(MATCH_PARENT, dp(16)))
        body.addView(
            text("Недавние загрузки", 15).apply {
                gravity = Gravity.CENTER
                setTextColor(KandinskyPalette.studioMuted)
                minimumHeight = dp(46)
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )
        for (upload in uploads.take(5)) {
            val icon =
                if (upload.isVideo) R.drawable.kandinsky_recent_video
                else R.drawable.kandinsky_attachment_photo
            addTile(upload.title, "", icon, "studio.recent." + upload.blobId) { onSelect(upload) }
        }
    }

    // Собираем один пункт меню с подписью, значком и признаком выбора. При нажатии сначала
    // закрываем окно, затем передаём действие, чтобы новое окно не оказалось под старым.
    private fun addTile(
        title: String,
        subtitle: String,
        resource: Int,
        id: String? = null,
        selected: Boolean = false,
        onSelect: () -> Unit,
    ) {
        val row =
            LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(if (subtitle.isEmpty()) 56 else 72)
                setPadding(dp(16), dp(8), dp(24), dp(8))
                contentDescription = if (subtitle.isEmpty()) title else "$title, $subtitle"
                isFocusable = true
                isSelected = selected
                accessibilityDelegate =
                    object : View.AccessibilityDelegate() {
                        // Объявляем строку кнопкой для системы доступности. У недавнего файла
                        // добавляем постоянное имя, чтобы его можно было найти независимо от
                        // позиции в списке.
                        override fun onInitializeAccessibilityNodeInfo(
                            host: View,
                            info: AccessibilityNodeInfo,
                        ) {
                            super.onInitializeAccessibilityNodeInfo(host, info)
                            info.className = "android.widget.Button"
                            if (id != null) info.viewIdResourceName = id
                        }
                    }
                setOnClickListener {
                    dialog.dismiss()
                    onSelect()
                }
            }
        row.addView(
            ImageView(context).apply {
                setImageResource(resource)
                imageTintList = ColorStateList.valueOf(KandinskyPalette.studioSheetHandle)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(16) },
        )
        val captions =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                addView(
                    text(title, 16).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        setTextColor(listText)
                        minimumHeight = dp(24)
                    }
                )
                if (subtitle.isNotEmpty())
                    addView(
                        text(subtitle, 14).apply {
                            gravity = Gravity.CENTER_VERTICAL
                            setTextColor(KandinskyPalette.studioSheetHandle)
                            minimumHeight = dp(20)
                        }
                    )
            }
        row.addView(captions, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (selected) {
            row.addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.kandinsky_model_check)
                    imageTintList = ColorStateList.valueOf(mint)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(16) },
            )
        }
        body.addView(row)
    }

    // Приводим качество, размер, пропорции, время и число результатов к возможностям модели.
    // Меняем подписи размеров вместе с форматом. При подтверждении проверяем числовой ключ и
    // передаём только поддерживаемые дополнительные поля.
    fun showSettings(
        available: StudioCapabilities,
        onCountChange: (Int) -> Unit,
        onApply: (KandinskyOptions) -> Unit,
    ) {
        scrollControlled = true
        body.setPadding(dp(20), 0, dp(20), dp(16))
        options =
            options.copy(
                quality =
                    available.quality.firstOrNull { it.id == options.quality }?.id ?: "standard",
                resolution =
                    available.resolutions.firstOrNull { it.id == options.resolution }?.id
                        ?: "standard",
                aspect =
                    options.aspect.takeIf { it in available.aspects }
                        ?: available.aspects.firstOrNull()
                        ?: "1:1",
                seconds =
                    options.seconds.takeIf { it in available.durations }
                        ?: available.durations.lastOrNull()
                        ?: 5,
                count = (options.count ?: 1).coerceIn(1, available.maxCount.coerceAtLeast(1)),
            )
        body.addView(
            text("Параметры", 24, weight = 600).apply {
                minimumHeight = dp(35)
                gravity = Gravity.CENTER_VERTICAL
            }
        )
        gap(body, 20)
        section("Качество", available.quality.map { it.id to it.name }, options.quality) {
            options = options.copy(quality = it)
        }
        val dimensions = mutableListOf<TextView>()
        section("Формат", available.aspects.map { it to it }, options.aspect) { aspect ->
            options = options.copy(aspect = aspect)
            available.resolutions.zip(dimensions).forEach { (choice, view) ->
                view.text = choice.label(aspect)
            }
        }
        dimensions +=
            section(
                "Размер",
                available.resolutions.map { it.id to it.label(options.aspect) },
                options.resolution,
            ) {
                options = options.copy(resolution = it)
            }
        if (available.durations.isNotEmpty()) {
            section(
                "Длительность",
                available.durations.map { it.toString() to "$it с" },
                options.seconds.toString(),
            ) {
                options = options.copy(seconds = it.toInt())
            }
        }
        section(
            "Количество",
            (1..available.maxCount).map { it.toString() to it.toString() },
            options.count.toString(),
            spacingAfter = 0,
        ) {
            val count = it.toInt()
            options = options.copy(count = count)
            onCountChange(count)
        }
        val details =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                isFocusableInTouchMode = true
            }
        val upperRule = View(context)
        val lowerRule = View(context)
        val detailRuleColor = Color.rgb(232, 235, 231)
        val negative =
            if (available.negativePrompt)
                StudioSettingsField("Что исключить", options.negativePrompt, multiline = true)
                    .also { details.addView(it) }
            else null
        val seed = StudioSettingsField("Seed", options.seed?.toString(), multiline = false)
        details.addView(seed)
        available.guidance?.let { guidance ->
            gap(details, 12)
            val step = (guidance.max - guidance.min) / 14
            val value = options.guidanceScale ?: guidance.default
            val label =
                text(guidanceLabel(value), 15).apply {
                    minimumHeight = dp(22)
                    gravity = Gravity.CENTER
                }
            details.addView(label)
            val slider =
                StudioGuidanceSlider().apply {
                    contentDescription = "Следование описанию"
                    max = 14
                    progress = if (step > 0) ((value - guidance.min) / step).roundToInt() else 0
                    setOnSeekBarChangeListener(
                        object : SeekBar.OnSeekBarChangeListener {
                            // На действие пользователя переводим ступень ползунка в вес
                            // следования описанию и обновляем подпись. Программная установка
                            // начальной ступени не переписывает выбор.
                            override fun onProgressChanged(
                                bar: SeekBar,
                                progress: Int,
                                fromUser: Boolean,
                            ) {
                                if (!fromUser) return
                                val next = guidance.min + progress * step
                                options = options.copy(guidanceScale = next)
                                label.text = guidanceLabel(next)
                            }

                            // Начало перетаскивания не меняет настройки отдельно. Значение
                            // запишется при первом фактическом изменении положения.
                            override fun onStartTrackingTouch(bar: SeekBar) = Unit

                            // Конец жеста не отправляет форму. Последняя ступень уже записана, и
                            // остальные настройки ждут кнопки подтверждения.
                            override fun onStopTrackingTouch(bar: SeekBar) = Unit
                        }
                    )
                }
            details.addView(slider, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)))
        }
        val toggle =
            text("Дополнительно", 16).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setCompoundDrawablesRelative(null, null, chevron(false), null)
                isFocusable = true
                setOnClickListener {
                    val expanding = details.visibility != View.VISIBLE
                    if (!expanding && details.hasFocus()) {
                        context
                            .getSystemService(InputMethodManager::class.java)
                            .hideSoftInputFromWindow(details.windowToken, 0)
                        requestFocus()
                    }
                    details.visibility = if (expanding) View.VISIBLE else View.GONE
                    val color = if (expanding) detailRuleColor else Color.TRANSPARENT
                    upperRule.setBackgroundColor(color)
                    lowerRule.setBackgroundColor(color)
                    setCompoundDrawablesRelative(null, null, chevron(expanding), null)
                }
            }
        body.addView(upperRule, LinearLayout.LayoutParams(MATCH_PARENT, dp(1)))
        body.addView(toggle)
        body.addView(details)
        body.addView(lowerRule, LinearLayout.LayoutParams(MATCH_PARENT, dp(1)))
        gap(body, 8)
        val error =
            text("", 14).apply {
                setTextColor(Color.rgb(186, 26, 26))
                visibility = View.GONE
            }
        body.addView(error)
        val done =
            text("Готово", 14, weight = 500).apply {
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = rounded(KandinskyPalette.studioInk, 14)
                isFocusable = true
                setOnClickListener {
                    val raw = seed.value.trim()
                    val number = raw.toIntOrNull()
                    if (raw.isNotEmpty() && (number == null || number < 0)) {
                        error.text = context.getString(R.string.generation_seed_range, 0, Int.MAX_VALUE)
                        error.visibility = View.VISIBLE
                        return@setOnClickListener
                    }
                    onApply(
                        options.copy(
                            seed = number,
                            negativePrompt = negative?.value?.trim(),
                            guidanceScale =
                                options.guidanceScale.takeIf { available.guidance != null },
                        )
                    )
                    dialog.dismiss()
                }
            }
        body.addView(done, LinearLayout.LayoutParams(MATCH_PARENT, dp(52)))
        show()
    }

    // Добавляем заголовок и кнопки одного параметра. При выборе выделяем только один вариант и
    // возвращаем его ключ, а созданные кнопки можно обновить при смене формата.
    private fun section(
        title: String,
        values: List<Pair<String, String>>,
        current: String?,
        spacingAfter: Int = 14,
        change: (String) -> Unit,
    ): List<TextView> {
        body.addView(
            text(title, 15).apply {
                minimumHeight = dp(22)
                gravity = Gravity.CENTER_VERTICAL
            }
        )
        val row = OptionRow()
        val buttons =
            values.map { (key, label) ->
                text(label, 14, weight = 500).apply {
                    gravity = Gravity.CENTER
                    minWidth = dp(48)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    letterSpacing = 0.1f / 14
                    isFocusable = true
                    styleChip(this, current == key)
                    setOnClickListener {
                        for (index in 0 until row.childCount) {
                            val item = row.getChildAt(index) as TextView
                            styleChip(item, item === this)
                        }
                        change(key)
                    }
                    row.addView(this)
                }
            }
        body.addView(row)
        if (spacingAfter > 0) gap(body, spacingAfter)
        return buttons
    }

    private inner class OptionRow : ViewGroup(context) {
        // Измеряем ширину каждого варианта и считаем, сколько рядов они займут. Слишком длинная
        // кнопка ограничена шириной панели, поэтому соседний раздел получает правильное место.
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = dp(48)
            val spacing = dp(8)
            var x = 0
            var rows = if (isEmpty()) 0 else 1
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                child.measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
                )
                if (x > 0 && x + child.measuredWidth > width) {
                    x = 0
                    rows++
                }
                x += child.measuredWidth + spacing
            }
            setMeasuredDimension(width, rows * height)
        }

        // Повторяем перенос кнопок по измеренной ширине и выставляем их прямоугольники. Раскладка
        // совпадает с высотой, которую форма получила при измерении.
        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val height = dp(48)
            var x = 0
            var y = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (x > 0 && x + child.measuredWidth > width) {
                    x = 0
                    y += height
                }
                child.layout(x, y, x + child.measuredWidth, y + height)
                x += child.measuredWidth + dp(8)
            }
        }

        // Оставляем кнопке ширину по подписи и задаём высоту прикосновения ряда. Варианты могут
        // иметь разную ширину, оставаясь на одной линии.
        override fun generateDefaultLayoutParams() = LayoutParams(WRAP_CONTENT, dp(48))
    }

    private inner class StudioSettingsField(
        title: String,
        initial: String?,
        private val multiline: Boolean,
    ) : FrameLayout(context) {
        private val iterator = BreakIterator.getCharacterInstance()
        private val plate =
            FrameLayout(context).apply { background = rounded(KandinskyPalette.studioSurface, 14) }
        private val caption =
            text(title, 16).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        private val counter =
            text("", 12).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setTextColor(KandinskyPalette.studioSheetHandle)
            }
        private val editor =
            EditText(context).apply {
                contentDescription = title
                background = null
                includeFontPadding = false
                inputType =
                    if (multiline) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    else InputType.TYPE_CLASS_NUMBER
                setTextColor(KandinskyPalette.studioInk)
                setHintTextColor(KandinskyPalette.studioMuted)
                textSize = 16f
                typeface = Typeface.DEFAULT
                setLineSpacing(dp(5).toFloat(), 1f)
                maxLines = if (multiline) 3 else 1
                if (multiline)
                    filters =
                        arrayOf(
                            InputFilter { source, start, end, dest, dstart, dend ->
                                if (start == end) return@InputFilter null
                                val head = dest.subSequence(0, dstart).toString()
                                val tail = dest.subSequence(dend, dest.length).toString()
                                val insertion = source.subSequence(start, end)
                                if (length(head + insertion + tail) <= NEGATIVE_PROMPT_LIMIT) return@InputFilter null
                                val room = NEGATIVE_PROMPT_LIMIT - length(head + tail)
                                if (room <= 0) return@InputFilter ""
                                val candidate =
                                    source.subSequence(
                                        start,
                                        start + prefixAtLimit(insertion.toString(), room),
                                    )
                                if (length(head + candidate + tail) <= NEGATIVE_PROMPT_LIMIT) candidate
                                else
                                    source.subSequence(
                                        start,
                                        start + prefixAtLimit(insertion.toString(), room - 1),
                                    )
                            }
                        )
                setText(initial.orEmpty())
            }

        val value: String
            // Читаем текущее содержимое системного поля без дополнительного преобразования. При
            // подтверждении именно это значение обрезается по краям и проверяется формой.
            get() = editor.text.toString()

        private val floating: Boolean
            // Поднимаем подпись, когда поле в фокусе или уже заполнено. Поднятая подпись
            // оставляет видимым введённый текст после закрытия клавиатуры.
            get() = editor.hasFocus() || value.isNotEmpty()

        // Помещаем редактор, подпись и при необходимости счётчик в плашку. Клавиатуру показываем
        // после получения фокуса окна, чтобы первое нажатие не потерялось.
        init {
            addView(plate, LayoutParams(MATCH_PARENT, dp(60)))
            plate.addView(editor, LayoutParams(MATCH_PARENT, MATCH_PARENT))
            plate.addView(caption, LayoutParams(WRAP_CONTENT, dp(24)))
            if (multiline) addView(counter, LayoutParams(WRAP_CONTENT, dp(16)))
            val showKeyboard = Runnable {
                if (editor.hasFocus() && editor.hasWindowFocus()) {
                    context
                        .getSystemService(InputMethodManager::class.java)
                        .showSoftInput(editor, 0)
                }
            }
            editor.setOnFocusChangeListener { _, focused ->
                refresh()
                if (focused) editor.post(showKeyboard)
            }
            editor.setOnClickListener {
                editor.removeCallbacks(showKeyboard)
                editor.post(showKeyboard)
            }
            editor.addTextChangedListener(
                object : TextWatcher {
                    // Оставляем обязательный вызов без действия. Вид поля обновляем после замены,
                    // когда можно посчитать новое содержимое.
                    override fun beforeTextChanged(
                        text: CharSequence?,
                        start: Int,
                        count: Int,
                        after: Int,
                    ) = Unit

                    // Середина изменения не требует отдельного пересчёта. Иначе за одно изменение
                    // пришлось бы дважды измерять подпись и высоту.
                    override fun onTextChanged(
                        text: CharSequence?,
                        start: Int,
                        before: Int,
                        count: Int,
                    ) = Unit

                    // После изменения показываем новый счётчик и запрашиваем расчёт высоты. Это
                    // учитывает и ввод, и вставку через системное меню.
                    override fun afterTextChanged(text: Editable?) {
                        refresh()
                    }
                }
            )
            refresh()
        }

        // Рассчитываем высоту многострочного поля по переносу текста, оставляя от одной до трёх
        // строк. Размещаем подпись и счётчик за пределами текста, чтобы они не перекрывали набор.
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val lines =
                if (multiline) {
                    val text = value
                    StaticLayout.Builder.obtain(
                            text,
                            0,
                            text.length,
                            editor.paint,
                            maxOf(1, width - dp(44)),
                        )
                        .setIncludePad(false)
                        .build()
                        .lineCount
                        .coerceIn(1, 3)
                } else 1
            val plateHeight = dp(60 + 24 * (lines - 1))
            val editorWidth = maxOf(0, width - dp(44))
            plate.layoutParams.height = plateHeight
            (caption.layoutParams as LayoutParams).apply {
                this.width = editorWidth
                height = dp(if (floating) 16 else 24)
                leftMargin = dp(22)
                topMargin = dp(if (floating) 7 else 18)
            }
            if (multiline)
                (counter.layoutParams as LayoutParams).apply {
                    this.width = editorWidth
                    leftMargin = dp(22)
                    topMargin = plateHeight + dp(4)
                }
            super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(
                    plateHeight + dp(if (multiline) 20 else 0),
                    MeasureSpec.EXACTLY,
                ),
            )
        }

        // Меняем положение подписи, отступы редактора и подсказку по текущему фокусу. У
        // исключений показываем число введённых символов и сообщаем форме о новой раскладке.
        private fun refresh() {
            val raised = floating
            caption.textSize = if (raised) 12f else 16f
            caption.gravity = Gravity.CENTER_VERTICAL
            caption.setTextColor(
                if (editor.hasFocus()) KandinskyPalette.studioInk
                else KandinskyPalette.studioSheetHandle
            )
            editor.gravity = (if (raised) Gravity.TOP else Gravity.CENTER_VERTICAL) or Gravity.START
            editor.setPadding(dp(22), dp(if (raised) 26 else 0), dp(22), dp(if (raised) 6 else 0))
            if (!multiline) editor.hint = if (editor.hasFocus()) "Случайный" else null
            if (multiline)
                counter.text = context.getString(
                    R.string.generation_prompt_count, length(value), NEGATIVE_PROMPT_LIMIT,
                )
            requestLayout()
        }

        // Считаем воспринимаемые символы по их границам. Один составной знак может содержать
        // несколько частей, но занимает одно место в счётчике.
        private fun length(value: String): Int {
            iterator.setText(value)
            iterator.first()
            var count = 0
            while (iterator.next() != BreakIterator.DONE) count++
            return count
        }

        // Находим границу допустимого начала вставляемой строки. Обрезка на такой границе
        // сохраняет целыми составные буквы и значки.
        private fun prefixAtLimit(value: String, limit: Int): Int {
            iterator.setText(value)
            iterator.first()
            repeat(limit) {
                val next = iterator.next()
                if (next == BreakIterator.DONE) return value.length
            }
            return iterator.current()
        }
    }

    private inner class StudioGuidanceSlider : SeekBar(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val inactive = Color.rgb(222, 228, 223)
        private val activeTick = Color.rgb(132, 238, 207)

        // Оставляем системную механику ползунка и задаём отступы под ручку. Собственный рисунок
        // заменяет стандартный фон, сохраняя обработку касания.
        init {
            setPadding(dp(26), 0, dp(26), 0)
            background = null
        }

        // По текущей ступени рисуем дорожку, деления и ручку. При нулевой ширине или пустом
        // диапазоне ничего не рисуем, поскольку положения для ручки ещё нет.
        override fun onDraw(canvas: Canvas) {
            val left = paddingLeft.toFloat()
            val right = (width - paddingRight).toFloat()
            if (right <= left || max <= 0) return
            val y = height / 2f
            val x = left + (right - left) * progress / max
            val scale = density
            paint.color = inactive
            canvas.drawRoundRect(
                left - 2 * scale,
                y - 2 * scale,
                right + 2 * scale,
                y + 2 * scale,
                2 * scale,
                2 * scale,
                paint,
            )
            paint.color = mint
            canvas.drawRoundRect(
                left - 2 * scale,
                y - 3 * scale,
                x,
                y + 3 * scale,
                3 * scale,
                3 * scale,
                paint,
            )
            for (index in 0..max) {
                paint.color = if (index <= progress) activeTick else mint
                canvas.drawCircle(left + (right - left) * index / max, y, scale, paint)
            }
            paint.color = Color.BLACK
            canvas.drawCircle(x, y, 11 * scale, paint)
            paint.color = mint
            canvas.drawCircle(x, y, 10 * scale, paint)
        }
    }

    // Оформляем выбранный вариант фоном и галочкой, остальные обводкой. Когда состояние прежнее,
    // переиспользуем уже созданный фон, избегая повторного создания рисунка.
    private fun styleChip(view: TextView, active: Boolean) {
        if (view.background != null && view.isSelected == active) return
        view.isSelected = active
        val contentWidth = view.paint.measureText(view.text.toString()) + dp(if (active) 54 else 34)
        val sideInset = maxOf(0, ((dp(48) - contentWidth) / 2).roundToInt())
        view.background =
            InsetDrawable(
                rounded(if (active) chipFill else Color.WHITE, 8).apply {
                    if (!active) setStroke(dp(1), chipOutline)
                },
                sideInset,
                dp(5),
                sideInset,
                dp(5),
            )
        view.setPadding(dp(17), 0, dp(17), 0)
        view.setTextColor(if (active) chipInk else KandinskyPalette.studioSheetHandle)
        view.compoundDrawablePadding = dp(2)
        val check =
            if (active) glyph(18, chipInk, 1.5f, floatArrayOf(2.7f, 8.1f, 7.2f, 12.6f, 15.3f, 4.5f))
            else null
        view.setCompoundDrawablesRelative(check, null, null, null)
    }

    // Возвращаем стрелку вверх или вниз в зависимости от положения дополнительных полей. Цвет и
    // направление показывают, раскрыты ли они.
    private fun chevron(up: Boolean): Drawable {
        val points =
            if (up) floatArrayOf(7f, 14f, 12f, 9f, 17f, 14f)
            else floatArrayOf(7f, 10f, 12f, 15f, 17f, 10f)
        return glyph(
            24,
            if (up) KandinskyPalette.studioInk else KandinskyPalette.studioSheetHandle,
            2f,
            points,
        )
    }

    // Собираем переиспользуемую ломаную заданного цвета и толщины. Её размер задаётся отдельно от
    // точек, чтобы значок масштабировался вместе с экраном.
    private fun glyph(size: Int, color: Int, stroke: Float, points: FloatArray): Drawable {
        return object : Drawable() {
                private val line =
                    Path().apply {
                        moveTo(points[0], points[1])
                        lineTo(points[2], points[3])
                        lineTo(points[4], points[5])
                    }
                private val paint =
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        this.color = color
                        style = Paint.Style.STROKE
                        strokeWidth = stroke
                    }

                // Переносим начало координат к границе значка и масштабируем линию до
                // фактического размера. Остальное содержимое сохраняет свои координаты после
                // выхода из преобразования.
                override fun draw(canvas: Canvas) {
                    canvas.withTranslation(bounds.left.toFloat(), bounds.top.toFloat()) {
                        canvas.scale(bounds.width().toFloat() / size, bounds.height().toFloat() / size)
                        canvas.drawPath(line, paint)
                    }
                }

                // Применяем системную прозрачность к кисти контура. Значок может тускнеть вместе
                // с владельцем без смены формы.
                override fun setAlpha(alpha: Int) {
                    paint.alpha = alpha
                }

                // Передаём кисти фильтр цвета, если его устанавливает система. Такой вызов нужен
                // для обычного поведения рисунка внутри элементов интерфейса.
                override fun setColorFilter(filter: ColorFilter?) {
                    paint.colorFilter = filter
                }

                // Сообщаем, что линия оставляет прозрачные участки в прямоугольнике. Система
                // продолжает рисовать фон под значком.
                @Deprecated("Android drawable transparency")
                override fun getOpacity() = PixelFormat.TRANSLUCENT
            }
            .apply { setBounds(0, 0, dp(size), dp(size)) }
    }

    // Готовим подпись веса с одним знаком после запятой. Постоянный формат позволяет подписи
    // оставаться одинаковой при разных настройках языка устройства.
    private fun guidanceLabel(value: Double) =
        String.format(Locale.ROOT, "Следование описанию %.1f", value)

    // Создаём заливку со скруглёнными углами для ручки и полей. Общая настройка сохраняет
    // согласованный размер скругления.
    private fun rounded(color: Int, radius: Int) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    // Добавляем отступ заданной высоты между группами. Следующий ряд начинается после него без
    // пустой текстовой подписи.
    private fun gap(parent: LinearLayout, height: Int) {
        parent.addView(View(context), LinearLayout.LayoutParams(MATCH_PARENT, dp(height)))
    }

    // Создаём подпись общего цвета с заданным размером и начертанием. Убираем системный запас по
    // высоте, чтобы строки совпадали с измерениями панели.
    private fun text(value: String, size: Int, weight: Int = 400) =
        TextView(context).apply {
            text = value
            textSize = size.toFloat()
            includeFontPadding = false
            setTextColor(KandinskyPalette.studioInk)
            if (weight != 400) typeface = Typeface.create(typeface, weight, false)
        }

    // Закрепляем диалог у нижнего края и просим систему уменьшить рабочую область при клавиатуре.
    // После показа окно занимает доступную ширину и высоту по содержимому.
    private fun show() {
        dialog.window?.apply {
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.54f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.show()
        dialog.window?.setLayout(MATCH_PARENT, WRAP_CONTENT)
    }

    // Переводим размеры оформления в точки текущего экрана. Округление даёт целое значение,
    // которое принимает системная разметка.
    private fun dp(value: Int) = (value * density).roundToInt()
}

private const val NEGATIVE_PROMPT_LIMIT = 2000
