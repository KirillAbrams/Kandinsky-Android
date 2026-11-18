// Строит прокручиваемую форму входа Андроида и связывает её с моделью шагов. Экран показывает
// подсказки и передаёт ввод, не управляя сетевым запросом напрямую.

package dev.kirill.kandinsky.chat.account

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import dev.kirill.kandinsky.chat.R
import dev.kirill.kandinsky.chat.network.NetworkAPI
import dev.kirill.kandinsky.chat.network.NetworkProfile
import dev.kirill.kandinsky.chat.rendering.KandinskyInsetHost
import dev.kirill.kandinsky.chat.rendering.KandinskyPalette

// Получает оформление, модель действий и необязательную команду возврата. Единый способ
// создания поддерживает и рабочую форму, и показ без готового входа.
class AccountEntryView private constructor(
    context: Context,
    private val model: AccountEntryViewModel?,
    cancel: (() -> Unit)?,
) : ScrollView(context), KandinskyInsetHost {
    // Создаёт вид формы без службы входа. Кнопки запроса тогда недоступны, что подходит для
    // показа до готовности службы.
    constructor(context: Context) : this(context, null, null)

    // Создаёт модель с сетевой службой и защищёнными записями и передаёт её форме. После
    // успешного входа экран отдаст профиль показывающему окну.
    constructor(
        activity: Activity,
        api: NetworkAPI,
        selected: (NetworkProfile) -> Unit,
        cancel: (() -> Unit)?,
    ) : this(
        activity, AccountEntryViewModel.forDevice(activity.applicationContext, api, selected), cancel,
    )

    private var displayedStep = AccountEntryStep.LOGIN
    private val title = TextView(context)
    private val codeField = EditText(context)
    private val forgot = Button(context)
    private val resend = Button(context)
    private val body = FrameLayout(context)
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val passwordField = EditText(context)
    private val emailField = EditText(context)
    private val error = TextView(context)
    private val submit = Button(context)
    private val toggle = Button(context)
    private val d = resources.displayMetrics.density

    // Назначает элементу устойчивое имя доступности. Автоматическую проверку можно привязать к
    // элементу даже при изменении видимой подписи.
    private fun id(view: View, value: String) {
        view.accessibilityDelegate =
            object : View.AccessibilityDelegate() {
                // Сохраняет обычные сведения системы и подставляет заданное имя. Элемент остаётся
                // доступен озвучиванию и проверкам.
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: android.view.accessibility.AccessibilityNodeInfo,
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.viewIdResourceName = value
                }
            }
    }

    // Размещает знак, поля, команды и сохранённые входы в прокручиваемой колонке. Связывает
    // действия с моделью, чтобы при смене шага не пришлось создавать другой экран.
    init {
        setBackgroundColor(Color.WHITE)
        isFillViewport = true
        clipToPadding = false
        body.setPadding((24 * d).toInt(), (24 * d).toInt(), (24 * d).toInt(), (24 * d).toInt())
        addView(body, LayoutParams(-1, -2))
        body.addView(column, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        column.setPadding((24 * d).toInt(), (28 * d).toInt(), (24 * d).toInt(), (24 * d).toInt())
        column.isFocusableInTouchMode = true
        // Ставит элемент в колонку с одинаковым нижним отступом. Новая кнопка сохранённого входа
        // не нарушит расстояние между полями.
        fun add(view: View) {
            column.addView(
                view,
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (12 * d).toInt() },
            )
        }
        add(
            object : View(context) {
                // Даёт знаку минимальное место и имя для служб доступности. По нему можно узнать
                // приложение даже без чтения самой картинки.
                init {
                    minimumHeight = (62 * d).toInt()
                    contentDescription = "Кандинский"
                    id(this, "account.brand")
                }

                // Выбирает допустимые размеры знака из желаемой ширины и высоты. Родительская
                // форма получает реальные границы перед рисованием.
                override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                    setMeasuredDimension(
                        resolveSize((196 * d).toInt(), widthSpec),
                        resolveSize((62 * d).toInt(), heightSpec),
                    )
                }

                // Рисует знак в масштабе экрана. На телефонах с разной плотностью он занимает
                // одинаковое место в форме.
                override fun onDraw(canvas: Canvas) {
                    KandinskyPalette.studioBrand(canvas, 85 * d, 8 * d, d, large = true)
                }
            }
        )
        add(
            title.apply {
                text = "Войти в студию"
                textSize = 31f
                setTextColor(Color.rgb(23, 32, 42))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
        )
        add(
            TextView(context).apply {
                setText(R.string.account_media_intro)
                textSize = 15f
                setTextColor(Color.rgb(76, 89, 103))
            }
        )
        // Задаёт полям общую высоту, отступы, фон и цвета. Без общего оформления разные шаги
        // могли бы прыгать при замене одного поля другим.
        fun field(view: EditText) {
            view.setTextColor(Color.rgb(23, 32, 42))
            view.setHintTextColor(Color.rgb(106, 118, 132))
            view.textSize = 16f
            view.minHeight = (50 * d).toInt()
            view.setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
            view.background =
                GradientDrawable().apply {
                    setColor(KandinskyPalette.studioSurface)
                    cornerRadius = 12 * d
                }
        }
        passwordField.hint = "Пароль"
        passwordField.setSingleLine()
        passwordField.inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        field(passwordField)
        id(passwordField, "account.name")
        emailField.hint = "Электронная почта"
        emailField.setSingleLine()
        emailField.inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        emailField.imeOptions = EditorInfo.IME_ACTION_DONE
        emailField.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                enter()
                true
            } else false
        }
        field(emailField)
        id(emailField, "account.username")
        add(emailField)
        codeField.hint = "Код из письма"
        codeField.inputType = InputType.TYPE_CLASS_NUMBER
        codeField.setSingleLine()
        field(codeField)
        id(codeField, "account.code")
        add(codeField)
        add(passwordField)
        error.setTextColor(Color.rgb(180, 30, 30))
        id(error, "account.error")
        add(error)
        submit.isAllCaps = false
        submit.textSize = 16f
        submit.setTextColor(Color.WHITE)
        submit.minHeight = (50 * d).toInt()
        submit.background =
            GradientDrawable().apply {
                setColor(KandinskyPalette.studioInk)
                cornerRadius = 14 * d
            }
        submit.stateListAnimator = null
        id(submit, "account.submit")
        submit.setOnClickListener { enter() }
        add(submit)
        toggle.isAllCaps = false
        toggle.setTextColor(KandinskyPalette.studioInk)
        toggle.background = null
        toggle.stateListAnimator = null
        id(toggle, "account.toggle")
        toggle.setOnClickListener { model?.toggleRegistration() }
        add(toggle)
        // Оформляет второстепенную кнопку в одну строку и укорачивает слишком длинную подпись.
        // Она не должна вытеснять основное действие формы.
        fun secondary(button: Button) {
            button.isAllCaps = false
            button.setTextColor(KandinskyPalette.studioInk)
            button.background = null
            button.stateListAnimator = null
            button.textSize = 14f
            button.setSingleLine()
            button.ellipsize = android.text.TextUtils.TruncateAt.END
        }
        secondary(forgot)
        forgot.text = "Забыли пароль?"
        id(forgot, "account.forgot")
        forgot.setOnClickListener { model?.startRecovery() }
        add(forgot)
        secondary(resend)
        resend.text = "Отправить код ещё раз"
        id(resend, "account.resend")
        resend.setOnClickListener { enter(resending = true) }
        add(resend)
        cancel?.let { action ->
            add(
                Button(context).apply {
                    secondary(this)
                    text = "Назад к чатам"
                    id(this, "account.cancel")
                    setOnClickListener { action() }
                }
            )
        }
        model?.onChange = ::update
        model?.loadSavedProfiles { profiles ->
            profiles.forEach { profile ->
                add(Button(context).apply {
                    secondary(this)
                    text = context.getString(
                        R.string.account_continue, profile.username?.let { "@$it" } ?: profile.name,
                    )
                    id(this, "account.saved.${profile.userID}")
                    setOnClickListener { model.choose(profile) }
                })
            }
        }
        update()
    }

    // Ограничивает ширину колонки свободным местом и верхним пределом для широкого экрана. Поля
    // не выходят за края и остаются читаемыми.
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val available =
            (MeasureSpec.getSize(widthSpec) - paddingLeft - paddingRight - (48 * d).toInt())
                .coerceAtLeast(1)
        val desired = minOf(available, (430 * d).toInt())
        if (column.layoutParams.width != desired)
            column.layoutParams = column.layoutParams.apply { width = desired }
        super.onMeasure(widthSpec, heightSpec)
    }

    // Устанавливает поля, заголовок и подписи для нового шага, очищает пароль и код при переходе.
    // Пока запрос идёт, основные кнопки не запускают его повторно.
    private fun update() {
        val step = model?.step ?: AccountEntryStep.LOGIN
        val busy = model == null || model.isBusy
        if (displayedStep != step) {
            codeField.text.clear()
            passwordField.text.clear()
            displayedStep = step
        }
        error.text = model?.message
        codeField.visibility = if (step.needsCode) View.VISIBLE else View.GONE
        passwordField.visibility = if (step.needsPassword) View.VISIBLE else View.GONE
        passwordField.hint = if (step == AccountEntryStep.VERIFY_RECOVERY) "Новый пароль" else "Пароль"
        forgot.visibility = if (step == AccountEntryStep.LOGIN) View.VISIBLE else View.GONE
        resend.visibility = if (step.needsCode) View.VISIBLE else View.GONE
        listOf(passwordField, codeField, submit, toggle, forgot, resend).forEach { it.isEnabled = !busy }
        emailField.isEnabled = !busy && !step.needsCode
        toggle.text = if (step == AccountEntryStep.LOGIN) "Создать аккаунт" else "Назад ко входу"
        title.text = when (step) {
            AccountEntryStep.LOGIN -> "Войти в студию"
            AccountEntryStep.REGISTRATION -> "Создать аккаунт"
            AccountEntryStep.RECOVERY -> "Восстановление пароля"
            AccountEntryStep.VERIFY_REGISTRATION -> "Подтверждение почты"
            AccountEntryStep.VERIFY_RECOVERY -> "Новый пароль"
        }
        submit.text = when (step) {
            AccountEntryStep.LOGIN -> "Войти"
            AccountEntryStep.REGISTRATION, AccountEntryStep.RECOVERY -> "Получить код"
            AccountEntryStep.VERIFY_REGISTRATION -> "Зарегистрироваться"
            AccountEntryStep.VERIFY_RECOVERY -> "Сохранить пароль"
        }
    }

    // Передаёт модели почту, пароль и код из полей и отмечает повтор отправки письма. У экрана и
    // клавиатуры остаётся один порядок проверки.
    private fun enter(resending: Boolean = false) {
        model?.submit(
            email = emailField.text.toString(),
            password = passwordField.text.toString(),
            code = codeField.text.toString(),
            resending = resending,
        )
    }

    // Оставляет место сверху и снизу под системные полосы и клавиатуру. Иначе часть формы могла
    // бы стать недоступной.
    override fun updateInsets(top: Int, bottom: Int) {
        setPadding(0, top, 0, bottom)
    }

    // Передаёт закрытие модели, чтобы работа с прежней формой остановилась. Она больше не сможет
    // принять запоздалый ответ.
    fun close() {
        model?.close()
    }
}
