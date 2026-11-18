// Ограничивает фоновую обработку медиа и ждёт выдачи результата в поток экрана. Переполненная
// очередь получает отказ вместо бесконечного роста.

package dev.kirill.kandinsky.chat.media

import android.os.Handler
import android.os.Looper
import dev.kirill.kandinsky.chat.domain.Cancellation
import java.util.concurrent.Executors

class MediaDecodeQueue(private val maximumWorkers: Int = 2) {
    // Проверяет допустимое число фоновых работников до начала очереди. Слишком много
    // одновременных разборов заняли бы память.
    init {
        require(maximumWorkers in 1..2)
    }

    private class Job(val execute: (Job) -> Unit, val fail: (Throwable) -> Unit) {
        val token = MediaCancellation()
        var state = 0
    }

    private val gate = Any()
    private val main = Handler(Looper.getMainLooper())
    private val workers =
        Executors.newFixedThreadPool(maximumWorkers) { r ->
            Thread(r, "Kandinsky-Media").apply { isDaemon = true }
        }
    private val pending = ArrayDeque<Job>()
    private val active = mutableSetOf<Job>()
    private var closed = false
    val runningCount
        // Читает число начатых задач под блокировкой. Пока результат выдаётся, задача ещё
        // учитывается как активная.
        get() = synchronized(gate) { active.size }

    val pendingCount
        // Под той же блокировкой читает число ожидающих. Число не смешивает работы, которые уже
        // выполняются.
        get() = synchronized(gate) { pending.size }

    // Ставит задачу по её срочности, назначает отмену и сообщает об отказе при переполнении.
    // Готовый результат возвращается в поток экрана или освобождается после отмены.
    fun <T> submit(
        priority: Int = 0,
        work: () -> T,
        dispose: (T) -> Unit,
        completion: (Result<T>) -> Unit,
    ): Cancellation {
        val job =
            Job(
                execute = { job ->
                    val result =
                        if (job.token.isCancelled)
                            Result.failure(MediaException(MediaError.CANCELLED))
                        else runCatching(work)
                    main.post {
                        val allowed = synchronized(gate) { !closed }
                        if (allowed && job.token.finish()) completion(result)
                        else {
                            result.getOrNull()?.let(dispose)
                            completion(Result.failure(MediaException(MediaError.CANCELLED)))
                        }
                        synchronized(gate) {
                            job.state = 2
                            active.remove(job)
                            drain()
                        }
                    }
                },
                fail = { completion(Result.failure(it)) },
            )
        job.token.onCancel {
            synchronized(gate) {
                if (job.state == 0 && pending.remove(job)) {
                    job.state = 2
                    main.post { job.fail(MediaException(MediaError.CANCELLED)) }
                }
            }
        }
        synchronized(gate) {
            if (closed || (active.size >= maximumWorkers && pending.size >= 16)) {
                job.state = 2
                val error = if (closed) MediaError.CANCELLED else MediaError.UNAVAILABLE
                main.post {
                    val reason = if (job.token.finish()) error else MediaError.CANCELLED
                    job.fail(MediaException(reason))
                }
            } else {
                if (priority > 0) pending.addFirst(job) else pending.addLast(job)
                drain()
            }
        }
        return job.token
    }

    // Запускает работы из головы очереди только до предела фоновых исполнителей. Отменённая
    // задача получает отказ до начала разбора.
    private fun drain() {
        while (!closed && active.size < maximumWorkers && pending.isNotEmpty()) {
            val job = pending.removeFirst()
            if (job.token.isCancelled) {
                job.state = 2
                main.post { job.fail(MediaException(MediaError.CANCELLED)) }
                continue
            }
            job.state = 1
            active.add(job)
            workers.execute { job.execute(job) }
        }
    }

    // Закрывает ожидание, отменяет активные задачи и завершает очередь работников. Иначе закрытый
    // чат оставил бы фоновый разбор без получателя.
    fun close() {
        synchronized(gate) {
            if (closed) return
            closed = true
            val waiting = pending.toList()
            pending.clear()
            for (job in waiting) {
                job.state = 2
                job.token.cancel()
                main.post { job.fail(MediaException(MediaError.CANCELLED)) }
            }
            for (job in active) job.token.cancel()
            workers.shutdown()
        }
    }
}
