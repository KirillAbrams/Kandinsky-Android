// Сцена истории на андроиде держит записанные команды рисования текста и подписи для каждого
// фрагмента. При прокрутке она передвигает узлы, подставляет сохранённые кадры и ограничивает
// число рабочих роликов. Закрытие возвращает файлы и рисунки их владельцам.

package dev.kirill.kandinsky.chat.rendering

import android.graphics.*
import android.os.Looper
import android.view.ViewGroup
import androidx.core.graphics.withSave
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.media.*

data class SceneFragment(val prepared: PreparedText, val part: Int, val frame: RectF) {
    val id
        // Собираем ключ из номера сообщения и номера его части. Длинное сообщение может занять
        // несколько слоёв, и у каждого свой рисунок.
        get() = FragmentID(prepared.key.messageID, part)
}

data class SceneSnapshot(val ticket: Int, val fragments: List<SceneFragment>)

data class SceneMetrics(
    var recordings: Int = 0,
    var liveFragments: Int = 0,
    var activeFrameClocks: Int = 0,
)

class MessageNode(var fragment: SceneFragment) {
    var image: MediaImageSlot? = null
    val extraImages = linkedMapOf<Int, MediaImageSlot>()

    // Закрываем основной кадр и остающиеся кадры альбома. Так ушедший фрагмент больше не
    // удерживает их в памяти.
    fun retireImages() {
        image?.close()
        image = null
        retireExtraImages()
    }

    // Освобождаем дополнительные ячейки, оставляя главный рисунок. Одиночное вложение не должно
    // сохранять старый альбом.
    fun retireExtraImages() {
        extraImages.values.forEach { it.close() }
        extraImages.clear()
    }

    var video: SilentVideoPreview? = null
    val albumVideos = linkedMapOf<String, SilentVideoPreview>()
    internal val progressViews = linkedMapOf<String, StudioProgressView>()

    // Останавливаем ролики в каждой ячейке. Для недавних видимых файлов можно оставить
    // проигрыватель на паузе в общем запасе вместо полного закрытия.
    fun retireAlbumVideos(keepingPlayers: Boolean = false) {
        albumVideos.values.forEach { if (keepingPlayers) it.setVisible(false) else it.close() }
        albumVideos.clear()
    }

    // Снимаем индикаторы с окна и очищаем соответствие ячеек. При следующем показе процент не
    // достанется другому файлу.
    fun retireProgress() {
        progressViews.values.forEach { (it.parent as? ViewGroup)?.removeView(it) }
        progressViews.clear()
    }

    // При смене состава или порядка переносим кадры по номеру вложения. Освобождаем только те,
    // которые больше не входят в карточку.
    fun preserveAlbumImages(previous: Message, current: Message) {
        if (previous.attachments.map { it.id } == current.attachments.map { it.id }) return
        val slots = mutableMapOf<String, MediaImageSlot>()
        previous.attachments.forEachIndexed { index, attachment ->
            (if (index == 0) image else extraImages[index])?.let { slots[attachment.id] = it }
        }
        image = null
        extraImages.clear()
        current.attachments.forEachIndexed { index, attachment ->
            slots.remove(attachment.id)?.let {
                if (index == 0) image = it else extraImages[index] = it
            }
        }
        slots.values.forEach { it.close() }
    }

    val native = RenderNode("Kandinsky message " + fragment.id)
    val overlay = RenderNode("Kandinsky static media stamp " + fragment.id)

    // Записываем неподвижное содержимое и отдельную подпись медиа в графические команды.
    // Завершаем обе записи даже при ошибке, чтобы узел не остался в промежуточном состоянии.
    fun record() {
        val width = native.width.coerceAtLeast(1)
        val height = native.height.coerceAtLeast(1)
        val c = native.beginRecording(width, height)
        try {
            fragment.prepared.draw(fragment.part, c)
        } finally {
            native.endRecording()
        }
        overlay.discardDisplayList()
        if (fragment.part == 0 && fragment.prepared.mediaRect != null) {
            val o = overlay.beginRecording(width, height)
            try {
                fragment.prepared.drawMediaStamp(o)
            } finally {
                overlay.endRecording()
            }
        }
    }

    // Ставим узел в нужное место, сохраняя его точный размер из разметки. Прокрутка меняет
    // положение готового рисунка, а вычитание экранных координат не меняет его высоту.
    fun position() {
        val layout = fragment.prepared
        // Размер берём до экранного сдвига, иначе погрешность вычитания меняет высоту слоя.
        val width = layout.width
        val height = layout.blocks[fragment.part].height
        native.setPosition(0, 0, kotlin.math.ceil(width).toInt(), kotlin.math.ceil(height).toInt())
        overlay.setPosition(0, 0, width.toInt(), height.toInt())

        val frame = fragment.frame
        native.translationX = frame.left
        native.translationY = frame.top
        overlay.translationX = frame.left
        overlay.translationY = frame.top
    }
}

class RetainedChatScene {
    private val nodes = linkedMapOf<FragmentID, MessageNode>()
    private val imagePrefetch = MediaImagePrefetch()
    private val videoPreviews = SilentVideoPreviewPool()
    var ticket = 1
        private set

    val metrics = SceneMetrics()
    var mediaVisible = true
        // При скрытии всех файлов отменяем соседнюю подготовку, закрываем ролики и освобождаем
        // рисунки. У невидимой ленты больше нет активных кадров и индикаторов.
        set(value) {
            field = value
            if (!value) {
                imagePrefetch.clear()
                mediaStore?.videoPlayback?.prepareCovers(emptyList())
                videoPreviews.clear()
                nodes.values.forEach {
                    it.video?.close()
                    it.video = null
                    it.retireAlbumVideos()
                    it.retireProgress()
                    it.retireImages()
                }
                metrics.activeFrameClocks = 0
                mediaStore?.releaseIdleImages()
            }
        }

    var movingMediaVisible = true
        // При скрытии только движения убираем проигрыватели и счётчики, сохраняя неподвижное
        // изображение. Под списком чатов может остаться кадр без фонового видео.
        set(value) {
            field = value
            if (!value) {
                videoPreviews.clear()
                nodes.values.forEach {
                    it.video?.close()
                    it.video = null
                    it.retireAlbumVideos()
                    it.retireProgress()
                }
                metrics.activeFrameClocks = 0
            }
        }

    var viewport = RectF()
    var imageViewport: RectF? = null
    var imagePreparationViewport: RectF? = null
    var movingContainer: ViewGroup? = null
    private var previewObserver: java.util.UUID? = null
    var mediaStore: MediaStore? = null
    var onImageReady: (() -> Unit)? = null
    var onFrameChanged: (() -> Unit)? = null
    private var closed = false

    // Проверяем, что доступ к сцене идёт из потока экрана. Готовые ресурсы из фоновой подготовки
    // должны быть переданы сюда перед изменением узлов.
    private fun main() {
        check(Looper.myLooper() == Looper.getMainLooper())
    }

    // Принимаем подготовленную строку только для текущей открытой сцены. Устаревший результат
    // закрываем, иначе новая раскладка получила бы старый размер.
    fun accept(resource: PreparedText, ticket: Int): Boolean {
        main()
        if (closed || this.ticket != ticket) {
            resource.close()
            return false
        }
        return true
    }

    // Предпочитаем кадр текущего ролика, затем сохранённый кадр, изображение строки или соседний
    // запас. Номер вложения не даёт показать кадр другой ячейки.
    fun preview(id: MessageID, attachmentIndex: Int = 0): PreparedImage? =
        nodes[FragmentID(id, 0)]?.let { node ->
            val attachment = node.fragment.prepared.message.attachments.getOrNull(attachmentIndex)
            val native = if (attachmentIndex == 0) node.video ?: node.albumVideos[attachment?.id]
                else node.albumVideos[attachment?.id]
            val captured = native?.snapshotFrame()
                ?: attachment?.let { mediaStore?.videoPlayback?.cover(VideoPlaybackKey(id, it)) }
            if (captured != null) return@let captured
            val shown = if (attachmentIndex == 0) node.image?.preview()
                else node.extraImages[attachmentIndex]?.preview()
            shown ?: mediaStore?.let { store ->
                node.fragment.prepared.message.attachments.getOrNull(attachmentIndex)?.let {
                    imagePrefetch.preview(
                        MediaImagePrefetch.Item(it, mediaThumbnailLimit(node.fragment.prepared)), store,
                    )
                }
            }
        }

    // Отдельно проверяем готовность нужной ячейки сообщения. Ошибка или ожидание одного файла не
    // означают, что другие ещё не видны.
    fun imageReady(id: MessageID, attachmentIndex: Int = 0) =
        nodes[FragmentID(id, 0)]?.let {
            if (attachmentIndex == 0) it.image?.ready else it.extraImages[attachmentIndex]?.ready
        } == true

    // Возвращаем тождество графического узла для проверок. По нему видно, остался ли прежний
    // рисунок после изменения позиции.
    fun nodeIdentity(id: FragmentID): Int? = nodes[id]?.let { System.identityHashCode(it.native) }

    // Проверяем, записаны ли команды для всех нужных фрагментов. До окончания ожидания медиа
    // требуем также готовности видимых ячеек, не задерживая кадр из-за далёкого вложения.
    fun presentationReady(ids: Set<FragmentID>, requireMedia: Boolean): Boolean =
        ids.all { id ->
            val node = nodes[id] ?: return@all false
            if (!node.native.hasDisplayList()) return@all false
            if (!requireMedia || node.fragment.part != 0) return@all true
            node.fragment.prepared.mediaItemRects.withIndex().all { (index, local) ->
                val rect =
                    RectF(local).apply { offset(node.fragment.frame.left, node.fragment.frame.top) }
                if (!RectF.intersects(rect, imageViewport ?: viewport)) true
                else
                    (if (index == 0) node.image else node.extraImages[index])?.let {
                        it.ready && !it.loading
                    } == true
            }
        }

    data class MediaLoadAction(
        val messageID: MessageID,
        val attachmentIndex: Int,
        val rect: RectF,
        val label: String,
    )

    // Перечисляем видимые действия загрузки по номеру вложения. При живом видео его собственное
    // управление имеет преимущество.
    fun loadActions(): List<MediaLoadAction> =
        nodes.values.flatMap { node ->
            if (node.video?.presented == true) emptyList()
            else
                (node.extraImages.toMap() + listOfNotNull(node.image?.let { 0 to it }))
                    .toSortedMap()
                    .mapNotNull { (index, image) ->
                        image.loadAction()?.let {
                            MediaLoadAction(node.fragment.id.messageID, index, it.first, it.second)
                        }
                    }
        }

    // Передаём касание кнопке загрузки, которая его приняла. У уже играющего одиночного видео
    // такой проверки нет, чтобы кнопка под кадром не сработала.
    fun performLoadAction(x: Float, y: Float): Boolean {
        for (node in nodes.values) if (node.video?.presented != true)
            for (image in listOfNotNull(node.image) + node.extraImages.values) if (
                image.performLoadAction(x, y)
            )
                return true
        return false
    }

    // Сбрасываем подготовку, увеличиваем номер и снимаем рисунки, ролики и индикаторы. Старые
    // задания после смены размеров не должны менять новую сцену.
    fun advanceGeneration() {
        imagePrefetch.clear()
        mediaStore?.videoPlayback?.prepareCovers(emptyList())
        videoPreviews.clear()
        main()
        ticket++
        nodes.values.forEach {
            it.video?.close()
            it.retireAlbumVideos()
            it.retireProgress()
            it.retireImages()
            it.native.discardDisplayList()
            it.overlay.discardDisplayList()
        }
        nodes.clear()
        metrics.liveFragments = 0
    }

    // Отбираем видимые фрагменты и до трёх ближайших с запасом в обе стороны, переиспользуем
    // записанные узлы и управляем ограниченным числом роликов. Готовим соседние фото и обложки
    // отдельно, чтобы быстрый свайп не удерживал всё окно в движении.
    fun apply(snapshot: SceneSnapshot) {
        main()
        if (closed || snapshot.ticket != ticket) return
        if (previewObserver == null)
            previewObserver = MediaAdmission.observePreviewAvailability { onImageReady?.invoke() }
        val fragments = snapshot.fragments.subList(0, minOf(128, snapshot.fragments.size))
        val imageView = imageViewport
        val preparation = imagePreparationViewport
        val towardOlder = imageView != null && preparation != null &&
            imageView.top - preparation.top >= preparation.bottom - imageView.bottom
        val neighbors =
            if (mediaVisible && imageView != null && preparation != null)
                fragments
                    .mapNotNull { fragment ->
                        val local = fragment.prepared.mediaRect
                        if (
                            fragment.part != 0 ||
                                local == null ||
                                fragment.prepared.message.attachments.isEmpty()
                        )
                            return@mapNotNull null
                        val rect =
                            RectF(local).apply { offset(fragment.frame.left, fragment.frame.top) }
                        if (
                            RectF.intersects(preparation, rect) &&
                                !RectF.intersects(imageView, rect)
                        )
                            Triple(fragment,
                                if (towardOlder) rect.centerY() < imageView.centerY()
                                else rect.centerY() > imageView.centerY(),
                                kotlin.math.abs(rect.centerY() - imageView.centerY()))
                        else null
                    }
                    .sortedWith(compareByDescending<Triple<SceneFragment, Boolean, Float>> { it.second }
                        .thenBy { it.third })
                    .let { ordered ->
                        val behind = ordered.firstOrNull { !it.second }
                        if (ordered.size > 3 && ordered[2].second && behind != null)
                            listOf(ordered[0], ordered[1], behind)
                        else ordered.take(3)
                    }
                    .map { it.first }
            else emptyList()
        val prefetched = neighbors.mapTo(HashSet(neighbors.size)) { it.id }
        val videoPreviewsAllowed = mediaVisible && movingMediaVisible && MediaAdmission.previewsAllowed
        var upcomingVideo: VideoPlaybackKey? = null
        var upcomingFraction = 0f
        // Запоминаем наиболее видимый ролик среди тех, что входят на экран и уже проигрывались.
        // Его можно успеть подготовить по сохранённой позиции, читая местный файл.
        fun considerVideo(key: VideoPlaybackKey, fraction: Float) {
            if (fraction >= 0.12f && fraction < 0.5f && fraction > upcomingFraction &&
                (mediaStore?.videoPlayback?.position(key) ?: 0L) > 0
            ) {
                upcomingVideo = key
                upcomingFraction = fraction
            }
        }
        val animated =
            fragments
                .filter { fragment ->
                    val local = fragment.prepared.mediaRect
                    if (
                        !videoPreviewsAllowed ||
                            viewport.width() <= 0 ||
                            viewport.height() <= 0 ||
                            fragment.part != 0 ||
                            fragment.prepared.generationCard?.isBatch == true ||
                            fragment.prepared.message.attachments.size > 1 ||
                            local == null ||
                            fragment.prepared.message.kind !in
                                listOf(MessageKind.GIF, MessageKind.VIDEO, MessageKind.ROUND)
                    )
                        false
                    else {
                        val rect =
                            RectF(local).apply { offset(fragment.frame.left, fragment.frame.top) }
                        val visible = RectF(rect)
                        if (!visible.intersect(viewport) || visible.width() <= 0 || rect.height() <= 0) {
                            false
                        } else {
                            val fraction = visible.height() / minOf(rect.height(), viewport.height())
                            val message = fragment.prepared.message
                            if (fraction < 0.5f && message.kind != MessageKind.GIF) {
                                message.attachments.firstOrNull()?.let {
                                    considerVideo(VideoPlaybackKey(message.id, it), fraction)
                                }
                            }
                            fraction >= 0.5f
                        }
                    }
                }
                .sortedBy {
                    val rect = it.prepared.mediaRect!!
                    kotlin.math.abs(it.frame.top + rect.centerY() - viewport.centerY())
                }
                .take(2)
                .map { it.id }
                .toSet()
        val wanted = fragments.map { it.id }.toSet()
        val videoKeys = fragments.filter {
            it.id in animated && it.prepared.message.kind in listOf(MessageKind.VIDEO, MessageKind.ROUND)
        }.mapNotNull { f -> f.prepared.message.attachments.firstOrNull()?.let {
            VideoPlaybackKey(f.prepared.message.id, it)
        } }.toMutableList()
        var hasAlbumVideo = false
        val currentMedia = mediaStore
        if (videoPreviewsAllowed && currentMedia != null) {
            for (f in fragments) {
                if (f.part != 0) continue
                val prepared = f.prepared
                if (prepared.mediaItemRects.size <= 1 && prepared.generationCard?.isBatch != true) continue
                prepared.message.attachments.forEachIndexed { index, a ->
                    if (!a.mime.startsWith("video/") || index !in prepared.mediaItemRects.indices) return@forEachIndexed
                    if (prepared.generationCard?.isBatch != true && !currentMedia.isStudioVideo(a)) return@forEachIndexed
                    val frame = RectF(prepared.mediaItemRects[index]).apply { offset(f.frame.left, f.frame.top) }
                    val shown = RectF(frame)
                    if (!shown.intersect(viewport) || frame.height() <= 0 || viewport.height() <= 0)
                        return@forEachIndexed
                    val key = VideoPlaybackKey(prepared.message.id, a)
                    val fraction = shown.height() / minOf(frame.height(), viewport.height())
                    if (fraction >= 0.5f) {
                        videoKeys += key
                        hasAlbumVideo = true
                    } else {
                        considerVideo(key, fraction)
                    }
                }
            }
        }
        videoPreviews.beginFrame(videoKeys, if (hasAlbumVideo) 4 else 2, upcomingVideo)
        nodes.keys
            .filter { it !in wanted }
            .forEach {
                nodes.remove(it)?.let { node ->
                    node.video?.setVisible(false)
                    node.retireAlbumVideos(keepingPlayers = true)
                    node.retireProgress()
                    node.retireImages()
                    node.native.discardDisplayList()
                    node.overlay.discardDisplayList()
                }
            }
        for (fragment in fragments) {
            val old = nodes[fragment.id]
            val node = old ?: MessageNode(fragment).also { nodes[fragment.id] = it }
            val previous = old?.fragment?.prepared
            val card = fragment.prepared.generationCard
            val newLayout = previous !== fragment.prepared
            val sameInk = newLayout && previous?.generationCard?.let { card?.sameStaticInk(it) } == true
            val changed =
                old == null ||
                    node.fragment.part != fragment.part ||
                    (newLayout && !sameInk && previous?.key != fragment.prepared.key)
            if (newLayout && card?.isBatch == true && previous?.generationCard?.isBatch == true)
                node.preserveAlbumImages(previous.message, fragment.prepared.message)
            node.fragment = fragment
            node.position()
            if (changed || !node.native.hasDisplayList()) {
                node.record()
                metrics.recordings++
            }
            val rect = fragment.prepared.mediaRect
            val media = mediaStore
            val a = fragment.prepared.message.attachments.firstOrNull()
            val preparing = fragment.id in prefetched
            val imageIsNear =
                imageView == null ||
                    rect == null ||
                    RectF.intersects(
                        RectF(imageView).apply { inset(0f, -20f * fragment.prepared.key.density) },
                        RectF(rect).apply { offset(fragment.frame.left, fragment.frame.top) },
                    )
            if (
                mediaVisible &&
                    imageIsNear &&
                    fragment.part == 0 &&
                    (fragment.prepared.mediaItemRects.size > 1 ||
                        fragment.prepared.generationCard?.isBatch == true) &&
                    media != null
            ) {
                bindAlbum(fragment, node, media, preparing, upcomingVideo)
            } else if (
                mediaVisible &&
                    imageIsNear &&
                    fragment.part == 0 &&
                    rect != null &&
                    media != null &&
                    a != null
            ) {
                node.retireAlbumVideos(keepingPlayers = true)
                node.retireExtraImages()
                val slot =
                    node.image
                        ?: MediaImageSlot(media) { onImageReady?.invoke() }.also { node.image = it }
                val deliveringPhoto =
                    fragment.prepared.message.direction == MessageDirection.INCOMING &&
                        media.studioPhotoKey(a) != null &&
                        (previous?.generationCard?.generation?.isActive == true ||
                            slot.awaitingStudioPhoto)
                val isVideo = fragment.prepared.message.kind in listOf(MessageKind.VIDEO, MessageKind.ROUND)
                val playbackKey = if (isVideo) VideoPlaybackKey(fragment.prepared.message.id, a) else null
                slot.bind(
                    a,
                    rect,
                    fragment.prepared.message.kind == MessageKind.ROUND,
                    cornerRadius = fragment.prepared.mediaCornerRadius,
                    bottomSquare = fragment.prepared.mediaBottomSquare,
                    kind = fragment.prepared.message.kind,
                    showControls = !media.isStudioVideo(a),
                    studioPhotoDelivery = deliveringPhoto,
                    showLoadingControl = fragment.prepared.message.kind != MessageKind.PHOTO,
                    localOnly = preparing,
                    offsetX = fragment.frame.left,
                    offsetY = fragment.frame.top,
                    playbackKey = playbackKey,
                )
                imagePrefetch.preview(MediaImagePrefetch.Item(a), media)?.let { slot.seed(it) }
                val container = movingContainer
                if (container != null && playbackKey != null &&
                    (fragment.id in animated || playbackKey == upcomingVideo)
                ) {
                    val key = playbackKey
                    val video = videoPreviews.preview(key) {
                        SilentVideoPreview(container.context, media) {
                                    updateClockMetrics()
                                    onFrameChanged?.invoke()
                                }
                    }
                    node.video = video
                    if (video != null && video.parent == null) container.addView(video)
                    video?.onPausedFrame = { slot.showSavedPlaybackFrame(key) }
                    video?.bind(
                        a,
                        rect,
                        fragment.prepared.message.kind == MessageKind.ROUND,
                        fragment.prepared.mediaCornerRadius,
                        fragment.prepared.mediaBottomSquare,
                        node.overlay,
                        fragment.prepared,
                        playbackKey = key,
                        localOnly = key == upcomingVideo,
                        offsetX = fragment.frame.left,
                        offsetY = fragment.frame.top,
                    )
                } else {
                    node.video?.setVisible(false)
                    node.video = null
                    slot.showSavedPlaybackFrame(playbackKey)
                }
            } else {
                node.video?.setVisible(false)
                node.video = null
                node.retireAlbumVideos(keepingPlayers = true)
                node.retireImages()
            }
            bindProgress(fragment, node)
        }
        mediaStore?.let { media ->
            val images = neighbors.flatMap { fragment ->
                val maxPixel = mediaThumbnailLimit(fragment.prepared)
                fragment.prepared.message.attachments.map { MediaImagePrefetch.Item(it, maxPixel) }
            }
            imagePrefetch.update(images, media)
            media.videoPlayback.prepareCovers(neighbors.flatMap { fragment ->
                fragment.prepared.message.attachments.filter { it.mime.startsWith("video/") }
                    .map { VideoPlaybackKey(fragment.prepared.message.id, it) }
            })
        }
        metrics.liveFragments = nodes.size
        updateClockMetrics()
    }

    // Удерживаем индикаторы только для видимых работающих ячеек. Фото после завершения генерации
    // может ещё готовиться к показу, поэтому его индикатор снимается по готовности кадра.
    private fun bindProgress(fragment: SceneFragment, node: MessageNode) {
        val container = movingContainer
        val card = fragment.prepared.generationCard
        if (!mediaVisible || !movingMediaVisible || fragment.part != 0 || container == null) {
            node.retireProgress()
            return
        }
        val wanted = mutableSetOf<String>()
        // Привязываем индикатор к рамке и номеру выбранной ячейки. Повторно используем
        // существующий рисунок и отмечаем, что он всё ещё нужен.
        fun bind(id: String, local: RectF, progress: Int?, deliveringPhoto: Boolean = false) {
            val rect = RectF(local).apply { offset(fragment.frame.left, fragment.frame.top) }
            if (!RectF.intersects(rect, viewport)) return
            wanted += id
            val view =
                node.progressViews.getOrPut(id) {
                    StudioProgressView(container.context).also { container.addView(it) }
                }
            view.bind(progress, rect, fragment.prepared.key.density, deliveringPhoto)
        }
        card?.runningCells?.forEach { bind(it.slot.id, it.frame, it.slot.progress) }
        fragment.prepared.message.attachments.forEachIndexed { index, attachment ->
            val image = if (index == 0) node.image else node.extraImages[index]
            val frame = fragment.prepared.mediaItemRects.getOrNull(index)
            if (image?.awaitingStudioPhoto == true && frame != null) {
                val id =
                    card?.cells?.firstOrNull { it.slot.blobId == attachment.thumbnailKey }?.slot?.id
                        ?: "single"
                bind(id, frame, 99, deliveringPhoto = true)
            }
        }
        node.progressViews.keys
            .toList()
            .filter { it !in wanted }
            .forEach { id -> node.progressViews.remove(id)?.let { container.removeView(it) } }
    }

    // Ограничиваем размер изображения в ячейке альбома сильнее, чем для одиночного файла. Рисунок
    // не должен занимать память полноразмерного кадра в каждой маленькой ячейке.
    private fun mediaThumbnailLimit(prepared: PreparedText): Int =
        if (prepared.mediaItemRects.size > 1 || prepared.generationCard?.isBatch == true)
            MediaImagePrefetch.ALBUM_MAX_PIXEL
        else 1024

    // Сохраняем рисунок каждой нужной ячейки, а для достаточно видимых роликов подключаем общий
    // запас проигрывателей. После паузы ставим сохранённый кадр. Ресурсы невидимых ячеек
    // освобождаем.
    private fun bindAlbum(
        fragment: SceneFragment,
        node: MessageNode,
        media: MediaStore,
        preparing: Boolean,
        upcomingVideo: VideoPlaybackKey?,
    ) {
        node.video?.setVisible(false)
        node.video = null
        val wanted = mutableSetOf<Int>()
        val playing = mutableSetOf<String>()
        val container = movingContainer
        fragment.prepared.mediaItemRects.forEachIndexed { index, local ->
            val rect = RectF(local).apply { offset(fragment.frame.left, fragment.frame.top) }
            if (
                !preparing &&
                    imageViewport?.let {
                        RectF(it)
                            .apply { inset(0f, -20f * fragment.prepared.key.density) }
                            .let { near -> !RectF.intersects(near, rect) }
                    } == true
            )
                return@forEachIndexed
            wanted += index
            val slot =
                if (index == 0)
                    node.image
                        ?: MediaImageSlot(media) { onImageReady?.invoke() }.also { node.image = it }
                else
                    node.extraImages.getOrPut(index) {
                        MediaImageSlot(media) { onImageReady?.invoke() }
                    }
            val attachment = fragment.prepared.message.attachments[index]
            val kind =
                if (attachment.mime.startsWith("video/")) MessageKind.VIDEO else MessageKind.PHOTO
            val studioVideo =
                kind == MessageKind.VIDEO &&
                    (fragment.prepared.generationCard?.isBatch == true ||
                        media.isStudioVideo(attachment))
            val slotID =
                fragment.prepared.generationCard
                    ?.cells
                    ?.firstOrNull { it.slot.blobId == attachment.thumbnailKey }
                    ?.slot
                    ?.id
            val deliveringPhoto =
                fragment.prepared.message.direction == MessageDirection.INCOMING &&
                    media.studioPhotoKey(attachment) != null &&
                    (slot.awaitingStudioPhoto || (slotID != null && slotID in node.progressViews))
            val cellPixelLimit = MediaImagePrefetch.ALBUM_MAX_PIXEL
            val playbackKey = if (kind == MessageKind.VIDEO)
                VideoPlaybackKey(fragment.prepared.message.id, attachment) else null
            slot.bind(
                attachment,
                local,
                maxPixel = cellPixelLimit,
                cornerRadius = 0f,
                kind = kind,
                showControls = !studioVideo,
                studioPhotoDelivery = deliveringPhoto,
                showLoadingControl = kind != MessageKind.PHOTO,
                localOnly = preparing,
                offsetX = fragment.frame.left,
                offsetY = fragment.frame.top,
                playbackKey = playbackKey,
            )
            imagePrefetch.preview(MediaImagePrefetch.Item(attachment, cellPixelLimit), media)
                ?.let { slot.seed(it) }
            if (playbackKey != null) {
                val visible = RectF(rect)
                if (
                    studioVideo &&
                        container != null &&
                        movingMediaVisible &&
                        MediaAdmission.previewsAllowed &&
                        visible.intersect(viewport) &&
                        (playbackKey == upcomingVideo ||
                            visible.height() >= minOf(rect.height(), viewport.height()) * .5f)
                ) {
                    playing += attachment.id
                    val key = playbackKey
                    val video = videoPreviews.preview(key) {
                            SilentVideoPreview(container.context, media) {
                                    updateClockMetrics()
                                    onFrameChanged?.invoke()
                                }
                                .also { container.addView(it) }
                    }
                    if (video != null) node.albumVideos[attachment.id] = video
                    video?.onPausedFrame = { slot.showSavedPlaybackFrame(key) }
                    video?.bind(
                        attachment,
                        local,
                        false,
                        12 * fragment.prepared.key.density,
                        false,
                        node.overlay,
                        fragment.prepared,
                        studioAlbum = true,
                        playbackKey = key,
                        localOnly = key == upcomingVideo,
                        offsetX = fragment.frame.left,
                        offsetY = fragment.frame.top,
                    )
                } else if (!preparing) {
                    slot.showSavedPlaybackFrame(playbackKey)
                    slot.prepareOriginalVideo(attachment)
                }
            }
        }
        node.albumVideos.keys
            .toList()
            .filter { it !in playing }
            .forEach { node.albumVideos.remove(it)?.setVisible(false) }
        if (0 !in wanted) {
            node.image?.close()
            node.image = null
        }
        node.extraImages.keys
            .toList()
            .filter { it !in wanted }
            .forEach { node.extraImages.remove(it)?.close() }
    }

    // Обрезаем картинки по общей форме альбома. При активном видео не дублируем кадр с кнопками,
    // а в программном режиме используем совместимый путь рисования.
    private fun drawImages(node: MessageNode, c: Canvas, software: Boolean) {
        c.withSave {
            if (node.extraImages.isNotEmpty())
                node.fragment.prepared.mediaRect?.let { local ->
                    val rect =
                        RectF(local).apply { offset(node.fragment.frame.left, node.fragment.frame.top) }
                    val radius = node.fragment.prepared.mediaCornerRadius
                    val bottom = if (node.fragment.prepared.mediaBottomSquare) 0f else radius
                    val clip =
                        Path().apply {
                            addRoundRect(
                                rect,
                                floatArrayOf(
                                    radius,
                                    radius,
                                    radius,
                                    radius,
                                    bottom,
                                    bottom,
                                    bottom,
                                    bottom,
                                ),
                                Path.Direction.CW,
                            )
                        }
                    c.clipPath(clip)
                }
            if (node.video?.presented != true || node.image?.showsControls == false) {
                if (software) node.image?.drawSoftware(c) else node.image?.draw(c)
            }
            for (image in node.extraImages.values) {
                if (software) image.drawSoftware(c) else image.draw(c)
            }
        }
    }

    // Очищаем запись команд одного узла по ключу. Следующий проход восстановит её из
    // подготовленной строки без чтения сообщения из базы.
    fun discardDisplayList(id: FragmentID) {
        main()
        nodes[id]?.native?.discardDisplayList()
    }

    // Восстанавливаем графические команды только у узлов, где записи больше нет. Счётчик
    // показывает, когда повторная запись действительно понадобилась.
    fun prepareForDraw() {
        main()
        for (node in nodes.values) if (!node.native.hasDisplayList()) {
            node.record()
            metrics.recordings++
        }
    }

    // Рисуем готовые узлы, попавшие в область холста, и их изображения. Подпись поверх видео
    // обрезаем по его рамке. Для программного холста выбираем отдельный путь.
    fun draw(canvas: Canvas) {
        if (!canvas.isHardwareAccelerated) {
            drawSoftware(canvas)
            return
        }
        prepareForDraw()
        val clip = android.graphics.Rect()
        if (!canvas.getClipBounds(clip)) return
        for (node in nodes.values) {
            val r = node.fragment.frame
            if (
                r.right < clip.left - 2 ||
                    r.left > clip.right + 2 ||
                    r.bottom < clip.top - 2 ||
                    r.top > clip.bottom + 2
            )
                continue
            canvas.drawRenderNode(node.native)
            drawImages(node, canvas, false)
            if (node.overlay.hasDisplayList()) {
                canvas.withSave {
                    if (node.video?.presented == true)
                        node.fragment.prepared.mediaRect?.let {
                            canvas.clipOutRect(
                                it.left + r.left,
                                it.top + r.top,
                                it.right + r.left,
                                it.bottom + r.top,
                            )
                        }
                    canvas.drawRenderNode(node.overlay)
                }
            }
        }
    }

    // Вручную рисуем фрагмент и подпись на холсте без ускорения. Сохраняем те же рамки, отсечение
    // и порядок, чтобы изображение соответствовало обычному показу.
    private fun drawSoftware(c: Canvas) {
        main()
        if (closed) return
        for (node in nodes.values) {
            val f = node.fragment
            if (c.quickReject(f.frame, Canvas.EdgeType.AA)) continue
            var save = c.save()
            c.translate(f.frame.left, f.frame.top)
            f.prepared.draw(f.part, c)
            c.restoreToCount(save)
            drawImages(node, c, true)
            if (f.part == 0 && f.prepared.mediaRect != null) {
                save = c.save()
                val media = f.prepared.mediaRect
                if (node.video?.presented == true)
                    c.clipOutRect(
                        media.left + f.frame.left,
                        media.top + f.frame.top,
                        media.right + f.frame.left,
                        media.bottom + f.frame.top,
                    )
                c.translate(f.frame.left, f.frame.top)
                f.prepared.drawMediaStamp(c)
                c.restoreToCount(save)
            }
        }
    }

    // Считаем реально играющие одиночные и альбомные ролики. Подготовленный или остановленный
    // проигрыватель не повышает число активных кадров.
    private fun updateClockMetrics() {
        metrics.activeFrameClocks =
            nodes.values.sumOf { node ->
                (if (node.video?.isPlaying == true) 1 else 0) +
                    node.albumVideos.values.count { it.isPlaying }
            }
    }

    // Очищаем сцену один раз и снимаем подписку на свободное место проигрывателя. После закрытия
    // не остаётся ссылки на окно движущихся вложений.
    fun close() {
        main()
        if (closed) return
        advanceGeneration()
        closed = true
        previewObserver?.let { MediaAdmission.removePreviewObserver(it) }
        previewObserver = null
        movingContainer = null
        metrics.activeFrameClocks = 0
    }
}
