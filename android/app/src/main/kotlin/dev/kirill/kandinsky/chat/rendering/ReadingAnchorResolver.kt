package dev.kirill.kandinsky.chat.rendering
import dev.kirill.kandinsky.chat.domain.*
object ReadingAnchorResolver {
 /**
  * Меняет опору только если пользователь не следует концу и её текущее сообщение действительно входит в удаляемый набор
  * Сначала выбирает ближайшую оставшуюся строку после удалённой, при её отсутствии ищет предыдущую
  * Для новой строки сбрасывает внутренние смещения, сохраняя версию разметки и отсутствие следования концу
  */
 fun afterRemoving(anchor:ReadingAnchor,rows:List<IndexedRow>,removed:Set<MessageID>):ReadingAnchor{
  val id=anchor.messageID
  if(anchor.followsBottom || id==null || id !in removed)return anchor
  val i=rows.indexOfFirst{it.messageID==id};if(i<0)return anchor
  val next=rows.asSequence().drop(i+1).firstOrNull{it.messageID !in removed}
  val previous=rows.subList(0,i).asReversed().firstOrNull{it.messageID !in removed}
  return ReadingAnchor(messageID=(next ?: previous)?.messageID,followsBottom=false,layoutVersion=anchor.layoutVersion)
 }
}
