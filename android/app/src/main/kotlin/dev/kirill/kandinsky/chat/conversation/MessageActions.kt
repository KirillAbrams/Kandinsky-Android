package dev.kirill.kandinsky.chat.conversation
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.storage.ChatRepository
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
class MessageActions(private val chatID:ChatID,private val repository:ChatRepository,private val state:ConversationState,private val copyText:(String)->Unit) {
 var onDelta:((MessageDelta)->Unit)?=null
 /**
  * Получает актуальное сообщение перед действием по его обозначению
  * Сначала находит место в указателе истории, затем читает страницу вокруг нужной позиции
  * Если сообщение отсутствует в указателе или странице, сообщает, что оно не найдено
  */
 private fun message(id:MessageID,callback:(Result<Message>)->Unit):Cancellation{
  val cancelled=AtomicBoolean(false);val current=AtomicReference<Cancellation?>()
  current.set(repository.index(chatID){result->if(!cancelled.get())result.fold({rows->
   val key=rows.firstOrNull{it.messageID==id}?.order
   if(key==null)callback(Result.failure(RepoException(RepoError.NOT_FOUND))) else current.set(repository.history(PageRequest(chatID,key,PageDirection.AROUND)){page->if(!cancelled.get())callback(page.mapCatching{p->p.rows.firstOrNull{it.id==id} ?: throw RepoException(RepoError.NOT_FOUND)})})
  },{callback(Result.failure(it))})})
  return Cancellation{cancelled.set(true);current.get()?.cancel()}
 }
 /**
  * Получает актуальное содержимое выбранного сообщения
  * Предпочитает обычный текст, а при его отсутствии берёт подпись вложения
  * Передаёт строку назначенному копированию и возвращает её как результат действия
  */
 fun copy(id:MessageID,callback:(Result<String>)->Unit):Cancellation=message(id){result->callback(result.mapCatching{val text=it.text.ifEmpty{it.caption};copyText(text);text})}
 /**
  * Если черновик ссылался на него как на цель ответа, очищает эту ссылку и сохраняет черновик
  * После успешного завершения передаёт изменение истории экрану и возвращает его вызывающей стороне
  */
 fun delete(id:MessageID,callback:(Result<MessageDelta>)->Unit):Cancellation=message(id){result->result.fold({
  repository.delete(id){deleted->deleted.fold({delta->
   if(state.draft.replyToID==id){state.editDraft(state.draft.copy(replyToID=null));state.flush{saved->if(saved.isSuccess){onDelta?.invoke(delta);callback(Result.success(delta))}else callback(Result.failure(saved.exceptionOrNull()!!))}}
   else{onDelta?.invoke(delta);callback(Result.success(delta))}
  },{callback(Result.failure(it))})}
 },{callback(Result.failure(it))})}
}
