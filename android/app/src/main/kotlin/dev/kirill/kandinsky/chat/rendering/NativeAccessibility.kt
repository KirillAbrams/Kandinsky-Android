package dev.kirill.kandinsky.chat.rendering
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider

data class NativeExtraAction(val id:Int,val label:String,val action:()->Unit)
data class NativeAction(val key:Int,val id:String,val label:String,val rect:RectF,val enabled:Boolean=true,val button:Boolean=true,val action:(()->Unit)?=null,val customActions:List<NativeExtraAction> = emptyList(),val longClick:(()->Unit)?=null)
class NativeAccessibility(private val host:View,private val screenID:String,private val actions:()->List<NativeAction>,private val realChildren:()->List<View> = {emptyList()}):AccessibilityNodeProvider() {
    private val manager=host.context.getSystemService(AccessibilityManager::class.java)
    private var focused=View.NO_ID
    // Живые соответствия ограничены текущими доступными элементами сцены
    // Статический номер занимается один раз, поэтому новая строка не подменяет прежний узел
    private val identities=mutableMapOf<String,Int>()
    private val names=mutableMapOf<Int,String>()
    private val claimed=BooleanArray(4096)
    private var nextDynamic=4096
    /**
     * Получает текущие описания нарисованных действий и снимает соответствия элементов, которых больше нет на сцене
     * Для исчезнувшего фокуса посылает событие его снятия и назначает новым элементам устойчивые системные номера
     * Статический номер использует только один раз, а повторно появляющиеся строки получают новые номера без подмены прежнего элемента
     */
    private fun snapshot():List<NativeAction>{
        val items=actions();val present=items.mapTo(HashSet()){it.id}
        for(name in identities.keys.toList())if(name !in present){
            val old=identities.remove(name)!!;names.remove(old)
            if(focused==old){focused=View.NO_ID;event(old,AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)}
        }
        for(item in items)if(item.id !in identities){
            val key=if(item.key in claimed.indices && !claimed[item.key]){
                claimed[item.key]=true;item.key
            }else{
                while(nextDynamic in names){nextDynamic=if(nextDynamic==Int.MAX_VALUE)4096 else nextDynamic+1}
                val value=nextDynamic;nextDynamic=if(nextDynamic==Int.MAX_VALUE)4096 else nextDynamic+1;value
            }
            identities[item.id]=key;names[key]=item.id
        }
        return items
    }
    /**
     * Для корневого запроса возвращает описание хозяина сцены с виртуальными действиями и обычными дочерними представлениями
     * Для известного виртуального элемента задаёт подпись, доступность, границы в родителе и на экране, фокус и поддержанные действия
     * Неизвестный или уже снятый номер возвращает без описания вместо привязки к другому элементу
     */
    override fun createAccessibilityNodeInfo(id:Int):AccessibilityNodeInfo? {
        val items=snapshot()
        if(id==View.NO_ID) return AccessibilityNodeInfo.obtain(host).apply {
            host.onInitializeAccessibilityNodeInfo(this)
            setSource(host);viewIdResourceName=screenID;packageName=host.context.packageName
            className=host.javaClass.name
            for(node in items) addChild(host,identities.getValue(node.id))
            for(child in realChildren()) addChild(child)
        }
        val item=items.find { it.id==names[id] } ?: return null
        val location=IntArray(2);host.getLocationOnScreen(location)
        val parentBounds=Rect();item.rect.roundOut(parentBounds)
        return AccessibilityNodeInfo.obtain().apply {
            setSource(host,id);setParent(host);packageName=host.context.packageName
            className=if(item.button) "android.widget.Button" else "android.widget.TextView"
            viewIdResourceName=item.id;contentDescription=item.label
            isEnabled=item.enabled;isVisibleToUser=host.isShown;isFocusable=true
            isClickable=item.button && item.enabled;isAccessibilityFocused=focused==id
            setBoundsInParent(parentBounds)
            val screen=Rect(parentBounds);screen.offset(location[0],location[1]);setBoundsInScreen(screen)
            addAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            isLongClickable=item.longClick!=null
            if(isLongClickable)addAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            for(extra in item.customActions)addAction(AccessibilityNodeInfo.AccessibilityAction(extra.id,extra.label))
            if(isClickable) addAction(AccessibilityNodeInfo.ACTION_CLICK)
            if(isAccessibilityFocused) addAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
        }
    }
    /**
     * Находит актуальное действие по сохранённому соответствию системного номера и имени элемента
     * Обычное нажатие выполняет только при доступном элементе с обработчиком, отдельно обслуживает фокус, долгое нажатие и дополнительные действия
     * Возвращает признак обработки запроса для системной доступности
     */
    override fun performAction(id:Int,action:Int,arguments:Bundle?):Boolean {
        val item=snapshot().find {it.id==names[id]} ?: return false
        return when(action) {
            AccessibilityNodeInfo.ACTION_CLICK->if(item.enabled && item.action!=null){item.action.invoke();true}else false
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS->{focused=id;event(id,AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);true}
            AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS->{focused=View.NO_ID;event(id,AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);true}
            AccessibilityNodeInfo.ACTION_LONG_CLICK->{item.longClick?.invoke();item.longClick!=null}
            else->item.customActions.firstOrNull{it.id==action}?.let{it.action();true} ?: false
        }
    }
    /**
     * При наличии сохранённого фокуса запрашивает актуальное описание соответствующего элемента
     * Если фокус отсутствует или элемент уже снят со сцены, возвращает отсутствие доступного узла
     */
    override fun findFocus(focus:Int):AccessibilityNodeInfo? = if(focused!=View.NO_ID) createAccessibilityNodeInfo(focused) else null
    /**
     * При включённой системной доступности создаёт событие заданного вида для конкретного виртуального элемента
     * Добавляет сведения приложения и источника и передаёт событие родителю представления
     */
    private fun event(id:Int,type:Int) {
        if(!manager.isEnabled)return
        val e=AccessibilityEvent.obtain(type);e.packageName=host.context.packageName;e.className=host.javaClass.name;e.setSource(host,id)
        host.parent?.requestSendAccessibilityEvent(host,e)
    }
    /**
     * Сначала согласует текущие виртуальные элементы и их номера с изменившейся сценой
     * При включённой доступности отправляет родителю событие изменения всего дочернего содержимого
     */
    fun notifyChanged(){
        snapshot()
        if(!manager.isEnabled)return
        val e=AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        e.contentChangeTypes=AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
        e.packageName=host.context.packageName;e.className=host.javaClass.name;e.setSource(host)
        host.parent?.requestSendAccessibilityEvent(host,e)
    }
}
interface KandinskyInsetHost {
 /**
  * Определяет передачу верхнего и нижнего свободного края экранному представлению
  * Реализация учитывает эти значения в собственном размещении при изменении системных областей или клавиатуры
  */
 fun updateInsets(top:Int,bottom:Int) }
