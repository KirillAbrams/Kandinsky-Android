package dev.kirill.kandinsky.chat.rendering
import android.content.Intent
import android.os.SystemClock
import android.view.*
import android.widget.OverScroller
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.kirill.kandinsky.chat.app.MainActivity
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.support.*
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
class ScrollFrameAlignmentTest {
 /**
  * Готовит настоящую сцену истории и проверяет согласованность положения её узлов до движения
  * Запускает системное перемещение и вручную продвигает его без выполнения отложенного обновления
  * После текущего расчёта прокрутки каждый узел должен уже соответствовать новому логическому смещению
  * Старое положение на один кадр считается ошибкой
  */
 @Test fun flingAdvancesNativeSceneBeforeTheCurrentDraw(){
  val i=InstrumentationRegistry.getInstrumentation();val d=UiDevice.getInstance(i);d.wakeUp()
  val old=Configurator.getInstance().waitForIdleTimeout;Configurator.getInstance().waitForIdleTimeout=0
  var host:ChatScrollHost?=null;var activity:MainActivity?=null;var harness:RepositoryHarness?=null
  try{
   activity=i.startActivitySync(i.targetContext.packageManager.getLaunchIntentForPackage(i.targetContext.packageName)!!.apply{addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)}) as MainActivity
   assertTrue(d.wait(Until.hasObject(By.pkg(i.targetContext.packageName)),15000))
   val h=RepositoryHarness.open(File(i.targetContext.cacheDir,"scroll-frame-"+UUID.randomUUID()));harness=h
   awaitResult<Unit>{cb->h.store.submit({db->
    db.beginTransaction()
    try{db.compileStatement("INSERT INTO messages(id,chatId,sortKey,kind,text,caption,revision,savedAt,direction,searchText) VALUES(?,2,?,'text',?,'',1,0,'incoming',?)").use{statement->
     for(n in 1..1000){statement.bindLong(1,n.toLong());statement.bindLong(2,n.toLong());statement.bindString(3,"row"+n);statement.bindString(4,"row"+n);statement.executeInsert()}
    };db.setTransactionSuccessful()}finally{db.endTransaction()}
   },cb)}
   lateinit var chat:ChatScrollHost
   i.runOnMainSync{chat=ChatScrollHost(activity!!);host=chat;activity!!.setContentView(chat)}
   awaitResult<Unit>{cb->i.runOnMainSync{chat.open(2,h.repo,ReadingAnchor(messageID=990,followsBottom=false),cb)};Cancellation{}}
   /**
    * Находит закрытое поле области истории по переданному имени и разрешает его чтение для проверки
    * Возвращает описание поля, через которое сценарий сверяет настоящую сцену и смещение
    */
   fun field(name:String)=ChatScrollHost::class.java.getDeclaredField(name).apply{isAccessible=true}
   /**
    * Получает сохранённую сцену текущей истории и читает её действительные узлы
    * Возвращает только узлы сообщений для сравнения их системного положения с логической геометрией
    */
   fun nodes():List<MessageNode>{val scene=field("scene").get(chat) as RetainedChatScene;return (RetainedChatScene::class.java.getDeclaredField("nodes").apply{isAccessible=true}.get(scene) as Map<*,*>).values.filterIsInstance<MessageNode>()}
   var ready=false;val end=SystemClock.uptimeMillis()+10000
   while(!ready && SystemClock.uptimeMillis()<end){i.runOnMainSync{
    val index=field("index").get(chat) as HistoryIndex;val offset=field("offset").getDouble(chat);val current=nodes()
    ready=current.isNotEmpty() && current.all{node->val f=node.fragment;val expected=index.top(f.id.messageID)!!-offset+f.prepared.blocks[f.part].offsetY;kotlin.math.abs(expected-node.native.translationY)<0.05}
   };if(!ready)SystemClock.sleep(20)}
   assertTrue("Prepared native scene fixture",ready)
   i.runOnMainSync{
    val scroller=field("scroller").get(chat) as OverScroller;scroller.abortAnimation();field("lastFlingY").setInt(chat,0)
    val before=field("offset").getDouble(chat);scroller.startScroll(0,0,0,-1200,1000)
    // Время системного движения продвигается без выполнения отложенного обновления
    // Так задержка положения сцены на один кадр не может скрыться
    SystemClock.sleep(30);chat.computeScroll();val now=field("offset").getDouble(chat)
    assertTrue("The actual OverScroller must advance",kotlin.math.abs(now-before)>1)
    val index=field("index").get(chat) as HistoryIndex
    val current=nodes();assertTrue(current.isNotEmpty())
    for(node in current){val f=node.fragment;val expected=index.top(f.id.messageID)!!-now+f.prepared.blocks[f.part].offsetY
     assertEquals("Native RenderNode must represent this draw's logical scroll offset",expected,node.native.translationY.toDouble(),0.05)}
   }
  }finally{i.runOnMainSync{host?.close();activity?.finish()};harness?.close();Configurator.getInstance().waitForIdleTimeout=old}
 }
}
