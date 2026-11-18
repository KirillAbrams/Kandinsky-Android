package dev.kirill.kandinsky.chat.rendering

import dev.kirill.kandinsky.chat.attachments.StudioUploadPreview
import dev.kirill.kandinsky.chat.results.MediaGalleryController
import dev.kirill.kandinsky.chat.results.PhotoViewerController
import dev.kirill.kandinsky.chat.diagnostics.MetricsRuntime
import dev.kirill.kandinsky.chat.R
import dev.kirill.kandinsky.chat.media.*
import dev.kirill.kandinsky.chat.conversation.MessageAction
import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.*
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.OverScroller
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.storage.ChatRepository
import java.util.concurrent.*
import kotlin.math.*
class ChatScrollHost(context:Context):ViewGroup(context) {
 private var index=HistoryIndex(dayGap=36.0*resources.displayMetrics.density)
 private val days=ScrollingDays()
 private var overlayTop=0.0;private var overlayBottom=0.0
 var onSceneChanged:(()->Unit)?=null
 private var pageRequests=0
 private val scene=RetainedChatScene()
 private val worker=TextLayoutWorker()
 private val cache=FragmentLayoutCache()
 private var pages:PageWindow?=null
 private var repository:ChatRepository?=null
 private var chatID:ChatID?=null
 private var loaded=emptyMap<MessageID,Message>()
 private val inFlight=mutableMapOf<TextLayoutKey,Cancellation>()
 private val main=Handler(Looper.getMainLooper())
 var onStudioResultDelete:((MessageID,String)->Unit)?=null
 var onStudioGenerationAction:((MessageID,StudioGenerationAction)->Unit)?=null
 var onStudioRetrySlot:((MessageID,StudioGeneration.Slot)->Unit)?=null
 private var indexRefreshing=false
 private var closed=false;private var queued=false;private var pageLoading=false;private var generation=0
 private var opening:((Result<Unit>)->Unit)?=null
 /**
  * Завершает текущее открытие чата переданным результатом
  * Сначала удаляет сохранённое продолжение, затем вызывает его при наличии
  * Это не позволяет повторному ответу завершить один запрос открытия дважды
  */
 private fun finishOpening(result:Result<Unit>){val callback=opening;opening=null;callback?.invoke(result)}
 /**
  * Обрабатывает ошибку подготовки указателя только для действующего поколения экрана
  * Снимает признак обновления и завершает открытие ошибкой, если оно ещё ожидается
  * При обычном обновлении уже открытого чата передаёт ошибку его общему обработчику
  */
 private fun indexFailed(error:Throwable,ticket:Int){if(!closed && ticket==generation){indexRefreshing=false;val wasOpening=opening!=null;finishOpening(Result.failure(error));if(!wasOpening)onError?.invoke(error)}}
 private var initialFrame:((Result<Unit>)->Unit)?=null
 private var initialMediaDeadline=0L
 private val initialTimeout=Runnable{schedule()}
 /**
  * Начинает ожидание первого подготовленного кадра перед показом открытого чата
  * Отменяет прежнее ожидание и даёт видимым изображениям небольшое время на подготовку
  * Завершение происходит после проверки текста и сцены, чтобы экран не открывался раньше нужного содержимого
  */
 fun prepareInitialFrame(completion:(Result<Unit>)->Unit){
  finishInitialFrame(Result.failure(RepoException(RepoError.CANCELLED)))
  if(closed){completion(Result.failure(RepoException(RepoError.CANCELLED)));return}
  initialFrame=completion;initialMediaDeadline=android.os.SystemClock.uptimeMillis()+250
  main.postDelayed(initialTimeout,250);schedule()
 }
 /**
  * Завершает текущее ожидание первого кадра переданным результатом
  * Сначала убирает сохранённое ожидание и отложенное пробуждение, затем вызывает продолжение
  * Так один и тот же запрос не получает завершение повторно
  */
 private fun finishInitialFrame(result:Result<Unit>){
  val callback=initialFrame;initialFrame=null;main.removeCallbacks(initialTimeout);callback?.invoke(result)
 }
 /**
  * Пока не истёк короткий срок подготовки медиа, требует также готовности видимых изображений
  * При выполнении условий сообщает об успешной подготовке, оставляя сам показ сцены её обычному механизму
  */
 private fun publishInitialFrame(fragments:List<SceneFragment>){
  if(initialFrame==null)return
  val visible=index.visible(max(0.0,offset+overlayTop),max(0.0,height-overlayTop-overlayBottom),overscan=0).map{index.rows[it]}
  if(visible.any{meta->loaded[meta.messageID]?.let{cache.value(key(it))}==null})return
  val ids=fragments.filter{RectF.intersects(it.frame,scene.viewport)}.map{it.id}.toSet()
  if(visible.isNotEmpty() && ids.isEmpty())return
  if(scene.presentationReady(ids,android.os.SystemClock.uptimeMillis()<initialMediaDeadline))finishInitialFrame(Result.success(Unit))
 }
 private var pendingAnchor:ReadingAnchor?=null
 private var offset=0.0
 private var lastPreparedOffset:Double?=null
 private var preparingOlder=true
 private val d=resources.displayMetrics.density
 private var layoutWidth=0
 private var layoutFontSize=desiredFontSize()
 /**
  * Получает размер основного текста с учётом системных настроек пользователя и плотности экрана
  * Переводит выбранный размер в пиксели и округляет его до целого
  * Ограничивает минимум, чтобы подготовка строк не получила недопустимый размер
  */
 private fun desiredFontSize()=max(3,android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,17f,resources.displayMetrics).roundToInt())
 private val scroller=OverScroller(context)
 private var lastFlingY=0
 private val displayPolicy=(context as? android.app.Activity)?.window?.let{dev.kirill.kandinsky.chat.diagnostics.ActiveDisplayPolicy(it)}
 /**
  * Обновляет запрос предпочтительной частоты экрана по активности чата
  * Учитывает видимость приложения, ручную прокрутку, инерцию и активные источники кадров медиа
  * Сам факт такого запроса не означает измеренную частоту показанных кадров
  */
 private fun updateDisplayHint(){displayPolicy?.setActive(!closed && scene.mediaVisible && isAttachedToWindow && (context as? dev.kirill.kandinsky.chat.app.MainActivity)?.isForeground==true && (dragging || !scroller.isFinished || scene.metrics.activeFrameClocks>0))}
 private var velocity:VelocityTracker?=null
 private var longPressHandled=false
 private val gestures=GestureDetector(context,object:GestureDetector.SimpleOnGestureListener(){
  /**
   * Подтверждает начало последовательности касаний распознавателю жестов
   * Это позволяет ему продолжить отслеживание и определить долгое нажатие
   * Сам выбор сообщения выполняется в обработчике подходящего жеста
   */
  override fun onDown(e:MotionEvent)=true
  /**
   * Открывает действия по долгому нажатию, если сейчас не идёт ручная прокрутка
   * Проверяет попадание в сообщение и допустимость действий в режиме генерации
   * Отмечает обработанный жест, чтобы отпускание пальца не открыло медиа ещё раз
   */
  override fun onLongPress(e:MotionEvent){
   if(dragging)return
   val handler=onMessageLongPress ?: return
   val (row,frame)=hit(e.x,e.y) ?: return
   if(onStudioGenerationAction!=null && !row.canReportStudioMedia && row.direction!=MessageDirection.OUTGOING)return
   longPressHandled=true
   handler(row,frame)
  }
 })
 private var lastY=0f;private var downY=0f;private var dragging=false
 private var nodes=emptyList<NativeAction>()
 private var lastAxNotice=0L
 private var axNoticePending=false
 private var playback:MediaCoordinator?=null
 private val playbackOwner=Any()
 private var tappedMediaIndex=0
 private val mediaExport=KandinskyMediaExport(context)
 init { KandinskyMediaAction.load(resources);StudioGenerationCard.load(resources) }
 /**
  * Передаёт нажатие по сообщению вместе с выбранным номером вложения общему обработчику медиа
  * На время вызова сохраняет этот номер, чтобы альбом открылся на нужной фотографии или видео
  * После обработки возвращает номер по умолчанию, в том числе при досрочном завершении
  */
 private fun activateMedia(row:Message,frame:RectF,index:Int=0){tappedMediaIndex=index;try{onMessageTap?.invoke(row,frame)}finally{tappedMediaIndex=0}}
 /**
  * Выполняет выбранное действие под готовым результатом генерации
  * Для раскрытия передаёт управление обычному открытию медиа, для сохранения и отправки использует исходный файл
  * Ошибку получения или передачи файла сообщает через общий обработчик экрана
  */
 private fun performMediaAction(action:KandinskyMediaAction,row:Message,frame:RectF){
  if(action==KandinskyMediaAction.EXPAND){activateMedia(row,frame);return}
  val media=viewerMedia ?: return
  val attachment=row.attachments.firstOrNull() ?: return
  mediaExport.start(media,attachment,action==KandinskyMediaAction.SHARE){onError?.invoke(it)}
 }
 private var viewerMedia:MediaStore?=null
 private var playerSurface:InlinePlayerSurface?=null
 private var studioPreview:StudioUploadPreview?=null
 private var photoViewer:PhotoViewerController?=null
 private var mediaGallery:MediaGalleryController?=null
 private var ownedPlayerChange:(()->Unit)?=null
 private var ownedPlayerDetach:(()->Unit)?=null
 private val ax=NativeAccessibility(this,"chat.history",{nodes},{listOfNotNull(playerSurface?.takeIf{it.parent===this})})
 private val buildPool=ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue(1),{r->Thread(r,"Kandinsky-Index").apply{isDaemon=true}},ThreadPoolExecutor.AbortPolicy())

 /**
  * Связывает управление воспроизведением с этим экраном и его поверхностью показа
  * Назначает обработчики изменения состояния, открытия медиа и перехода к полному просмотру
  * Проверяет владельца представления, чтобы другой экран не изменял чужую поверхность воспроизведения
  */
 fun configurePlayback(coordinator:MediaCoordinator,media:MediaStore){
  playback=coordinator;coordinator.presentationOwner=playbackOwner;viewerMedia=media;playerSurface=InlinePlayerSurface(context,coordinator)
  coordinator.onChange={if(coordinator.presentationOwner===playbackOwner){playerSurface?.update();studioPreview?.update()};schedule()}
  coordinator.onDetachOutput={if(coordinator.presentationOwner===playbackOwner){playerSurface?.detachPlayer();(playerSurface?.parent as? ViewGroup)?.removeView(playerSurface);studioPreview?.close()}}
  ownedPlayerChange=coordinator.onChange;ownedPlayerDetach=coordinator.onDetachOutput
  onMessageTap={row,_->
   if(row.kind in listOf(MessageKind.PHOTO,MessageKind.VIDEO,MessageKind.ROUND,MessageKind.GIF) && repository!=null)openMediaGallery(row,tappedMediaIndex)
   else if(!closed && photoViewer==null)row.attachments.firstOrNull()?.takeIf{it.mime.startsWith("image/")}?.let{a->
    val preview=scene.preview(row.id);coordinator.stop();setMediaVisible(false)
    val viewer=PhotoViewerController(context as android.app.Activity,a,media,preview,row.caption);photoViewer=viewer
    viewer.onClose={if(photoViewer===viewer){photoViewer=null;setMediaVisible(true)}};viewer.show()
   }
  }
 }
 /**
  * Открывает отдельный просмотр выбранного видео с возможностью передать его на улучшение
  * Приостанавливает движение медиа в чате и использует общую поверхность воспроизведения
  * После закрытия останавливает этот просмотр, восстанавливает видимость чата и вызывает подходящее продолжение
  */
 fun previewStudioVideo(attachment:Attachment,onEnhance:(()->Unit)?=null,onClose:()->Unit){
  val coordinator=playback
  val surface=playerSurface
  if(coordinator==null || surface==null || studioPreview!=null){onClose();return}
  coordinator.presentationOwner=playbackOwner
  setMovingMediaVisible(false)
  coordinator.play(PlaybackTarget(attachment,-1L,kind=MessageKind.VIDEO,loopPreview=true))
  surface.circular(false);surface.setPoster(null)
  val rotated=attachment.orientation>=5
  surface.aspect(if(rotated)attachment.rawHeight else attachment.rawWidth,if(rotated)attachment.rawWidth else attachment.rawHeight)
  val preview=StudioUploadPreview(context as android.app.Activity,surface,coordinator,attachment,onEnhance!=null){enhancing->
   studioPreview=null
   coordinator.stop(if(enhancing && onEnhance!=null)onEnhance else onClose)
   setMovingMediaVisible(true)
   setMediaVisible(true)
  }
  studioPreview=preview;preview.show()
 }
 /**
  * Открывает последовательный просмотр вложений чата с выбранного результата
  * Передаёт уже доступное изображение, хранилище и общую поверхность воспроизведения
  * На время просмотра согласует видимость медиа в чате, а после закрытия восстанавливает её только для того же окна просмотра
  */
 private fun openMediaGallery(row:Message,attachmentIndex:Int=0){
  if(mediaGallery!=null || photoViewer!=null)return
  val repo=repository ?: return;val media=viewerMedia ?: return;val coordinator=playback ?: return;val surface=playerSurface ?: return
  val studio=onStudioResultDelete!=null
  val wasMoving=scene.movingMediaVisible
  lateinit var viewer:MediaGalleryController
  viewer=MediaGalleryController(context as android.app.Activity,row,repo,media,coordinator,surface,scene.preview(row.id,attachmentIndex),attachmentIndex){
   if(!closed && mediaGallery===viewer){
    mediaGallery=null;setMediaVisible(true)
    if(studio)setMovingMediaVisible(wasMoving)
   }
  }
  viewer.onDeleteResult=onStudioResultDelete
  mediaGallery=viewer
  if(studio)setMovingMediaVisible(false)else setMediaVisible(false)
  coordinator.setInlineVisible(false)
  if(row.kind!=MessageKind.VIDEO)coordinator.stop()
  viewer.show()
 }

 /**
  * Назначает их самому элементу без измерения всех сообщений
  * Сообщения размещаются по указателю высот и ограниченной видимой сцене
  */
 override fun onMeasure(w:Int,h:Int){setMeasuredDimension(MeasureSpec.getSize(w),MeasureSpec.getSize(h))}
 /**
  * Обновляет положение встроенного проигрывателя после размещения области истории
  * Использует актуальные границы выбранного сообщения и текущую прокрутку
  * Остальные фрагменты размещаются отдельно при согласовании сцены
  */
 override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){updateInlinePlayer()}
 /**
  * Согласует встроенную поверхность воспроизведения с областью выбранного сообщения
  * Находит подготовленные размеры, учитывает положение прокрутки и скрывает поверхность за пределами экрана
  * Во время отдельного просмотра или при чужом владельце не переставляет проигрыватель в этот чат
  */
 private fun updateInlinePlayer(){
  if(studioPreview!=null || mediaGallery!=null)return
  val c=playback ?: return;val id=c.currentMessageID ?: return;val surface=playerSurface ?: return
  if(c.presentationOwner!==playbackOwner)return
  val row=loaded[id];val top=index.top(id)
   if(row==null || top==null){hideInlinePlayer();return}
   val y=(top-offset).toFloat()
   if(y+(index.height(id) ?: 0.0)<=0 || y>=height){hideInlinePlayer();return}
   val prepared=cache.value(key(row)) ?: return
  val x=rowLeft(row,prepared)
  val rect=RectF(prepared.mediaRect ?: RectF(0f,0f,prepared.width,if(row.kind==MessageKind.VOICE)60*d else prepared.height.coerceIn(64*d,110*d))).apply{if(row.kind==MessageKind.ROUND)right=left+prepared.width;offset(x,y)}
  if(rect.bottom<=0 || rect.top>=height){hideInlinePlayer();return}
  if(surface.parent!==this){surface.setPoster(scene.preview(id));(surface.parent as? ViewGroup)?.removeView(surface);addView(surface)};c.setInlineVisible(true)
  row.attachments.firstOrNull()?.let{a->surface.aspect(if(a.orientation>=5)a.rawHeight else a.rawWidth,if(a.orientation>=5)a.rawWidth else a.rawHeight)}

  surface.circular(row.kind==MessageKind.ROUND)
  surface.measure(MeasureSpec.makeMeasureSpec(rect.width().toInt(),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(rect.height().toInt(),MeasureSpec.EXACTLY))
  surface.layout(rect.left.toInt(),rect.top.toInt(),rect.right.toInt(),rect.bottom.toInt());surface.update()
 }
 /**
  * Убирает встроенную поверхность из дерева отображения и отмечает, что она больше не видна
  * Изменяет состояние воспроизведения только при принадлежности текущему экрану
  * Останавливает содержимое, которому нельзя продолжать работу вне видимой области
  */
 private fun hideInlinePlayer(){
  val c=playback ?: return;if(c.presentationOwner!==playbackOwner)return;playerSurface?.detachPlayer();(playerSurface?.parent as? ViewGroup)?.removeView(playerSurface);c.setInlineVisible(false)
  c.stop()
 }
 /**
  * Согласует разрешение показа медиа с видимостью и состоянием экрана приложения
  * Учитывает открытый просмотр поверх чата, чтобы одни и те же ресурсы не показывались одновременно в двух местах
  * После изменения состояния запрашивает очередное согласование сцены
  */
 fun setMediaVisible(visible:Boolean){
  if(!visible)displayPolicy?.setActive(false)
  val active= !closed && visible && isAttachedToWindow && (context as? dev.kirill.kandinsky.chat.app.MainActivity)?.isForeground==true
  scene.mediaVisible=active && photoViewer==null && studioPreview==null && mediaGallery==null
  if(active)mediaGallery?.resumeAutoplay()
  schedule()
 }
 /**
  * Останавливает текущее движение прокрутки перед переходом на другой экран
  * Приостанавливает движущиеся медиа, оставляя сохранённые неподвижные изображения
  * Используется при переходах между чатами, чтобы скрытый экран не продолжал движение
  */
 fun pauseForNavigation(){scroller.abortAnimation();dragging=false;setMovingMediaVisible(false)}
 /**
  * Обновляет разрешение на движущееся содержимое сцены
  * Учитывает отдельный просмотр загруженного видео и просмотр результатов генерации
  * Запрашивает согласование, чтобы активные поверхности и индикаторы соответствовали новому состоянию
  */
 fun setMovingMediaVisible(visible:Boolean){scene.movingMediaVisible=visible && studioPreview==null && mediaGallery?.onDeleteResult==null;schedule()}
 /**
  * Подключает хранилище медиа к сцене текущего чата
  * Назначает уведомление о готовности изображения как повод обновить подготовленное представление
  * Так готовый файл может появиться в нужной ячейке без нового открытия чата
  */
 fun configureMedia(store:dev.kirill.kandinsky.chat.media.MediaStore){scene.mediaStore=store;scene.movingContainer=this;scene.onFrameChanged={updateDisplayHint();invalidate()};scene.onImageReady={schedule();invalidate()}}
 var onAnchor:((ReadingAnchor)->Unit)?=null
 var onMessageTap:((Message,RectF)->Unit)?=null
 var onSelectionTap:((Message)->Boolean)?=null
 var selectedMessageIDs:Set<MessageID> = emptySet()
  set(value){if(field!=value){field=value;invalidate()}}
 var onMessageLongPress:((Message,RectF)->Unit)?=null
  var onCountChanged:((Int)->Unit)?=null
 var onMessageAction:((Message,MessageAction)->Unit)?=null
 var onError:((Throwable)->Unit)?=null
 val messageCount get()=index.rows.size
 val metrics get()=scene.metrics
 val loadedCount get()=pages?.loadedCount ?: 0
 init{setWillNotDraw(false);importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES;isFocusable=true}
 /**
  * Привязывает экран к выбранному чату и создаёт ограниченное окно страниц его истории
  * Получает указатель сообщений, подготавливает быстрый поиск по высотам и восстанавливает сохранённое место чтения
  * Перед применением проверяет поколение открытия, чтобы поздний ответ не заменил уже выбранный другой чат
  */
 fun open(chatID:ChatID,repository:ChatRepository,anchor:ReadingAnchor?,completion:(Result<Unit>)->Unit){
  if(closed){completion(Result.failure(RepoException(RepoError.CANCELLED)));return}
  finishOpening(Result.failure(RepoException(RepoError.CANCELLED)));opening=completion
  this.chatID=chatID;this.repository=repository;pages=PageWindow(chatID,repository);pendingAnchor=anchor ?: ReadingAnchor();generation++;val ticket=generation
  repository.index(chatID){result->if(!closed && ticket==generation)result.fold({rows->buildIndex(rows,emptyMap(),ticket){prepared->
   index=prepared;setGeometry(pendingAnchor ?: ReadingAnchor());schedule();finishOpening(Result.success(Unit))
  }},{indexFailed(it,ticket)})}
 }
 /**
  * Подготавливает указатель истории в отдельной ограниченной очереди
  * Для неизменившихся версий оставляет прежние сведения о высотах, для новых учитывает плотность экрана
  * Передаёт результат в главный поток только при совпадении поколения и сообщает об ошибках подготовки
  */
 private fun buildIndex(rows:List<IndexedRow>,previous:Map<MessageID,IndexedRow>,ticket:Int,completion:(HistoryIndex)->Unit){
  try{buildPool.execute{
   try{
    val scaled=rows.map{row->previous[row.messageID]?.takeIf{it.revision==row.revision} ?: row.copy(estimatedHeight=row.estimatedHeight*d)}
    val value=HistoryIndex(dayGap=36.0*d);value.replace(scaled,ReadingAnchor(),0.0)
    main.post{if(!closed && ticket==generation)completion(value)}
   }catch(t:Exception){main.post{indexFailed(t,ticket)}}
  }}catch(t:RejectedExecutionException){indexFailed(t,ticket)}
 }
 /**
  * Принимает изменение истории, сохраняя место чтения или переходя вниз после собственной отправки
  * Для подходящих обновлений генерации использует короткий путь без перестроения всего указателя
  * В остальных случаях обновляет указатель и оставляет загруженные сообщения, чьи версия и порядок не изменились
  * Устаревший результат предыдущего обновления не применяет к текущему экрану
  */
 fun apply(delta:MessageDelta){
  if(closed)return
  val repo=repository ?: return;val id=chatID ?: return
  if(!indexRefreshing && patchStudioRows(delta))return
  playback?.apply(delta);mediaGallery?.apply(delta)
  indexRefreshing=true
  val anchor=if(delta.reason==MessageChangeReason.LOCAL_SEND && delta.inserted.any{it.direction==MessageDirection.OUTGOING})ReadingAnchor(followsBottom=true) else ReadingAnchorResolver.afterRemoving(readingAnchor(),index.rows,delta.removedIDs.toSet());pendingAnchor=anchor;generation++;val ticket=generation
  repo.index(id){result->if(!closed && ticket==generation)result.fold({rows->buildIndex(rows,index.rows.associateBy{it.messageID},ticket){prepared->
   val retained=loaded.values.filter{row->prepared.position(row.id)?.let{prepared.rows[it]}?.let{it.revision==row.revision && it.order==row.order}==true}
   pages?.close();pages=PageWindow(id,repo).also{it.seedKnownRows(retained)};pageLoading=false;loaded=retained.associateBy{it.id}
   indexRefreshing=false;index=prepared
   restoreCachedRowHeights(anchor)
   setGeometry(anchor);onCountChanged?.invoke(index.rows.size);schedule();finishOpening(Result.success(Unit))
  }},{indexFailed(it,ticket)})}
 }
 /**
  * Обновляет карточки генерации на месте, когда список сообщений, их порядок и время не изменились
  * Заменяет версии уже известных сообщений, подготавливает их новое содержимое и уточняет высоты
  * При смене вложений уведомляет просмотр медиа и воспроизведение
  * Возвращает признак применённого короткого пути, чтобы не перестраивать всю историю
  */
 private fun patchStudioRows(delta:MessageDelta):Boolean{
  if(delta.inserted.isNotEmpty() || delta.removedIDs.isNotEmpty() || delta.updated.isEmpty())return false
  if(!delta.updated.all{row->
    val entry=index.position(row.id)?.let{index.rows[it]}
    (row.studioGeneration!=null || loaded[row.id]?.studioGeneration!=null) && entry!=null && entry.order==row.order && entry.savedAtMs==row.savedAtMs
   })return false
  val anchor=readingAnchor()
  var heightChanged=false
  var mediaChanged=false
  val changed=loaded.toMutableMap()
  for(row in delta.updated){
   index.refreshRevision(row);pages?.refreshKnownRow(row)
   val old=changed[row.id] ?: continue
   if(old.revision>=row.revision)continue
   mediaChanged=mediaChanged || old.attachments!=row.attachments
   changed[row.id]=row
   val resource=PreparedText(row,key(row));cache.insert(resource)
   val size=measuredRowHeight(resource)
   val previous=index.height(row.id)
   if(previous!=null && kotlin.math.abs(previous-size)>0.5){
    index.updateHeight(row.id,size,anchor,height.toDouble());heightChanged=true
   }
  }
  loaded=changed
  if(mediaChanged){playback?.apply(delta);mediaGallery?.apply(delta)}
  if(heightChanged)setGeometry(anchor)
  schedule()
  return true
 }
 /**
  * Возвращает место чтения, которое можно восстановить после изменения истории или размеров экрана
  * Сохраняет сообщение у верхней границы, его смещение и признак следования за концом чата
  * Для подготовленного текстового сообщения дополнительно запоминает строку, чтобы изменение переноса текста не уводило чтение
  */
 fun readingAnchor(viewport:Int=height):ReadingAnchor{
  pendingAnchor?.let{return it}
  var value=index.anchorAt(max(0.0,offset+overlayTop)).copy(followsBottom=index.totalHeight-offset-viewport<24*d)
  val id=value.messageID;val row=loaded[id]
  if(id!=null && row?.kind==MessageKind.TEXT){
   val resource=cache.value(key(row));val top=index.top(id)
   if(resource!=null && top!=null)resource.lineAnchor((offset+overlayTop-top).toFloat())?.let{value=value.copy(textUTF16Offset=it.first,screenDelta=it.second.toDouble())}
  };return value
 }
 /**
  * Переводит сохранённое место чтения в допустимое смещение текущей истории
  * Для следования за концом выбирает нижнюю границу, а для текста по возможности восстанавливает конкретную строку
  * При отсутствии нужной разметки использует положение сообщения и ограничивает результат размерами содержимого
  */
 private fun resolved(anchor:ReadingAnchor):Double{
  if(anchor.followsBottom)return max(0.0,index.totalHeight-height)
  val id=anchor.messageID;val row=loaded[id]
  if(id!=null && row!=null && anchor.textUTF16Offset!=null){
   val y=cache.value(key(row))?.yForUTF16(anchor.textUTF16Offset);val top=index.top(id)
   if(y!=null && top!=null)return (top+y-anchor.screenDelta-overlayTop).coerceIn(0.0,max(0.0,index.totalHeight-height))
  }
  return (index.offset(anchor,max(0.0,height-overlayTop))-overlayTop).coerceIn(0.0,max(0.0,index.totalHeight-height))
 }
 /**
  * Добавляет небольшой промежуток после сообщения с учётом плотности экрана
  * Единый расчёт используется при обновлении высот и восстановлении сохранённой разметки
  */
 private fun measuredRowHeight(resource:PreparedText)=(resource.height+6*d).toDouble()
 /**
  * Восстанавливает точные высоты загруженных сообщений из подходящей сохранённой разметки
  * Пропускает отсутствующие и практически совпадающие измерения
  * Возвращает признак изменения, чтобы положение истории пересчиталось только при необходимости
  */
 private fun restoreCachedRowHeights(anchor:ReadingAnchor):Boolean{
  var changed=false
  for(row in loaded.values){
   val indexedHeight=index.height(row.id) ?: continue
   val resource=cache.value(key(row)) ?: continue
   val measuredHeight=measuredRowHeight(resource)
   if(abs(indexedHeight-measuredHeight)<=0.5)continue
   index.updateHeight(row.id,measuredHeight,anchor,height.toDouble())
   changed=true
  }
  return changed
 }
 /**
  * Обновляет отступы истории и её положение по сохранённому месту чтения
  * Учитывает верхнюю и нижнюю панели, чтобы содержимое не смещалось из выбранной точки
  * После изменения запрашивает показ в новой геометрии, не изменяя сами сообщения
  */
 private fun setGeometry(anchor:ReadingAnchor){index.configureInsets(overlayTop+8*d,overlayBottom+8*d);offset=resolved(anchor);invalidate()}
 /**
  * Ограничивает отступы доступной высотой экрана и сохраняет место чтения до изменения
  * Обновляет геометрию и планирует согласование сцены под новыми панелями
  */
 fun updateOverlayInsets(top:Double,bottom:Double){
  if(top==overlayTop && bottom==overlayBottom)return
  val anchor=readingAnchor();overlayTop=top.coerceIn(0.0,height.toDouble());overlayBottom=bottom.coerceIn(0.0,max(0.0,height-overlayTop))
  setGeometry(anchor);schedule()
 }
 val gestureViewport:RectF get()=RectF(0f,overlayTop.toFloat(),width.toFloat(),(height-overlayBottom).toFloat())
 /**
  * Сохраняет место чтения перед изменением размеров области истории
  * При новой ширине или размере шрифта отменяет подготовку и удаляет несовместимую разметку
  * Применяет новое положение и запрашивает подготовку содержимого под текущие размеры
  */
 override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){
  val anchor=readingAnchor(oldh);val font=desiredFontSize()
  val reflow=w!=layoutWidth || font!=layoutFontSize
  layoutWidth=w;layoutFontSize=font
  if(reflow){inFlight.values.forEach{it.cancel()};inFlight.clear();scene.advanceGeneration();cache.clear();pendingAnchor=anchor}
  setGeometry(anchor);schedule()
 }
 /**
  * Проверяет размер текста после изменения системных настроек
  * Если размер изменился, сохраняет место чтения и отменяет разметку под прежний шрифт
  * Восстанавливает геометрию и планирует подготовку с новыми параметрами
  */
 override fun onConfigurationChanged(configuration:android.content.res.Configuration){
  super.onConfigurationChanged(configuration)
  val font=desiredFontSize()
  if(font!=layoutFontSize){
   val anchor=readingAnchor();layoutFontSize=font
   inFlight.values.forEach{it.cancel()};inFlight.clear();scene.advanceGeneration();cache.clear();pendingAnchor=anchor
   setGeometry(anchor);schedule()
  }
 }
 /**
  * Составляет параметры подготовки одного сообщения из его версии и текущих размеров экрана
  * Выбирает подходящую ширину для текста, результата генерации, альбома или круглого видео
  * Учитывает шрифт и масштаб, чтобы сохранённая разметка использовалась только при совпадении условий показа
  */
 private fun key(row:Message):TextLayoutKey{
  val viewport=if(layoutWidth>0)layoutWidth else width
  val visual=row.attachments.isNotEmpty() && row.kind in listOf(MessageKind.PHOTO,MessageKind.VIDEO,MessageKind.ROUND,MessageKind.GIF,MessageKind.STICKER)
  val a=row.attachments.firstOrNull();val rw=if(a!=null && a.orientation>=5)a.rawHeight else a?.rawWidth;val rh=if(a!=null && a.orientation>=5)a.rawWidth else a?.rawHeight
  val result=row.direction==MessageDirection.INCOMING && row.attachments.size==1 && (row.kind==MessageKind.PHOTO || row.kind==MessageKind.VIDEO)
  val studio=row.studioGeneration
  val bound=when{
   studio!=null && (studio.isActive || studio.slots.isNotEmpty())->minOf(304*d,viewport-20*d,if(studio.ratio>=1)304*d else viewport*.7f+8*d)
   studio!=null->minOf(316*d,viewport-24*d)
   result->minOf(304*d,viewport-20*d,if((rw ?: 1)>=(rh ?: 1))304*d else viewport*.7f+8*d)
   row.kind==MessageKind.ROUND->viewport*.6
   visual->if((rw ?: 1)>=(rh ?: 1))viewport-60*d else viewport*.7+4*d
   else->minOf(viewport*.76,280.0*d)
  }
  val allocation=if(row.kind==MessageKind.ROUND)minOf(viewport-12.0*d,bound.toDouble()+36*d)else bound
  return TextLayoutKey(row.id,row.revision,max(60,allocation.toInt()),layoutFontSize,1,d,roundDiameter=if(row.kind==MessageKind.ROUND)bound.toInt() else null)
 }
 /**
  * Определяет горизонтальное положение подготовленного сообщения в чате
  * Отдельно размещает карточки генерации и результаты с кнопками действий
  * Для обычного сообщения выбирает отступ по направлению отправки и наличию медиа
  */
 private fun rowLeft(row:Message,prepared:PreparedText):Float{
  prepared.generationCard?.let{return (if(it.isBatch || it.generation.isActive)10 else 6)*d}
  if(prepared.mediaActions.isNotEmpty())return 10*d
  val media=prepared.mediaRect!=null
  return if(row.direction==MessageDirection.OUTGOING)width-prepared.width-(if(media)6 else 12)*d else (if(media)6 else 18)*d
 }
 /**
  * Объединяет несколько запросов обновления в один ожидающий проход сцены
  * Если такой проход уже запланирован или экран закрыт, не создаёт повторную работу
  * Это ограничивает число согласований при частых изменениях процентов, изображений и прокрутки
  */
 private fun schedule(){if(queued || closed)return;queued=true;post{if(queued && !closed){queued=false;render()}}}
 /**
  * Не запускает второй запрос страницы, пока предыдущий относится к действующему окну
  * После ответа проверяет актуальность окна, сохраняет полученные сообщения и запрашивает новый показ
  * Ошибку передаёт ожиданию первого кадра и обработчику экрана
  */
 private fun requestPage(center:OrderKey){
  if(pageLoading)return
  val p=pages ?: return;val id=chatID ?: return
  pageRequests++;if(MetricsRuntime.enabled)MetricsRuntime.recorder.peak("page_requests",pageRequests)
  pageLoading=true
  p.request(PageRequest(id,center,PageDirection.AROUND)){result->
   if(closed || pages !== p)return@request
   pageLoading=false
   if(!closed)result.fold({
    val anchor=readingAnchor()
    loaded=p.loadedRows.associateBy{it.id}
    // Сохранённый фрагмент не проходит новую подготовку, поэтому его точную высоту восстанавливаем отдельно
    if(restoreCachedRowHeights(anchor))setGeometry(anchor)
    schedule()
   },{finishInitialFrame(Result.failure(it));if(it !is RepoException || it.reason!=RepoError.CANCELLED)onError?.invoke(it)})
  }
 }
 /**
  * Использует сохранённую разметку, а недостающий текст отдаёт ограниченной очереди подготовки
  * Собирает фрагменты, отдельные области результатов и описания для системного чтения экрана
  * Согласует готовую сцену с медиа и проверяет, можно ли завершить ожидание первого кадра
  */
 private fun render(){
  if(width<=0 || height<=0 || closed)return
  val renderToken=MetricsRuntime.recorder.begin();if(MetricsRuntime.enabled)android.os.Trace.beginSection("Kandinsky.Scene")
  lastPreparedOffset?.let{if(offset!=it)preparingOlder=offset<it}
  lastPreparedOffset=offset
  val leading=minOf(600.0*d,height*0.9)
  val trailing=minOf(320.0*d,height*0.4)
  val before=if(preparingOlder)leading else trailing
  val after=if(preparingOlder)trailing else leading
  val current=index.visible(max(0.0,offset),height.toDouble(),overscan=0)
  val runwayTop=max(0.0,offset-before)
  val runway=index.visible(runwayTop,offset+height+after-runwayTop,overscan=0)
  val start=maxOf(runway.first,current.last-127)
  val window=start..maxOf(current.last,minOf(runway.last,start+127))
  val visible=window.map{index.rows[it]}
  val pivot=current.first+(current.last-current.first+1)/2
  window.filter{index.rows[it].messageID !in loaded}.minByOrNull{kotlin.math.abs(it-pivot)}?.let{requestPage(index.rows[it].order)}
  val fragments=mutableListOf<SceneFragment>();val accessibility=days.apply(index.days(offset,height.toDouble()),offset,width,d,studio=onStudioGenerationAction!=null).toMutableList()
  for(meta in visible){
   val row=loaded[meta.messageID] ?: continue;val top=index.top(row.id) ?: continue;val k=key(row)
   val resource=cache.value(k)
   if(resource==null){
    if(!inFlight.containsKey(k)){
     val ticket=scene.ticket
     inFlight[k]=worker.prepare(row,k,ticket){t,value->
      inFlight.remove(k)
      if(value!=null && scene.accept(value,t)){
       val anchor=readingAnchor();cache.insert(value)
       if(loaded[row.id]?.let{key(it)==value.key}==true && index.position(row.id)!=null){index.updateHeight(row.id,measuredRowHeight(value),anchor,height.toDouble());setGeometry(anchor)}
       schedule()
      }
     }
    };continue
   }
   val x=rowLeft(row,resource)
   val rowY=(top-offset).toFloat()
   for(part in resource.blocks.indices){
    val b=resource.blocks[part];val y=rowY+b.offsetY
    if(y+b.height >= -before && y<=height+after && fragments.size<128)fragments+=SceneFragment(resource,part,RectF(x,y,x+resource.width,y+b.height))
   }
   if(rowY+resource.height<=0 || rowY>=height)continue
   resource.mediaItemRects.forEachIndexed{itemIndex,rect->
    val hit=RectF(rect).apply{offset(x,rowY)}
    if(playback?.currentMessageID!=row.id && hit.bottom>0 && hit.top<height && accessibility.size<128){
     val report=if(row.canReportStudioMedia && onStudioGenerationAction!=null)listOf(
      NativeExtraAction(R.id.message_report,"Пожаловаться на результат"){onMessageLongPress?.invoke(row,hit)}
     )else emptyList()
     accessibility+=NativeAction(1000+accessibility.size,"media.image."+row.id+(if(itemIndex==0)""else ".$itemIndex"),if(row.kind==MessageKind.ROUND)"Воспроизвести кружочек" else if(row.attachments[itemIndex].mime.startsWith("video/"))"Воспроизвести видео" else "Открыть фотографию",hit,action={activateMedia(row,hit,itemIndex)},customActions=report)
    }
   }
   for(button in resource.mediaActions){
    val hit=RectF(button.frame).apply{offset(x,rowY)}
    if(hit.bottom>0 && hit.top<height && accessibility.size<128){
     val id="media.action.${button.action.name.lowercase(java.util.Locale.ROOT)}.${row.id}"
     accessibility+=NativeAction(1000+accessibility.size,id,button.action.title,hit,action={performMediaAction(button.action,row,hit)})
    }
   }
   resource.generationCard?.let{card->
    val image=card.image
    val indicators=if(card.isBatch)card.cells.filter{it.slot.blobId==null} else if(image!=null)
     listOf(StudioGenerationCard.Cell(StudioGeneration.Slot("single",card.generation.stage,null,card.generation.progress),image))
     else emptyList()
    for(cell in indicators){
     val hit=RectF(cell.frame).apply{offset(x,rowY)}
     if(hit.bottom>0 && hit.top<height && accessibility.size<128){
      val retry=cell.slot.takeIf{card.retryCells.any{candidate->candidate.slot.id==it.id}}
      val label=if(retry!=null)"Повторить генерацию" else StudioGenerationCard.progressLabel(cell.slot.stage,cell.slot.progress)
      accessibility+=NativeAction(1000+accessibility.size,"generation.slot.${row.id}.${cell.slot.id}",label,hit,button=retry!=null,
       action=retry?.let{slot->{onStudioRetrySlot?.invoke(row.id,slot);Unit}})
     }
    }
    for(button in card.controls){
     val hit=RectF(button.frame).apply{offset(x,rowY)}
     if(hit.bottom>0 && hit.top<height && accessibility.size<128){
      val id="generation.action.${button.action.name.lowercase(java.util.Locale.ROOT)}.${row.id}"
      accessibility+=NativeAction(1000+accessibility.size,id,button.action.title,hit,action={onStudioGenerationAction?.invoke(row.id,button.action)})
     }
    }
   }
   if(row.kind==MessageKind.VOICE){
    if(playback?.currentMessageID!=row.id){val hit=RectF(x,rowY,x+resource.width,rowY+resource.height);accessibility+=NativeAction(1000+accessibility.size,"media.audio."+row.id,resource.text,hit,action={onMessageTap?.invoke(row,hit)})}
    continue
   }
   for(line in resource.accessibleLines(max(0f,-rowY),height-rowY,max(0,128-accessibility.size))){
    val id=if(line.start==0)"message."+row.id else "message."+row.id+".text."+line.start
    val promptMenu=onStudioResultDelete!=null && row.direction==MessageDirection.OUTGOING
    val lineFrame=RectF(line.frame).apply{offset(x,rowY)}
    accessibility+=NativeAction(1000+accessibility.size,id,line.text,lineFrame,button=promptMenu,
     action=if(promptMenu){{onMessageLongPress?.invoke(row,lineFrame);Unit}}else null,
     customActions=if(onStudioGenerationAction!=null)emptyList() else listOf(NativeExtraAction(R.id.message_copy,"Копировать"){onMessageAction?.invoke(row,MessageAction.COPY)}),
     longClick=if(onStudioGenerationAction!=null)null else { { onMessageLongPress?.invoke(row,RectF(x,rowY,x+resource.width,rowY+resource.height));Unit } })
   }
  }
  scene.viewport=RectF(0f,overlayTop.toFloat(),width.toFloat(),maxOf(overlayTop,height-overlayBottom).toFloat())
  scene.imageViewport=RectF(0f,0f,width.toFloat(),height.toFloat())
  scene.imagePreparationViewport=RectF(0f,-before.toFloat(),width.toFloat(),(height+after).toFloat())
  scene.apply(SceneSnapshot(scene.ticket,fragments))
  for((id,itemIndex,rect,label) in scene.loadActions())if(rect.bottom>0 && rect.top<height)accessibility+=NativeAction(1000+accessibility.size,"media.load.$id"+(if(itemIndex==0)""else ".$itemIndex"),label,rect,action={scene.performLoadAction(rect.centerX(),rect.centerY())})
  updateInlinePlayer();nodes=accessibility
  val now=android.os.SystemClock.uptimeMillis()
  if(now-lastAxNotice>=150){lastAxNotice=now;ax.notifyChanged()}else if(!axNoticePending){axNoticePending=true;postDelayed({axNoticePending=false;if(!closed){lastAxNotice=android.os.SystemClock.uptimeMillis();ax.notifyChanged()}},150)}
  if(MetricsRuntime.enabled){android.os.Trace.endSection();MetricsRuntime.recorder.end("scene_cpu_ms",renderToken);MetricsRuntime.recorder.peak("loaded_messages",loaded.size);MetricsRuntime.recorder.peak("history_rows",index.rows.size);MetricsRuntime.recorder.peak("scene_fragments",scene.metrics.liveFragments);MetricsRuntime.recorder.peak("active_gif_owners",scene.metrics.activeFrameClocks);MetricsRuntime.recorder.peak("text_cache_bytes",cache.bytes);MetricsRuntime.recorder.peak("image_owned_bytes",viewerMedia?.bytesUsed ?: 0)}
  updateDisplayHint();onSceneChanged?.invoke();invalidate();publishInitialFrame(fragments)
 }
 /**
  * Поверх выбранных сообщений добавляет полупрозрачное выделение по их готовым размерам
  * После закрытия экрана не обращается к прежнему содержимому
  */
 override fun onDraw(c:Canvas){if(!closed){scene.draw(c);days.draw(c)
  if(selectedMessageIDs.isNotEmpty()){val tint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.argb(46,0,122,255)}
   for(id in selectedMessageIDs){val row=loaded[id] ?: continue;val resource=cache.value(key(row)) ?: continue;val y=((index.top(id) ?: continue)-offset).toFloat();if(y+resource.height>=0 && y<=height){val x=rowLeft(row,resource);c.drawRoundRect(x-3*d,y,x+resource.width+3*d,y+resource.height,16*d,16*d,tint)}}
  }
 }}
 /**
  * Выбирает аппаратный или обычный способ по возможностям переданной области рисования
  * Дополняет неподвижную сцену подложками видео и встроенного проигрывателя в их текущих координатах
  */
 fun drawBackdrop(c:Canvas){if(!closed){if(c.isHardwareAccelerated){scene.draw(c);days.draw(c)}else{scene.drawSoftware(c);days.drawSoftware(c)};scene.drawMovingBackdrop(c);playerSurface?.takeIf{it.parent===this}?.let{c.save();c.translate(it.left.toFloat(),it.top.toFloat());it.drawBackdrop(c);c.restore()}}}
 /**
  * Он использует актуальные видимые сообщения и доступные действия, собранные при подготовке сцены
  * Отдельное дерево настоящих представлений для каждого текстового фрагмента здесь не создаётся
  */
 override fun getAccessibilityNodeProvider():AccessibilityNodeProvider=ax
 /**
  * Разбирает касания истории на прокрутку, долгое нажатие и обычное действие по сообщению
  * При движении меняет только положение ограниченной сцены, а после отпускания запускает инерцию по скорости жеста
  * Для нажатия различает повтор конкретной генерации, кнопки результата, режим выделения и выбранное вложение
  * После завершения или отмены жеста освобождает измеритель скорости и передаёт место чтения
  */
 override fun onTouchEvent(event:MotionEvent):Boolean{
  gestures.onTouchEvent(event)
  when(event.actionMasked){
   MotionEvent.ACTION_DOWN->{scroller.abortAnimation();velocity?.recycle();velocity=VelocityTracker.obtain();velocity?.addMovement(event);lastY=event.y;downY=event.y;dragging=false;longPressHandled=false;parent.requestDisallowInterceptTouchEvent(true)}
   MotionEvent.ACTION_MOVE->{
    velocity?.addMovement(event)
    if(abs(event.y-downY)>ViewConfiguration.get(context).scaledTouchSlop)dragging=true
    if(dragging){pendingAnchor=null;offset=(offset+lastY-event.y).coerceIn(0.0,max(0.0,index.totalHeight-height));schedule()}
    lastY=event.y
   }
   MotionEvent.ACTION_UP->{
    velocity?.addMovement(event);velocity?.computeCurrentVelocity(1000,ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat())
    if(dragging){val speed=-(velocity?.yVelocity ?: 0f).toInt();lastFlingY=0;scroller.fling(0,0,0,speed,0,0,Int.MIN_VALUE/2,Int.MAX_VALUE/2);postInvalidateOnAnimation()}
    else if(!longPressHandled && !scene.performLoadAction(event.x,event.y))hit(event.x,event.y)?.let{(row,frame)->
     if(onSelectionTap?.invoke(row)!=true){
      val prepared=cache.value(key(row))
      val x=event.x-frame.left
      val y=event.y-frame.top
      val card=prepared?.generationCard
      if(card!=null){
       val retry=card.retryCells.firstOrNull{it.frame.contains(x,y)}
       val control=card.controls.firstOrNull{it.frame.contains(x,y)}
       val selected=if(card.isBatch)prepared.mediaItemRects.indexOfFirst{it.contains(x,y)}else -1
       if(retry!=null)onStudioRetrySlot?.invoke(row.id,retry.slot)
       else if(control!=null)onStudioGenerationAction?.invoke(row.id,control.action)
       else if(selected>=0)activateMedia(row,frame,selected)
       return@let
      }
      val button=prepared?.mediaActions?.firstOrNull{it.frame.contains(x,y)}
      if(button!=null){
       val hit=RectF(button.frame).apply{offset(frame.left,frame.top)}
       performMediaAction(button.action,row,hit)
      }else{
       if(onStudioResultDelete!=null && row.direction==MessageDirection.OUTGOING && row.kind==MessageKind.TEXT){
        onMessageLongPress?.invoke(row,frame);return@let
       }
       val selected=prepared?.mediaItemRects?.indexOfFirst{it.contains(x,y)}?.coerceAtLeast(0) ?: 0
       activateMedia(row,frame,selected)
      }
     }
    }
    dragging=false;velocity?.recycle();velocity=null;updateDisplayHint();onAnchor?.invoke(readingAnchor())
   }
   MotionEvent.ACTION_CANCEL->{dragging=false;velocity?.recycle();velocity=null;updateDisplayHint();onAnchor?.invoke(readingAnchor())}
  };return true
 }
 /**
  * Продолжает инерционное движение по разнице текущего и прежнего положения системного расчёта
  * Ограничивает смещение границами истории и останавливает инерцию при достижении края
  * Согласует сцену в новом положении и сохраняет место чтения после завершения движения
  */
 override fun computeScroll(){
  if(scroller.computeScrollOffset()){pendingAnchor=null;val delta=scroller.currY-lastFlingY;lastFlingY=scroller.currY
   val proposed=offset+delta;offset=proposed.coerceIn(0.0,max(0.0,index.totalHeight-height));if(offset!=proposed)scroller.abortAnimation()
   queued=false;render();postInvalidateOnAnimation();if(scroller.isFinished)onAnchor?.invoke(readingAnchor())}
 }
 /**
  * Перемещает историю к сообщению, которое присутствует в текущем указателе
  * Сохраняет его как новое место чтения без следования за концом чата
  * Применяет положение, планирует подготовку и сообщает наружу о выбранном месте
  */
 fun goTo(messageID:MessageID){
  if(index.position(messageID)==null)return
  scroller.abortAnimation();pendingAnchor=ReadingAnchor(messageID=messageID,screenDelta=if(index.position(messageID)==0)max(0.0,(index.top(messageID) ?: 0.0)-overlayTop)else 0.0,followsBottom=false);setGeometry(pendingAnchor!!);schedule();onAnchor?.invoke(pendingAnchor!!)
 }
 /**
  * Проверяет попадание внутрь его фактической области, а не только совпадение высоты
  * Возвращает само сообщение и его область относительно видимого экрана
  */
 private fun hit(x:Float,y:Float):Pair<Message,RectF>?{
  val id=index.anchorAt(offset+y).messageID;val row=loaded[id] ?: return null;val top=index.top(row.id) ?: return null;val resource=cache.value(key(row)) ?: return null
  val left=rowLeft(row,resource)
  val r=RectF(left,(top-offset).toFloat(),left+resource.width,(top-offset+resource.height).toFloat())
  return if(r.contains(x,y))row to r else null
 }
 /**
  * Освобождает сцены, очереди подготовки и связанные медиа через общий метод закрытия
  * После очистки передаёт событие родительскому элементу
  */
 override fun onDetachedFromWindow(){close();super.onDetachedFromWindow()}
 /**
  * Уменьшает допустимый объём сохранённой текстовой разметки до шестнадцати мегабайт
  * Просит хранилище удалить лишние восстанавливаемые записи до этого предела
  * Сами сообщения остаются в истории и могут быть подготовлены снова при необходимости
  */
 fun trimMemory(){cache.limit=16*1024*1024;cache.trim()}
 /**
  * Завершает работу экрана и отменяет незаконченные открытия, загрузки и подготовку текста
  * Закрывает отдельный просмотр, освобождает сцену, страницы и сохранённую разметку
  * Снимает только принадлежащие экрану связи с воспроизведением и обработчиками действий
  * Повторный вызов не выполняет полную очистку заново
  */
 fun close(){
  mediaExport.close()
  onStudioGenerationAction=null
  if(closed)return;closed=true;finishInitialFrame(Result.failure(RepoException(RepoError.CANCELLED)));finishOpening(Result.failure(RepoException(RepoError.CANCELLED)));val gallery=mediaGallery;mediaGallery=null;gallery?.close(returnToChat=false);displayPolicy?.close();val viewer=photoViewer;photoViewer=null;viewer?.close();studioPreview?.close();if(playback?.presentationOwner===playbackOwner)playerSurface?.detachPlayer();(playerSurface?.parent as? ViewGroup)?.removeView(playerSurface);playback?.let{c->
   if(c.onChange===ownedPlayerChange)c.onChange=null
   if(c.onDetachOutput===ownedPlayerDetach)c.onDetachOutput=null
   if(c.presentationOwner===playbackOwner){c.setInlineVisible(false);c.stop()}
  };ownedPlayerChange=null;ownedPlayerDetach=null;nodes=emptyList();index=HistoryIndex();onMessageTap=null;onMessageLongPress=null;onMessageAction=null;onSelectionTap=null;generation++;pages?.close();inFlight.values.forEach{it.cancel()};inFlight.clear();worker.close();buildPool.shutdownNow();cache.clear();loaded=emptyMap();scene.close();viewerMedia?.releaseIdleImages();days.close();onSceneChanged=null;scroller.abortAnimation();velocity?.recycle();velocity=null
 }
}
