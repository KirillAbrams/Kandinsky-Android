package dev.kirill.kandinsky.chat.results

import dev.kirill.kandinsky.chat.media.prepareOpaqueMedia

import dev.kirill.kandinsky.chat.media.MediaImageSlot
import dev.kirill.kandinsky.chat.media.MediaNavigationDrawable
import dev.kirill.kandinsky.chat.media.MediaProgressControl
import dev.kirill.kandinsky.chat.media.MediaStore
import dev.kirill.kandinsky.chat.media.PreparedImage
import dev.kirill.kandinsky.chat.media.ThumbnailKey

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import dev.kirill.kandinsky.chat.domain.Attachment
import dev.kirill.kandinsky.chat.domain.Cancellation
import kotlin.math.abs
import kotlin.math.max

class PhotoViewerController(private val activity:Activity,private val attachment:Attachment,private val media:MediaStore,private var preview:PreparedImage?=null,private val caption:String="",private val embedded:Boolean=false){
 var onClose:(()->Unit)?=null
 var onRequestDismiss:(()->Unit)?=null;var onToggleControls:(()->Unit)?=null;var onPreviewReady:(()->Unit)?=null
 private val sharedStudioPhoto=media.studioPhotoKey(attachment)!=null
 private var active= !embedded;private var closed=false;private var pageRoot:FrameLayout?=null
 var downloadPausedByUser=false;private set
 val pageIsZoomed get()=image?.isZoomed==true
 private val main=Handler(Looper.getMainLooper())
 private var dialog:Dialog?=null
 private var download:Cancellation?=null
 private var generation=0
 private var hasOriginal=false
 private var standbyPreview:PreparedImage?=null
 private var image:PhotoContent?=null
 private var progress:MediaProgressControl?=null
 private var retry:ImageButton?=null
 private var back:ImageButton?=null
 private var captionView:TextView?=null;private var captionShade:View?=null
 /**
  * Для встроенной страницы сразу запускает очистку её ресурсов
  * Для самостоятельного экрана закрывает окно, чей обработчик выполнит очистку и уведомление
  */
 fun close(){if(embedded)cleanup()else dialog?.dismiss()}
 /**
  * Назначает элементу устойчивое имя через дополнение системного описания
  * Имя связывает действие страницы с чтением экрана и проверками
  */
 private fun identity(view:View,id:String){
  view.accessibilityDelegate=object:View.AccessibilityDelegate(){
   /**
    * Дополняет обычное системное описание элемента заданным именем
    * Остальные доступные свойства сохраняет из родительской обработки
    */
   override fun onInitializeAccessibilityNodeInfo(host:View,info:AccessibilityNodeInfo){super.onInitializeAccessibilityNodeInfo(host,info);info.viewIdResourceName=id}}
 }
 /**
  * Открывает самостоятельную страницу фотографии только один раз и до её окончательного закрытия
  * Создаёт окно и область просмотра, связывает закрытие с очисткой и уведомлением владельца
  * После показа запрашивает оригинал обычного вложения
  */
 fun show(){
  if(dialog!=null || embedded || closed)return
  val d=Dialog(activity,android.R.style.Theme_Material_NoActionBar_Fullscreen);dialog=d
  val root=createRoot()
  d.setOnDismissListener{dialog=null;cleanup();val callback=onClose;onClose=null;callback?.invoke()}
  d.window?.prepareOpaqueMedia();d.setContentView(root);d.show();d.window?.setLayout(-1,-1);root.requestApplyInsets();if(!sharedStudioPhoto)loadOriginal()
 }
 // Создаёт её при первом обращении и отклоняет вызов для самостоятельной или закрытой страницы
 fun pageView():View{check(embedded && !closed);return pageRoot ?: createRoot()}
 // Возвращает пустой результат, если область изображения ещё не создана
 fun retainedPreview():PreparedImage?=image?.retainedPreview()
 /**
  * Для незакрытой встроенной страницы подготавливает представление при необходимости
  * Отменяет загрузку с сохранением пользовательской паузы и действия повтора
  */
 fun pauseOriginal(){if(!closed){pageView();cancelDownload(true)}}
 /**
  * Согласует готовность, загрузку и анимацию при смене активной страницы
  * Готовые фото генерации используют общий источник и отдельно обновляют доступность повтора
  * При уходе обычной страницы останавливает запрос и анимацию, оставляя меньшую подложку
  */
 fun setPageActive(value:Boolean){
  if(closed || active==value)return;active=value
  if(sharedStudioPhoto){retry?.visibility=if(active && image?.needsRetry==true)View.VISIBLE else View.GONE;return}
  if(value){if(downloadPausedByUser)retry?.visibility=View.VISIBLE else if(hasOriginal){image?.reload()}else loadOriginal()}
  else{cancelDownload(false);image?.demote()}
 }
 /**
  * Создаёт чёрную область изображения с загрузкой, повтором и кнопкой возврата
  * Добавляет текстовую подпись, если она передана, и согласует управление с системными отступами
  * Сохраняет созданные элементы для последующих обновлений и очистки
  */
 private fun createRoot():FrameLayout{
  val density=activity.resources.displayMetrics.density
  val root=FrameLayout(activity);pageRoot=root;root.setBackgroundColor(Color.BLACK)
  val content=PhotoContent();image=content;root.addView(content,FrameLayout.LayoutParams(-1,-1))
  val loading=MediaProgressControl(activity);progress=loading;identity(loading,"media.cancel");loading.setOnClickListener{cancelDownload(true)}
  val again=ImageButton(activity).apply{setImageResource(android.R.drawable.stat_sys_download);setColorFilter(Color.WHITE);setBackgroundColor(Color.TRANSPARENT);visibility=View.GONE;contentDescription="Загрузить оригинал";setOnClickListener{loadOriginal()}}
  retry=again;identity(again,"media.retry")
  for(control in listOf(loading,again))root.addView(control,FrameLayout.LayoutParams((48*density).toInt(),(48*density).toInt(),Gravity.CENTER))
  if(caption.isNotEmpty()){
   val shade=View(activity).apply{background=android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.TRANSPARENT,0xBF000000.toInt()))};captionShade=shade
   root.addView(shade,FrameLayout.LayoutParams(-1,(212*density).toInt(),Gravity.BOTTOM).apply{bottomMargin=(12*density).toInt()})
   val text=TextView(activity).apply{this.text=caption;textSize=16f;setTextColor(Color.WHITE);setPadding(0,0,0,0);maxHeight=(180*density).toInt();movementMethod=android.text.method.ScrollingMovementMethod();isVerticalScrollBarEnabled=false;setTextIsSelectable(true)};captionView=text;identity(text,"viewer.caption")
   root.addView(text,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply{leftMargin=(16*density).toInt();rightMargin=(16*density).toInt();bottomMargin=(24*density).toInt()})
  }
  val close=ImageButton(activity).apply{setImageDrawable(MediaNavigationDrawable(back=true));setColorFilter(Color.WHITE);setBackgroundColor(Color.TRANSPARENT);contentDescription="Закрыть фотографию";visibility=if(embedded)View.GONE else View.VISIBLE;setOnClickListener{this@PhotoViewerController.close()}}
  back=close;identity(close,"photo.close")
  root.addView(close,FrameLayout.LayoutParams((44*density).toInt(),(44*density).toInt(),Gravity.TOP or Gravity.START).apply{leftMargin=(12*density).toInt();topMargin=(8*density).toInt()})
  root.setOnApplyWindowInsetsListener{_,insets->
   @Suppress("DEPRECATION") val top=insets.systemWindowInsetTop
   @Suppress("DEPRECATION") val bottom=insets.systemWindowInsetBottom
   (close.layoutParams as FrameLayout.LayoutParams).apply{topMargin=top+(8*density).toInt()}.also{close.layoutParams=it}
   captionView?.let{text->(text.layoutParams as FrameLayout.LayoutParams).apply{bottomMargin=bottom+(24*density).toInt()}.also{text.layoutParams=it}}
   insets
  }
  return root
 }
 /**
  * Запускает получение оригинала для активной открытой страницы с новым номером попытки
  * Для фото генерации обновляет общий источник изображения вместо отдельного запроса файла
  * Применяет прогресс и итог только при совпадении попытки и текущего корня страницы
  * После успеха обновляет изображение, а при ошибке возвращает доступный повтор
  */
 private fun loadOriginal(){
  val root=pageRoot ?: return;if(!active || closed)return;downloadPausedByUser=false
  if(sharedStudioPhoto){retry?.visibility=View.GONE;image?.retryStudioPhoto();return}
  hasOriginal=false;generation++;val ticket=generation;download?.cancel()
  retry?.visibility=View.GONE;progress?.update(null,!media.hasLocalOriginal(attachment))
  download=media.ensureOriginal(attachment,progress={value->main.post{if(ticket==generation && pageRoot===root && !closed)progress?.update(value.takeIf{it>0 && it<1},true)}}){result->
   if(ticket!=generation || pageRoot!==root || closed)return@ensureOriginal
   download=null
   result.fold({hasOriginal=true;image?.reload()},{progress?.update(null,false);retry?.visibility=View.VISIBLE;retry?.contentDescription="Не удалось загрузить. Повторить"})
  }
 }
 /**
  * Отменяет текущую загрузку и делает её поздние ответы устаревшими
  * При явной паузе снимает готовность оригинала и приостанавливает анимацию
  * Убирает загрузку и по запросу оставляет кнопку повтора
  */
 private fun cancelDownload(showRetry:Boolean){
  generation++;download?.cancel();download=null;if(showRetry){downloadPausedByUser=true;hasOriginal=false};progress?.update(null,false);retry?.visibility=if(showRetry)View.VISIBLE else View.GONE
 }
 /**
  * Один раз снимает активность страницы, отменяет загрузку и закрывает изображение
  * Возвращает удержанные подложки и очищает ссылки на элементы страницы
  * Самостоятельный экран дополнительно просит хранилище освободить неиспользуемые изображения
  */
 private fun cleanup(){
  if(closed)return;closed=true;active=false;cancelDownload(false);image?.close();preview?.close();preview=null;standbyPreview?.close();standbyPreview=null;image=null;progress=null;retry=null;back=null;captionView=null;captionShade=null;pageRoot=null
  if(!embedded)media.releaseIdleImages()
 }
 private inner class PhotoContent:View(activity){
  private val slot:MediaImageSlot=MediaImageSlot(media){
   if(closed)return@MediaImageSlot
   if(sharedStudioPhoto){
    retry?.visibility=if(active && slotFailed())View.VISIBLE else View.GONE
    onPreviewReady?.invoke();invalidate();return@MediaImageSlot
   }
   retainStandbyPreview()
   onPreviewReady?.invoke()
   if(active && hasOriginal && !originalBound && (standbyPreview!=null || slotFailed()))main.post{bind()}
   if(originalBound && !slotLoading()){
    progress?.update(null,false);retry?.visibility=if(slotFailed())View.VISIBLE else View.GONE
   };invalidate()
  }
  // Уведомление передаётся отложенно, когда владелец изображения уже установлен
  /**
   * Возвращает текущий признак подготовки изображения у его владельца
   * Используется после отложенного уведомления, когда владелец уже установлен
   */
  private fun slotLoading():Boolean=slot.loading
  /**
   * Возвращает сохранённый признак ошибки подготовки изображения
   * По нему страница выбирает действие повтора и возможность перехода к оригиналу
   */
  private fun slotFailed():Boolean=slot.loadFailed
  private val fitted=RectF()
  private var scale=1f;private var panX=0f;private var panY=0f
  val isZoomed get()=scale>1.01f
  /**
   * Возвращает отдельное удержание готовой подложки из владельца изображения
   * Получатель сможет сохранить кадр на время перехода без нового декодирования
   */
  fun retainedPreview():PreparedImage?=slot.preview()
  val needsRetry get()=slot.loadFailed
  /**
   * Сбрасывает прежнюю попытку общего фото генерации и повторно связывает изображение
   * Подготовка затем идёт через обычного владельца рисунка страницы
   */
  fun retryStudioPhoto(){slot.refresh();bind()}
  private var dismissY=0f
  private var animator:ValueAnimator?=null
  private var closed=false
  private var originalBound=false
  private val zoom=ScaleGestureDetector(activity,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){
   /**
    * Останавливает прежнюю анимацию масштаба и оставляет жест текущему изображению
    * Для страницы видеовложения отклоняет увеличение
    */
   override fun onScaleBegin(detector:ScaleGestureDetector):Boolean{animator?.cancel();parent?.requestDisallowInterceptTouchEvent(true);return !attachment.mime.startsWith("video/")}
   /**
    * Умножает текущий масштаб на изменение жеста двух пальцев
    * Передаёт фокус жеста общему расчёту смещения изображения
    */
   override fun onScale(detector:ScaleGestureDetector):Boolean{setScale(scale*detector.scaleFactor,detector.focusX,detector.focusY);return true}
  })
  private val gestures=GestureDetector(activity,object:GestureDetector.SimpleOnGestureListener(){
   // При увеличенном масштабе запрещает родителю перехватывать жест перемещения
   override fun onDown(e:MotionEvent):Boolean{parent?.requestDisallowInterceptTouchEvent(isZoomed);return true}
   /**
    * На встроенной странице передаёт переключение управления её владельцу
    * На самостоятельной меняет видимость возврата, подписи и затемнения
    */
   override fun onSingleTapConfirmed(e:MotionEvent):Boolean{if(embedded){onToggleControls?.invoke();return true};back?.let{val visibility=if(it.visibility==View.VISIBLE)View.GONE else View.VISIBLE;it.visibility=visibility;captionView?.visibility=visibility;captionShade?.visibility=visibility};return true}
   /**
    * Отклоняет увеличение для видеовложения
    * Для фотографии плавно возвращает исходный масштаб либо приближает точку нажатия
    */
   override fun onDoubleTap(e:MotionEvent):Boolean{
    if(attachment.mime.startsWith("video/"))return false
    val start=scale;val target=if(scale>1.05f)1f else 3f
    animator=ValueAnimator.ofFloat(start,target).apply{duration=180;addUpdateListener{setScale(it.animatedValue as Float,e.x,e.y)};start()};return true
   }
   /**
    * Во время увеличения перемещает изображение и ограничивает его смещение допустимой областью
    * При исходном масштабе использует преимущественно вертикальное движение для закрытия
    * Не вмешивается в выполняющийся жест двух пальцев
    */
   override fun onScroll(first:MotionEvent?,current:MotionEvent,dx:Float,dy:Float):Boolean{
    if(zoom.isInProgress)return true
    if(scale>1.01f){panX-=dx;panY-=dy;clampPan()}else if(first!=null && abs(current.y-first.y)>abs(current.x-first.x)){dismissY=current.y-first.y}
    invalidate();return true
   }
  })
  // Повторно связывает изображение с доступным качеством и просит перерисовку
  fun reload(){retainStandbyPreview();bind();invalidate()}
  /**
   * Передаёт сохранённую небольшую подложку следующей привязке изображения
   * Повторно выбирает качество по активности страницы вместо удержания лишнего оригинала
   */
  fun demote(){standbyPreview?.let{preview?.close();preview=it.retained()};bind()}
  /**
   * Ограничивает масштаб допустимыми пределами и сохраняет выбранную точку под пальцами
   * Пересчитывает смещение, снимает жест закрытия и ограничивает края изображения
   * Обновляет доступную подпись масштаба и просит перерисовку
   */
  private fun setScale(value:Float,focusX:Float,focusY:Float){
   val next=value.coerceIn(1f,4f);val ratio=next/scale
   panX=focusX-width/2f-(focusX-width/2f-panX)*ratio;panY=focusY-height/2f-(focusY-height/2f-panY)*ratio
   scale=next;dismissY=0f;clampPan();contentDescription="Фотография, масштаб ${(scale*100).toInt()}%";invalidate()
  }
  /**
   * Вычисляет допустимое смещение увеличенного изображения по каждой оси
   * Удерживает текущие координаты внутри этих пределов
   */
  private fun clampPan(){
   val x=max(0f,(fitted.width()*scale-width)/2);val y=max(0f,(fitted.height()*scale-height)/2)
   panX=panX.coerceIn(-x,x);panY=panY.coerceIn(-y,y)
  }
  /**
   * Вписывает изображение в новую область с учётом его поворота и пропорций
   * Уточняет допустимое смещение и передаёт новые границы владельцу рисунка
   */
  override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){
   val iw=maxOf(1,if(attachment.orientation>=5)attachment.rawHeight else attachment.rawWidth)
   val ih=maxOf(1,if(attachment.orientation>=5)attachment.rawWidth else attachment.rawHeight)
   val ratio=minOf(w.toFloat()/iw,h.toFloat()/ih)
   fitted.set((w-iw*ratio)/2,(h-ih*ratio)/2,(w+iw*ratio)/2,(h+ih*ratio)/2);clampPan();bind()
  }
  /**
   * Получает готовую небольшую подложку из памяти либо текущего изображения
   * Проверяет её размер по режиму страницы и закрывает неподходящее удержание
   * Заменяет прежнюю сохранённую подложку подходящим кадром
   */
  private fun retainStandbyPreview(){
   val limit=if(embedded)384 else 1024
   val candidate=media.cachedThumbnail(attachment,ThumbnailKey(limit)) ?: slot.preview() ?: return
   val bitmap=candidate.bitmap
   if(bitmap==null || maxOf(bitmap.width,bitmap.height)>limit){candidate.close();return}
   standbyPreview?.close();standbyPreview=candidate
  }
  /**
   * Выбирает качество по активности, готовности оригинала и наличию небольшой подложки
   * Передаёт существующему владельцу изображения текущую область без элементов управления
   * Однократно передаёт удержанный кадр и пробует подключить допустимую анимацию
   */
  private fun bind(){if(!closed && !fitted.isEmpty){
   originalBound=active && hasOriginal && (standbyPreview!=null || slotFailed())
   slot.bind(attachment,fitted,maxPixel=if(originalBound)2048 else if(embedded)384 else 1024,cornerRadius=0f,showControls=false)
   preview?.let{preview=null;slot.seed(it)}
  }}
  /**
   * Применяет масштаб, перемещение и временный сдвиг закрытия к изображению
   * Выбирает аппаратный либо обычный рисунок подготовленного кадра и анимации
   * После вывода возвращает прежнее состояние холста
   */
  override fun onDraw(c:Canvas){
   val save=c.save();c.translate(width/2f+panX,height/2f+panY+dismissY);c.scale(scale,scale);c.translate(-width/2f,-height/2f)
   if(c.isHardwareAccelerated){slot.draw(c)}else{slot.drawSoftware(c)};c.restoreToCount(save)
  }
  /**
   * Передаёт касания распознавателям масштаба и обычных жестов
   * При достаточном сдвиге после отпускания запрашивает закрытие страницы
   * Короткий или отменённый сдвиг плавно возвращает в исходное положение
   */
  override fun onTouchEvent(event:MotionEvent):Boolean{
   // При втором нажатии распознаватель сначала сообщает о двойном жесте
   // Прежнюю анимацию нужно снять до передачи нового касания
   if(event.actionMasked==MotionEvent.ACTION_DOWN)animator?.cancel()
   zoom.onTouchEvent(event);gestures.onTouchEvent(event)
   if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL){
    if(event.actionMasked==MotionEvent.ACTION_UP && abs(dismissY)>height*0.16f){if(embedded)onRequestDismiss?.invoke()else this@PhotoViewerController.close()}
    else if(dismissY!=0f){val start=dismissY;animator=ValueAnimator.ofFloat(start,0f).apply{duration=180;addUpdateListener{dismissY=it.animatedValue as Float;invalidate()};start()}}
   };return true
  }
  /**
   * Один раз останавливает анимацию жеста, закрывает анимацию изображения и снимает наблюдение
   * Освобождает владельца подготовленного рисунка этой области
   */
  fun close(){if(closed)return;closed=true;animator?.cancel();animator=null;slot.close()}
  /**
   * Закрывает ресурсы изображения перед системным отсоединением области от окна
   * Затем продолжает стандартное завершение представления
   */
  override fun onDetachedFromWindow(){close();super.onDetachedFromWindow()}
 }
}
