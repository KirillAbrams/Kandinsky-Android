package dev.kirill.kandinsky.chat.support

import android.app.Instrumentation
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.uiautomator.*
import org.junit.Assert.assertTrue

/**
 * Начинает запись настоящим удержанием пальца и затем переводит её в закреплённое состояние жестом вверх
 * Перед жестом ждёт закрытия прежней записи и доступности нужной кнопки
 * После движения отпускает палец, чтобы проверить продолжение записи без дальнейшего удержания
 */
fun lockNativeCapture(i:Instrumentation,device:UiDevice,id:String){
 assertTrue("Prior capture has left the window after native retirement",device.wait(Until.gone(By.res("recording.screen")),5000))
 val button=checkNotNull(device.wait(Until.findObject(By.res(id).enabled(true)),10000)){"Capture control $id must be available"}
 val rect=button.visibleBounds;val x=rect.exactCenterX();val y=rect.exactCenterY();val down=SystemClock.uptimeMillis()
 /**
  * Создаёт одно событие касания с общим временем начала текущего жеста
  * Передаёт его через системное управление вводом и освобождает событие после доставки
  */
 fun event(action:Int,px:Float,py:Float){
  val value=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,px,py,0);value.source=InputDevice.SOURCE_TOUCHSCREEN
  try{assertTrue("Native capture pointer is delivered",i.uiAutomation.injectInputEvent(value,true))}finally{value.recycle()}
 }
 event(MotionEvent.ACTION_DOWN,x,y);SystemClock.sleep(320)
 val distance=110*i.targetContext.resources.displayMetrics.density
 for(step in 1..8){event(MotionEvent.ACTION_MOVE,x,y-distance*step/8);SystemClock.sleep(20)}
 event(MotionEvent.ACTION_UP,x,y-distance)
}
