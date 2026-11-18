package dev.kirill.kandinsky.chat.media
import android.content.Context
import android.graphics.*
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageView
import dev.kirill.kandinsky.chat.rendering.KandinskyPalette

// Перенос поверхности между экранами не создаёт второй проигрыватель и не добавляет управление поверх кадра
class InlinePlayerSurface(context:Context,private val coordinator:MediaCoordinator):FrameLayout(context),TextureView.SurfaceTextureListener {
 private val texture=TextureView(context)
 private val poster=ImageView(context).apply{scaleType=ImageView.ScaleType.CENTER_CROP}
 private var posterFrame:PreparedImage?=null
 private var surface:Surface?=null
 private var retainedTexture:SurfaceTexture?=null
 private var movingPresentation=false
 var studioPreviewBackground=false;set(value){if(field!=value){field=value;update()}}
 var hasVideoFrame=false;private set
 private var circular=false
 private val solidFill=android.graphics.drawable.ColorDrawable(Color.BLACK)
 private var backgroundTone=Color.BLACK
 private var aspect=1f
 private var backdropPixels:android.graphics.Bitmap?=null
 private var backdropFrame=0L;private var copiedBackdropFrame=-1L;private var backdropCopies=0
 init{
  setBackgroundColor(Color.BLACK);isClickable=false
  addView(texture,LayoutParams(-1,-1));texture.surfaceTextureListener=this;addView(poster,LayoutParams(-1,-1))
 }
 /**
  * Обновляет только фон и подключение доступной видеоповерхности
  * Сохранённое изображение остаётся до появления настоящего кадра текущего источника
  */
 fun update(){
  if(coordinator.currentMessageID!=null && texture.isAvailable && surface==null && retainedTexture==null)texture.surfaceTexture?.let{onSurfaceTextureAvailable(it,texture.width,texture.height)}
  poster.visibility=if(posterFrame==null)View.GONE else View.VISIBLE
  val tone=if(circular)Color.TRANSPARENT else if(studioPreviewBackground)KandinskyPalette.studioSheet else Color.BLACK
  if(backgroundTone!=tone){solidFill.color=tone;backgroundTone=tone};background=solidFill
 }
 /**
  * Измеряет видеовыход и сохранённую подложку по прежним пропорциям источника
  * Круглая обрезка использует ту же ограниченную сторону без дополнительных дочерних элементов
  */
 override fun onMeasure(w:Int,h:Int){
  val width=MeasureSpec.getSize(w);val height=MeasureSpec.getSize(h);setMeasuredDimension(width,height)
  val vw=if(circular)minOf(width,height)else if(width/height.coerceAtLeast(1).toFloat()>aspect)(height*aspect).toInt() else width
  val vh=if(circular)vw else (vw/aspect).toInt()
  for(view in listOf(texture,poster))view.measure(MeasureSpec.makeMeasureSpec(maxOf(1,vw),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(maxOf(1,vh),MeasureSpec.EXACTLY))
 }
 /**
  * Размещает видео и неподвижный кадр в одинаковых границах с прежним преобразованием круглого заполнения
  * Положение поверхности не зависит от обновления времени воспроизведения
  */
 override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){
  val vw=texture.measuredWidth;val vh=texture.measuredHeight
  val x=if(circular)0 else (width-vw)/2;val y=(height-vh)/2
  texture.layout(x,y,x+vw,y+vh)
  texture.setTransform(Matrix().apply{if(circular)setScale(maxOf(1f,aspect),maxOf(1f,1/aspect),vw/2f,vh/2f)})
  poster.layout(x,y,x+vw,y+vh);poster.clipToOutline=circular;poster.outlineProvider=texture.outlineProvider
 }
 /**
  * Передаёт поверхности владение подготовленным неподвижным кадром
  * Снимает прежний рисунок и возвращает его удержание перед установкой нового
  * Видимость подложки определяется наличием переданного кадра
  */
 fun setPoster(frame:PreparedImage?){if(frame==null && posterFrame==null && poster.drawable==null)return;poster.setImageDrawable(null);posterFrame?.close();posterFrame=frame;poster.setImageBitmap(frame?.bitmap);poster.visibility=if(frame==null)View.GONE else View.VISIBLE}
 /**
  * Отмечает начало переноса видеоповерхности между представлениями
  * При системном уничтожении этот признак позволит сохранить прежнюю поверхность
  */
 fun beginPresentationMove(){movingPresentation=true}
 /**
  * Откладывает завершение переноса до следующей работы потока экрана
  * Снимает признак переноса, только если сохранённая поверхность больше не ожидает возврата
  */
 fun finishPresentationMove(){post{if(retainedTexture==null)movingPresentation=false}}
 /**
  * Меняет круглую обрезку видеоповерхности при изменении режима
  * Назначает соответствующий внешний контур и обновляет оформление и размещение
  */
 fun circular(value:Boolean){
  if(circular==value)return;circular=value
  // Цвет заднего плана задаётся общим обновлением состояния поверхности
  texture.clipToOutline=value
  texture.outlineProvider=object:ViewOutlineProvider(){
   /**
    * Задаёт круглый либо прямоугольный контур по границам видеовыхода
    * Выбор контура соответствует режиму, для которого создан этот обработчик
    */
   override fun getOutline(view:View,outline:android.graphics.Outline){if(value)outline.setOval(0,0,view.width,view.height)else outline.setRect(0,0,view.width,view.height)}};update();requestLayout()
 }
 /**
  * Вычисляет допустимое отношение ширины и высоты источника
  * При заметном изменении пропорций запрашивает новое размещение видео
  */
 fun aspect(width:Int,height:Int){val next=maxOf(1,width).toFloat()/maxOf(1,height);if(kotlin.math.abs(next-aspect)>0.0001f){aspect=next;requestLayout()}}
 /**
  * При обычном выводе повторно использует уменьшенную копию кадра до появления нового
  * Сохраняет круглую обрезку и уже подготовленную неподвижную подложку
  */
 fun drawBackdrop(c:android.graphics.Canvas){
  if(c.quickReject(0f,0f,width.toFloat(),height.toFloat(),android.graphics.Canvas.EdgeType.AA))return
  if(c.isHardwareAccelerated){draw(c);return}
  val save=c.save()
  if(circular)c.clipPath(android.graphics.Path().apply{addOval(android.graphics.RectF(texture.left.toFloat(),texture.top.toFloat(),texture.right.toFloat(),texture.bottom.toFloat()),android.graphics.Path.Direction.CW)})
  else c.drawColor(android.graphics.Color.BLACK)
  if(texture.isAvailable && texture.width>0 && texture.height>0 && !c.quickReject(texture.left.toFloat(),texture.top.toFloat(),texture.right.toFloat(),texture.bottom.toFloat(),android.graphics.Canvas.EdgeType.AA)){
   val scale=minOf(1.0,256.0/texture.width,256.0/texture.height);val w=maxOf(1,(texture.width*scale).toInt());val h=maxOf(1,(texture.height*scale).toInt())
   var target=backdropPixels
   if(target==null || target.width!=w || target.height!=h){target?.recycle();target=android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888);backdropPixels=target;copiedBackdropFrame=-1}
   if(copiedBackdropFrame!=backdropFrame){texture.getBitmap(target)?.let{backdropCopies++;copiedBackdropFrame=backdropFrame}}
   if(copiedBackdropFrame>=0)target?.let{c.drawBitmap(it,null,android.graphics.RectF(texture.left.toFloat(),texture.top.toFloat(),texture.right.toFloat(),texture.bottom.toFloat()),android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))}
  }
  c.restoreToCount(save)
  for(n in 0 until childCount){val child=getChildAt(n);if(child===texture || child.visibility!=View.VISIBLE)continue;val s=c.save();c.translate(child.left.toFloat(),child.top.toFloat());child.draw(c);c.restoreToCount(s)}
 }
 /**
  * Снимает кадр, подпись, загрузку и временные данные заднего плана
  * Забирает действующую и сохранённую поверхности из собственного состояния
  * Освобождает их после подтверждения отсоединения от воспроизведения
  */
 fun detachPlayer(){hasVideoFrame=false;setPoster(null);movingPresentation=false;backdropPixels?.recycle();backdropPixels=null;copiedBackdropFrame=-1;val old=surface;surface=null;val held=retainedTexture;retainedTexture=null;if(old!=null || held!=null)coordinator.attach(null){old?.release();held?.release()}}
 /**
  * Возвращает сохранённую поверхность после переноса, если она существует
  * Иначе создаёт новый выход только для выбранного видео
  * Прежний выход освобождает после подтверждения подключения нового
  */
 override fun onSurfaceTextureAvailable(t:SurfaceTexture,w:Int,h:Int){
  val held=retainedTexture
  if(held!=null){retainedTexture=null;movingPresentation=false;if(held!==t)texture.setSurfaceTexture(held);return}
  movingPresentation=false
  if(coordinator.currentMessageID==null)return
  val old=surface;val next=Surface(t);surface=next;coordinator.attach(next){old?.release()}
 }
 // Отдельной перенастройки воспроизведения в этом обработчике нет
 override fun onSurfaceTextureSizeChanged(t:SurfaceTexture,w:Int,h:Int){}
 /**
  * При переносе сохраняет действующую поверхность вместо её немедленного освобождения
  * В остальных случаях отсоединяет выход и освобождает его с поверхностью после подтверждения
  * Сообщает системе, что освобождение остаётся за этим владельцем
  */
 override fun onSurfaceTextureDestroyed(t:SurfaceTexture):Boolean{
  if(movingPresentation && surface!=null){retainedTexture=t;return false}
  val old=surface;surface=null;if(old!=null)coordinator.attach(null){old.release();t.release()}else t.release();return false
 }
 /**
  * Отмечает появление нового кадра для обновления копии заднего плана
  * При готовом воспроизведении подтверждает наличие видео и снимает неподвижную подложку
  */
 override fun onSurfaceTextureUpdated(t:SurfaceTexture){backdropFrame++;if(coordinator.isReady){hasVideoFrame=true;setPoster(null)}}
}
