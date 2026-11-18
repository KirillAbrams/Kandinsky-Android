// Разделяет чтение местной базы, загрузку файлов и запись полученных номеров. Потеря ответа не
// должна удалять сохранённые сведения о уже переданном вложении.

package dev.kirill.kandinsky.chat.network

import android.database.sqlite.SQLiteDatabase
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.storage.ChatStorageCodec
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal class OutgoingMessagePayloads(private val sync: NetworkSync) {
    private data class PayloadReferences(
        val uploads: Map<String, Pair<String, String?>>,
        val replies: Map<Long, Long>,
    )

    // Читает связи из базы, закрывает это чтение до сетевой работы и отбирает доступные ответы.
    // Перед возвратом записывает номера загруженных файлов, чтобы повтор мог использовать их.
    fun prepare(commands: List<SendCommand>, completion: (Result<List<JSONObject>>) -> Unit) {
        // Сначала читаем ссылки из базы. Пока загружаются файлы, транзакция уже закрыта.
        sync.database({ db -> readReferences(db, commands) }) { selected ->
            val references =
                selected.getOrElse {
                    completion(Result.failure(it))
                    return@database
                }
            sync.http({
                commands
                    .filter { it.replyToID == null || it.replyToID in references.replies }
                    .map { messagePayload(it, references) }
            }) { prepared ->
                val messages =
                    prepared.getOrElse {
                        completion(Result.failure(it))
                        return@http
                    }
                sync.database({ db -> saveUploads(db, messages) }) { saved ->
                    completion(saved.map { messages })
                }
            }
        }
    }

    // Собирает ранее выданные номера оригиналов и копий, а для ответов находит их номера сервера.
    // Без такой сверки отправка могла бы ссылаться на местную строку, о которой сервер ещё не
    // знает.
    private fun readReferences(db: SQLiteDatabase, commands: List<SendCommand>): PayloadReferences {
        val uploads = hashMapOf<String, Pair<String, String?>>()
        db.rawQuery("SELECT attachmentID,blobID,previewBlobID FROM net_uploads", null).use {
            while (it.moveToNext()) uploads[it.getString(0)] =
                it.getString(1) to (if (it.isNull(2)) null else it.getString(2))
        }
        val replies = hashMapOf<Long, Long>()
        for (command in commands) command.replyToID?.let { id ->
            db.rawQuery("SELECT serverID FROM net_messages WHERE localID=?", arrayOf(id.toString()))
                .use { if (it.moveToFirst() && !it.isNull(0)) replies[id] = it.getLong(0) }
        }
        return PayloadReferences(uploads, replies)
    }

    // Складывает вид сообщения, текст, вложения и параметры генерации в форму для сервера.
    // Использует прежние номера вместо повторной загрузки, когда файл уже был принят.
    private fun messagePayload(command: SendCommand, references: PayloadReferences): JSONObject {
        val attachments =
            command.attachments.map { attachment ->
                val upload =
                    references.uploads[attachment.id] ?: uploadAttachment(attachment, command)
                attachmentPayload(attachment, upload)
            }
        val reply =
            command.replyToID?.let { references.replies[it] ?: error("Reply is not accepted yet") }
        return JSONObject()
            .put("clientId", command.operationID.lowercase())
            .put("chatId", command.chatID)
            .put("kind", command.kind.name.lowercase())
            .put("text", command.text)
            .put("caption", command.caption)
            .put("replyToId", reply ?: JSONObject.NULL)
            .put("attachments", JSONArray(attachments))
            .apply {
                command.generationOptions?.let {
                    put("generationOptions", ChatStorageCodec.generationOptions(it))
                }
            }
    }

    // Отправляет оригинал из местной папки, при необходимости связывает его с чатом как исходник
    // генерации. Затем готовит копию с номером родителя, чтобы сервер мог проследить связь
    // файлов.
    private fun uploadAttachment(
        attachment: AttachmentDraft,
        command: SendCommand,
    ): Pair<String, String?> {
        val file = File(sync.store.root, "media/" + attachment.relativePath)
        val source =
            command.generationOptions != null &&
                (attachment.mime.startsWith("image/") || attachment.mime == "video/mp4")
        val result =
            sync.api.upload(file, attachment.mime, attachment.id.lowercase(), studioSource = source, chat = command.chatID)
        val blob = result.getString("blobId")
        return blob to NetworkPreview.upload(sync.store.root, attachment, sync.api, parent = blob)
    }

    // Прикладывает номера, размеры, поворот и другие сведения, нужные получателю. Без них лента
    // не сможет заранее выделить правильное место и выбрать подходящую копию.
    private fun attachmentPayload(
        attachment: AttachmentDraft,
        upload: Pair<String, String?>,
    ): JSONObject {
        return JSONObject()
            .put("id", attachment.id)
            .put("blobId", upload.first)
            .put("previewBlobId", upload.second ?: JSONObject.NULL)
            .put("mime", attachment.mime)
            .put("byteSize", attachment.byteSize)
            .put("rawWidth", attachment.rawWidth)
            .put("rawHeight", attachment.rawHeight)
            .put("orientation", attachment.orientation)
            .put("durationMs", attachment.durationMs ?: JSONObject.NULL)
            .put("thumbnailKey", attachment.thumbnailKey)
            .apply {
                attachment.tinyPreview?.let { put("tinyPreview", it) }
                attachment.waveform?.let { put("waveform", org.json.JSONArray(it)) }
            }
    }

    // Сохраняет номера всех обработанных вложений одной записью базы. После обрыва уже принятые
    // файлы можно найти по местным номерам.
    private fun saveUploads(db: SQLiteDatabase, messages: List<JSONObject>) {
        NetworkStore.transaction(db) {
            for (message in messages) {
                val attachments = message.getJSONArray("attachments")
                for (i in 0 until attachments.length()) {
                    val attachment = attachments.getJSONObject(i)
                    db.execSQL(
                        "INSERT OR REPLACE INTO net_uploads(attachmentID,blobID," +
                            "previewBlobID) " +
                            "VALUES(?,?,?)",
                        arrayOf(
                            attachment.getString("id"),
                            attachment.getString("blobId"),
                            attachment.optString("previewBlobId").takeUnless { it == "null" },
                        ),
                    )
                }
            }
        }
    }
}
