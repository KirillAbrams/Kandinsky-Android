// Управляет главным окном Андроида. Восстанавливает вход и выбранный чат, открывает вложения
// через систему и закрывает работу владельца при выходе.

package dev.kirill.kandinsky.chat.app

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import dev.kirill.kandinsky.chat.account.AccountEntryView
import dev.kirill.kandinsky.chat.attachments.AttachmentPicker
import dev.kirill.kandinsky.chat.chatlist.StudioHistoryPanel
import dev.kirill.kandinsky.chat.conversation.*
import dev.kirill.kandinsky.chat.diagnostics.*
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.media.*
import dev.kirill.kandinsky.chat.network.*
import dev.kirill.kandinsky.chat.rendering.ForegroundHost
import dev.kirill.kandinsky.chat.rendering.KandinskyInsetHost
import dev.kirill.kandinsky.chat.storage.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException

class MainActivity : ComponentActivity(), AttachmentPicker, ForegroundHost {
    private lateinit var coordinator: AppCoordinator
    private var store: SQLiteStore? = null
    private var runtime: ChatRuntime? = null
    private var documentResult: ((Intent?) -> Unit)? = null
    private var cameraPermission: ((Boolean) -> Unit)? = null
    private var repository: ChatRepository? = null
    private var state: ConversationState? = null
    private var chatView: ConversationChrome? = null
    private var restoredStudioDraft: StudioDraftRecord? = null
    private var rootName = "chat-v1"
    private val appDataRoot by lazy {
        dev.kirill.kandinsky.chat.storage.AppDataRoot.resolve(filesDir)
    }
    private var pendingContainer: android.widget.FrameLayout? = null
    private var baseInsetTop = 0
    private var baseInsetBottom = 0

    private var frameProbe: WindowFrameProbe? = null
    override var isForeground = false
        private set

    private var generation = 0
    private var studioOptions = KandinskyOptions()
    private var entry: AccountEntryView? = null
    private var historyDrawer: FrameLayout? = null
    private var drawerClosing = false

    // Передаёт сведения о действиях пользователя сетевой службе и оставляет обычную обработку
    // системе. Без этого служба могла бы считать активный вход бездействующим.
    override fun onUserInteraction() {
        super.onUserInteraction()
        runtime?.network?.userInteraction()
    }

    // Показывает ожидание, готовит окно и открывает вход и историю вне потока экрана. При
    // повороте повторно использует готовые службы, чтобы не открывать ту же базу и соединение
    // заново.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coordinator = AppCoordinator { show(it) }
        val loading =
            TextView(this).apply {
                text = "Кандинский · открываем историю"
                gravity = android.view.Gravity.CENTER
                setBackgroundColor(Color.WHITE)
            }
        setContentView(loading)
        configureWindow()
        val applicationRoot =
            try {
                appDataRoot
            } catch (error: Exception) {
                loading.text = "Не удалось открыть сохранённые данные приложения"
                return
            }
        val metrics = intent.getBooleanExtra("metrics", false)
        MetricsRuntime.enabled = metrics
        MetricsRuntime.output = File(applicationRoot, "diagnostics/latest.json")
        if (metrics) frameProbe = WindowFrameProbe(window)
        val restoredID = savedInstanceState?.getLong("chatID", -1) ?: -1
        val retained = lastCustomNonConfigurationInstance as? ChatRuntime
        if (retained != null) activateChat(retained, restoredID)
        else {
            Thread(
                    {
                        val session = runCatching {
                            ChatRuntime.restoreProfile(applicationContext, ::invitationFromIntent)
                        }
                        runOnUiThread {
                            if (isDestroyed) return@runOnUiThread
                            if (session.isFailure) {
                                loading.text =
                                    "Не удалось открыть сохранённый вход. Попробуйте ещё раз."
                                return@runOnUiThread
                            }
                            val profile = session.getOrNull()
                            if (profile == null) {
                                showAccountEntry()
                                return@runOnUiThread
                            }
                            val name = "net-v1-u" + profile.userID
                            SQLiteStore.open(File(applicationRoot, name)) { result ->
                                result.fold(
                                    { opened ->
                                        if (isDestroyed) {
                                            opened.close {}
                                            return@fold
                                        }
                                        store = opened
                                        ChatRuntime.openAccount(opened, profile) { installed ->
                                            installed.fold(
                                                { local ->
                                                    local.media.recover { recovered ->
                                                        if (isDestroyed) local.close()
                                                        else
                                                            recovered.fold(
                                                                { activateChat(local, restoredID) },
                                                                {
                                                                    loading.text =
                                                                        "Не удалось восстановить медиа."
                                                                },
                                                            )
                                                    }
                                                },
                                                {
                                                    loading.text =
                                                        "Не удалось открыть сетевую историю."
                                                },
                                            )
                                        }
                                    },
                                    { loading.text = "Не удалось открыть историю." },
                                )
                            }
                        }
                    },
                    "KandinskySession",
                )
                .start()
        }
        onBackPressedDispatcher.addCallback(this) { leaveStudio() }
    }

    // Берёт приглашение из параметра запуска или ссылки со специальным адресом входа. Посторонняя
    // ссылка не будет прочитана как приглашение.
    private fun invitationFromIntent(): String? =
        intent.getStringExtra("network-invitation")
            ?: intent.data
                ?.takeIf { it.scheme == "kandinsky-native" && it.host == "join" }
                ?.getQueryParameter("invitation")

    // Подключает обмен, сообщения об ошибках и события удаления к текущему аккаунту. Сначала
    // восстанавливает черновик, затем при его отсутствии ищет прежний или недавний чат.
    private fun activateChat(local: ChatRuntime, restoredID: Long) {
        runtime = local
        store = local.store
        repository = local.repository
        rootName = local.store.root.name
        val sync =
            local.network
                ?: run {
                    showAccountEntry()
                    return
                }
        sync.onDeltas = { deltas ->
            for (delta in deltas) {
                local.playback.apply(delta)
                if (coordinator.currentChatID == delta.chat.id) {
                    if (delta.chat.hidden) coordinator.showChats() else chatView?.apply(delta)
                }
            }
            if (coordinator.currentChatID == null)
                local.repository.chats("") { result ->
                    result.onSuccess { rows ->
                        coordinator.setAllowedChats(rows.map { it.id }.toSet())
                    }
                }
        }
        sync.onStatus = { text ->
            chatView?.setNetworkStatus(text)
        }
        sync.onAccountErased = { eraseAccountLocally(sync) }
        sync.onSessionExpired = {
            if (!isDestroyed && runtime?.network === sync) {
                try {
                    NetworkCredentials(this).forget(sync.profile.userID)
                    switchAccount()
                    android.widget.Toast.makeText(
                            this,
                            "Сеанс истёк. Войдите снова.",
                            android.widget.Toast.LENGTH_LONG,
                        )
                        .show()
                } catch (_: Exception) {
                    chatView?.setNetworkStatus("Не удалось закрыть истёкший сеанс.")
                }
            }
        }
        sync.foreground(isForeground)
        val ticket = generation
        sync.savedStudioDraft { pending ->
            if (isDestroyed || generation != ticket) return@savedStudioDraft
            val record = pending.getOrNull()
            if (record?.hasContent == true) {
                restoredStudioDraft = record
                coordinator.showChats()
                return@savedStudioDraft
            }
            local.repository.chats("") { result ->
                if (isDestroyed || generation != ticket) return@chats
                val rows = result.getOrDefault(emptyList())
                val lastChat =
                    restoredID.takeIf { it > 0 }
                        ?: NetworkCredentials(this).selectedChat(sync.profile.userID)
                val selected =
                    rows.firstOrNull { it.id == lastChat && !it.archived }
                        ?: rows.firstOrNull { !it.archived }
                if (selected != null) {
                    coordinator.admitChat(selected.id)
                    coordinator.openChat(selected.id)
                } else {
                    coordinator.showChats()
                    val draft = chatView ?: return@chats
                    val draftTicket = generation
                    sync.studioThreads("recent", "", 0, 1) { remote ->
                        val first = remote.getOrNull()?.firstOrNull() ?: return@studioThreads
                        if (
                            isDestroyed ||
                                generation != draftTicket ||
                                draft.studioDraftTouched ||
                                historyDrawer != null
                        )
                            return@studioThreads
                        sync.admitStudioThread(first) { saved ->
                            if (
                                saved.isSuccess &&
                                    !isDestroyed &&
                                    generation == draftTicket &&
                                    !draft.studioDraftTouched &&
                                    historyDrawer == null
                            ) {
                                coordinator.admitChat(first.id)
                                coordinator.openChat(first.id)
                            }
                        }
                    }
                }
            }
        }
    }

    // Настраивает системные полосы и уменьшение области при открытии клавиатуры, учитывая версию
    // телефона. Иначе верхние или нижние кнопки могли бы оказаться под элементами системы.
    @Suppress("DEPRECATION")
    private fun configureWindow() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.WHITE
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        } else
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                    View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    // Передаёт новый выбор из управляющего объекта к подготовке экрана. Пустой номер означает,
    // что нужно открыть заготовку вместо существующей истории.
    private fun show(id: Long?) = showSurface(id)

    // Сохраняет параметры старого чата, закрывает его состояние и готовит новый экран скрытым.
    // Показывает черновик или историю только после подготовки и проверки номера перехода.
    @Suppress("DEPRECATION")
    private fun showSurface(id: Long?) {
        if (id != null)
            runtime?.network?.let { NetworkCredentials(this).selectChat(it.profile.userID, id) }
        if (historyDrawer != null) closeStudioDrawer(animated = false)
        generation++
        val ticket = generation
        if (runtime?.network != null) chatView?.let { studioOptions = it.generationOptions }
        chatView?.prepareForNavigation()
        state?.close()
        state = null
        val root = findViewById<android.view.ViewGroup>(android.R.id.content)
        pendingContainer?.let { pending ->
            runtime?.media?.withNavigationCache { root.removeView(pending) }
                ?: root.removeView(pending)
        }
        val container =
            android.widget.FrameLayout(this).apply {
                alpha = 0f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
        pendingContainer = container
        val inputBlocker =
            View(this).apply {
                isClickable = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        // Проверяет, что подготовленный экран ещё ожидается, и убирает прежние представления. До
        // этого запрет на нажатия и озвучивание скрывал незаконченный экран.
        fun reveal() {
            if (isDestroyed || generation != ticket || pendingContainer !== container) return
            val retire = {
                for (n in root.childCount - 1 downTo 0) {
                    val child = root.getChildAt(n)
                    if (child !== container) root.removeView(child)
                }
            }
            runtime?.media?.withNavigationCache(retire) ?: retire()
            container.removeView(inputBlocker)
            container.alpha = 1f
            container.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            pendingContainer = null
        }
        runtime?.network?.visibleChat(id)
        chatView = null
        val openToken = MetricsRuntime.recorder.begin()
        val repo = repository
        val surface: View
        if (id == null) {
            val sync = runtime?.network
            if (sync != null && repo != null) {
                val model = ConversationState(repo)
                state = model
                val chat = createConversationChrome(0L, model, runtime) { openStudioDrawer() }
                val record = restoredStudioDraft ?: StudioDraftRecord(UUID.randomUUID(), Draft())
                restoredStudioDraft = null
                val identity = record.identity
                var draftPrepared = false
                fun prepareDraft(draft: Draft, completion: (Result<Unit>) -> Unit) {
                    if (isDestroyed || generation != ticket) {
                        completion(Result.failure(CancellationException()))
                        return
                    }
                    sync.beginStudioDraft(StudioDraftRecord(identity, draft)) { result ->
                        if (isDestroyed || generation != ticket) {
                            completion(Result.failure(CancellationException()))
                            return@beginStudioDraft
                        }
                        completion(result.map { previous ->
                            draftPrepared = true
                            if (previous != null && previous.identity != identity) {
                                runtime?.media?.discardUnpublishedImports(previous.draft.attachmentDrafts)
                            }
                            Unit
                        })
                    }
                }
                model.persistStudioDraft = { chatID, draft, completion ->
                    when {
                        chatID != null -> sync.finishStudioDraft(identity, completion)
                        draftPrepared -> sync.saveStudioDraft(draft, identity, completion)
                        else -> prepareDraft(draft, completion)
                    }
                }
                chat.prepareSourceDraft = { completion ->
                    if (model.chatID != null) completion(Result.success(Unit))
                    else
                        sync.createStudioThread(identity) { created ->
                            if (isDestroyed || generation != ticket)
                                completion(Result.failure(CancellationException()))
                            else
                                created.fold(
                                    { id ->
                                        model.bindStudioDraft(id)
                                        coordinator.admitChat(id)
                                        NetworkCredentials(this).selectChat(sync.profile.userID, id)
                                        completion(Result.success(Unit))
                                    },
                                    { completion(Result.failure(it)) },
                                )
                        }
                }
                chat.restoreGenerationOptions(studioOptions)
                chat.onNewChat = { composeNetworkChat() }
                chat.onFirstSend = { options ->
                    submitFirstPrompt(chat, model, sync, identity, options, ticket)
                }
                chatView = chat
                surface = chat
                prepareDraft(record.draft) { started ->
                    if (isDestroyed || generation != ticket) return@prepareDraft
                    chat.openStudioDraft(record.draft)
                    started.onFailure(chat::showError)
                    container.post { reveal() }
                }
            } else {
                showAccountEntry()
                return
            }
        } else {
            val model = repo?.let { ConversationState(it) }
            state = model
            val chat = createConversationChrome(id, model, runtime) { openStudioDrawer() }
            surface = chat
            chatView = chat
            runtime?.network?.let {
                chat.restoreGenerationOptions(studioOptions)
                chat.onNewChat = { composeNetworkChat() }
            }
            if (repo != null && model != null)
                model.open(id) { result ->
                    if (generation == ticket)
                        result.fold(
                            {
                                chat.openHistory(repo, model.anchor) { opened ->
                                    if (generation == ticket) {
                                        if (MetricsRuntime.enabled)
                                            MetricsRuntime.recorder.end(
                                                "chat_metadata_open_ms",
                                                openToken,
                                            )
                                        opened.exceptionOrNull()?.let { chat.showError(it) }
                                        reveal()
                                    }
                                }
                                model.onDelta = { delta ->
                                    if (generation == ticket) chat.apply(delta)
                                }
                            },
                            {
                                chat.showError(it)
                                reveal()
                            },
                        )
                }
            else container.post { reveal() }
        }
        surface.setOnApplyWindowInsetsListener { _, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                top = bars.top
                bottom = maxOf(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom)
            } else {
                top = insets.systemWindowInsetTop
                bottom = insets.systemWindowInsetBottom
            }
            baseInsetTop = top
            baseInsetBottom = bottom
            (surface as? KandinskyInsetHost)?.updateInsets(top, bottom)
            insets
        }
        container.addView(surface, android.widget.FrameLayout.LayoutParams(-1, -1))
        container.addView(inputBlocker, android.widget.FrameLayout.LayoutParams(-1, -1))
        root.addView(container, android.widget.FrameLayout.LayoutParams(-1, -1))
        surface.requestApplyInsets()
    }

    // Передаёт экрану службы обмена, файлов и проигрывания текущего владельца, если они открыты.
    // Сам экран не должен заново создавать эти службы.
    internal fun createConversationChrome(
        chatID: Long,
        state: ConversationState?,
        runtime: ChatRuntime?,
        back: (() -> Unit)?,
    ): ConversationChrome {
        val services =
            runtime?.let { ConversationServices(it.network, it.media, it.admission, it.playback) }
        return ConversationChrome(this, chatID, state, services, back)
    }

    // Связывает команды панели с открытием чата, созданием, удалением и выходом. Сначала
    // закрывает панель, чтобы её слой не остался поверх следующего экрана.
    private fun makeStudioHistoryDrawer(sync: NetworkSync) =
        StudioHistoryPanel(
            this,
            sync,
            coordinator.currentChatID,
            closeCurrent = { closeStudioDrawer() },
            open = { id ->
                closeStudioDrawer {
                    coordinator.admitChat(id)
                    coordinator.openChat(id)
                }
            },
            compose = { closeStudioDrawer(then = ::composeNetworkChat) },
            logout = { closeStudioDrawer { logoutStudio(sync) } },
            eraseAccount = { closeStudioDrawer { confirmAccountErasure(sync) } },
            deleted = { id ->
                closeStudioDrawer {
                    coordinator.forgetChat(id)
                    if (coordinator.currentChatID == id) coordinator.showChats()
                }
            },
        )

    // Открывает одну панель поверх видимой истории с учётом направления письма и системных
    // отступов. Пока она открыта, скрывает чат от озвучивания и сообщает ему о перекрытии.
    private fun openStudioDrawer() {
        val sync = runtime?.network ?: return coordinator.showChats()
        val chat = chatView ?: return
        if (historyDrawer != null || pendingContainer != null || chat.isStartingThread) return
        val root = findViewById<android.view.ViewGroup>(android.R.id.content)
        val overlay = FrameLayout(this)
        val shade =
            View(this).apply {
                setBackgroundColor(Color.argb(138, 0, 0, 0))
                alpha = 0f
                contentDescription = "Закрыть историю"
                setOnClickListener { closeStudioDrawer() }
            }
        val panel = makeStudioHistoryDrawer(sync)
        panel.contentDescription = "История запросов"
        val width = minOf(root.width, (304 * resources.displayMetrics.density).toInt())
        if (width <= 0) return
        val radius = 16 * resources.displayMetrics.density
        val rtl = root.layoutDirection == View.LAYOUT_DIRECTION_RTL
        panel.background =
            android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadii =
                    if (rtl) floatArrayOf(radius, radius, 0f, 0f, 0f, 0f, radius, radius)
                    else floatArrayOf(0f, 0f, radius, radius, radius, radius, 0f, 0f)
            }
        panel.clipToOutline = true
        panel.translationX = if (rtl) width.toFloat() else -width.toFloat()
        panel.updateInsets(baseInsetTop, baseInsetBottom)
        overlay.addView(shade, FrameLayout.LayoutParams(-1, -1))
        overlay.addView(panel, FrameLayout.LayoutParams(width, -1, Gravity.START))
        overlay.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                panel.updateInsets(
                    bars.top,
                    maxOf(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom),
                )
            } else
                @Suppress("DEPRECATION")
                panel.updateInsets(insets.systemWindowInsetTop, insets.systemWindowInsetBottom)
            insets
        }
        chat.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        chat.setHistoryDrawerVisible(true)
        historyDrawer = overlay
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        overlay.requestApplyInsets()
        panel.animate().translationX(0f).setDuration(246).start()
        shade.animate().alpha(1f).setDuration(246).start()
    }

    // Скрывает клавиатуру панели и убирает её сразу или по окончании движения. Повторный запрос
    // во время закрытия не должен запустить второй переход.
    private fun closeStudioDrawer(animated: Boolean = true, then: (() -> Unit)? = null) {
        val drawer = historyDrawer ?: return
        if (drawerClosing && animated) return
        drawerClosing = true
        val panel = drawer.getChildAt(1) as StudioHistoryPanel
        panel.hideKeyboard()
        // Проверяет текущую панель, снимает её и возвращает чату доступность и обычный показ.
        // Только затем выполняет действие, ради которого панель закрыли.
        fun finish() {
            if (historyDrawer !== drawer) return
            historyDrawer = null
            drawerClosing = false
            (drawer.parent as? android.view.ViewGroup)?.removeView(drawer)
            chatView?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            chatView?.setHistoryDrawerVisible(false)
            then?.invoke()
        }
        panel.animate().cancel()
        if (!animated) {
            finish()
            return
        }
        drawer.getChildAt(0).animate().alpha(0f).setDuration(220).start()
        panel
            .animate()
            .translationX(
                if (panel.layoutDirection == View.LAYOUT_DIRECTION_RTL) panel.width.toFloat()
                else -panel.width.toFloat()
            )
            .setDuration(220)
            .withEndAction { finish() }
            .start()
    }

    // Убирает местный вход, закрывает его экран и отдельно отправляет выход прежним ключом. Если
    // местная запись не очистилась, показывает ошибку вместо смены аккаунта.
    private fun logoutStudio(sync: NetworkSync) {
        try {
            NetworkCredentials(this).removeActive()
            switchAccount()
            Thread(
                    {
                        val api = NetworkAPI(sync.profile)
                        try {
                            api.json("/v1/kandinsky/logout", org.json.JSONObject())
                        } catch (_: Exception) {} finally {
                            api.close()
                        }
                    },
                    "KandinskyLogout",
                )
                .start()
        } catch (_: Exception) {
            android.widget.Toast.makeText(
                    this,
                    "Не удалось выйти",
                    android.widget.Toast.LENGTH_LONG,
                )
                .show()
        }
    }

    // Открывает защищённое поле пароля, проверяет длину и ждёт подтверждения сервера. При ошибке
    // возвращает возможность ввести пароль, при успехе начинает местную очистку.
    private fun confirmAccountErasure(sync: NetworkSync) {
        if (runtime?.network !== sync || isDestroyed) return
        val password =
            android.widget.EditText(this).apply {
                hint = "Пароль"
                isSingleLine = true
                inputType =
                    android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                filters = arrayOf(android.text.InputFilter.LengthFilter(256))
                accessibilityDelegate =
                    object : View.AccessibilityDelegate() {
                        // Присваивает полю пароля постоянный идентификатор доступности. Так
                        // проверка может найти нужное поле независимо от подписи и языка системы.
                        override fun onInitializeAccessibilityNodeInfo(
                            host: View,
                            info: android.view.accessibility.AccessibilityNodeInfo,
                        ) {
                            super.onInitializeAccessibilityNodeInfo(host, info)
                            info.viewIdResourceName = "account.erasure.password"
                        }
                    }
            }
        val padding = (resources.displayMetrics.density * 24).toInt()
        val feedback =
            TextView(this).apply {
                visibility = View.GONE
                setTextColor(Color.rgb(167, 31, 44))
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
        val field =
            android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(padding, 0, padding, 0)
                addView(password, android.widget.LinearLayout.LayoutParams(-1, -2))
                addView(feedback, android.widget.LinearLayout.LayoutParams(-1, -2))
            }
        val dialog =
            android.app.AlertDialog.Builder(this)
                .setTitle("Удалить аккаунт?")
                .setMessage(
                    "История, запросы и медиа будут удалены. Для подтверждения введите пароль."
                )
                .setView(field)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить навсегда", null)
                .create()
        dialog.show()
        val confirm = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
        confirm.setOnClickListener {
            val value = password.text.toString()
            if (value.codePointCount(0, value.length) !in 12..128) {
                feedback.text = "Проверьте пароль."
                feedback.visibility = View.VISIBLE
                return@setOnClickListener
            }
            feedback.visibility = View.GONE
            confirm.isEnabled = false
            password.isEnabled = false
            sync.eraseStudioAccount(value) { result ->
                if (runtime?.network !== sync || isDestroyed) {
                    dialog.dismiss()
                    return@eraseStudioAccount
                }
                result.fold(
                    {
                        dialog.dismiss()
                        eraseAccountLocally(sync)
                    },
                    { error ->
                        confirm.isEnabled = true
                        password.isEnabled = true
                        feedback.text =
                            if ((error as? NetworkFailure)?.status == 403) "Неверный пароль."
                            else "Проверьте соединение и попробуйте снова."
                        feedback.visibility = View.VISIBLE
                    },
                )
            }
        }
    }

    // Принимает удаление только от действующего аккаунта, ставит отметку и закрывает панель. Если
    // приложение остановится, отметка позволит возобновить очистку.
    private fun eraseAccountLocally(sync: NetworkSync) {
        if (runtime?.network !== sync || isDestroyed) return
        val id = sync.profile.userID
        runCatching { LocalAccountErasure.stage(applicationContext, id) }
        closeStudioDrawer(animated = false)
        switchAccount(erasing = id)
    }

    // На отдельном потоке повторяет отметку и завершает очистку файлов и входа владельца. При
    // сбое окно предлагает повторить ту же операцию.
    private fun finishErasure(userID: Long) {
        val context = applicationContext
        Thread(
                {
                    val result = runCatching {
                        LocalAccountErasure.stage(context, userID)
                        LocalAccountErasure.finish(context, NetworkCredentials(context))
                    }
                    runOnUiThread {
                        if (!isDestroyed)
                            result.fold(
                                { showAccountEntry() },
                                {
                                    android.app.AlertDialog.Builder(this)
                                        .setTitle("Не удалось очистить данные")
                                        .setMessage(
                                            "Повторите очистку данных этого аккаунта на устройстве."
                                        )
                                        .setCancelable(false)
                                        .setPositiveButton("Повторить") { _, _ ->
                                            finishErasure(userID)
                                        }
                                        .show()
                                },
                            )
                    }
                },
                "KandinskyAccountErasure",
            )
            .start()
    }

    // Показывает форму с отдельной службой входа и учитывает клавиатуру. После выбора запускает
    // главное окно заново с сохранённым профилем и очищает прежний стек экранов.
    private fun showAccountEntry() {
        pendingContainer = null
        entry?.close()
        val screen =
            AccountEntryView(
                this,
                NetworkAPI(null),
                {
                    entry?.close()
                    entry = null
                    startActivity(
                        android.content.Intent(this, MainActivity::class.java).apply {
                            flags =
                                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                    )
                    finish()
                },
                null,
            )
        entry = screen
        screen.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                screen.updateInsets(
                    bars.top,
                    maxOf(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom),
                )
            } else screen.updateInsets(insets.systemWindowInsetTop, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(screen)
        screen.requestApplyInsets()
    }

    // Снимает выбор, сохраняет точку чтения и закрывает службы прежнего владельца. При удалении
    // сразу скрывает прежние данные и ждёт закрытия файла перед очисткой.
    private fun switchAccount(erasing: Long? = null) {
        generation++
        pendingContainer = null
        chatView?.captureAnchor()
        chatView = null
        state?.close()
        state = null
        studioOptions = KandinskyOptions()
        val old = runtime
        runtime = null
        repository = null
        store = null
        coordinator.close()
        if (erasing != null) setContentView(FrameLayout(this))
        // После освобождения служб выбирает местную очистку или экран входа. Обычный вход не
        // открывается, если окно уже закрыто.
        fun finish() {
            if (erasing != null) finishErasure(erasing) else if (!isDestroyed) showAccountEntry()
        }
        if (old == null) finish()
        else {
            old.network?.close()
            old.playback.stop {
                old.close()
                if (erasing == null) finish() else old.store.close { finish() }
            }
        }
    }

    // Запрашивает заготовку нового чата при действующем подключении. Если первая отправка уже
    // создаёт историю, второй запуск не происходит.
    private fun composeNetworkChat() {
        if (runtime?.network != null && chatView?.isStartingThread != true) coordinator.showChats()
    }

    // Ставит отметку о начале отправки и использует существующий номер черновика или создаёт чат
    // с тем же номером операции. После смены экрана поздний ответ не откроет ненужный чат.
    private fun submitFirstPrompt(
        chat: ConversationChrome,
        model: ConversationState,
        sync: NetworkSync,
        identity: UUID,
        options: KandinskyOptions,
        ticket: Int,
    ) {
        if (isDestroyed || generation != ticket || chat.isStartingThread) return
        chat.setStartingThread(true)
        // Привязывает черновик к полученному чату и передаёт запрос с настройками генерации. По
        // завершении открывает сохранённый чат либо показывает ошибку на текущем экране.
        fun send(id: Long) {
            if (isDestroyed || generation != ticket) return
            model.bindStudioDraft(id)
            model.send(options) sent@{ result ->
                if (isDestroyed || generation != ticket) return@sent
                chat.setStartingThread(false)
                result.fold(
                    {
                        coordinator.admitChat(id)
                        coordinator.openChat(id)
                    },
                    { chat.showError(it) },
                )
            }
        }
        val bound = model.chatID
        if (bound != null) {
            send(bound)
            return
        }
        sync.createStudioThread(identity) { result ->
            if (isDestroyed || generation != ticket) return@createStudioThread
            result.fold(
                ::send,
                {
                    chat.setStartingThread(false)
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Не удалось начать историю")
                        .setMessage("Проверьте соединение.")
                        .setPositiveButton("Понятно", null)
                        .show()
                },
            )
        }
    }

    // Сначала закрывает открытую панель, иначе возвращает к входу или закрывает окно по текущему
    // состоянию. Во время создания истории не прерывает первый запрос кнопкой назад.
    private fun leaveStudio() {
        if (chatView?.isStartingThread == true) return
        if (historyDrawer != null) closeStudioDrawer()
        else if (runtime?.network == null && coordinator.currentChatID != null)
            coordinator.showChats()
        else finish()
    }

    // Отмечает, что окно видно, возобновляет обмен и разрешает показ файлов. Это возвращает
    // работу после выхода из фона.
    override fun onStart() {
        super.onStart()
        isForeground = true
        runtime?.network?.foreground(true)
        chatView?.setMediaVisible(true)
    }

    // Ставит обмен и вложения на паузу, останавливает проигрывание и запись, сохраняет позицию.
    // Так скрытое окно освобождает ресурсы и помнит, где пользователь читал.
    override fun onStop() {
        MetricsRuntime.export()
        isForeground = false
        runtime?.network?.foreground(false)
        chatView?.setMediaVisible(false)
        runtime?.playback?.stop()
        chatView?.stopCapture()
        chatView?.captureAnchor()
        state?.flush {}
        super.onStop()
    }

    // Записывает номер текущего чата и папку для нового окна. Просит сохранить точку чтения,
    // чтобы пересоздание не сбрасывало место.
    override fun onSaveInstanceState(outState: Bundle) {
        chatView?.captureAnchor()
        state?.flush {}
        outState.putLong("chatID", coordinator.currentChatID ?: -1)
        outState.putString("rootName", rootName)
        super.onSaveInstanceState(outState)
    }

    // Оставляет готовые службы аккаунта при пересоздании окна, если история уже открыта. Новое
    // окно может вернуть их без повторного запуска базы.
    @Deprecated("Stores the runtime across Activity recreation")
    override fun onRetainCustomNonConfigurationInstance(): Any? =
        if (repository != null) runtime else null

    // При серьёзном предупреждении системы просит экран и хранилище освободить лишнюю память. Это
    // снижает риск остановки приложения из-за удержания подготовленных копий.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            runtime?.media?.trimMemory()
            chatView?.trimMemory()
        }
    }

    // Закрывает форму, измерения и состояние окна, убирает ожидающие ответы выбора файлов.
    // Сохраняет общие службы только при пересоздании окна с готовой историей.
    override fun onDestroy() {
        entry?.close()
        entry = null
        frameProbe?.close()
        frameProbe = null
        chatView?.captureAnchor()
        generation++
        state?.close()
        documentResult = null
        cameraPermission = null
        if (!isChangingConfigurations || repository == null) {
            runtime?.close() ?: store?.close {}
        }
        coordinator.close()
        super.onDestroy()
    }

    // Возвращает успех сразу при уже выданном доступе или запоминает обработчик и просит систему
    // показать разрешение. Камера не откроется до ответа.
    override fun requestCameraPermission(callback: (Boolean) -> Unit) {
        if (
            checkSelfPermission(android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        )
            callback(true)
        else {
            cameraPermission = callback
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 7402)
        }
    }

    // Сохраняет обработчик и открывает системный выбор документа с отдельным номером запроса.
    // Результат можно направить владельцу именно этой операции.
    @Suppress("DEPRECATION")
    override fun launchDocumentPicker(request: Intent, callback: (Intent?) -> Unit) {
        documentResult = callback
        startActivityForResult(request, 7401)
    }

    // Убирает ссылку на ожидающий ответ выбора файла. После закрытия экрана поздний результат не
    // уйдёт прежнему обработчику.
    override fun clearDocumentResult() {
        documentResult = null
    }

    // Передаёт выбранный документ только для своего номера запроса, а отмену обозначает пустым
    // ответом. Остальные результаты обрабатывает система.
    @Deprecated("Native SDK-only scoped picker result")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7401) documentResult?.invoke(if (resultCode == RESULT_OK) data else null)
    }

    // Для своего номера доступа к камере забирает одноразовый обработчик и сообщает решение.
    // Повторный системный вызов уже не запустит прежнее действие.
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7402) {
            val callback = cameraPermission
            cameraPermission = null
            callback?.invoke(
                grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED
            )
        }
    }
}
