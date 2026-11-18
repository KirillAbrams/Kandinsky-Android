package dev.kirill.kandinsky.chat.rendering
import android.graphics.*
import android.text.*
import dev.kirill.kandinsky.chat.domain.*
import kotlin.math.*
data class TextLayoutKey(val messageID:MessageID,val revision:Int,val width:Int,val fontSize:Int,val scale:Int,val density:Float=fontSize/17f,val mediaExpanded:Boolean=false,val roundDiameter:Int?=null)
data class FragmentID(val messageID:MessageID,val part:Int)
data class TextBlock(val firstLine:Int,val endLine:Int,val offsetY:Float,val height:Float,val startUTF16:Int)



/** Ограниченная сетка альбома также используется при предварительном просмотре выбранных вложений */
object MediaMosaic {
 /**
  * Размещает от двух до десяти вложений в альбоме и возвращает отдельную область каждого изображения
  * Подбирает число изображений в строках по их пропорциям и округляет границы до пикселей
  * Для четырёх результатов по отдельному признаку оставляет две равные строки, чтобы сохранить сетку ожидания
  */
 fun layout(sizes:List<Pair<Int,Int>>,width:Float,spacing:Float=1f,maxColumns:Int=3,scale:Float=1f,balancedFour:Boolean=false):List<RectF>{
  if(sizes.size !in 2..10 || !width.isFinite() || width<40)return emptyList()
  val ratios=sizes.map{(it.first.toDouble()/maxOf(1,it.second)).coerceIn(.667,1.7)}
  val columns=maxColumns.coerceIn(2,3);val target=width*4.0/3
  var best=emptyList<Int>();var cost=Double.MAX_VALUE
  /**
   * Оценивает один вариант разбиения альбома на строки по общей высоте и пропорциям изображений
   * Увеличивает штраф за слишком низкие строки и за уменьшение числа изображений к низу альбома
   * Запоминает вариант, который ближе остальных к выбранной высоте всей сетки
   */
  fun score(rows:List<Int>){
   var index=0;val heights=rows.map{count->val sum=ratios.subList(index,index+count).sum();index+=count;(width-(count-1)*spacing)/sum}
   var value=kotlin.math.abs(heights.sum()+(rows.size-1)*spacing-target)
   if(rows.zipWithNext().any{it.first>it.second})value*=1.5
   if(heights.any{it<width*.18})value+=width
   if(value<cost){best=rows;cost=value}
  }
  /**
   * Перебирает допустимое количество изображений в очередной строке альбома
   * Ограничивает число строк и передаёт полностью заполненный вариант для оценки
   * Так поиск остаётся ограниченным даже при максимально допустимом числе вложений
   */
  fun visit(remaining:Int,rows:List<Int>){
   if(remaining==0){score(rows);return};if(rows.size>=maxOf(4,(sizes.size+columns-1)/columns))return
   for(count in 1..minOf(columns,remaining))visit(remaining-count,rows+count)
  }
  if(balancedFour && sizes.size==4){best=listOf(2,2)}
  else if(sizes.size==2){val raw=sizes.map{it.first.toDouble()/maxOf(1,it.second)};best=if(raw.all{it>1.2} && kotlin.math.abs(raw[0]-raw[1])<.2)listOf(1,1)else listOf(2)}else visit(sizes.size,emptyList())
  val result=mutableListOf<RectF>();var index=0;var y=0.0;val pixelScale=maxOf(1f,scale).toDouble()
  for(count in best){
   val sum=ratios.subList(index,index+count).sum();val height=kotlin.math.floor((width-(count-1)*spacing)/sum*pixelScale)/pixelScale;var x=0.0
   for(column in 0 until count){
    val right=if(column==count-1)width.toDouble() else kotlin.math.floor((x+ratios[index]*height)*pixelScale)/pixelScale
    result+=RectF(x.toFloat(),y.toFloat(),maxOf(x+1,right).toFloat(),(y+maxOf(1.0,height)).toFloat());x=right+spacing;index++
   };y+=height+spacing
  };return result
 }
}
enum class KandinskyMediaAction(val title:String,private val iconResource:Int) {
 SAVE("Сохранить",dev.kirill.kandinsky.chat.R.drawable.kandinsky_result_save),
 SHARE("Поделиться",dev.kirill.kandinsky.chat.R.drawable.kandinsky_result_share),
 EXPAND("На весь экран",dev.kirill.kandinsky.chat.R.drawable.kandinsky_result_expand);

 /**
  * Рисует заранее загруженный значок действия под результатом генерации
  * Размещает его по центру верхней части кнопки с учётом плотности экрана
  * Если изображения значков ещё не подготовлены, завершает вызов без рисования
  */
 fun draw(canvas:Canvas,frame:RectF,unit:Float) {
  val icon=icons[this] ?: return
  val half=6.75f*unit
  val x=frame.centerX()
  val y=frame.centerY()-6*unit
  canvas.drawBitmap(icon,null,RectF(x-half,y-half,x+half,y+half),iconPaint)
 }

 companion object {
  @Volatile private var icons:Map<KandinskyMediaAction,Bitmap> = emptyMap()
  private val iconPaint=Paint(Paint.FILTER_BITMAP_FLAG).apply{
   colorFilter=PorterDuffColorFilter(Color.rgb(97,97,97),PorterDuff.Mode.SRC_IN)
  }

  /**
   * Один раз загружает из ресурсов значки действий под готовым результатом
   * Последующие вызовы используют уже сохранённые изображения и не читают их заново
   * Защита метода не позволяет двум потокам одновременно создавать один и тот же набор
   */
  @Synchronized fun load(resources:android.content.res.Resources){
   if(icons.isEmpty())icons=entries.associateWith{BitmapFactory.decodeResource(resources,it.iconResource)}
  }
 }
}

class PreparedText(val message:Message,val key:TextLayoutKey) {
 private val visual=message.attachments.isNotEmpty() && message.kind in listOf(MessageKind.PHOTO,MessageKind.VIDEO,MessageKind.ROUND,MessageKind.GIF,MessageKind.STICKER)
 val text=if(message.kind==MessageKind.TEXT)message.text else if(visual)message.caption else label(message)
 private val unit=key.density.coerceAtLeast(0.1f)
 private val isStudioPrompt=message.kind==MessageKind.TEXT && message.direction==MessageDirection.OUTGOING
 private val emojiSize=if(message.kind==MessageKind.TEXT && !isStudioPrompt)largeEmojiSize(message.text,unit)else null
 private val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply{
  textSize=emojiSize ?: key.fontSize*(if(isStudioPrompt)16f/17 else 1f)
  color=if(isStudioPrompt)KandinskyPalette.studioInk else Color.BLACK
  typeface=Typeface.DEFAULT
 }
 private val promptLineHeight=ceil(paint.textSize*1.4f)
 private val stampSize=(if(isStudioPrompt)10 else 12)*unit
 private val promptStampColor=Color.rgb(86,112,100)
 private val promptBubble:Path?
 private var captionStampInline=false
 private val isStudioResult=message.studioGeneration?.slots.isNullOrEmpty() && message.direction==MessageDirection.INCOMING && message.attachments.size==1 &&
  (message.kind==MessageKind.PHOTO || message.kind==MessageKind.VIDEO)
 private val studioBadgeText=if(isStudioResult)Paint(Paint.ANTI_ALIAS_FLAG).apply{textSize=11*unit;color=Color.WHITE}else null
 private val studioBadgeFill=if(isStudioResult)Paint(Paint.ANTI_ALIAS_FLAG).apply{color=0x99000000.toInt()}else null
 val mediaCornerRadius get()=if(isStudioResult)12*unit else if(message.kind==MessageKind.STICKER)0f else 15*unit
 val mediaBottomSquare get()=mediaRect!=null && message.kind!=MessageKind.ROUND && message.kind!=MessageKind.STICKER && text.isNotEmpty()
 private val stamp=android.text.format.DateFormat.format("HH:mm",message.savedAtMs).toString()+(if(message.direction==MessageDirection.OUTGOING)when(message.delivery){MessageDelivery.PENDING->" ◷";MessageDelivery.READ->if(isStudioPrompt)"" else " ✓✓";MessageDelivery.FAILED->" !";else->if(isStudioPrompt)"" else " ✓"}else "")
 val generationCard=message.studioGeneration?.let{StudioGenerationCard(it,message,key,stamp)}
 private val stampPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{textSize=stampSize;color=KandinskyPalette.secondary;typeface=Typeface.DEFAULT}
 private val compact=!isStudioPrompt && emojiSize==null && message.kind==MessageKind.TEXT && !text.contains("\n") && Layout.getDesiredWidth(text,paint)+stampPaint.measureText(stamp)+36*unit<=key.width
 private val textInset=if(isStudioPrompt)14*unit else if(emojiSize!=null)0f else 10*unit
 private val trailingTextInset=if(isStudioPrompt)20*unit else 10*unit
 private val albumFrames=if(visual && message.kind in listOf(MessageKind.PHOTO,MessageKind.VIDEO) && message.attachments.size>1)MediaMosaic.layout(message.attachments.map{if(it.orientation>=5)it.rawHeight to it.rawWidth else it.rawWidth to it.rawHeight},max(40*unit,key.width-4*unit),spacing=unit,scale=1f,balancedFour=message.attachments.first().id.contains("-result-"))else emptyList()
 private val mediaSize:Pair<Float,Float>?=if(albumFrames.isNotEmpty())albumFrames.maxOf{it.right} to albumFrames.maxOf{it.bottom}else if(visual){
  val a=message.attachments.first()
  if(message.kind==MessageKind.ROUND){val d=max(40*unit,min(key.width,key.roundDiameter ?: key.width).toFloat());d to d}else{
   val rw=max(1,if(a.orientation>=5)a.rawHeight else a.rawWidth).toFloat();val rh=max(1,if(a.orientation>=5)a.rawWidth else a.rawHeight).toFloat()
   val maxW=max(40*unit,if(isStudioResult)min(300*unit,key.width-4*unit)else key.width-4*unit)
   val maxH=if(isStudioResult)(if(message.kind==MessageKind.VIDEO){if(rw>=rh)250 else 360}else 380)*unit else maxW+100*unit
   val factor=min(maxW/rw,maxH/rh)
   if(message.kind==MessageKind.STICKER){val fit=minOf(180*unit,maxW)/maxOf(rw,rh);rw*fit to rh*fit}
   else if(isStudioResult)rw*factor to rh*factor
   else min(maxW,max(min(120*unit,maxW),rw*factor)) to min(maxH,max(120*unit,rh*factor))
  }
 }else null
 val width=generationCard?.width ?: if(isStudioPrompt){
  min(key.width.toFloat(),ceil(max(Layout.getDesiredWidth(text,paint),stampPaint.measureText(stamp)))+textInset+trailingTextInset)
 }else mediaSize?.let{if(message.kind==MessageKind.ROUND)min(key.width.toFloat(),it.first+36*unit) else if(message.kind==MessageKind.STICKER)max(min(76*unit,key.width.toFloat()),it.first+4*unit)else it.first+4*unit} ?: min(key.width.toFloat(),max((if(emojiSize!=null)76 else 114)*unit,ceil(Layout.getDesiredWidth(text,paint))+(if(compact)stampPaint.measureText(stamp)+36*unit else textInset+12*unit)))
 val mediaRect:RectF?=generationCard?.takeIf{it.isBatch}?.image ?: mediaSize?.let{val x=if(message.kind==MessageKind.ROUND)0f else 2*unit;val y=if(message.kind==MessageKind.ROUND)4*unit else 2*unit;RectF(x,y,x+it.first,y+it.second)}
 val mediaItemRects:List<RectF> = generationCard?.takeIf{it.isBatch}?.resultFrames ?: mediaRect?.let{media->if(albumFrames.isEmpty())listOf(RectF(media))else albumFrames.map{RectF(it).apply{offset(media.left,media.top)}}} ?: emptyList()
 data class MediaButton(val action:KandinskyMediaAction,val frame:RectF)
 val mediaActions:List<MediaButton> = if(isStudioResult && text.isEmpty() && mediaRect!=null){
  val actions=if(message.kind==MessageKind.VIDEO)KandinskyMediaAction.entries else listOf(KandinskyMediaAction.SAVE,KandinskyMediaAction.SHARE)
  val buttonWidth=min(48*unit,mediaRect.width()/actions.size)
  val top=mediaRect.bottom+2*unit
  val bottom=top+max(48*unit,key.fontSize*14f/17f*1.45f+16*unit)
  actions.mapIndexed{index,action->
   val left=mediaRect.left+index*buttonWidth
   MediaButton(action,RectF(left,top,left+buttonWidth,bottom))
  }
 }else emptyList()
 private val textTop=(mediaRect?.bottom ?: 0f)+(if(isStudioPrompt)9 else if(emojiSize!=null)16 else 8)*unit
 private var layout:StaticLayout?=if(generationCard!=null)null else StaticLayout.Builder.obtain(if(text.isEmpty())" " else text,0,max(1,text.length),paint,max(20,(width-textInset-trailingTextInset).toInt()))
  .setIncludePad(false)
  .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
  .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
  .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
  .apply {
   if(isStudioPrompt){
    val metrics=paint.fontMetrics
    setLineSpacing(max(0f,promptLineHeight-(metrics.descent-metrics.ascent)),1f)
   }
  }.build()
 val blocks:List<TextBlock>
 val byteCost:Int
 val height:Float get()=blocks.lastOrNull()?.let{it.offsetY+it.height} ?: 44*unit
 var closeCount=0;private set
 init {
  val card=generationCard
  if(card!=null){
   blocks=listOf(TextBlock(0,0,0f,card.height,0))
   promptBubble=null
   byteCost=1024+card.status.length*2
  }else{
  val l=layout!!
  captionStampInline=visual && message.kind!=MessageKind.ROUND && text.isNotEmpty() && l.getParagraphDirection(l.lineCount-1)>0 && l.getLineWidth(l.lineCount-1)+stampPaint.measureText(stamp)+8*unit<=width-textInset-11*unit
  val b=mutableListOf<TextBlock>();var first=0;var offset=0f
  while(first<l.lineCount){
   var end=first
   while(end<l.lineCount && end-first<16 && (end==first || l.getLineBottom(end)+textTop-offset<=400))end++
   val bottom=when {
    end<l.lineCount -> l.getLineTop(end)+textTop
    isStudioPrompt -> max(l.height.toFloat(),promptLineHeight*l.lineCount)+textTop+21*unit
    mediaActions.isNotEmpty() -> mediaActions.last().frame.bottom
    visual && text.isEmpty() -> mediaRect!!.bottom+(if(message.kind==MessageKind.STICKER)26 else 2)*unit
    emojiSize!=null -> l.height+textTop+26*unit
    captionStampInline -> l.height+textTop+6*unit
    compact -> max(33.333f*unit,l.height+textTop+5*unit)
    else -> max(44*unit,l.height+textTop+18*unit)
   }
   b+=TextBlock(first,end,offset,bottom-offset,l.getLineStart(first));first=end;offset=bottom
  }
  blocks=b
  promptBubble=if(isStudioPrompt)makePromptBubble() else null
  byteCost=text.length*2+l.lineCount*256+blocks.size*64+512+message.attachments.sumOf{(it.tinyPreview?.length ?: 0)*2+(it.waveform?.size ?: 0)*4}
  }
 }
 /**
  * Закругляет углы и добавляет небольшой выступ справа внизу
  * Все отступы умножает на плотность экрана, чтобы форма сохраняла пропорции на разных устройствах
  */
 private fun makePromptBubble():Path {
  val corner=16*unit
  val join=5*unit
  return Path().apply{
   addRoundRect(0f,0f,width-6*unit,height,floatArrayOf(corner,corner,corner,corner,join,join,corner,corner),Path.Direction.CW)
   moveTo(width-10*unit,height-12*unit)
   quadTo(width-5*unit,height-3*unit,width,height-unit)
   quadTo(width-5*unit,height+unit,width-13*unit,height-2*unit)
   close()
  }
 }
 /**
  * Возвращает положение этой строки в тексте и её смещение относительно выбранной точки
  * Эти данные позволяют сохранить место чтения после изменения ширины или размера шрифта
  */
 fun lineAnchor(y:Float):Pair<Int,Float>?{val l=layout ?: return null;val line=l.getLineForVertical(max(0,(y-textTop).toInt()));return l.getLineStart(line) to (l.getLineTop(line)+textTop-y)}
 /**
  * Возвращает верхнюю границу строки, содержащей заданную позицию в тексте
  * Перед поиском ограничивает позицию длиной сообщения, затем добавляет верхний отступ текста
  * Если подготовленная разметка уже освобождена, возвращает отсутствие положения
  */
 fun yForUTF16(value:Int):Float?=layout?.let{it.getLineTop(it.getLineForOffset(value.coerceIn(0,text.length)))+textTop}
 data class AccessibleLine(val start:Int,val text:String,val frame:RectF)
 /**
  * Собирает непустые строки текста, которые пересекают переданный участок сообщения
  * Для каждой строки возвращает текст, его начало и область на экране, ограничивая число результатов
  * Вместо строк обычного сообщения для карточки генерации возвращает её видимую подпись состояния
  */
 fun accessibleLines(lower:Float,upper:Float,limit:Int):List<AccessibleLine>{
  if(limit<=0)return emptyList()
  generationCard?.let{card->
   val frame=card.statusFrame
   return if(frame.bottom>lower && frame.top<upper)listOf(AccessibleLine(0,card.status,frame)) else emptyList()
  }
  val l=layout ?: return emptyList()
  val first=l.getLineForVertical(max(0,(lower-textTop).toInt()));val last=l.getLineForVertical(max(0,(upper-textTop).toInt()))
  val result=mutableListOf<AccessibleLine>()
  for(line in first..last){
   if(result.size>=limit)break
   val start=l.getLineStart(line);val end=min(text.length,l.getLineEnd(line));val value=if(start<end)text.substring(start,end).trimEnd('\n','\r') else ""
   val y=l.getLineTop(line)+textTop
   if(value.isNotEmpty() && y<upper && l.getLineBottom(line)+textTop>lower)result+=AccessibleLine(start,value,RectF(textInset,y,width-trailingTextInset,l.getLineBottom(line)+textTop))
  };return result
 }
 /**
  * Рисует выбранный блок подготовленного сообщения, ограничивая текст его областью
  * Добавляет фон и в последнем блоке время со состоянием отправки
  * Для запроса, крупных эмодзи и карточки генерации применяет отдельное оформление
  * После освобождения текстовой разметки завершает вызов без повторного её создания
  */
 fun draw(part:Int,c:Canvas){
  generationCard?.let{if(part==0 && closeCount==0)it.drawStatic(c);return}
  val l=layout ?: return
  if(part !in blocks.indices)return
  val b=blocks[part];val bg=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=if(message.direction==MessageDirection.OUTGOING)KandinskyPalette.studioOutgoing else Color.WHITE}
  if(promptBubble!=null){
   c.save()
   c.translate(0f,-b.offsetY)
   c.drawPath(promptBubble,bg)
   c.restore()
  }else if(message.kind!=MessageKind.ROUND && message.kind!=MessageKind.STICKER && emojiSize==null && mediaActions.isEmpty())c.drawRoundRect(0f,-b.offsetY,width,height-b.offsetY,(if(visual)17 else 14)*unit,(if(visual)17 else 14)*unit,bg)

  c.save();c.clipRect(0f,0f,width,b.height);c.translate(textInset,textTop-b.offsetY);l.draw(c);c.restore()
  if(part==blocks.lastIndex){
   if(emojiSize!=null){
    val right=width-2*unit;val left=max(0f,right-stampPaint.measureText(stamp)-10*unit);bg.color=0x5C000000;c.drawRoundRect(left,b.height-22*unit,right,b.height-4*unit,9*unit,9*unit,bg);stampPaint.color=Color.WHITE;c.drawText(stamp,left+5*unit,b.height-8*unit,stampPaint)
   }else if(message.kind!=MessageKind.STICKER && !(visual && text.isEmpty())){
    stampPaint.color=when{
     isStudioPrompt -> promptStampColor
     visual && message.direction==MessageDirection.OUTGOING -> Color.rgb(112,177,92)
     else -> KandinskyPalette.secondary
    }
    val baseline=when{
     isStudioPrompt -> b.height-11*unit
     captionStampInline -> textTop+l.getLineBaseline(l.lineCount-1)-b.offsetY
     compact -> textTop+l.getLineBaseline(0)
     else -> b.height-7*unit
    }
    val stampInset=if(isStudioPrompt)trailingTextInset else 11*unit
    c.drawText(stamp,width-stampPaint.measureText(stamp)-stampInset,baseline,stampPaint)
   }
  }
 }
 /**
  * У готового результата генерации использует отдельное оформление и добавляет кнопки действий при обычном показе
  * У сетки генераций пропускает этот общий слой, поскольку её ячейки рисуют состояние отдельно
  */
 fun drawMediaStamp(c:Canvas,manual:Boolean=false){
  if(generationCard?.isBatch==true)return
  val rect=mediaRect ?: return
  if(message.kind==MessageKind.ROUND && key.mediaExpanded && !manual)return
  if(isStudioResult){
   drawStudioMediaBadges(c,rect)
   if(!manual)for(button in mediaActions)button.action.draw(c,button.frame,unit)
   return
  }
  stampPaint.color=Color.WHITE
  /**
   * Рисует одну округлую полупрозрачную подпись поверх вложения
   * Подбирает ширину по тексту и оставляет одинаковые внутренние отступы
   * Используется для времени и длительности, чтобы буквы читались на светлом и тёмном изображении
   */
  fun pill(label:String,x:Float,y:Float){val w=stampPaint.measureText(label);val box=RectF(x,y,x+w+10*unit,y+18*unit);c.drawRoundRect(box,9*unit,9*unit,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=0x5C000000});c.drawText(label,x+5*unit,box.bottom-4*unit,stampPaint)}
  if(message.kind==MessageKind.STICKER){pill(stamp,max(0f,width-stampPaint.measureText(stamp)-10*unit),rect.bottom+4*unit);return}
  val seconds=max(0L,(message.attachments.firstOrNull()?.durationMs ?: 0)/1000)
  val info=if(message.kind==MessageKind.GIF)"GIF" else String.format(java.util.Locale.ROOT,if(message.kind==MessageKind.ROUND)"%02d:%02d" else "%d:%02d",seconds/60,seconds%60)
  if(message.kind==MessageKind.VIDEO || message.kind==MessageKind.GIF)pill(info,rect.left+6*unit,rect.top+6*unit)
  if(message.kind==MessageKind.ROUND && !manual){
   pill(info,rect.left+3*unit,rect.bottom-22*unit)
   val x=rect.centerX();val y=rect.top+14*unit;val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=0x47000000};c.drawCircle(x,y,12*unit,p);p.color=Color.WHITE
   c.drawPath(Path().apply{moveTo(x-7*unit,y-3*unit);lineTo(x-3*unit,y-3*unit);lineTo(x+unit,y-7*unit);lineTo(x+unit,y+7*unit);lineTo(x-3*unit,y+3*unit);lineTo(x-7*unit,y+3*unit);close()},p)
   p.style=Paint.Style.STROKE;p.strokeWidth=1.7f*unit;p.strokeCap=Paint.Cap.ROUND;c.drawLine(x+4*unit,y-3*unit,x+9*unit,y+3*unit,p);c.drawLine(x+9*unit,y-3*unit,x+4*unit,y+3*unit,p)
  }
  if(text.isEmpty()){val w=stampPaint.measureText(stamp);pill(stamp,if(message.kind==MessageKind.ROUND)min(width-w-12*unit,rect.right+28*unit-w-10*unit)else rect.right-w-16*unit,if(message.kind==MessageKind.ROUND)rect.bottom-22*unit else rect.bottom-24*unit)}
 }
 /**
  * У видео выводит длительность сверху слева, а у результата без текстовой подписи добавляет время снизу справа
  * Использует подготовленные краски для одинакового цвета букв и подложек
  */
 private fun drawStudioMediaBadges(canvas:Canvas,rect:RectF) {
  val textPaint=studioBadgeText ?: return
  val fillPaint=studioBadgeFill ?: return
  val badgeHeight=17.2f*unit
  /**
   * Рисует одну подпись готового результата в переданной области
   * Сначала выводит тёмную подложку со скруглением, затем белый текст с внутренними отступами
   * Высоту и положение букв берёт из общих размеров подписей результата
   */
  fun draw(label:String,left:Float,top:Float,width:Float) {
   canvas.drawRoundRect(left,top,left+width,top+badgeHeight,5*unit,5*unit,fillPaint)
   canvas.drawText(label,left+5*unit,top+badgeHeight-4*unit,textPaint)
  }
  if(message.kind==MessageKind.VIDEO){
   val seconds=max(0L,(message.attachments.first().durationMs ?: 0)/1000)
   val duration=String.format(java.util.Locale.ROOT,"%d:%02d",seconds/60,seconds%60)
   draw(duration,rect.left+6*unit,rect.top+6*unit,textPaint.measureText(duration)+10*unit)
  }
  if(text.isEmpty()){
   val width=textPaint.measureText(stamp)+10*unit
   draw(stamp,rect.right-6*unit-width,rect.bottom-6*unit-badgeHeight,width)
  }
 }
 /**
  * Освобождает подготовленные строки сообщения и отмечает, что объект уже закрыт
  * Повторный вызов не освобождает данные второй раз и не увеличивает счётчик закрытия
  * После этого отрисовка больше не использует прежнюю текстовую разметку
  */
 @Synchronized fun close(){if(closeCount==0){closeCount=1;layout=null}}
 /**
  * Отклоняет обычный текст и неоднозначные знаки без нужного признака представления
  * Для набора эмодзи выбирает увеличенный размер шрифта по их количеству и плотности экрана
  */
 private fun largeEmojiSize(value:String,unit:Float):Float?{
  if(value.isEmpty())return null
  var head=0;while(head<value.length && (value[head]==' ' || value[head]=='\n'))head++
  if(head==value.length || !android.icu.lang.UCharacter.hasBinaryProperty(value.codePointAt(head),android.icu.lang.UProperty.EMOJI))return null
  val iterator=android.icu.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT);iterator.setText(value)
  var start=iterator.first();var end=iterator.next();var count=0
  while(end!=android.icu.text.BreakIterator.DONE){
   val glyph=value.substring(start,end)
   if(glyph!=" " && glyph!="\n"){
    val first=glyph.codePointAt(0)
    if(!android.icu.lang.UCharacter.hasBinaryProperty(first,android.icu.lang.UProperty.EMOJI))return null
    if(!android.icu.lang.UCharacter.hasBinaryProperty(first,android.icu.lang.UProperty.EMOJI_PRESENTATION) && !glyph.contains('\uFE0F') && !glyph.contains('\u20E3') && first<=0x238C)return null
    count++
   };start=end;end=iterator.next()
  }
  if(count==0)return null
  val multiplier=when(count){1,2->.34f;3->.28f;4->.22f;else->.19f}
  return ceil(120*multiplier*unit)+ceil(4*unit)
 }

 /**
  * Составляет текстовую основу сообщения с вложением
  * Для прежних видов вложений сохраняет читаемое название и авторскую подпись
  * Не создаёт отдельные элементы записи или воспроизведения звука
  */
 private fun label(m:Message):String {

  val title=when(m.kind){MessageKind.PHOTO->"▧ Фотография";MessageKind.VIDEO->"▶ Видео";MessageKind.VOICE->"◉ Голосовое сообщение";MessageKind.ROUND->"◉ Видеокружочек";MessageKind.GIF->"GIF";else->"Стикер"}
  return title+(if(m.caption.isEmpty())"" else "\n"+m.caption)
 }
}
