package dev.kirill.kandinsky.chat.rendering
import dev.kirill.kandinsky.chat.domain.*
import java.util.TimeZone
import kotlin.math.*
data class HistoryDay(val messageID:MessageID,val timestamp:Long,val top:Double,val height:Double)
/**
 * Хранит компактные строки и дерево частичных сумм их высот
 * Разделители дней входят только в геометрию и не создают новых сообщений
 */
class HistoryIndex(private val dayGap:Double=0.0,private val timeZone:TimeZone=TimeZone.getDefault()) {
 var rows:List<IndexedRow> = emptyList();private set
 private var tree=DoubleArray(1)
 private var leading=DoubleArray(0)
 private var positions=emptyMap<MessageID,Int>()
 private var topInset=0.0;private var bottomInset=0.0
 var totalHeight=0.0;private set
 init{require(dayGap.isFinite() && dayGap>=0)}
 // Для непустой истории сразу меняет общую высоту на разницу отступов, сохраняя высоты и порядок самих сообщений
 fun configureInsets(top:Double,bottom:Double){
  require(top.isFinite() && bottom.isFinite() && top>=0 && bottom>=0)
  if(rows.isNotEmpty())totalHeight+=top-topInset+bottom-bottomInset
  topInset=top;bottomInset=bottom
 }
 /**
  * Возвращает сохранённое время строки для определения её календарного дня
  * Если отдельного времени ещё нет, использует ключ порядка как совместимое запасное значение
  */
 private fun stamp(row:IndexedRow)=row.savedAtMs ?: row.order.sortKey
 /**
  * Приводит время сообщения к номеру календарного дня с учётом смещения выбранного часового пояса
  * По этому значению соседние строки получают разделитель даты только при переходе к другому дню
  */
 private fun day(row:IndexedRow):Long{
  val ms=stamp(row);val span=86400000L
  return Math.floorDiv(ms,span)+Math.floorDiv(Math.floorMod(ms,span)+timeZone.getOffset(ms),span)
 }
 /**
  * Сортирует компактные строки по устойчивому порядку и проверяет разные номера и положительные конечные высоты
  * Заново строит поиск позиции по номеру и дерево частичных сумм высот вместе с промежутками разделителей дней
  * Если прежняя опорная строка исчезла, выбирает ближайшую к её порядку оставшуюся строку
  * Возвращает восстановленное смещение чтения для новой истории и текущей высоты видимой области
  */
 fun replace(values:List<IndexedRow>,anchor:ReadingAnchor,viewport:Double):Double {
  val sorted=values.sortedBy{it.order}
  require(sorted.map{it.messageID}.toSet().size==sorted.size && sorted.all{it.messageID==it.order.messageID && it.estimatedHeight.isFinite() && it.estimatedHeight>0})
  val oldOrder=anchor.messageID?.let{positions[it]}?.let{rows[it].order}
  rows=sorted.toMutableList();positions=sorted.mapIndexed{i,r->r.messageID to i}.toMap()
  tree=DoubleArray(sorted.size+1);leading=DoubleArray(sorted.size);totalHeight=if(sorted.isEmpty())0.0 else topInset+bottomInset
  var previous:Long?=null
  sorted.forEachIndexed{i,r->
   if(dayGap>0){val current=day(r);if(previous!=current)leading[i]=dayGap;previous=current}
   val h=r.estimatedHeight+leading[i];tree[i+1]+=h;totalHeight+=h
   val parent=i+1+((i+1) and -(i+1));if(parent<tree.size)tree[parent]+=tree[i+1]
  }
  var restored=anchor
  if(anchor.messageID!=null && !positions.containsKey(anchor.messageID) && oldOrder!=null && sorted.isNotEmpty()){
   var lo=0;var hi=sorted.size
   while(lo<hi){val mid=(lo+hi)/2;if(sorted[mid].order<oldOrder)lo=mid+1 else hi=mid}
   restored=anchor.copy(messageID=sorted[min(lo,sorted.size-1)].messageID)
  }
  return offset(restored,viewport)
 }
 // Использует частичные суммы дерева вместо прохода всех предыдущих сообщений и ограничивает число существующей длиной истории
 fun prefix(count:Int):Double{var n=count.coerceIn(0,rows.size);var sum=topInset;while(n>0){sum+=tree[n];n-=n and -n};return sum}
 /**
  * Возвращает сохранённую позицию сообщения по его постоянному номеру
  * При отсутствии номера в текущем наборе оставляет отсутствие позиции, не просматривая строки заново
  */
 fun position(id:MessageID)=positions[id]
 /**
  * Находит верх сообщения по сохранённой позиции и сумме предшествующих высот
  * Добавляет промежуток его разделителя дня, чтобы положение относилось к самому сообщению, а не к заголовку перед ним
  */
 fun top(id:MessageID)=positions[id]?.let{prefix(it)+leading[it]}
 // Промежутки дат и внешние отступы в эту высоту не входят, неизвестный номер возвращает отсутствие значения
 fun height(id:MessageID)=positions[id]?.let{rows[it].estimatedHeight}
 /**
  * Ограничивает вертикальное смещение областью строк без внешних отступов и находит соответствующую позицию в дереве
  * Поиск проходит частичные суммы крупными шагами, учитывая промежуток перед строкой без линейного обхода всей истории
  */
 private fun seek(value:Double):Int{
  if(rows.isEmpty())return 0
  val content=totalHeight-topInset-bottomInset
  val target=(value-topInset).coerceIn(0.0,max(0.0,Math.nextDown(content)))
  var index=0;var sum=0.0;var step=1
  while(step<=rows.size/2)step*=2
  while(step>0){val next=index+step;if(next<tree.size && sum+tree[next]<=target){index=next;sum+=tree[next]};step/=2}
  return min(index,rows.size-1)
 }
 /**
  * Преобразует текущее смещение прокрутки в опору на конкретное сообщение и долю его высоты
  * Если область начинается перед самим сообщением, сохраняет дополнительный экранный сдвиг для устойчивого возврата через разделитель дня
  * Для пустой истории возвращает опору без сообщения и без следования концу
  */
 fun anchorAt(offset:Double):ReadingAnchor{
  if(rows.isEmpty())return ReadingAnchor(followsBottom=false)
  val i=seek(offset);val r=rows[i];val top=prefix(i)+leading[i]
  return ReadingAnchor(messageID=r.messageID,fraction=((offset-top)/r.estimatedHeight).coerceIn(0.0,1.0),screenDelta=max(0.0,top-offset),followsBottom=false)
 }
 /**
  * Для следования концу вычисляет последнее допустимое смещение с учётом высоты видимой области
  * Для обычной опоры восстанавливает положение сообщения, долю его высоты и экранный сдвиг, ограничивая результат краями содержимого
  * При неизвестном сообщении возвращает начало истории
  */
 fun offset(anchor:ReadingAnchor,viewport:Double):Double{
  if(anchor.followsBottom)return max(0.0,totalHeight-max(0.0,viewport))
  val i=anchor.messageID?.let{positions[it]} ?: return 0.0
  return (prefix(i)+leading[i]+anchor.fraction.coerceIn(0.0,1.0)*rows[i].estimatedHeight-anchor.screenDelta).coerceIn(0.0,max(0.0,totalHeight-max(0.0,viewport)))
 }
 /**
  * Для уже известной строки с тем же порядком принимает только более новую версию сообщения
  * Меняет номер версии компактной записи без пересчёта её высоты, даты и дерева, пока геометрия отдельно не обновлена
  */
 fun refreshRevision(message:Message){
  val i=position(message.id) ?: return
  val old=rows[i]
  if(old.order!=message.order || old.revision>=message.revision)return
  (rows as MutableList<IndexedRow>)[i]=old.copy(revision=message.revision)
 }
 /**
  * Меняет только затронутые суммы дерева и общую высоту, сохраняя порядок и версию строки
  * Возвращает смещение, которое удерживает переданную опору чтения после изменения размеров
  */
 fun updateHeight(id:MessageID,height:Double,anchor:ReadingAnchor,viewport:Double):Double{
  val i=positions[id];require(i!=null && height.isFinite() && height>0)
  val old=rows[i];val delta=height-old.estimatedHeight
  (rows as MutableList<IndexedRow>)[i]=old.copy(estimatedHeight=height)
  var n=i+1;while(n<tree.size){tree[n]+=delta;n+=n and -n};totalHeight+=delta
  return offset(anchor,viewport)
 }
 /**
  * Ограничивает результат длиной истории и числом фрагментов не больше ста двадцати восьми
  * Для пустой истории или неподходящего размера области возвращает пустой диапазон
  */
 fun visible(offset:Double,height:Double,overscan:Int=1,maxFragments:Int=128):IntRange{
  if(rows.isEmpty() || height<=0 || maxFragments<=0)return IntRange.EMPTY
  val start=max(0,seek(offset)-max(0,overscan));val end=minOf(rows.size,seek(offset+height)+1+max(0,overscan),start+min(128,maxFragments))
  return start until max(start,end)
 }
 /**
  * В ограниченном видимом диапазоне выбирает только промежутки начала нового календарного дня
  * Возвращает номер опорного сообщения, время и геометрию пересекающих экран разделителей, не превращая их в сообщения истории
  */
 fun days(offset:Double,height:Double):List<HistoryDay>{
  if(dayGap==0.0)return emptyList()
  return visible(offset,height).mapNotNull{i->val y=prefix(i);if(leading[i]>0 && y+leading[i]>offset && y<offset+height)HistoryDay(rows[i].messageID,stamp(rows[i]),y,leading[i])else null}
 }
}
