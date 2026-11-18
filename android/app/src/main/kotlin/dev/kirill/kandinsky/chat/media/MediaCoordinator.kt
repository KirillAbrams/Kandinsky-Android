package dev.kirill.kandinsky.chat.media
import android.os.*
import android.view.Surface
import dev.kirill.kandinsky.chat.domain.*
import java.util.concurrent.atomic.AtomicInteger
data class PlaybackTarget(val attachment:Attachment,val messageID:MessageID,val chatID:ChatID?=null,val kind:MessageKind?=null,val loopPreview:Boolean=false)
class MediaCoordinator(private val admission:MediaAdmission,private val media:MediaStore){
 private val main=Handler(Looper.getMainLooper())
 private val thread=HandlerThread("Kandinsky-one-player").apply{start()};private val worker=Handler(thread.looper)
 private var session:PlayerSession?=null;private var retiring:PlayerSession?=null;private var lease:PlaybackLease?=null;private var request:Cancellation?=null
 private var desiredOutput:Surface?=null

 val currentTarget:PlaybackTarget? get()=desired
 private var desired:PlaybackTarget?=null;private var desiredSource:MediaPlaybackSource?=null;private var wantsPlaying=true;private var generation=0;private var stopping=false;private var closed=false
 private val completions=mutableListOf<()->Unit>()
 private val nativeCount=AtomicInteger();private val peak=AtomicInteger()
 val peakNativePlayers get()=peak.get()
 val activePlayerCount get()=nativeCount.get()
 var currentMessageID:MessageID?=null;private set
 var isReady=false;private set
 var isBuffering=false;private set
 /**
  * Повторно открывает выбранное вложение после ошибки
  * Сохраняет его текущий способ размещения и беззвучный режим
  * Если выбранного источника уже нет, новое воспроизведение не создаёт
  */
 fun retry(){desired?.let{play(it,inlineVisible)}}
 var isPlaying=false;private set
 var durationMs=0L;private set
 var positionMs=0L;private set
 var downloadProgress:Double?=null;private set
 var lastError:Throwable?=null;private set
 var onChange:(()->Unit)?=null
 var onDetachOutput:(()->Unit)?=null
 var onConsumed:((PlaybackTarget)->Unit)?=null
 var presentationOwner:Any?=null
 val isMuted=true

 private val observers=linkedMapOf<java.util.UUID,()->Unit>()
 /**
  * Позволяет нескольким представлениям следить за одним владельцем воспроизведения
  * Возвращённое обозначение используется для последующего снятия наблюдения
  */
 fun observeChanges(callback:()->Unit):java.util.UUID=java.util.UUID.randomUUID().also{observers[it]=callback}
 /**
  * Не меняет общий обработчик экрана и другие наблюдения
  * Само воспроизведение при снятии подписки сохраняется
  */
 fun removeChangeObserver(token:java.util.UUID){observers.remove(token)}
 private var consumed=false;private var seeking=false;private var seekTicket=0
 private var playedMs=0L;private var lastPlayedPosition:Long?=null
 /**
  * Обнуляет накопленный промежуток действительного воспроизведения и последнюю контрольную позицию
  * Используется при смене источника, паузе и перемотке
  * Не позволяет скачку времени после такого действия засчитаться как просмотр
  */
 private fun resetConsumptionProgress(){playedMs=0;lastPlayedPosition=null}
 /**
  * Накапливает действительно пройденное время выбранного сообщения только во время готового активного воспроизведения
  * Исключает ожидание данных, паузу, явную перемотку и резкие скачки положения
  * После небольшого достаточного промежутка один раз сообщает владельцу об учёте просмотра или прослушивания
  */
 private fun observeConsumption(){
  val target=desired ?: return;val callback=onConsumed ?: return
  if(consumed || closed || target.chatID==null || target.kind !in listOf(MessageKind.VOICE,MessageKind.ROUND,MessageKind.VIDEO))return
  if(!isReady || !isPlaying || isBuffering || seeking){lastPlayedPosition=null;return}
  val position=positionMs;val previous=lastPlayedPosition;lastPlayedPosition=position
  if(previous==null)return
  val delta=position-previous
  // Учитываем только положение проигрывателя, исключая явную перемотку и разрывы измерения
  if(delta !in 0L..1000L)return
  playedMs+=delta
  if(playedMs>=250){consumed=true;callback(target)}
 }
 var inlineVisible=false;private set
 /**
  * Не создаёт отдельную общую панель или звуковое управление
  * Сам источник и его положение при этом не заменяет
  */
 fun setInlineVisible(value:Boolean){if(inlineVisible!=value){inlineVisible=value}}

 init{admission.installPlaybackStopper{done->stop(done)}}
 /**
  * Сверяет выбранный источник с обновлением истории
  * При удалении сообщения, замене его файла или изменении вида останавливает воспроизведение
  * Сравнивает путь, вид содержимого и размер вложения, оставляя тот же источник при обычном обновлении сопутствующих сведений
  */
 fun apply(delta:MessageDelta){
  val target=desired ?: return
  if(target.messageID in delta.removedIDs){stop();return}
  val row=(delta.updated+delta.inserted).firstOrNull{it.id==target.messageID} ?: return
  val stillPresent=row.attachments.any{source->
   source.relativePath==target.attachment.relativePath && source.mime==target.attachment.mime && source.byteSize==target.attachment.byteSize
  }
  if(!stillPresent || target.kind!=null && row.kind!=target.kind)stop()
 }
 /**
  * Останавливает прежний выбор и начинает подготовку выбранного источника
  * Видео всегда переводит в беззвучный режим, сохраняя выбранное место показа
  * При получении файла или потокового источника проверяет поколение запроса, чтобы поздний ответ не начал другое воспроизведение
  * Ошибку подготовки сохраняет для отображения и возможного повтора
  */
 fun play(target:PlaybackTarget,inline:Boolean=false){
  if(closed)return
  stop()
  if(!target.attachment.mime.startsWith("video/")){lastError=MediaException(MediaError.UNAVAILABLE);notifyChanged();return}
  wantsPlaying=true;inlineVisible=inline;desired=target;currentMessageID=target.messageID;downloadProgress=0.0;lastError=null;notifyChanged();val ticket=generation
  request=media.playbackSource(target.attachment){result->
   if(ticket==generation && !closed){request=null;result.fold({playPrepared(target,it)},{lastError=it;notifyChanged()})}else result.getOrNull()?.close()
  }
 }
 /**
  * Согласует его с исключительным разрешением основного проигрывателя и завершением прежнего владельца
  * Перед подключением сохраняет новое поколение, по которому проверяются последующие ответы
  */
 private fun playPrepared(target:PlaybackTarget,source:MediaPlaybackSource){
  if(closed)return;desired=target;desiredSource=source;generation++;lastError=null;downloadProgress=null;notifyChanged()
  if(!stopping)main.post{advance()}
 }
 /**
  * Начинает подключение желаемого подготовленного источника после завершения прежнего освобождения
  * Получает исключительное разрешение и создаёт одну системную сессию с нужными повторением и громкостью
  * Подключает поверхность, готовность, положение, ожидание данных и учёт просмотра с проверкой поколения
  */
 private fun advance(){
  if(closed || stopping || session!=null || request!=null)return
  val target=desired ?: return;val source=desiredSource ?: return;val ticket=generation
  request=admission.requestPlayback{result->
   request=null
   result.fold({owner->
    if(closed || ticket!=generation){owner.release();main.post{advance()};return@fold}
    lease=owner;currentMessageID=target.messageID;isReady=false;positionMs=0
    val next=PlayerSession(worker,main,{delta->val count=nativeCount.addAndGet(delta);peak.updateAndGet{maxOf(it,count)};main.post{if(!closed)notifyChanged()}},
     {pos,duration,playing->if(ticket==generation && !closed){positionMs=pos;durationMs=duration;isPlaying=playing && wantsPlaying;observeConsumption();notifyChanged()}},
     {prepared->if(ticket==generation && !closed)prepared.fold({duration->durationMs=duration;isReady=true;if(wantsPlaying)resume();notifyChanged()},{error->lastError=error;stop()})},{buffering->if(ticket==generation && !closed){isBuffering=buffering;notifyChanged()}},loop=target.kind==MessageKind.ROUND || target.loopPreview,reportProgress=true)
    session=next
    try{next.open(source);desiredOutput?.let{next.attach(it){}}
    }catch(t:Throwable){lastError=t;stop()}
   },{error->if(ticket==generation){lastError=error;desired=null;currentMessageID=null;notifyChanged()}})
  }
 }
 /**
  * Передаёт паузу текущему системному проигрывателю
  * Уведомляет экран, сохраняя выбранное вложение для продолжения
  */
 fun pause(){wantsPlaying=false;resetConsumptionProgress();isPlaying=false;isBuffering=false;session?.pause();notifyChanged()}
 /**
  * Запускает его после выполнения предусмотренных условий готовности и владения ресурсом
  * Обновляет экран через общий механизм уведомления состояния
  */
 fun resume(){
  wantsPlaying=true;if(closed || !isReady || session==null)return
  isPlaying=true;session?.resume();notifyChanged()
 }
 /**
  * Ограничивает запрошенное положение длительностью и передаёт перемотку системному проигрывателю
  * На время операции исключает скачок из учёта просмотра и сохраняет её поколение
  * Только совпадающее завершение снимает признак перемотки текущего источника
  */
 fun seek(ms:Long,completion:(Boolean)->Unit={}){
  val s=session;if(s==null){completion(false);return}
  seekTicket++;val seek=seekTicket;val ticket=generation;seeking=true;resetConsumptionProgress()
  s.seek(ms.coerceIn(0,maxOf(0,durationMs))){done->
   if(ticket==generation && seek==seekTicket){seeking=false;resetConsumptionProgress()}
   completion(done)
  }
 }
 /**
  * Связывает основной проигрыватель с переданной системной поверхностью
  * Согласует выбранный выход с его текущим владельцем
  * Не создаёт второй проигрыватель ради другого места показа
  */
 fun attach(surface:Surface?,detached:()->Unit={}){desiredOutput=surface;val s=session ?: retiring;if(s==null)main.post(detached)else s.attach(surface,detached)}
 /**
  * Делает прежний выбор и незавершённые ответы недействительными
  * Сбрасывает учёт просмотра и передаёт остановку системного источника предусмотренному пути освобождения
  * Назначенное продолжение вызывается после завершения остановки владельца
  */
 fun stop(completed:()->Unit={}){
  consumed=false;seeking=false;seekTicket++;resetConsumptionProgress()
  desired=null;desiredSource?.close();desiredSource=null;generation++;stopCurrent(completed)
 }
 /**
  * Собирает продолжения остановки и отсоединяет текущую системную сессию только один раз
  * Отменяет подготовку и сообщает представлению о снятии выхода
  * Разрешение основного проигрывателя возвращает после подтверждённого освобождения самой сессии
  */
 private fun stopCurrent(completed:()->Unit){
  completions+=completed
  request?.cancel();request=null;
  if(stopping)return
  stopping=true;isPlaying=false;isReady=false;isBuffering=false;currentMessageID=null;downloadProgress=null;inlineVisible=false
  desiredOutput=null;val old=session;retiring=old;onDetachOutput?.invoke()
  session=null
  /**
   * Возвращает разрешение после завершения системной остановки и снимает признак освобождения
   * Уведомляет экран и вызывает накопленные продолжения
   * Затем проверяет новое желаемое воспроизведение, которое могло появиться во время остановки
   */
  fun finish(){
   lease?.release();lease=null;stopping=false
   val callbacks=completions.toList();completions.clear();notifyChanged();callbacks.forEach{it()}
    // Новый подготовленный источник мог появиться, пока система освобождала прежний
    main.post{advance()}
  }
  if(old!=null)old.release{retiring=null;finish()}else main.post{finish()}
 }
 /**
  * Передаёт состояние текущему экрану и дополнительным наблюдателям
  * Перед каждым дополнительным вызовом проверяет, что подписка всё ещё относится к тому же обработчику
  * Само уведомление не создаёт повторное подключение источника
  */
 private fun notifyChanged(){onChange?.invoke();if(observers.isNotEmpty())for((token,callback) in observers.toList())if(observers[token]===callback)callback()}
 /**
  * Окончательно закрывает управление воспроизведением и снимает получателей состояния
  * Останавливает выбранный источник, освобождает принадлежащие разрешения и системные наблюдения
  * После закрытия новые запросы воспроизведения не принимаются
  */
 fun close(){
  if(closed)return;closed=true;onConsumed=null;observers.clear();desired=null;desiredSource?.close();desiredSource=null;generation++
  stopCurrent{onChange=null;onDetachOutput=null;thread.quitSafely()}
 }
}
