package dev.kirill.kandinsky.chat.account

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import dev.kirill.kandinsky.chat.network.NetworkAPI
import dev.kirill.kandinsky.chat.network.NetworkProfile
import dev.kirill.kandinsky.chat.rendering.KandinskyInsetHost
import dev.kirill.kandinsky.chat.rendering.KandinskyPalette

class AccountEntryView(
    private val activity: Activity,
    api: NetworkAPI,
    selected: (NetworkProfile) -> Unit,
    private val cancel: (() -> Unit)?,
) : ScrollView(activity), KandinskyInsetHost {
    private val model = AccountEntryViewModel.forDevice(activity.applicationContext, api, selected)
    private var displayedStep = AccountEntryStep.LOGIN
    private val title = TextView(activity)
    private val codeField = EditText(activity)
    private val forgot = Button(activity)
    private val resend = Button(activity)
    private val body = FrameLayout(activity)
    private val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val passwordField = EditText(activity)
    private val emailField = EditText(activity)
    private val error = TextView(activity)
    private val submit = Button(activity)
    private val toggle = Button(activity)
    private val d = resources.displayMetrics.density

    private fun id(view: View, value: String) {
        view.accessibilityDelegate =
            object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: android.view.accessibility.AccessibilityNodeInfo,
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.viewIdResourceName = value
                }
            }
    }

    init {
        setBackgroundColor(Color.WHITE)
        isFillViewport = true
        clipToPadding = false
        body.setPadding((24 * d).toInt(), (24 * d).toInt(), (24 * d).toInt(), (24 * d).toInt())
        addView(body, LayoutParams(-1, -2))
        body.addView(column, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        column.setPadding((24 * d).toInt(), (28 * d).toInt(), (24 * d).toInt(), (24 * d).toInt())
        column.isFocusableInTouchMode = true
        fun add(view: View) {
            column.addView(
                view,
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (12 * d).toInt() },
            )
        }
        add(
            object : View(activity) {
                init {
                    minimumHeight = (62 * d).toInt()
                    contentDescription = "Кандинский"
                    id(this, "account.brand")
                }

                override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                    setMeasuredDimension(
                        resolveSize((196 * d).toInt(), widthSpec),
                        resolveSize((62 * d).toInt(), heightSpec),
                    )
                }

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
            TextView(activity).apply {
                text = "Изображения и видео\nв одном чате"
                textSize = 15f
                setTextColor(Color.rgb(76, 89, 103))
            }
        )
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
        toggle.setOnClickListener { model.toggleRegistration() }
        add(toggle)
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
        forgot.setOnClickListener { model.startRecovery() }
        add(forgot)
        secondary(resend)
        resend.text = "Отправить код ещё раз"
        id(resend, "account.resend")
        resend.setOnClickListener { enter(resending = true) }
        add(resend)
        cancel?.let { action ->
            add(
                Button(activity).apply {
                    secondary(this)
                    text = "Назад к чатам"
                    id(this, "account.cancel")
                    setOnClickListener { action() }
                }
            )
        }
        model.onChange = ::update
        model.loadSavedProfiles { profiles ->
            profiles.forEach { profile ->
                add(Button(activity).apply {
                    secondary(this)
                    text = "Продолжить · " + (profile.username?.let { "@" + it } ?: profile.name)
                    id(this, "account.saved.${profile.userID}")
                    setOnClickListener { model.choose(profile) }
                })
            }
        }
        update()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val available =
            (MeasureSpec.getSize(widthSpec) - paddingLeft - paddingRight - (48 * d).toInt())
                .coerceAtLeast(1)
        val desired = minOf(available, (430 * d).toInt())
        if (column.layoutParams.width != desired)
            column.layoutParams = column.layoutParams.apply { width = desired }
        super.onMeasure(widthSpec, heightSpec)
    }

    private fun update() {
        val step = model.step
        val busy = model.isBusy
        if (displayedStep != step) {
            codeField.text.clear()
            passwordField.text.clear()
            displayedStep = step
        }
        error.text = model.message
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

    private fun enter(resending: Boolean = false) {
        model.submit(
            email = emailField.text.toString(),
            password = passwordField.text.toString(),
            code = codeField.text.toString(),
            resending = resending,
        )
    }

    override fun updateInsets(top: Int, bottom: Int) {
        setPadding(0, top, 0, bottom)
    }

    fun close() = model.close()
}
