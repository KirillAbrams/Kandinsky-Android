package dev.kirill.kandinsky.chat.network

import androidx.test.platform.app.InstrumentationRegistry
import dev.kirill.kandinsky.chat.storage.SQLiteChatRepository
import dev.kirill.kandinsky.chat.storage.SQLiteStore
import dev.kirill.kandinsky.chat.support.awaitResult
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Загружает только опубликованный неизменяемый начальный набор с сохранением новых сообщений и других чатов */
class PrimePublishedCorpusHistoryTest {
 /**
  * Только для заданного проверочного профиля загружает первые десять тысяч сообщений опубликованной медиаленты
  * Проверяет продвижение каждой ограниченной страницы, допустимые номера и отсутствие повторов
  * Сверяет сохранённые границы, уникальность и точное количество сообщений каждого вида
  * Записывает отчёт подготовки, не очищая более новые сообщения или другие чаты
  */
 @Test fun cacheTheVerifiedPublishedPrefixInBoundedPages(){
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val profile=checkNotNull(NetworkCredentials(context).load());assertEquals("Owned TECNO demo identity",1010L,profile.userID)
  val api=NetworkAPI(profile)
  val store=awaitResult<SQLiteStore>{cb->SQLiteStore.open(File(context.filesDir,"Kandinsky/net-v1-u${profile.userID}"),callback=cb)}
  val cache=NetworkStore(SQLiteChatRepository(store,NetworkJournal(profile.userID)),profile.userID)
  val out=File(context.getExternalFilesDir(null),"kandinsky-network-probe").apply{mkdirs()}
  var before=10001L;var pages=0;val sequences=HashSet<Long>()
  try{
   val metadata=api.json("/v1/chats");val chats=metadata.getJSONArray("chats")
   assertTrue("The owned profile can discover the published room",(0 until chats.length()).any{chats.getJSONObject(it).getLong("id")==1352L})
   awaitResult<Unit>{cb->store.submit({db->cache.mergeChats(db,metadata);Unit},cb)}
   while(true){
    val data=api.json("/v1/chats/1352/history?limit=128&before=$before");val rows=data.getJSONArray("messages")
    assertTrue(rows.length() in 1..128);var oldest=before
    for(index in 0 until rows.length()){
     val seq=rows.getJSONObject(index).getLong("seq");assertTrue(seq in 1L..10000L && seq<before);assertTrue("No duplicate history sequence",sequences.add(seq));oldest=minOf(oldest,seq)
    }
    awaitResult<Unit>{cb->store.submit({db->cache.history(db,1352,data);Unit},cb)}
    before=oldest;pages++
    File(out,"published-prime-progress.json").writeText(JSONObject().put("pages",pages).put("received",sequences.size).put("oldestSeq",before).toString())
    if(!data.getBoolean("hasOlder"))break
    assertTrue("Bounded128-row pagination terminates",pages<80)
   }
   assertEquals(10000,sequences.size);assertEquals(1L,before)
   val counts=awaitResult<List<Long>>{cb->store.submit({db->
    db.rawQuery("SELECT count(*),count(DISTINCT n.serverSeq),min(n.serverSeq),max(n.serverSeq),count(DISTINCT m.kind) FROM messages m JOIN net_messages n ON n.localID=m.id WHERE m.chatId=1352 AND n.serverSeq BETWEEN 1 AND 10000",null).use{c->assertTrue(c.moveToFirst());(0..4).map{c.getLong(it)}}
   },cb)}
   assertEquals(listOf(10000L,10000L,1L,10000L,6L),counts)
   val kinds=awaitResult<Map<String,Long>>{cb->store.submit({db->
    db.rawQuery("SELECT m.kind,count(*) FROM messages m JOIN net_messages n ON n.localID=m.id WHERE m.chatId=1352 AND n.serverSeq BETWEEN 1 AND 10000 GROUP BY m.kind",null).use{c->buildMap{while(c.moveToNext())put(c.getString(0),c.getLong(1))}}
   },cb)}
   assertEquals(mapOf("text" to 6500L,"photo" to 2000L,"voice" to 700L,"video" to 400L,"round" to 200L,"gif" to 200L),kinds)
   File(out,"published-prime-summary.json").writeText(JSONObject().put("profileID",1010).put("chatID",1352).put("prefixRows",10000).put("pages",pages).put("kinds",JSONObject(kinds)).put("source","Real HTTPS/PostgreSQL immutable10000-message prefix,128rows/request; newer messages preserved").toString(2))
  }finally{api.close();awaitResult<Unit>{store.close(it)}}
 }
}
