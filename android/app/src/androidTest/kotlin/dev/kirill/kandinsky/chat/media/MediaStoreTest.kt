package dev.kirill.kandinsky.chat.media
import dev.kirill.kandinsky.chat.support.TestFixtures
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.rendering.*
import dev.kirill.kandinsky.chat.support.*
import dev.kirill.kandinsky.chat.media.kernel.*
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
class MediaStoreTest {
 /**
  * Задерживает получение исходного фото и переводит отдельную генерацию в серверное состояние готовности
  * Пока действительные пиксели не показаны, сцена должна сохранять тот же объект указателя загрузки
  * После получения рисунка повторное применение состояния должно убрать указатель
  * Остальная ожидающая ячейка остаётся частью той же группы
  */
 @Test fun studioPhotoKeepsTheSameIndicatorUntilPixelsArePresented(){
  val h=RepositoryHarness.open(File(context.cacheDir,"studio-delivery-"+UUID.randomUUID()))
  val media=MediaStore(h.store,h.repo,studioOriginals=true)
  val file=File(h.root,"studio-delivery.jpg")
  TestFixtures.assets.open("media/"+photoName()).use{source->file.outputStream().use{source.copyTo(it)}}
  val gate=CountDownLatch(1)
  val displayed=CountDownLatch(1)
  val scene=RetainedChatScene()
  val photo=Attachment("net-1-result-first","remote/first.jpg","image/jpeg",file.length(),1024,1024,1,null,"first")
  /**
   * Создаёт снимок сцены с двумя местами генерации и выбранной готовностью первого
   * Меняет версию, наличие вложения и процент согласованно с переданным состоянием
   * Используется для сравнения одного и того же указателя до появления пикселей и после
   */
  fun snapshot(ready:Boolean):SceneSnapshot {
   val cells=listOf(StudioGeneration.Slot("first",if(ready)StudioGeneration.Stage.SUCCEEDED else StudioGeneration.Stage.RUNNING,
    if(ready)"first" else null,if(ready)100 else 94),StudioGeneration.Slot("second",StudioGeneration.Stage.QUEUED,null,null))
   val generation=StudioGeneration(StudioGeneration.Stage.RUNNING,"image","1:1",null,null,slots=cells)
   val row=Message(1,2,OrderKey(1,1),MessageDirection.INCOMING,0,if(ready)MessageKind.PHOTO else MessageKind.TEXT,
    "","",null,if(ready)2 else 1,if(ready)listOf(photo) else emptyList(),studioGeneration=generation)
   val prepared=PreparedText(row,TextLayoutKey(1,row.revision,300,17,1))
   return SceneSnapshot(scene.ticket,listOf(SceneFragment(prepared,0,android.graphics.RectF(0f,0f,prepared.width,prepared.height))))
  }
  lateinit var before:SceneSnapshot
  lateinit var after:SceneSnapshot
  lateinit var indicator:StudioProgressView
  try{
   main{
    val container=android.widget.FrameLayout(context)
    media.remoteFile={_,preview,_->check(!preview && gate.await(8,TimeUnit.SECONDS));file to "image/jpeg"}
    scene.mediaStore=media
    scene.movingContainer=container
    scene.viewport=android.graphics.RectF(0f,0f,400f,650f)
    scene.onImageReady={if(scene.imageReady(1))displayed.countDown()}
    before=snapshot(false);after=snapshot(true)
    scene.apply(before)
    indicator=container.getChildAt(0) as StudioProgressView
    scene.apply(after)
    assertSame(indicator,container.getChildAt(0))
    assertFalse(scene.imageReady(1))
   }
   gate.countDown()
   assertTrue("Actual photo pixels are presented",displayed.await(6,TimeUnit.SECONDS))
   main{
    scene.apply(after)
    assertNull(indicator.parent)
   }
  }finally{
   gate.countDown()
   main{scene.close();media.close()}
   h.close()
  }
 }
 /**
  * Готовит две отдельные сцены с четырьмя фото каждая и задерживает загрузку нового разговора
  * Пока прежняя сцена ещё удерживает изображения, следующая должна получить все четыре своих результата
  * После готовности не должно оставаться действий загрузки
  * Пиковый объём учтённых изображений обязан оставаться в заданном пределе
  */
 @Test fun fourPhotoAlbumsCanOverlapDuringChatNavigation(){
  val h=RepositoryHarness.open(File(context.cacheDir,"album-navigation-"+UUID.randomUUID()))
  val media=MediaStore(h.store,h.repo,studioOriginals=true)
  val original=File(h.root,"album.png")
  val pixels=Bitmap.createBitmap(1024,1024,Bitmap.Config.ARGB_8888)
  try{original.outputStream().use{assertTrue(pixels.compress(Bitmap.CompressFormat.PNG,100,it))}}finally{pixels.recycle()}
  val gate=CountDownLatch(1)
  val old=RetainedChatScene()
  val next=RetainedChatScene()
  /**
   * Создаёт готовую группу из четырёх разных адресов фото для указанной сцены и сообщения
   * Возвращает подготовленный фрагмент с поколением именно переданной сцены
   * Так две одновременно существующие истории не смешивают номера вложений
   */
  fun snapshot(scene:RetainedChatScene,id:Long,prefix:String):SceneSnapshot{
   val photos=(0 until 4).map{index->
    val key="$prefix-$index"
    Attachment(key,"remote/$key.png","image/png",original.length(),1024,1024,1,null,key)
   }
   val generation=StudioGeneration(StudioGeneration.Stage.SUCCEEDED,"image","1:1",null,null,
    slots=photos.map{StudioGeneration.Slot(it.id,StudioGeneration.Stage.SUCCEEDED,it.thumbnailKey,100)})
   val row=Message(id,id,OrderKey(id,id),MessageDirection.INCOMING,0,MessageKind.PHOTO,
    "","",null,1,photos,studioGeneration=generation)
   val prepared=PreparedText(row,TextLayoutKey(id,1,300,17,3))
   return SceneSnapshot(scene.ticket,listOf(SceneFragment(prepared,0,android.graphics.RectF(0f,0f,prepared.width,prepared.height))))
  }
  val oldReady=CountDownLatch(1)
  val nextReady=CountDownLatch(1)
  /**
   * Читает признак готовности каждого из четырёх изображений заданного сообщения в сцене
   * Возвращает четыре отдельных значения без изменения состояния загрузки
   */
  fun ready(scene:RetainedChatScene,id:Long)=(0 until 4).map{scene.imageReady(id,it)}
  try{
   main{
    media.remoteFile={attachment,preview,_->
     check(!preview)
     if(attachment.id.startsWith("new"))check(gate.await(8,TimeUnit.SECONDS))
     original to "image/png"
    }
    for(scene in listOf(old,next)){
     scene.mediaStore=media
     scene.viewport=android.graphics.RectF(0f,0f,390f,650f)
    }
    old.onImageReady={if(ready(old,1).all{it})oldReady.countDown()}
    next.onImageReady={if(ready(next,2).all{it})nextReady.countDown()}
    old.apply(snapshot(old,1,"old"))
   }
   assertTrue("The outgoing album has four displayed photos",oldReady.await(5,TimeUnit.SECONDS))
   main{next.apply(snapshot(next,2,"new"))}
   gate.countDown()
   assertTrue("The incoming album has four displayed photos while the old chat retains its frames",nextReady.await(5,TimeUnit.SECONDS))
   main{
    assertTrue(next.loadActions().isEmpty())
    assertTrue(media.peakBytesUsed<=48*1024*1024)
   }
  }finally{
   gate.countDown()
   main{old.close();next.close();media.close()}
   h.close()
  }
 }
 /**
  * Сначала заполняет обычный предел разрешений движущихся предварительных изображений
  * Для видимой группы генераций допускает ещё два разрешения до общего числа четыре
  * Следующие обычные и групповые запросы должны отклоняться, все полученные разрешения возвращаются
  */
 @Test fun visibleStudioAlbumCanReserveFourVideoPreviews() {
  main {
   val leases = mutableListOf<PlaybackLease>()
   try {
    repeat(2) { leases += checkNotNull(MediaAdmission.reservePreview {}) }
    assertNull(MediaAdmission.reservePreview {})
    repeat(2) { leases += checkNotNull(MediaAdmission.reservePreview(studioAlbum = true) {}) }
    assertEquals(4, MediaAdmission.previewOwnerCount)
    assertNull(MediaAdmission.reservePreview(studioAlbum = true) {})
   } finally {
    leases.forEach { it.release() }
   }
  }
 }
 /**
  * Сохраняет намерение отправки выбранного фото и проверяет защиту от старого или недопустимого черновика
  * Повреждённое намерение должно остановить восстановление до удаления любых исходников
  * После восстановления правильных данных очистка убирает только непривязанные импорты
  * Удаление последней ссылки намерения позволяет затем убрать и прежний выбранный исходник
  */
 @Test fun selectionIntentProtectsOriginalsAndCorruptionStopsRecoveryBeforeDeletion(){
  val h=RepositoryHarness.open(File(context.cacheDir,"selection-ownership-"+UUID.randomUUID()));val media=MediaStore(h.store,h.repo)
  /**
   * Подготавливает выбранное настоящее фото в отдельном хранилище выбора
   * Начинает работу на основном потоке и ждёт результат через общий помощник
   */
  fun selection()=awaitResult<AttachmentDraft>{cb->main{media.stageSelection({TestFixtures.assets.open("media/"+photoName())},"image/jpeg",cb)};Cancellation{}}
  val selected=selection();val orphan=selection();val camera=stage(media)
  val command=SendCommand(UUID.randomUUID().toString(),2,MessageKind.PHOTO,attachments=listOf(selected))
  try{
   awaitResult<Unit>{h.repo.saveDraft(2,Draft(text="Текущий",revision=5,pendingMediaBatch=listOf(command)),it)}
   awaitResult<Unit>{h.repo.saveDraft(2,Draft(text="Устаревший",revision=4),it)}
   val current=awaitResult<Draft>{h.repo.draft(2,it)};assertEquals("Текущий",current.text);assertEquals(listOf(command),current.pendingMediaBatch)
   val invalid=command.copy(attachments=emptyList())
   val error=runCatching{awaitResult<Unit>{h.repo.saveDraft(2,Draft(text="Недопустимый",revision=6,pendingMediaBatch=listOf(invalid)),it)}}.exceptionOrNull()
   assertEquals(RepoError.INVALID_INPUT,(error as? RepoException)?.reason)
   assertEquals(current,awaitResult<Draft>{h.repo.draft(2,it)})
   val payload=awaitResult<String>{cb->h.store.submit({db->
    val payload=db.rawQuery("SELECT payload FROM media_selection_intents WHERE chatID=2",null).use{it.moveToFirst();it.getString(0)}
    db.execSQL("UPDATE media_selection_intents SET payload='{' WHERE chatID=2");payload
   },cb)}
   assertTrue("Malformed intent cannot authorize a deletion sweep",runCatching{recover(media)}.isFailure)
   for(asset in listOf(selected,orphan,camera))assertTrue(media.fileURL(asset.relativePath).isFile)
   awaitResult<Unit>{cb->h.store.submit({db->db.execSQL("UPDATE media_selection_intents SET payload=? WHERE chatID=2",arrayOf(payload));Unit},cb)}
   awaitResult<Unit>{cb->main{media.discardUnpublishedImports(listOf(selected,orphan,camera),cb)};Cancellation{}}
   assertTrue(media.fileURL(selected.relativePath).isFile)
   for(asset in listOf(orphan,camera))assertFalse(media.fileURL(asset.relativePath).exists())
   recover(media);assertTrue(media.fileURL(selected.relativePath).isFile)
   awaitResult<Unit>{h.repo.saveDraft(2,Draft(revision=6),it)};recover(media)
   assertFalse(media.fileURL(selected.relativePath).exists())
  }finally{main{media.close()};h.close()}
 }
 private val i=InstrumentationRegistry.getInstrumentation();private val context=i.targetContext
 /**
  * Синхронно выполняет переданное действие на основном потоке приложения
  * Используется для владельцев медиа, сцен, разрешений и публикации изображений
  */
 private fun main(action:()->Unit)=i.runOnMainSync(action)
 /**
  * Читает описание встроенного проверочного набора и выбирает первую запись обычной фотографии
  * Возвращает имя настоящего файла для последующих опытов подготовки и хранения
  */
 private fun photoName():String{val a=JSONObject(TestFixtures.assets.open("manifest.json").bufferedReader().use{it.readText()}).getJSONArray("files");return (0 until a.length()).map{a.getJSONObject(it)}.first{it.getString("mime")=="image/jpeg"}.getString("fileName")}
 /**
  * Подготавливает фото из переданного источника либо стандартного файла проверочного набора
  * Вызывает владельца медиа на основном потоке и возвращает сохранённый черновик вложения
  */
 private fun stage(media:MediaStore,open:()->java.io.InputStream={TestFixtures.assets.open("media/"+photoName())}):AttachmentDraft=awaitResult{cb->main{media.stage(open,"image/jpeg",cb)};Cancellation{}}
 /**
  * Запускает восстановление и адресную очистку файлов текущего владельца медиа на основном потоке
  * Ожидает результат и передаёт ошибку вызывающей проверке
  */
 private fun recover(media:MediaStore){awaitResult<Unit>{cb->main{media.recover(cb)};Cancellation{}}}
 /**
  * Связывает одно подготовленное фото с отправкой в проверочный чат под переданным номером намерения
  * Возвращает изменение сообщений после сохранения через владельца медиа
  */
 private fun commit(media:MediaStore,d:AttachmentDraft,token:String):MessageDelta=awaitResult{cb->main{media.commit(listOf(d),SendCommand(token,2,MessageKind.PHOTO),cb)};Cancellation{}}
 /**
  * Копирует метаданные подготовленного черновика в обычное описание вложения
  * Путь, размеры, ориентация и длительность сохраняются без повторного чтения исходника
  */
 private fun attachment(d:AttachmentDraft)=Attachment(d.id,d.relativePath,d.mime,d.byteSize,d.rawWidth,d.rawHeight,d.orientation,d.durationMs,d.thumbnailKey)
 /**
  * Получает полноразмерный рисунок фото и передаёт его новому владельцу видимой ячейки
  * Задерживает следующее получение файла, позволяя малому предварительному изображению прийти позже
  * Позднее малое изображение не должно заменить уже переданные качественные пиксели
  */
 @Test fun transferredPhotoIsNotReplacedByALateMicroPreview(){
  val h=RepositoryHarness.open(File(context.cacheDir,"preview-order-"+UUID.randomUUID()));val media=MediaStore(h.store,h.repo)
  val draft=stage(media);val a=attachment(draft).copy(tinyPreview=checkNotNull(draft.tinyPreview))
  val full=awaitResult<PreparedImage>{cb->main{media.thumbnail(a,ThumbnailKey(1024),0){_,value->cb(value)}};Cancellation{}}
  val expected=checkNotNull(full.bitmap);assertTrue(maxOf(expected.width,expected.height)>64)
  val held=CountDownLatch(1);val file=media.fileURL(a.relativePath);lateinit var slot:MediaImageSlot
  try{
   main{
    media.releaseIdleImages();media.remoteFile={_,_,token->token.onCancel{held.countDown()};held.await(5,TimeUnit.SECONDS);file to "image/jpeg"}
    slot=MediaImageSlot(media){};slot.bind(a,android.graphics.RectF(0f,0f,260f,200f),maxPixel=1024,showControls=false);slot.seed(checkNotNull(full.retained()))
   }
   val barrier=awaitResult<PreparedImage>{cb->main{media.tinyPreview(a,cb)};Cancellation{}}
   main{barrier.close();val shown=checkNotNull(slot.preview());try{assertSame("A late tiny JPEG must not downgrade the transferred photo",expected,shown.bitmap)}finally{shown.close()}}
  }finally{held.countDown();main{slot.close();full.close();media.close()};h.close()}
 }
 /**
  * Создаёт ещё завершающегося настоящего владельца предварительного показа
  * Отмена ожидающего ручного воспроизведения должна дать ровно один отказ без преждевременного освобождения показа
  * Новый захват обязан дождаться действительного окончания прежнего владельца
  * Только после подтверждённого завершения выдаётся новое разрешение
  */
 @Test fun cancelledManualReservationAndCaptureStillWaitForRetiringNativePreview(){
  val first=MediaAdmission();val replacement=MediaAdmission();lateinit var lifetime:PreviewLifetime
  main{val lease=checkNotNull(MediaAdmission.reservePreview{lifetime.retire()});lifetime=PreviewLifetime(lease);lifetime.begin()}
  try{
   val cancelled=CountDownLatch(1);val replies=AtomicInteger();lateinit var request:Cancellation
   main{request=first.requestPlayback{assertTrue(it.isFailure);replies.incrementAndGet();cancelled.countDown()};assertEquals(1,MediaAdmission.previewOwnerCount);assertNull(MediaAdmission.reservePreview{})}
   main{request.cancel()};assertTrue(cancelled.await(2,TimeUnit.SECONDS));assertEquals(1,replies.get())
   val granted=CountDownLatch(1);var capture:CaptureLease?=null
   main{replacement.acquire(CaptureKind.PHOTO){assertTrue(it.isSuccess);capture=it.getOrThrow();granted.countDown()}}
   assertFalse("Capture cannot overtake a retiring native preview",granted.await(100,TimeUnit.MILLISECONDS));main{assertNull(capture);assertEquals(1,MediaAdmission.previewOwnerCount);lifetime.end()}
   assertTrue(granted.await(2,TimeUnit.SECONDS));main{assertEquals(0,MediaAdmission.previewOwnerCount);capture!!.release()}
  }finally{main{lifetime.retire();first.close();replacement.close()}}
 }
 /**
  * Подменяет отдельный источник настоящими файлами двух размеров и записывает режимы обращений
  * Первое получение для истории допускает малое фото, а увеличенный просмотр должен запросить исходник
  * Проверяет размеры результатов, порядок обращений и ограниченный пиковый объём памяти
  */
 @Test fun viewerDecodeUsesOriginalAndDoesNotReuseTimelinePreview(){
  val h=RepositoryHarness.open(File(context.cacheDir,"viewer-original-"+UUID.randomUUID()));val media=MediaStore(h.store,h.repo)
  /**
   * Создаёт отдельное одноцветное фото переданных размеров в папке проверки
   * Возвращает записанный файл после освобождения временного массива пикселей
   * Он служит техническим образцом для различения малого и полного источника
   */
  fun imageFile(name:String,w:Int,height:Int):File{val f=File(h.root,name);val b=Bitmap.createBitmap(w,height,Bitmap.Config.ARGB_8888);b.eraseColor(android.graphics.Color.RED);f.outputStream().use{assertTrue(b.compress(Bitmap.CompressFormat.JPEG,95,it))};b.recycle();return f}
  val original=imageFile("original.jpg",2048,1536);val preview=imageFile("preview.jpg",256,192);val modes=Collections.synchronizedList(mutableListOf<Boolean>())
  val a=Attachment("viewer-original","remote/viewer-original.jpg","image/jpeg",100,2048,1536,1,null,"viewer-original")
  try{
   main{media.remoteFile={_,isPreview,_->modes.add(isPreview);(if(isPreview)preview else original) to "image/jpeg"}}
   val small=awaitResult<PreparedImage>{cb->main{media.thumbnail(a,ThumbnailKey(1024),0){_,value->cb(value)}};Cancellation{}}
   assertEquals(256,small.bitmap!!.width);main{small.close()}
   val full=awaitResult<PreparedImage>{cb->main{media.thumbnail(a,ThumbnailKey(2048),1){_,value->cb(value)}};Cancellation{}}
   try{assertEquals("The viewer needs the original instead of the timeline cache",2048,full.bitmap!!.width);assertEquals(listOf(true,false),modes.toList());assertTrue(media.peakBytesUsed<=48*1024*1024)}finally{main{full.close()}}
  }finally{main{media.close()};h.close()}
 }
 /**
  * В режиме исходников генерации загружает полное фото даже для небольшой ячейки истории
  * Следующий владелец галереи должен немедленно получить тот же массив готовых пикселей
  * Должно произойти одно обращение за исходником без отдельной малой копии
  * Пиковая учтённая память остаётся в заданном пределе
  */
 @Test fun studioPhotoTimelineAndGalleryReuseOriginalPixels(){
  val h=RepositoryHarness.open(File(context.cacheDir,"studio-original-"+UUID.randomUUID()))
  val media=MediaStore(h.store,h.repo,studioOriginals=true)
  /**
   * Создаёт отдельный технический файл фото заданного размера
   * Записывает его в папку проверки и освобождает временное изображение
   * Вызывающий опыт различает по этим размерам исходник и малую копию
   */
  fun fixture(name:String,width:Int,height:Int):File {
   val file=File(h.root,name)
   val image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
   try{file.outputStream().use{assertTrue(image.compress(Bitmap.CompressFormat.JPEG,95,it))}}finally{image.recycle()}
   return file
  }
  val original=fixture("studio-full.jpg",1280,768)
  val preview=fixture("studio-small.jpg",256,154)
  val photo=Attachment("studio","remote/studio.jpg","image/jpeg",200_000,1280,768,1,null,"studio")
  val requests=Collections.synchronizedList(mutableListOf<Boolean>())
  val displayed=CountDownLatch(1)
  lateinit var timeline:MediaImageSlot
  lateinit var gallery:MediaImageSlot
  val cell=android.graphics.RectF(0f,0f,350f,210f)
  main{
   media.remoteFile={_,small,_->requests.add(small);(if(small)preview else original) to "image/jpeg"}
   timeline=MediaImageSlot(media){if(timeline.ready && !timeline.loading)displayed.countDown()}
   gallery=MediaImageSlot(media){}
   timeline.bind(photo,cell,maxPixel=512,kind=dev.kirill.kandinsky.chat.domain.MessageKind.PHOTO)
  }
  try{
   assertTrue(displayed.await(5,TimeUnit.SECONDS))
   main{
    val first=checkNotNull(timeline.preview())
    try{
     assertEquals(1280,first.bitmap!!.width)
     assertEquals(768,first.bitmap!!.height)
     gallery.bind(photo,cell,maxPixel=384,showControls=false)
     assertTrue(gallery.ready)
     val reopened=checkNotNull(gallery.preview())
     try{assertSame(first.bitmap,reopened.bitmap)}finally{reopened.close()}
     assertEquals(listOf(false),requests.toList())
     assertTrue(media.peakBytesUsed<=48*1024*1024)
    }finally{first.close()}
   }
  }finally{main{gallery.close();timeline.close();media.close()};h.close()}
 }
 /**
  * Проверяет создание и сохранение малого предварительного фото в метаданных вложения
  * Повтор того же намерения с прежним неполным форматом не должен создавать новое сообщение
  * При заблокированном чтении малое фото обязано быть готово, а отмена загрузки и повтор работать последовательно
  * После доступности исходника указатель загрузки исчезает и показывается большее изображение
  */
 @Test fun metadataSurvivesStorageLegacyRetryAndBlockedThumbnail(){
  val h=RepositoryHarness.open(File(context.cacheDir,"metadata-"+UUID.randomUUID()));val media=MediaStore(h.store,h.repo)
  val draft=stage(media);val tiny=checkNotNull(draft.tinyPreview);assertTrue(tiny.length<=4096)
  val bytes=android.util.Base64.decode(tiny,android.util.Base64.NO_WRAP);val image=checkNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size));assertTrue(maxOf(image.width,image.height)<=64);image.recycle()
  val first=commit(media,draft,"preview-retry");val saved=first.inserted.first().attachments.first();assertEquals(tiny,saved.tinyPreview)
  val second=commit(media,draft.copy(tinyPreview=null,waveform=null),"preview-retry");assertEquals(first.inserted.first().id,second.inserted.first().id)
  val release=CountDownLatch(1);val ready=CountDownLatch(1);val slot=AtomicReference<MediaImageSlot>()
  main{media.remoteFile={_,_,token->token.onCancel{release.countDown()};release.await(5,TimeUnit.SECONDS);throw MediaException(MediaError.CANCELLED)};val s=MediaImageSlot(media){if(slot.get()?.ready==true)ready.countDown()};slot.set(s);s.bind(saved,android.graphics.RectF(0f,0f,200f,140f))}
  assertTrue("Tiny preview must bypass blocked I/O",ready.await(3,TimeUnit.SECONDS))
  main{val s=slot.get();assertTrue(s.loading);assertTrue(s.performLoadAction(100f,70f));assertFalse(s.loading);assertEquals("Повторить загрузку",s.loadAction()?.second);media.remoteFile=null;assertTrue(s.performLoadAction(100f,70f))}
  val end=System.nanoTime()+3_000_000_000L;while(System.nanoTime()<end){var loading=true;main{loading=slot.get().loading};if(!loading)break;Thread.sleep(10)}
  main{val s=slot.get();assertFalse(s.loading);assertNull(s.loadAction());val full=s.preview();assertTrue((full?.bitmap?.width ?:0)>64);full?.close();s.close()};release.countDown()
  main{media.close()};h.close()
 }

 /**
  * Запрашивает захват при действующем разрешении воспроизведения и проверяет отказ конкурирующему захвату
  * Одного ответа обработчика остановки недостаточно без возврата самого разрешения
  * После настоящего освобождения захват должен выдаваться
  * Закрытый владелец не должен принять позднюю выдачу, ответ отмены остаётся единственным
  */
 @Test fun captureAdmissionWaitsForProvenReleaseAndRejectsLateGrant(){
  val admission=MediaAdmission();val stopped=AtomicReference<()->Unit>()
  main{admission.installPlaybackStopper{stopped.set(it)}}
  val playback=awaitResult<PlaybackLease>{cb->main{admission.requestPlayback(cb)};Cancellation{}}
  val capture=AtomicReference<CaptureLease>();val granted=CountDownLatch(1)
  main{admission.acquire(CaptureKind.PHOTO){capture.set(it.getOrThrow());granted.countDown()}}
  assertTrue(runCatching{awaitResult<CaptureLease>{cb->main{admission.acquire(CaptureKind.PHOTO,cb)};Cancellation{}}}.isFailure)
  main{stopped.get().invoke()};assertNull(capture.get());assertEquals(1,admission.playbackActiveCount)
  main{playback.release()};assertTrue(granted.await(2,TimeUnit.SECONDS));assertEquals(1,admission.captureActiveCount)
  main{capture.get().release();admission.close()}
  val late=MediaAdmission();val lateStop=AtomicReference<()->Unit>();main{late.installPlaybackStopper{lateStop.set(it)}}
  val owner=awaitResult<PlaybackLease>{cb->main{late.requestPlayback(cb)};Cancellation{}}
  val cancelled=CountDownLatch(1);val replies=AtomicInteger()
  main{
   late.acquire(CaptureKind.PHOTO){assertTrue(it.isFailure);replies.incrementAndGet();cancelled.countDown()}
   late.close();lateStop.get().invoke();owner.release()
  }
  assertTrue(cancelled.await(2,TimeUnit.SECONDS));assertEquals(1,replies.get());assertEquals(0,late.captureActiveCount)
 }
 /**
  * После сохранения исходного фото внедряет ошибки до и после перемещения следующего файла
  * Восстановление должно сохранить привязанный исходник и удалить непривязанные остатки
  * Проверяет нехватку места при сохранении и потерю ответа после успешной записи
  * Точный повтор последнего намерения обязан вернуть существующий результат без повреждения базы
  */
 @Test fun crashRecoveryDiskFullAndLostReplyPreserveReferencedOriginal(){
  val h=RepositoryHarness.open(File(context.cacheDir,"media-"+UUID.randomUUID()));val media=MediaStore(h.store,h.repo)
  val original=stage(media);val first=commit(media,original,"media-one")
  assertEquals(original.relativePath,first.inserted.first().attachments.first().relativePath)
  for(fault in listOf(RepoFault.BEFORE_RENAME,RepoFault.AFTER_RENAME)){
   h.faults.arm(fault);assertTrue(runCatching{stage(media)}.isFailure);recover(media)
   assertTrue(File(h.root,"media/"+original.relativePath).isFile);assertEquals(1,File(h.root,"media/objects").listFiles()!!.size)
  }
  val full=stage(media);h.faults.arm(RepoFault.STORAGE_FULL);assertTrue(runCatching{commit(media,full,"media-full")}.isFailure)
  recover(media);assertFalse(File(h.root,"media/"+full.relativePath).exists())
  val lost=stage(media);h.faults.arm(RepoFault.AFTER_COMMIT_BEFORE_REPLY);assertTrue(runCatching{commit(media,lost,"media-lost")}.isFailure)
  recover(media);assertTrue(commit(media,lost,"media-lost").inserted.isNotEmpty());assertEquals(2,h.messageCount(2));assertEquals("ok",h.integrity())
  main{media.close()};h.close()
 }
 /**
  * Подготавливает крупное техническое фото и требует уменьшение до заданного размера с правильной ориентацией
  * Устаревшая привязка передачи кадра не должна принять результат и должна освободить его один раз
  * Повреждённый источник и запрещённое чтение обязаны дать соответствующие ошибки
  * Учтённая память не должна превысить установленный предел
  */
 @Test fun realImageDownsampleCorruptionPermissionAndStaleMailbox(){
  val h=RepositoryHarness.open(File(context.cacheDir,"media-"+UUID.randomUUID()));val media=MediaStore(h.store,h.repo)
  val large=File(h.root,"huge.jpg");val bitmap=Bitmap.createBitmap(4032,3024,Bitmap.Config.ARGB_8888)
  bitmap.eraseColor(android.graphics.Color.RED);large.outputStream().use{assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,90,it))};bitmap.recycle()
  val draft=stage(media){large.inputStream()}
  val decoded=awaitResult<PreparedImage>{cb->main{media.thumbnail(attachment(draft),ThumbnailKey(1024),42){ticket,result->assertEquals(42,ticket);cb(result)}};Cancellation{}}
  val image=decoded.bitmap!!;assertTrue(maxOf(image.width,image.height)<=1024);assertEquals(1,decoded.orientation)
  assertNotNull(PhotoPresentation.make(image.width,image.height,1,200.0,200.0,PhotoSurfaceStyle.FIT))
  main{
   val mailbox=MediaSurfaceMailbox<PreparedImage>(schedule={android.os.Handler(android.os.Looper.getMainLooper()).post(it)},render={})
   val binding=mailbox.begin()!!;mailbox.clear();assertFalse(mailbox.offer(binding,1,decoded));decoded.close();assertEquals(1,decoded.closeCount);mailbox.close();mailbox.flush()
  }
  assertTrue(runCatching{stage(media){"not an image".byteInputStream()}}.isFailure)
  val permission=runCatching{stage(media){throw SecurityException("test inaccessible source")}}.exceptionOrNull()
  assertTrue(permission is MediaException && permission.reason==MediaError.PERMISSION_DENIED)
  assertTrue(media.peakBytesUsed<=48*1024*1024);main{media.close()};h.close()
 }
 private class NativeDecodeProbe(val context:android.content.Context,val name:String){
  var bitmap:Bitmap?=TestFixtures.assets.open("media/"+name).use{BitmapFactory.decodeStream(it)}
  var closeCount=0;private set
  /**
   * Синхронно освобождает декодированное изображение вспомогательного владельца только при первом вызове
   * Обнуляет ссылку и сохраняет счётчик закрытия
   * Повторное освобождение не обращается к уже возвращённым пикселям
   */
  @Synchronized fun close(){if(closeCount==0){closeCount=1;bitmap?.recycle();bitmap=null}}
 }
 /**
  * Передаёт в настоящую очередь больше заданий, чем она может одновременно выполнять и ожидать
  * Задерживает два работающих декодирования и проверяет ограничение ожидающих
  * Закрывает очередь до завершения работ
  * Каждый созданный результат должен освободиться один раз без успешной публикации после закрытия
  */
 @Test fun decodeQueueHasTwoWorkersAndSixteenPending(){
  val queue=MediaDecodeQueue();val hold=CountDownLatch(1);val started=CountDownLatch(2);val finished=CountDownLatch(21)
  val probes=Collections.synchronizedList(mutableListOf<NativeDecodeProbe>());val name=photoName()
  main{repeat(21){queue.submit(work={val p=NativeDecodeProbe(context,name);probes.add(p);started.countDown();hold.await();p},dispose={it.close()}){result->result.onSuccess{it.close();fail("closed queue published")};finished.countDown()}}}
  assertTrue(started.await(2,TimeUnit.SECONDS));assertEquals(2,queue.runningCount);assertEquals(16,queue.pendingCount)
  main{queue.close()};hold.countDown();assertTrue(finished.await(3,TimeUnit.SECONDS));assertEquals(2,probes.size);assertTrue(probes.all{it.closeCount==1})
 }
 /**
  * Создаёт техническое фото с четырьмя цветными углами и записывает все восемь вариантов ориентации
  * Импорт должен сохранить исходные метаданные, а готовый рисунок применить поворот и отражение ровно один раз
  * Проверяет итоговые размеры и цвет каждого угла с допустимыми потерями кодирования
  */
 @Test fun allEightEXIFOrientationsAreAppliedOnce(){
  val h=RepositoryHarness.open(File(context.cacheDir,"orient-"+UUID.randomUUID()));val media=MediaStore(h.store,h.repo)
  val raw=Bitmap.createBitmap(40,24,Bitmap.Config.ARGB_8888)
  val colors=intArrayOf(android.graphics.Color.RED,android.graphics.Color.GREEN,android.graphics.Color.BLUE,android.graphics.Color.YELLOW)
  for(y in 0 until 24)for(x in 0 until 40)raw.setPixel(x,y,colors[(if(y<12)0 else 2)+(if(x<20)0 else 1)])
  val expected=arrayOf(intArrayOf(0,1,2,3),intArrayOf(1,0,3,2),intArrayOf(3,2,1,0),intArrayOf(2,3,0,1),intArrayOf(0,2,1,3),intArrayOf(2,0,3,1),intArrayOf(3,1,2,0),intArrayOf(1,3,0,2))
  for(orientation in 1..8){
   val file=File(h.root,"orientation-$orientation.jpg");file.outputStream().use{assertTrue(raw.compress(Bitmap.CompressFormat.JPEG,100,it))}
   android.media.ExifInterface(file.path).apply{setAttribute(android.media.ExifInterface.TAG_ORIENTATION,orientation.toString());saveAttributes()}
   val draft=stage(media){file.inputStream()};assertEquals(40,draft.rawWidth);assertEquals(24,draft.rawHeight);assertEquals(orientation,draft.orientation)
   val decoded=awaitResult<PreparedImage>{cb->main{media.thumbnail(attachment(draft),ThumbnailKey(1024),orientation){_,result->cb(result)}};Cancellation{}}
   val image=decoded.bitmap!!;assertEquals(if(orientation>=5)24 else 40,image.width);assertEquals(if(orientation>=5)40 else 24,image.height);assertEquals(1,decoded.orientation)
   val corners=listOf(4 to 4,image.width-5 to 4,4 to image.height-5,image.width-5 to image.height-5)
   for((n,p) in corners.withIndex()){
    val actual=image.getPixel(p.first,p.second);val reference=colors[expected[orientation-1][n]]
    for(shift in intArrayOf(16,8,0))assertEquals("orientation $orientation corner $n component $shift",((reference ushr shift) and 255).toDouble(),((actual ushr shift) and 255).toDouble(),25.0)
   };decoded.close()
  };raw.recycle();main{media.close()};h.close()
 }
 /**
  * Снимает права чтения с отдельного исходника и требует явную ошибку разрешения
  * Затем заменяет папку объектов символической ссылкой на соседние сохранённые данные
  * Восстановление должно отказаться от такой папки, не удаляя чужой файл
  * В конце удаляет только созданную ссылку и освобождает владельца
  */
 @Test fun unreadableSourceAndRecoveryCannotFollowDirectorySymlink(){
  val h=RepositoryHarness.open(File(context.cacheDir,"permission-"+UUID.randomUUID()));val media=MediaStore(h.store,h.repo)
  val denied=File(h.root,"denied.jpg");TestFixtures.assets.open("media/"+photoName()).use{input->denied.outputStream().use{input.copyTo(it)}}
  android.system.Os.chmod(denied.path,0)
  try{
   val error=runCatching{stage(media){denied.inputStream()}}.exceptionOrNull()
   assertTrue("native EACCES maps to permission denied",error is MediaException && error.reason==MediaError.PERMISSION_DENIED)
  }finally{android.system.Os.chmod(denied.path,android.system.OsConstants.S_IRUSR or android.system.OsConstants.S_IWUSR)}
  val outside=File(h.root,"preserved");outside.mkdirs();val original=File(outside,"original.txt");original.writeText("keep original")
  val objects=File(h.root,"media/objects");assertTrue(objects.delete());android.system.Os.symlink(outside.path,objects.path)
  val error=runCatching{recover(media)}.exceptionOrNull()
  assertTrue(error is MediaException && error.reason==MediaError.CORRUPT);assertEquals("keep original",original.readText())
  assertTrue(objects.delete());main{media.close()};h.close()
 }

 /**
  * Импортирует настоящие проверочные видео, голос, анимацию и наклейку системными средствами
  * Для временных медиа проверяет длительность, для визуальных получает действительный предварительный кадр
  * Сохраняет каждый результат сообщением и сверяет виды и единственное вложение у каждого
  */
 @Test fun nativeMediaFormatsStagePersistAndDecodePosters(){
  val h=RepositoryHarness.open(File(context.cacheDir,"formats-"+UUID.randomUUID()));val media=MediaStore(h.store,h.repo)
  val files=JSONObject(TestFixtures.assets.open("manifest.json").bufferedReader().use{it.readText()}).getJSONArray("files")
  val formats=listOf("video/mp4" to MessageKind.VIDEO,"audio/mp4" to MessageKind.VOICE,"image/gif" to MessageKind.GIF,"image/png" to MessageKind.STICKER)
  for((mime,kind) in formats){
   val name=(0 until files.length()).map{files.getJSONObject(it)}.first{it.getString("mime")==mime}.getString("fileName")
   val d=awaitResult<AttachmentDraft>{cb->main{media.stage({TestFixtures.assets.open("media/"+name)},mime,cb)};Cancellation{}}
   if(mime.startsWith("audio/") || mime.startsWith("video/"))assertTrue((d.durationMs ?: 0)>0)
   if(!mime.startsWith("audio/")){
    val frame=awaitResult<PreparedImage>{cb->main{media.thumbnail(attachment(d),ThumbnailKey(1024),1){_,r->cb(r)}};Cancellation{}}
    assertNotNull(frame.bitmap);frame.close()
   }
   awaitResult<MessageDelta>{cb->main{media.commit(listOf(d),SendCommand("format-"+mime,2,kind),cb)};Cancellation{}}
  }
  val rows=awaitResult<HistoryPage>{h.repo.history(PageRequest(2),it)}.rows
  assertEquals(formats.map{it.second},rows.map{it.kind});assertTrue(rows.all{it.attachments.size==1});main{media.close()};h.close()
 }
 /**
  * Записывает готовое изображение в настоящий системный список рисования
  * Закрытие владельца сохранённых изображений не должно уничтожить ещё удерживаемые пиксели
  * После отсоединения списка и возврата последней ссылки память должна освободиться ровно один раз
  */
 @Test fun nativeDisplayListLeaseSurvivesCacheCloseUntilDetach(){
  val h=RepositoryHarness.open(File(context.cacheDir,"lease-"+UUID.randomUUID()));val media=MediaStore(h.store,h.repo);val d=stage(media)
  val frame=awaitResult<PreparedImage>{cb->main{media.thumbnail(attachment(d),ThumbnailKey(1024),1){_,r->cb(r)}};Cancellation{}}
  main{
   val node=android.graphics.RenderNode("native image lease");val bitmap=frame.bitmap!!
   val canvas=node.beginRecording(bitmap.width,bitmap.height);canvas.drawBitmap(bitmap,0f,0f,android.graphics.Paint());node.endRecording()
   assertTrue(node.hasDisplayList());media.close();assertNotNull(frame.bitmap);assertFalse(bitmap.isRecycled);assertTrue(media.bytesUsed>0)
   node.discardDisplayList();frame.close();assertEquals(0,media.bytesUsed);assertEquals(1,frame.closeCount)
  };h.close()
 }

 /**
  * Готовит фото и оставляет его только в запасе неиспользуемых изображений владельца медиа
  * Закрывает связанную область истории и требует освобождения этого неиспользуемого запаса
  * Повторное закрытие и освобождение остальных владельцев должны оставаться допустимыми
  */
 @Test fun chatCloseReleasesIdleDecodedCache(){
  val h=RepositoryHarness.open(File(context.cacheDir,"chat-cache-"+UUID.randomUUID()));lateinit var media:MediaStore;lateinit var c:MediaCoordinator;lateinit var admission:MediaAdmission
  lateinit var host:dev.kirill.kandinsky.chat.rendering.ChatScrollHost
  main{media=MediaStore(h.store,h.repo);admission=MediaAdmission();c=MediaCoordinator(admission,media);host=dev.kirill.kandinsky.chat.rendering.ChatScrollHost(context);host.configurePlayback(c,media)}
  val d=stage(media);val f=awaitResult<PreparedImage>{cb->main{media.thumbnail(attachment(d),ThumbnailKey(1024),1){_,r->cb(r)}};Cancellation{}}
  try{main{f.close();assertTrue(media.bytesUsed>0);host.close();assertEquals("Leaving the chat must release its idle decoded cache",0,media.bytesUsed)}}
  finally{main{host.close();c.close();media.close();admission.close()};h.close()}
 }

 /**
  * Последовательно готовит много разных фото и каждый раз возвращает видимую ссылку
  * После такого прохода сохранённый декодированный набор не должен расти до всех просмотренных изображений
  * Проверяет установленный предел относительно размера одного результата и закрывает владельца
  */
 @Test fun serialScrollBurstKeepsOnlySmallDecodedHotSet(){
  val h=RepositoryHarness.open(File(context.cacheDir,"hot-set-"+UUID.randomUUID()));lateinit var media:MediaStore
  main{media=MediaStore(h.store,h.repo)};var one=0
  try{
   repeat(24){
    val d=stage(media);val f=awaitResult<PreparedImage>{cb->main{media.thumbnail(attachment(d),ThumbnailKey(1024),1){_,r->cb(r)}};Cancellation{}}
    main{one=f.bitmap!!.allocationByteCount;f.close()}
   }
   main{assertTrue("Serial scroll must not retain every decoded image: ${media.bytesUsed}",media.bytesUsed<=16*one)}
  }finally{main{media.close()};h.close()}
 }

}
