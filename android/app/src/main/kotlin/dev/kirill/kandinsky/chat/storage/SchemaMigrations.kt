package dev.kirill.kandinsky.chat.storage
import android.database.sqlite.SQLiteDatabase
import dev.kirill.kandinsky.chat.domain.*
import java.util.Locale
object SchemaMigrations {
 const val VERSION=3
 private val legacy=listOf(
 "CREATE TABLE IF NOT EXISTS chats(id INTEGER PRIMARY KEY,title TEXT NOT NULL,avatarKind TEXT NOT NULL DEFAULT 'initial',lastMessageId INTEGER,updatedSortKey INTEGER NOT NULL DEFAULT 0,unreadCount INTEGER NOT NULL DEFAULT 0,pinned INTEGER NOT NULL DEFAULT 0)",
 "CREATE TABLE IF NOT EXISTS messages(id INTEGER PRIMARY KEY AUTOINCREMENT,chatId INTEGER NOT NULL REFERENCES chats(id) ON DELETE CASCADE,sortKey INTEGER NOT NULL,kind TEXT NOT NULL,text TEXT NOT NULL DEFAULT '',caption TEXT NOT NULL DEFAULT '',replyToId INTEGER REFERENCES messages(id) ON DELETE SET NULL,revision INTEGER NOT NULL DEFAULT 1,savedAt INTEGER NOT NULL)",
 "CREATE INDEX IF NOT EXISTS messages_chat_sort ON messages(chatId,sortKey,id)",
 "CREATE TABLE IF NOT EXISTS attachments(id TEXT PRIMARY KEY,messageId INTEGER NOT NULL REFERENCES messages(id) ON DELETE CASCADE,relativePath TEXT NOT NULL,mime TEXT NOT NULL,byteSize INTEGER NOT NULL,width INTEGER NOT NULL DEFAULT 0,height INTEGER NOT NULL DEFAULT 0,durationMs INTEGER,thumbnailKey TEXT NOT NULL DEFAULT '')",
 "CREATE INDEX IF NOT EXISTS attachments_message ON attachments(messageId)",
 "CREATE TABLE IF NOT EXISTS drafts(chatId INTEGER PRIMARY KEY REFERENCES chats(id) ON DELETE CASCADE,text TEXT NOT NULL,attachmentDraft TEXT)",
 "CREATE TABLE IF NOT EXISTS reading_positions(chatId INTEGER PRIMARY KEY REFERENCES chats(id) ON DELETE CASCADE,messageId INTEGER,offsetWithin TEXT NOT NULL,followsBottom INTEGER NOT NULL,layoutVersion INTEGER NOT NULL)",
 "CREATE TABLE IF NOT EXISTS metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
 /**
  * Для прежних версий в одной сделке создаёт начальные таблицы и последовательно добавляет поля направления, поиска, черновиков и доставки
  * Заполняет поисковый текст ограниченными порциями, затем сохраняет номер завершённой версии
  * Таблицы уменьшенных копий, просмотров и незавершённого выбора обеспечивает и при уже актуальном основном номере схемы
  */
 fun apply(db:SQLiteDatabase) {
  val old=db.version
  if(old>VERSION)throw RepoException(RepoError.CORRUPT)
  if(old==VERSION){MediaSelectionStorage.install(db);db.execSQL("CREATE TABLE IF NOT EXISTS media_previews(attachmentID TEXT PRIMARY KEY REFERENCES attachments(id) ON DELETE CASCADE,tinyPreview TEXT,waveform TEXT)");db.execSQL("CREATE TABLE IF NOT EXISTS media_preview_catalog(key TEXT PRIMARY KEY,tinyPreview TEXT,waveform TEXT)");db.execSQL("CREATE TABLE IF NOT EXISTS message_content(messageID INTEGER PRIMARY KEY REFERENCES messages(id) ON DELETE CASCADE,consumed INTEGER NOT NULL DEFAULT 0 CHECK(consumed IN (0,1)))");return}
  db.beginTransaction()
  try {
   if(old<1)legacy.forEach(db::execSQL)
   if(old<2) {
    db.execSQL("ALTER TABLE messages ADD COLUMN direction TEXT NOT NULL DEFAULT 'incoming'")
    db.execSQL("ALTER TABLE messages ADD COLUMN searchText TEXT NOT NULL DEFAULT ''")
    db.execSQL("ALTER TABLE attachments ADD COLUMN orientation INTEGER NOT NULL DEFAULT 1")
    db.execSQL("ALTER TABLE drafts ADD COLUMN revision INTEGER NOT NULL DEFAULT 0")
    db.execSQL("CREATE TABLE operations(token TEXT PRIMARY KEY,payloadHash TEXT NOT NULL,messageId INTEGER NOT NULL)")
    var last=0L
    while(true){
     val batch=ArrayList<Triple<Long,String,String>>();var bytes=0
     db.rawQuery("SELECT id,text,caption FROM messages WHERE id>? ORDER BY id LIMIT 128",arrayOf(last.toString())).use{c->
      while(c.moveToNext()){
       val row=Triple(c.getLong(0),c.getString(1),c.getString(2));val size=(row.second.length+row.third.length)*2
       if(batch.isNotEmpty() && bytes+size>8*1024*1024)break
       batch.add(row);bytes+=size
      }
     }
     if(batch.isEmpty())break
     for(row in batch){last=row.first;db.execSQL("UPDATE messages SET searchText=? WHERE id=?",arrayOf((row.second+" "+row.third).lowercase(Locale.ROOT),row.first))}
    }
   }
   if(old<3)db.execSQL("ALTER TABLE messages ADD COLUMN deliveryState TEXT NOT NULL DEFAULT 'local'")
   db.execSQL("INSERT OR REPLACE INTO metadata(key,value) VALUES('schema','3')")
   db.execSQL("CREATE TABLE IF NOT EXISTS media_previews(attachmentID TEXT PRIMARY KEY REFERENCES attachments(id) ON DELETE CASCADE,tinyPreview TEXT,waveform TEXT)")
   db.execSQL("CREATE TABLE IF NOT EXISTS media_preview_catalog(key TEXT PRIMARY KEY,tinyPreview TEXT,waveform TEXT)");db.execSQL("CREATE TABLE IF NOT EXISTS message_content(messageID INTEGER PRIMARY KEY REFERENCES messages(id) ON DELETE CASCADE,consumed INTEGER NOT NULL DEFAULT 0 CHECK(consumed IN (0,1)))")
   MediaSelectionStorage.install(db)
   db.version=VERSION;db.setTransactionSuccessful()
  }finally{db.endTransaction()}
 }
}
