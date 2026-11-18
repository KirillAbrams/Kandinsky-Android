// Храним счётчик освобождения кадра, чтобы проверки замечали лишний возврат или удержание
// объекта.

package dev.kirill.kandinsky.chat.support

import java.util.concurrent.atomic.AtomicInteger

class FrameProbe : AutoCloseable {
    private val count = AtomicInteger()
    val closes: Int
        // Читаем общий счётчик без ручной блокировки. По значению видно, освободил ли кадр поток,
        // которому он был передан.
        get() = count.get()

    // Увеличиваем общий счётчик и требуем единицу. Вторая попытка освобождения сразу сообщает об
    // ошибке владения.
    override fun close() {
        check(count.incrementAndGet() == 1)
    }
}
