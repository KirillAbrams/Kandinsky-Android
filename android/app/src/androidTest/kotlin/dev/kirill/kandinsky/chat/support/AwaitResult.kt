package dev.kirill.kandinsky.chat.support
import android.os.Looper
import dev.kirill.kandinsky.chat.domain.Cancellation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
/**
 * Только вне основного потока регистрирует асинхронное действие и ждёт его результат в пределах срока
 * Требует единственного вызова ответа, сохраняя значение безопасно между потоками
 * Возвращает полученное значение либо передаёт ошибку результата вызывающему коду
 */
fun <T> awaitResult(register:((Result<T>)->Unit)->Cancellation):T {
 check(Looper.myLooper()!=Looper.getMainLooper())
 val done=CountDownLatch(1);val value=AtomicReference<Result<T>>();val calls=AtomicInteger()
 register { assertEquals(1,calls.incrementAndGet());value.set(it);done.countDown() }
 assertTrue("native storage callback <=5s",done.await(5,TimeUnit.SECONDS))
 return value.get().getOrThrow()
}
