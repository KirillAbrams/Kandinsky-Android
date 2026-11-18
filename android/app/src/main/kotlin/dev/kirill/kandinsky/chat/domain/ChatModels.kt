package dev.kirill.kandinsky.chat.domain
enum class MessageKind { TEXT,PHOTO,VIDEO,VOICE,ROUND,GIF,STICKER }
enum class MessageDirection { INCOMING,OUTGOING }
enum class MessageDelivery { LOCAL, PENDING, ACCEPTED, DELIVERED, READ, FAILED }
enum class MessageChangeReason { LOCAL_SEND, SYNC, HISTORY }
data class OrderKey(val sortKey:Long,val messageID:MessageID):Comparable<OrderKey> {
 /**
  * Сравнивает две позиции истории по основному ключу порядка
  * При совпадении ключей использует постоянный номер сообщения, чтобы порядок оставался однозначным
  */
 override fun compareTo(other:OrderKey):Int=if(sortKey==other.sortKey)messageID.compareTo(other.messageID) else sortKey.compareTo(other.sortKey)
}
data class ChatSummary(val id:ChatID,val title:String,val lastPreview:String,val lastSortKey:Long,val lastSavedAtMs:Long,val unreadCount:Int,val pinned:Boolean,val archived:Boolean=false,val muted:Boolean=false,val markedUnread:Boolean=false,val hidden:Boolean=false)
data class Attachment(val id:String,val relativePath:String,val mime:String,val byteSize:Long,val rawWidth:Int,val rawHeight:Int,val orientation:Int,val durationMs:Long?,val thumbnailKey:String,val tinyPreview:String?=null,val waveform:List<Int>?=null)
data class AttachmentDraft(val id:String,val relativePath:String,val mime:String,val byteSize:Long,val rawWidth:Int=0,val rawHeight:Int=0,val orientation:Int=1,val durationMs:Long?=null,val thumbnailKey:String="",val tinyPreview:String?=null,val waveform:List<Int>?=null)
data class Message(val id:MessageID,val chatID:ChatID,val order:OrderKey,val direction:MessageDirection,val savedAtMs:Long,val kind:MessageKind,val text:String,val caption:String,val replyToID:MessageID?,val revision:Int,val attachments:List<Attachment>,val delivery:MessageDelivery=MessageDelivery.LOCAL,val contentRead:Boolean=false,val studioGeneration:StudioGeneration?=null) {
    val canReportStudioMedia get()=direction==MessageDirection.INCOMING && attachments.size==1 &&
        (kind==MessageKind.PHOTO || kind==MessageKind.VIDEO)
}
enum class StudioReportReason(val wire:String,val title:String) {
    INAPPROPRIATE("inappropriate","Недопустимое содержание"),
    HARMFUL("harmful","Опасное или вредное содержание"),
    OTHER("other","Другая причина")
}
enum class StudioGenerationAction(val title:String){
    CHECK("Проверить"), CANCEL("Отмена"), RETRY("Повторить")
}
data class StudioGeneration(
    val stage:Stage,val model:String?,val aspect:String?,val startedAtMs:Long?,val error:String?,
    val moderated:Boolean=false,val slots:List<Slot> = emptyList(),val progress:Int?=null
){
    enum class Stage{QUEUED,RUNNING,FAILED,CANCELLED,SUCCEEDED}
    data class Slot(val id:String,val stage:Stage,val blobId:String?,val progress:Int?,val runId:String?=null,val moderated:Boolean=false) {
        val canRetry: Boolean get() = stage == Stage.FAILED && !moderated && blobId == null
    }
    val isActive get()=stage==Stage.QUEUED || stage==Stage.RUNNING
    val isVideo get()=model=="video" || model=="video_sr"
    val ratio:Float get(){
        val parts=aspect?.split(':').orEmpty()
        if(parts.size!=2)return 1f
        val first=parts[0].toFloatOrNull() ?: return 1f
        val second=parts[1].toFloatOrNull() ?: return 1f
        return (first/second).takeIf{first>0f && second>0f && it.isFinite() && it in 0.1f..10f} ?: 1f
    }
    val failureText get()=if(stage==Stage.CANCELLED)"Генерация отменена"
        else error?.takeIf(String::isNotBlank) ?: "Не удалось создать. Попробуйте ещё раз."

    companion object{
        val statusTexts=setOf("Генерация продолжается","В очереди","Генерация","Время вышло","Не удалось создать","Генерация отменена","Генерация заблокирована после проверки")
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
    val guidanceScale: Double? = null
)
data class StudioUpload(val blobId:String,val mime:String,val byteSize:Long){
    val isVideo get()=mime=="video/mp4"
    val title get()=if(isVideo)"Посмотреть загруженное видео" else "Использовать загруженное фото"
}
data class StudioChoice(val id: String, val name: String, val sizes: Map<String, List<Int>> = emptyMap()) {
    /**
     * Для выбранных пропорций ищет сохранённые стороны размера и объединяет их в подпись через знак умножения
     * Если такого размера в описании нет, возвращает обычное название варианта настройки
     */
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
    val guidance: StudioGuidance?
)
data class StudioModel(
    val id: String,
    val name: String,
    val description: String,
    val capabilities: StudioCapabilities?,
    val referenceCapabilities: StudioCapabilities?
)
data class SendCommand(val operationID:String,val chatID:ChatID,val kind:MessageKind=MessageKind.TEXT,val text:String="",val caption:String="",val replyToID:MessageID?=null,val attachments:List<AttachmentDraft> = emptyList(), val generationOptions: KandinskyOptions? = null)
data class MessageDelta(val inserted:List<Message> = emptyList(),val updated:List<Message> = emptyList(),val removedIDs:List<MessageID> = emptyList(),val chat:ChatSummary,val reason:MessageChangeReason=MessageChangeReason.LOCAL_SEND)
data class Draft(val text:String="",val attachmentDrafts:List<AttachmentDraft> = emptyList(),val replyToID:MessageID?=null,val revision:Long=0,val operationID:String?=null,val pendingSend:SendCommand?=null,val pendingRevision:Long?=null,val pendingMediaBatch:List<SendCommand>?=null)
data class ReadingAnchor(val messageID:MessageID?=null,val fraction:Double=0.0,val textUTF16Offset:Int?=null,val screenDelta:Double=0.0,val followsBottom:Boolean=true,val layoutVersion:Int=1)
data class IndexedRow(val messageID:MessageID,val order:OrderKey,val estimatedHeight:Double,val revision:Int,val savedAtMs:Long?=null)
