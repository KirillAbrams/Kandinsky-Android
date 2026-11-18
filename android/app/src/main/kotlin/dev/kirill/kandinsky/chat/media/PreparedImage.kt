package dev.kirill.kandinsky.chat.media
import android.graphics.Bitmap
class ImageByteBudget {
 private val gate=Any();private var used=0;private var peak=0;private var maximum=48*1024*1024
 var limit:Int get()=synchronized(gate){maximum};set(value){synchronized(gate){maximum=value.coerceAtLeast(0)}}
 val bytes get()=synchronized(gate){used}
 val peakBytes get()=synchronized(gate){peak}
 /**
  * Учитывает уже занятые байты и текущий предел
  * Само место этим ответом ещё не занимает
  */
 fun canFit(value:Int)=synchronized(gate){value>0 && used<=maximum-value}
 /**
  * Занимает допустимый объём памяти до начала подготовки изображения
  * Увеличивает текущий учёт и запоминает наибольшее достигнутое значение
  * Возвращает отдельного владельца резерва либо отказ, если свободного бюджета недостаточно
  */
 fun reserve(value:Int):ImageByteCharge?=synchronized(gate){if(value<=0 || used>maximum-value)null else{used+=value;peak=maxOf(peak,used);ImageByteCharge(this,value)}}
 /**
  * Уменьшает учтённое число занятых байтов после возврата резерва
  * Выполняет изменение под общей защитой и не допускает отрицательного остатка
  * Само изображение или системную поверхность этот счётчик не освобождает
  */
 internal fun release(value:Int){synchronized(gate){used=(used-value).coerceAtLeast(0)}}
}
class ImageByteCharge(private val budget:ImageByteBudget,private var amount:Int):AutoCloseable{
 /**
  * Уменьшает предварительный резерв до действительного размера подготовленных пикселей
  * Отклоняет отрицательный размер и попытку увеличить резерв через это действие
  * Разницу возвращает общему бюджету после изменения принадлежащей владельцу суммы
  */
 @Synchronized fun shrink(actual:Int){if(actual<0 || actual>amount)throw MediaException(MediaError.UNAVAILABLE);val difference=amount-actual;amount=actual;budget.release(difference)}
 /**
  * Сначала обнуляет собственную сумму, чтобы повторный вызов не освободил её второй раз
  * Само владение пикселями управляется отдельным объектом
  */
 @Synchronized override fun close(){val value=amount;amount=0;budget.release(value)}
}
internal class ImagePixels(bitmap:Bitmap,private val charge:ImageByteCharge){
 private val gate=Any();private var value:Bitmap?=bitmap;private var references=1;private var ownerClosed=false;private var published=false
 val byteSize=bitmap.allocationByteCount
 /**
  * Создаёт отдельное удержание ещё доступных пикселей для одного потребителя
  * Увеличивает число владельцев под защитой состояния и возвращает оболочку готового изображения
  * Если пиксели уже освобождены, возвращает отсутствие кадра
  */
 fun frame():PreparedImage?=synchronized(gate){if(value==null || references<=0)null else{references++;published=true;PreparedImage(this)}}
 /**
  * Не создаёт отдельную копию или новое удержание
  * Вызывающий потребитель должен уже владеть соответствующим готовым кадром
  */
 fun bitmap():Bitmap?=synchronized(gate){value}
 /**
  * Закрывает исходное владение подготовленными пикселями только один раз
  * Остальные выданные кадры продолжают удерживать это изображение
  * Фактическое снятие собственного изображения и учтённого резерва произойдёт после последнего владельца
  */
 fun closeOwner(){synchronized(gate){if(ownerClosed)return;ownerClosed=true;releaseLocked()}}
 /**
  * Возвращает одно ранее выданное владение пикселями под защитой общего состояния
  * Передаёт уменьшение числа владельцев внутреннему освобождению
  * Используется при закрытии готового кадра его потребителем
  */
 fun release(){synchronized(gate){releaseLocked()}}
 /**
  * Уменьшает число владельцев подготовленных пикселей на одно завершённое удержание
  * После возврата последнего убирает собственную ссылку на изображение и возвращает учтённый резерв
  * Вызывается только внутри предусмотренной защиты состояния изображения
  */
 private fun releaseLocked(){
  references--
  if(references==0){
   // Явно освобождаем только пиксели, которые ещё не передавались системе рисования
   // Для показанных изображений система удерживает свои ссылки, поэтому снимаем только собственное владение
   if(!published)value?.recycle()
   value=null;charge.close()
  }
 }
}
class PreparedImage internal constructor(private var pixels:ImagePixels?):AutoCloseable {
 private val gate=Any();val orientation=1
 @Volatile var closeCount=0;private set
 val bitmap:Bitmap? get()=synchronized(gate){pixels?.bitmap()}
 /**
  * Создаёт дополнительного независимого владельца того же подготовленного изображения
  * Позволяет передать готовый кадр другому экрану без повторного декодирования
  * Если исходное удержание уже закрыто, возвращает отсутствие результата
  */
 fun retained():PreparedImage?=synchronized(gate){pixels?.frame()}
 /**
  * Закрывает только это удержание готового изображения и убирает его ссылку на пиксели
  * Передаёт возврат одного владельца общему объекту изображения
  * Повторное закрытие не увеличивает счётчик и не освобождает чужое удержание
  */
 override fun close(){val p=synchronized(gate){val p=pixels ?: return;pixels=null;closeCount++;p};p.release()}
}
internal class ThumbnailCache(private val budget:ImageByteBudget,private val entryLimit:Int=16,private val navigationBytes:Int=8*1024*1024) {
 private val values=LinkedHashMap<String,ImagePixels>(16,0.75f,true)
 /**
  * Ищет готовые пиксели по постоянному обозначению и возвращает отдельное удержание
  * При успехе учитывает недавнее использование записи для выбора будущего вытеснения
  * При отсутствии записи не запускает загрузку или подготовку изображения
  */
 fun frame(key:String)=values[key]?.frame()
 /**
  * Убирает собственные давно не использованные изображения, пока бюджет не допускает новый резерв
  * Каждый раз повторно проверяет действительное число занятых байтов
  * Может вернуть отказ даже после очистки, если изображения ещё удерживаются видимыми потребителями
  */
 fun makeRoom(amount:Int):Boolean {
  val iterator=values.entries.iterator()
  while(!budget.canFit(amount) && iterator.hasNext()){iterator.next().value.closeOwner();iterator.remove()}
  return budget.canFit(amount)
 }
 /**
  * Сохраняет исходное владение подготовленными пикселями под выбранным обозначением
  * При замене возвращает владение прежней записи и отмечает новое использование
  * Лишние записи вытесняет по давности, чтобы число изображений оставалось ограниченным
  */
 fun insert(value:ImagePixels,key:String){values.remove(key)?.closeOwner();values[key]=value;val iterator=values.entries.iterator();while(values.size>entryLimit && iterator.hasNext()){iterator.next().value.closeOwner();iterator.remove()}}
 /**
  * Сокращает готовый запас на время перехода между чатами
  * Сначала убирает отдельные полноразмерные записи обычного просмотра, затем самые старые до предела памяти перехода
  * Остальные удержания видимых изображений продолжают принадлежать их потребителям
  */
 fun retainNavigationImages(){
  val originals=values.entries.iterator()
  while(originals.hasNext()){val entry=originals.next();if(entry.key.contains("@original:")){entry.value.closeOwner();originals.remove()}}
  var bytes=values.values.sumOf{it.byteSize};val oldest=values.entries.iterator()
  while(bytes>navigationBytes && oldest.hasNext()){val value=oldest.next().value;bytes-=value.byteSize;value.closeOwner();oldest.remove()}
 }
 /**
  * Убирает все собственные записи готового запаса и возвращает их исходное владение
  * Очищает порядок использования, не затрагивая постоянные файлы
  * Кадры, которые уже выданы другому владельцу, остаются живы до его отдельного закрытия
  */
 fun clear(){values.values.forEach{it.closeOwner()};values.clear()}
}
