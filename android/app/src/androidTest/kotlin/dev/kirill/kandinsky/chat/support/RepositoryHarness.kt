package dev.kirill.kandinsky.chat.support
import android.database.sqlite.SQLiteDatabase
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.storage.*
import java.io.File

class RepositoryHarness private constructor(val root:File,val store:SQLiteStore,val faults:RepositoryFaults) {
 val repo=SQLiteChatRepository(store)
 companion object {
  /**
   * Создаёт в переданной папке базу прежней схемы из заранее записанного набора команд
   * Добавляет известное сообщение, черновик и старый номер версии независимо от текущей процедуры обновления
   */
  fun createLegacy(root:File){
   root.mkdirs()
   val sql="CREATE TABLE chats(id INTEGER PRIMARY KEY,title TEXT NOT NULL,avatarKind TEXT NOT NULL,lastMessageId INTEGER,updatedSortKey INTEGER NOT NULL,unreadCount INTEGER NOT NULL,pinned INTEGER NOT NULL);\nCREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT,chatId INTEGER NOT NULL,sortKey INTEGER NOT NULL,kind TEXT NOT NULL,text TEXT NOT NULL,caption TEXT NOT NULL,replyToId INTEGER,revision INTEGER NOT NULL,savedAt INTEGER NOT NULL);\nCREATE TABLE attachments(id TEXT PRIMARY KEY,messageId INTEGER NOT NULL,relativePath TEXT NOT NULL,mime TEXT NOT NULL,byteSize INTEGER NOT NULL,width INTEGER NOT NULL,height INTEGER NOT NULL,durationMs INTEGER,thumbnailKey TEXT NOT NULL);\nCREATE TABLE drafts(chatId INTEGER PRIMARY KEY,text TEXT NOT NULL,attachmentDraft TEXT);\nCREATE TABLE reading_positions(chatId INTEGER PRIMARY KEY,messageId INTEGER,offsetWithin TEXT NOT NULL,followsBottom INTEGER NOT NULL,layoutVersion INTEGER NOT NULL);\nCREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL);\nCREATE INDEX messages_chat_sort ON messages(chatId,sortKey,id);\nCREATE INDEX attachments_message ON attachments(messageId);\nINSERT INTO chats VALUES(2,'Alica','initial',7,10,3,0);\nINSERT INTO messages VALUES(7,2,10,'text','Ранее 👋','',NULL,1,10);\nINSERT INTO drafts VALUES(2,'Старый черновик',NULL);\nINSERT INTO metadata VALUES('schema','1');\nPRAGMA user_version=1;"
   SQLiteDatabase.openOrCreateDatabase(File(root,"chat.sqlite3"),null).use{db->sql.split(";").filter{it.isNotBlank()}.forEach{db.execSQL(it)}}
  }
  /**
   * Открывает производственное местное хранилище в отдельной проверочной папке и дожидается его готовности
   * Сохраняет исходный чат при его отсутствии и возвращает оболочку с теми же базой, операциями и управлением отказами
   */
  fun open(root:File,faults:RepositoryFaults=RepositoryFaults()):RepositoryHarness {
   val store=awaitResult<SQLiteStore>{SQLiteStore.open(root,faults,it)}
   awaitResult<Unit>{cb->store.submit({db->db.execSQL("INSERT OR IGNORE INTO chats(id,title,avatarKind,updatedSortKey,unreadCount,pinned) VALUES(2,'Alica','initial',0,3,0)")},cb)}
   return RepositoryHarness(root,store,faults)
  }
 }
 /**
  * Вызывает обычное закрытие хранилища и дожидается его подтверждения в проверочном потоке
  * Это позволяет следующему действию открыть тот же файл уже после окончания прежнего владельца соединения
  */
 fun close(){awaitResult<Unit>{store.close(it)}}
 /**
  * Закрывает текущее хранилище и создаёт новое в той же папке с тем же управлением отказами
  * Так сценарий проверяет восстановление из сохранённого файла после повторного открытия
  */
 fun reopen():RepositoryHarness {close();return open(root,faults)}
 /**
  * Считает строки выбранного чата отдельным запросом через независимое соединение базы
  * Возвращает количество для сверки фактического сохранения без повторения метода выдачи сообщений приложению
  */
 fun messageCount(chatID:Long):Int = rawScalar("SELECT COUNT(*) FROM messages WHERE chatId=$chatID")
 /**
  * Открывает файл проверочной базы только для чтения и выполняет переданный запрос независимо от хранилища
  * Возвращает первое целое поле и закрывает ответ и соединение после завершения
  */
 fun rawScalar(sql:String):Int = SQLiteDatabase.openDatabase(File(root,"chat.sqlite3").path,null,SQLiteDatabase.OPEN_READONLY).use{db->db.rawQuery(sql,null).use{it.moveToFirst();it.getInt(0)}}
 /**
  * Через отдельное соединение только для чтения вызывает встроенную проверку целостности базы
  * Возвращает её текстовый результат, закрывая ответ и соединение по завершении чтения
  */
 fun integrity():String = SQLiteDatabase.openDatabase(File(root,"chat.sqlite3").path,null,SQLiteDatabase.OPEN_READONLY).use{db->db.rawQuery("PRAGMA integrity_check",null).use{it.moveToFirst();it.getString(0)}}
}
