package dev.kirill.kandinsky.chat.media
import dev.kirill.kandinsky.chat.support.TestFixtures

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.kirill.kandinsky.chat.app.MainActivity
import dev.kirill.kandinsky.chat.conversation.ConversationChrome
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.rendering.*
import dev.kirill.kandinsky.chat.support.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WarmNavigationCacheTest {
 private val i get()=InstrumentationRegistry.getInstrumentation()
 /**
  * Синхронно выполняет переданное действие на основном потоке приложения
  * Используется для сцены истории, навигации и удерживаемых изображений
  */
 private fun main(action:()->Unit)=i.runOnMainSync(action)
 /**
  * Подготавливает конкретное настоящее фото проверочного набора через владельца медиа
  * Возвращает черновик вложения после асинхронного ответа вне основного потока
  */
 private fun photo(media:MediaStore):AttachmentDraft=awaitResult{callback->
  main{media.stage({TestFixtures.assets.open("real-media/backend-824d66cce0d64409ad4888a13792c7d4.jpg")},"image/jpeg",callback)}
  Cancellation{}
 }
 /**
  * Переносит метаданные подготовленного черновика в обычное описание вложения
  * Сохраняет номер, путь, размеры, ориентацию и длительность без повторного чтения файла
  */
 private fun attachment(draft:AttachmentDraft)=Attachment(draft.id,draft.relativePath,draft.mime,draft.byteSize,draft.rawWidth,draft.rawHeight,draft.orientation,draft.durationMs,draft.thumbnailKey)
 /**
  * Запрашивает подготовленное изображение переданного вложения с выбранным пределом размера
  * Вызывает владельца медиа на основном потоке и возвращает полученный удерживаемый ресурс
  */
 private fun decoded(media:MediaStore,asset:Attachment,size:Int):PreparedImage=awaitResult{callback->main{media.thumbnail(asset,ThumbnailKey(size),0){_,result->callback(result)}};Cancellation{}}

 /**
  * Располагает фото за краем экрана, но внутри области предварительной подготовки
  * Уже скачанный файл должен декодироваться заранее без сети, отсутствующий исходник вне экрана готовит только малое изображение
  * При появлении строки запрос исходника не должен убирать это малое изображение или показывать действие загрузки
  * После получения качественных пикселей повторное появление должно сразу использовать тот же рисунок
  */
 @Test fun historyPhotoWarmsOutsideViewportShowsPreviewAndReusesDecodedFrame(){
  val h=RepositoryHarness.open(File(i.targetContext.cacheDir,"prefetch-photo-"+UUID.randomUUID()))
  val media=MediaStore(h.store,h.repo,studioOriginals=true)
  val draft=photo(media)
  val original=media.fileURL(draft.relativePath)
  val asset=attachment(draft).copy(id="ready",relativePath="remote/ready.jpg",tinyPreview=checkNotNull(draft.tinyPreview))
  val row=Message(1,2,OrderKey(1,1),MessageDirection.INCOMING,0,MessageKind.PHOTO,"","",null,1,listOf(asset))
  val prepared=PreparedText(row,TextLayoutKey(1,1,300,17,3))
  val scene=RetainedChatScene()
  val viewport=RectF(0f,0f,390f,500f)
  val snapshot=SceneSnapshot(scene.ticket,listOf(SceneFragment(prepared,0,RectF(0f,550f,prepared.width,550f+prepared.height))))
  val release=CountDownLatch(1)
  val calls=java.util.concurrent.atomic.AtomicInteger()
  val cachedFile=media.fileURL(asset.relativePath)
  cachedFile.parentFile?.mkdirs()
  original.copyTo(cachedFile)
  /**
   * Временно удерживает показанное сценой изображение и читает ширину его пикселей
   * Возвращает ноль при отсутствии рисунка и закрывает временную ссылку после чтения
   */
  fun width():Int=scene.preview(1)?.use{it.bitmap?.width ?:0} ?:0
  /**
   * Проверяет переданное условие на основном потоке до установленного срока
   * При истечении срока фиксирует неудачу с заданным пояснением
   */
  fun waitFor(label:String,predicate:()->Boolean){
   val until=SystemClock.uptimeMillis()+4000
   var done=false
   while(!done && SystemClock.uptimeMillis()<until){
    main{done=predicate()}
    if(!done)SystemClock.sleep(10)
   }
   assertTrue(label,done)
  }
  try{
   main{
    scene.mediaStore=media;scene.viewport=viewport;scene.imageViewport=viewport
    scene.imagePreparationViewport=RectF(0f,0f,390f,1100f)
    media.remoteFile={_,preview,_->
     check(!preview)
     calls.incrementAndGet();check(release.await(8,TimeUnit.SECONDS))
     original to "image/jpeg"
    }
    scene.apply(snapshot)
   }
   waitFor("The downloaded photo is decoded before it is visible"){width()>64}
   assertEquals(0,calls.get())
   main{scene.apply(SceneSnapshot(scene.ticket,emptyList()));media.releaseIdleImages()}
   assertTrue(cachedFile.delete())
   main{scene.apply(snapshot)}
   waitFor("An uncached offscreen row can prepare its tiny preview"){width() in 1..64}
   assertEquals("Offscreen preparation must not start a remote original",0,calls.get())
   main{
    scene.viewport=RectF(viewport).apply{offset(0f,400f)};scene.imageViewport=scene.viewport
    scene.apply(snapshot)
   }
   waitFor("The missing original is requested when the row appears"){
    if(calls.get()==0)scene.apply(snapshot)
    calls.get()==1
   }
   main{assertTrue("The tiny preview must survive the start of the original request",width() in 1..64)}
   main{assertTrue(scene.loadActions().isEmpty())}
   release.countDown()
   waitFor("Sharp local pixels replace the preview"){width()>64}
   main{
    val expected=scene.preview(1)
    try{
     scene.apply(SceneSnapshot(scene.ticket,emptyList()))
     scene.apply(snapshot)
     scene.preview(1)?.use{assertSame(expected?.bitmap,it.bitmap)} ?: fail("The cached frame must bind immediately")
     assertTrue(scene.loadActions().isEmpty())
     assertEquals(1,calls.get())
    }finally{expected?.close()}
   }
  }finally{release.countDown();main{scene.close();media.close()};h.close()}
 }



 /**
  * Запрашивает готовность начального кадра у ещё не измеренной области истории
  * Пока геометрии нет, ответ должен оставаться ожидающим
  * Закрытие обязано завершить это ожидание ошибкой вместо потерянного продолжения навигации
  */
 @Test fun closingAnInitialViewportResolvesItsPendingWaiter(){
  var result:Result<Unit>?=null
  main{
   val host=ChatScrollHost(i.targetContext)
   host.prepareInitialFrame{result=it}
   assertNull("The unmeasured viewport is still preparing",result)
   host.close()
  }
  assertTrue("Closing must cancel, not leak the pending navigation",result?.isFailure==true)
 }

 /**
  * Заполняет запас декодированных фото и отдельный крупный исходник, затем выполняет вложенные области сохранения для навигации
  * Ошибка внутри области должна передаться, оставив только ограниченный обычный запас без крупного исходника
  * После выхода обычная очистка снова обязана освобождать весь неиспользуемый набор
  * Давление памяти и закрытие сохраняют возможность полной очистки даже внутри навигации
  */
 @Test fun navigationEvictsOriginalsBoundsNormalPixelsAndRestoresPurgeAfterFailure(){
  val h=RepositoryHarness.open(File(i.targetContext.cacheDir,"navigation-bound-"+UUID.randomUUID()))
  lateinit var media:MediaStore;main{media=MediaStore(h.store,h.repo)}
  try{
   val assets=(0 until 6).map{attachment(photo(media))}
   for(asset in assets)decoded(media,asset,1024).close()
   decoded(media,assets.last(),2048).close()
   var before=0;main{before=media.bytesUsed};assertTrue(before>8*1024*1024)
   main{
    runCatching{media.withNavigationCache{media.withNavigationCache{media.releaseIdleImages();throw IllegalStateException("owned test failure")}}}.onSuccess{fail("Scope must propagate the original failure")}
    assertTrue(media.bytesUsed in 1..8*1024*1024)
    assertNull(media.cachedThumbnail(assets.last(),ThumbnailKey(2048)))
    val recent=media.cachedThumbnail(assets.last(),ThumbnailKey(1024));assertNotNull(recent);recent?.close()
    media.releaseIdleImages();assertEquals(0,media.bytesUsed)
   }
   for(closeStore in listOf(false,true)){
    decoded(media,assets.last(),512).close()
    main{
     media.withNavigationCache{if(closeStore)media.close()else media.trimMemory()}
     assertEquals(0,media.bytesUsed);assertNull(media.cachedThumbnail(assets.last(),ThumbnailKey(512)))
    }
   }
  }finally{main{media.close()};h.close()}
 }

 /**
  * Заранее декодирует фото и возвращает видимую ссылку в сохранённый запас
  * Новая привязка ячейки должна до возврата вызова получить те же готовые пиксели
  * Промежуточные состояние загрузки и действие отмены недопустимы
  */
 @Test fun cachedPhotoIsReadyDuringBindWithoutAnIntermediateLoadingControl(){
  val h=RepositoryHarness.open(File(i.targetContext.cacheDir,"warm-slot-"+UUID.randomUUID()))
  lateinit var media:MediaStore;lateinit var slot:MediaImageSlot
  main{media=MediaStore(h.store,h.repo);slot=MediaImageSlot(media){}}
  try{
   val asset=attachment(photo(media))
   val decoded=awaitResult<PreparedImage>{callback->main{media.thumbnail(asset,ThumbnailKey(512),0){_,result->callback(result)}};Cancellation{}}
   val expected=decoded.bitmap;assertNotNull(expected);decoded.close()
   var ready=false;var loading=true;var action=true;var actual:Bitmap?=null
   main{
    slot.bind(asset,RectF(0f,0f,240f,180f),maxPixel=512,kind=MessageKind.PHOTO)
    ready=slot.ready;loading=slot.loading;action=slot.loadAction()!=null
    slot.preview()?.let{actual=it.bitmap;it.close()}
   }
   assertTrue("A RAM hit must paint before bind returns, without waiting for the main queue",ready)
   assertFalse("Cached pixels must never enter the loading state",loading)
   assertFalse("A decoded cached image has no cancellable load",action)
   assertSame("Navigation reuses the decoded pixels, not a second decode",expected,actual)
  }finally{main{slot.close();media.close()};h.close()}
 }


 /**
  * Открывает обычное окно только в отдельной пустой проверочной установке
  * До запуска требует отсутствие сохранённого аккаунта, поэтому проверка не подключается к публичному серверу
  * Сама проверяемая история создаётся отдельно через обычные владельцы базы и медиа
  */
 private fun emptyActivity():MainActivity {
  val context=i.targetContext
  check(context.packageName.endsWith(".cleanup")){"Нужна отдельная проверочная установка"}
  check(dev.kirill.kandinsky.chat.network.NetworkCredentials(context).load()==null){"Проверка требует пустую установку без аккаунта"}
  val device=UiDevice.getInstance(i);device.wakeUp()
  androidx.test.uiautomator.Configurator.getInstance().waitForIdleTimeout=0
  val activity=i.startActivitySync(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.apply {
   addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
  }) as MainActivity
  val until=SystemClock.uptimeMillis()+5000
  var ready=false
  while(!ready && SystemClock.uptimeMillis()<until) {
   main {
    val content=activity.findViewById<android.view.ViewGroup>(android.R.id.content)
    ready=activity.isForeground && !activity.isDestroyed && !activity.isFinishing &&
     (0 until content.childCount).any{content.getChildAt(it) is dev.kirill.kandinsky.chat.account.AccountEntryView}
   }
   if(!ready)SystemClock.sleep(10)
  }
  if(!ready)main {
   val content=activity.findViewById<android.view.ViewGroup>(android.R.id.content)
   val children=(0 until content.childCount).map{content.getChildAt(it).javaClass.name}
   println("KANDINSKY_CACHE_HOST children=$children foreground=${activity.isForeground} focus=${activity.hasWindowFocus()} destroyed=${activity.isDestroyed} finishing=${activity.isFinishing}")
  }
  if(!ready){main{activity.finish()};error("Не открылась пустая форма входа")}
  return activity
 }
 /**
  * Получает действительную область истории из оболочки чата
  * Проверка читает сцену этой области, а не отдельную копию подготовленного содержимого
  */
 private fun host(chrome:ConversationChrome)=ConversationChrome::class.java.getDeclaredField("timeline").apply{isAccessible=true}.get(chrome) as ChatScrollHost
 /**
  * Возвращает сцену переданной области истории для проверки первого подготовленного изображения
  * Временные ссылки на изображение закрываются вызывающей проверкой
  */
 private fun scene(host:ChatScrollHost)=ChatScrollHost::class.java.getDeclaredField("scene").apply{isAccessible=true}.get(host) as RetainedChatScene
 /**
  * Подключает к настоящему окну оболочку чата с отдельной местной базой проверки
  * Ожидает её обычное завершение подготовки начального кадра
  * В момент завершения уже должны существовать видимые фрагменты и готовые пиксели фотографии
  */
 private fun openConversation(activity:MainActivity,runtime:LocalRuntime,row:Message):Pair<ConversationChrome,dev.kirill.kandinsky.chat.conversation.ConversationState> {
  val model=dev.kirill.kandinsky.chat.conversation.ConversationState(runtime.repository)
  awaitResult<Unit>{callback->main{model.open(2,callback)};Cancellation{}}
  val done=CountDownLatch(1);var outcome:Result<Unit>?=null
  lateinit var chrome:ConversationChrome
  main {
   chrome=ConversationChrome(activity,2,model,runtime){}
   activity.setContentView(chrome)
   chrome.openHistory(runtime.repository,null){result->outcome=result;done.countDown()}
  }
  assertTrue("Начальный кадр должен завершить подготовку",done.await(8,TimeUnit.SECONDS))
  checkNotNull(outcome).getOrThrow()
  main {
   val host=host(chrome);val scene=scene(host)
   assertTrue("Подготовлены настоящие фрагменты истории",host.metrics.liveFragments>0)
   assertTrue("Фото готово в первом показанном кадре",scene.imageReady(row.id))
   assertTrue("У готового фото нет действия загрузки",scene.loadActions().isEmpty())
  }
  return chrome to model
 }
 /**
  * Заранее декодирует настоящее фото и открывает историю обычной оболочкой чата
  * Завершение открытия должно означать готовый рисунок без промежуточного загрузчика
  * Готовые пиксели берутся из существующего запаса без повторного декодирования
  */
 @Test fun openHistoryCompletesWithTheInitialPhotoFrameNotOnlyMetadata(){
  val activity=emptyActivity();val h=RepositoryHarness.open(File(i.targetContext.cacheDir,"initial-photo-"+UUID.randomUUID()))
  lateinit var runtime:LocalRuntime;var chrome:ConversationChrome?=null;var model:dev.kirill.kandinsky.chat.conversation.ConversationState?=null
  main{runtime=LocalRuntime(activity,h.store)}
  try {
   val draft=photo(runtime.media)
   val row=awaitResult<MessageDelta>{callback->main{runtime.media.commit(listOf(draft),SendCommand(UUID.randomUUID().toString(),2,MessageKind.PHOTO),callback)};Cancellation{}}.inserted.single()
   val warm=decoded(runtime.media,row.attachments.single(),1024);val expected=warm.bitmap;warm.close()
   val opened=openConversation(activity,runtime,row);chrome=opened.first;model=opened.second
   main{scene(host(opened.first)).preview(row.id)?.use{assertSame(expected,it.bitmap)} ?: fail("Нет готового фото")}
  }finally{main{chrome?.let{host(it).close()};model?.close();runtime.playback.close();runtime.media.close();runtime.admission.close();activity.finish()};i.waitForIdleSync();h.close()}
 }
 /**
  * Закрывает подготовленный экран чата в пределах обычного сохранения изображений для навигации
  * После нового открытия первые пиксели должны совпасть с уже декодированным фото без загрузчика
  * Обычное скрытие содержимого после перехода по-прежнему должно освобождать неиспользуемые изображения
  */
 @Test fun conversationRoundTripKeepsTheFirstPhotoDrawableAndBackgroundStillPurges(){
  val activity=emptyActivity();val h=RepositoryHarness.open(File(i.targetContext.cacheDir,"return-photo-"+UUID.randomUUID()))
  lateinit var runtime:LocalRuntime;var chrome:ConversationChrome?=null;var model:dev.kirill.kandinsky.chat.conversation.ConversationState?=null
  main{runtime=LocalRuntime(activity,h.store)}
  try {
   val draft=photo(runtime.media)
   val row=awaitResult<MessageDelta>{callback->main{runtime.media.commit(listOf(draft),SendCommand(UUID.randomUUID().toString(),2,MessageKind.PHOTO),callback)};Cancellation{}}.inserted.single()
   val first=openConversation(activity,runtime,row);chrome=first.first;model=first.second
   var expected:Bitmap?=null
   main {
    scene(host(first.first)).preview(row.id)?.use{expected=it.bitmap}
    assertNotNull(expected)
    runtime.media.withNavigationCache {
     host(first.first).close();first.second.close()
     activity.setContentView(android.widget.FrameLayout(activity))
     runtime.media.releaseIdleImages()
    }
    assertTrue(runtime.media.bytesUsed in 1..8*1024*1024)
   }
   val next=openConversation(activity,runtime,row);chrome=next.first;model=next.second
   main {
    scene(host(next.first)).preview(row.id)?.use{assertSame(expected,it.bitmap)} ?: fail("Нет сохранённого фото")
    host(next.first).setMediaVisible(false)
   }
   val until=SystemClock.uptimeMillis()+2000;var bytes=Int.MAX_VALUE
   while(bytes>0 && SystemClock.uptimeMillis()<until){main{bytes=runtime.media.bytesUsed};SystemClock.sleep(10)}
   assertEquals("Скрытие вне навигации освобождает изображения",0,bytes)
  }finally{main{chrome?.let{host(it).close()};model?.close();runtime.playback.close();runtime.media.close();runtime.admission.close();activity.finish()};i.waitForIdleSync();h.close()}
 }
}
