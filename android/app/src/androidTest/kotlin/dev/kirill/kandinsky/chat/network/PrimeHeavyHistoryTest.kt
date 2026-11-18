package dev.kirill.kandinsky.chat.network
import androidx.test.platform.app.InstrumentationRegistry
import dev.kirill.kandinsky.chat.storage.*
import dev.kirill.kandinsky.chat.support.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import org.json.JSONObject
/** Явная подготовка измерения загружает настоящую серверную историю в существующую местную базу */
class PrimeHeavyHistoryTest {
 /**
  * Загружает настоящий заданный серверный чат последовательными ограниченными страницами в текущую местную базу
  * Каждая страница должна продвигать старую границу, а число страниц оставаться ограниченным
  * После достижения конца проверяет десять тысяч сохранённых сообщений и шесть видов содержимого
  * Записывает промежуточный ход и итог подготовки для отдельного измерения
  */
 @Test fun cacheTenThousandServerRowsInBoundedPages(){
  val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext
  val chatID=(InstrumentationRegistry.getArguments().getString("chat-id") ?: "4").toLong()
  val profile=checkNotNull(NetworkCredentials(context).load());val api=NetworkAPI(profile)
  val store=awaitResult<SQLiteStore>{cb->SQLiteStore.open(File(context.filesDir,"Kandinsky/net-v1-u"+profile.userID),callback=cb)}
  val local=SQLiteChatRepository(store,NetworkJournal(profile.userID));val cache=NetworkStore(local,profile.userID)
  var before:Long?=null;var pages=0;var received=0
  val output=File(context.getExternalFilesDir(null),"kandinsky-network-probe").apply{mkdirs()}
  try{
   while(true){
    val data=api.json("/v1/chats/$chatID/history?limit=128"+(before?.let{"&before="+it} ?: ""));val rows=data.getJSONArray("messages")
    assertTrue(rows.length() in 1..128)
    awaitResult<Unit>{cb->store.submit({db->cache.history(db,chatID,data);Unit},cb)}
    pages++;received+=rows.length();val next=(0 until rows.length()).minOf{rows.getJSONObject(it).getLong("seq")}
    assertTrue(before==null || next<before!!);before=next
    File(output,"prime-progress.json").writeText(JSONObject().put("pages",pages).put("received",received).put("oldestSeq",next).toString())
    if(!data.getBoolean("hasOlder"))break
    assertTrue("Pagination must terminate",pages<200)
   }
   val counts=awaitResult<Pair<Int,Int>>{cb->store.submit({db->
    val total=db.rawQuery("SELECT count(*) FROM messages WHERE chatId=?",arrayOf(chatID.toString())).use{it.moveToFirst();it.getInt(0)}
    val kinds=db.rawQuery("SELECT count(DISTINCT kind) FROM messages WHERE chatId=?",arrayOf(chatID.toString())).use{it.moveToFirst();it.getInt(0)};total to kinds
   },cb)}
   assertEquals(10000,counts.first);assertEquals(6,counts.second)
   File(output,"prime-summary.json").writeText(JSONObject().put("pages",pages).put("received",received).put("stored",counts.first).put("kinds",counts.second).put("source","HTTPS PostgreSQL history; <=128 messages per response").toString())
  }finally{api.close();awaitResult<Unit>{store.close(it)}}
 }
}
