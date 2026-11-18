package dev.kirill.kandinsky.chat.diagnostics
import kotlin.math.ceil
data class NumericStatistics(val count:Long,val retained:Int,val p50:Double,val p95:Double,val p99:Double,val maximum:Double)
class MetricsRecorder(private val capacity:Int=8192){
 private class Samples(capacity:Int){val values=DoubleArray(capacity);var size=0;var next=0;var count=0L;var maximum=0.0}
 private val samples=linkedMapOf<String,Samples>()
 private val peaks=linkedMapOf<String,Int>()
 init{require(capacity>0)}
 /**
  * Снимает показание монотонных часов перед началом измеряемого участка работы
  * Возвращённую опору нужно передать завершению измерения, чтобы изменение календарного времени не исказило длительность
  */
 fun begin()=System.nanoTime()
 /**
  * Вычисляет прошедшее время от переданной опоры монотонных часов и переводит его в миллисекунды
  * Передаёт длительность общей записи показателя с заданным именем
  */
 fun end(name:String,token:Long)=record(name,(System.nanoTime()-token)/1_000_000.0)
 /**
  * Принимает только конечную неотрицательную длительность и ограничивает число видов измерений восемью
  * Хранит значения в ограниченном круговом наборе, заменяя самые ранние после заполнения
  * Полное число принятых измерений и наибольшее значение продолжает учитывать за весь срок работы
  */
 @Synchronized fun record(name:String,milliseconds:Double){
  if(!milliseconds.isFinite() || milliseconds<0 || !samples.containsKey(name) && samples.size>=8)return
  val s=samples.getOrPut(name){Samples(capacity)};s.count++;s.maximum=maxOf(s.maximum,milliseconds)
  s.values[s.next]=milliseconds;s.next=(s.next+1)%capacity;s.size=minOf(capacity,s.size+1)
 }
 /**
  * Сохраняет наибольшее наблюдавшееся значение именованного целочисленного показателя
  * Ограничивает число таких показателей двенадцатью, чтобы диагностический набор имён не рос без предела
  */
 @Synchronized fun peak(name:String,value:Int){if(peaks.containsKey(name) || peaks.size<12)peaks[name]=maxOf(peaks[name] ?: 0,value)}
 /**
  * Сортирует только удерживаемое ограниченное окно значений одного показателя и вычисляет его основные доли распределения
  * Возвращает размер окна вместе с полным числом измерений и максимумом за весь срок записи
  * При отсутствии измерений возвращает отсутствие статистики
  */
 @Synchronized fun statistics(name:String):NumericStatistics?{
  val s=samples[name] ?: return null;if(s.size==0)return null;val a=s.values.copyOf(s.size).sorted()
  // Возвращает значение этого места для расчёта середины и верхних долей распределения длительностей
  fun q(p:Double)=a[maxOf(0,ceil(a.size*p).toInt()-1)]
  return NumericStatistics(s.count,s.size,q(0.5),q(0.95),q(0.99),s.maximum)
 }
 /**
  * Возвращает удерживаемые исходные измерения отдельно для каждого вида показателя
  * После заполнения кругового набора переставляет его части в порядок поступления от более раннего значения к более позднему
  */
 @Synchronized fun rawSamples()=samples.mapValues{(_,s)->if(s.size<capacity)s.values.copyOf(s.size).toList() else (s.values.copyOfRange(s.next,capacity)+s.values.copyOfRange(0,s.next)).toList()}
 /**
  * Собирает текущую сводную статистику для всех уже известных видов измерений
  * Вызывающая сторона получает готовые числовые результаты без доступа к изменяемым внутренним накопителям
  */
 @Synchronized fun snapshot()=samples.keys.associateWith{statistics(it)!!}
 /**
  * Под согласованным доступом копирует текущие максимумы целочисленных показателей
  * Возвращённая копия пригодна для отчёта и не позволяет внешнему коду менять внутренний накопитель
  */
 @Synchronized fun peakSnapshot()=peaks.toMap()
}
