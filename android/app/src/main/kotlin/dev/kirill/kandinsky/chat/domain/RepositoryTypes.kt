package dev.kirill.kandinsky.chat.domain
import java.util.concurrent.atomic.AtomicReference
enum class RepoError { INVALID_INPUT,NOT_FOUND,STORAGE_FULL,IO,CORRUPT,CANCELLED }
class RepoException(val reason:RepoError):RuntimeException(reason.name)
enum class PageDirection { OLDER,NEWER,AROUND }
data class PageRequest(val chatID:ChatID,val anchor:OrderKey?=null,val direction:PageDirection=PageDirection.OLDER,val limit:Int=128,val visualOnly:Boolean=false)
data class HistoryPage(val rows:List<Message>,val before:OrderKey?,val after:OrderKey?)
fun interface Cancellation {
 /**
  * Задаёт общее действие отмены выполняемой операции хранилища
  * Конкретный владелец операции предоставляет реализацию, которую вызывающая сторона может сохранить и вызвать при закрытии ожидания
  */
 fun cancel() }
enum class RepoFault { BEFORE_COMMIT,AFTER_COMMIT_BEFORE_REPLY,BEFORE_RENAME,AFTER_RENAME,STORAGE_FULL }
class RepositoryFaults {
 private val next=AtomicReference<RepoFault?>()
 /**
  * Назначает один ожидаемый отказ для управляемой проверки сохранения или переименования файла
  * Заменяет прежнее назначение согласованным доступом к общему значению, не вызывая отказ немедленно
  */
 fun arm(fault:RepoFault){next.set(fault)}
 /**
  * Сравнивает запрошенную точку отказа с назначенной и снимает её только при совпадении
  * Возвращает признак срабатывания так, чтобы один и тот же назначенный отказ нельзя было применить дважды
  */
 fun consume(fault:RepoFault)=next.compareAndSet(fault,null)
}
