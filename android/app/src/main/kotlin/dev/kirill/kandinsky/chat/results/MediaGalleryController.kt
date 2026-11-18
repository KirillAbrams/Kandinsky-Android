// Полноэкранная галерея на андроиде открывает выбранное вложение и держит две ближайшие страницы.
// При свайпе она согласует жест, соседнюю порцию истории и общее воспроизведение. Подготовленный
// кадр остаётся на странице, пока проигрыватель получает первый кадр или переходит к другому
// ролику.

package dev.kirill.kandinsky.chat.results

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import androidx.core.view.isVisible
import dev.kirill.kandinsky.chat.R
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.media.InlinePlayerSurface
import dev.kirill.kandinsky.chat.media.MediaCoordinator
import dev.kirill.kandinsky.chat.media.MediaNavigationDrawable
import dev.kirill.kandinsky.chat.media.MediaStore
import dev.kirill.kandinsky.chat.media.PlaybackTarget
import dev.kirill.kandinsky.chat.media.PreparedImage
import dev.kirill.kandinsky.chat.media.VideoPlaybackKey
import dev.kirill.kandinsky.chat.media.ThumbnailKey
import dev.kirill.kandinsky.chat.media.prepareOpaqueMedia
import dev.kirill.kandinsky.chat.storage.ChatRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

class MediaGalleryController(
    private val activity: Activity,
    seed: Message,
    private val repository: ChatRepository,
    private val media: MediaStore,
    private val playback: MediaCoordinator,
    private val surface: InlinePlayerSurface,
    private var seedPreview: PreparedImage? = null,
    attachmentIndex: Int = 0,
    private val onReturn: () -> Unit,
) {
    var onDeleteResult: ((MessageID, String) -> Unit)? = null

    private class Page(
        var entry: MediaGalleryEntry,
        val photo: PhotoViewerController,
        val view: View,
    ) {
        var slot = 1
    }

    private val model = MediaGalleryWindow(seed, attachmentIndex)
    private val seedID = model.selected?.id
    private val d = activity.resources.displayMetrics.density
    private val owner = Any()
    private val previousOwner = playback.presentationOwner
    private val galleryPreloads = mutableMapOf<String, Cancellation>()
    private val pages = linkedMapOf<String, Page>()
    private val cancelled = mutableSetOf<String>()
    private var dialog: Dialog? = null
    private var request: Cancellation? = null
    private var pendingPage: HistoryPage? = null
    private var lastRequestID: String? = null
    private var observer: UUID? = null
    private var activeVideo: MediaGalleryEntry? = null
    private var parkedVideo: MediaGalleryEntry? = null
    private var lastSelectionID: String? = null
    private var playingDuringSwipe = false
    private var preparingLocalVideo = false
    private val studioPlayback
        // Различаем просмотр результата генерации и обычного вложения по наличию действия
        // удаления. Для результата применяем режим повтора и отдельное поведение восстановления
        // после паузы.
        get() = onDeleteResult != null

    private var closing = false
    private var cleaned = false
    private var appeared = false
    private var startingVideo = false
    private var controlsHidden = false
    private val root =
        FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            clipChildren = true
        }
    private val pager = Pager()
    private val title =
        TextView(activity).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        }
    private val caption =
        TextView(activity).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            maxHeight = (180 * d).toInt()
            setPadding(0, 0, 0, 0)
            movementMethod = android.text.method.ScrollingMovementMethod()
            isVerticalScrollBarEnabled = false
            setTextIsSelectable(true)
        }
    private val topShade =
        View(activity).apply {
            background =
                GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0x99000000.toInt(), Color.TRANSPARENT),
                )
        }
    private val bottomShade =
        View(activity).apply {
            background =
                GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.TRANSPARENT, 0xC7000000.toInt()),
                )
        }
    private val closeButton =
        ImageButton(activity).apply {
            setImageDrawable(MediaNavigationDrawable())
            setPadding(0, 0, 0, 0)
            setColorFilter(Color.WHITE)
            background = null
            setOnClickListener { close() }
        }
    private val moreButton =
        TextView(activity).apply {
            text = "⋯"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = "Действия с результатом"
            setOnClickListener { confirmResultRemoval() }
        }
    private val retry =
        ImageButton(activity).apply {
            setImageResource(android.R.drawable.stat_sys_download)
            setColorFilter(Color.WHITE)
            background =
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x66000000)
                }
            visibility = View.GONE
            contentDescription = "Повторить загрузку"
            setOnClickListener { retryVideo() }
        }
    private val date = SimpleDateFormat("d MMMM, HH:mm", Locale("ru", "RU"))
    private var topInset = 0
    private var bottomInset = 0

    // Даём видимому элементу постоянное имя. Средства доступности и проверки могут найти страницу
    // или кнопку без поиска по её месту на экране.
    private fun id(view: View, name: String) {
        view.accessibilityDelegate =
            object : View.AccessibilityDelegate() {
                // После заполнения обычных свойств добавляем переданное имя элемента. Системные
                // сведения о содержимом при этом сохраняются.
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfo,
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.viewIdResourceName = name
                }
            }
    }

    // Один раз собираем галерею, подключаем проигрыватель и показываем окно поверх переписки.
    // Полноэкранная раскладка получает отступы системы, а после показа включаем выбранную
    // страницу и читаем соседей.
    fun show() {
        if (dialog != null || closing) return
        id(root, "media.viewer")
        id(pager, "viewer.pages")
        id(title, "viewer.item")
        id(caption, "viewer.caption")
        id(retry, "media.retry")
        id(moreButton, "viewer.result.actions")
        root.addView(pager, FrameLayout.LayoutParams(-1, -1))
        root.addView(topShade, FrameLayout.LayoutParams(-1, (100 * d).toInt(), Gravity.TOP))
        root.addView(bottomShade, FrameLayout.LayoutParams(-1, (212 * d).toInt(), Gravity.BOTTOM))
        root.addView(
            title,
            FrameLayout.LayoutParams(-1, (32 * d).toInt(), Gravity.TOP).apply {
                leftMargin = (16 * d).toInt()
                rightMargin = (84 * d).toInt()
            },
        )
        root.addView(
            caption,
            FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                leftMargin = (16 * d).toInt()
                rightMargin = (16 * d).toInt()
            },
        )
        root.addView(
            closeButton,
            FrameLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt(), Gravity.TOP or Gravity.END)
                .apply { rightMargin = (12 * d).toInt() },
        )
        root.addView(
            moreButton,
            FrameLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt(), Gravity.TOP or Gravity.END)
                .apply { rightMargin = (60 * d).toInt() },
        )
        root.addView(
            retry,
            FrameLayout.LayoutParams((48 * d).toInt(), (48 * d).toInt(), Gravity.CENTER),
        )
        val next =
            object : Dialog(activity, android.R.style.Theme_Material_NoActionBar_Fullscreen) {
                // Направляем закрытие собственного окна через общую уборку. Если владелец уже
                // отвязал окно, используем обычное системное закрытие и избегаем повторного
                // вызова.
                override fun dismiss() {
                    if (dialog === this) close() else super.dismiss()
                }
            }
        dialog = next
        next.setOnDismissListener {
            if (dialog === next) {
                dialog = null
                finishClose(true)
            }
        }
        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION") val top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION") val bottom = insets.systemWindowInsetBottom
            topInset = top
            bottomInset = bottom
            layoutControls()
            insets
        }
        observer = playback.observeChanges { playerChanged() }
        reconcile()
        next.window?.prepareOpaqueMedia()
        next.setContentView(root)
        next.show()
        next.window?.setLayout(-1, -1)
        root.requestApplyInsets()
        appeared = true
        activate()
        loadNeighbours()
    }

    // Оставляем отступы под системные полосы, а для видео добавляем место под управление.
    // Заголовок сжимается, если рядом открывается действие результата.
    private fun layoutControls() {
        (closeButton.layoutParams as FrameLayout.LayoutParams)
            .apply { topMargin = topInset + (4 * d).toInt() }
            .also { closeButton.layoutParams = it }
        (moreButton.layoutParams as FrameLayout.LayoutParams)
            .apply { topMargin = topInset + (4 * d).toInt() }
            .also { moreButton.layoutParams = it }
        (title.layoutParams as FrameLayout.LayoutParams)
            .apply {
                topMargin = topInset + (10 * d).toInt()
                rightMargin = ((if (moreButton.isVisible) 118 else 84) * d).toInt()
            }
            .also { title.layoutParams = it }
        val bottom = bottomInset + ((if (model.selected?.isVideo == true) 68 else 24) * d).toInt()
        (caption.layoutParams as FrameLayout.LayoutParams)
            .apply { bottomMargin = bottom }
            .also { caption.layoutParams = it }
        (bottomShade.layoutParams as FrameLayout.LayoutParams)
            .apply { bottomMargin = maxOf(0, bottom - (8 * d).toInt()) }
            .also { bottomShade.layoutParams = it }
        (topShade.layoutParams as FrameLayout.LayoutParams)
            .apply { height = topInset + (70 * d).toInt() }
            .also { topShade.layoutParams = it }
    }

    // Оставляем подходящие страницы по номеру и файлу, а ушедшие закрываем вместе с оригиналом.
    // Переносим ручную отмену загрузки на повторно открытую страницу и только после расстановки
    // активируем выбранное вложение.
    private fun reconcile() {
        if (closing) return
        val selected =
            model.selected
                ?: run {
                    close()
                    return
                }
        updateResultMenu()
        val wanted = model.neighbours
        val byID = wanted.associateBy { it.id }
        for ((key, page) in pages.toMap()) if (byID[key]?.hasSameMedia(page.entry) != true) {
            if (page.photo.downloadPausedByUser) cancelled.add(key)
            if (surface.parent === page.view) retireVideo()
            page.photo.close()
            pager.removeView(page.view)
            pages.remove(key)
        }
        for (entry in wanted) {
            val existing = pages[entry.id]
            if (existing != null) {
                existing.entry = entry
                continue
            }
            val initial = if (entry.id == seedID) seedPreview.also { seedPreview = null } else null
            val playbackKey = if (entry.isVideo) VideoPlaybackKey(entry.messageID, entry.attachment) else null
            val photo = PhotoViewerController(
                activity, entry.attachment, media, initial, embedded = true, playbackKey = playbackKey,
            )
            val view = photo.pageView()
            id(view, "viewer.page." + entry.id)
            val page = Page(entry, photo, view)
            pages[entry.id] = page
            pager.addView(view)
            photo.onRequestDismiss = { close() }
            photo.onToggleControls = { toggleControls() }
            photo.onPreviewReady = {
                if (
                    !closing &&
                        (activeVideo ?: parkedVideo)?.id == entry.id &&
                        entry.isVideo &&
                        !surface.hasVideoFrame
                )
                    surface.setPoster(pages[entry.id]?.photo?.retainedPreview())
            }
            if (entry.id in cancelled) photo.pauseOriginal()
        }
        cancelled.retainAll(model.entries.map { it.id }.toSet())
        model.selectedIndex?.let { selectedIndex ->
            for (page in pages.values) {
                val index = model.entries.indexOfFirst { it.id == page.entry.id }
                if (index >= 0) page.slot = index - selectedIndex + 1
            }
        }
        pager.relayoutAndCentre()
        caption.text = selected.caption
        caption.visibility =
            if (controlsHidden || selected.caption.isEmpty()) View.GONE else View.VISIBLE
        title.text =
            activity.getString(
                if (selected.isVideo) R.string.viewer_video_title else R.string.viewer_photo_title,
                date.format(Date(selected.savedAtMs)),
            )
        closeButton.contentDescription =
            if (selected.isVideo) "Вернуть в чат" else "Закрыть фотографию"
        id(closeButton, if (selected.isVideo) "player.inline" else "photo.close")
        layoutControls()
        activate()
        prepareGalleryNeighbours()
    }

    // Запрашиваем кадры на расстоянии двух позиций из местного хранилища. Уход за это окно
    // отменяет работу, чтобы быстрый свайп не набрал длинную очередь чтения.
    private fun prepareGalleryNeighbours() {
        val index = model.selectedIndex ?: return
        val farther = listOfNotNull(model.entries.getOrNull(index - 2), model.entries.getOrNull(index + 2))
        val ids = farther.mapTo(mutableSetOf()) { it.id }
        for (id in galleryPreloads.keys.toList()) {
            if (id !in ids) galleryPreloads.remove(id)?.cancel()
        }
        for (entry in farther) {
            if (entry.id in galleryPreloads) continue
            val key = (media.studioPhotoKey(entry.attachment) ?: ThumbnailKey(384)).copy(gallery = true)
            galleryPreloads[entry.id] = media.thumbnail(
                entry.attachment, key, 0, localOnly = true, priority = 0,
            ) { _, result -> result.getOrNull()?.close() }
        }
    }

    // Освобождаем оригиналы соседних страниц до выбора нового. Для видео проверяем, можно ли
    // продолжить отложенный ролик или перенести проигрыватель из ленты с живым кадром. Иначе
    // запускаем файл выбранной страницы.
    private fun activate() {
        if (!appeared || closing) return
        val selected = model.selected ?: return
        // Прежний оригинал освобождает ресурсы до получения памяти следующей страницей
        for (page in pages.values) if (page.entry.id != selected.id || selected.isVideo)
            page.photo.setPageActive(false)
        val previousSelection = lastSelectionID
        lastSelectionID = selected.id
        if (!selected.isVideo) {
            pages[selected.id]?.photo?.setPageActive(true)
            parkVideo(selected, previousSelection)
            retry.visibility = View.GONE
            return
        }
        val page = pages[selected.id] ?: return
        if (activeVideo?.id == selected.id && activeVideo?.hasSameMedia(selected) == true) return
        if (parkedVideo?.id == selected.id && parkedVideo?.hasSameMedia(selected) == true &&
            ownsVideo(selected) && selected.id !in cancelled
        ) {
            parkedVideo = null
            activeVideo = selected
            if (!playingDuringSwipe) playback.resume()
            playingDuringSwipe = false
            playerChanged()
            return
        }
        if (parkedVideo != null) retireVideo()
        val reuse =
            !studioPlayback &&
                playback.currentMessageID == selected.messageID &&
                playback.currentTarget?.attachment?.relativePath == selected.attachment.relativePath
        if (reuse) {
            surface.beginPresentationMove()
            (surface.parent as? ViewGroup)?.removeView(surface)
            activeVideo = null
        } else retireVideo()
        activeVideo = selected
        placeSurface(page, keepFrame = reuse && surface.hasVideoFrame)
        if (!studioPlayback && selected.id in cancelled) {
            surface.visibility = View.GONE
            retry.visibility = View.VISIBLE
            return
        }
        startingVideo = true
        if (!reuse) startVideo(selected)
        playback.setInlineVisible(false)
        surface.visibility = View.VISIBLE
        surface.update()
        surface.finishPresentationMove()
        startingVideo = false
        playerChanged()
    }

    // Проверяем, что у галереи нужное сообщение и нужный файл и что проигрыватель не завершился
    // ошибкой. Это защищает от показа кадра другого вложения из того же сообщения.
    private fun ownsVideo(entry: MediaGalleryEntry): Boolean =
        playback.presentationOwner === owner &&
            playback.currentMessageID == entry.messageID &&
            playback.currentTarget?.attachment?.relativePath == entry.attachment.relativePath &&
            playback.lastError == null

    // Ставим готовое видео на паузу при переходе к фото и сохраняем кадр. Когда проигрыватель
    // свободен, можно подготовить соседний уже просмотренный ролик из местного файла по
    // сохранённой позиции.
    private fun parkVideo(selected: MediaGalleryEntry, previousSelection: String?) {
        val previous = activeVideo
        if (previous != null) {
            if (pages[previous.id] != null && ownsVideo(previous) && surface.hasVideoFrame) {
                activeVideo = null
                parkedVideo = previous
                playback.pause()
                surface.rememberCurrentFrame()
                media.markShownInGallery(previous.attachment)
                pages[previous.id]?.photo?.showLastVideoFrame()
            } else retireVideo()
        }
        if (playingDuringSwipe) {
            playingDuringSwipe = false
            playback.pause()
        }
        if (parkedVideo != null || previousSelection == selected.id) return
        val index = model.selectedIndex ?: return
        val oldIndex = model.entries.indexOfFirst { it.id == previousSelection }
        val direction = if (oldIndex > index) -1 else 1
        for (neighbour in listOf(index + direction, index - direction)) {
            val entry = model.entries.getOrNull(neighbour) ?: continue
            if (!entry.isVideo || entry.id in cancelled) continue
            val page = pages[entry.id] ?: continue
            val key = VideoPlaybackKey(entry.messageID, entry.attachment)
            if (media.videoPlayback.position(key) <= 0) continue
            parkedVideo = entry
            placeSurface(page, keepFrame = false)
            startVideo(entry, preparing = true)
            surface.visibility = View.VISIBLE
            surface.update()
            break
        }
    }

    // Переносим поверхность проигрывателя в рамку выбранной страницы. Учитываем поворот исходного
    // файла, чтобы ширина и высота не поменялись местами при показе.
    private fun placeSurface(page: Page, keepFrame: Boolean) {
        val attachment = page.entry.attachment
        playback.presentationOwner = owner
        surface.circular(false)
        surface.aspect(
            if (attachment.orientation >= 5) attachment.rawHeight else attachment.rawWidth,
            if (attachment.orientation >= 5) attachment.rawWidth else attachment.rawHeight,
        )
        (page.view as ViewGroup).addView(surface, FrameLayout.LayoutParams(-1, -1))
        surface.setPoster(if (keepFrame) null else page.photo.retainedPreview())
    }

    // Запускаем нужный файл. Подготовленного соседа держим на паузе и читаем только с телефона, а
    // при обычном открытии допускаем загрузку.
    private fun startVideo(entry: MediaGalleryEntry, preparing: Boolean = false) {
        preparingLocalVideo = preparing
        playback.play(
            PlaybackTarget(entry.attachment, entry.messageID, entry.kind, loopPreview = studioPlayback),
            startPaused = preparing,
            localOnly = preparing,
        )
    }

    // Убираем прежний проигрыватель с экрана, сохраняя видимый кадр для страницы. Останавливаем
    // обработку только при своём владении, чтобы не прервать чужое воспроизведение.
    private fun retireVideo(stop: Boolean = true) {
        val previous = activeVideo ?: parkedVideo
        playingDuringSwipe = false
        preparingLocalVideo = false
        parkedVideo = null
        if (previous != null && surface.hasVideoFrame) media.markShownInGallery(previous.attachment)
        activeVideo = null
        surface.detachPlayer()
        if (previous != null) pages[previous.id]?.photo?.showLastVideoFrame()
        (surface.parent as? ViewGroup)?.removeView(surface)
        if (previous != null && stop && playback.presentationOwner === owner) playback.stop()
    }

    // При возврате приложения повторно активируем ролик генерации, если ссылка на страницу ещё
    // есть, а проигрыватель её уже отпустил. Если видео продолжает работать, перезапуск не нужен.
    fun resumeAutoplay() {
        if (!studioPlayback || closing || activeVideo == null || playback.currentMessageID != null)
            return
        activeVideo = null
        activate()
    }

    // Обновляем поверхность по текущему состоянию. Пока живого кадра нет, оставляем готовый
    // снимок. Если местная подготовка выбранного ролика не удалась, снова открываем его обычным
    // способом.
    private fun playerChanged() {
        if (closing) return
        val selected = activeVideo ?: parkedVideo ?: return
        surface.update()
        if (activeVideo == null) {
            surface.visibility = if (ownsVideo(selected)) View.VISIBLE else View.GONE
            return
        }
        if (preparingLocalVideo && playback.lastError != null) {
            // Свайп может закончиться до проверки файла. Тогда открываем видео обычным способом.
            retireVideo()
            activate()
            return
        }
        val stopped = !startingVideo && playback.currentMessageID == null
        val failed = playback.lastError != null || selected.id in cancelled || stopped
        retry.visibility = if (failed && !studioPlayback) View.VISIBLE else View.GONE
        surface.visibility = if (failed) View.GONE else View.VISIBLE
        if (!failed && !surface.hasVideoFrame)
            surface.setPoster(pages[selected.id]?.photo?.retainedPreview())
    }

    // Снимаем отметку об отмене загрузки для текущего видео, освобождаем прежнюю попытку и
    // запускаем её заново. Отметки остальных страниц остаются на месте.
    private fun retryVideo() {
        val selected = model.selected ?: return
        if (!selected.isVideo) return
        cancelled.remove(selected.id)
        retireVideo()
        activate()
    }

    // Показываем действие удаления только для входящего серверного файла результата с
    // обработчиком. У пользовательского исходника такого действия нет.
    private fun canDelete(entry: MediaGalleryEntry) =
        onDeleteResult != null &&
            entry.isIncoming &&
            entry.attachment.relativePath.startsWith("remote/")

    // Пересчитываем видимость меню при смене файла и общего состояния кнопок. Скрытые кнопки не
    // появляются из-за перехода к удаляемому результату.
    private fun updateResultMenu() {
        moreButton.visibility =
            if (!controlsHidden && model.selected?.let(::canDelete) == true) View.VISIBLE
            else View.GONE
    }

    // Открываем меню для выбранного результата и передаём обработчику номер сообщения вместе с
    // ключом одного файла. Отмена закрывает окно без отправки действия.
    private fun confirmResultRemoval() {
        val entry = model.selected?.takeIf(::canDelete) ?: return
        android.app.AlertDialog.Builder(activity)
            .setTitle("Действия с результатом")
            .setItems(arrayOf("Удалить результат")) { _, _ ->
                onDeleteResult?.invoke(entry.messageID, entry.attachment.thumbnailKey)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // Прячем или возвращаем заголовок, подпись и кнопки по касанию страницы. Пустую подпись с её
    // затемнением не рисуем даже при видимых кнопках.
    private fun toggleControls() {
        controlsHidden = !controlsHidden
        updateResultMenu()
        val v = if (controlsHidden) View.GONE else View.VISIBLE
        title.visibility = v
        closeButton.visibility = v
        topShade.visibility = v
        bottomShade.visibility =
            if (controlsHidden || caption.text.isEmpty()) View.GONE else View.VISIBLE
        caption.visibility =
            if (controlsHidden || caption.text.isEmpty()) View.GONE else View.VISIBLE
    }

    private var requestGeneration = 0

    // Читаем не более тридцати двух записей фото и видео вокруг выбранного места. Номер запроса
    // отсеивает поздний ответ, а при движении порция ждёт остановки, чтобы не сдвинуть страницу.
    private fun loadNeighbours() {
        val selected = model.selected ?: return
        if (closing || request != null || !model.needsPage || lastRequestID == selected.id) return
        lastRequestID = selected.id
        val generation = ++requestGeneration
        request =
            repository.history(
                PageRequest(
                    selected.chatID,
                    selected.order,
                    PageDirection.AROUND,
                    32,
                    visualOnly = true,
                )
            ) { result ->
                if (closing || generation != requestGeneration) return@history
                request = null
                result.fold(
                    { page ->
                        if (pager.busy) pendingPage = page
                        else {
                            model.merge(page)
                            reconcile()
                        }
                    },
                    { lastRequestID = null },
                )
            }
    }

    // Когда подготовленный сосед занял большую часть экрана, запускаем его с сохранённого места.
    // При движении назад останавливаем ниже другого порога, чтобы запросы пуска и паузы не
    // спорили у одной границы.
    private fun updateVideoDuringSwipe(offset: Float) {
        val entry = parkedVideo ?: return
        val slot = pages[entry.id]?.slot ?: return
        if (slot == 1 || !ownsVideo(entry) || !playback.isReady) return
        val visibleFraction = offset * (slot - 1)
        if (!playingDuringSwipe && visibleFraction >= 0.6f) {
            playingDuringSwipe = true
            playback.resume()
        } else if (playingDuringSwipe && visibleFraction < 0.5f) {
            playingDuringSwipe = false
            playback.pause()
        }
    }

    // По конечному положению выбираем страницу, применяем отложенную историю и возвращаем окно к
    // центру. Если соседей всё ещё мало, запрашиваем следующую порцию.
    private fun settled() {
        if (closing || pager.stride <= 0) return
        val slot = (pager.scrollX.toDouble() / pager.stride).roundToInt()
        pages.values.firstOrNull { it.slot == slot }?.let { model.select(it.entry.id) }
        pendingPage?.let {
            pendingPage = null
            model.merge(it)
        }
        reconcile()
        loadNeighbours()
    }

    // Новый жест может застать незавершённое движение. Закрепляем ближайшую страницу и переносим
    // смещение относительно нового центра, чтобы под пальцем остался тот же участок.
    private fun resumeGesture() {
        if (closing || pager.stride <= 0) return
        val offset = pager.scrollX
        val slot = (offset.toDouble() / pager.stride).roundToInt()
        val target = pages.values.firstOrNull { it.slot == slot } ?: return
        if (target.entry.id == model.selected?.id) return
        val shift = (slot - 1) * pager.stride
        if (!model.select(target.entry.id)) return
        pendingPage?.let {
            pendingPage = null
            model.merge(it)
        }
        reconcile()
        pager.scrollTo(offset - shift, 0)
        loadNeighbours()
    }

    // При изменении истории текущего чата сбрасываем незавершённый запрос соседей и обновляем
    // модель. Если смотреть больше нечего, закрываем окно. Во время свайпа ждём остановки для
    // новой раскладки.
    fun apply(delta: MessageDelta) {
        if (closing || delta.chat.id != model.chatID) return
        requestGeneration++
        request?.cancel()
        request = null
        pendingPage = null
        lastRequestID = null
        model.apply(delta)
        if (model.selected == null) {
            close()
            return
        }
        if (!pager.busy) {
            reconcile()
            loadNeighbours()
        }
    }

    // Помечаем галерею закрытой, останавливаем запрос и движение страниц, затем закрываем диалог.
    // Обработчик закрытия получает явный признак возврата в чат.
    fun close(returnToChat: Boolean = true) {
        if (closing) return
        closing = true
        request?.cancel()
        request = null
        pendingPage = null
        pager.cancelMotion()
        val old = dialog
        dialog = null
        old?.dismiss()
        finishClose(returnToChat)
    }

    // Освобождаем страницы, фоновые чтения и подписку ровно один раз. При возврате передаём ленте
    // готовый кадр и владение продолжающимся обычным видео. При уходе из чата отпускаем лишние
    // картинки.
    private fun finishClose(returnToChat: Boolean) {
        if (cleaned) return
        cleaned = true
        galleryPreloads.values.forEach { it.cancel() }
        galleryPreloads.clear()
        closing = true
        request?.cancel()
        request = null
        pager.cancelMotion()
        observer?.let { playback.removeChangeObserver(it) }
        observer = null
        val returning =
            returnToChat &&
                !studioPlayback &&
                model.selected?.isVideo == true &&
                playback.currentMessageID == model.selected?.messageID &&
                playback.presentationOwner === owner
        if (returning) {
            surface.rememberCurrentFrame()
            surface.beginPresentationMove()
            (surface.parent as? ViewGroup)?.removeView(surface)
            activeVideo = null
        } else retireVideo()
        surface.visibility = View.VISIBLE
        if (playback.presentationOwner === owner) playback.presentationOwner = previousOwner
        for (page in pages.values) {
            page.photo.close()
            pager.removeView(page.view)
        }
        pages.clear()
        seedPreview?.close()
        seedPreview = null
        if (returnToChat) {
            // При выходе из просмотра последний кадр снова нужен в ленте.
            media.withNavigationCache(onReturn)
        } else {
            media.releaseIdleImages()
            onReturn()
        }
        surface.finishPresentationMove()
    }

    private inner class Pager : ViewGroup(activity) {
        private val scroller = OverScroller(activity)
        private val config = ViewConfiguration.get(activity)
        private var velocity: VelocityTracker? = null
        private var dragging = false
        private var settling = false
        private var multi = false
        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var oldStride = 0
        val stride
            // Расстояние между началами страниц равно ширине области плюс зазор. По нему измеряем
            // долю жеста и ставим соседние страницы.
            get() = width + (20 * d).toInt()

        val busy
            // Считаем область занятой во время касания, инерции или смещения от центра. В этот
            // момент новый ответ хранилища не должен переставлять страницы.
            get() = dragging || !scroller.isFinished || width > 0 && abs(scrollX - stride) > 1

        // Настраиваем отсечение страниц по краям окна. Так за пределами видимой области не
        // появятся соседние кадры.
        init {
            clipChildren = true
            setWillNotDraw(false)
        }

        // Выдаём каждой удерживаемой странице точный размер окна. Ширина подписи или самого фото
        // не влияет на расстояние свайпа.
        override fun onMeasure(w: Int, h: Int) {
            setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h))
            for (page in pages.values) page.view.measure(
                MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY),
            )
        }

        // Переставляем страницы после смены размера и восстанавливаем долю смещения от прежнего
        // шага. Так поворот или пересчёт окна не перескакивает на другой кадр.
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val fraction = if (oldStride > 0) scrollX.toDouble() / oldStride else 1.0
            layoutPages()
            scrollTo((fraction * stride).roundToInt(), 0)
            oldStride = stride
        }

        // Расставляем видимые и соседние страницы по вычисленному шагу. Центр показывает текущий
        // файл, боковые места готовы к свайпу.
        private fun layoutPages() {
            for (page in pages.values) {
                val x = page.slot * stride
                page.view.layout(x, 0, x + width, height)
            }
        }

        // Заново измеряем страницы после выбора и возвращаем прокрутку в центр. До первого
        // расчёта ширины сохраняем только запрос новой раскладки.
        fun relayoutAndCentre() {
            if (width > 0) {
                for (page in pages.values) page.view.measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
                )
                layoutPages()
                scrollTo(stride, 0)
                oldStride = stride
            }
            requestLayout()
            invalidate()
        }

        // Отменяем движение и возвращаем системе измеритель скорости касания. Закрытая галерея
        // больше не должна получать продолжение свайпа.
        fun cancelMotion() {
            scroller.abortAnimation()
            dragging = false
            settling = false
            velocity?.recycle()
            velocity = null
        }

        // Ограничиваем смещение реально существующими соседями. У первого и последнего вложения
        // свайп не уходит в пустое место.
        private fun bounds(): IntRange {
            val selected = model.selectedIndex ?: 0
            return (if (selected > 0) 0 else stride)..(if (selected + 1 < model.entries.size)
                    stride * 2
                else stride)
        }

        // Начинаем новое измерение скорости и заканчиваем старое движение. Если предыдущий жест
        // не закончен, переносим остаток смещения на ближайшую страницу.
        private fun begin(e: MotionEvent) {
            downX = e.x
            downY = e.y
            lastX = e.x
            multi = false
            velocity?.recycle()
            velocity = VelocityTracker.obtain()
            velocity?.addMovement(e)
            dragging = !scroller.isFinished
            scroller.abortAnimation()
            settling = false
            resumeGesture()
        }

        // Забираем только достаточно длинный горизонтальный жест одним пальцем. Масштабирование и
        // движение увеличенного фото остаются у самой страницы.
        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    begin(e)
                    return dragging
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    multi = true
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (
                        !multi &&
                            e.pointerCount == 1 &&
                            pages[model.selected?.id]?.photo?.pageIsZoomed != true &&
                            abs(e.x - downX) > config.scaledTouchSlop &&
                            abs(e.x - downX) > abs(e.y - downY) * 1.1f
                    ) {
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) {
                        velocity?.recycle()
                        velocity = null
                    }
                }
            }
            return false
        }

        // Выполняем системное нажатие и переключаем видимость подписей и кнопок. Короткое касание
        // не превращается в перелистывание.
        override fun performClick(): Boolean {
            super.performClick()
            toggleControls()
            return true
        }

        // Двигаем окно в пределах соседей, а при отпускании выбираем цель по скорости и
        // положению. Системная отмена возвращает в центр. Как только цель ясна, продолжаем
        // короткое движение без удержания измерителя.
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (velocity == null) begin(e)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    velocity?.addMovement(e)
                    if (!dragging) {
                        val slop = config.scaledTouchSlop
                        if (multi || abs(e.x - downX) <= slop || abs(e.x - downX) <= abs(e.y - downY))
                            return true
                        dragging = true
                    }
                    val next = (scrollX + lastX - e.x).roundToInt().coerceIn(bounds())
                    scrollTo(next, 0)
                    lastX = e.x
                    return true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    multi = true
                    return true
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (!dragging && scrollX == stride) {
                        velocity?.recycle()
                        velocity = null
                        if (
                            e.actionMasked == MotionEvent.ACTION_UP && !multi &&
                                abs(e.x - downX) <= config.scaledTouchSlop &&
                                abs(e.y - downY) <= config.scaledTouchSlop
                        ) performClick()
                        return true
                    }
                    velocity?.addMovement(e)
                    velocity?.computeCurrentVelocity(
                        1000,
                        config.scaledMaximumFlingVelocity.toFloat(),
                    )
                    val speed = velocity?.xVelocity ?: 0f
                    var slot =
                        if (e.actionMasked == MotionEvent.ACTION_CANCEL) 1
                        else if (abs(speed) > config.scaledMinimumFlingVelocity) {
                            if (speed < 0) ceil(scrollX.toDouble() / stride).toInt()
                            else floor(scrollX.toDouble() / stride).toInt()
                        } else (scrollX.toDouble() / stride).roundToInt()
                    slot = slot.coerceIn(0, 2)
                    val target = (slot * stride).coerceIn(bounds())
                    dragging = false
                    velocity?.recycle()
                    velocity = null
                    scroller.startScroll(scrollX, 0, target - scrollX, 0, 180)
                    settling = true
                    postInvalidateOnAnimation()
                    return true
                }
            }
            return true
        }

        // Передаём проигрывателю долю смещения только во время жеста или его завершения.
        // Программная перестановка страниц сама по себе не начинает воспроизведение.
        override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
            super.onScrollChanged(l, t, oldl, oldt)
            if ((dragging || settling) && stride > 0) {
                updateVideoDuringSwipe(l.toFloat() / stride - 1f)
            }
        }

        // Применяем следующий шаг движения и просим перерисовку. После последнего шага один раз
        // фиксируем новую страницу и применяем отложенную историю.
        override fun computeScroll() {
            if (scroller.computeScrollOffset()) {
                scrollTo(scroller.currX, 0)
                postInvalidateOnAnimation()
            } else if (settling) {
                settling = false
                settled()
            }
        }
    }
}
