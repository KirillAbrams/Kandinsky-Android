// Показ файлов в ленте на андроиде передаёт один проигрыватель между сообщением и полноэкранным
// просмотром. Готовый кадр передаётся вместе с вложением, а движущееся содержимое
// останавливается, когда чат скрыт.

package dev.kirill.kandinsky.chat.rendering

import android.graphics.*
import android.view.*
import dev.kirill.kandinsky.chat.attachments.StudioUploadPreview
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.media.*
import dev.kirill.kandinsky.chat.results.MediaGalleryController
import dev.kirill.kandinsky.chat.results.PhotoViewerController
import kotlin.math.*

// Запоминаем выбранную ячейку на время обработки касания. Возвращаем номер по умолчанию даже при
// ошибке обработчика, чтобы следующее фото открылось на своём месте.
internal fun ChatScrollHost.activateMedia(row: Message, frame: RectF, index: Int = 0) {
    tappedMediaIndex = index
    try {
        onMessageTap?.invoke(row, frame)
    } finally {
        tappedMediaIndex = 0
    }
}

// Открываем просмотр по действию увеличения. Сохранение и отправку файла передаём владельцу
// выгрузки, который берёт оригинал и сообщает об ошибке.
internal fun ChatScrollHost.performMediaAction(action: KandinskyMediaAction, row: Message, frame: RectF) {
    if (action == KandinskyMediaAction.EXPAND) {
        activateMedia(row, frame)
        return
    }
    val media = viewerMedia ?: return
    val attachment = row.attachments.firstOrNull() ?: return
    mediaExport.start(media, attachment, action == KandinskyMediaAction.SHARE) {
        onError?.invoke(it)
    }
}

// Назначаем владельца поверхности и сохраняем ссылки на свои обработчики. По касанию открываем
// галерею или отдельное фото с готовым кадром. Чужое окно не должно получить обновление или
// закрытие от ленты.
internal fun ChatScrollHost.attachPlayback(coordinator: MediaCoordinator, media: MediaStore) {
    playback = coordinator
    coordinator.presentationOwner = playbackOwner
    viewerMedia = media
    playerSurface = InlinePlayerSurface(context, coordinator)
    coordinator.onChange = {
        if (coordinator.presentationOwner === playbackOwner) {
            playerSurface?.update()
            studioPreview?.update()
        }
        schedule()
    }
    coordinator.onDetachOutput = {
        if (coordinator.presentationOwner === playbackOwner) {
            playerSurface?.detachPlayer()
            (playerSurface?.parent as? ViewGroup)?.removeView(playerSurface)
            studioPreview?.close()
        }
    }
    ownedPlayerChange = coordinator.onChange
    ownedPlayerDetach = coordinator.onDetachOutput
    onMessageTap = { row, _ ->
        if (
            row.kind in
                listOf(
                    MessageKind.PHOTO,
                    MessageKind.VIDEO,
                    MessageKind.ROUND,
                    MessageKind.GIF,
                ) && repository != null
        )
            openMediaGallery(row, tappedMediaIndex)
        else if (!closed && photoViewer == null)
            row.attachments
                .firstOrNull()
                ?.takeIf { it.mime.startsWith("image/") }
                ?.let { a ->
                    val preview = scene.preview(row.id)
                    coordinator.stop()
                    setMediaVisible(false)
                    val viewer =
                        PhotoViewerController(
                            context as android.app.Activity,
                            a,
                            media,
                            preview,
                            row.caption,
                        )
                    photoViewer = viewer
                    viewer.onClose = {
                        if (photoViewer === viewer) {
                            photoViewer = null
                            setMediaVisible(true)
                        }
                    }
                    viewer.show()
                }
    }
}

// Передаём исходный ролик в отдельный просмотр с правильными пропорциями и повтором. При выходе
// останавливаем его, возвращаем видимость чата и затем запускаем закрытие либо улучшение.
internal fun ChatScrollHost.presentStudioPreview(
    attachment: Attachment,
    onEnhance: (() -> Unit)? = null,
    onClose: () -> Unit,
) {
    val coordinator = playback
    val surface = playerSurface
    if (coordinator == null || surface == null || studioPreview != null) {
        onClose()
        return
    }
    coordinator.presentationOwner = playbackOwner
    setMovingMediaVisible(false)
    coordinator.play(
        PlaybackTarget(attachment, -1L, kind = MessageKind.VIDEO, loopPreview = true)
    )
    surface.circular(false)
    surface.setPoster(null)
    val rotated = attachment.orientation >= 5
    surface.aspect(
        if (rotated) attachment.rawHeight else attachment.rawWidth,
        if (rotated) attachment.rawWidth else attachment.rawHeight,
    )
    val preview =
        StudioUploadPreview(
            context as android.app.Activity,
            surface,
            coordinator,
            attachment,
            onEnhance != null,
        ) { enhancing ->
            studioPreview = null
            coordinator.stop(if (enhancing && onEnhance != null) onEnhance else onClose)
            setMovingMediaVisible(true)
            setMediaVisible(true)
        }
    studioPreview = preview
    preview.show()
}

// Передаём выбранное вложение, готовый кадр и общую поверхность в галерею. Для результата
// генерации ставим на паузу движение внизу, а при возврате восстанавливаем прежнее состояние
// только для того же владельца.
private fun ChatScrollHost.openMediaGallery(row: Message, attachmentIndex: Int = 0) {
    if (mediaGallery != null || photoViewer != null) return
    val repo = repository ?: return
    val media = viewerMedia ?: return
    val coordinator = playback ?: return
    val surface = playerSurface ?: return
    val studio = onStudioResultDelete != null
    val wasMoving = scene.movingMediaVisible
    lateinit var viewer: MediaGalleryController
    viewer =
        MediaGalleryController(
            context as android.app.Activity,
            row,
            repo,
            media,
            coordinator,
            surface,
            scene.preview(row.id, attachmentIndex),
            attachmentIndex,
        ) {
            if (!closed && mediaGallery === viewer) {
                mediaGallery = null
                setMediaVisible(true)
                if (studio) setMovingMediaVisible(wasMoving)
            }
        }
    viewer.onDeleteResult = onStudioResultDelete
    mediaGallery = viewer
    if (studio) setMovingMediaVisible(false) else setMediaVisible(false)
    coordinator.setInlineVisible(false)
    if (row.kind != MessageKind.VIDEO) coordinator.stop()
    viewer.show()
}

// Ищем выбранный ролик среди видимых строк и ставим поверхность в рамку его вложения. Учитываем
// поворот картинки и круглую форму. Если строка ушла или ещё не видна, убираем проигрыватель из
// ленты.
internal fun ChatScrollHost.updateInlinePlayer() {
    if (studioPreview != null || mediaGallery != null) return
    val c = playback ?: return
    val id = c.currentMessageID ?: return
    val surface = playerSurface ?: return
    if (c.presentationOwner !== playbackOwner) return
    val row = loaded[id]
    val top = index.top(id)
    if (row == null || top == null) {
        hideInlinePlayer()
        return
    }
    val y = (top - offset).toFloat()
    if (y + (index.height(id) ?: 0.0) <= 0 || y >= height) {
        hideInlinePlayer()
        return
    }
    val prepared = cache.value(layoutKey(row)) ?: return
    val x = rowLeft(row, prepared)
    val rect =
        RectF(
                prepared.mediaRect
                    ?: RectF(
                        0f,
                        0f,
                        prepared.width,
                        if (row.kind == MessageKind.VOICE) 60 * d
                        else prepared.height.coerceIn(64 * d, 110 * d),
                    )
            )
            .apply {
                if (row.kind == MessageKind.ROUND) right = left + prepared.width
                offset(x, y)
            }
    if (rect.bottom <= 0 || rect.top >= height) {
        hideInlinePlayer()
        return
    }
    if (surface.parent !== this) {
        surface.setPoster(scene.preview(id))
        (surface.parent as? ViewGroup)?.removeView(surface)
        addView(surface)
    }
    c.setInlineVisible(true)
    row.attachments.firstOrNull()?.let { a ->
        surface.aspect(
            if (a.orientation >= 5) a.rawHeight else a.rawWidth,
            if (a.orientation >= 5) a.rawWidth else a.rawHeight,
        )
    }

    surface.circular(row.kind == MessageKind.ROUND)
    surface.measure(
        View.MeasureSpec.makeMeasureSpec(rect.width().toInt(), View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(rect.height().toInt(), View.MeasureSpec.EXACTLY),
    )
    surface.layout(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
    surface.update()
}

// Снимаем поверхность и прекращаем показ только для своего воспроизведения. Если управление
// перешло в галерею, чужой ролик продолжается.
private fun ChatScrollHost.hideInlinePlayer() {
    val c = playback ?: return
    if (c.presentationOwner !== playbackOwner) return
    playerSurface?.detachPlayer()
    (playerSurface?.parent as? ViewGroup)?.removeView(playerSurface)
    c.setInlineVisible(false)
    c.stop()
}

// Проверяем, что чат перед пользователем и не закрыт отдельным просмотром. При возврате активного
// окна разрешаем галерее восстановить её ролик, а при скрытии снимаем запрос частоты дисплея.
internal fun ChatScrollHost.updateMediaVisibility(visible: Boolean) {
    if (!visible) displayPolicy?.setActive(false)
    val active =
        !closed &&
            visible &&
            isAttachedToWindow &&
            isHostForeground()
    scene.mediaVisible =
        active && photoViewer == null && studioPreview == null && mediaGallery == null
    if (active) mediaGallery?.resumeAutoplay()
    schedule()
}

// Завершаем инерцию и снимаем состояние жеста перед уходом. Останавливаем движение файлов, чтобы
// переход не оставил работающую за экраном ленту.
internal fun ChatScrollHost.pauseMediaForNavigation() {
    scroller.abortAnimation()
    dragging = false
    setMovingMediaVisible(false)
}

// Меняем разрешение именно движущихся кадров и индикаторов с учётом открытого просмотра. Сцена
// применит это на следующем кадре.
internal fun ChatScrollHost.updateMovingMediaVisibility(visible: Boolean) {
    scene.movingMediaVisible =
        visible && studioPreview == null && mediaGallery?.onDeleteResult == null
    schedule()
}

// Передаём сцене хранилище и место для поверхностей. Изображение после подготовки просит
// пересобрать кадр, а движение также обновляет запрос к дисплею.
internal fun ChatScrollHost.attachMediaStore(store: dev.kirill.kandinsky.chat.media.MediaStore) {
    scene.mediaStore = store
    scene.movingContainer = this
    scene.onFrameChanged = {
        updateDisplayHint()
        invalidate()
    }
    scene.onImageReady = {
        schedule()
        invalidate()
    }
}
