// Позволяем проверкам дождаться результата хранилища и заметить повторное завершение.

package dev.kirill.kandinsky.chat.support

import android.os.Looper
import dev.kirill.kandinsky.chat.domain.Cancellation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*

// Запускаем операцию вне основного потока, ждём ответ до пяти секунд и проверяем единственный
// вызов. Затем возвращаем значение или передаём ошибку операции проверке.
fun <T> awaitResult(register: ((Result<T>) -> Unit) -> Cancellation): T {
    check(Looper.myLooper() != Looper.getMainLooper())
    val done = CountDownLatch(1)
    val value = AtomicReference<Result<T>>()
    val calls = AtomicInteger()
    register {
        assertEquals(1, calls.incrementAndGet())
        value.set(it)
        done.countDown()
    }
    assertTrue("native storage callback <=5s", done.await(5, TimeUnit.SECONDS))
    return value.get().getOrThrow()
}
