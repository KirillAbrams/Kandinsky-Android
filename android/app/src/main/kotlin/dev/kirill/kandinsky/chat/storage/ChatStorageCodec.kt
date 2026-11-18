// Переводит черновики, вложения, команды и точки чтения в записи местной базы и разбирает их
// обратно. Также получает устойчивый отпечаток сообщения для повтора.

package dev.kirill.kandinsky.chat.storage

import dev.kirill.kandinsky.chat.domain.*
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal object ChatStorageCodec {
    // Читает необязательное целое, отличая отсутствующее поле от нуля. Без этого пустая ссылка
    // могла бы стать номером несуществующего сообщения.
    private fun nullable(o: JSONObject, key: String): Long? =
        if (o.isNull(key)) null else o.getLong(key)

    // Сохраняет название и свойства файла, размеры, поворот и доступные копии для просмотра.
    // После восстановления вложение остаётся привязано к тому же файлу.
    private fun attachment(a: AttachmentDraft) =
        JSONObject().apply {
            put("id", a.id)
            put("relativePath", a.relativePath)
            put("mime", a.mime)
            put("byteSize", a.byteSize)
            put("rawWidth", a.rawWidth)
            put("rawHeight", a.rawHeight)
            put("orientation", a.orientation)
            put("durationMs", a.durationMs ?: JSONObject.NULL)
            put("thumbnailKey", a.thumbnailKey)
            a.tinyPreview?.let { put("tinyPreview", it) }
            a.waveform?.let { put("waveform", JSONArray(it)) }
        }

    // Восстанавливает свойства вложения и проверяет маленькое изображение и точки звука. Для
    // полей, которых не было в старой версии, берёт безопасные значения по умолчанию.
    private fun decodeAttachment(o: JSONObject) =
        AttachmentDraft(
            o.getString("id"),
            o.getString("relativePath"),
            o.getString("mime"),
            o.getLong("byteSize"),
            o.optInt("rawWidth"),
            o.optInt("rawHeight"),
            o.optInt("orientation", 1),
            nullable(o, "durationMs"),
            o.optString("thumbnailKey", ""),
            MediaPreviewStorage.tiny(o.optString("tinyPreview").takeUnless { it == "null" }),
            MediaPreviewStorage.readWave(o.optJSONArray("waveform")?.toString()),
        )

    // Записывает текст, вложения, номер операции и состояние незавершённой отправки. После
    // перезапуска можно продолжить тот же ввод с теми же командами.
    fun draft(d: Draft): String =
        JSONObject()
            .apply {
                d.pendingMediaBatch?.let {
                    put(
                        "pendingMediaBatch",
                        JSONArray().apply { it.forEach { item -> put(command(item)) } },
                    )
                }
                put("pendingRevision", d.pendingRevision ?: JSONObject.NULL)
                put("pendingSend", d.pendingSend?.let { command(it) } ?: JSONObject.NULL)
                d.generationOptions?.let { put("generationOptions", generationOptions(it)) }
                put("text", d.text)
                put("revision", d.revision)
                put("operationID", d.operationID ?: JSONObject.NULL)
                put("replyToID", d.replyToID ?: JSONObject.NULL)
                put(
                    "attachmentDrafts",
                    JSONArray().apply { d.attachmentDrafts.forEach { put(attachment(it)) } },
                )
            }
            .toString()

    // Читает сохранённый ввод и список начатых действий. Если строка отсутствует или её формат
    // сломан, восстанавливает хотя бы старое текстовое поле.
    fun decodeDraft(json: String?, text: String): Draft {
        if (json == null) return Draft(text)
        return try {
            val o = JSONObject(json)
            val a = o.optJSONArray("attachmentDrafts") ?: JSONArray()
            val batch =
                o.optJSONArray("pendingMediaBatch")?.let { b ->
                    require(b.length() in 1..32)
                    (0 until b.length()).map { decodeCommand(b.getJSONObject(it)) }
                }
            Draft(
                o.optString("text", text),
                (0 until a.length()).map { decodeAttachment(a.getJSONObject(it)) },
                nullable(o, "replyToID"),
                o.optLong("revision"),
                if (o.isNull("operationID")) null else o.getString("operationID"),
                if (o.isNull("pendingSend")) null
                else decodeCommand(o.getJSONObject("pendingSend")),
                nullable(o, "pendingRevision"),
                batch,
                o.optJSONObject("generationOptions")?.let { decodeGenerationOptions(it) },
            )
        } catch (t: org.json.JSONException) {
            Draft(text)
        }
    }

    // Записывает положение строки, смещение, связь с концом и версию раскладки. Эти поля
    // позволяют вернуться к тому же участку текста.
    fun anchor(a: ReadingAnchor): String =
        JSONObject()
            .apply {
                put("messageID", a.messageID ?: JSONObject.NULL)
                put("fraction", a.fraction)
                put("textUTF16Offset", a.textUTF16Offset ?: JSONObject.NULL)
                put("screenDelta", a.screenDelta)
                put("followsBottom", a.followsBottom)
                put("layoutVersion", a.layoutVersion)
            }
            .toString()

    // Восстанавливает точку чтения по всем сохранённым полям. Без доли строки или версии
    // раскладки экран мог бы восстановить неверное место.
    fun decodeAnchor(json: String): ReadingAnchor {
        val o = JSONObject(json)
        return ReadingAnchor(
            nullable(o, "messageID"),
            o.getDouble("fraction"),
            nullable(o, "textUTF16Offset")?.toInt(),
            o.getDouble("screenDelta"),
            o.getBoolean("followsBottom"),
            o.getInt("layoutVersion"),
        )
    }

    // Превращает команду отправки в строку для местной очереди. Через неё номер операции и
    // параметры переживают перезапуск.
    fun commandString(command: SendCommand): String = command(command).toString()

    // Читает одну команду из сохранённой строки. Не подменяет ошибки разбора пустой отправкой.
    fun readCommand(value: String): SendCommand = decodeCommand(JSONObject(value))

    // Превращает команды выбранных файлов в упорядоченный список для базы. Порядок нужен, чтобы
    // после восстановления вложения отправились как были выбраны.
    fun mediaBatch(commands: List<SendCommand>): String =
        JSONArray().apply { commands.forEach { put(command(it)) } }.toString()

    // Проверяет число команд и вложений в каждой перед чтением. Иначе слишком большая или пустая
    // группа могла бы попасть в восстановление отправки.
    fun readMediaBatch(value: String): List<SendCommand> {
        val array = JSONArray(value)
        if (array.length() !in 1..32) throw org.json.JSONException("Invalid selection count")
        return (0 until array.length()).map { index ->
            val objectValue = array.getJSONObject(index)
            if (objectValue.getJSONArray("attachments").length() !in 1..10)
                throw org.json.JSONException("Invalid selection attachments")
            decodeCommand(objectValue)
        }
    }

    // Записывает выбранную модель, вид кадра, число результатов и остальные настройки. Очередь
    // получает именно те условия, которые выбрал пользователь.
    fun generationOptions(value: KandinskyOptions) =
        JSONObject().apply {
            put("model", value.model)
            put("aspect", value.aspect)
            put("duration_seconds", value.seconds)
            put("quality", value.quality)
            put("resolution", value.resolution)
            put("count", value.count)
            put("seed", value.seed)
            put("negative_prompt", value.negativePrompt)
            put("guidance_scale", value.guidanceScale)
        }

    // Собирает все поля одной отправки, включая номер чата, вложения и настройки генерации. После
    // восстановления команда ещё знает, что и куда отправлять.
    private fun command(c: SendCommand) =
        JSONObject().apply {
            put("operationID", c.operationID)
            put("chatID", c.chatID)
            put("kind", c.kind.name)
            put("text", c.text)
            put("caption", c.caption)
            put("replyToID", c.replyToID ?: JSONObject.NULL)
            put("attachments", JSONArray().apply { c.attachments.forEach { put(attachment(it)) } })
            c.generationOptions?.let { put("generationOptions", generationOptions(it)) }
        }

    // Восстанавливает обязательные настройки и сохраняет отсутствие дополнительных значений.
    // Незаполненный параметр не превращается в придуманное значение.
    private fun decodeGenerationOptions(value: JSONObject) =
        KandinskyOptions(
            value.getString("model"),
            value.getString("aspect"),
            value.getInt("duration_seconds"),
            quality = if (value.isNull("quality")) null else value.getString("quality"),
            resolution = if (value.isNull("resolution")) null else value.getString("resolution"),
            count = if (value.isNull("count")) null else value.getInt("count"),
            seed = if (value.isNull("seed")) null else value.getInt("seed"),
            negativePrompt =
                if (value.isNull("negative_prompt")) null else value.getString("negative_prompt"),
            guidanceScale =
                if (value.isNull("guidance_scale")) null else value.getDouble("guidance_scale"),
        )

    // Собирает отправку из номера, текста, файлов, ответа и условий генерации. Если обязательного
    // поля нет, сообщает ошибку вызывающему коду.
    private fun decodeCommand(o: JSONObject): SendCommand {
        val a = o.getJSONArray("attachments")
        val options = o.optJSONObject("generationOptions")?.let { decodeGenerationOptions(it) }
        return SendCommand(
            o.getString("operationID"),
            o.getLong("chatID"),
            MessageKind.valueOf(o.getString("kind")),
            o.getString("text"),
            o.getString("caption"),
            nullable(o, "replyToID"),
            (0 until a.length()).map { decodeAttachment(a.getJSONObject(it)) },
            options,
        )
    }

    // Сортирует имена полей, последовательно обрабатывает элементы списка и приводит значения к
    // одному текстовому виду. От перестановки полей отпечаток тогда не изменится.
    private fun canonical(v: Any?): String =
        when (v) {
            null,
            JSONObject.NULL -> "null"
            is JSONObject ->
                v.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
                    JSONObject.quote(it) + ":" + canonical(v.get(it))
                }
            is JSONArray ->
                (0 until v.length()).joinToString(",", "[", "]") { canonical(v.get(it)) }
            is String -> JSONObject.quote(v)
            else -> v.toString()
        }

    // Получает отпечаток номера чата, текста, подписи, ответа и исходных свойств вложений.
    // Маленькие изображения и форма звука не влияют на сравнение сохранённого повтора.
    fun payloadHash(c: SendCommand): String {
        val value =
            JSONObject().apply {
                put("chatID", c.chatID)
                put("kind", c.kind.name.lowercase(Locale.ROOT))
                put("text", c.text)
                put("caption", c.caption)
                put("replyToID", c.replyToID ?: JSONObject.NULL)
                put(
                    "attachments",
                    JSONArray().apply {
                        c.attachments.forEach {
                            put(
                                attachment(it).apply {
                                    remove("tinyPreview")
                                    remove("waveform")
                                }
                            )
                        }
                    },
                )
            }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical(value).toByteArray(Charsets.UTF_8))
            .joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 255) }
    }
}
