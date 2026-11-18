package dev.kirill.kandinsky.chat.storage

import android.database.sqlite.SQLiteDatabase
import dev.kirill.kandinsky.chat.domain.*
import java.util.Locale
import java.util.UUID

/**
 * Хранит намерение выбора вложений отдельно от обычного черновика
 * Выбор сохраняется, даже если прежний клиент перепишет только известные ему поля ввода
 */
internal object MediaSelectionStorage {
 private const val MAXIMUM_PAYLOAD_BYTES=1_048_576
 // Связь с чатом удаляет такое намерение вместе с удалением его местной записи
 fun install(db:SQLiteDatabase){db.execSQL("CREATE TABLE IF NOT EXISTS media_selection_intents(chatID INTEGER PRIMARY KEY REFERENCES chats(id) ON DELETE CASCADE,payload TEXT NOT NULL)")}
 /**
  * Требует разные корректные номера операций, совпадение чата у каждой отправки и от одного до десяти вложений в ней
  * Пути файлов должны быть относительными, без пустых частей и переходов к текущей или родительской папке
  */
 private fun valid(commands:List<SendCommand>,chatID:Long):Boolean = chatID>0 && commands.size in 1..32 && commands.sumOf{it.attachments.size}<=32 && commands.map{it.operationID.lowercase(Locale.ROOT)}.toSet().size==commands.size && commands.all{command->
  command.chatID==chatID && runCatching{UUID.fromString(command.operationID).toString().equals(command.operationID,ignoreCase=true)}.getOrDefault(false) && command.attachments.size in 1..10 && command.attachments.all{asset->!asset.relativePath.startsWith("/") && asset.relativePath.split('/').none{it.isEmpty() || it=="." || it==".."}}
 }
 /**
  * Ограничивает объём сохранённого описания одним мегабайтом до его разбора в список отправок
  * Проверяет полученную группу общими правилами выбора и сообщает повреждение при ошибке записи или нарушении ограничений
  */
 private fun decode(payload:String,chatID:Long):List<SendCommand>{
  if(payload.toByteArray(Charsets.UTF_8).size>MAXIMUM_PAYLOAD_BYTES)throw RepoException(RepoError.CORRUPT)
  val commands=try{ChatStorageCodec.readMediaBatch(payload)}catch(_:org.json.JSONException){throw RepoException(RepoError.CORRUPT)}catch(_:IllegalArgumentException){throw RepoException(RepoError.CORRUPT)}
  if(!valid(commands,chatID))throw RepoException(RepoError.CORRUPT)
  return commands
 }
 /**
  * Находит сохранённое намерение выбора вложений для одного чата
  * При наличии записи возвращает проверенную группу отправок, при отсутствии возвращает отсутствие незавершённого выбора
  */
 fun load(db:SQLiteDatabase,chatID:Long):List<SendCommand>?=db.rawQuery("SELECT payload FROM media_selection_intents WHERE chatID=?",arrayOf(chatID.toString())).use{if(it.moveToFirst())decode(it.getString(0),chatID)else null}
 /**
  * Удаляет запись намерения, если выбор уже завершён или снят
  * Для новой группы проверяет номера, состав и пути, ограничивает описание одним мегабайтом и сохраняет его целиком по номеру чата
  */
 fun save(db:SQLiteDatabase,chatID:Long,commands:List<SendCommand>?){
  if(commands==null){db.execSQL("DELETE FROM media_selection_intents WHERE chatID=?",arrayOf(chatID));return}
  if(!valid(commands,chatID))throw RepoException(RepoError.INVALID_INPUT)
  val payload=ChatStorageCodec.mediaBatch(commands)
  if(payload.toByteArray(Charsets.UTF_8).size>MAXIMUM_PAYLOAD_BYTES)throw RepoException(RepoError.INVALID_INPUT)
  db.execSQL("INSERT OR REPLACE INTO media_selection_intents(chatID,payload) VALUES(?,?)",arrayOf(chatID,payload))
 }
 /**
  * Читает все сохранённые намерения выбора и собирает разные относительные пути их исходных файлов
  * Обходит всю таблицу, поскольку очистка не должна принять неполную страницу за полный список ещё используемых оригиналов
  * Повреждённое намерение останавливает чтение вместо разрешения удалить потенциально нужный файл
  */
 fun references(db:SQLiteDatabase):Set<String>{
  val paths=mutableSetOf<String>()
  db.rawQuery("SELECT chatID,payload FROM media_selection_intents",null).use{c->while(c.moveToNext())for(command in decode(c.getString(1),c.getLong(0)))for(asset in command.attachments)paths+=asset.relativePath}
  return paths
 }
}
