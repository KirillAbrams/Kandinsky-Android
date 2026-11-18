package dev.kirill.kandinsky.chat.rendering
import android.os.Handler
import android.os.Looper
import dev.kirill.kandinsky.chat.domain.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
class TextLayoutWorker {
 private val main=Handler(Looper.getMainLooper())
 private val closed=AtomicBoolean(false)
 private val pool=ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue(32),{r->Thread(r,"Kandinsky-Text").apply{isDaemon=true}},ThreadPoolExecutor.AbortPolicy())
 private inner class Job(val message:Message,val key:TextLayoutKey,val ticket:Int,val cancelled:AtomicBoolean,val completion:(Int,PreparedText?)->Unit):Runnable{
  /**
   * Для одного задания подготавливает текст сообщения вне главного потока, если задание и очередь ещё действуют
   * Перед выдачей в главный поток повторно проверяет отмену и закрытие, освобождая ставший ненужным ресурс
   */
  override fun run(){
   val resource=if(cancelled.get() || closed.get())null else PreparedText(message,key)
   main.post{if(cancelled.get() || closed.get()){resource?.close();completion(ticket,null)}else completion(ticket,resource)}
  }
 }
 /**
  * Ставит подготовку сообщения в очередь двух исполнителей с тридцатью двумя местами ожидания
  * При отказе принять работу сообщает отсутствие результата через главный поток
  * Возвращённая отмена помечает задание и при возможности снимает его из ожидания с тем же завершением
  */
 fun prepare(message:Message,key:TextLayoutKey,ticket:Int,completion:(Int,PreparedText?)->Unit):Cancellation{
  val cancelled=AtomicBoolean(false);val job=Job(message,key,ticket,cancelled,completion)
  try{pool.execute(job)}catch(e:RejectedExecutionException){cancelled.set(true);main.post{completion(ticket,null)}}
  return Cancellation{cancelled.set(true);if(pool.remove(job))main.post{completion(ticket,null)}}
 }
 /**
  * Запрещает дальнейшую выдачу результатов и запрашивает остановку рабочей очереди
  * Снятые ожидающие задания помечает отменёнными и завершает отсутствием результата в главном потоке
  * Уже работающие задания проверят закрытие перед передачей своего ресурса
  */
 fun close(){
  closed.set(true);val pending=pool.shutdownNow()
  for(r in pending){val job=r as Job;job.cancelled.set(true);main.post{job.completion(job.ticket,null)}}
 }
}
class FragmentLayoutCache {
 private val values=LinkedHashMap<TextLayoutKey,PreparedText>(16,0.75f,true)
 var bytes=0;private set
 var limit=32*1024*1024
 // Успешное чтение делает запись недавно использованной, чтобы при следующем вытеснении сначала ушли более старые обращения
 fun value(key:TextLayoutKey)=values[key]
 /**
  * Убирает прежнюю запись того же ключа из собственного учёта и проверяет, помещается ли новый ресурс в допустимый объём
  * Сохраняет подходящий результат как недавно использованный, обновляет число занятых байтов и запускает ограниченное вытеснение
  */
 fun insert(value:PreparedText){
  values.remove(value.key)?.let{bytes-=it.byteCost}
  if(value.byteCost>limit)return
  values[value.key]=value;bytes+=value.byteCost;trim()
 }
 /**
  * Убирает самые давно использованные собственные записи, пока не выполнены предел объёма и предел двухсот пятидесяти шести ключей
  * Уменьшает учтённые байты вместе с удалением каждой ссылки, не закрывая ресурс, которым ещё может владеть видимая сцена
  */
 fun trim(){val i=values.entries.iterator();while((bytes>limit || values.size>256) && i.hasNext()){bytes-=i.next().value.byteCost;i.remove()}}
 /**
  * Снимает все ссылки накопителя на подготовленные тексты и очищает порядок использования
  * Обнуляет собственный учёт объёма, оставляя срок жизни ещё видимых ресурсов их другим владельцам
  */
 fun clear(){values.clear();bytes=0}
}
