package dev.kirill.kandinsky.chat.attachments

import android.app.Activity
import android.content.Intent
import android.net.Uri
import dev.kirill.kandinsky.chat.conversation.ConversationState
import dev.kirill.kandinsky.chat.conversation.StudioGenerationSheet
import dev.kirill.kandinsky.chat.domain.Attachment
import dev.kirill.kandinsky.chat.domain.AttachmentDraft
import dev.kirill.kandinsky.chat.domain.Cancellation
import dev.kirill.kandinsky.chat.domain.KandinskyOptions
import dev.kirill.kandinsky.chat.domain.StudioUpload
import dev.kirill.kandinsky.chat.media.MediaAdmission
import dev.kirill.kandinsky.chat.media.MediaCancellation
import dev.kirill.kandinsky.chat.media.MediaError
import dev.kirill.kandinsky.chat.media.MediaException
import dev.kirill.kandinsky.chat.media.MediaStore
import dev.kirill.kandinsky.chat.network.NetworkSync
import java.io.File
import kotlin.concurrent.thread

internal enum class AttachmentSource {
    PHOTO,
    CAMERA,
    VIDEO,
}

internal class ChatAttachmentCoordinator(
    private val activity: Activity,
    private val picker: AttachmentPicker,
    private val network: NetworkSync?,
    private val media: MediaStore,
    private val admission: MediaAdmission,
    private val state: ConversationState,
    private val isActive: () -> Boolean,
    private val options: () -> KandinskyOptions,
    private val selectOptions: (KandinskyOptions) -> Unit,
    private val showError: (String) -> Unit,
    private val previewVideo: (Attachment, (() -> Unit)?, () -> Unit) -> Unit,
    private val refresh: () -> Unit,
    private val refreshProgress: () -> Unit,
    private val prepareDraft: ((Result<Unit>) -> Unit) -> Unit = { it(Result.success(Unit)) },
) {
    private val cameraOrGalleryError =
        "Не удалось открыть камеру или галерею. Проверьте разрешения."
    private var camera: PhotoCaptureController? = null
    private var pendingImport: Cancellation? = null
    private var documentPending = false
    private var activeTransfer: MediaCancellation? = null
    private var mappingDraft: AttachmentDraft? = null
    private var lastSourceAttempt: String? = null
    private var checkingSource: String? = null
    var progress = 0.0
        private set

    val isUploading
        get() = activeTransfer != null

    val isBusy
        get() = pendingImport != null || isUploading || checkingSource != null

    val canPick
        get() = !isBusy && !documentPending

    fun resumePendingUpload(reconnected: Boolean = false) {
        val network = this.network ?: return
        val source = state.draft.attachmentDrafts.firstOrNull() ?: return
        if (!isActive() || !state.isReady || isBusy || !source.mime.startsWith("image/")) return
        if (reconnected) lastSourceAttempt = null
        if (lastSourceAttempt == source.id) return
        lastSourceAttempt = source.id
        checkingSource = source.id
        network.hasStudioUpload(source.id) { saved ->
            if (checkingSource == source.id) checkingSource = null
            if (
                saved.getOrNull() == false &&
                    isActive() &&
                    !isBusy &&
                    state.draft.attachmentDrafts.firstOrNull()?.id == source.id
            )
                upload(source)
        }
    }

    fun stopCapture() {
        camera?.cancel()
    }

    fun pausePendingUpload() {
        activeTransfer?.cancel()
        activeTransfer = null
        refresh()
    }

    fun close(discardDraft: Boolean) {
        stopCapture()
        pendingImport?.cancel()
        pendingImport = null
        activeTransfer?.cancel()
        activeTransfer = null
        mappingDraft?.let { file ->
            if (state.draft.attachmentDrafts.none { it.id == file.id })
                media.discardUnpublishedImport(file)
        }
        mappingDraft = null
        if (documentPending) {
            picker.clearDocumentResult()
            documentPending = false
        }
        val drafts = state.draft.attachmentDrafts
        if (discardDraft && drafts.isNotEmpty()) media.discardUnpublishedImports(drafts)
    }

    fun showPicker() {
        val sheet = StudioGenerationSheet(activity, options())
        sheet.showAttachments { source ->
            if (!isActive()) return@showAttachments
            when (source) {
                AttachmentSource.CAMERA -> openCamera()
                AttachmentSource.PHOTO,
                AttachmentSource.VIDEO -> pickDocument(source)
            }
        }
        network?.recentStudioUploads { result ->
            if (isActive())
                result.onSuccess { uploads -> sheet.appendRecentUploads(uploads, ::selectRecent) }
        }
    }

    private fun openCamera() {
        val capture =
            PhotoCaptureController(
                activity,
                media,
                admission,
                requestCameraPermission = picker::requestCameraPermission,
                prepareStudioPhoto = true,
            )
        camera = capture
        capture.open { result ->
            camera = null
            result.onSuccess(::uploadIfOpen).onFailure { error ->
                if (isActive() && (error as? MediaException)?.reason != MediaError.CANCELLED)
                    showError(cameraOrGalleryError)
            }
        }
    }

    private fun uploadIfOpen(file: AttachmentDraft) {
        if (isActive()) upload(file) else media.discardUnpublishedImport(file)
    }

    private fun pickDocument(source: AttachmentSource) {
        documentPending = true
        refresh()
        val request =
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = if (source == AttachmentSource.VIDEO) "video/*" else "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        try {
            picker.launchDocumentPicker(request) { data ->
                picker.clearDocumentResult()
                documentPending = false
                refresh()
                data?.data?.takeIf { isActive() }?.let { importDocument(it, source) }
            }
        } catch (_: android.content.ActivityNotFoundException) {
            picker.clearDocumentResult()
            documentPending = false
            refresh()
            showError(cameraOrGalleryError)
        }
    }

    private fun importDocument(uri: Uri, source: AttachmentSource) {
        val resolver = activity.contentResolver
        val open = {
            resolver.openInputStream(uri) ?: throw java.io.IOException("No granted stream")
        }
        val complete: (Result<AttachmentDraft>) -> Unit = { result ->
            pendingImport = null
            refresh()
            result.onSuccess(::uploadIfOpen).onFailure {
                if (isActive()) showError("Не удалось открыть файл")
            }
        }
        pendingImport =
            if (source == AttachmentSource.VIDEO) {
                media.stageSelection(open, resolver.getType(uri).orEmpty(), complete)
            } else media.stageStudioPhoto(open, complete)
    }

    private fun selectRecent(upload: StudioUpload) {
        val network = this.network ?: return
        if (!canPick || !isActive()) return
        val download = MediaCancellation()
        pendingImport = download
        refresh()
        prepareDraft { prepared ->
            if (pendingImport !== download || download.isCancelled || !isActive()) return@prepareDraft
            if (prepared.isSuccess) downloadRecent(network, upload, download)
            else {
                pendingImport = null
                refresh()
                showError("Не удалось подготовить чат")
            }
        }
    }

    private fun downloadRecent(network: NetworkSync, upload: StudioUpload, download: MediaCancellation) {
        val chat = state.chatID ?: run {
            pendingImport = null
            refresh()
            return
        }
        val suffix = if (upload.isVideo) ".mp4" else ".jpg"
        val temporary = File(activity.cacheDir, "kandinsky-" + java.util.UUID.randomUUID() + suffix)
        thread(name = "KandinskyRecentUpload") {
            val result = runCatching {
                network.api.json(
                    "/v1/kandinsky/threads/$chat/sources/${upload.blobId}", method = "POST"
                )
                network.api.download(upload.blobId, temporary, upload.byteSize, download)
            }
            activity.runOnUiThread {
                if (download.finish() && isActive() && result.isSuccess) {
                    importRecent(network, upload, temporary)
                } else {
                    temporary.delete()
                    if (pendingImport === download) pendingImport = null
                    if (isActive() && !download.isCancelled && result.isFailure)
                        showError("Не удалось открыть файл")
                    refresh()
                }
            }
        }
    }

    private fun importRecent(network: NetworkSync, upload: StudioUpload, temporary: File) {
        pendingImport =
            media.stageSelection({ temporary.inputStream() }, upload.mime) { result ->
                temporary.delete()
                val file = result.getOrNull()
                if (file == null || !isActive()) {
                    pendingImport = null
                    if (file != null) media.discardUnpublishedImport(file)
                    else if (isActive()) showError("Не удалось открыть файл")
                    refresh()
                    return@stageSelection
                }
                network.rememberStudioUpload(file.id, upload.blobId) { saved ->
                    pendingImport = null
                    refresh()
                    if (isActive() && saved.isSuccess) {
                        if (upload.isVideo) preview(file, media) else replacePhoto(file)
                    } else {
                        media.discardUnpublishedImport(file)
                        if (isActive()) showError("Не удалось открыть файл")
                    }
                }
            }
    }

    fun replacePhoto(photo: AttachmentDraft?) {
        if (photo == null) {
            pendingImport?.cancel()
            activeTransfer?.cancel()
            activeTransfer = null
            checkingSource = null
        }
        val model =
            if (photo != null) "video"
            else if (options().model == "video_sr") "image" else options().model
        replaceSource(photo, model)
    }

    private fun replaceSource(source: AttachmentDraft?, model: String) {
        val options = options()
        val previous = state.draft.attachmentDrafts
        if (previous.map { it.id } == listOfNotNull(source?.id)) return
        val next =
            if (model == "video_sr") KandinskyOptions(model = model)
            else
                KandinskyOptions(
                    model = model,
                    aspect = options.aspect,
                    count = options.count,
                    seed = options.seed,
                )
        state.editDraft(state.draft.copy(attachmentDrafts = listOfNotNull(source)))
        selectOptions(next)
        if (previous.isNotEmpty()) state.flush { media.discardUnpublishedImports(previous) }
    }

    private fun upload(file: AttachmentDraft) {
        val network = this.network
        val isPhoto = file.mime.startsWith("image/")
        val valid =
            file.byteSize <= 20 * 1024 * 1024 &&
                (isPhoto || file.mime == "video/mp4" && (file.durationMs ?: 60001) <= 60000)
        if (network == null || !isActive() || !valid) {
            media.discardUnpublishedImport(file)
            if (!valid)
                showError(
                    if (isPhoto) "Выберите фото до 20 МБ" else "Выберите MP4 до 20 МБ и 60 секунд"
                )
            return
        }
        val transfer = MediaCancellation()
        lastSourceAttempt = file.id
        activeTransfer = transfer
        progress = 0.0
        transfer.onProgress { value ->
            activity.runOnUiThread {
                if (activeTransfer === transfer) {
                    progress = maxOf(progress, value)
                    refreshProgress()
                }
            }
        }
        refresh()
        fun transmit() {
            val chat = state.chatID ?: run {
                finishUpload(transfer, file, kept = false)
                return
            }
            thread(name = "KandinskyStudioUpload") {
                val result = runCatching {
                    network.uploadStudioSource(file, media.fileURL(file.relativePath), transfer, chat)
                }
                activity.runOnUiThread {
                    if (activeTransfer !== transfer || !isActive()) {
                        finishUpload(transfer, file, kept = false)
                    } else {
                        result.fold(
                            { (blob, previewID) ->
                                rememberUpload(network, transfer, file, blob, previewID)
                            },
                            { error -> uploadFailed(transfer, file, error) },
                        )
                    }
                }
            }
        }
        if (!isPhoto) {
            prepareDraft { prepared ->
                if (prepared.isSuccess && activeTransfer === transfer && isActive()) transmit()
                else finishUpload(transfer, file, kept = false)
            }
            return
        }
        replacePhoto(file)
        state.flush { persisted ->
            if (persisted.isFailure || activeTransfer !== transfer || !isActive()) {
                if (persisted.isFailure) showError("Не удалось сохранить выбранное фото")
                finishUpload(transfer, file, kept = false)
                return@flush
            }
            prepareDraft { prepared ->
                if (activeTransfer !== transfer || !isActive()) {
                    finishUpload(transfer, file, kept = false)
                    return@prepareDraft
                }
                prepared.fold(
                    {
                        state.flush { saved ->
                            if (saved.isSuccess && activeTransfer === transfer && isActive())
                                transmit()
                            else {
                                if (saved.isFailure)
                                    showError("Не удалось сохранить выбранное фото")
                                finishUpload(transfer, file, kept = false)
                            }
                        }
                    },
                    {
                        showError("Фото осталось на устройстве. Проверьте соединение.")
                        finishUpload(transfer, file, kept = true)
                    },
                )
            }
        }
    }

    private fun rememberUpload(
        network: NetworkSync,
        transfer: MediaCancellation,
        file: AttachmentDraft,
        blob: String,
        previewID: String?,
    ) {
        mappingDraft = file
        network.rememberStudioUpload(file.id, blob, previewID) { saved ->
            if (mappingDraft?.id == file.id) mappingDraft = null
            val keep = activeTransfer === transfer && isActive() && saved.isSuccess
            if (keep) {
                if (file.mime.startsWith("image/")) replacePhoto(file)
                else preview(file, media)
            } else if (activeTransfer === transfer) {
                showError(uploadError(file))
            }
            finishUpload(transfer, file, keep)
        }
    }

    private fun uploadFailed(transfer: MediaCancellation, file: AttachmentDraft, error: Throwable) {
        val offline =
            error is java.net.UnknownHostException ||
                error is java.net.SocketException ||
                error is java.net.SocketTimeoutException
        // Фото можно отправить позже из черновика. Для просмотра видео нужен подтверждённый файл.
        val keep = file.mime.startsWith("image/") && offline
        if (keep) replacePhoto(file) else showError(uploadError(file))
        finishUpload(transfer, file, keep)
    }

    private fun uploadError(file: AttachmentDraft) =
        if (file.mime.startsWith("image/")) "Не удалось загрузить фото"
        else "Не удалось загрузить видео"

    private fun finishUpload(transfer: MediaCancellation, file: AttachmentDraft, kept: Boolean) {
        if (activeTransfer === transfer) {
            transfer.finish()
            activeTransfer = null
            refresh()
        }
        if (!kept && state.draft.attachmentDrafts.none { it.id == file.id })
            media.discardUnpublishedImport(file)
    }

    private fun preview(file: AttachmentDraft, media: MediaStore) {
        val attachment =
            Attachment(
                file.id,
                file.relativePath,
                file.mime,
                file.byteSize,
                file.rawWidth,
                file.rawHeight,
                file.orientation,
                file.durationMs,
                file.thumbnailKey,
                file.tinyPreview,
            )
        val duration = file.durationMs ?: 0
        val eligible =
            duration in 1000..5100 &&
                minOf(file.rawWidth, file.rawHeight) >= 128 &&
                maxOf(file.rawWidth, file.rawHeight) <= 960 &&
                file.rawWidth * file.rawHeight <= 960 * 540
        val enhance: (() -> Unit)? =
            if (eligible && network != null) {
                {
                    val pending = MediaCancellation()
                    pendingImport = pending
                    refresh()
                    network.studioModels { result ->
                        if (pendingImport === pending) pendingImport = null
                        val active = pending.finish() && isActive()
                        if (active && result.getOrNull()?.any { it.id == "video_sr" } == true) {
                            replaceSource(file, "video_sr")
                        } else {
                            media.discardUnpublishedImport(file)
                            if (active) showError("Улучшение видео сейчас недоступно")
                        }
                        refresh()
                    }
                }
            } else null
        previewVideo(attachment, enhance) { media.discardUnpublishedImport(file) }
    }
}
