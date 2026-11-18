// Экран переписки на андроиде соединяет ленту, черновик и меню генерации. Он раскладывает поле и
// нарисованные кнопки вокруг истории, передаёт их нажатия в состояние и отдельные обработчики.
// Для средств доступности те же кнопки получает система озвучивания.

package dev.kirill.kandinsky.chat.conversation

import android.app.Activity
import android.content.Context
import android.graphics.*
import android.os.Build
import android.text.InputType
import android.text.StaticLayout
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.EditText
import android.widget.TextView
import androidx.core.graphics.withClip
import androidx.core.graphics.withSave
import dev.kirill.kandinsky.chat.attachments.AttachmentPicker
import dev.kirill.kandinsky.chat.attachments.ChatAttachmentCoordinator
import dev.kirill.kandinsky.chat.domain.KandinskyOptions
import dev.kirill.kandinsky.chat.domain.StudioModel
import dev.kirill.kandinsky.chat.rendering.*

internal class ConversationChrome(
    context: Context,
    private val chatID: Long,
    private val state: ConversationState? = null,
    private val services: ConversationServices? = null,
    private val back: (() -> Unit)?,
) : ViewGroup(context), KandinskyInsetHost {
    // Создаём вариант экрана без номера и обработчика возврата. Он нужен там, где показывается
    // только оформление и поле, а открывать сохранённую переписку не требуется.
    constructor(context: Context) : this(context, 0L, back = null)

    private val timeline = ChatScrollHost(context)
    private var activeGenerationID: Long? = null
    private var generationLookup = 0
    private val isStoppingGeneration
        // По номеру активного задания определяем, должна ли правая кнопка останавливать
        // генерацию. По этому же признаку выбираем рисунок и название действия.
        get() = activeGenerationID != null

    // Запрашиваем последнее активное задание у слоя связи. Принимаем ответ только с текущим
    // номером проверки, иначе запоздалое чтение могло бы вернуть устаревшую кнопку остановки.
    private fun refreshActiveGeneration() {
        val network = services?.network ?: return
        if (chatID <= 0L) return
        val lookup = ++generationLookup
        network.activeGeneration(chatID) { result ->
            if (lookup == generationLookup)
                result.onSuccess { active ->
                    if (activeGenerationID != active) {
                        activeGenerationID = active
                        requestLayout()
                        invalidate()
                    }
                }
        }
    }

    private var rowCount = 0
    private var historyReady = false
    private var networkTitle: String? = null
    private var networkStatus: String? = null

    // Обновляем состояние связи и оповещаем владельца передачи. При обрыве он приостанавливает
    // загрузку, а при восстановлении может продолжить её с сохранённого состояния.
    fun setNetworkStatus(value: String) {
        networkStatus = value
        if (value == "В сети") attachments?.resumePendingUpload(reconnected = true)
        else if (value.startsWith("Нет связи")) attachments?.pausePendingUpload()
        invalidate()
    }

    private var keyboardHeight = 0
    private var studioFeedback: TextView? = null
    private val clearStudioFeedback = Runnable {
        studioFeedback?.let(::removeView)
        studioFeedback = null
        requestLayout()
    }
    private val attachments =
        if (services != null && state != null && context is Activity && context is AttachmentPicker) {
            ChatAttachmentCoordinator(
                activity = context,
                picker = context,
                network = services.network,
                media = services.media,
                admission = services.admission,
                state = state,
                isActive = { isAttachedToWindow },
                options = { options },
                selectOptions = ::restoreGenerationOptions,
                showError = ::attachmentError,
                previewVideo = timeline::previewStudioVideo,
                refresh = ::requestLayout,
                refreshProgress = {
                    requestLayout()
                    invalidate()
                },
                prepareDraft = { completion ->
                    prepareSourceDraft?.invoke(completion) ?: completion(Result.success(Unit))
                },
            )
        } else null
    private val studioUploadBar = RectF()
    var prepareSourceDraft: (((Result<Unit>) -> Unit) -> Unit)? = null
    private val transferProgress: Double?
        // Берём ход местной передачи, если она идёт. Иначе делим все принятые байты с других
        // устройств на их общий размер, чтобы один маленький файл не менял средний процент так же
        // сильно, как большой.
        get() {
            if (attachments?.isUploading == true) return attachments.progress
            val network = services?.network ?: return null
            val remote =
                network.studioTransfers.filter {
                    !it.deviceId.equals(network.profile.deviceID, true)
                }
            if (remote.isEmpty()) return null
            return remote.sumOf { it.received }.toDouble() / remote.sumOf { it.total }
        }

    private val transferTitle: String
        // Подписываем полосу по источнику текущей передачи. Если загрузка идёт с другого
        // телефона, человек видит, почему поле на этом устройстве ничего не отправляло.
        get() =
            if (attachments?.isUploading == true) "Загрузка файла"
            else "Загрузка на другом устройстве"

    private val referenceIcon by lazy {
        BitmapFactory.decodeResource(
            resources,
            dev.kirill.kandinsky.chat.R.drawable.kandinsky_attachment_photo,
        )
    }
    private var hadStudioPhoto = false
    private val referenceChip = RectF()
    private var actionsUI: ConversationUIController? = null
    private var applying = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    var onNewChat: (() -> Unit)? = null
    var onFirstSend: ((KandinskyOptions) -> Unit)? = null
    var isStartingThread = false
        private set

    val studioDraftTouched
        // Проверяем, было ли поле открыто для ввода, менялся ли черновик или началось ли создание
        // чата. Эти признаки помогают отличить нетронутый экран от уже начатой работы.
        get() = input.hasFocus() || (state?.draft?.revision ?: 0L) > 0L || isStartingThread

    val generationOptions
        // Готовим переносимые настройки для следующего экрана. Режим улучшения привязан к
        // конкретному видео, а исходное фото требует режима видео, поэтому эти случаи
        // обрабатываем отдельно.
        get() =
            when {
                options.model == "video_sr" -> KandinskyOptions()
                hadStudioPhoto -> options.copy(model = "video")
                else -> options
            }

    // Возвращаем записанные параметры и восстанавливаем подпись модели. Защита на время
    // присваивания предотвращает обратную запись и повторное уведомление о том же черновике.
    fun restoreGenerationOptions(value: KandinskyOptions) {
        restoringGenerationOptions = true
        try {
            options = value
        } finally {
            restoringGenerationOptions = false
        }
        modelName =
            when (value.model) {
                "video_sr" -> "Улучшение видео"
                "video" -> "Video Lite"
                else -> "Image Lite"
            }
        invalidate()
    }

    // Открываем поле до выдачи сервером номера чата. Модель состояния получает восстановленный
    // черновик, если человек уже набирал запрос.
    fun openStudioDraft(restored: dev.kirill.kandinsky.chat.domain.Draft? = null) {
        historyReady = true
        state?.openStudioDraft(restored)
    }

    // Переключаем признак создания первого чата и уведомляем экран. Так кнопки и поле сразу
    // учитывают, что следующий переход ещё выполняется.
    fun setStartingThread(value: Boolean) {
        isStartingThread = value
        state?.onChange?.invoke()
    }

    private val wallpaper = KandinskyWallpaper()
    private val modelFrame = RectF()
    private val modelTextPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val modelChevronFrame = RectF()
    private val header = RectF()
    private var top = 0
    private var bottom = 0
    private val nodes = ArrayList<NativeAction>(12)
    private var pendingClick: NativeAction? = null
    private val backControl =
        NativeAction(300, "chat.back", "История запросов", RectF(), action = back)
    private val newChatControl =
        NativeAction(312, "chat.compose", "Новый чат", RectF(), action = { onNewChat?.invoke() })
    private val attachmentControl =
        NativeAction(301, "composer.plus", "Добавить фото или видео", RectF(), action = ::addAttachment)
    private val modelControl =
        NativeAction(304, "composer.model", "Модель генерации", RectF(), action = ::chooseGenerationOptions)
    private val settingsControl =
        NativeAction(313, "composer.settings", "Параметры генерации", RectF(), action = ::showGenerationSettings)
    private val uploadStatus =
        NativeAction(318, "studio.upload.progress", "", RectF(), button = false)
    private val removeReferenceControl =
        NativeAction(316, "studio.reference.remove", "", RectF(), action = { attachments?.replacePhoto(null) })
    private val sendControl =
        NativeAction(305, "composer.send", "", RectF(), action = ::submitOrStopGeneration)
    private val welcomeTitle =
        NativeAction(314, "chat.empty.title", "Что создадим?", RectF(), button = false)
    private val welcomeControl =
        NativeAction(315, "chat.welcome", "Попробовать с магнолией", RectF(), action = ::fillWelcomePrompt)
    private val chatTitle =
        NativeAction(306, "chat.title", "Kandinsky", RectF(), button = false)
    private val attachmentErrorStatus =
        NativeAction(319, "studio.attachment.error", "", RectF(), button = false)
    private val input =
        EditText(context).apply {
            background = null
            hint = "Опишите изображение или видео"
            textSize = 16f
            setTextColor(KandinskyPalette.studioInk)
            setHintTextColor(KandinskyPalette.studioMuted)
            inputType =
                InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(0, 0, 0, 0)
            accessibilityDelegate =
                object : View.AccessibilityDelegate() {
                    // Даём системному полю ввода постоянное имя для средств доступности и
                    // проверок. Обычные свойства поля по-прежнему заполняет система.
                    override fun onInitializeAccessibilityNodeInfo(
                        host: View,
                        info: AccessibilityNodeInfo,
                    ) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.viewIdResourceName = "composer.input"
                    }
                }
        }
    private val ax =
        NativeAccessibility(
            this,
            "chat.screen",
            { nodes },
            { if (state == null) listOf(input) else listOf(timeline, input) },
        )
    private val body = RectF()
    private val composer = RectF()
    private val card = RectF()
    private var restoringGenerationOptions = false
    private var options = KandinskyOptions()
        // Запоминаем текущие параметры и записываем осознанное изменение в черновик. При
        // восстановлении сохранённого значения такую запись пропускаем, чтобы не зациклить
        // уведомления.
        set(value) {
            field = value
            if (!restoringGenerationOptions) state?.updateGenerationOptions(value)
        }

    private var modelName = "Image Lite"
    private var modelCatalog: List<StudioModel>? = null
    private var loadingModels = false
    private val d
        // Читаем плотность экрана для пересчёта размеров элементов. При одинаковом размере в
        // настройках кнопки остаются сопоставимыми на экранах с разным числом точек.
        get() = resources.displayMetrics.density

    private val s
        // Ограничиваем размер узора и припусков истории шириной экрана. На узком устройстве эти
        // размеры уменьшаются вместе с доступным местом.
        get() = minOf(width / 384f, resources.displayMetrics.density)

    private val composerUnit
        // Выбираем единицу размеров панели ввода из плотности и измеренной ширины. Панель поэтому
        // помещается и на экране уже расчётной ширины.
        get() = minOf(d, measuredWidth / 390f)

    // Соединяем ленту, загрузку вложений и изменения черновика. При переносе текста в поле
    // проверяем, закончила ли его составлять клавиатура, и подавляем встречное уведомление. Иначе
    // обновление состояния могло бы сбить ещё набираемое слово.
    init {
        services?.network?.onStudioTransfersChanged = {
            requestLayout()
            invalidate()
        }
        services?.let {
            timeline.configureMedia(it.media)
            timeline.configurePlayback(it.playback, it.media)
        }
        // Сплошные панели оформления не считывают прокручиваемое содержимое для размытия
        wallpaper.onReady = { postInvalidateOnAnimation() }
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        if (state != null) addView(timeline)
        addView(input)
        input.isEnabled = state == null
        KandinskyAssets.load(context, this)
        timeline.onAnchor = {
            state?.anchor = it
            if (chatID > 0L) services?.network?.viewport(chatID, it)
        }
        state?.onChange = {
            state.draft.generationOptions?.let { saved ->
                if (options != saved) restoreGenerationOptions(saved)
            }
            input.isEnabled = state.isReady && historyReady && !isStartingThread
            if (services?.network != null) {
                val source = state.draft.attachmentDrafts.firstOrNull()
                val hasPhoto = source?.mime?.startsWith("image/") == true
                if (source?.mime == "video/mp4") {
                    if (options.model != "video_sr")
                        restoreGenerationOptions(KandinskyOptions(model = "video_sr"))
                } else if (options.model == "video_sr") {
                    restoreGenerationOptions(
                        KandinskyOptions(model = if (hasPhoto) "video" else "image")
                    )
                } else if (hasPhoto && options.model != "video" || !hasPhoto && hadStudioPhoto) {
                    restoreGenerationOptions(
                        KandinskyOptions(
                            model = "video",
                            aspect = options.aspect,
                            count = options.count,
                            seed = options.seed,
                        )
                    )
                }
                hadStudioPhoto = hasPhoto
            }
            attachments?.resumePendingUpload()
            requestLayout()
            invalidate()
            if (
                input.text.toString() != state.draft.text &&
                    android.view.inputmethod.BaseInputConnection.getComposingSpanStart(input.text) <
                        0
            ) {
                applying = true
                ComposerInputBridge.synchronize(input, state.draft.text)
                applying = false
            }
        }
        input.addTextChangedListener(
            object : android.text.TextWatcher {
                // Оставляем обязательный вызов наблюдателя пустым. Перед заменой поля новое
                // значение ещё неизвестно, а старый текст уже есть в черновике.
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                // Записываем новый текст пользователя в черновик. Программное восстановление поля
                // пропускаем, иначе та же правка вернулась бы в состояние повторно.
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (!applying && state != null) {
                        state.editDraft(state.draft.copy(text = s.toString()))
                    }
                }

                // Завершаем обязательный вызов наблюдателя без второй записи. Новый текст уже
                // принят в момент изменения поля.
                override fun afterTextChanged(s: android.text.Editable?) {}
            }
        )
    }

    // Подключаем меню строк и действия генерации, затем открываем ленту на сохранённой позиции.
    // Поле становится доступно после подготовки первого кадра. Ошибку открытия возвращаем
    // вызвавшему экрану вместо состояния готовности.
    fun openHistory(
        repository: dev.kirill.kandinsky.chat.storage.ChatRepository,
        anchor: dev.kirill.kandinsky.chat.domain.ReadingAnchor?,
        completion: (Result<Unit>) -> Unit,
    ) {
        timeline.onError = { showError(it) }
        if (services?.network != null)
            repository.chats("") {
                it.onSuccess { rows ->
                    networkTitle = rows.firstOrNull { it.id == chatID }?.title
                    requestLayout()
                    invalidate()
                }
            }
        if (state != null) {
            val ui =
                ConversationUIController(
                    context as android.app.Activity,
                    chatID,
                    repository,
                    state,
                    { apply(it) },
                )
            services?.network?.let { sync ->
                ui.generationAction = { message, action, operation, completion ->
                    sync.generationAction(
                        chatID,
                        message,
                        action,
                        operation,
                        completion = completion,
                    )
                }
                ui.reportGeneration = { message, reason, completion ->
                    sync.reportGeneration(chatID, message, reason, completion)
                }
                ui.deleteGenerationContent = { message, blob, completion ->
                    sync.deleteGenerationContent(chatID, message, blob, completion)
                }
                timeline.onStudioResultDelete = ui::removeGenerationContent
                ui.retrySlotAction = { message, slot, operation, completion ->
                    sync.generationAction(
                        chatID,
                        message,
                        dev.kirill.kandinsky.chat.domain.StudioGenerationAction.RETRY,
                        operation,
                        slot,
                        completion,
                    )
                }
                timeline.onStudioGenerationAction = ui::performGenerationAction
                timeline.onStudioRetrySlot = ui::retrySlot
            }
            actionsUI = ui
            timeline.onMessageLongPress = { message, _ -> ui.menu(message) }
            timeline.onMessageAction = { message, kind -> ui.perform(kind, message) }
            timeline.onCountChanged = {
                rowCount = it
                requestLayout()
                invalidate()
            }
        }
        timeline.open(chatID, repository, anchor) { result ->
            if (result.isFailure) {
                completion(result)
                return@open
            }
            refreshActiveGeneration()
            rowCount = timeline.messageCount
            requestLayout()
            invalidate()
            timeline.prepareInitialFrame { prepared ->
                if (prepared.isSuccess) {
                    historyReady = true
                    state?.onChange?.invoke()
                    requestLayout()
                    invalidate()
                }
                completion(prepared)
            }
        }
    }

    // Обновляем строки ленты и при перемене активного задания проверяем, какую генерацию ещё
    // можно остановить. Если сервер скрыл текущий чат, уходим из него в список.
    fun apply(delta: dev.kirill.kandinsky.chat.domain.MessageDelta) {
        timeline.apply(delta)
        if (delta.chat.id == chatID && services?.network != null) {
            val active = activeGenerationID
            val rows = delta.inserted + delta.updated
            if (
                rows.any { (it.studioGeneration?.isActive == true) != (it.id == active) } ||
                    active in delta.removedIDs
            )
                refreshActiveGeneration()
        }
        if (delta.chat.hidden) back?.invoke()
    }

    // Восстанавливаем получение хода передачи и просим продолжить сохранённую загрузку. Пока
    // экран был вне окна, он мог пропустить изменение процентов.
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        services?.network?.onStudioTransfersChanged = {
            requestLayout()
            invalidate()
        }
        attachments?.resumePendingUpload()
    }

    // Признаём просроченными ответы о генерации и закрываем вложения, меню и фон. Убираем
    // отложенную плашку ошибки, чтобы она не затронула уже снятый экран.
    override fun onDetachedFromWindow() {
        generationLookup++

        attachments?.close(
            discardDraft = services?.network == null && chatID == 0L && state?.chatID == null
        )
        removeCallbacks(clearStudioFeedback)
        clearStudioFeedback.run()
        actionsUI?.close()
        wallpaper.close()
        super.onDetachedFromWindow()
    }

    // Передаём просьбу освободить память ленте и фону, затем просим перерисовать экран. Сами
    // сохранённые сообщения при этом остаются в хранилище.
    fun trimMemory() {
        timeline.trimMemory()
        wallpaper.trimMemory()
        invalidate()
    }

    // Разрешаем подготовку вложений только при видимой ленте на переднем плане. Для движущихся
    // вложений возвращаем обычный режим, который затем уточняет список чатов.
    fun setMediaVisible(visible: Boolean) {
        timeline.setMediaVisible(visible && timeline.isHostForeground())
        timeline.setMovingMediaVisible(true)
    }

    // Перед открытием списка скрываем клавиатуру и убираем фокус с поля. Видео под списком ставим
    // на паузу, а после закрытия снова разрешаем показ.
    fun setHistoryDrawerVisible(visible: Boolean) {
        if (visible) {
            input.clearFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(input.windowToken, 0)
        }
        timeline.setMovingMediaVisible(!visible)
    }

    // Перед уходом закрываем камеру и клавиатуру, останавливаем ленту и сохраняем позицию. После
    // снятия экрана правильное место чтения уже нельзя вычислить по видимой области.
    fun prepareForNavigation() {
        stopCapture()
        input.clearFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
        timeline.pauseForNavigation()
        captureAnchor()
    }

    // Просим владельца вложений остановить съёмку, если она ещё идёт. Закрытая переписка не
    // должна продолжать держать камеру.
    fun stopCapture() {
        attachments?.stopCapture()
    }

    // Снимаем текущую позицию из ленты и оставляем её в состоянии чата. Это значение используется
    // при восстановлении после возврата.
    fun captureAnchor() {
        state?.anchor = timeline.readingAnchor()
    }

    // Выводим подсказку о повторе, когда сохранить запрос не удалось. Человек видит, что нажатие
    // можно повторить без нового набора текста.
    fun showError(error: Throwable) {
        android.widget.Toast.makeText(
                context,
                "Не удалось сохранить. Повторите отправку.",
                android.widget.Toast.LENGTH_LONG,
            )
            .show()
    }

    // При изменении верхнего или нижнего системного отступа заново раскладываем экран. При
    // прежних значениях не тратим время на повторное измерение.
    override fun updateInsets(top: Int, bottom: Int) {
        if (this.top != top || this.bottom != bottom) {
            this.top = top
            this.bottom = bottom
            requestLayout()
            invalidate()
        }
    }

    // Даём полю высоту строки с запасом под системный шрифт и оставляем минимальную область для
    // набора. При крупном шрифте текст поэтому остаётся в пределах поля.
    private fun inputHeight(): Int {
        val fm = input.paint.fontMetrics
        return maxOf(
            (48 * composerUnit).toInt(),
            kotlin.math.ceil((fm.bottom - fm.top + 8 * composerUnit).toDouble()).toInt(),
        )
    }

    // Измеряем историю на весь экран, а поле и плашку ошибки с учётом ширины, шрифта и отступов.
    // Текст ошибки может занять несколько строк, поэтому высоту считаем после переноса.
    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h))
        val unit = composerUnit
        val inputSize =
            android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP,
                16 * unit / d,
                resources.displayMetrics,
            )
        if (kotlin.math.abs(input.textSize - inputSize) > .01f)
            input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, inputSize)
        val editorWidth = measuredWidth - 54 * unit
        timeline.measure(
            MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY),
        )
        input.measure(
            MeasureSpec.makeMeasureSpec(maxOf(60 * d, editorWidth).toInt(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(inputHeight(), MeasureSpec.EXACTLY),
        )
        studioFeedback?.let { feedback ->
            val inset = feedbackInsets()
            val padding = (24 * d).toInt()
            val lineHeight = (22 * d).toInt()
            val lines =
                StaticLayout.Builder.obtain(
                        feedback.text,
                        0,
                        feedback.text.length,
                        feedback.paint,
                        maxOf(1, measuredWidth - 2 * padding),
                    )
                    .setIncludePad(false)
                    .build()
                    .lineCount
            val vertical = (14 * d).toInt()
            feedback.setPadding(padding, vertical, padding, vertical + inset.second)
            feedback.measure(
                MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(
                    2 * vertical + lines * lineHeight + inset.second,
                    MeasureSpec.EXACTLY,
                ),
            )
        }
    }

    // Размещаем историю, заголовок и панель ввода с учётом клавиатуры, файла и полосы передачи.
    // Здесь же обновляем прямоугольники кнопок для касаний и озвучивания, чтобы видимое и
    // доступное расположение совпадало.
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val fieldHeight = input.measuredHeight
        val unit = composerUnit
        val paneHeight = fieldHeight + 62 * unit
        header.set(0f, top.toFloat(), width.toFloat(), top + 57 * d)
        composer.set(
            14 * unit,
            height - bottom - keyboardHeight - 8 * d - paneHeight,
            width - 14 * unit,
            height - bottom - keyboardHeight - 8 * d,
        )
        val referenceVisible =
            services?.network != null && state?.draft?.attachmentDrafts?.isNotEmpty() == true
        val referenceHeight = if (referenceVisible) 48 * d else 0f
        if (referenceVisible)
            referenceChip.set(
                composer.left,
                composer.top - referenceHeight,
                composer.left + 107.46f * d,
                composer.top,
            )
        else referenceChip.setEmpty()
        val progress = transferProgress
        val transferring = progress != null
        val progressY = composer.top - referenceHeight - 12 * d
        if (transferring)
            studioUploadBar.set(composer.left, progressY, composer.right, progressY + 4 * d)
        else studioUploadBar.setEmpty()
        body.set(
            0f,
            header.bottom,
            width.toFloat(),
            composer.top - referenceHeight - (if (transferring) 40 else 8) * d,
        )
        timeline.layout(0, 0, width, height)
        timeline.updateOverlayInsets(
            (header.bottom + 6 * s).toDouble(),
            (height - body.bottom).toDouble(),
        )
        val rowY = composer.bottom - 33 * unit
        val plusX = composer.left + 34 * unit
        val sendX = composer.right - 35 * unit
        val settingsX = sendX - 58 * unit
        modelFrame.set(
            composer.left + 59 * unit,
            rowY - 20 * unit,
            settingsX - 28 * unit,
            rowY + 20 * unit,
        )
        val inputX = composer.left + 13 * unit
        val inputY = composer.top + 3 * unit
        input.layout(
            inputX.toInt(),
            inputY.toInt(),
            (inputX + input.measuredWidth).toInt(),
            (inputY + fieldHeight).toInt(),
        )
        val cardTop = maxOf(body.top + 12 * d, body.centerY() - 105 * d)
        card.set(32 * d, cardTop, width - 32 * d, cardTop + 210 * d)

        nodes.clear()
        val ready = state == null || state.isReady && historyReady && !isStartingThread
        val headerY = header.top + 28 * d
        placeControl(backControl, 28 * d, headerY, back != null && !isStartingThread)
        placeControl(newChatControl, width - 32 * d, headerY, !isStartingThread)
        placeControl(attachmentControl, plusX, rowY, ready && attachments?.canPick != false)
        modelControl.rect.set(modelFrame)
        modelControl.enabled = ready
        nodes.add(modelControl)
        placeControl(settingsControl, settingsX, rowY, ready)

        if (progress != null) {
            uploadStatus.label = "$transferTitle: ${(progress * 100).toInt()}%"
            uploadStatus.rect.set(studioUploadBar)
            uploadStatus.rect.top -= 20 * d
            nodes.add(uploadStatus)
        }
        if (referenceVisible) {
            val source = state?.draft?.attachmentDrafts?.firstOrNull()
            removeReferenceControl.label =
                if (source?.mime == "video/mp4") "Удалить исходное видео"
                else "Удалить исходное фото"
            removeReferenceControl.rect.set(
                referenceChip.left + 70 * d,
                referenceChip.top,
                referenceChip.left + 106 * d,
                referenceChip.bottom,
            )
            removeReferenceControl.enabled = ready && state?.isSending != true
            nodes.add(removeReferenceControl)
        }
        val stopping = isStoppingGeneration
        val canSend = services?.network != null || canSubmitDraft()
        sendControl.label = if (stopping) "Остановить генерацию" else "Начать генерацию"
        placeControl(
            sendControl, sendX, rowY,
            ready && (stopping || canSend && state?.isSending != true && attachments?.isBusy != true),
        )
        if (rowCount == 0 && historyReady) {
            welcomeTitle.rect.set(card.left, card.top + 56 * d, card.right, card.top + 95 * d)
            nodes.add(welcomeTitle)
            welcomeControl.rect.set(
                card.centerX() - 114 * d, card.top + 163 * d,
                card.centerX() + 114 * d, card.top + 211 * d,
            )
            welcomeControl.enabled = ready
            nodes.add(welcomeControl)
        }
        chatTitle.rect.set(
            width / 2 - 80 * d, header.top + 8 * d,
            width / 2 + 80 * d, header.bottom - 8 * d,
        )
        nodes.add(chatTitle)
        studioFeedback?.let { feedback ->
            val edge = height - feedbackInsets().first
            feedback.layout(0, edge - feedback.measuredHeight, width, edge)
            attachmentErrorStatus.label = feedback.text.toString()
            attachmentErrorStatus.rect.set(0f, feedback.top.toFloat(), width.toFloat(), edge.toFloat())
            nodes.add(attachmentErrorStatus)
        }
        ax.notifyChanged()
        invalidate()
    }

    // Ставим область нажатия вокруг центра нарисованной кнопки. Край кнопки файла ограничиваем
    // соседним выбором модели, иначе две области перекрылись бы сильнее.
    private fun placeControl(control: NativeAction, x: Float, y: Float, enabled: Boolean) {
        val hit = 24 * d
        val right = if (control === attachmentControl) minOf(x + hit, modelFrame.left) else x + hit
        control.rect.set(x - hit, y - hit, right, y + hit)
        control.enabled = enabled
        nodes.add(control)
    }

    // Открываем выбор файла только для готового черновика без незаконченной отправки. Перед
    // выбором скрываем клавиатуру, а сам файл принимает владелец вложений.
    private fun addAttachment() {
        if (
            state == null || services == null || !state.isReady || state.isSending ||
                state.draft.pendingSend != null || state.draft.pendingMediaBatch != null
        ) return
        input.clearFocus()
        context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            .hideSoftInputFromWindow(input.windowToken, 0)
        if (services.network != null) attachments?.showPicker()
    }

    // Если есть активное задание, передаём запрос на остановку. Иначе завершаем набор, проверяем
    // содержимое и отправляем запрос через существующий либо ещё создаваемый чат.
    private fun submitOrStopGeneration() {
        val active = activeGenerationID
        if (active != null) {
            actionsUI?.performGenerationAction(
                active,
                dev.kirill.kandinsky.chat.domain.StudioGenerationAction.CANCEL,
            )
            return
        }
        ComposerInputBridge.finish(input)
        if (!canSubmitDraft()) return
        val firstSend = onFirstSend
        if (firstSend != null) firstSend(options)
        else state?.send(options) { it.exceptionOrNull()?.let(::showError) }
    }

    // Переносим пример с магнолией в черновик. Он остаётся доступным для редактирования до
    // первого нажатия отправки.
    private fun fillWelcomePrompt() {
        val state = state ?: return
        state.editDraft(
            state.draft.copy(
                text = "Белая магнолия у окна, мягкий дневной свет, минималистичная фотография",
            )
        )
    }

    // Возвращаем системе список озвучиваемых нарисованных кнопок. Без него в дереве остались бы
    // только настоящие дочерние поля.
    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = ax

    // Забираем начало касания над панелями и на приглашении пустого чата. Клавиатурное поле и
    // область истории сохраняют свои касания для ввода и прокрутки.
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_DOWN) return false
        val insideInput = e.x >= input.left && e.x < input.right &&
            e.y >= input.top && e.y < input.bottom
        return (header.contains(e.x, e.y) || e.y >= body.bottom) && !insideInput ||
            welcomeControl in nodes && welcomeControl.enabled && welcomeControl.rect.contains(e.x, e.y)
    }

    // После отпускания ищем ближайшую активную кнопку под пальцем. Когда области соседних кнопок
    // соприкасаются, расстояние до центра даёт однозначный выбор.
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            var nearest: NativeAction? = null
            var nearestDistance = Float.POSITIVE_INFINITY
            for (control in nodes) {
                if (!control.enabled || !control.button || !control.rect.contains(e.x, e.y)) continue
                val dx = e.x - control.rect.centerX()
                val dy = e.y - control.rect.centerY()
                val distance = dx * dx + dy * dy
                if (distance < nearestDistance) {
                    nearest = control
                    nearestDistance = distance
                }
            }
            if (nearest != null) {
                pendingClick = nearest
                performClick()
            }
        }
        return true
    }

    // Забираем отложенное нажатие один раз и выполняем назначенное действие после системного
    // вызова. Так кнопка сохраняет обычное поведение нажатия и не повторяет старую команду.
    override fun performClick(): Boolean {
        val action = pendingClick?.action
        pendingClick = null
        val handledByView = super.performClick()
        action?.invoke()
        return handledByView || action != null
    }

    // Разрешаем отправку текста, вложенного файла и уже сохранённой попытки. Пустой новый текст
    // сам по себе не даёт запроса, но ожидающий повтор должен остаться доступным.
    private fun canSubmitDraft(): Boolean {
        val draft = state?.draft ?: return false
        return draft.pendingSend != null ||
            draft.pendingMediaBatch != null ||
            draft.text.isNotBlank() ||
            draft.attachmentDrafts.isNotEmpty()
    }

    // Обновляем плашку с причиной ошибки, озвучиваем её и ставим новый срок закрытия. Снятие
    // старого срока позволяет новой ошибке остаться видимой полные четыре секунды.
    private fun attachmentError(message: String) {
        if (!isAttachedToWindow) return
        removeCallbacks(clearStudioFeedback)
        studioFeedback?.let(::removeView)
        val feedback =
            TextView(context).apply {
                text = message
                textSize = 15f
                includeFontPadding = false
                setLineHeight((22 * d).toInt())
                setTextColor(Color.rgb(236, 242, 237))
                setBackgroundColor(Color.rgb(44, 50, 47))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        studioFeedback = feedback
        addView(feedback)
        requestLayout()
        announceForAccessibility(message)
        postDelayed(clearStudioFeedback, 4000)
    }

    // Различаем клавиатуру и нижнюю полосу системы на разных версиях андроида. Плашка стоит над
    // клавиатурой или оставляет место для системной полосы, чтобы подпись оставалась читаемой.
    private fun feedbackInsets(): Pair<Int, Int> {
        val insets = rootWindowInsets
        val safe =
            if (Build.VERSION.SDK_INT >= 30) {
                insets?.getInsets(WindowInsets.Type.navigationBars())?.bottom ?: 0
            } else {
                @Suppress("DEPRECATION")
                insets?.stableInsetBottom ?: 0
            }
        val keyboard = maxOf(keyboardHeight, if (bottom > safe) bottom else 0)
        return keyboard to if (keyboard > 0) 0 else safe
    }

    // Показываем обычные модели и записываем новый выбор с прежними общими настройками. Исходный
    // файл удаляем из черновика, если выбранный режим его больше не принимает.
    private fun chooseGenerationOptions() = withModels { models ->
        StudioGenerationSheet(context, options).showModels(models.filter { it.id != "video_sr" }) {
            model ->
            val source = state?.draft?.attachmentDrafts?.firstOrNull()
            if (source != null && (model.id != "video" || source.mime == "video/mp4"))
                attachments?.replacePhoto(null)
            options =
                KandinskyOptions(
                    model = model.id,
                    aspect = options.aspect,
                    count = options.count,
                    seed = options.seed,
                )
            modelName = model.name
            invalidate()
        }
    }

    // Берём ограничения модели для текущего вида запроса, с файлом или без. Возвращаем выбранные
    // параметры экрану, чтобы число результатов и подпись сразу совпадали с новым запросом.
    private fun showGenerationSettings() = withModels { models ->
        val model = models.firstOrNull { it.id == options.model } ?: return@withModels
        val hasReference = state?.draft?.attachmentDrafts?.isNotEmpty() == true
        val available =
            (if (hasReference) model.referenceCapabilities else null)
                ?: model.capabilities
                ?: return@withModels
        StudioGenerationSheet(context, options).showSettings(
            available,
            onCountChange = { count ->
                options = options.copy(count = count)
                invalidate()
            },
        ) {
            options = it
            invalidate()
        }
    }

    // Убираем клавиатуру и показываем уже загруженный список либо ждём один запрос. Ответ
    // использует только экран, который ещё находится в окне, иначе запоздавший список появился бы
    // после ухода из чата.
    private fun withModels(present: (List<StudioModel>) -> Unit) {
        if (loadingModels) return
        input.clearFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
        modelCatalog?.let {
            present(it)
            return
        }
        val sync = services?.network ?: return
        loadingModels = true
        sync.studioModels { result ->
            loadingModels = false
            if (!isAttachedToWindow) return@studioModels
            val models = result.getOrNull()
            if (models.isNullOrEmpty()) {
                android.widget.Toast.makeText(
                        context,
                        "Не удалось загрузить модели",
                        android.widget.Toast.LENGTH_LONG,
                    )
                    .show()
            } else {
                modelCatalog = models
                present(models)
            }
        }
    }

    // Рисуем фон истории и приглашение на пустой переписке. Пример становится видимым после
    // загрузки истории, когда известно, что она действительно пуста.
    override fun onDraw(c: Canvas) {
        // Фон студии непрозрачный, оставшиеся полосы рисует отдельное оформление
        if (services?.network == null) c.drawColor(Color.WHITE)
        wallpaper.render(c, body, s)
        if (rowCount == 0) {
            KandinskyPalette.studioBrand(c, card.centerX(), card.top, d, large = true)
            KandinskyPalette.text(
                c,
                "Что создадим?",
                card.centerX(),
                card.top + 56 * d,
                30 * d,
                KandinskyPalette.studioInk,
                bold = true,
                center = true,
            )
            KandinskyPalette.text(
                c,
                "Опишите кадр или добавьте фото",
                card.centerX(),
                card.top + 107 * d,
                17 * d,
                KandinskyPalette.studioMuted,
                center = true,
            )
            if (historyReady)
                KandinskyPalette.text(
                    c,
                    "Попробовать с магнолией",
                    card.centerX(),
                    card.top + 181 * d,
                    13 * d,
                    KandinskyPalette.studioInk,
                    center = true,
                )
        }
    }

    // Обрезаем историю по видимой области и поверх неё рисуем непрозрачные панели, кнопки и ход
    // передачи. Поле ввода и плашка идут последними, иначе их мог бы закрыть фон.
    override fun dispatchDraw(c: Canvas) {
        if (state != null) {
            if (services?.network == null) drawChild(c, timeline, drawingTime)
            else if (!body.isEmpty) {
                // Сплошные заголовок и область ввода закрывают всё за пределами истории
                c.withClip(body) {
                    drawChild(c, timeline, drawingTime)
                }
            }
        }
        paint.color = Color.WHITE
        c.drawRect(0f, 0f, width.toFloat(), header.bottom, paint)
        c.drawRect(0f, body.bottom, width.toFloat(), height.toFloat(), paint)
        paint.color = KandinskyPalette.studioBorder
        c.drawRect(0f, header.bottom - d, width.toFloat(), header.bottom, paint)
        val unit = composerUnit
        c.drawRoundRect(composer, 18 * unit, 18 * unit, paint)
        paint.color = KandinskyPalette.studioSurface
        val surface = RectF(composer).apply { inset(unit, unit) }
        c.drawRoundRect(surface, 17 * unit, 17 * unit, paint)
        drawSharpChrome(c)
        if (!studioUploadBar.isEmpty) {
            paint.color = KandinskyPalette.studioInk
            paint.textSize = 12 * d
            c.drawText(
                "$transferTitle: ${((transferProgress ?: 0.0) * 100).toInt()}%",
                studioUploadBar.left,
                studioUploadBar.top - 6 * d,
                paint,
            )
            paint.color = Color.rgb(206, 233, 219)
            c.drawRect(studioUploadBar, paint)
            paint.color = Color.rgb(56, 227, 178)
            c.drawRect(
                studioUploadBar.left,
                studioUploadBar.top,
                studioUploadBar.left +
                    studioUploadBar.width() * (transferProgress ?: 0.0).toFloat(),
                studioUploadBar.bottom,
                paint,
            )
        }
        if (!referenceChip.isEmpty) drawReferenceChip(c)
        drawChild(c, input, drawingTime)
        studioFeedback?.let { drawChild(c, it, drawingTime) }
    }

    // Рисуем вид исходного файла и крестик в пределах рассчитанного ярлыка. Крестик совпадает с
    // отдельной областью удаления, а обычная подпись не меняет черновик.
    private fun drawReferenceChip(c: Canvas) {
        val x = referenceChip.left
        val y = referenceChip.top
        val outline =
            RectF(
                x + .5f * d,
                y + 5.5f * d,
                referenceChip.right - .5f * d,
                referenceChip.bottom - 5.5f * d,
            )
        paint.color = Color.WHITE
        c.drawRoundRect(outline, 8 * d, 8 * d, paint)
        paint.color = Color.rgb(191, 201, 195)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = d
        c.drawRoundRect(outline, 8 * d, 8 * d, paint)
        paint.style = Paint.Style.FILL
        val isVideo = state?.draft?.attachmentDrafts?.firstOrNull()?.mime == "video/mp4"
        if (!isVideo)
            referenceIcon?.let {
                c.drawBitmap(it, null, RectF(x + 11 * d, y + 16 * d, x + 27 * d, y + 32 * d), paint)
            }
        val ink = Color.rgb(64, 73, 68)
        KandinskyPalette.text(
            c,
            if (isVideo) "Видео" else "Фото",
            x + (if (isVideo) 19 else 37) * d,
            y + 16 * d,
            14 * d,
            ink,
        )
        paint.color = ink
        paint.strokeWidth = 1.5f * d
        c.drawLine(x + 85 * d, y + 19.5f * d, x + 94 * d, y + 28.5f * d, paint)
        c.drawLine(x + 94 * d, y + 19.5f * d, x + 85 * d, y + 28.5f * d, paint)
    }

    // Рисуем значок приложения и кнопки в рассчитанных точках, выбирая отправку или остановку по
    // состоянию задания. Длинное имя модели укорачиваем, чтобы рядом осталось место для стрелки.
    private fun drawSharpChrome(c: Canvas) {

        KandinskyPalette.studioBrand(c, header.centerX(), header.top + 16 * d, d)
        val controls =
            mapOf(
                "chat.back" to IconID.MENU,
                "chat.compose" to IconID.COMPOSE,
                "composer.plus" to IconID.PLUS,
                "composer.settings" to IconID.TUNE,
                "composer.send" to if (isStoppingGeneration) IconID.STOP else IconID.SEND,
            )
        val unit = composerUnit
        for ((id, icon) in controls) {
            val node = nodes.firstOrNull { it.id == id } ?: continue
            val x = if (id == "composer.plus") composer.left + 34 * unit else node.rect.centerX()
            val y = node.rect.centerY()
            val solid = id == "composer.send"
            if (solid) {
                paint.color = KandinskyPalette.studioInk
                c.drawCircle(x, y, 21 * unit, paint)
            }
            if (id == "composer.plus") {
                drawComposerBorder(
                    c,
                    RectF(x - 21 * unit, y - 18 * unit, x + 21 * unit, y + 18 * unit),
                )
            } else if (id == "composer.settings") {
                paint.color = KandinskyPalette.studioMuted
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = unit
                c.drawCircle(x, y, 20.5f * unit, paint)
                paint.style = Paint.Style.FILL
            }
            c.withSave {
                val half =
                    when (id) {
                        "chat.back",
                        "chat.compose" -> 12 * d
                        "composer.plus" -> 12.5f * unit
                        "composer.settings" -> 10.5f * unit
                        "composer.send" -> 11 * unit
                        else -> 11 * d
                    }
                KandinskyIconPaths.draw(
                    c,
                    icon,
                    RectF(x - half, y - half, x + half, y + half),
                    if (solid) Color.WHITE else KandinskyPalette.studioInk,
                )
            }
        }
        if (!modelFrame.isEmpty) {
            drawComposerBorder(c, modelFrame)
            modelTextPaint.color = KandinskyPalette.studioInk
            modelTextPaint.textSize = 13 * unit
            val count = options.count ?: 1
            val label = if (count > 1) "$modelName · $count" else modelName
            val text =
                android.text.TextUtils.ellipsize(
                        label,
                        modelTextPaint,
                        (modelFrame.width() - 33 * unit).coerceAtLeast(0f),
                        android.text.TextUtils.TruncateAt.END,
                    )
                    .toString()
            val textWidth = modelTextPaint.measureText(text)
            val x = modelFrame.centerX() - (textWidth + 17 * unit) / 2
            val y = modelFrame.centerY()
            c.drawText(
                text,
                x,
                y - (modelTextPaint.ascent() + modelTextPaint.descent()) / 2,
                modelTextPaint,
            )
            modelChevronFrame.set(
                x + textWidth + 4 * unit,
                y - 6.5f * unit,
                x + textWidth + 17 * unit,
                y + 6.5f * unit,
            )
            KandinskyIconPaths.draw(
                c,
                IconID.CHEVRON_DOWN,
                modelChevronFrame,
                KandinskyPalette.studioInk,
                2.4f,
            )
        }
    }

    // Сдвигаем линию внутрь прямоугольника на половину толщины. Тогда обводка целиком входит в
    // рассчитанный размер и не выступает в соседнюю кнопку.
    private fun drawComposerBorder(c: Canvas, frame: RectF) {
        paint.color = Color.rgb(226, 227, 223)
        paint.style = Paint.Style.STROKE
        val unit = composerUnit
        paint.strokeWidth = unit
        c.drawRoundRect(
            frame.left + unit / 2,
            frame.top + unit / 2,
            frame.right - unit / 2,
            frame.bottom - unit / 2,
            9.5f * unit,
            9.5f * unit,
            paint,
        )
        paint.style = Paint.Style.FILL
    }
}
