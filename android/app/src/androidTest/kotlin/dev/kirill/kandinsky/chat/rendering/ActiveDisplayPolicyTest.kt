package dev.kirill.kandinsky.chat.rendering
import android.content.Intent
import android.view.*
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.kirill.kandinsky.chat.app.MainActivity
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID
class ActiveDisplayPolicyTest{
 /**
  * Начинает настоящее инерционное движение истории через последовательность системных событий касания
  * Во время движения окно должно запросить самый частый совместимый режим экрана без смены физических размеров
  * После остановки должен восстановиться прежний запрос режима
  * Проверка запроса частоты не измеряет число фактически показанных кадров приложения
  */
 @Test fun activeNativeInertiaHintsHighestCompatibleDisplayModeAndRestoresIdleWindow(){
  val i=InstrumentationRegistry.getInstrumentation();val d=UiDevice.getInstance(i);Configurator.getInstance().waitForIdleTimeout=0
  val intent=i.targetContext.packageManager.getLaunchIntentForPackage(i.targetContext.packageName)!!.apply{addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)}
  val activity=i.startActivitySync(intent) as MainActivity
  try{
   dev.kirill.kandinsky.chat.support.AppFlowDriver().openChat("Длинная история")
   assertNotNull(d.wait(Until.findObject(By.res("composer.input").enabled(true)),15000))
   /**
    * Рекурсивно ищет прокручиваемую область истории в дереве представлений окна
    * Возвращает первый найденный объект либо отсутствие результата после обхода потомков
    */
   fun host(v:View):ChatScrollHost?{if(v is ChatScrollHost)return v;if(v is ViewGroup)for(n in 0 until v.childCount)host(v.getChildAt(n))?.let{return it};return null}
   lateinit var scroll:ChatScrollHost;var previous=0;var expected=0
   i.runOnMainSync{
    scroll=host(activity.window.decorView)!!;previous=activity.window.attributes.preferredDisplayModeId
    val display=activity.window.decorView.display;val current=display.mode
    expected=display.supportedModes.filter{it.physicalWidth==current.physicalWidth && it.physicalHeight==current.physicalHeight}.maxBy{it.refreshRate}.modeId
    val t=SystemClock.uptimeMillis()
    for(n in 0..8){val action=if(n==0)MotionEvent.ACTION_DOWN else if(n==8)MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
     val e=MotionEvent.obtain(t,t+n*16,action,288f,400f+n*30f,0);e.source=InputDevice.SOURCE_TOUCHSCREEN;scroll.onTouchEvent(e);e.recycle()}
   }
   var activeSeen=false;val end=SystemClock.uptimeMillis()+5000
   while(!activeSeen && SystemClock.uptimeMillis()<end){i.runOnMainSync{activeSeen=activity.window.attributes.preferredDisplayModeId==expected};SystemClock.sleep(10)}
   assertTrue("Active native scroll must hint highest compatible mode id="+expected,activeSeen)
   var restored=false;val idle=SystemClock.uptimeMillis()+8000
   while(!restored && SystemClock.uptimeMillis()<idle){i.runOnMainSync{restored=activity.window.attributes.preferredDisplayModeId==previous};SystemClock.sleep(20)}
   assertTrue("Idle screen restores the preceding window mode hint",restored)
  }finally{i.runOnMainSync{activity.finish()}}
 }
}
