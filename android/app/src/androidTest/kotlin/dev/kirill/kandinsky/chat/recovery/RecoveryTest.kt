package dev.kirill.kandinsky.chat.recovery
import dev.kirill.kandinsky.chat.support.TestFixtures
import androidx.test.platform.app.InstrumentationRegistry
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.media.*
import dev.kirill.kandinsky.chat.support.*
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
class RecoveryTest{
 private val i=InstrumentationRegistry.getInstrumentation();private val context=i.targetContext
 /**
  * Синхронно выполняет действие на основном потоке приложения
  * Вызывающий сценарий использует его для владельцев воспроизведения, сцены и медиа
  */
 private fun main(action:()->Unit)=i.runOnMainSync(action)
 /**
  * С короткими паузами проверяет требуемое состояние на основном потоке
  * При истечении установленного срока фиксирует неготовность освобождения или подготовки ресурса
  */
 private fun waitFor(condition:()->Boolean){val end=android.os.SystemClock.uptimeMillis()+8000;var ready=false;while(!ready && android.os.SystemClock.uptimeMillis()<end){main{ready=condition()};if(!ready)Thread.sleep(10)};assertTrue("Native release/preparation exceeded8seconds",ready)}
 /**
  * Сначала подтверждает, что проверочный видеофайл действительно содержит звуковую дорожку
  * Для обычного и круглого видео проверяет беззвучный запуск и продвижение воспроизведения
  * Воспроизведение должно продвигаться, а повтор сохранять выключенный звук
  * В завершении ждёт освобождения разрешения проигрывателя
  */
 @Test fun studioVideoWithAudioAutoplaysMuted(){
  val source="media/video-00.mp4"
  val extractor=android.media.MediaExtractor()
  try{
   TestFixtures.assets.openFd(source).use{extractor.setDataSource(it.fileDescriptor,it.startOffset,it.length)}
   assertTrue((0 until extractor.trackCount).any{extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/")==true})
  }finally{extractor.release()}
  val h=RepositoryHarness.open(File(context.cacheDir,"studio-silent-"+UUID.randomUUID()))
  val admission=MediaAdmission()
  lateinit var media:MediaStore
  lateinit var player:MediaCoordinator
  main{media=MediaStore(h.store,h.repo);player=MediaCoordinator(admission,media)}
  try{
   val d=awaitResult<AttachmentDraft>{cb->main{media.stage({TestFixtures.assets.open(source)},"video/mp4",cb)};Cancellation{}}
   val attachment=Attachment(d.id,d.relativePath,d.mime,d.byteSize,d.rawWidth,d.rawHeight,d.orientation,d.durationMs,d.thumbnailKey)
   for(kind in listOf(MessageKind.VIDEO,MessageKind.ROUND)){
    main{
     player.play(PlaybackTarget(attachment,1,kind=kind))
     assertTrue(player.isMuted)
    }
    waitFor{player.isPlaying && player.positionMs>=150}
    main{player.retry();assertTrue(player.isMuted)}
   }
  }finally{
   main{player.close()}
   waitFor{admission.playbackActiveCount==0}
   main{media.close();admission.close()}
   h.close()
  }
 }
 /**
  * Создаёт два независимых набора владельцев воспроизведения в одном процессе
  * После запуска первого пытается включить второй и проверяет общий предел одного настоящего проигрывателя
  * После освобождения первого второй должен успешно получить ресурс
  * Завершение ждёт освобождения разрешений обоих владельцев
  */
 @Test fun twoRuntimesCannotCreateOverlappingNativePlayers(){
  val h=RepositoryHarness.open(File(context.cacheDir,"recovery-"+UUID.randomUUID()));lateinit var media:MediaStore;lateinit var c1:MediaCoordinator;lateinit var c2:MediaCoordinator
  val first=MediaAdmission();val second=MediaAdmission()
  main{media=MediaStore(h.store,h.repo);c1=MediaCoordinator(first,media);c2=MediaCoordinator(second,media)}
  val files=JSONObject(TestFixtures.assets.open("manifest.json").bufferedReader().use{it.readText()}).getJSONArray("files")
  val file=(0 until files.length()).map{files.getJSONObject(it)}.first{it.getString("mime")=="video/mp4"}.getString("fileName")
  val d=awaitResult<AttachmentDraft>{cb->main{media.stage({TestFixtures.assets.open("media/"+file)},"video/mp4",cb)};Cancellation{}}
  val a=Attachment(d.id,d.relativePath,d.mime,d.byteSize,d.rawWidth,d.rawHeight,d.orientation,d.durationMs,d.thumbnailKey)
  var peak=0;val observed=CountDownLatch(1)
  try{
   main{c1.play(PlaybackTarget(a,1))};waitFor{c1.isReady}
   main{c2.onChange={if(c2.activePlayerCount>0 || c2.lastError!=null){peak=maxOf(peak,c1.activePlayerCount+c2.activePlayerCount);observed.countDown()}};c2.play(PlaybackTarget(a,2))}
   assertTrue(observed.await(8,TimeUnit.SECONDS));assertTrue("One native player is a PROCESS invariant: observed "+peak,peak<=1)
   main{c1.close()};waitFor{first.playbackActiveCount==0}
   main{c2.stop()};waitFor{second.playbackActiveCount==0}
   main{c2.play(PlaybackTarget(a,3))};waitFor{c2.isReady};main{assertEquals(1,c2.activePlayerCount)}
  }finally{main{c1.close();c2.close()};waitFor{first.playbackActiveCount+second.playbackActiveCount==0};main{media.close();first.close();second.close()};h.close()}
 }

 /**
  * Ограничивает рост настоящей базы текущим числом страниц и пытается отправить большой текст
  * Требует действительной ошибки нехватки места с сохранением прежнего сообщения и черновика
  * После снятия ограничения повторяет то же намерение дважды
  * В базе должно появиться ровно одно новое сообщение
  */
 @Test fun realSQLiteFullRollsBackAndExactRetryWorks(){
  val h=RepositoryHarness.open(File(context.cacheDir,"actual-full-"+UUID.randomUUID()))
  awaitResult<MessageDelta>{h.repo.send(SendCommand("preserved",2,text="keep"),it)}
  awaitResult<Unit>{h.repo.saveDraft(2,Draft(text="durable draft",revision=9),it)}
  val pages=awaitResult<Long>{cb->h.store.submit({db->db.rawQuery("PRAGMA page_count",null).use{it.moveToFirst();it.getLong(0)}},cb)}
  awaitResult<Long>{cb->h.store.submit({db->db.beginTransaction();try{val n=db.rawQuery("PRAGMA max_page_count="+pages,null).use{it.moveToFirst();it.getLong(0)};db.setTransactionSuccessful();n}finally{db.endTransaction()}},cb)}
  val command=SendCommand("actual-full",2,text="x".repeat(220000))
  try{awaitResult<MessageDelta>{h.repo.send(command,it)};fail("Real SQLITE_FULL required")}catch(t:RepoException){assertEquals(RepoError.STORAGE_FULL,t.reason)}
  assertEquals(1,h.rawScalar("SELECT COUNT(*) FROM messages"));assertEquals("ok",h.integrity())
  assertEquals("durable draft",awaitResult<Draft>{h.repo.draft(2,it)}.text)
  awaitResult<Long>{cb->h.store.submit({db->db.beginTransaction();try{val n=db.rawQuery("PRAGMA max_page_count=1000000",null).use{it.moveToFirst();it.getLong(0)};db.setTransactionSuccessful();n}finally{db.endTransaction()}},cb)}
  awaitResult<MessageDelta>{h.repo.send(command,it)};awaitResult<MessageDelta>{h.repo.send(command,it)}
  assertEquals(2,h.rawScalar("SELECT COUNT(*) FROM messages"));h.close()
 }
 /**
  * Сохраняет настоящее фото, затем внедряет нехватку места при подготовке следующего
  * Ошибка должна удалить временные остатки и не изменить байты уже сохранённого исходника
  * После восстановления проверяет прежний файл и единственное вложение в базе
  */
 @Test fun partialStagingNoSpaceCleansTempAndPreservesCommittedBytes(){
  val h=RepositoryHarness.open(File(context.cacheDir,"partial-write-"+UUID.randomUUID()));lateinit var media:MediaStore;main{media=MediaStore(h.store,h.repo)}
  val files=JSONObject(TestFixtures.assets.open("manifest.json").bufferedReader().use{it.readText()}).getJSONArray("files")
  val file=(0 until files.length()).map{files.getJSONObject(it)}.first{it.getString("mime")=="image/jpeg"}.getString("fileName")
  val d=awaitResult<AttachmentDraft>{cb->main{media.stage({TestFixtures.assets.open("media/"+file)},"image/jpeg",cb)};Cancellation{}}
  awaitResult<MessageDelta>{cb->main{media.commit(listOf(d),SendCommand("original",2,kind=MessageKind.PHOTO),cb)};Cancellation{}}
  val original=media.fileURL(d.relativePath);val bytes=original.readBytes();h.faults.arm(RepoFault.STORAGE_FULL)
  try{awaitResult<AttachmentDraft>{cb->main{media.stage({TestFixtures.assets.open("media/"+file)},"image/jpeg",cb)};Cancellation{}};fail("Bounded staging write fault must report ENOSPC")}catch(t:MediaException){assertEquals(MediaError.STORAGE_FULL,t.reason)}
  assertEquals(0,File(h.root,"media/staging").listFiles()!!.size)
  awaitResult<Unit>{cb->main{media.recover(cb)};Cancellation{}}
  assertArrayEquals(bytes,original.readBytes());assertEquals(1,h.rawScalar("SELECT COUNT(*) FROM attachments"));main{media.close()};h.close()
 }

 /**
  * Получает разрешение записи у одного владельца и освобождает его на основном потоке
  * Сразу запрашивает воспроизведение у другого владельца без отдельного ожидания
  * Подтверждённое освобождение должно быть видно новому запросу, полученное разрешение также возвращается
  */
 @Test fun acknowledgedCaptureReleaseAllowsImmediateCrossRuntimeHandoff(){
  val first=MediaAdmission();val second=MediaAdmission();val done=CountDownLatch(1);var success=false
  main{first.acquire(CaptureKind.PHOTO){r->
   r.getOrThrow().release()
   second.requestPlayback{next->success=next.isSuccess;next.getOrNull()?.release();done.countDown()}
  }}
  assertTrue(done.await(3,TimeUnit.SECONDS));main{first.close();second.close()}
  assertTrue("Acknowledged native release on main must be visible to the next runtime immediately",success)
 }

 /**
  * Последовательно подключает две сцены истории к одному владельцу воспроизведения
  * Закрытие прежней сцены не должно удалить подписку новой
  * Закрытие последней сцены обязано снять её собственные обработчики изменения и отсоединения поверхности
  */
 @Test fun closedScrollHostUnsubscribesOnlyItsOwnPlaybackObservers(){
  val h=RepositoryHarness.open(File(context.cacheDir,"observer-owner-"+UUID.randomUUID()));lateinit var media:MediaStore;lateinit var player:MediaCoordinator
  val admission=MediaAdmission()
  main{
   media=MediaStore(h.store,h.repo);player=MediaCoordinator(admission,media)
   val old=dev.kirill.kandinsky.chat.rendering.ChatScrollHost(context);old.configurePlayback(player,media)
   val next=dev.kirill.kandinsky.chat.rendering.ChatScrollHost(context);next.configurePlayback(player,media);val latest=player.onChange
   old.close();assertSame("Closing the previous view must preserve the new view subscription",latest,player.onChange)
   next.close();assertNull("A closed screen must release its player callback subscription",player.onChange);assertNull(player.onDetachOutput)
   player.close();media.close();admission.close()
  };h.close()
 }
}
