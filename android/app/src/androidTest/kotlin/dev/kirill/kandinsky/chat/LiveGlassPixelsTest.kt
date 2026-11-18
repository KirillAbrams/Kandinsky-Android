package dev.kirill.kandinsky.chat
import android.content.Intent
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.kirill.kandinsky.chat.app.MainActivity
import dev.kirill.kandinsky.chat.rendering.NativeGlassSurface
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
class LiveGlassPixelsTest {
 /**
  * В отдельной рисующей поверхности помещает прозрачную панель поверх цветных полос
  * Проверяет смешение задних цветов и сохранение чёрного элемента переднего плана по действительным пикселям окна
  * После замены заднего рисунка зелёная составляющая под панелью должна вырасти
  * В завершении освобождает поверхность и убирает проверочный рисунок
  */
 @Test fun backdropIsFilteredLiveWhileForegroundRemainsSharp(){
  val i=InstrumentationRegistry.getInstrumentation();UiDevice.getInstance(i).wakeUp()
  val activity=i.startActivitySync(i.targetContext.packageManager.getLaunchIntentForPackage(i.targetContext.packageName)!!.apply{addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)}) as MainActivity
  // Отдельный рисунок устанавливается после окончания начального открытия хранилища
  val d=UiDevice.getInstance(i);assertTrue(d.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.pkg(i.targetContext.packageName)),15000))
  val glass=NativeGlassSurface(activity);var green=false
  val ink=Paint();val pane=RectF(40f,100f,540f,500f)
  val view=object:View(activity){
   /**
    * Заливает переданную область красным с узкими синими полосами либо сплошным зелёным
    * Выбор задаётся состоянием текущего опыта для проверки обновления содержимого под панелью
    */
   private fun backdrop(c:Canvas){c.drawColor(if(green)Color.GREEN else Color.RED);if(!green){ink.color=Color.BLUE;var x=0f;while(x<width){c.drawRect(x,0f,x+8,height.toFloat(),ink);x+=16}}}
   /**
    * Рисует исходный фон, прозрачную панель с повторным чтением того же фона и чёрный элемент переднего плана
    * Порядок позволяет проверить раздельно размытие заднего рисунка и чёткость переднего
    */
   override fun onDraw(c:Canvas){backdrop(c);glass.draw(c,pane,20f,resources.displayMetrics.density){backdrop(it)};ink.color=Color.BLACK;c.drawRect(250f,250f,270f,270f,ink)}
  }
  try{
   i.runOnMainSync{activity.setContentView(view);view.setWillNotDraw(false)}
   /**
    * Ждёт применения обновлений и запрашивает системное копирование действительных пикселей окна
    * Требует успешного ответа в установленный срок
    * Возвращает отдельное изображение, которое вызывающий код должен освободить
    */
   fun copy():Bitmap{
    i.waitForIdleSync();android.os.SystemClock.sleep(300)
    val bitmap=Bitmap.createBitmap(activity.window.decorView.width,activity.window.decorView.height,Bitmap.Config.ARGB_8888)
    val latch=CountDownLatch(1);var status=-1
    PixelCopy.request(activity.window,bitmap,{status=it;latch.countDown()},Handler(Looper.getMainLooper()))
    assertTrue(latch.await(5,TimeUnit.SECONDS));assertEquals(PixelCopy.SUCCESS,status);return bitmap
   }
   /**
    * Получает положение проверочного представления внутри окна на основном потоке
    * Читает цвет из снимка по указанным координатам относительно этого представления
    */
   fun pixel(b:Bitmap,x:Int,y:Int):Int{val point=IntArray(2);i.runOnMainSync{view.getLocationInWindow(point)};return b.getPixel(point[0]+x,point[1]+y)}
   val first=copy();val filtered=pixel(first,200,200);val sharp=pixel(first,260,260)
   assertTrue("Live blur mixes narrow red/blue stripes",Color.red(filtered) in 50..240 && Color.blue(filtered) in 50..240)
   assertTrue("Foreground black remains sharp",Color.red(sharp)<20 && Color.green(sharp)<20 && Color.blue(sharp)<20)
   i.runOnMainSync{green=true;view.invalidate()};val second=copy();val changed=pixel(second,200,200)
   assertTrue("Changing backdrop updates the material",Color.green(changed)>Color.green(filtered)+70)
   first.recycle();second.recycle()
  }finally{i.runOnMainSync{activity.setContentView(View(activity));glass.close()}}
 }
 /**
  * Рисует прозрачную панель программным способом поверх красных и синих полос
  * Повторяет рисунок после смены фона на зелёный и требует соответствующего изменения пикселей
  * Проверяет допустимость повторного закрытия поверхности и освобождает изображение
  */
 @Test fun softwareCompatibilityBlurUsesLivePixelsAndCloses(){
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val surface=NativeGlassSurface(context);val image=Bitmap.createBitmap(300,180,Bitmap.Config.ARGB_8888);val c=Canvas(image)
  var green=false;val paint=Paint();val pane=RectF(10f,10f,290f,170f)
  val backdrop:(Canvas)->Unit={canvas->canvas.drawColor(if(green)Color.GREEN else Color.RED);if(!green){paint.color=Color.BLUE;var x=0f;while(x<300){canvas.drawRect(x,0f,x+4,180f,paint);x+=8}}}
  try{
   surface.draw(c,pane,20f,1f,backdrop);val before=image.getPixel(150,80)
   assertTrue(Color.red(before)>40 && Color.blue(before)>40)
   green=true;surface.draw(c,pane,20f,1f,backdrop);val after=image.getPixel(150,80)
   assertTrue(Color.green(after)>Color.green(before)+70)
  }finally{surface.close();surface.close();image.recycle()}
 }

}
