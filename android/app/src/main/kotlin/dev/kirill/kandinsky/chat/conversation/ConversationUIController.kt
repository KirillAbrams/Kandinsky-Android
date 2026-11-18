package dev.kirill.kandinsky.chat.conversation

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.storage.ChatRepository
import java.util.UUID

enum class MessageAction {
    COPY
}

class ConversationUIController(
    private val activity: Activity,
    chatID: ChatID,
    repository: ChatRepository,
    private val state: ConversationState,
    onDelta: (MessageDelta) -> Unit,
) {
    private var dialog: Dialog? = null
    private var closed = false
    private val actions =
        MessageActions(chatID, repository, state) { value ->
            (activity.getSystemService(Activity.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("Запрос", value))
        }
    var generationAction:
        ((MessageID, StudioGenerationAction, UUID?, (Result<Unit>) -> Unit) -> Unit)? =
        null
    var retrySlotAction:
        ((MessageID, StudioGeneration.Slot, UUID, (Result<Unit>) -> Unit) -> Unit)? =
        null
    var deleteGenerationContent: ((MessageID, String?, (Result<Unit>) -> Unit) -> Unit)? = null
    var reportGeneration: ((MessageID, StudioReportReason, (Result<Unit>) -> Unit) -> Unit)? = null
    private val generationActionsInFlight = mutableSetOf<MessageID>()

    init {
        actions.onDelta = onDelta
    }

    private fun canCancel(message: Message) =
        message.direction == MessageDirection.OUTGOING &&
            message.delivery in listOf(MessageDelivery.PENDING, MessageDelivery.FAILED)

    fun perform(kind: MessageAction, message: Message) {
        when (kind) {
            MessageAction.COPY -> actions.copy(message.id) { it.exceptionOrNull()?.let(::failed) }
        }
    }

    private fun confirmPendingRemoval(message: Message) {
        if (closed || activity.isDestroyed) return
        dialog =
            AlertDialog.Builder(activity)
                .setTitle("Отменить отправку?")
                .setMessage("Этот запрос не будет отправлен повторно.")
                .setNegativeButton("Оставить", null)
                .setPositiveButton("Отменить отправку") { _, _ ->
                    actions.delete(message.id) { it.exceptionOrNull()?.let(::failed) }
                }
                .show()
    }

    fun menu(message: Message) {

        if (message.direction == MessageDirection.OUTGOING) {
            val items = mutableListOf<KandinskySheetAction>()
            if (message.text.isNotEmpty() || message.caption.isNotEmpty())
                items +=
                    KandinskySheetAction("Копировать запрос", "copy") {
                        perform(MessageAction.COPY, message)
                    }
            if (canCancel(message))
                items +=
                    KandinskySheetAction("Отменить отправку", "trash", true) {
                        confirmPendingRemoval(message)
                    }
            else if (deleteGenerationContent != null)
                items +=
                    KandinskySheetAction("Удалить запрос", "trash", true) {
                        confirmPromptRemoval(message)
                    }
            if (items.isNotEmpty()) sheet(items = items)
            return
        }
        val report = reportGeneration ?: return
        if (!message.canReportStudioMedia || message.id in generationActionsInFlight) return
        sheet(
            "Пожаловаться на результат",
            StudioReportReason.entries.map { reason ->
                KandinskySheetAction(reason.title, "report") {
                    if (generationActionsInFlight.add(message.id)) {
                        report(message.id, reason) { result ->
                            generationActionsInFlight.remove(message.id)
                            if (!closed && !activity.isDestroyed) {
                                dialog =
                                    AlertDialog.Builder(activity)
                                        .setTitle(
                                            if (result.isSuccess) "Жалоба отправлена"
                                            else "Жалобу не удалось отправить"
                                        )
                                        .setMessage(
                                            if (result.isSuccess) null else "Попробуйте ещё раз."
                                        )
                                        .setPositiveButton("Понятно", null)
                                        .show()
                            }
                        }
                    }
                }
            },
        )
        return
    }

    fun retrySlot(message: MessageID, slot: StudioGeneration.Slot) {
        val request = retrySlotAction ?: return
        if (closed) return
        val operation = state.beginSlotRetry(slot) ?: return
        request(message, slot, operation) { result ->
            state.finishSlotRetry(slot.id)
            result.onFailure(::failed)
        }
    }

    // Один запрос на удаление может прийти с нескольких устройств.
    fun removeGenerationContent(id: MessageID, blob: String?) {
        val request = deleteGenerationContent ?: return
        if (closed || !generationActionsInFlight.add(id)) return
        request(id, blob) { result ->
            generationActionsInFlight.remove(id)
            result.onFailure(::failed)
        }
    }

    private fun confirmPromptRemoval(message: Message) {
        dialog =
            AlertDialog.Builder(activity)
                .setTitle("Удалить запрос?")
                .setMessage("Запрос и его результаты исчезнут на всех устройствах.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить") { _, _ -> removeGenerationContent(message.id, null) }
                .show()
    }

    fun performGenerationAction(id: MessageID, action: StudioGenerationAction) {
        val request = generationAction ?: return
        if (closed || !generationActionsInFlight.add(id)) return
        request(id, action, null) { result ->
            generationActionsInFlight.remove(id)
            result.onFailure(::failed)
        }
    }

    private fun failed(t: Throwable) {
        if (!closed && !activity.isDestroyed)
            Toast.makeText(
                    activity,
                    "Действие не выполнено. Сообщения и черновик сохранены.",
                    Toast.LENGTH_LONG,
                )
                .show()
    }

    private fun sheet(title: String? = null, items: List<KandinskySheetAction>) {
        if (closed || activity.isDestroyed) return
        activity.currentFocus?.let {
            (activity.getSystemService(Activity.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(it.windowToken, 0)
            it.clearFocus()
        }
        dialog?.dismiss()
        dialog = KandinskyActionSheet(activity, title, items).also { it.show() }
    }

    fun close() {
        closed = true
        dialog?.dismiss()
        dialog = null
    }
}
