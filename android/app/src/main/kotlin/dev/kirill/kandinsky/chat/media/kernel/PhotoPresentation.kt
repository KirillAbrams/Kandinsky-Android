package dev.kirill.kandinsky.chat.media.kernel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
internal enum class PhotoSurfaceStyle { FIT,FILL,AVATAR }
/** Перевод исходных пикселей в область показа без создания повёрнутого изображения или копии каждого кадра */
internal data class PhotoPresentation(val a:Double,val b:Double,val c:Double,val d:Double,val tx:Double,val ty:Double){
 /**
  * Переводит точку исходного изображения в координаты области показа по подготовленному преобразованию
  * Учитывает масштаб, поворот, отражение и смещение через сохранённые коэффициенты
  * Изображение в памяти при таком переводе не копируется
  */
 fun point(x:Double,y:Double)=Pair(a*x+c*y+tx,b*x+d*y+ty)
 companion object{
  /**
   * Проверяет размеры и допустимые значения, а для аватара требует квадратную область
   * Сохраняет пропорции при вписывании или заполнении и возвращает отсутствие результата для неподходящих параметров
   */
  fun make(rawWidth:Int,rawHeight:Int,orientation:Int,width:Double,height:Double,style:PhotoSurfaceStyle):PhotoPresentation?{
   if(rawWidth !in 1..2048||rawHeight !in 1..2048||orientation !in 1..8||
      !width.isFinite()||!height.isFinite()||width<=0||height<=0||width>8192||height>8192||
      (style==PhotoSurfaceStyle.AVATAR&&abs(width-height)>0.001))return null
   val w=rawWidth.toDouble();val h=rawHeight.toDouble();val sw=if(orientation>=5)h else w;val sh=if(orientation>=5)w else h
   val scale=if(style==PhotoSurfaceStyle.FIT)min(width/sw,height/sh)else max(width/sw,height/sh)
   val x=(width-sw*scale)/2;val y=(height-sh*scale)/2
   return when(orientation){
    1->PhotoPresentation(scale,0.0,0.0,scale,x,y)
    2->PhotoPresentation(-scale,0.0,0.0,scale,x+w*scale,y)
    3->PhotoPresentation(-scale,0.0,0.0,-scale,x+w*scale,y+h*scale)
    4->PhotoPresentation(scale,0.0,0.0,-scale,x,y+h*scale)
    5->PhotoPresentation(0.0,scale,scale,0.0,x,y)
    6->PhotoPresentation(0.0,scale,-scale,0.0,x+h*scale,y)
    7->PhotoPresentation(0.0,-scale,-scale,0.0,x+h*scale,y+w*scale)
    else->PhotoPresentation(0.0,-scale,scale,0.0,x,y+w*scale)
   }
  }
 }
}
