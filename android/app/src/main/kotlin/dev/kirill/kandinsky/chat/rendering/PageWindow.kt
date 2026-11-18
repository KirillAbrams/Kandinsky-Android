package dev.kirill.kandinsky.chat.rendering
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.storage.ChatRepository
class PageWindow(val chatID:ChatID,private val repository:ChatRepository?=null) {
 var generation=1;private set
 private var closed=false
 private var request:Cancellation?=null
 private val pages=ArrayDeque<List<Message>>()
 val loadedRows:List<Message> get()=pages.flatten().fold(mutableMapOf<MessageID,Message>()){byID,row->
  if((byID[row.id]?.revision ?: -1)<=row.revision)byID[row.id]=row;byID
 }.values.sortedBy{it.order}
 val loadedCount:Int get()=loadedRows.size
 /**
  * Проверяет открытость окна, номер чата и наличие хранилища, затем отменяет прежнее чтение страницы
  * Новое чтение связывает с поколением и применяет ответ только пока окно и поколение ещё действуют
  * Возвращает действие отмены запроса, а устаревший ответ сообщает вызывающей стороне как отменённый
  */
 fun request(value:PageRequest,callback:(Result<HistoryPage>)->Unit):Cancellation{
  val repo=repository
  if(closed || value.chatID!=chatID || repo==null){callback(Result.failure(RepoException(RepoError.CANCELLED)));return Cancellation{}}
  request?.cancel();generation++;val ticket=generation
  val token=repo.history(value){result->
   if(closed || ticket!=generation)callback(Result.failure(RepoException(RepoError.CANCELLED))) else {
    result.onSuccess{apply(it,value.chatID,ticket)};callback(result)
   }
  };request=token;return token
 }
 /**
  * Принимает страницу только для собственного чата, действующего поколения и незакрытого окна
  * Проверяет размер и принадлежность всех строк, затем сохраняет страницу и оставляет лишь три последние порции
  */
 fun apply(page:HistoryPage,chatID:ChatID,generation:Int){
  if(closed || this.chatID!=chatID || this.generation!=generation || page.rows.size>128 || page.rows.any{it.chatID!=chatID})return
  pages.addLast(page.rows);while(pages.size>3)pages.removeFirst()
 }
 /**
  * Во всех удерживаемых страницах ищет строку с номером обновлённого сообщения
  * Заменяет только более старую версию, оставляя остальные сохранённые сообщения на месте
  */
 fun refreshKnownRow(message:Message){
  for(i in pages.indices){
   val page=pages[i];val at=page.indexOfFirst{it.id==message.id && it.revision<message.revision}
   if(at>=0)pages[i]=page.toMutableList().also{it[at]=message}
  }
 }
 /**
  * Принимает до трёхсот восьмидесяти четырёх уже сохранённых разных сообщений собственного чата
  * Сортирует их по порядку и заполняет окно страницами до ста двадцати восьми строк
  * Это позволяет немедленно вернуть известную историю на экран, пока отдельно уточняются новые серверные данные
  */
 fun seedKnownRows(rows:List<Message>){
  if(closed || rows.size>384 || rows.any{it.chatID!=chatID} || rows.map{it.id}.toSet().size!=rows.size)return
  val ordered=rows.sortedBy{it.order};pages.clear();ordered.chunked(128).forEach{pages.addLast(it)}
 }
 /**
  * Отмечает окно закрытым, меняет поколение и освобождает удерживаемые страницы сообщений
  * Поздний ответ прежнего чтения больше не может наполнить закрытое окно
  */
 fun close(){closed=true;generation++;request?.cancel();request=null;pages.clear()}
}
