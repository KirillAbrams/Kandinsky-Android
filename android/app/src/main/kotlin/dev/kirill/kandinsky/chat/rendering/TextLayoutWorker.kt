package dev.kirill.kandinsky.chat.rendering

import android.os.Handler
import android.os.Looper
import dev.kirill.kandinsky.chat.domain.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

class TextLayoutWorker {
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private val pool =
        ThreadPoolExecutor(
            2,
            2,
            0,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(32),
            { r -> Thread(r, "Kandinsky-Text").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )

    private inner class Job(
        val message: Message,
        val key: TextLayoutKey,
        val ticket: Int,
        val cancelled: AtomicBoolean,
        val completion: (Int, PreparedText?) -> Unit,
    ) : Runnable {
        override fun run() {
            val resource = if (cancelled.get() || closed.get()) null else PreparedText(message, key)
            main.post {
                if (cancelled.get() || closed.get()) {
                    resource?.close()
                    completion(ticket, null)
                } else completion(ticket, resource)
            }
        }
    }

    fun prepare(
        message: Message,
        key: TextLayoutKey,
        ticket: Int,
        completion: (Int, PreparedText?) -> Unit,
    ): Cancellation {
        val cancelled = AtomicBoolean(false)
        val job = Job(message, key, ticket, cancelled, completion)
        try {
            pool.execute(job)
        } catch (e: RejectedExecutionException) {
            cancelled.set(true)
            main.post { completion(ticket, null) }
        }
        return Cancellation {
            cancelled.set(true)
            if (pool.remove(job)) main.post { completion(ticket, null) }
        }
    }

    fun close() {
        closed.set(true)
        val pending = pool.shutdownNow()
        for (r in pending) {
            val job = r as Job
            job.cancelled.set(true)
            main.post { job.completion(job.ticket, null) }
        }
    }
}

class FragmentLayoutCache {
    private val values = LinkedHashMap<TextLayoutKey, PreparedText>(16, 0.75f, true)
    var bytes = 0
        private set

    var limit = 32 * 1024 * 1024

    // Успешное чтение делает запись недавно использованной, чтобы при следующем вытеснении сначала
    // ушли более старые обращения
    fun value(key: TextLayoutKey) = values[key]

    fun insert(value: PreparedText) {
        values.remove(value.key)?.let { bytes -= it.byteCost }
        if (value.byteCost > limit) return
        values[value.key] = value
        bytes += value.byteCost
        trim()
    }

    fun trim() {
        val i = values.entries.iterator()
        while ((bytes > limit || values.size > 256) && i.hasNext()) {
            bytes -= i.next().value.byteCost
            i.remove()
        }
    }

    fun clear() {
        values.clear()
        bytes = 0
    }
}
