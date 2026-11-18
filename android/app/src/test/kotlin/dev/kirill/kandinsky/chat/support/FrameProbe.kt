package dev.kirill.kandinsky.chat.support
import java.util.concurrent.atomic.AtomicInteger
class FrameProbe:AutoCloseable {
 private val count=AtomicInteger()
 val closes:Int get()=count.get()
 /**
  * Увеличивает защищённый счётчик освобождения проверочного кадра
  * Требует единственного вызова, чтобы повторное закрытие сразу выявляло ошибку владения
  */
 override fun close(){check(count.incrementAndGet()==1)}
}
