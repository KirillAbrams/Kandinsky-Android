package dev.kirill.kandinsky.chat.media
import android.graphics.*
import android.os.Handler
import android.os.Looper
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.media.kernel.*
/** Кадр обмена удерживается для текущего системного рисунка видимого изображения */
class MediaImageSlot(private val store:MediaStore,private val invalidate:()->Unit):AutoCloseable {
 val native=RenderNode("Kandinsky image")
 private val main=Handler(Looper.getMainLooper())
 private var request:Cancellation?=null;private var tinyRequest:Cancellation?=null;private var videoPreparation:Cancellation?=null
 private var attachment:Attachment?=null;private var pixelLimit=1024;private var loadAttempt=0
 var loading=false;private set
 private var failed=false;private var progress=0.0
 val loadFailed get()=failed
 var showsControls=true;private set
 private var showLoadingControl=true
 private var deferred=false
 private var studioPhotoDelivery=false
 private var studioPhotoMaxPixel:Int?=null
 val awaitingStudioPhoto get()=studioPhotoDelivery && !ready && !failed
 private val controlPaint=Paint(Paint.ANTI_ALIAS_FLAG);private val density=android.content.res.Resources.getSystem().displayMetrics.density
 private var identity="";private var mediaPath="";private var binding:Long?=null
 private var frame=RectF()
 private var circular=false
 private var cornerRadius=12f;private var bottomSquare=false;private var kind:MessageKind?=null
 private var displayed:PreparedImage?=null
 private var closed=false
 var ready=false;private set
 private val mailbox=MediaSurfaceMailbox<PreparedImage>(schedule={main.post(it)},render={publish(it)})
 private val invalidation=store.registerInvalidation{clear()}
 /**
  * Связывает вложение с областью сообщения и параметрами его показа
  * Сохраняет совместимый готовый рисунок, а при наличии подходящих пикселей в памяти применяет их сразу
  * При отсутствии готового изображения запускает маленькую копию и основной рисунок с отдельным порядком готовности
  * По номеру попытки и привязки отклоняет поздний результат, а подготовку заранее может ограничить уже местными файлами
  */
 fun bind(attachment:Attachment,rect:RectF,circular:Boolean=false,maxPixel:Int=1024,cornerRadius:Float=12f,bottomSquare:Boolean=false,kind:MessageKind?=null,showControls:Boolean=true,studioPhotoDelivery:Boolean=false,studioPhotoMaxPixel:Int?=null,showLoadingControl:Boolean=true,localOnly:Boolean=false){
  if(closed)return
  this.studioPhotoMaxPixel=studioPhotoMaxPixel
  val thumbnail=store.studioPhotoKey(attachment,studioPhotoMaxPixel) ?: ThumbnailKey(maxPixel)
  val changed=this.studioPhotoDelivery!=studioPhotoDelivery || showsControls!=showControls || this.showLoadingControl!=showLoadingControl || frame.width()!=rect.width() || frame.height()!=rect.height() || this.cornerRadius!=cornerRadius || this.bottomSquare!=bottomSquare || this.kind!=kind;frame=RectF(rect);this.circular=circular;showsControls=showControls;this.showLoadingControl=showLoadingControl;this.studioPhotoDelivery=studioPhotoDelivery;this.cornerRadius=cornerRadius;this.bottomSquare=bottomSquare;this.kind=kind;this.attachment=attachment;pixelLimit=thumbnail.maxPixel
  native.setPosition(0,0,kotlin.math.ceil(rect.width().toDouble()).toInt().coerceAtLeast(1),kotlin.math.ceil(rect.height().toDouble()).toInt().coerceAtLeast(1))
  native.translationX=rect.left;native.translationY=rect.top
  val key=attachment.relativePath+"@"+thumbnail.maxPixel
  if(identity==key && (!deferred || localOnly)){if(changed)record();return}
  val preserve=mediaPath==attachment.relativePath && displayed!=null
  loadAttempt++;val attempt=loadAttempt;request?.cancel();tinyRequest?.cancel();videoPreparation?.cancel();videoPreparation=null;identity=key;loading=true;failed=false;deferred=false;progress=0.0;mediaPath=attachment.relativePath;if(!preserve)ready=false
  val token=mailbox.begin(preserve) ?: return;binding=token
  val cached=store.cachedThumbnail(attachment,thumbnail)
  if(cached!=null){
   request=null;tinyRequest=null;loading=false
   mailbox.offer(token,3,cached);mailbox.flush()
   return
  }
  if(!preserve && !studioPhotoDelivery && attachment.tinyPreview!=null)tinyRequest=store.tinyPreview(attachment){result->result.onSuccess{mailbox.offer(token,2,it)}}
  record()
  request=store.thumbnail(attachment,thumbnail,0,localOnly=localOnly,progress={value->if(loadAttempt==attempt && !closed){progress=value;record();invalidate()}}){_,result->
   if(loadAttempt!=attempt || closed){result.getOrNull()?.close();return@thumbnail}
   request=null;loading=false;failed=result.isFailure && !localOnly;deferred=result.isFailure && localOnly
   result.onSuccess{mailbox.offer(token,3,it)};record();invalidate()
  }
 }
 /**
  * Снимает прежнюю системную ссылку отображения перед установкой переданного готового кадра
  * Применяет изображение к текущей области с сохранением пропорций и отмечает готовность после допустимого вывода
  * После изменения уведомляет сцену, а освобождением прежнего удержания управляет обмен кадрами
  */
 private fun publish(value:PreparedImage?){native.discardDisplayList();displayed=null;ready=false;displayed=value;record();invalidate()}
 /**
  * Учитывает круг, радиус верхних углов и уменьшенное скругление снизу под подпись
  * Один контур используется аппаратным и обычным способом рисования
  */
 private fun clipPath():Path{
  val r=if(circular)minOf(frame.width(),frame.height())/2 else cornerRadius;val b=if(bottomSquare)minOf(r,4*density) else r
  return Path().apply{addRoundRect(RectF(0f,0f,frame.width(),frame.height()),floatArrayOf(r,r,r,r,b,b,b,b),Path.Direction.CW)}
 }
 /**
  * Перезаписывает системные команды текущего изображения в ограниченной области ячейки
  * Применяет нужную подложку, контур и преобразование готовых пикселей
  * Отмечает готовность после допустимого рисунка и добавляет только разрешённые действия состояния
  */
 private fun record(){
  native.discardDisplayList()
  val c=native.beginRecording(kotlin.math.ceil(frame.width().toDouble()).toInt().coerceAtLeast(1),kotlin.math.ceil(frame.height().toDouble()).toInt().coerceAtLeast(1))
  try{
   c.clipPath(clipPath());if(kind!=MessageKind.STICKER && (studioPhotoDelivery || attachment?.tinyPreview==null))c.drawColor(Color.rgb(214,230,219))
   displayed?.bitmap?.let{b->PhotoPresentation.make(b.width,b.height,1,frame.width().toDouble(),frame.height().toDouble(),PhotoSurfaceStyle.FILL)?.let{p->
    val matrix=Matrix().apply{setValues(floatArrayOf(p.a.toFloat(),p.c.toFloat(),p.tx.toFloat(),p.b.toFloat(),p.d.toFloat(),p.ty.toFloat(),0f,0f,1f))}
    c.drawBitmap(b,matrix,Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG));ready=true
   }}
   drawControl(c)
  }finally{native.endRecording()}
 }
 /**
  * Рисует текущий кадр обычными командами без сохранённого аппаратного узла
  * Пропускает закрытую или невидимую область, применяет обрезку и сохранение пропорций
  * После изображения добавляет предусмотренные действия состояния
  */
 fun drawSoftware(c:Canvas){
  if(closed || c.quickReject(frame,Canvas.EdgeType.AA))return
  val save=c.save();c.translate(frame.left,frame.top)
  c.clipPath(clipPath());if(kind!=MessageKind.STICKER && (studioPhotoDelivery || attachment?.tinyPreview==null))c.drawColor(Color.rgb(214,230,219));displayed?.bitmap?.let{b->
   PhotoPresentation.make(b.width,b.height,1,frame.width().toDouble(),frame.height().toDouble(),PhotoSurfaceStyle.FILL)?.let{p->
    c.drawBitmap(b,Matrix().apply{setValues(floatArrayOf(p.a.toFloat(),p.c.toFloat(),p.tx.toFloat(),p.b.toFloat(),p.d.toFloat(),p.ty.toFloat(),0f,0f,1f))},Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
   }
  };drawControl(c);c.restoreToCount(save)
 }
 /**
  * Выводит сохранённый системный узел изображения в его текущем положении
  * Если система потеряла команды рисования, сначала восстанавливает их из текущего состояния
  * Повторная загрузка файла для этого не требуется
  */
 fun draw(c:Canvas){if(!native.hasDisplayList())record();c.drawRenderNode(native)}
 private val hasTransferControl get()=loading && !studioPhotoDelivery && (showLoadingControl || progress>0)
 /**
  * Возвращает область и подпись действия только при разрешённых кнопках и подходящем состоянии
  * Для текущего переноса выбирает отмену, для ошибки предлагает повтор
  * Обычная готовая фотография без ошибки не получает действие загрузки
  */
 fun loadAction():Pair<RectF,String>?=if(!showsControls || !failed && !hasTransferControl)null else RectF(frame.centerX()-24*density,frame.centerY()-24*density,frame.centerX()+24*density,frame.centerY()+24*density) to if(loading)"Отменить загрузку" else "Повторить загрузку"
 /**
  * Проверяет попадание в разрешённую область отмены или повтора загрузки
  * При отмене делает текущую попытку устаревшей и останавливает её запрос
  * При повторе заново связывает то же вложение с сохранёнными параметрами области
  */
 fun performLoadAction(x:Float,y:Float):Boolean{
  val action=loadAction() ?: return false;if(closed || !action.first.contains(x,y))return false
  if(loading){loadAttempt++;request?.cancel();request=null;loading=false;failed=true;record();invalidate()}else attachment?.let{identity="";bind(it,frame,circular,pixelLimit,cornerRadius,bottomSquare,kind,showsControls,studioPhotoDelivery,studioPhotoMaxPixel,showLoadingControl)}
  return true
 }
 /**
  * Различает готовое видео, перенос и ошибку, используя текущую долю загрузки
  * При отключённых кнопках или неподходящем состоянии ничего не добавляет
  */
 private fun drawControl(c:Canvas){
  if(!showsControls || loading && !hasTransferControl)return
  if(!loading && !failed){
   if(kind==MessageKind.VIDEO){val x=frame.width()/2;val y=frame.height()/2;val d=density;val p=controlPaint;p.style=Paint.Style.FILL;p.color=0x5C000000;c.drawCircle(x,y,24*d,p);p.color=Color.WHITE;c.drawPath(Path().apply{moveTo(x-4*d,y-9*d);lineTo(x-4*d,y+9*d);lineTo(x+11*d,y);close()},p)}
   return
  }
  val x=frame.width()/2;val y=frame.height()/2;val d=density;val p=controlPaint;p.style=Paint.Style.FILL;p.color=0x6B000000;c.drawCircle(x,y,24*d,p)
  p.style=Paint.Style.STROKE;p.color=Color.WHITE;p.strokeWidth=2*d;p.strokeCap=Paint.Cap.ROUND;c.drawArc(RectF(x-20*d,y-20*d,x+20*d,y+20*d),-90f,((if(loading)if(progress>0)progress else 0.72 else 0.82)*360).toFloat(),false,p)
  if(failed){c.drawLine(x-2*d,y-11*d,x+4*d,y-7*d,p);c.drawLine(x+4*d,y-7*d,x-2*d,y-3*d,p)}else{c.drawLine(x-5*d,y-5*d,x+5*d,y+5*d,p);c.drawLine(x+5*d,y-5*d,x-5*d,y+5*d,p)}
 }
 /**
  * Заранее запрашивает оригинал подходящего видео после готовности его изображения
  * Не создаёт вторую подготовку и не начинает её во время загрузки или ошибки кадра
  * Отмену сохраняет в этой ячейке до смены содержимого или закрытия
  */
 fun prepareOriginalVideo(attachment:Attachment){
  if(ready && !loading && !failed && videoPreparation==null)videoPreparation=store.prepareStudioVideo(attachment)
 }
 /**
  * Возвращает дополнительное удержание уже показанного изображения
  * Позволяет открытию просмотра использовать тот же кадр без ожидания новой загрузки
  * При отсутствии показанного кадра не создаёт замену и не запускает чтение
  */
 fun preview():PreparedImage?=displayed?.retained()
 /**
  * Принимает готовое изображение, переданное из другого представления
  * Более чёткий кадр получает приоритет над поздней маленькой копией текущей привязки
  * Если привязки ещё нет, закрывает переданное удержание, не оставляя его без владельца
  */
 fun seed(image:PreparedImage){
  val token=binding;if(token==null){image.close();return}
  val bitmap=image.bitmap;val serial=if(bitmap!=null && maxOf(bitmap.width,bitmap.height)>=pixelLimit)4L else 2L
  mailbox.offer(token,serial,image);mailbox.flush()
 }
 /**
  * Делает текущую попытку подготовки устаревшей и отменяет связанные запросы
  * Сбрасывает признаки загрузки, ошибки и совпадения привязки, сохраняя сам показанный кадр
  * Новая подготовка начнётся при следующем связывании вложения
  */
 fun refresh(){loadAttempt++;tinyRequest?.cancel();request?.cancel();videoPreparation?.cancel();videoPreparation=null;request=null;identity="";loading=false;failed=false}
 /**
  * Отменяет текущую подготовку и запрашивает снятие показанного содержимого
  * Сразу применяет очистку в потоке экрана, чтобы прежняя системная ссылка не оставалась до уведомления
  * После этого ячейка может принять новую привязку
  */
 fun clear(){refresh();mailbox.clear();mailbox.flush()}
 /**
  * Окончательно закрывает ячейку и делает незавершённую подготовку недействительной
  * Снимает содержимое и собственные системные команды рисования через общий обмен кадрами
  * Удаляет наблюдение хранилища, чтобы сброс медиа больше не обращался к закрытому владельцу
  */
 override fun close(){if(closed)return;closed=true;refresh();mailbox.close();mailbox.flush();native.discardDisplayList();displayed=null;store.unregisterInvalidation(invalidation)}
}
