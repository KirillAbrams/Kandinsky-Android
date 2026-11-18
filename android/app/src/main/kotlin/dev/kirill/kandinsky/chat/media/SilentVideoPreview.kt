package dev.kirill.kandinsky.chat.media

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.FrameLayout
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.rendering.PreparedText

/** Беззвучная системная поверхность оставляет неподвижное изображение сцены видимым до первого кадра видео */
class SilentVideoPreview(context:Context,private val store:MediaStore,private val changed:()->Unit):FrameLayout(context),TextureView.SurfaceTextureListener,AutoCloseable {
 private val texture=TextureView(context).apply{isOpaque=false;alpha=0f;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
 private val main=Handler(Looper.getMainLooper())
 private var identity:String?=null
 private var request:Cancellation?=null
 private var lifetime:PreviewLifetime?=null
 private var session:PlayerSession?=null
 private var surface:Surface?=null
 private val heldTextures=mutableListOf<SurfaceTexture>()
 private var retiring=false
 private var outputsRetired=false
 private var aspect=1f;private var transformedAspect=-1f
 private var transformedWidth=0;private var transformedHeight=0
 private var generation=0
 private var hasFrame=false
 private var circular=false
 private var cornerRadius=0f
 private var bottomSquare=false
 private var stamp:RenderNode?=null
 private val stampPlacement=RenderNode("Kandinsky preview foreground placement").apply{setClipToBounds(false)}
 private var prepared:PreparedText?=null
 private var localMedia=RectF()
 private var backdrop:Bitmap?=null
 private var frameVersion=0L
 private var copiedVersion=-1L
 var frameCallbacks=0;private set
 var closed=false;private set
 val active get()=!closed && hasFrame && session!=null
 private val invalidation=store.registerInvalidation{close()}
 init{
  setBackgroundColor(Color.TRANSPARENT);isClickable=false;isFocusable=false;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
  addView(texture,LayoutParams(-1,-1));texture.surfaceTextureListener=this
  texture.clipToOutline=true
  texture.outlineProvider=object:ViewOutlineProvider(){
   /**
    * Для круглого видео выбирает овал, для обычного использует подготовленный контур углов
    * При пустых размерах возвращает пустой контур
    */
   override fun getOutline(view:View,outline:Outline){
   if(view.width<=0 || view.height<=0){outline.setEmpty();return}
   if(circular)outline.setOval(0,0,view.width,view.height)else{
    @Suppress("DEPRECATION") outline.setConvexPath(shape(view.width.toFloat(),view.height.toFloat()))
   }
  }}
 }
 /**
  * Согласует размеры и форму беззвучного предпросмотра с текущей ячейкой сообщения
  * Для нового источника получает ограниченное место показа и начинает подготовку файла
  * Один владелец относится к одному исходнику, поэтому при замене файла закрывается для создания следующего
  * Результат устаревшей подготовки освобождает вместо установки в уже изменившуюся ячейку
  */
 fun bind(attachment:Attachment,rect:RectF,circular:Boolean,cornerRadius:Float,bottomSquare:Boolean,stamp:RenderNode,prepared:PreparedText,studioAlbum:Boolean=false){
  val stampChanged=this.stamp!==stamp
  val outlineChanged=this.circular!=circular || this.cornerRadius!=cornerRadius || this.bottomSquare!=bottomSquare || width!=rect.width().toInt() || height!=rect.height().toInt()
  this.circular=circular;this.cornerRadius=cornerRadius;this.bottomSquare=bottomSquare;this.stamp=stamp;this.prepared=prepared;localMedia=RectF(prepared.mediaRect ?: RectF())
  val w=if(attachment.orientation>=5)attachment.rawHeight else attachment.rawWidth;val h=if(attachment.orientation>=5)attachment.rawWidth else attachment.rawHeight
  aspect=maxOf(1,w).toFloat()/maxOf(1,h)
  measure(MeasureSpec.makeMeasureSpec(rect.width().toInt().coerceAtLeast(1),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(rect.height().toInt().coerceAtLeast(1),MeasureSpec.EXACTLY))
  layout(rect.left.toInt(),rect.top.toInt(),rect.right.toInt(),rect.bottom.toInt());if(outlineChanged)texture.invalidateOutline()
  // Обратное положение строки сохраняем в свойствах системного узла
  // Записанный абсолютный сдвиг остался бы в прежних командах дочернего рисунка
  // в то время как верхний узел сцены продолжает перемещаться
  stampPlacement.setPosition(0,0,width,height)
  stampPlacement.translationX=-left.toFloat();stampPlacement.translationY=-top.toFloat()
  if(stampChanged || !stampPlacement.hasDisplayList()){
   val canvas=stampPlacement.beginRecording(width.coerceAtLeast(1),height.coerceAtLeast(1))
   try{canvas.drawRenderNode(stamp)}finally{stampPlacement.endRecording()}
   invalidate()
  }
  if(closed)return
  // Перед заменой источника завершаем прежнего владельца
  // Следующий создаётся после подтверждения освобождения прежней системной сессии
  if(identity!=null){if(identity!=attachment.relativePath)close();return}
  val lease=MediaAdmission.reservePreview(studioAlbum){close()} ?: return
  val life=PreviewLifetime(lease);lifetime=life;identity=attachment.relativePath;val ticket=++generation
  life.begin()
  request=store.playbackSource(attachment){result->
   try{
    if(closed || generation!=ticket){result.getOrNull()?.close();return@playbackSource}
    request=null
    result.fold({source->
     val next=PlayerSession(store.previewWorker(),main,{},{_,_,_->},{ready->
      if(!closed && generation==ticket)ready.fold({session?.resume()},{retireNative()})
     },loop=true)
     session=next;next.open(source);surface?.let{next.attach(it){}}
    },{retireNative()})
   }finally{life.end()}
  }
 }
 /**
  * Обновляет масштаб видеотекстуры после размещения поверхности
  * Сохраняет пропорции с заполнением всей ячейки и обрезкой лишних краёв
  * Не назначает прежнее преобразование повторно, если размеры и пропорции не изменились
  */
 override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){
  super.onLayout(changed,l,t,r,b)
  if(width>0 && height>0 && (transformedWidth!=width || transformedHeight!=height || transformedAspect!=aspect)){
   transformedWidth=width;transformedHeight=height;transformedAspect=aspect
   val viewportAspect=width.toFloat()/height
   texture.setTransform(Matrix().apply{setScale(maxOf(1f,aspect/viewportAspect),maxOf(1f,viewportAspect/aspect),width/2f,height/2f)})
  }
 }
 /**
  * Различает круг, обычное скругление и уменьшенное скругление нижних углов под подпись
  * Возвращает геометрию для обрезки самой поверхности и её подложки
  */
 private fun shape(w:Float,h:Float):Path{
  val r=cornerRadius;val b=if(bottomSquare)minOf(r,4*resources.displayMetrics.density)else r
  return Path().apply{if(circular)addOval(RectF(0f,0f,w,h),Path.Direction.CW)else addRoundRect(RectF(0f,0f,w,h),floatArrayOf(r,r,r,r,b,b,b,b),Path.Direction.CW)}
 }
 /**
  * Сначала выводит дочернюю видеоповерхность обычным способом системы
  * Затем добавляет неподвижные подписи текущего сообщения поверх неё
  * Разделение сохраняет самостоятельное обновление кадров видео
  */
 override fun dispatchDraw(canvas:Canvas){
  super.dispatchDraw(canvas)
  drawForeground(canvas)
 }
 /**
  * Рисует верхние подписи только после появления первого кадра видео
  * При аппаратном выводе использует сохранённый узел с актуальным обратным смещением сообщения
  * При обычном выводе переводит координаты к области медиа и рисует подписи из подготовленной разметки
  */
 private fun drawForeground(canvas:Canvas){
  if(hasFrame){val save=canvas.save();canvas.clipRect(0,0,width,height)
   if(canvas.isHardwareAccelerated){if(stampPlacement.hasDisplayList())canvas.drawRenderNode(stampPlacement)}
   else{canvas.translate(-localMedia.left,-localMedia.top);prepared?.drawMediaStamp(canvas)}
   canvas.restoreToCount(save)
  }
 }
 /**
  * Для обычного рисования сохраняет уменьшенную копию и обновляет её только после нового кадра
  * Ограничивает изображение формой поверхности и затем добавляет верхние подписи
  */
 fun drawBackdrop(canvas:Canvas){
  if(!hasFrame || canvas.quickReject(0f,0f,width.toFloat(),height.toFloat(),Canvas.EdgeType.AA))return
  if(canvas.isHardwareAccelerated){draw(canvas);return}
  val save=canvas.save();canvas.clipPath(shape(width.toFloat(),height.toFloat()))
  val scale=minOf(1f,128f/maxOf(1,width,height));val w=maxOf(1,(width*scale).toInt());val h=maxOf(1,(height*scale).toInt())
  if(backdrop?.width!=w || backdrop?.height!=h){backdrop?.recycle();backdrop=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);copiedVersion=-1}
  if(copiedVersion!=frameVersion){texture.getBitmap(backdrop!!)?.let{copiedVersion=frameVersion}}
  if(copiedVersion>=0)canvas.drawBitmap(backdrop!!,null,RectF(0f,0f,width.toFloat(),height.toFloat()),Paint(Paint.FILTER_BITMAP_FLAG))
  canvas.restoreToCount(save)
  drawForeground(canvas)
 }
 /**
  * Освобождает системную поверхность и удержанные текстуры вывода
  * Отмечает завершение освобождения, чтобы позднее уведомление уничтожения не удержало их снова
  * Вызывается после подтверждённого отсоединения системного проигрывателя
  */
 private fun releaseOutputs(){outputsRetired=true;surface?.release();surface=null;heldTextures.forEach{it.release()};heldTextures.clear()}
 /**
  * Отменяет подготовку и отсоединяет системное воспроизведение от видимой области
  * Снимает активность показа и освобождает источник через принадлежащих ему владельцев
  * Занятое место возвращается после завершения предусмотренного освобождения, включая ещё незаконченные работы
  */
 private fun retireNative(){
  if(retiring)return;retiring=true;hasFrame=false;texture.alpha=0f;invalidate();request?.cancel();request=null
  val old=session;session=null;val life=lifetime;lifetime=null
  // Рабочий поток сначала снимает поверхность и освобождает проигрыватель, только его подтверждение возвращает место
  if(old!=null){life?.begin();old.release{releaseOutputs();life?.end();life?.retire();changed()}}
  else{releaseOutputs();life?.retire();changed()}
 }
 /**
  * Отсоединяет его от сцены и снимает наблюдение за действительностью медиа
  * Освобождение системного воспроизведения передаёт общему пути завершения владельца
  */
 override fun close(){
  if(closed)return;closed=true;generation++;retireNative();(parent as? ViewGroup)?.removeView(this)
  backdrop?.recycle();backdrop=null;stamp=null;stampPlacement.discardDisplayList();prepared=null;store.unregisterInvalidation(invalidation)
 }
 /**
  * Передаёт её действующему проигрывателю и освобождает прежнюю поверхность после подтверждения подключения
  * Если владелец уже закрывается, новую поверхность не создаёт
  */
 override fun onSurfaceTextureAvailable(value:SurfaceTexture,w:Int,h:Int){
  if(closed || retiring)return
  val old=surface;val next=Surface(value);surface=next
  val native=session;if(native!=null)native.attach(next){old?.release()}else old?.release()
 }
 /**
  * Отдельное действие не требуется, поскольку преобразование обновляется при размещении самого элемента
  * Повторное подключение источника здесь не запускается
  */
 override fun onSurfaceTextureSizeChanged(value:SurfaceTexture,w:Int,h:Int){}
 /**
  * Принимает ответственность за освобождение уничтожаемой текстуры и не отдаёт его системе автоматически
  * Если системные выходы уже освобождены, сразу закрывает текстуру
  * Иначе удерживает её до подтверждённого отсоединения проигрывателя и запускает закрытие владельца
  */
 override fun onSurfaceTextureDestroyed(value:SurfaceTexture):Boolean{
  if(outputsRetired){value.release();return false}
  if(heldTextures.none{it===value})heldTextures+=value
  if(!retiring)close()
  return false
 }
 /**
  * Принимает уведомление только от действующей видеотекстуры незакрытого владельца
  * После первого кадра проявляет поверхность, затем обновляет версию подложки и сообщает сцене об изменении
  * Счётчик этих уведомлений не является измерением действительно показанных экраном кадров
  */
 override fun onSurfaceTextureUpdated(value:SurfaceTexture){
  if(closed || retiring || texture.surfaceTexture!==value)return
  if(!hasFrame){hasFrame=true;texture.alpha=1f;invalidate()};frameVersion++;frameCallbacks++;changed()
 }
}
