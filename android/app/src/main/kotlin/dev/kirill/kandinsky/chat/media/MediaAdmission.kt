package dev.kirill.kandinsky.chat.media

import android.os.Handler
import android.os.Looper
import dev.kirill.kandinsky.chat.domain.Cancellation
import java.util.UUID

private object ProcessMediaGate {
 var playback:UUID?=null
 var capture:UUID?=null
 var pendingCapture:UUID?=null
 val previews=linkedMapOf<UUID,()->Unit>()
 var drain:Pair<UUID,()->Unit>?=null
 val observers=linkedMapOf<UUID,()->Unit>()
}

/** Разрешения учитывают ожидающую системную работу и владельцев, чьё освобождение ещё не подтверждено */
class MediaAdmission:CaptureAdmission {
 private class PendingCapture(val kind:CaptureKind,val token:MediaCancellation,val completion:(Result<CaptureLease>)->Unit){
  val id=UUID.randomUUID();var stopped=false;var scheduled=false
 }
 private class PendingPlayback(val id:UUID,val token:MediaCancellation,val completion:(Result<PlaybackLease>)->Unit){var scheduled=false}
 companion object {
  private val main=Handler(Looper.getMainLooper())
  /**
   * При обращении из другого потока немедленно сообщает об ошибке
   * Самостоятельно перенос работы здесь не выполняется
   */
  private fun checkMain(){check(Looper.myLooper()==Looper.getMainLooper())}
  /**
   * Выполняет освобождение разрешения в потоке экрана
   * Если вызов уже пришёл из нужного потока, выполняет действие сразу
   * В остальных случаях асинхронно передаёт его в главный поток
   */
  private fun releaseOnMain(action:()->Unit){if(Looper.myLooper()==Looper.getMainLooper())action()else main.post(action)}
  val previewOwnerCount get()=ProcessMediaGate.previews.size
  val previewsAllowed get()=ProcessMediaGate.playback==null && ProcessMediaGate.capture==null && ProcessMediaGate.pendingCapture==null
  /**
   * Выдаёт место для беззвучного показа только при отсутствии основного воспроизведения и записи
   * Ограничивает обычные показы двумя владельцами, а результаты видеоальбома четырьмя
   * При освобождении последнего места продолжает ожидающее исключительное действие и уведомляет наблюдателей
   */
  fun reservePreview(studioAlbum:Boolean=false,stop:()->Unit):PlaybackLease?{
   checkMain();if(!previewsAllowed || ProcessMediaGate.previews.size>=(if(studioAlbum)4 else 2))return null
   val id=UUID.randomUUID();ProcessMediaGate.previews[id]=stop
   return PlaybackLease{releaseOnMain{
    if(ProcessMediaGate.previews.remove(id)!=null){
     if(ProcessMediaGate.previews.isEmpty())ProcessMediaGate.drain?.second?.invoke()
     notifyPreviewAvailability()
    }
   }}
  }
  /**
   * Сохраняет обработчик, которому нужно сообщать о повторном разрешении беззвучных показов
   * Возвращает отдельное обозначение для последующего удаления наблюдения
   * Само наличие наблюдателя не занимает место воспроизведения
   */
  fun observePreviewAvailability(action:()->Unit):UUID{checkMain();val id=UUID.randomUUID();ProcessMediaGate.observers[id]=action;return id}
  /**
   * Удаляет сохранённое наблюдение по его обозначению
   * После этого обработчик больше не вызывается общим уведомлением доступности
   * Другие наблюдения и выданные места показа сохраняются
   */
  fun removePreviewObserver(id:UUID){checkMain();ProcessMediaGate.observers.remove(id)}
  /**
   * Уведомляет сохранённых наблюдателей только при разрешённом беззвучном показе
   * Обходит копию списка, чтобы обработчики могли менять свои подписки во время вызова
   * Самостоятельно места показа не выдаёт
   */
  private fun notifyPreviewAvailability(){if(previewsAllowed)ProcessMediaGate.observers.values.toList().forEach{it()}}
  /**
   * Вызывает сохранённые команды остановки всех беззвучных показов
   * Обходит копию набора, поскольку остановка может освободить место и изменить исходный список
   * Выдача исключительного разрешения затем ждёт фактического освобождения владельцев
   */
  private fun stopPreviews(){ProcessMediaGate.previews.values.toList().forEach{it()}}
  /**
   * Убирает ожидающее продолжение только при совпадении обозначения запроса
   * Не даёт завершению старой операции удалить ожидание более новой
   * Используется при отмене и выдаче разрешений
   */
  private fun clearDrain(id:UUID){if(ProcessMediaGate.drain?.first==id)ProcessMediaGate.drain=null}
 }
 private var closed=false
 private var playbackID:UUID?=null
 private var captureID:UUID?=null
 private var pendingCapture:PendingCapture?=null
 private var pendingPlayback:PendingPlayback?=null
 private var stopper:((()->Unit)->Unit)?=null
 @Volatile var playbackActiveCount=0;private set
 @Volatile var captureActiveCount=0;private set
 /**
  * Сохраняет способ остановить основное воспроизведение перед записью
  * Остановка должна вызвать переданное подтверждение после освобождения своей работы
  * После закрытия владельца новый обработчик не устанавливается
  */
 fun installPlaybackStopper(stop:(()->Unit)->Unit){checkMain();if(!closed)stopper=stop}
 /**
  * Возвращает отказ в разрешении асинхронно и с поддержкой отмены запроса
  * Отмена и обычный ответ согласуются через общий признак завершения
  * Вызывающая сторона получает либо исходную причину отказа, либо отмену
  */
 private fun <T> rejected(error:MediaError,completion:(Result<T>)->Unit):Cancellation{
  val token=MediaCancellation();token.onCancel{main.post{completion(Result.failure(MediaException(MediaError.CANCELLED)))}}
  main.post{if(token.finish())completion(Result.failure(MediaException(error)))};return token
 }
 /**
  * Резервирует исключительное основное воспроизведение на уровне всего процесса
  * Отказывает при конкурирующей записи или другом владельце и просит беззвучные показы остановиться
  * Выдаёт разрешение после их освобождения, а при отмене убирает только собственную бронь
  */
 fun requestPlayback(completion:(Result<PlaybackLease>)->Unit):Cancellation {
  checkMain();if(closed)return rejected(MediaError.CANCELLED,completion)
  if(captureID!=null || pendingCapture!=null || playbackID!=null || ProcessMediaGate.playback!=null || ProcessMediaGate.capture!=null || ProcessMediaGate.pendingCapture!=null)return rejected(MediaError.UNAVAILABLE,completion)
  val id=UUID.randomUUID();val token=MediaCancellation();playbackID=id;ProcessMediaGate.playback=id;playbackActiveCount=1
  pendingPlayback=PendingPlayback(id,token,completion)
  token.onCancel{main.post{
   val p=pendingPlayback
   if(p?.id==id){
    pendingPlayback=null;clearDrain(id)
    if(ProcessMediaGate.playback==id)ProcessMediaGate.playback=null
    if(playbackID==id){playbackID=null;playbackActiveCount=0}
    p.completion(Result.failure(MediaException(MediaError.CANCELLED)));tryGrant();notifyPreviewAvailability()
   }
  }}
  ProcessMediaGate.drain=id to {tryGrantPlayback()};stopPreviews();tryGrantPlayback();return token
 }
 /**
  * Проверяет, освобождены ли беззвучные показы и не ожидается ли запись
  * Назначает выдачу разрешения один раз и перед завершением повторно проверяет текущий запрос
  * Возвращаемое разрешение освобождает собственную бронь и позволяет продолжить ожидающую запись
  */
 private fun tryGrantPlayback(){
  val p=pendingPlayback ?: return
  if(closed || pendingCapture!=null || ProcessMediaGate.previews.isNotEmpty() || p.scheduled)return
  p.scheduled=true
  main.post{
   if(pendingPlayback?.id==p.id && p.token.finish()){
    pendingPlayback=null;clearDrain(p.id)
    if(closed){
     if(ProcessMediaGate.playback==p.id)ProcessMediaGate.playback=null
     playbackID=null;playbackActiveCount=0;p.completion(Result.failure(MediaException(MediaError.CANCELLED)));notifyPreviewAvailability()
    }else p.completion(Result.success(PlaybackLease{releaseOnMain{
     if(ProcessMediaGate.playback==p.id)ProcessMediaGate.playback=null
     if(playbackID==p.id){playbackID=null;playbackActiveCount=0;tryGrant()}
     notifyPreviewAvailability()
    }}))
   }
  }
 }
 /**
  * Заранее запрещает новые беззвучные показы и просит текущее воспроизведение подтвердить остановку
  * При отмене снимает своё ожидание и возвращает возможность продолжения остальной медийной работы
  */
 override fun acquire(kind:CaptureKind,completion:(Result<CaptureLease>)->Unit):Cancellation {
  checkMain();if(closed)return rejected(MediaError.CANCELLED,completion)
  if(captureID!=null || pendingCapture!=null || ProcessMediaGate.capture!=null || ProcessMediaGate.pendingCapture!=null || (ProcessMediaGate.playback!=null && ProcessMediaGate.playback!=playbackID))return rejected(MediaError.UNAVAILABLE,completion)
  val token=MediaCancellation();val p=PendingCapture(kind,token,completion);pendingCapture=p;ProcessMediaGate.pendingCapture=p.id
  token.onCancel{main.post{
   if(pendingCapture?.id==p.id){
    pendingCapture=null;clearDrain(p.id)
    if(ProcessMediaGate.pendingCapture==p.id)ProcessMediaGate.pendingCapture=null
    if(ProcessMediaGate.capture==p.id)ProcessMediaGate.capture=null
    if(captureID==p.id){captureID=null;captureActiveCount=0}
    p.completion(Result.failure(MediaException(MediaError.CANCELLED)));tryGrantPlayback();notifyPreviewAvailability()
   }
  }}
  ProcessMediaGate.drain=p.id to {tryGrant()};stopPreviews()
  val acknowledged={main.post{val current=pendingCapture;if(current?.id==p.id){current.stopped=true;tryGrant()}};Unit}
  stopper?.invoke(acknowledged) ?: acknowledged();return token
 }
 /**
  * Выдаёт ожидающее разрешение записи только после подтверждения остановки и освобождения конкурирующих владельцев
  * Переводит ожидающую бронь в активную и завершает запрос асинхронно
  * Освобождение выданного разрешения снимает именно эту запись и снова открывает доступ к беззвучным показам
  */
 private fun tryGrant(){
  val p=pendingCapture ?: return
  if(closed || playbackID!=null || ProcessMediaGate.playback!=null || ProcessMediaGate.capture!=null || ProcessMediaGate.previews.isNotEmpty() || ProcessMediaGate.pendingCapture!=p.id || !p.stopped || p.scheduled)return
  p.scheduled=true;captureID=p.id;ProcessMediaGate.capture=p.id;ProcessMediaGate.pendingCapture=null;captureActiveCount=1;clearDrain(p.id)
  main.post{
   if(pendingCapture?.id==p.id && p.token.finish()){
    pendingCapture=null
    if(closed){
     if(ProcessMediaGate.capture==p.id)ProcessMediaGate.capture=null
     captureID=null;captureActiveCount=0;p.completion(Result.failure(MediaException(MediaError.CANCELLED)));notifyPreviewAvailability()
    }else p.completion(Result.success(CaptureLease(p.kind){releaseOnMain{
     if(ProcessMediaGate.capture==p.id)ProcessMediaGate.capture=null
     if(captureID==p.id){captureID=null;captureActiveCount=0};notifyPreviewAvailability()
    }}))
   }
  }
 }
 /**
  * Прекращает приём новых разрешений и отменяет ожидающие запросы воспроизведения и записи
  * Вызывает установленную остановку основного воспроизведения и убирает её обработчик
  * Уже выданные ресурсы освобождаются через принадлежащие им разрешения
  */
 fun close(){
  checkMain();if(closed)return;closed=true;pendingCapture?.token?.cancel();pendingPlayback?.token?.cancel()
  val stop=stopper;stopper=null;stop?.invoke{}
 }
}

/** Отменённая подготовка сохраняет занятое место до фактического освобождения системного результата */
internal class PreviewLifetime(private var lease:PlaybackLease?){
 private var work=0
 private var retired=false
 /**
  * Учитывает ещё одну начатую подготовку владельца беззвучного показа
  * Запрещает начинать работу после его завершения
  * Место остаётся занятым до возврата результата каждой такой подготовки
  */
 fun begin(){check(!retired);work++}
 /**
  * Отмечает возврат одной незавершённой работы владельца показа
  * Проверяет, что ранее такая работа действительно была начата
  * После уменьшения счётчика пробует освободить место уже завершённого показа
  */
 fun end(){check(work>0);work--;releaseIfRetired()}
 /**
  * Помечает владельца показа завершённым, запрещая новую подготовку
  * Проверяет возможность освободить выданное ему место
  * Незавершённые системные работы продолжают удерживать место до возврата своих результатов
  */
 fun retire(){retired=true;releaseIfRetired()}
 /**
  * Освобождает место только после завершения показа и возврата всех его работ
  * Убирает сохранённую ссылку на выданное разрешение
  * Так отменённая подготовка не открывает место следующему владельцу раньше освобождения своих ресурсов
  */
 private fun releaseIfRetired(){if(retired && work==0){lease?.release();lease=null}}
}
