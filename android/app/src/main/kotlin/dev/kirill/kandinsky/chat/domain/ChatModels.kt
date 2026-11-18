// Описывает сообщения, вложения, ход генерации и местные команды Андроида. Эти данные переходят
// между базой, службой обмена и экраном без повторного толкования полей.

package dev.kirill.kandinsky.chat.domain
enum class MessageKind {
    TEXT,
    PHOTO,
    VIDEO,
    VOICE,
    ROUND,
    GIF,
    STICKER,
}

enum class MessageDirection {
    INCOMING,
    OUTGOING,
}

enum class MessageDelivery {
    LOCAL,
    PENDING,
    ACCEPTED,
    DELIVERED,
    READ,
    FAILED,
}

enum class MessageChangeReason {
    LOCAL_SEND,
    SYNC,
    HISTORY,
}

data class OrderKey(val sortKey: Long, val messageID: MessageID) : Comparable<OrderKey> {
    // Сравнивает строки по ключу порядка, затем по номеру сообщения при равном ключе. Это
    // сохраняет обе строки на границе страницы, даже когда время совпало.
    override fun compareTo(other: OrderKey): Int =
        if (sortKey == other.sortKey) messageID.compareTo(other.messageID)
        else sortKey.compareTo(other.sortKey)
}

data class ChatSummary(
    val id: ChatID,
    val title: String,
    val lastPreview: String,
    val lastSortKey: Long,
    val lastSavedAtMs: Long,
    val unreadCount: Int,
    val pinned: Boolean,
    val archived: Boolean = false,
    val muted: Boolean = false,
    val markedUnread: Boolean = false,
    val hidden: Boolean = false,
)

data class Attachment(
    val id: String,
    val relativePath: String,
    val mime: String,
    val byteSize: Long,
    val rawWidth: Int,
    val rawHeight: Int,
    val orientation: Int,
    val durationMs: Long?,
    val thumbnailKey: String,
    val tinyPreview: String? = null,
    val waveform: List<Int>? = null,
)

data class AttachmentDraft(
    val id: String,
    val relativePath: String,
    val mime: String,
    val byteSize: Long,
    val rawWidth: Int = 0,
    val rawHeight: Int = 0,
    val orientation: Int = 1,
    val durationMs: Long? = null,
    val thumbnailKey: String = "",
    val tinyPreview: String? = null,
    val waveform: List<Int>? = null,
)

data class Message(
    val id: MessageID,
    val chatID: ChatID,
    val order: OrderKey,
    val direction: MessageDirection,
    val savedAtMs: Long,
    val kind: MessageKind,
    val text: String,
    val caption: String,
    val replyToID: MessageID?,
    val revision: Int,
    val attachments: List<Attachment>,
    val delivery: MessageDelivery = MessageDelivery.LOCAL,
    val studioGeneration: StudioGeneration? = null,
) {
    val canReportStudioMedia
        // Разрешает жалобу на входящий снимок или ролик с одним вложением.
        get() =
            direction == MessageDirection.INCOMING &&
                attachments.size == 1 &&
                (kind == MessageKind.PHOTO || kind == MessageKind.VIDEO)
}

enum class StudioReportReason(val wire: String, val title: String) {
    INAPPROPRIATE("inappropriate", "Недопустимое содержание"),
    HARMFUL("harmful", "Опасное или вредное содержание"),
    OTHER("other", "Другая причина"),
}

enum class StudioGenerationAction(val title: String) {
    CHECK("Проверить"),
    CANCEL("Отмена"),
    RETRY("Повторить"),
}

data class StudioGeneration(
    val stage: Stage,
    val model: String?,
    val aspect: String?,
    val startedAtMs: Long?,
    val error: String?,
    val moderated: Boolean = false,
    val slots: List<Slot> = emptyList(),
    val progress: Int? = null,
) {
    enum class Stage {
        QUEUED,
        RUNNING,
        FAILED,
        CANCELLED,
        SUCCEEDED,
    }

    data class Slot(
        val id: String,
        val stage: Stage,
        val blobId: String?,
        val progress: Int?,
        val runId: String? = null,
        val moderated: Boolean = false,
    ) {
        val canRetry: Boolean
            // Позволяет повторить неудачный результат без файла, если проверка содержания его не блокирует.
            get() = stage == Stage.FAILED && !moderated && blobId == null
    }

    val isActive
        // Отмечает очередь и выполнение как активные состояния. Завершённая генерация не остаётся занятой.
        get() = stage == Stage.QUEUED || stage == Stage.RUNNING

    val isVideo
        // Определяет ролик и его улучшение, чтобы экран выбрал оформление видео.
        get() = model == "video" || model == "video_sr"

    val ratio: Float
        // Считает ширину к высоте и заменяет непригодное соотношение квадратом.
        get() {
            val parts = aspect?.split(':').orEmpty()
            if (parts.size != 2) return 1f
            val first = parts[0].toFloatOrNull() ?: return 1f
            val second = parts[1].toFloatOrNull() ?: return 1f
            return (first / second).takeIf {
                first > 0f && second > 0f && it.isFinite() && it in 0.1f..10f
            } ?: 1f
        }

    val failureText
        // Различает отмену и сбой. Без сообщения от службы предлагает повтор.
        get() =
            if (stage == Stage.CANCELLED) "Генерация отменена"
            else error?.takeIf(String::isNotBlank) ?: "Не удалось создать. Попробуйте ещё раз."

    companion object {
        val statusTexts =
            setOf(
                "Генерация продолжается",
                "В очереди",
                "Генерация",
                "Время вышло",
                "Не удалось создать",
                "Генерация отменена",
                "Генерация заблокирована после проверки",
            )
    }
}

data class KandinskyOptions(
    val model: String = "image",
    val aspect: String = "1:1",
    val seconds: Int = 5,
    val quality: String? = null,
    val resolution: String? = null,
    val count: Int? = null,
    val seed: Int? = null,
    val negativePrompt: String? = null,
    val guidanceScale: Double? = null,
)

data class StudioUpload(val blobId: String, val mime: String, val byteSize: Long) {
    val isVideo
        // Выбирает просмотр ролика по виду файла. Для снимка используется другое действие.
        get() = mime == "video/mp4"

    val title
        // Подписывает действие для загруженного файла. Ролик можно смотреть, а снимок выбрать как исходник.
        get() = if (isVideo) "Посмотреть загруженное видео" else "Использовать загруженное фото"
}

data class StudioDraftRecord(val identity: java.util.UUID, val draft: Draft) {
    val hasContent: Boolean
        // Считает черновик нужным при вводе, файлах, параметрах или ожидающей отправке.
        get() =
            draft.text.isNotBlank() ||
                draft.attachmentDrafts.isNotEmpty() ||
                draft.pendingSend != null ||
                !draft.pendingMediaBatch.isNullOrEmpty() ||
                draft.generationOptions != null
}

data class StudioTransfer(
    val id: String,
    val deviceId: String,
    val received: Long,
    val total: Long,
    val mime: String,
) {
    val isValid: Boolean
        // Проверяет номера, ограничения размера и положение счётчика, прежде чем показать передачу.
        get() =
            runCatching {
                    java.util.UUID.fromString(id).toString() == id.lowercase() &&
                        (deviceId.isEmpty() ||
                            java.util.UUID.fromString(deviceId).toString() ==
                                deviceId.lowercase()) &&
                        total in 1..20 * 1024 * 1024L &&
                        received in 0..total &&
                        mime.toByteArray().size <= 80
                }
                .getOrDefault(false)

    val progress: Double
        // Возвращает долю переданных байтов. Вызывающий код сначала проверяет счётчики.
        get() = received.toDouble() / total
}

data class StudioChoice(
    val id: String,
    val name: String,
    val sizes: Map<String, List<Int>> = emptyMap(),
) {
    // Показывает размеры нужного соотношения сторон, если они указаны в списке модели. В
    // остальных случаях остаётся знакомое название варианта.
    fun label(aspect: String) = sizes[aspect]?.joinToString(" × ") ?: name
}

data class StudioGuidance(val min: Double, val max: Double, val default: Double)

data class StudioCapabilities(
    val quality: List<StudioChoice>,
    val resolutions: List<StudioChoice>,
    val aspects: List<String>,
    val durations: List<Int>,
    val maxCount: Int,
    val negativePrompt: Boolean,
    val guidance: StudioGuidance?,
)

data class StudioModel(
    val id: String,
    val name: String,
    val description: String,
    val capabilities: StudioCapabilities?,
    val referenceCapabilities: StudioCapabilities?,
)

// Описывает одну отправку вместе с постоянным номером для безопасного повтора.
data class SendCommand(
    val operationID: String,
    val chatID: ChatID,
    val kind: MessageKind = MessageKind.TEXT,
    val text: String = "",
    val caption: String = "",
    val replyToID: MessageID? = null,
    val attachments: List<AttachmentDraft> = emptyList(),
    val generationOptions: KandinskyOptions? = null,
)

// Передаёт экрану изменённые строки и карточку вместо полной истории.
data class MessageDelta(
    val inserted: List<Message> = emptyList(),
    val updated: List<Message> = emptyList(),
    val removedIDs: List<MessageID> = emptyList(),
    val chat: ChatSummary,
    val reason: MessageChangeReason = MessageChangeReason.LOCAL_SEND,
)

// Хранит ввод, вложения и ожидающие команды до момента подтверждения отправки.
data class Draft(
    val text: String = "",
    val attachmentDrafts: List<AttachmentDraft> = emptyList(),
    val replyToID: MessageID? = null,
    val revision: Long = 0,
    val operationID: String? = null,
    val pendingSend: SendCommand? = null,
    val pendingRevision: Long? = null,
    val pendingMediaBatch: List<SendCommand>? = null,
    val generationOptions: KandinskyOptions? = null,
)

// Сохраняет строку, смещение и версию раскладки, чтобы открыть чат на прежнем месте.
data class ReadingAnchor(
    val messageID: MessageID? = null,
    val fraction: Double = 0.0,
    val textUTF16Offset: Int? = null,
    val screenDelta: Double = 0.0,
    val followsBottom: Boolean = true,
    val layoutVersion: Int = 1,
)

data class IndexedRow(
    val messageID: MessageID,
    val order: OrderKey,
    val estimatedHeight: Double,
    val revision: Int,
    val savedAtMs: Long? = null,
)
