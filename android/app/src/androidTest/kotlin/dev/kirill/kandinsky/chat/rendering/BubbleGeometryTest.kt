package dev.kirill.kandinsky.chat.rendering
import dev.kirill.kandinsky.chat.domain.*
import org.junit.Test
import org.junit.Assert.*
class BubbleGeometryTest{
 /**
  * Проверяет компактную высоту короткого текста и геометрию фото с нижними действиями сохранения и передачи
  * Для видео требует полный набор предусмотренных действий
  * По пикселям наложенных отметок проверяет прозрачные отступы и действительный рисунок кнопки
  */
 @Test fun compactTextAndPhotoActionFooter(){
  val row=Message(1,2,OrderKey(1,1),MessageDirection.INCOMING,1,MessageKind.TEXT,"Ndjfjd","",null,1,emptyList())
  val t=PreparedText(row,TextLayoutKey(1,1,280,17,1));assertTrue(t.height<=36);assertEquals(1,t.accessibleLines(0f,1000f,10).size)
  val a=Attachment("ref","seed/photo-000.jpg","image/jpeg",10,413,275,1,null,"")
  val photo=Message(2,2,OrderKey(2,2),MessageDirection.INCOMING,1,MessageKind.PHOTO,"","",null,1,listOf(a))
  val p=PreparedText(photo,TextLayoutKey(2,1,280,17,1));assertEquals(p.mediaRect!!.bottom+50,p.height,0.01f);assertEquals(2f,p.mediaRect!!.left,0.01f)
  assertEquals(12f,p.mediaCornerRadius,0.01f)
  assertEquals(listOf(KandinskyMediaAction.SAVE,KandinskyMediaAction.SHARE),p.mediaActions.map{it.action})
  val first=p.mediaActions.first().frame
  assertEquals(p.mediaRect!!.bottom+2,first.top,0.01f)
  assertEquals(48f,first.width(),0.01f)
  assertEquals(48f,first.height(),0.01f)
  val video=PreparedText(photo.copy(kind=MessageKind.VIDEO),TextLayoutKey(2,1,280,17,1))
  assertEquals(KandinskyMediaAction.entries,video.mediaActions.map{it.action})
  KandinskyMediaAction.load(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.resources)
  val bitmap=android.graphics.Bitmap.createBitmap(kotlin.math.ceil(p.width).toInt(),kotlin.math.ceil(p.height).toInt(),android.graphics.Bitmap.Config.ARGB_8888)
  p.drawMediaStamp(android.graphics.Canvas(bitmap))
  val badgeRight=p.mediaRect!!.right.toInt()-6
  val badgeBottom=kotlin.math.ceil(p.mediaRect!!.bottom-6).toInt()
  assertEquals(0,android.graphics.Color.alpha(bitmap.getPixel(badgeRight-8,badgeBottom)))
  assertTrue(android.graphics.Color.alpha(bitmap.getPixel(badgeRight-8,badgeBottom-2))>100)
  val centerX=first.centerX().toInt()
  val centerY=first.centerY().toInt()-6
  assertTrue((centerY-7..centerY+7).any{y->(centerX-7..centerX+7).any{x->android.graphics.Color.alpha(bitmap.getPixel(x,y))>100}})
 }
 /**
  * Готовит исходящий текстовый запрос и сверяет отступы строки с полной высотой сообщения
  * По прозрачности справа проверяет свободную боковую область и нижний выступ подложки
  * Так время и форма запроса проверяются отдельно от содержания текста
  */
 @Test fun kandinskyPromptKeepsSeparateTimestampAndTail() {
  val row=Message(3,2,OrderKey(3,3),MessageDirection.OUTGOING,1,MessageKind.TEXT,"Медиа 191","",null,1,emptyList())
  val prepared=PreparedText(row,TextLayoutKey(3,1,280,17,1))
  val line=prepared.accessibleLines(0f,1000f,1).single()
  assertEquals(14f,line.frame.left,0.01f)
  assertEquals(9f,line.frame.top,0.01f)
  assertEquals(53f,prepared.height,0.01f)
  val bitmap=android.graphics.Bitmap.createBitmap(kotlin.math.ceil(prepared.width).toInt(),53,android.graphics.Bitmap.Config.ARGB_8888)
  prepared.draw(0,android.graphics.Canvas(bitmap))
  assertEquals(0,android.graphics.Color.alpha(bitmap.getPixel(bitmap.width-2,bitmap.height/2)))
  assertTrue((49..52).any { android.graphics.Color.alpha(bitmap.getPixel(bitmap.width-2,it))>100 })
 }
}
