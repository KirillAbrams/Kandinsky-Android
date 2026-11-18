package dev.kirill.kandinsky.chat.media
import dev.kirill.kandinsky.chat.domain.Cancellation
import java.util.concurrent.atomic.AtomicBoolean
enum class MediaError { UNAVAILABLE,PERMISSION_DENIED,INTERRUPTED,CORRUPT,STORAGE_FULL,IO,CANCELLED }
class MediaException(val reason:MediaError):RuntimeException(reason.name)
enum class CaptureKind { PHOTO }
data class ThumbnailKey(val maxPixel:Int=1024,val studioOriginal:Boolean=false)
class MediaCancellation:Cancellation {
 private val gate=Any();private var state=0;private var handler:(()->Unit)?=null;private var progress:((Double)->Unit)?=null
 /**
  * Сохраняет обработчик изменения доли выполнения только для незавершённой операции
  * Назначение защищено общей блокировкой состояния
  * После отмены или завершения новый обработчик не устанавливается
  */
 fun onProgress(action:(Double)->Unit){synchronized(gate){if(state==0)progress=action}}
 /**
  * Получает актуальный обработчик и передаёт ему долю выполнения в пределах от нуля до единицы
  * Проверяет незавершённое состояние под защитой, а сам вызов выполняет после выхода из неё
  * Значение здесь не накапливается и не сохраняется как новый статус операции
  */
 fun reportProgress(value:Double){val action=synchronized(gate){if(state==0)progress else null};action?.invoke(value.coerceIn(0.0,1.0))}
 /**
  * Назначает действие отмены для ещё работающей операции
  * Если отмена уже произошла, вызывает переданное действие сразу после проверки состояния
  * После обычного завершения новое действие отмены не сохраняет
  */
 fun onCancel(action:()->Unit){val now=synchronized(gate){if(state==0)handler=action;state==1};if(now)action()}
 /**
  * Переводит незавершённую операцию в отменённое состояние только один раз
  * Забирает действие отмены и очищает обработчики прогресса и дальнейшей отмены
  * Сохранённое действие вызывает вне защиты состояния, чтобы оно могло завершить связанную работу
  */
 override fun cancel(){val action=synchronized(gate){if(state!=0)return;state=1;handler.also{handler=null;progress=null}};action?.invoke()}
 /**
  * Под защитой отмечает успешное принятие завершения операции
  * Возвращает разрешение только для первого завершения до отмены
  * Очищает обработчики, чтобы они не удерживали получателей после конца работы
  */
 fun finish():Boolean=synchronized(gate){if(state!=0)return false;state=2;handler=null;progress=null;true}
 val isCancelled get()=synchronized(gate){state==1}
}
class CaptureLease(val kind:CaptureKind,private val released:()->Unit){
 private val once=AtomicBoolean(false);
  /**
   * Забирает и выполняет действие освобождения принадлежащего разрешения только один раз
   * Повторный вызов больше не передаёт освобождение внешнему владельцу
   * Используется для согласованного завершения записи или воспроизведения
   */
  fun release(){if(once.compareAndSet(false,true))released()}
}
class PlaybackLease(private val released:()->Unit){
 private val once=AtomicBoolean(false);
  /**
   * Забирает и выполняет действие освобождения принадлежащего разрешения только один раз
   * Повторный вызов больше не передаёт освобождение внешнему владельцу
   * Используется для согласованного завершения записи или воспроизведения
   */
  fun release(){if(once.compareAndSet(false,true))released()}
}
interface CaptureAdmission {
 /**
  * Определяет запрос разрешения на выбранный вид захвата медиа
  * Возвращает возможность отменить ожидание и передаёт разрешение либо ошибку назначенному продолжению
  * Конкретные ограничения ресурсов устанавливает реализация этого договора
  */
 fun acquire(kind:CaptureKind,completion:(Result<CaptureLease>)->Unit):Cancellation}
