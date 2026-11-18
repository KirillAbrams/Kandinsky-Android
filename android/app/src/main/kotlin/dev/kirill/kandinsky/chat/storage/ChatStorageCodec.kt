package dev.kirill.kandinsky.chat.storage
import dev.kirill.kandinsky.chat.domain.*
import org.json.JSONObject
import org.json.JSONArray
import java.security.MessageDigest
import java.util.Locale
internal object ChatStorageCodec {
 /**
  * Читает необязательное целое значение из сохранённой записи
  * Отсутствующее или явно пустое поле возвращает как отсутствие, а заданное значение передаёт обычному чтению числа
  */
 private fun nullable(o:JSONObject,key:String):Long?=if(o.isNull(key))null else o.getLong(key)
 /**
  * Записывает номер, местный путь, вид, размер и геометрию выбранного вложения
  * Сохраняет необязательную длительность и при наличии маленькую копию и рисунок дорожки для восстановления черновика
  */
 private fun attachment(a:AttachmentDraft)=JSONObject().apply {
  put("id",a.id);put("relativePath",a.relativePath);put("mime",a.mime);put("byteSize",a.byteSize)
  put("rawWidth",a.rawWidth);put("rawHeight",a.rawHeight);put("orientation",a.orientation)
  put("durationMs",a.durationMs ?: JSONObject.NULL);put("thumbnailKey",a.thumbnailKey)
  a.tinyPreview?.let{put("tinyPreview",it)};a.waveform?.let{put("waveform",JSONArray(it))}
 }
 /**
  * Восстанавливает выбранное вложение из сохранённых обязательных полей и необязательных сведений показа
  * Для прежней записи подставляет исходные геометрию и ориентацию, а маленькую копию и рисунок проверяет общей процедурой
  */
 private fun decodeAttachment(o:JSONObject)=AttachmentDraft(o.getString("id"),o.getString("relativePath"),o.getString("mime"),o.getLong("byteSize"),o.optInt("rawWidth"),o.optInt("rawHeight"),o.optInt("orientation",1),nullable(o,"durationMs"),o.optString("thumbnailKey",""),MediaPreviewStorage.tiny(o.optString("tinyPreview").takeUnless{it=="null"}),MediaPreviewStorage.readWave(o.optJSONArray("waveform")?.toString()))
 /**
  * Кодирует черновик с текстом, выбранными вложениями, ответом и версией ввода
  * Отдельно сохраняет номер операции, незавершённую отправку и группу медиа, чтобы восстановление знало уже начатое намерение
  */
 fun draft(d:Draft):String=JSONObject().apply {
  d.pendingMediaBatch?.let{put("pendingMediaBatch",JSONArray().apply{it.forEach{item->put(command(item))}})}
  put("pendingRevision",d.pendingRevision ?: JSONObject.NULL);put("pendingSend",d.pendingSend?.let{command(it)} ?: JSONObject.NULL);put("text",d.text);put("revision",d.revision);put("operationID",d.operationID ?: JSONObject.NULL)
  put("replyToID",d.replyToID ?: JSONObject.NULL);put("attachmentDrafts",JSONArray().apply{d.attachmentDrafts.forEach{put(attachment(it))}})
 }.toString()
 /**
  * Восстанавливает текст, вложения и состояние незавершённой отправки из сохранённого описания черновика
  * При отсутствии описания или ошибке его чтения сохраняет отдельно переданный текст в простом черновике
  * Вложенную группу отправок принимает только при допустимом количестве записей
  */
 fun decodeDraft(json:String?,text:String):Draft {
  if(json==null)return Draft(text)
  return try {
   val o=JSONObject(json);val a=o.optJSONArray("attachmentDrafts") ?: JSONArray()
   val batch=o.optJSONArray("pendingMediaBatch")?.let{b->require(b.length() in 1..32);(0 until b.length()).map{decodeCommand(b.getJSONObject(it))}}
   Draft(o.optString("text",text),(0 until a.length()).map{decodeAttachment(a.getJSONObject(it))},nullable(o,"replyToID"),o.optLong("revision"),if(o.isNull("operationID"))null else o.getString("operationID"),if(o.isNull("pendingSend"))null else decodeCommand(o.getJSONObject("pendingSend")),nullable(o,"pendingRevision"),batch)
  }catch(t:org.json.JSONException){Draft(text)}
 }
 /**
  * Кодирует место чтения по номеру сообщения, доле строки и дополнительному текстовому смещению
  * Сохраняет экранный сдвиг, следование концу истории и версию разметки для последующего восстановления прокрутки
  */
 fun anchor(a:ReadingAnchor):String=JSONObject().apply{
  put("messageID",a.messageID ?: JSONObject.NULL);put("fraction",a.fraction)
  put("textUTF16Offset",a.textUTF16Offset ?: JSONObject.NULL);put("screenDelta",a.screenDelta)
  put("followsBottom",a.followsBottom);put("layoutVersion",a.layoutVersion)
 }.toString()
 /**
  * Разбирает сохранённую опору прокрутки с необязательными номером сообщения и текстовым смещением
  * Остальные обязательные поля читает без выдуманных значений, передавая ошибку повреждённой записи вызывающему хранилищу
  */
 fun decodeAnchor(json:String):ReadingAnchor {
  val o=JSONObject(json)
  return ReadingAnchor(nullable(o,"messageID"),o.getDouble("fraction"),nullable(o,"textUTF16Offset")?.toInt(),o.getDouble("screenDelta"),o.getBoolean("followsBottom"),o.getInt("layoutVersion"))
 }
 /**
  * Превращает одну подготовленную отправку в текст для постоянной исходящей очереди
  * Состав полей и вложений поручает общей процедуре кодирования команды
  */
 fun commandString(command:SendCommand):String=command(command).toString()
 /**
  * Разбирает текст одной сохранённой команды в объект полей
  * Восстановление номера, чата, содержимого и настроек выполняет общей процедурой чтения отправки
  */
 fun readCommand(value:String):SendCommand=decodeCommand(JSONObject(value))
 /**
  * Кодирует список отправок медиа в массив в том же порядке, в котором они переданы
  * Каждую запись собирает общей процедурой команды, сохраняя постоянный номер и её вложения
  */
 fun mediaBatch(commands:List<SendCommand>):String=JSONArray().apply{commands.forEach{put(command(it))}}.toString()
 /**
  * Читает группу из одной до тридцати двух сохранённых отправок
  * Перед восстановлением каждой требует от одного до десяти вложений и сообщает повреждение при нарушении состава
  */
 fun readMediaBatch(value:String):List<SendCommand>{
  val array=JSONArray(value);if(array.length() !in 1..32)throw org.json.JSONException("Invalid selection count")
  return (0 until array.length()).map{index->val objectValue=array.getJSONObject(index);if(objectValue.getJSONArray("attachments").length() !in 1..10)throw org.json.JSONException("Invalid selection attachments");decodeCommand(objectValue)}
 }
 /**
  * Записывает модель, пропорции, длительность и необязательные настройки генерации под серверными именами полей
  * Так одно описание используется и в сохранённой команде, и при подготовке её сетевой отправки
  */
 fun generationOptions(value: KandinskyOptions) = JSONObject().apply {
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
 /**
  * Собирает одну отправку с постоянным номером операции, чатом, видом, текстом, подписью, ответом и вложениями
  * При наличии выбранной генерации добавляет её настройки, оставляя обычную отправку без такого поля
  */
 private fun command(c:SendCommand)=JSONObject().apply{
  put("operationID",c.operationID);put("chatID",c.chatID);put("kind",c.kind.name);put("text",c.text);put("caption",c.caption);put("replyToID",c.replyToID ?: JSONObject.NULL);put("attachments",JSONArray().apply{c.attachments.forEach{put(attachment(it))}})
  c.generationOptions?.let { put("generationOptions", generationOptions(it)) }
 }
 /**
  * Восстанавливает обязательные поля отправки и выбранные вложения из сохранённой записи
  * При наличии настроек генерации читает модель, пропорции, длительность и необязательные параметры без смены прежнего номера операции
  */
 private fun decodeCommand(o:JSONObject):SendCommand {
  val a=o.getJSONArray("attachments")
  val options = o.optJSONObject("generationOptions")?.let {
   KandinskyOptions(
    it.getString("model"), it.getString("aspect"), it.getInt("duration_seconds"),
    quality = if (it.isNull("quality")) null else it.getString("quality"),
    resolution = if (it.isNull("resolution")) null else it.getString("resolution"),
    count = if (it.isNull("count")) null else it.getInt("count"),
    seed = if (it.isNull("seed")) null else it.getInt("seed"),
    negativePrompt = if (it.isNull("negative_prompt")) null else it.getString("negative_prompt"),
    guidanceScale = if (it.isNull("guidance_scale")) null else it.getDouble("guidance_scale")
   )
  }
  return SendCommand(o.getString("operationID"),o.getLong("chatID"),MessageKind.valueOf(o.getString("kind")),o.getString("text"),o.getString("caption"),nullable(o,"replyToID"),(0 until a.length()).map{decodeAttachment(a.getJSONObject(it))},options)
 }
 /**
  * Приводит вложенные значения к устойчивому текстовому виду для сравнения содержимого
  * Поля объекта сортирует по именам, порядок массива сохраняет, а строки записывает с правильным экранированием
  */
 private fun canonical(v:Any?):String=when(v){
  null,JSONObject.NULL->"null"
  is JSONObject->v.keys().asSequence().toList().sorted().joinToString(",","{","}"){JSONObject.quote(it)+":"+canonical(v.get(it))}
  is JSONArray->(0 until v.length()).joinToString(",","[","]"){canonical(v.get(it))}
  is String->JSONObject.quote(v)
  else->v.toString()
 }
 /**
  * Считает отпечаток чата, вида, текста, подписи, ответа и описаний выбранных вложений
  * Из описаний вложений исключает маленькую копию и рисунок дорожки, которые не должны менять сравнение основного содержимого
  * Перед вычислением приводит поля к устойчивому порядку и возвращает отпечаток шестнадцатеричными знаками
  */
 fun payloadHash(c:SendCommand):String {
  val value=JSONObject().apply {
   put("chatID",c.chatID);put("kind",c.kind.name.lowercase(Locale.ROOT));put("text",c.text);put("caption",c.caption)
   put("replyToID",c.replyToID ?: JSONObject.NULL);put("attachments",JSONArray().apply{c.attachments.forEach{put(attachment(it).apply{remove("tinyPreview");remove("waveform")})}})
  }
  return MessageDigest.getInstance("SHA-256").digest(canonical(value).toByteArray(Charsets.UTF_8)).joinToString(""){String.format(Locale.ROOT,"%02x",it.toInt() and 255)}
 }
}
