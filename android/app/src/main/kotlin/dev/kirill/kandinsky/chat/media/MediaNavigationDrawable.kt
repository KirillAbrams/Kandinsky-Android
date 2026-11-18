package dev.kirill.kandinsky.chat.media
import android.graphics.*
import android.graphics.drawable.Drawable

internal class MediaNavigationDrawable(private val back:Boolean=false):Drawable(){
 private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;strokeWidth=if(back)2f else 1f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
 /**
  * Масштабирует его по границам без поиска шрифта
  * После рисунка возвращает прежние координаты холста
  */
 override fun draw(canvas:Canvas){
  val size=minOf(bounds.width(),bounds.height())*(if(back)0.5f else 1f);val save=canvas.save()
  canvas.translate(bounds.exactCenterX()-size/2,bounds.exactCenterY()-size/2);canvas.scale(size/24,size/24)
  paint.style=Paint.Style.STROKE
  if(back){val path=Path();path.moveTo(15f,4f);path.lineTo(7f,12f);path.lineTo(15f,20f);canvas.drawPath(path,paint)}
  else{canvas.drawLine(6f,6f,18f,18f,paint);canvas.drawLine(18f,6f,6f,18f,paint)}
  canvas.restoreToCount(save)
 }
 /**
  * Меняет прозрачность краски значка и запрашивает обновление рисунка
  * Геометрию выбранного знака этим вызовом не перестраивает
  */
 override fun setAlpha(alpha:Int){paint.alpha=alpha;invalidateSelf()}
 /**
  * Назначает цветовой фильтр краске значка и запрашивает обновление
  * Фильтр применяется при следующем рисовании текущей геометрии
  */
 override fun setColorFilter(filter:ColorFilter?){paint.colorFilter=filter;invalidateSelf()}
 // Это позволяет учитывать незаполненную часть его границ при выводе
 @Deprecated("Drawable translucency is intrinsic") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
