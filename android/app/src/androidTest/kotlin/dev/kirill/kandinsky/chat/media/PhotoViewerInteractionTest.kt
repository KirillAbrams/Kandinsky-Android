package dev.kirill.kandinsky.chat.media

import dev.kirill.kandinsky.chat.results.MediaGalleryController
import dev.kirill.kandinsky.chat.results.PhotoViewerController
import dev.kirill.kandinsky.chat.support.TestFixtures

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.*
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.support.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class PhotoViewerInteractionTest {
 /**
  * Создаёт три настоящие страницы просмотра крупных фото и сначала ждёт лёгкие изображения всех соседей
  * Поочерёдно активирует страницы в обе стороны, требуя полного разрешения выбранного фото
  * Неактивные соседи должны сохранить только малые пиксели и не блокировать подготовку следующего исходника
  * Закрытие обязано вернуть всю учтённую память в пределах установленного пика
  */
 @Test fun pagingLargeOriginalsReleasesInactiveResolutionBeforeTheNextDecode(){
  val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext;val device=UiDevice.getInstance(i);device.wakeUp();Configurator.getInstance().waitForIdleTimeout=0
  val activity=i.startActivitySync(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.apply{addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)}) as android.app.Activity
  assertTrue(device.wait(Until.hasObject(By.pkg(i.targetContext.packageName)),15000))
  val h=RepositoryHarness.open(File(context.cacheDir,"gallery-large-"+UUID.randomUUID()));lateinit var media:MediaStore
  val pages=mutableListOf<PhotoViewerController>()
  /**
   * Проверяет переданное состояние на основном потоке с короткими паузами
   * При истечении срока фиксирует неудачу с заданным пояснением
   */
  fun state(label:String,predicate:()->Boolean){val end=SystemClock.uptimeMillis()+5000;while(SystemClock.uptimeMillis()<end){var ok=false;i.runOnMainSync{ok=predicate()};if(ok)return;SystemClock.sleep(10)};fail(label)}
  i.runOnMainSync{media=MediaStore(h.store,h.repo)}
  try{
   val files=org.json.JSONObject(TestFixtures.assets.open("manifest.json").bufferedReader().use{it.readText()}).getJSONArray("files")
   val name=(0 until files.length()).map{files.getJSONObject(it)}.first{it.getString("mime")=="image/jpeg"}.getString("fileName")
   val source=File(h.root,"gallery-large.jpg")
   val photo=TestFixtures.assets.open("media/$name").use{android.graphics.BitmapFactory.decodeStream(it)}!!
   val large=Bitmap.createBitmap(2048,2048,Bitmap.Config.ARGB_8888)
   android.graphics.Canvas(large).drawBitmap(photo,null,android.graphics.Rect(0,0,2048,2048),android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
   source.outputStream().use{assertTrue(large.compress(Bitmap.CompressFormat.JPEG,95,it))};large.recycle();photo.recycle()
   val attachments=(0 until 3).map{
    val draft=awaitResult<AttachmentDraft>{cb->i.runOnMainSync{media.stage({source.inputStream()},"image/jpeg",cb)};Cancellation{}}
    Attachment(draft.id,draft.relativePath,draft.mime,draft.byteSize,draft.rawWidth,draft.rawHeight,draft.orientation,draft.durationMs,draft.thumbnailKey,tinyPreview=draft.tinyPreview)
   }
   i.runOnMainSync{
    val host=android.widget.FrameLayout(activity);activity.setContentView(host)
    for((index,attachment) in attachments.withIndex()){
     val page=PhotoViewerController(activity,attachment,media,embedded=true);pages+=page
     host.addView(page.pageView().apply{translationX=index*device.displayWidth.toFloat()},android.widget.FrameLayout.LayoutParams(-1,-1))
    }
   }
   /**
    * Временно удерживает готовое изображение указанной страницы и читает его ширину
    * Возвращает ноль при отсутствии пикселей
    * Временная ссылка всегда закрывается после чтения
    */
   fun width(index:Int):Int{val image=pages[index].retainedPreview();return try{image?.bitmap?.width ?: 0}finally{image?.close()}}
   state("All neighbours have actual preview pixels"){pages.indices.all{width(it)>0}}
   i.runOnMainSync{pages[0].setPageActive(true)}
   state("The first real original reaches the native surface"){width(0)==2048}
   for(index in 1..2){
    i.runOnMainSync{pages[index-1].setPageActive(false);pages[index].setPageActive(true)}
    state("Selected original ${index+1} must not be blocked by a full-resolution neighbour"){width(index)==2048}
   }
   for(index in 1 downTo 0){
    i.runOnMainSync{media.releaseIdleImages();pages[index+1].setPageActive(false);pages[index].setPageActive(true)}
    state("Reverse paging restores evicted original ${index+1}"){width(index)==2048}
   }
   i.runOnMainSync{for(index in 1..2)assertTrue("Neighbours retain lightweight pixels",width(index) in 1..384)}
   i.runOnMainSync{assertTrue(media.peakBytesUsed<=48*1024*1024);pages.forEach{it.close()};media.close()}
   state("Closing retires every actual image lease"){media.bytesUsed==0}
  }finally{i.runOnMainSync{pages.forEach{it.close()};media.close();activity.finish()};h.close()}
 }
 /**
  * Открывает галерею из трёх фото с готовыми предварительными изображениями и задержанными исходниками
  * Отмена должна остановить только исходник выбранной страницы, сохранив рисунок всех соседей
  * Живое обновление сообщения не должно сбрасывать незаконченный горизонтальный жест
  * Переход к соседу запускает только его исходник, возврат сохраняет отмену и действие повтора
  */
 @Test fun adjacentPreviewsSurviveCancellingOnlyTheSelectedOriginal(){
  val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext;val device=UiDevice.getInstance(i);device.wakeUp();Configurator.getInstance().waitForIdleTimeout=0
  val activity=i.startActivitySync(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.apply{addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)}) as android.app.Activity
  assertTrue(device.wait(Until.hasObject(By.pkg(i.targetContext.packageName)),15000))
  val h=RepositoryHarness.open(File(context.cacheDir,"gallery-cancel-"+UUID.randomUUID()));val admission=MediaAdmission()
  lateinit var media:MediaStore;lateinit var player:MediaCoordinator;var viewer:MediaGalleryController?=null
  val originals=java.util.concurrent.atomic.AtomicInteger();val cancelled=java.util.concurrent.atomic.AtomicInteger()
  /**
   * Проверяет переданное состояние на основном потоке с короткими паузами
   * При истечении срока фиксирует неудачу с заданным пояснением
   */
  fun state(label:String,predicate:()->Boolean){val end=SystemClock.uptimeMillis()+5000;while(SystemClock.uptimeMillis()<end){var ok=false;i.runOnMainSync{ok=predicate()};if(ok)return;SystemClock.sleep(10)};fail(label)}
  /**
   * Рекурсивно собирает всех потомков переданного представления без самого корня
   * Возвращает плоский набор для поиска страниц настоящего окна галереи
   */
  fun descendants(view:View):List<View> = if(view is ViewGroup)(0 until view.childCount).flatMap{listOf(view.getChildAt(it))+descendants(view.getChildAt(it))}else emptyList()
  i.runOnMainSync{media=MediaStore(h.store,h.repo);player=MediaCoordinator(admission,media);activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
  try{
   val files=org.json.JSONObject(TestFixtures.assets.open("manifest.json").bufferedReader().use{it.readText()}).getJSONArray("files")
   val names=(0 until files.length()).map{files.getJSONObject(it)}.filter{it.getString("mime")=="image/jpeg"}.take(3).map{it.getString("fileName")};assertEquals(3,names.size)
   val sources=mutableMapOf<String,File>();val ids=mutableListOf<Long>()
   for((index,name) in names.withIndex()){
    val draft=awaitResult<AttachmentDraft>{cb->i.runOnMainSync{media.stage({TestFixtures.assets.open("media/"+name)},"image/jpeg",cb)};Cancellation{}}
    val row=awaitResult<MessageDelta>{h.repo.send(SendCommand(UUID.randomUUID().toString(),2,MessageKind.PHOTO,caption="Фотография ${index+1}",attachments=listOf(draft)),it)}.inserted.single();ids+=row.id
    val path="remote/gallery-$index.jpg";sources[path]=media.fileURL(draft.relativePath)
    awaitResult<Unit>{cb->h.store.submit({db->db.execSQL("UPDATE attachments SET relativePath=? WHERE id=?",arrayOf(path,draft.id))},cb)}
   }
   i.runOnMainSync{media.remoteFile={a,preview,token->val file=checkNotNull(sources[a.relativePath]);if(!preview){originals.incrementAndGet();val gate=java.util.concurrent.CountDownLatch(1);token.onCancel{cancelled.incrementAndGet();gate.countDown()};check(gate.await(8,java.util.concurrent.TimeUnit.SECONDS))};file to "image/jpeg"}}
   val all=awaitResult<HistoryPage>{h.repo.history(PageRequest(2,limit=10,visualOnly=true),it)};val seed=all.rows.single{it.id==ids[1]}
   lateinit var native:View;lateinit var pager:ViewGroup
   i.runOnMainSync{val before=WindowInspector.getGlobalWindowViews().toSet();viewer=MediaGalleryController(activity,seed,h.repo,media,player,InlinePlayerSurface(activity,player)){};viewer!!.show();native=WindowInspector.getGlobalWindowViews().first{it !in before};pager=descendants(native).filterIsInstance<ViewGroup>().first{it.javaClass.simpleName=="Pager"}}
   /**
    * Уменьшает рисунок содержимого страницы до небольшого отдельного изображения
    * Проверяет наличие видимых цветовых составляющих вместо полностью чёрного результата
    * Временное изображение освобождается после чтения пикселей
    */
   fun hasPixels(page:View):Boolean{
    val content=(page as ViewGroup).getChildAt(0);if(content.width==0 || content.height==0)return false
    val b=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);val canvas=android.graphics.Canvas(b);canvas.scale(32f/content.width,32f/content.height);content.draw(canvas)
    val values=IntArray(1024);b.getPixels(values,0,32,0,0,32,32);b.recycle();return values.any{Color.red(it)>20 || Color.green(it)>20 || Color.blue(it)>20}
   }
   state("All three native page surfaces contain actual preview pixels"){pager.childCount==3 && (0 until pager.childCount).all{hasPixels(pager.getChildAt(it))}}
   assertEquals("Neighbours fetch thumbnails only",1,originals.get());device.findObject(By.res("media.cancel")).click()
   state("The central cross cancels the original I/O"){cancelled.get()==1}
   i.runOnMainSync{
    val centre=pager.scrollX;val partial=(centre*1.4).toInt();pager.scrollTo(partial,0)
    viewer!!.apply(MessageDelta(updated=listOf(seed),chat=ChatSummary(2,"QA","",0,0,0,false),reason=MessageChangeReason.SYNC))
    assertEquals("A live receipt must not reset an in-flight horizontal gesture",partial,pager.scrollX)
    pager.scrollTo(centre,0)
   }
   val middleCaption="Фотография 2"
   device.swipe(device.displayWidth*4/5,device.displayHeight/2,device.displayWidth/5,device.displayHeight/2,12)
   assertTrue(device.wait(Until.hasObject(By.text("Фотография 3")),5000));state("Only the new original starts"){originals.get()==2}
   device.swipe(device.displayWidth/5,device.displayHeight/2,device.displayWidth*4/5,device.displayHeight/2,12)
   assertTrue(device.wait(Until.hasObject(By.text(middleCaption)),5000));assertTrue(device.hasObject(By.res("media.retry")));assertEquals(2,originals.get())
   i.runOnMainSync{assertEquals(3,pager.childCount);assertTrue((0 until pager.childCount).all{hasPixels(pager.getChildAt(it))});viewer!!.close()}
   state("Closing releases every actual decoded image lease"){media.bytesUsed==0};assertEquals(2,cancelled.get())
  }finally{i.runOnMainSync{viewer?.close();player.close();media.close();admission.close();activity.finish()};h.close()}
 }
 /**
  * Перелистывает настоящую галерею из фото, видео и следующего фото в одном окне
  * Видео должно использовать единственного системного проигрывателя с правильным номером сообщения
  * Новый обратный жест во время незавершённого перехода должен выбрать ближайшую страницу без сдвига пикселей под пальцем
  * Закрытие возвращает исходное положение чтения чата
  */
 @Test fun photoVideoPhotoPagesReuseOneOpaqueWindowAndOnePlayer(){
  val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext;val device=UiDevice.getInstance(i);device.wakeUp()
  Configurator.getInstance().waitForIdleTimeout=0
  val activity=i.startActivitySync(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.apply{addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)}) as android.app.Activity
  assertTrue(device.wait(Until.hasObject(By.pkg(i.targetContext.packageName)),15000))
  val h=RepositoryHarness.open(File(context.cacheDir,"gallery-pages-"+UUID.randomUUID()));val admission=MediaAdmission()
  lateinit var media:MediaStore;lateinit var player:MediaCoordinator;lateinit var host:dev.kirill.kandinsky.chat.rendering.ChatScrollHost
  i.runOnMainSync{media=MediaStore(h.store,h.repo);player=MediaCoordinator(admission,media);host=dev.kirill.kandinsky.chat.rendering.ChatScrollHost(activity);activity.setContentView(host);host.configureMedia(media);host.configurePlayback(player,media)}
  try{
   val files=org.json.JSONObject(TestFixtures.assets.open("manifest.json").bufferedReader().use{it.readText()}).getJSONArray("files")
   val photoName=(0 until files.length()).map{files.getJSONObject(it)}.first{it.getString("mime")=="image/jpeg"}.getString("fileName")
   /**
    * Подготавливает заданный настоящий файл и вид вложения через владельца медиа на основном потоке
    * Возвращает черновик для последующего сохранения сообщением в отдельной базе
    */
   fun stage(name:String,mime:String)=awaitResult<AttachmentDraft>{cb->i.runOnMainSync{media.stage({TestFixtures.assets.open("media/$name")},mime,cb)};Cancellation{}}
   val photo=stage(photoName,"image/jpeg");val video=stage("video-00.mp4","video/mp4")
   val first=awaitResult<MessageDelta>{h.repo.send(SendCommand(UUID.randomUUID().toString(),2,MessageKind.PHOTO,caption="Первое фото",attachments=listOf(photo)),it)}.inserted.single()
   val second=awaitResult<MessageDelta>{h.repo.send(SendCommand(UUID.randomUUID().toString(),2,MessageKind.VIDEO,caption="Второе: видео",attachments=listOf(video)),it)}.inserted.single()
   val third=awaitResult<MessageDelta>{h.repo.send(SendCommand(UUID.randomUUID().toString(),2,MessageKind.PHOTO,caption="Третье фото",attachments=listOf(photo.copy(id=UUID.randomUUID().toString()))),it)}.inserted.single()
   awaitResult<Unit>{cb->i.runOnMainSync{host.open(2,h.repo,null,cb)};Cancellation{}}
   lateinit var galleryWindow:View;var anchor:ReadingAnchor?=null
   i.runOnMainSync{anchor=host.readingAnchor();val before=WindowInspector.getGlobalWindowViews().toSet();host.onMessageTap?.invoke(first,android.graphics.RectF());galleryWindow=WindowInspector.getGlobalWindowViews().first{it !in before}}
   assertTrue("The tapped media opens one actual paged viewer",device.wait(Until.hasObject(By.res("viewer.pages")),5000))
   assertTrue(device.hasObject(By.text(first.caption)))
   /**
    * Выполняет один горизонтальный жест от правой части экрана к левой
    * Вызывающий сценарий отдельно ждёт выбранную страницу после перелистывания
    */
   fun swipeNext(){device.swipe(device.displayWidth*4/5,device.displayHeight/2,device.displayWidth/5,device.displayHeight/2,12)}
   swipeNext();assertTrue("Horizontal paging selects the actual video",device.wait(Until.hasObject(By.text(second.caption)),5000))
   val ready=java.util.concurrent.CountDownLatch(1)
   i.runOnMainSync{if(player.isReady)ready.countDown()else player.onChange={if(player.isReady)ready.countDown()}}
   assertTrue(ready.await(8,java.util.concurrent.TimeUnit.SECONDS))
   i.runOnMainSync{assertEquals(second.id,player.currentMessageID);assertEquals(1,player.activePlayerCount);assertTrue(WindowInspector.getGlobalWindowViews().contains(galleryWindow))}
   swipeNext();assertTrue(device.wait(Until.hasObject(By.text(third.caption)),5000))
   i.runOnMainSync{assertTrue(WindowInspector.getGlobalWindowViews().contains(galleryWindow));assertEquals(1,player.peakNativePlayers)}
   i.runOnMainSync{
    /**
     * Рекурсивно собирает всех потомков переданного представления без самого корня
     * Возвращает плоский набор для поиска страниц настоящего окна галереи
     */
    fun descendants(view:View):List<View> = if(view is ViewGroup)(0 until view.childCount).flatMap{listOf(view.getChildAt(it))+descendants(view.getChildAt(it))}else emptyList()
    val pager=descendants(galleryWindow).filterIsInstance<ViewGroup>().first{it.javaClass.simpleName=="Pager"}
    val stride=pager.width+(20*activity.resources.displayMetrics.density).toInt();pager.scrollTo((stride*0.3).toInt(),0)
    val previous=(0 until pager.childCount).map{pager.getChildAt(it)}.first{it.left==0};val visualX=previous.left-pager.scrollX
    val now=SystemClock.uptimeMillis();val down=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,pager.width*0.8f,pager.height*0.5f,0)
    pager.dispatchTouchEvent(down);down.recycle()
    assertTrue("A new reverse gesture commits the nearest visible page",descendants(galleryWindow).filterIsInstance<android.widget.TextView>().any{it.text.toString()==second.caption})
    assertEquals("Rebasing preserves the pixels under the finger",visualX,previous.left-pager.scrollX)
    val cancel=android.view.MotionEvent.obtain(now,now+1,android.view.MotionEvent.ACTION_CANCEL,pager.width*0.8f,pager.height*0.5f,0);pager.dispatchTouchEvent(cancel);cancel.recycle()
   }
   SystemClock.sleep(220);swipeNext();assertTrue(device.wait(Until.hasObject(By.text(third.caption)),5000))
   device.findObject(By.res("photo.close")).click();assertTrue(device.wait(Until.gone(By.res("viewer.pages")),5000))
   i.runOnMainSync{assertEquals(anchor?.messageID,host.readingAnchor().messageID)}
  }finally{i.runOnMainSync{host.close();player.close();media.close();admission.close();activity.finish()};h.close()}
 }
 /**
  * Открывает отдельный просмотр фото поверх заведомо красного исходного экрана
  * Проверяет, что непрозрачное окно занимает область строки состояния и выреза устройства
  * Пиксель у верхнего края должен оставаться чёрным до и после небольшого жеста вниз
  */
 @Test fun galleryBackingCoversTheDisplayCutoutAndStaysBlackDuringDrag(){
  val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext;val device=UiDevice.getInstance(i);device.wakeUp()
  val activity=i.startActivitySync(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.apply{addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)}) as android.app.Activity
  assertTrue(device.wait(Until.hasObject(By.pkg(i.targetContext.packageName)),15000))
  val h=RepositoryHarness.open(File(context.cacheDir,"photo-cutout-"+UUID.randomUUID()));lateinit var media:MediaStore;var viewer:PhotoViewerController?=null
  i.runOnMainSync{media=MediaStore(h.store,h.repo);activity.setContentView(android.widget.FrameLayout(activity).apply{setBackgroundColor(Color.RED)})}
  try{
   val files=org.json.JSONObject(TestFixtures.assets.open("manifest.json").bufferedReader().use{it.readText()}).getJSONArray("files");val name=(0 until files.length()).map{files.getJSONObject(it)}.first{it.getString("mime")=="image/jpeg"}.getString("fileName")
   val d=awaitResult<AttachmentDraft>{cb->i.runOnMainSync{media.stage({TestFixtures.assets.open("media/"+name)},"image/jpeg",cb)};Cancellation{}}
   val a=Attachment(d.id,d.relativePath,d.mime,d.byteSize,d.rawWidth,d.rawHeight,d.orientation,d.durationMs,d.thumbnailKey)
   var window:View?=null
   i.runOnMainSync{val before=WindowInspector.getGlobalWindowViews().toSet();viewer=PhotoViewerController(activity,a,media);viewer!!.show();window=WindowInspector.getGlobalWindowViews().first{it !in before}}
   assertTrue(device.wait(Until.hasObject(By.res("photo.close")),5000));i.waitForIdleSync()
   i.runOnMainSync{
    val location=IntArray(2);window!!.getLocationOnScreen(location)
    assertEquals("The opaque gallery window extends into the status/cutout area",0,location[1])
   }
   val bitmap=checkNotNull(i.uiAutomation.takeScreenshot())
   try{assertEquals("The chat cannot show through the top of the gallery",Color.BLACK,bitmap.getPixel(bitmap.width/5,2))}finally{bitmap.recycle()}
   device.swipe(device.displayWidth/2,device.displayHeight/2,device.displayWidth/2,device.displayHeight/2+device.displayHeight/12,20)
   val moved=checkNotNull(i.uiAutomation.takeScreenshot())
   try{assertEquals(Color.BLACK,moved.getPixel(moved.width/5,2))}finally{moved.recycle()}
  }finally{i.runOnMainSync{viewer?.close();media.close();activity.finish()};h.close()}
 }
 /**
  * Сохраняет настоящее фото с подписью в отдельную историю и открывает его через действие сообщения
  * В отдельном просмотре должны оставаться доступными закрытие и точный текст подписи
  * После проверки закрывает просмотр и созданных владельцев
  */
 @Test fun photoTapPreservesTheCaptionInTheOriginalViewer(){
  val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext;val device=UiDevice.getInstance(i);device.wakeUp()
  val activity=i.startActivitySync(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.apply{addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)}) as android.app.Activity
  assertTrue(device.wait(Until.hasObject(By.pkg(i.targetContext.packageName)),15000))
  val h=RepositoryHarness.open(File(context.cacheDir,"photo-caption-"+UUID.randomUUID()));val admission=MediaAdmission();lateinit var media:MediaStore;lateinit var player:MediaCoordinator;lateinit var host:dev.kirill.kandinsky.chat.rendering.ChatScrollHost
  i.runOnMainSync{media=MediaStore(h.store,h.repo);player=MediaCoordinator(admission,media);host=dev.kirill.kandinsky.chat.rendering.ChatScrollHost(activity);activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);activity.setContentView(host);host.configureMedia(media);host.configurePlayback(player,media)}
  try{
   val files=org.json.JSONObject(TestFixtures.assets.open("manifest.json").bufferedReader().use{it.readText()}).getJSONArray("files");val name=(0 until files.length()).map{files.getJSONObject(it)}.first{it.getString("mime")=="image/jpeg"}.getString("fileName")
   val draft=awaitResult<AttachmentDraft>{cb->i.runOnMainSync{media.stage({TestFixtures.assets.open("media/"+name)},"image/jpeg",cb)};Cancellation{}}
   val row=awaitResult<MessageDelta>{h.repo.send(SendCommand(UUID.randomUUID().toString(),2,MessageKind.PHOTO,caption="A readable photo caption",attachments=listOf(draft)),it)}.inserted.single()
   awaitResult<Unit>{cb->i.runOnMainSync{host.open(2,h.repo,null,cb);host.setMediaVisible(true)};Cancellation{}}
   i.runOnMainSync{host.onMessageTap?.invoke(row,android.graphics.RectF())}
   assertTrue(device.wait(Until.hasObject(By.res("photo.close")),5000))
   assertTrue("Opening the original must preserve the message caption",device.hasObject(By.text(row.caption)))
   device.findObject(By.res("photo.close")).click()
  }finally{i.runOnMainSync{host.close();player.close();media.close();admission.close();activity.finish()};h.close()}
 }
 /**
  * Открывает выбранное второе видео группы генерации без ручного нажатия воспроизведения
  * До и после первого кадра управление перемоткой и паузой должно быть скрыто
  * Обновление той же группы не должно прерывать выбранное видео или создавать второго проигрывателя
  * После закрытия в чат не должен возвращаться ручной проигрыватель и не должно происходить удаление результата
  */
 @Test fun studioVideoAutoplaysWithoutTransportAndClosesWithoutAnInlinePlayer(){
  val i=InstrumentationRegistry.getInstrumentation()
  val context=i.targetContext
  val device=UiDevice.getInstance(i)
  device.wakeUp()
  Configurator.getInstance().waitForIdleTimeout=0
  val activity=i.startActivitySync(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.apply{
   addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
  }) as android.app.Activity
  assertTrue("Окно приложения открылось",device.wait(Until.hasObject(By.pkg(i.targetContext.packageName)),30000))
  val h=RepositoryHarness.open(File(context.cacheDir,"studio-autoplay-"+UUID.randomUUID()))
  val admission=MediaAdmission()
  lateinit var media:MediaStore
  lateinit var player:MediaCoordinator
  lateinit var surface:InlinePlayerSurface
  var viewer:MediaGalleryController?=null
  val returned=java.util.concurrent.CountDownLatch(1)
  i.runOnMainSync{
   media=MediaStore(h.store,h.repo,studioOriginals=true)
   player=MediaCoordinator(admission,media)
   surface=InlinePlayerSurface(activity,player)
  }
  try{
   /**
    * Подготавливает заданное настоящее видео через владельца медиа на основном потоке
    * Возвращает черновик для создания группы из двух вложений в отдельном чате
    */
   fun stage(name:String)=awaitResult<AttachmentDraft>{cb->i.runOnMainSync{media.stage({TestFixtures.assets.open("media/$name")},"video/mp4",cb)};Cancellation{}}
   val first=stage("video-01.mp4");val second=stage("video-00.mp4")
   val delta=awaitResult<MessageDelta>{h.repo.send(SendCommand(UUID.randomUUID().toString(),2,MessageKind.VIDEO,attachments=listOf(first,second)),it)}
   val row=delta.inserted.single()
   assertEquals(2,row.attachments.size)
   val controls={ (0 until surface.childCount).map(surface::getChildAt).filter{it is MediaProgressControl || it.createAccessibilityNodeInfo().viewIdResourceName=="player.pause"} }
   /**
    * Периодически проверяет переданное состояние на основном потоке до установленного срока
    * При неготовности фиксирует заданное описание ошибки
    */
   fun waitFor(description:String,check:()->Boolean){
    val end=SystemClock.uptimeMillis()+8000
    do{
     var ready=false
     i.runOnMainSync{ready=check()}
     if(ready)return
     SystemClock.sleep(20)
    }while(SystemClock.uptimeMillis()<end)
    fail(description)
   }
   i.runOnMainSync{
    viewer=MediaGalleryController(activity,row,h.repo,media,player,surface,attachmentIndex=1){returned.countDown()}
    viewer!!.onDeleteResult={_,_->fail("Closing a video must never delete it")}
    viewer!!.show()
    assertEquals(3,controls().size)
    assertTrue("Transport is hidden before the first frame",controls().all{it.visibility==View.GONE})
   }
   waitFor("Video starts without a tap"){player.isPlaying && player.positionMs>0 && surface.hasVideoFrame}
   i.runOnMainSync{
    assertTrue("Transport stays hidden after playback starts",controls().all{it.visibility==View.GONE})
    assertEquals(1,player.peakNativePlayers)
    assertEquals(second.id,player.currentTarget?.attachment?.id)
    val refresh=MessageDelta(updated=listOf(row),chat=delta.chat)
    player.apply(refresh);viewer!!.apply(refresh)
    assertTrue("Refreshing an album must leave its selected video running",player.isPlaying)
    viewer!!.close()
   }
   assertTrue(returned.await(3,java.util.concurrent.TimeUnit.SECONDS))
   waitFor("No manual player returns to the chat"){player.activePlayerCount==0 && player.currentMessageID==null && surface.parent==null}
   i.runOnMainSync{
    val poster=MediaImageSlot(media){}
    val video=Attachment("video","remote/video.mp4","video/mp4",100,640,384,1,5000,"video")
    try{
     poster.bind(video,android.graphics.RectF(0f,0f,260f,150f),kind=MessageKind.VIDEO,showControls=false)
     assertTrue(poster.loading)
     assertNull(poster.loadAction())
     assertFalse(poster.performLoadAction(130f,75f))
    }finally{poster.close()}
   }
  }finally{i.runOnMainSync{viewer?.close();player.close();media.close();admission.close();activity.finish()};h.close()}
 }
 /**
  * Открывает видео с подписью через действие настоящей истории
  * После готовности ставит его на паузу и меняет позицию
  * Возврат в чат должен сохранить тот же единственный проигрыватель, паузу и выбранное время
  * Подпись проверяется в отдельном полноэкранном показе
  */
 @Test fun videoTapOpensFullScreenWithCaptionAndReturnsTheSamePausedNativeOutput(){
  val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext;val device=UiDevice.getInstance(i);device.wakeUp()
  val activity=i.startActivitySync(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.apply{
   addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
  }) as android.app.Activity
  assertTrue(device.wait(Until.hasObject(By.pkg(i.targetContext.packageName)),15000))
  val h=RepositoryHarness.open(File(context.cacheDir,"video-route-"+UUID.randomUUID()));val admission=MediaAdmission();lateinit var media:MediaStore;lateinit var player:MediaCoordinator;lateinit var host:dev.kirill.kandinsky.chat.rendering.ChatScrollHost
  i.runOnMainSync{media=MediaStore(h.store,h.repo);player=MediaCoordinator(admission,media);host=dev.kirill.kandinsky.chat.rendering.ChatScrollHost(activity);activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);activity.setContentView(host);host.configureMedia(media);host.configurePlayback(player,media)}
  try{
   val draft=awaitResult<AttachmentDraft>{cb->i.runOnMainSync{media.stage({TestFixtures.assets.open("media/video-00.mp4")},"video/mp4",cb)};Cancellation{}}
   val row=awaitResult<MessageDelta>{h.repo.send(SendCommand(UUID.randomUUID().toString(),2,MessageKind.VIDEO,caption="A real video caption",attachments=listOf(draft)),it)}.inserted.single()
   awaitResult<Unit>{cb->i.runOnMainSync{host.open(2,h.repo,null,cb);host.setMediaVisible(true)};Cancellation{}}
   i.runOnMainSync{host.onMessageTap?.invoke(row,android.graphics.RectF())}
   assertTrue("A video tap must open the actual full-screen viewer",device.wait(Until.hasObject(By.desc("Вернуть в чат")),5000))
   assertTrue(device.hasObject(By.text(row.caption)))
   val ready=java.util.concurrent.CountDownLatch(1);i.runOnMainSync{if(player.isReady)ready.countDown()else player.onChange={if(player.isReady)ready.countDown()}}
   assertTrue(ready.await(8,java.util.concurrent.TimeUnit.SECONDS));val sought=java.util.concurrent.CountDownLatch(1)
   i.runOnMainSync{player.pause();player.seek(1200){done->assertTrue(done);sought.countDown()}}
   assertTrue(sought.await(3,java.util.concurrent.TimeUnit.SECONDS))
   device.findObject(By.desc("Вернуть в чат")).click()
   val end=SystemClock.uptimeMillis()+3000;var returned=false
   while(SystemClock.uptimeMillis()<end && !returned){i.runOnMainSync{returned=player.inlineVisible};SystemClock.sleep(20)}
   assertTrue(returned);i.runOnMainSync{assertEquals(1,player.activePlayerCount);assertEquals(1,player.peakNativePlayers);assertEquals(1200.0,player.positionMs.toDouble(),120.0);assertFalse(player.isPlaying)}
  }finally{i.runOnMainSync{host.close();player.close();media.close();admission.close();activity.finish()};h.close()}
 }
 /**
  * Открывает настоящую анимацию и требует одного владельца движущегося показа
  * По нескольким отпечаткам центральной области снимков проверяет смену исходных пикселей
  * После сигнала нехватки памяти и закрытия разрешение анимации должно освободиться
  * Закрытие владельца медиа возвращает всю учтённую память
  */
 @Test fun fullScreenGifDisplaysChangingOriginalFramesAndRetiresOnDismissal(){
  val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext;val device=UiDevice.getInstance(i);device.wakeUp()
  val activity=i.startActivitySync(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.apply{addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)}) as android.app.Activity
  assertTrue(device.wait(Until.hasObject(By.pkg(i.targetContext.packageName)),15000))
  val h=RepositoryHarness.open(File(context.cacheDir,"gif-viewer-"+UUID.randomUUID()));lateinit var media:MediaStore;var viewer:PhotoViewerController?=null
  i.runOnMainSync{media=MediaStore(h.store,h.repo);activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
  /**
   * Проверяет переданное состояние на основном потоке с небольшими паузами
   * После истечения срока фиксирует заданное пояснение неготовности
   */
  fun awaitState(label:String,check:()->Boolean){val end=SystemClock.uptimeMillis()+5000;while(SystemClock.uptimeMillis()<end){var ok=false;i.runOnMainSync{ok=check()};if(ok)return;SystemClock.sleep(20)};fail(label)}
  try{
   val d=awaitResult<AttachmentDraft>{cb->i.runOnMainSync{media.stage({TestFixtures.assets.open("catalogue-media/gif-waterfall-0.gif")},"image/gif",cb)};Cancellation{}}
   val a=Attachment(d.id,d.relativePath,d.mime,d.byteSize,d.rawWidth,d.rawHeight,d.orientation,d.durationMs,d.thumbnailKey)
   i.runOnMainSync{viewer=PhotoViewerController(activity,a,media);viewer!!.show()}
   awaitState("The fullscreen GIF must own one bounded moving output"){MediaAdmission.previewOwnerCount==1}
   assertTrue(device.wait(Until.gone(By.res("media.cancel")),5000))
   val hashes=mutableSetOf<Int>();val end=SystemClock.uptimeMillis()+2000
   while(SystemClock.uptimeMillis()<end){val b=checkNotNull(i.uiAutomation.takeScreenshot());val values=IntArray(80*80);val x=b.width/2-40;val y=b.height/2-200;b.getPixels(values,0,80,x,y,80,80);hashes+=values.contentHashCode();b.recycle();SystemClock.sleep(60)}
   assertTrue("A GIF viewer must display changing source pixels, not the first static frame",hashes.size>=3)
   i.runOnMainSync{media.trimMemory();viewer!!.close()};awaitState("Dismissal after a memory warning must retire the native GIF reservation"){MediaAdmission.previewOwnerCount==0}
   i.runOnMainSync{media.close();assertEquals(0,media.bytesUsed)}
  }finally{i.runOnMainSync{viewer?.close();media.close();activity.finish()};h.close()}
 }
 /**
  * Создаёт отдельное техническое фото с цветовым переходом для измерения увеличения и перемещения
  * Двойное нажатие должно заметно увеличить высоту рисунка, а жест по увеличенному фото изменить пиксель под центром
  * Сохраняет снимки исходного вида и обоих преобразований
  * Закрытие должно вернуть управление через настоящий обработчик завершения
  */
 @Test fun doubleTapZoomPanAndCloseUseTheOriginalSurface(){
  val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext;val device=UiDevice.getInstance(i)
  device.wakeUp();Configurator.getInstance().waitForIdleTimeout=0
  context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.apply{addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)})
  assertTrue(device.wait(Until.hasObject(By.pkg(i.targetContext.packageName)),15000))
  val h=RepositoryHarness.open(File(context.cacheDir,"photo-interaction-"+UUID.randomUUID()))
  lateinit var activity:android.app.Activity;lateinit var media:MediaStore;var viewer:PhotoViewerController?=null
  i.runOnMainSync{activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first();media=MediaStore(h.store,h.repo);activity.setContentView(android.widget.FrameLayout(activity).apply{setBackgroundColor(Color.BLACK)})}
  try{
   val source=File(h.root,"gradient.jpg");val b=Bitmap.createBitmap(1600,800,Bitmap.Config.ARGB_8888)
   val row=IntArray(1600){x->Color.rgb(50+x*200/1599,0,250-x*200/1599)}
   for(y in 0 until 800)b.setPixels(row,0,1600,0,y,1600,1)
   source.outputStream().use{assertTrue(b.compress(Bitmap.CompressFormat.JPEG,95,it))};b.recycle()
   val draft=awaitResult<AttachmentDraft>{cb->i.runOnMainSync{media.stage({source.inputStream()},"image/jpeg",cb)};Cancellation{}}
   val a=Attachment(draft.id,draft.relativePath,draft.mime,draft.byteSize,draft.rawWidth,draft.rawHeight,draft.orientation,draft.durationMs,draft.thumbnailKey)
   var native:View?=null
   i.runOnMainSync{
    val before=WindowInspector.getGlobalWindowViews().toSet();viewer=PhotoViewerController(activity,a,media);viewer!!.show()
    val window=WindowInspector.getGlobalWindowViews().first{it !in before}
    val container=window.findViewById<ViewGroup>(android.R.id.content);val root=container.getChildAt(0) as ViewGroup;native=root.getChildAt(0)
   }
   device.waitForIdle(500);Thread.sleep(300)
   /**
    * Получает настоящий системный снимок текущего экрана для сравнения пикселей
    * Возвращённое изображение освобождается вызывающим шагом после измерения или сохранения
    */
   fun capture():Bitmap=checkNotNull(i.uiAutomation.takeScreenshot())
   /**
    * Ищет в заданном столбце снимка пиксели технического цветового перехода
    * Возвращает высоту между первым и последним подходящими пикселями
    * Если рисунка в столбце нет, возвращает ноль
    */
   fun coloredHeight(image:Bitmap,x:Int):Int{
    val ys=(0 until image.height).filter{y->val color=image.getPixel(x,y);Color.green(color)<20 && maxOf(Color.red(color),Color.blue(color))>80}
    return if(ys.isEmpty())0 else ys.last()-ys.first()+1
   }
   val location=IntArray(2);var width=0;var height=0
   i.runOnMainSync{native!!.getLocationOnScreen(location);width=native!!.width;height=native!!.height}
   val cx=location[0]+width/2;val cy=location[1]+height/2
   var first=capture();var initial=coloredHeight(first,cx)
   val firstDeadline=SystemClock.uptimeMillis()+4000
   while(initial<=width/3 && SystemClock.uptimeMillis()<firstDeadline){first.recycle();SystemClock.sleep(50);first=capture();initial=coloredHeight(first,cx)}
   val evidence=File(context.getExternalFilesDir("kandinsky-test-metrics"),"photo-viewer-first.png");evidence.outputStream().use{first.compress(Bitmap.CompressFormat.PNG,100,it)}
   println("PHOTO_VIEWER width=$width height=$height x=${location[0]} y=${location[1]} coloredHeight=$initial center=${first.getPixel(cx,cy)} ownedBytes=${media.bytesUsed}")
   first.recycle();assertTrue("Original aspect-fit image must be visible",initial>width/3)
   device.click(cx,cy);SystemClock.sleep(60);device.click(cx,cy);SystemClock.sleep(400)
   val zoomed=capture();val expanded=coloredHeight(zoomed,cx);val beforePan=zoomed.getPixel(cx,cy)
   File(context.getExternalFilesDir("kandinsky-test-metrics"),"photo-viewer-zoomed.png").outputStream().use{zoomed.compress(Bitmap.CompressFormat.PNG,100,it)};zoomed.recycle()
   assertTrue("Double tap must enlarge the image, not merely hide controls",expanded>initial*1.8)
   device.swipe(cx,cy,cx+width/4,cy,20);SystemClock.sleep(300)
   val panned=capture();val afterPan=panned.getPixel(cx,cy)
   File(context.getExternalFilesDir("kandinsky-test-metrics"),"photo-viewer-panned.png").outputStream().use{panned.compress(Bitmap.CompressFormat.PNG,100,it)};panned.recycle()
   assertTrue("Panning a zoomed photo must change the original pixels under the viewport",kotlin.math.abs(Color.red(beforePan)-Color.red(afterPan))>8)
   var closed=false;i.runOnMainSync{viewer!!.onClose={closed=true};viewer!!.close()};i.waitForIdleSync();assertTrue("Native dismiss must return ownership",closed)
  }finally{i.runOnMainSync{viewer?.close();media.close();activity.finish()};h.close()}
 }
}
