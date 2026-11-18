package dev.kirill.kandinsky.chat.rendering
import androidx.test.platform.app.InstrumentationRegistry
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.graphics.RectF
import org.junit.Test
import org.junit.Assert.*
class AccessibilityIdentityTest{
 private val i=InstrumentationRegistry.getInstrumentation()
 /**
  * Создаёт виртуальные действия двух сообщений и удаляет соседнюю строку
  * Оставшееся сообщение должно сохранить прежний системный номер независимо от нового положения в наборе
  * Вытесненный номер не должен нажать уже другое сообщение или вызвать его действие
  */
 @Test fun visibleIdentitySurvivesNeighborRemovalAndOldIDCannotActivateDifferentMessage(){i.runOnMainSync{
  val host=View(i.targetContext);var calls=0
  var rows=listOf(NativeAction(1000,"message.a","A",RectF(0f,0f,100f,40f),action={calls++}),NativeAction(1001,"message.b","B",RectF(0f,40f,100f,80f),action={calls++}))
  val provider=NativeAccessibility(host,"test.history",{rows})
  assertEquals("B",provider.createAccessibilityNodeInfo(1001)!!.contentDescription)
  assertEquals("A",provider.createAccessibilityNodeInfo(1000)!!.contentDescription)
  rows=listOf(NativeAction(1000,"message.b","B",RectF(0f,0f,100f,40f),action={calls++}))
  provider.notifyChanged()
  assertEquals("Retained message must keep its native virtual identity","B",provider.createAccessibilityNodeInfo(1001)?.contentDescription)
  rows=listOf(NativeAction(1000,"message.c","C",RectF(0f,0f,100f,40f),action={calls++}))
  provider.notifyChanged()
  assertFalse("An evicted message ID cannot activate another message",provider.performAction(1000,AccessibilityNodeInfo.ACTION_CLICK,null))
  assertEquals(0,calls)
 }}
 /**
  * Явно включает службу управления экраном и ждёт доступности системных событий
  * После изменения структуры виртуального набора родитель должен получить признак обновления всего поддерева
  */
 @Test fun structuralChangesPublishNativeSubtreeInvalidation(){
  // Проверка системных событий требует явно подключённой службы управления экраном
  i.uiAutomation
  val deadline=android.os.SystemClock.uptimeMillis()+5000
  var enabled=false
  while(!enabled && android.os.SystemClock.uptimeMillis()<deadline){
   i.runOnMainSync{enabled=i.targetContext.getSystemService(android.view.accessibility.AccessibilityManager::class.java).isEnabled}
   if(!enabled)android.os.SystemClock.sleep(10)
  }
  i.runOnMainSync{
  assertTrue(i.targetContext.getSystemService(android.view.accessibility.AccessibilityManager::class.java).isEnabled)
  var type=0
  val parent=object:FrameLayout(i.targetContext){
   /**
    * Запоминает вид изменения содержимого в пришедшем системном событии
    * Принимает событие, чтобы вызывающий сценарий мог сверить правильность уведомления поддерева
    */
   override fun requestSendAccessibilityEvent(child:View,event:AccessibilityEvent):Boolean{type=event.contentChangeTypes;return true}}
  val host=View(i.targetContext);parent.addView(host)
  val provider=NativeAccessibility(host,"test.history",{emptyList()});provider.notifyChanged()
  assertEquals(AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE,type)
 }}
}
