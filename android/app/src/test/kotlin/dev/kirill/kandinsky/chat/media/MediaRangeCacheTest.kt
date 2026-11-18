package dev.kirill.kandinsky.chat.media
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
class MediaRangeCacheTest {
 /**
  * Читает конец файла и повторно открывает сохранённые части без нового обращения к источнику
  * После получения остальных частей проверяет полный оригинал по точным байтам
  * Дополнительно сравнивает случайные чтения с независимым исходным массивом и отклоняет чтение после закрытия
  */
 @Test fun seekUsesDiskAcrossSessionsAndPromotesVerifiedOriginal(){
  val root=Files.createTempDirectory("range-cache").toFile()
  try{
   val bytes=ByteArray(MediaRangeCache.CHUNK_SIZE*3+17){(it%251).toByte()};val blob=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)};val original=File(root,"media/remote/$blob.mp4");var calls=0
   val first=MediaRangeCache(root,blob,bytes.size.toLong(),original){start,end,_->calls++;bytes.copyOfRange(start.toInt(),end.toInt()+1)}
   val tail=first.read(bytes.size-10L,10,MediaCancellation());assertArrayEquals(bytes.takeLast(10).toByteArray(),tail);assertEquals(1,calls);assertFalse(original.exists());first.close()
   val resumed=MediaRangeCache(root,blob,bytes.size.toLong(),original){start,end,_->calls++;bytes.copyOfRange(start.toInt(),end.toInt()+1)}
   assertArrayEquals(tail,resumed.read(bytes.size-10L,10,MediaCancellation()));assertEquals(1,calls)
   for(i in 0..2)resumed.read(i.toLong()*MediaRangeCache.CHUNK_SIZE,1,MediaCancellation())
   assertEquals(4,calls);assertArrayEquals(bytes,original.readBytes())
   val random=java.util.Random(7301)
   repeat(150){val offset=random.nextInt(bytes.size);val count=1+random.nextInt(131072);val value=resumed.read(offset.toLong(),count,MediaCancellation());assertEquals(minOf(count,65536,bytes.size-offset),value.size);assertArrayEquals(bytes.copyOfRange(offset,offset+value.size),value)}
   resumed.close();assertTrue(runCatching{resumed.read(0,1,MediaCancellation())}.isFailure)
  }finally{root.deleteRecursively()}
 }
 /**
  * Проверяет, что закрытие владельца прерывает ожидающее чтение через отмену источника
  * Затем запускает несколько одновременных чтений одной части
  * Все должны получить данные при единственном обращении к источнику
  */
 @Test fun closeInterruptsBlockedReadAndConcurrentReadsCoalesce(){
  val root=Files.createTempDirectory("range-cancel").toFile();val entered=CountDownLatch(1);val canceled=CountDownLatch(1)
  try{
   val cache=MediaRangeCache(root,"a".repeat(32),1024,File(root,"original")){_,_,token->token.onCancel{canceled.countDown()};entered.countDown();check(canceled.await(3,TimeUnit.SECONDS));throw MediaException(MediaError.CANCELLED)}
   val done=CountDownLatch(1);Thread{assertTrue(runCatching{cache.read(0,10,MediaCancellation())}.isFailure);done.countDown()}.start();assertTrue(entered.await(2,TimeUnit.SECONDS));cache.close();assertTrue(done.await(2,TimeUnit.SECONDS))
   val count=AtomicInteger();val shared=MediaRangeCache(root,"b".repeat(32),1024,File(root,"second")){_,_,_->count.incrementAndGet();ByteArray(1024){3}}
   val pool=java.util.concurrent.Executors.newFixedThreadPool(4)
   try{val readers=List(4){pool.submit<Unit>{assertEquals(10,shared.read(0,10,MediaCancellation()).size)}};readers.forEach{it.get(3,TimeUnit.SECONDS)};assertEquals(1,count.get())}finally{pool.shutdownNow();shared.close()}
  }finally{root.deleteRecursively()}
 }
 /**
  * Проверяет полную часть с неправильным отпечатком и ответ недостаточной длины
  * Оба случая должны завершиться ошибкой и не создавать готовый оригинал
  */
 @Test fun incompleteAndWrongDigestNeverBecomeOriginal(){
  val root=Files.createTempDirectory("range-invalid").toFile()
  try{
   val original=File(root,"original");val cache=MediaRangeCache(root,"a".repeat(64),1024,original){_,_,_->ByteArray(1024){1}}
   assertTrue(runCatching{cache.read(0,1,MediaCancellation())}.isFailure);assertFalse(original.exists());cache.close()
   val short=MediaRangeCache(root,"a".repeat(64),1024,original){_,_,_->ByteArray(10)};assertTrue(runCatching{short.read(0,1,MediaCancellation())}.isFailure);assertFalse(original.exists());short.close()
  }finally{root.deleteRecursively()}
 }
}
