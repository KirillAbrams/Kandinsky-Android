package dev.kirill.kandinsky.chat.rendering
import android.graphics.*
import android.os.Looper
import android.view.ViewGroup
import dev.kirill.kandinsky.chat.media.*
import dev.kirill.kandinsky.chat.domain.*
data class SceneFragment(val prepared:PreparedText,val part:Int,val frame:RectF){val id get()=FragmentID(prepared.key.messageID,part)}
data class SceneSnapshot(val ticket:Int,val fragments:List<SceneFragment>)
data class SceneMetrics(var recordings:Int=0,var liveFragments:Int=0,var activeFrameClocks:Int=0)
class MessageNode(var fragment:SceneFragment) {
 var image:MediaImageSlot?=null
 val extraImages=linkedMapOf<Int,MediaImageSlot>()
 /**
  * Убирает собственные ссылки на освобождённые области показа
  * Используется при уходе содержимого из нужной области или при смене сообщения
  */
 fun retireImages(){image?.close();image=null;retireExtraImages()}
 /**
  * Закрывает только дополнительные изображения альбома и очищает их список
  * Основное изображение с первым номером здесь сохраняется
  * Это позволяет переключиться с альбома на одиночное вложение без лишнего закрытия первой ячейки
  */
 fun retireExtraImages(){extraImages.values.forEach{it.close()};extraImages.clear()}
 var video:SilentVideoPreview?=null
 val albumVideos=linkedMapOf<String,SilentVideoPreview>()
 internal val progressViews=linkedMapOf<String,StudioProgressView>()
 /**
  * Очищает их привязки к отдельным вложениям
  * После вызова ячейка больше не удерживает эти проигрыватели
  */
 fun retireAlbumVideos(){albumVideos.values.forEach{it.close()};albumVideos.clear()}
 /**
  * Очищает сохранённые привязки, чтобы прежние проценты не остались поверх другого содержимого
  * Повторное появление индикатора выполняется при следующем согласовании сцены
  */
 fun retireProgress(){
  progressViews.values.forEach{(it.parent as? ViewGroup)?.removeView(it)};progressViews.clear()
 }
 /**
  * Переносит уже подготовленные изображения между прежним и новым порядком вложений
  * Сопоставляет их по постоянному обозначению вложения, а не по изменившемуся номеру в списке
  * Закрывает только те изображения, которых больше нет в обновлённом сообщении
  */
 fun preserveAlbumImages(previous:Message,current:Message){
  if(previous.attachments.map{it.id}==current.attachments.map{it.id})return
  val slots=mutableMapOf<String,MediaImageSlot>()
  previous.attachments.forEachIndexed{index,attachment->
   (if(index==0)image else extraImages[index])?.let{slots[attachment.id]=it}
  }
  image=null;extraImages.clear()
  current.attachments.forEachIndexed{index,attachment->
   slots.remove(attachment.id)?.let{if(index==0)image=it else extraImages[index]=it}
  }
  slots.values.forEach{it.close()}
 }
 val native=RenderNode("Kandinsky message "+fragment.id)
 val overlay=RenderNode("Kandinsky static media stamp "+fragment.id)
 /**
  * Для первого фрагмента с медиа отдельно записывает подписи поверх вложения
  * Каждую запись завершает даже при ошибке, чтобы узел не остался в состоянии незаконченного рисования
  */
 fun record(){
  val c=native.beginRecording(kotlin.math.ceil(fragment.frame.width().toDouble()).toInt().coerceAtLeast(1),kotlin.math.ceil(fragment.frame.height().toDouble()).toInt().coerceAtLeast(1))
  try{fragment.prepared.draw(fragment.part,c)}finally{native.endRecording()}
  overlay.discardDisplayList();if(fragment.part==0 && fragment.prepared.mediaRect!=null){val o=overlay.beginRecording(kotlin.math.ceil(fragment.frame.width().toDouble()).toInt().coerceAtLeast(1),kotlin.math.ceil(fragment.frame.height().toDouble()).toInt().coerceAtLeast(1));try{fragment.prepared.drawMediaStamp(o)}finally{overlay.endRecording()}}
 }
 /**
  * Обновляет размеры и положение узлов текста и подписей по текущей области фрагмента
  * Использует перемещение готовых узлов вместо повторной записи содержимого
  * Это позволяет прокручивать уже подготовленный текст без его нового измерения
  */
 fun position(){val r=fragment.frame;native.setPosition(0,0,kotlin.math.ceil(r.width().toDouble()).toInt(),kotlin.math.ceil(r.height().toDouble()).toInt());native.translationX=r.left;native.translationY=r.top;overlay.setPosition(0,0,r.width().toInt(),r.height().toInt());overlay.translationX=r.left;overlay.translationY=r.top}
}
class RetainedChatScene {
 private val nodes=linkedMapOf<FragmentID,MessageNode>()
 var ticket=1;private set
 val metrics=SceneMetrics()
 var mediaVisible=true
  set(value){field=value;if(!value){nodes.values.forEach{it.video?.close();it.video=null;it.retireAlbumVideos();it.retireProgress();it.retireImages()};metrics.activeFrameClocks=0;mediaStore?.releaseIdleImages()}}
 var movingMediaVisible=true
  set(value){field=value;if(!value){nodes.values.forEach{it.video?.close();it.video=null;it.retireAlbumVideos();it.retireProgress()};metrics.activeFrameClocks=0}}
 var viewport=RectF()
 var imageViewport:RectF?=null
 var imagePreparationViewport:RectF?=null
 var movingContainer:ViewGroup?=null
 private var previewObserver:java.util.UUID?=null
 var mediaStore:MediaStore?=null
 var onImageReady:(()->Unit)?=null
 var onFrameChanged:(()->Unit)?=null
 private var closed=false
 /**
  * При обращении из другого потока немедленно сообщает об ошибке
  * Самостоятельно работу между потоками не переносит
  */
 private fun main(){check(Looper.myLooper()==Looper.getMainLooper())}
 /**
  * Если сцена закрыта или уже переключилась, сразу закрывает устаревший результат
  * Возвращает разрешение использовать ресурс, не добавляя его в сцену самостоятельно
  */
 fun accept(resource:PreparedText,ticket:Int):Boolean {main();if(closed || this.ticket!=ticket){resource.close();return false};return true}
 /**
  * Для первого вложения использует основную область показа, для остальных ищет область альбома
  * Самостоятельно не запускает чтение файла или сетевую загрузку
  */
 fun preview(id:MessageID,attachmentIndex:Int=0):PreparedImage?=nodes[FragmentID(id,0)]?.let{if(attachmentIndex==0)it.image?.preview() else it.extraImages[attachmentIndex]?.preview()}
 /**
  * Учитывает номер вложения, чтобы различать отдельные результаты альбома
  * Возвращает ложь, если нужного фрагмента или подготовленной области изображения пока нет
  */
 fun imageReady(id:MessageID,attachmentIndex:Int=0)=nodes[FragmentID(id,0)]?.let{if(attachmentIndex==0)it.image?.ready else it.extraImages[attachmentIndex]?.ready}==true
 /**
  * Позволяет проверить, был ли сохранён тот же узел при обновлении содержимого
  * Если фрагмента в сцене нет, возвращает отсутствие значения
  */
 fun nodeIdentity(id:FragmentID):Int?=nodes[id]?.let{System.identityHashCode(it.native)}
 /**
  * Проверяет, подготовлены ли все запрошенные фрагменты для показа
  * При требовании готовых медиа дополнительно проверяет видимые изображения и отсутствие их текущей загрузки
  * Вложения за пределами области показа не задерживают подтверждение готовности видимого содержимого
  */
 fun presentationReady(ids:Set<FragmentID>,requireMedia:Boolean):Boolean=ids.all{id->
  val node=nodes[id] ?: return@all false
  if(!node.native.hasDisplayList())return@all false
  if(!requireMedia || node.fragment.part!=0)return@all true
  node.fragment.prepared.mediaItemRects.withIndex().all{(index,local)->
   val rect=RectF(local).apply{offset(node.fragment.frame.left,node.fragment.frame.top)}
   if(!RectF.intersects(rect,imageViewport ?: viewport))true
   else (if(index==0)node.image else node.extraImages[index])?.let{it.ready && !it.loading}==true
  }
 }
 data class MediaLoadAction(val messageID:MessageID,val attachmentIndex:Int,val rect:RectF,val label:String)
 /**
  * Для каждого действия возвращает сообщение, номер вложения, область кнопки и её подпись
  * У сообщения с активным одиночным видео эти действия не добавляет поверх воспроизведения
  */
 fun loadActions():List<MediaLoadAction> = nodes.values.flatMap{node->if(node.video?.active==true)emptyList()else (node.extraImages.toMap()+listOfNotNull(node.image?.let{0 to it})).toSortedMap().mapNotNull{(index,image)->image.loadAction()?.let{MediaLoadAction(node.fragment.id.messageID,index,it.first,it.second)}}}
 /**
  * Передаёт нажатие областям загрузки изображений текущей сцены
  * Завершает поиск после первого обработанного действия и сообщает, было ли оно найдено
  * Пропускает сообщения с активным одиночным видео, чтобы действие не срабатывало поверх него
  */
 fun performLoadAction(x:Float,y:Float):Boolean{for(node in nodes.values)if(node.video?.active!=true)for(image in listOfNotNull(node.image)+node.extraImages.values)if(image.performLoadAction(x,y))return true;return false}
 /**
  * Увеличивает номер поколения сцены, чтобы результаты прежней подготовки больше не принимались
  * Освобождает рисунки, изображения, видео и индикаторы всех сохранённых фрагментов
  * Очищает список узлов и число живых фрагментов перед следующим содержимым
  */
 fun advanceGeneration(){main();ticket++;nodes.values.forEach{it.video?.close();it.retireAlbumVideos();it.retireProgress();it.retireImages();it.native.discardDisplayList();it.overlay.discardDisplayList()};nodes.clear();metrics.liveFragments=0}
 /**
  * Согласует с подготовленным снимком не больше ста двадцати восьми фрагментов сообщения
  * Сохраняет совместимые узлы и изображения, обновляя неподвижный рисунок только при изменении его содержимого
  * Отбирает ближайшие изображения для подготовки заранее и видимые медиа для автоматического воспроизведения
  * Удаляет ненужные ресурсы, связывает отдельные проценты и обновляет показатели сцены
  */
 fun apply(snapshot:SceneSnapshot){
  main();if(closed || snapshot.ticket!=ticket)return
  if(previewObserver==null)previewObserver=MediaAdmission.observePreviewAvailability{onImageReady?.invoke()}
  val fragments=snapshot.fragments.take(128)
  val imageView=imageViewport
  val preparation=imagePreparationViewport
  val prefetched=if(mediaVisible && imageView!=null && preparation!=null)fragments.mapNotNull{fragment->
   val local=fragment.prepared.mediaRect
   if(fragment.part!=0 || local==null || fragment.prepared.message.attachments.isEmpty())return@mapNotNull null
   val rect=RectF(local).apply{offset(fragment.frame.left,fragment.frame.top)}
   if(RectF.intersects(preparation,rect) && !RectF.intersects(imageView,rect))fragment.id to kotlin.math.abs(rect.centerY()-imageView.centerY()) else null
  }.sortedBy{it.second}.take(3).map{it.first}.toSet()else emptySet()
  val animated=fragments.filter{fragment->
   val local=fragment.prepared.mediaRect
   if(!mediaVisible || !movingMediaVisible || !MediaAdmission.previewsAllowed || viewport.width()<=0 || viewport.height()<=0 || fragment.part!=0 || fragment.prepared.generationCard?.isBatch==true || fragment.prepared.message.attachments.size>1 || local==null || fragment.prepared.message.kind !in listOf(MessageKind.GIF,MessageKind.VIDEO,MessageKind.ROUND))false
   else{val rect=RectF(local).apply{offset(fragment.frame.left,fragment.frame.top)};val visible=RectF(rect);visible.intersect(viewport) && visible.width()>0 && visible.height()>=minOf(rect.height(),viewport.height())*.5f}
  }.sortedBy{val rect=it.prepared.mediaRect!!;kotlin.math.abs(it.frame.top+rect.centerY()-viewport.centerY())}.take(2).map{it.id}.toSet()
  val wanted=fragments.map{it.id}.toSet()
  nodes.keys.filter{it !in wanted}.forEach{nodes.remove(it)?.let{node->node.video?.close();node.retireAlbumVideos();node.retireProgress();node.retireImages();node.native.discardDisplayList();node.overlay.discardDisplayList()}}
  for(fragment in fragments){
   val old=nodes[fragment.id];val node=old ?: MessageNode(fragment).also{nodes[fragment.id]=it}
   val previous=old?.fragment?.prepared
   val card=fragment.prepared.generationCard
   val sameInk=previous?.generationCard?.let{card?.sameStaticInk(it)}==true
   val changed=old==null || (!sameInk && node.fragment.prepared.key!=fragment.prepared.key) || node.fragment.part!=fragment.part
   if(card?.isBatch==true && previous?.generationCard?.isBatch==true)node.preserveAlbumImages(previous.message,fragment.prepared.message)
   node.fragment=fragment;node.position()
   if(changed || !node.native.hasDisplayList()){node.record();metrics.recordings++}
   val rect=fragment.prepared.mediaRect;val media=mediaStore;val a=fragment.prepared.message.attachments.firstOrNull()
   val preparing=fragment.id in prefetched
   val imageIsNear=preparing || imageView==null || rect==null || RectF.intersects(RectF(imageView).apply{inset(0f,-20f*fragment.prepared.key.density)},RectF(rect).apply{offset(fragment.frame.left,fragment.frame.top)})
   if(mediaVisible && imageIsNear && fragment.part==0 && (fragment.prepared.mediaItemRects.size>1 || fragment.prepared.generationCard?.isBatch==true) && media!=null){
    bindAlbum(fragment,node,media,preparing)
   }else if(mediaVisible && imageIsNear && fragment.part==0 && rect!=null && media!=null && a!=null){
    node.retireExtraImages()
    val slot=node.image ?: MediaImageSlot(media){onImageReady?.invoke()}.also{node.image=it}
    val deliveringPhoto=fragment.prepared.message.direction==MessageDirection.INCOMING && media.studioPhotoKey(a)!=null &&
     (previous?.generationCard?.generation?.isActive==true || slot.awaitingStudioPhoto)
    slot.bind(a,RectF(rect).apply{offset(fragment.frame.left,fragment.frame.top)},fragment.prepared.message.kind==MessageKind.ROUND,cornerRadius=fragment.prepared.mediaCornerRadius,bottomSquare=fragment.prepared.mediaBottomSquare,kind=fragment.prepared.message.kind,
     showControls=!media.isStudioVideo(a),studioPhotoDelivery=deliveringPhoto,showLoadingControl=fragment.prepared.message.kind!=MessageKind.PHOTO,localOnly=preparing)
    val container=movingContainer
    if(fragment.id in animated && container!=null && fragment.prepared.message.kind in listOf(MessageKind.VIDEO,MessageKind.ROUND)){
     if(node.video?.closed==true)node.video=null
     val video=node.video ?: SilentVideoPreview(container.context,media){updateClockMetrics();onFrameChanged?.invoke()}.also{node.video=it;container.addView(it)}
     video.bind(a,RectF(rect).apply{offset(fragment.frame.left,fragment.frame.top)},fragment.prepared.message.kind==MessageKind.ROUND,fragment.prepared.mediaCornerRadius,fragment.prepared.mediaBottomSquare,node.overlay,fragment.prepared)
    }else{node.video?.close();node.video=null}
   }else{node.video?.close();node.video=null;node.retireAlbumVideos();node.retireImages()}
   bindProgress(fragment,node)
  };metrics.liveFragments=nodes.size;updateClockMetrics()
 }
 /**
  * Согласует отдельные индикаторы с выполняемыми ячейками и доставкой готовых фотографий
  * Создаёт их только для видимых областей, сохраняя существующий элемент по обозначению результата
  * Удаляет индикаторы, для которых в текущем сообщении больше нет подходящего состояния
  */
 private fun bindProgress(fragment:SceneFragment,node:MessageNode){
  val container=movingContainer
  val card=fragment.prepared.generationCard
  if(!mediaVisible || !movingMediaVisible || fragment.part!=0 || container==null){
   node.retireProgress();return
  }
  val wanted=mutableSetOf<String>()
  /**
   * Находит или создаёт индикатор одного результата, если его область пересекает видимую часть чата
   * Передаёт ему текущий процент, размеры и признак доставки фотографии
   * Запоминает использованное обозначение, чтобы после обхода удалить только устаревшие индикаторы
   */
  fun bind(id:String,local:RectF,progress:Int?,deliveringPhoto:Boolean=false){
   val rect=RectF(local).apply{offset(fragment.frame.left,fragment.frame.top)}
   if(!RectF.intersects(rect,viewport))return
   wanted+=id
   val view=node.progressViews.getOrPut(id){StudioProgressView(container.context).also{container.addView(it)}}
   view.bind(progress,rect,fragment.prepared.key.density,deliveringPhoto)
  }
  card?.runningCells?.forEach{bind(it.slot.id,it.frame,it.slot.progress)}
  fragment.prepared.message.attachments.forEachIndexed{index,attachment->
   val image=if(index==0)node.image else node.extraImages[index]
   val frame=fragment.prepared.mediaItemRects.getOrNull(index)
   if(image?.awaitingStudioPhoto==true && frame!=null){
    val id=card?.cells?.firstOrNull{it.slot.blobId==attachment.thumbnailKey}?.slot?.id ?: "single"
    bind(id,frame,99,deliveringPhoto=true)
   }
  }
  node.progressViews.keys.toList().filter{it !in wanted}.forEach{id->
   node.progressViews.remove(id)?.let{container.removeView(it)}
  }
 }
 /**
  * Связывает каждое доступное вложение альбома с его собственной областью изображения
  * Сохраняет уже подготовленные области, отдельно учитывая доставку фотографий и автоматический показ видимых видео
  * При подготовке заранее ограничивается локальными данными, а удалённые из нужной области ресурсы закрывает
  */
 private fun bindAlbum(fragment:SceneFragment,node:MessageNode,media:MediaStore,preparing:Boolean){
  node.video?.close();node.video=null
  val wanted=mutableSetOf<Int>()
  val playing=mutableSetOf<String>()
  val container=movingContainer
  fragment.prepared.mediaItemRects.forEachIndexed{index,local->
   val rect=RectF(local).apply{offset(fragment.frame.left,fragment.frame.top)}
   if(!preparing && imageViewport?.let{RectF(it).apply{inset(0f,-20f*fragment.prepared.key.density)}.let{near->!RectF.intersects(near,rect)}}==true)return@forEachIndexed
   wanted+=index
   val slot=if(index==0)node.image ?: MediaImageSlot(media){onImageReady?.invoke()}.also{node.image=it}else node.extraImages.getOrPut(index){MediaImageSlot(media){onImageReady?.invoke()}}
   val attachment=fragment.prepared.message.attachments[index];val kind=if(attachment.mime.startsWith("video/"))MessageKind.VIDEO else MessageKind.PHOTO
   val studioVideo=kind==MessageKind.VIDEO && (fragment.prepared.generationCard?.isBatch==true || media.isStudioVideo(attachment))
   val slotID=fragment.prepared.generationCard?.cells?.firstOrNull{it.slot.blobId==attachment.thumbnailKey}?.slot?.id
   val deliveringPhoto=fragment.prepared.message.direction==MessageDirection.INCOMING && media.studioPhotoKey(attachment)!=null &&
    (slot.awaitingStudioPhoto || (slotID!=null && slotID in node.progressViews))
   val cellPixelLimit=512
   slot.bind(attachment,rect,maxPixel=cellPixelLimit,cornerRadius=0f,kind=kind,showControls=!studioVideo,
    studioPhotoDelivery=deliveringPhoto,studioPhotoMaxPixel=cellPixelLimit,showLoadingControl=kind!=MessageKind.PHOTO,localOnly=preparing)
   if(kind==MessageKind.VIDEO){
    val visible=RectF(rect)
    if(studioVideo && container!=null && movingMediaVisible &&
     MediaAdmission.previewsAllowed && visible.intersect(viewport) && visible.height()>=minOf(rect.height(),viewport.height())*.5f){
     playing+=attachment.id
     if(node.albumVideos[attachment.id]?.closed==true)node.albumVideos.remove(attachment.id)
     val video=node.albumVideos.getOrPut(attachment.id){
      SilentVideoPreview(container.context,media){updateClockMetrics();onFrameChanged?.invoke()}.also{container.addView(it)}
     }
     video.bind(attachment,rect,false,12*fragment.prepared.key.density,false,node.overlay,fragment.prepared,studioAlbum=true)
    }else if(!preparing)slot.prepareOriginalVideo(attachment)
   }
  }
  node.albumVideos.keys.toList().filter{it !in playing}.forEach{node.albumVideos.remove(it)?.close()}
  if(0 !in wanted){node.image?.close();node.image=null}
  node.extraImages.keys.toList().filter{it !in wanted}.forEach{node.extraImages.remove(it)?.close()}
 }
 /**
  * Рисует основное и дополнительные изображения одного сообщения
  * Для альбома ограничивает общую область скруглённым контуром с нужной формой нижних углов
  * Выбирает аппаратный или обычный вывод и учитывает, перекрывает ли первое изображение активное видео
  */
 private fun drawImages(node:MessageNode,c:Canvas,software:Boolean){
  val saved=c.save()
  if(node.extraImages.isNotEmpty())node.fragment.prepared.mediaRect?.let{local->
   val rect=RectF(local).apply{offset(node.fragment.frame.left,node.fragment.frame.top)};val radius=node.fragment.prepared.mediaCornerRadius;val bottom=if(node.fragment.prepared.mediaBottomSquare)0f else radius
   val clip=Path().apply{addRoundRect(rect,floatArrayOf(radius,radius,radius,radius,bottom,bottom,bottom,bottom),Path.Direction.CW)};c.clipPath(clip)
  }
  if(node.video?.active!=true || node.image?.showsControls==false){if(software)node.image?.drawSoftware(c)else node.image?.draw(c)}
  for(image in node.extraImages.values){if(software)image.drawSoftware(c)else image.draw(c)}
  c.restoreToCount(saved)
 }
 /**
  * Удаляет сохранённые команды рисования указанного фрагмента в главном потоке
  * Сам узел и его положение остаются в сцене
  * При следующей подготовке к показу отсутствующий рисунок будет записан заново
  */
 fun discardDisplayList(id:FragmentID){main();nodes[id]?.native?.discardDisplayList()}
 /**
  * Проверяет сохранённые узлы перед рисованием сцены
  * Повторно записывает только те фрагменты, у которых система больше не хранит команды рисования
  * Каждую такую запись учитывает в показателе повторной подготовки
  */
 fun prepareForDraw(){main();for(node in nodes.values)if(!node.native.hasDisplayList()){node.record();metrics.recordings++}}
 /**
  * Рисует сохранённые узлы, изображения и движущиеся вложения в текущей области обрезки
  * Пропускает фрагменты за пределами этой области и использует уже записанные команды текста
  * Поверх активного видео оставляет только подписи снаружи его области, чтобы не дублировать передний слой
  */
 fun draw(canvas:Canvas){
  prepareForDraw()
  val clip=android.graphics.Rect();if(!canvas.getClipBounds(clip))return
  for(node in nodes.values){
   val r=node.fragment.frame
   // Область обрезки задана в координатах текущего рисования с учётом преобразований размытой подложки
   if(r.right<clip.left-2 || r.left>clip.right+2 || r.bottom<clip.top-2 || r.top>clip.bottom+2)continue
   canvas.drawRenderNode(node.native);drawImages(node,canvas,false);
   if(node.overlay.hasDisplayList()){
    val save=canvas.save()
    // Готовая видеоповерхность сама рисует передний слой внутри своей области
    // Сцена оставляет подписи снаружи, включая время отправки круглого видео
    if(node.video?.active==true)node.fragment.prepared.mediaRect?.let{canvas.clipOutRect(it.left+r.left,it.top+r.top,it.right+r.left,it.bottom+r.top)}
    canvas.drawRenderNode(node.overlay);canvas.restoreToCount(save)
   }
  }
 }
 /**
  * Рисует сцену обычными командами без использования сохранённых аппаратных узлов
  * Обрабатывает только пересекающиеся с областью показа фрагменты, добавляя текст, изображения и подписи
  * У активного видео исключает его область из общего слоя подписей
  */
 fun drawSoftware(c:Canvas){
  main();if(closed)return
  for(node in nodes.values){
   val f=node.fragment;if(c.quickReject(f.frame,Canvas.EdgeType.AA))continue
   var save=c.save();c.translate(f.frame.left,f.frame.top);f.prepared.draw(f.part,c);c.restoreToCount(save)
   drawImages(node,c,true);
   if(f.part==0 && f.prepared.mediaRect!=null){save=c.save();val media=f.prepared.mediaRect;if(node.video?.active==true)c.clipOutRect(media.left+f.frame.left,media.top+f.frame.top,media.right+f.frame.left,media.bottom+f.frame.top);c.translate(f.frame.left,f.frame.top);f.prepared.drawMediaStamp(c);c.restoreToCount(save)}
  }
 }
 /**
  * Подсчитывает активные источники кадров одиночных медиа и видео внутри альбомов
  * Сохраняет число в диагностике сцены после изменения воспроизведения
  * Этот показатель описывает работающие источники обновления, а не частоту кадров, показанных экраном
  */
 private fun updateClockMetrics(){metrics.activeFrameClocks=nodes.values.sumOf{node->
  (if(node.video?.active==true)1 else 0)+node.albumVideos.values.count{it.active}
 }}
 /**
  * Перед каждым вызовом переносит координаты к нужному элементу и затем восстанавливает их
  * Используется для живого оформления, которое должно учитывать движущееся содержимое
  */
 fun drawMovingBackdrop(c:Canvas){
  for(node in nodes.values)for(video in listOfNotNull(node.video)+node.albumVideos.values){
   val save=c.save();c.translate(video.left.toFloat(),video.top.toFloat());video.drawBackdrop(c);c.restoreToCount(save)
  }
 }
 /**
  * Удаляет наблюдение за доступностью предпросмотра и обнуляет число активных источников кадров
  * Повторное закрытие завершает вызов сразу, не освобождая прежние ресурсы второй раз
  */
 fun close(){main();if(closed)return;advanceGeneration();closed=true;previewObserver?.let{MediaAdmission.removePreviewObserver(it)};previewObserver=null;movingContainer=null;metrics.activeFrameClocks=0}
}
