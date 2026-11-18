package dev.kirill.kandinsky.chat.account

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import dev.kirill.kandinsky.chat.network.NetworkAPI
import dev.kirill.kandinsky.chat.network.NetworkCredentials
import dev.kirill.kandinsky.chat.network.NetworkFailure
import dev.kirill.kandinsky.chat.network.NetworkProfile
import dev.kirill.kandinsky.chat.rendering.KandinskyInsetHost
import dev.kirill.kandinsky.chat.rendering.KandinskyPalette

class AccountEntryView(
    private val activity: Activity,
    private val api: NetworkAPI,
    private val selected: (NetworkProfile) -> Unit,
    private val cancel: (() -> Unit)?,
) : ScrollView(activity), KandinskyInsetHost {
    private val credentials = NetworkCredentials(activity.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private val gate = Any()
    private var closed = false
    private var busy = false
    private var registering = false
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
            TextView(activity).apply {
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
        toggle.setOnClickListener {
            if (!busy) {
                registering = !registering
                error.text = ""
                update()
            }
        }
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
        Thread(
                {
                    val profiles = runCatching { credentials.profiles() }
                    main.post {
                        if (!closed)
                            profiles.fold(
                                { values ->
                                    values.forEach { profile ->
                                        add(
                                            Button(activity).apply {
                                                secondary(this)
                                                text =
                                                    "Продолжить · " +
                                                        (profile.username?.let { "@" + it }
                                                            ?: profile.name)
                                                id(this, "account.saved.${profile.userID}")
                                                setOnClickListener { choose(profile) }
                                            }
                                        )
                                    }
                                },
                                {
                                    error.text =
                                        "Не удалось прочитать сохранённые аккаунты. Данные остаются на устройстве."
                                },
                            )
                    }
                },
                "KandinskyAccounts",
            )
            .start()
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
        submit.text = if (registering) "Зарегистрироваться" else "Войти"
        toggle.text = if (registering) "Войти" else "Создать аккаунт"
    }

    private fun setBusy(value: Boolean) {
        busy = value
        listOf(passwordField, emailField, submit, toggle).forEach { it.isEnabled = !value }
    }

    private fun choose(profile: NetworkProfile) {
        if (busy) return
        runRequest { profile }
    }

    // Сохраняем подтверждённый профиль до открытия чатов, чтобы вход пережил перезапуск.
    private fun enter() {
        if (busy) return
        val email = emailField.text.toString().trim()
        val password = passwordField.text.toString()
        if (!email.contains('@') || password.length !in 12..128) {
            error.text = "Введите почту и пароль от 12 символов."
            return
        }
        val createAccount = registering
        runRequest { api.signIn(email, password, createAccount) }
    }

    private fun runRequest(action: () -> NetworkProfile) {
        error.text = ""
        setBusy(true)
        Thread(
                {
                    val result = runCatching {
                        val profile = action()
                        synchronized(gate) {
                            check(!closed)
                            credentials.save(profile)
                        }
                        profile
                    }
                    main.post {
                        if (!closed) {
                            setBusy(false)
                            result.fold(
                                { selected(it) },
                                { e ->
                                    error.text =
                                        when ((e as? NetworkFailure)?.status) {
                                            409 -> "Аккаунт уже существует. Войди с паролем."
                                            404 -> "Аккаунт не найден."
                                            429 -> "Слишком много попыток. Попробуйте через минуту."
                                            else ->
                                                "Не удалось войти. Проверьте соединение и попробуйте снова."
                                        }
                                },
                            )
                        }
                    }
                },
                "KandinskyEntry",
            )
            .start()
    }

    override fun updateInsets(top: Int, bottom: Int) {
        setPadding(0, top, 0, bottom)
    }

    // Уход с экрана прекращает ожидание ответа.
    fun close() {
        val first =
            synchronized(gate) {
                if (closed) false
                else {
                    closed = true
                    true
                }
            }
        // Закрытие защищённого соединения может потребовать отправку данных, поэтому выполняем его.
        if (first) Thread({ api.close() }, "KandinskyEntryClose").start()
    }
}
