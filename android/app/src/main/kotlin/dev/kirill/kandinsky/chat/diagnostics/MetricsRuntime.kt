package dev.kirill.kandinsky.chat.diagnostics
import android.os.*
import android.view.*
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.concurrent.*
object MetricsRuntime {
 @Volatile var enabled=false
 val recorder=MetricsRecorder()
 @Volatile var displayRefreshHz:Float?=null
 @Volatile var displayMaximumHz:Float?=null
 var output:File?=null
 private val writer=ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue(1),{r->Thread(r,"Kandinsky-Metrics").apply{isDaemon=true}},ThreadPoolExecutor.DiscardOldestPolicy())
 /**
  * При включённой диагностике собирает распределения длительностей, исходные значения, максимумы и сведения о режиме экрана
  * Передаёт запись ограниченной фоновой очереди, которая при переполнении оставляет более свежий ожидающий отчёт
  * Файл заменяется через средство целостной записи, а ошибка сохранения откатывает незавершённую замену
  */
 fun export(){
  if(!enabled)return;val file=output ?: return
  val stats=JSONObject();recorder.snapshot().forEach{(k,v)->stats.put(k,JSONObject().put("count",v.count).put("retained",v.retained).put("p50",v.p50).put("p95",v.p95).put("p99",v.p99).put("maximum",v.maximum))}
  val raw=JSONObject();recorder.rawSamples().forEach{(k,v)->raw.put(k,org.json.JSONArray(v))}
  val json=JSONObject().put("scope","Emulator HWUI FrameMetrics total duration and scene CPU; not physical device FPS").put("statistics",stats).put("rawSamples",raw).put("displayRefreshHz",displayRefreshHz).put("displayMaximumHz",displayMaximumHz).put("peaks",JSONObject(recorder.peakSnapshot())).toString()
  writer.execute{
   file.parentFile?.mkdirs();val atomic=AtomicFile(file);var stream:java.io.FileOutputStream?=null
   try{stream=atomic.startWrite();stream.write(json.toByteArray());atomic.finishWrite(stream)}catch(_:Throwable){atomic.failWrite(stream)}
  }
 }
}
@Suppress("DEPRECATION")
class WindowFrameProbe(private val window:Window):AutoCloseable {
 private val thread=HandlerThread("Kandinsky-FrameMetrics").apply{start()}
 private val listener=Window.OnFrameMetricsAvailableListener{_,metrics,dropped->
  MetricsRuntime.recorder.record("window_total_duration_ms",metrics.getMetric(FrameMetrics.TOTAL_DURATION)/1_000_000.0)
  MetricsRuntime.recorder.peak("frame_listener_dropped_reports",dropped)
 }
 init{(window.decorView.display ?: window.windowManager.defaultDisplay)?.let{MetricsRuntime.displayRefreshHz=it.refreshRate;MetricsRuntime.displayMaximumHz=it.supportedModes.maxOfOrNull{mode->mode.refreshRate}};window.addOnFrameMetricsAvailableListener(listener,Handler(thread.looper))}
 /**
  * Снимает наблюдателя длительностей оконных кадров и просит его рабочий поток завершиться
  * Затем запускает сохранение собранного отчёта, отделяя системные длительности обработки окна от фактической частоты показа устройства
  */
 override fun close(){window.removeOnFrameMetricsAvailableListener(listener);thread.quitSafely();MetricsRuntime.export()}
}
