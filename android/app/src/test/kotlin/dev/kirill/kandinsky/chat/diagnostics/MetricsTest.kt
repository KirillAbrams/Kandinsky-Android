package dev.kirill.kandinsky.chat.diagnostics
import org.junit.Test
import org.junit.Assert.*
class MetricsTest{
 /**
  * Заполняет ограниченный накопитель измерений и сравнивает общий счётчик, последние значения и середину распределения
  */
 @Test fun boundedNumbersKeepOnlyTheConfiguredWindow(){
  val r=MetricsRecorder(4);for(x in 1..10)r.record("scene_cpu_ms",x.toDouble())
  val s=r.statistics("scene_cpu_ms")!!;assertEquals(10L,s.count);assertEquals(4,s.retained);assertEquals(8.0,s.p50,0.0)
  assertEquals(listOf(7.0,8.0,9.0,10.0),r.rawSamples()["scene_cpu_ms"])
 }
}
