// Лента на андроиде отдельно описывает нарисованные строки системе доступности. Картинка, повтор
// генерации и видимый абзац получают свои координаты и подписи, поэтому ими можно управлять без
// точного касания.

package dev.kirill.kandinsky.chat.rendering

import android.graphics.*
import android.view.*
import dev.kirill.kandinsky.chat.R
import dev.kirill.kandinsky.chat.conversation.MessageAction
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.media.*
import kotlin.math.*

// Добавляем видимые элементы с устойчивыми именами и рамками. Для карточки различаем ход работы и
// доступный повтор, для текста оставляем подходящие действия меню. Лимит сдерживает число
// элементов на одном кадре.
internal fun ChatScrollHost.appendRowAccessibility(
    row: Message, resource: PreparedText, x: Float, rowY: Float,
    accessibility: MutableList<NativeAction>,
) {
    resource.mediaItemRects.forEachIndexed { itemIndex, rect ->
        val hit = RectF(rect).apply { offset(x, rowY) }
        if (
            playback?.currentMessageID != row.id &&
                hit.bottom > 0 &&
                hit.top < height &&
                accessibility.size < 128
        ) {
            val report =
                if (row.canReportStudioMedia && onStudioGenerationAction != null)
                    listOf(
                        NativeExtraAction(
                            R.id.message_report,
                            "Пожаловаться на результат",
                        ) {
                            onMessageLongPress?.invoke(row, hit)
                        }
                    )
                else emptyList()
            accessibility +=
                NativeAction(
                    1000 + accessibility.size,
                    "media.image." + row.id + (if (itemIndex == 0) "" else ".$itemIndex"),
                    if (row.kind == MessageKind.ROUND) "Воспроизвести кружочек"
                    else if (row.attachments[itemIndex].mime.startsWith("video/"))
                        "Воспроизвести видео"
                    else "Открыть фотографию",
                    hit,
                    action = { activateMedia(row, hit, itemIndex) },
                    customActions = report,
                )
        }
    }
    for (button in resource.mediaActions) {
        val hit = RectF(button.frame).apply { offset(x, rowY) }
        if (hit.bottom > 0 && hit.top < height && accessibility.size < 128) {
            val id =
                "media.action.${button.action.name.lowercase(java.util.Locale.ROOT)}.${row.id}"
            accessibility +=
                NativeAction(
                    1000 + accessibility.size,
                    id,
                    button.action.title,
                    hit,
                    action = { performMediaAction(button.action, row, hit) },
                )
        }
    }
    resource.generationCard?.let { card ->
        val image = card.image
        val indicators =
            if (card.isBatch) card.cells.filter { it.slot.blobId == null }
            else if (image != null)
                listOf(
                    StudioGenerationCard.Cell(
                        StudioGeneration.Slot(
                            "single",
                            card.generation.stage,
                            null,
                            card.generation.progress,
                        ),
                        image,
                    )
                )
            else emptyList()
        for (cell in indicators) {
            val hit = RectF(cell.frame).apply { offset(x, rowY) }
            if (hit.bottom > 0 && hit.top < height && accessibility.size < 128) {
                val retry =
                    cell.slot.takeIf {
                        card.retryCells.any { candidate -> candidate.slot.id == it.id }
                    }
                val label =
                    if (retry != null) "Повторить генерацию"
                    else
                        StudioGenerationCard.progressLabel(
                            cell.slot.stage,
                            cell.slot.progress,
                        )
                accessibility +=
                    NativeAction(
                        1000 + accessibility.size,
                        "generation.slot.${row.id}.${cell.slot.id}",
                        label,
                        hit,
                        button = retry != null,
                        action =
                            retry?.let { slot ->
                                {
                                    onStudioRetrySlot?.invoke(row.id, slot)
                                    Unit
                                }
                            },
                    )
            }
        }
        for (button in card.controls) {
            val hit = RectF(button.frame).apply { offset(x, rowY) }
            if (hit.bottom > 0 && hit.top < height && accessibility.size < 128) {
                val id =
                    "generation.action.${button.action.name.lowercase(java.util.Locale.ROOT)}.${row.id}"
                accessibility +=
                    NativeAction(
                        1000 + accessibility.size,
                        id,
                        button.action.title,
                        hit,
                        action = { onStudioGenerationAction?.invoke(row.id, button.action) },
                    )
            }
        }
    }
    if (row.kind == MessageKind.VOICE) {
        if (playback?.currentMessageID != row.id) {
            val hit = RectF(x, rowY, x + resource.width, rowY + resource.height)
            accessibility +=
                NativeAction(
                    1000 + accessibility.size,
                    "media.audio." + row.id,
                    resource.text,
                    hit,
                    action = { onMessageTap?.invoke(row, hit) },
                )
        }
        return
    }
    for (line in
        resource.accessibleLines(
            max(0f, -rowY),
            height - rowY,
            max(0, 128 - accessibility.size),
        )) {
        val id =
            if (line.start == 0) "message." + row.id
            else "message." + row.id + ".text." + line.start
        val promptMenu =
            onStudioResultDelete != null && row.direction == MessageDirection.OUTGOING
        val lineFrame = RectF(line.frame).apply { offset(x, rowY) }
        accessibility +=
            NativeAction(
                1000 + accessibility.size,
                id,
                line.text,
                lineFrame,
                button = promptMenu,
                action =
                    if (promptMenu) {
                        {
                            onMessageLongPress?.invoke(row, lineFrame)
                            Unit
                        }
                    } else null,
                customActions =
                    if (onStudioGenerationAction != null) emptyList()
                    else
                        listOf(
                            NativeExtraAction(R.id.message_copy, "Копировать") {
                                onMessageAction?.invoke(row, MessageAction.COPY)
                            }
                        ),
                longClick =
                    if (onStudioGenerationAction != null) null
                    else {
                        {
                            onMessageLongPress?.invoke(
                                row,
                                RectF(x, rowY, x + resource.width, rowY + resource.height),
                            )
                            Unit
                        }
                    },
            )
    }
}
