// На андроиде два фоновых потока измеряют строки, а отдельное хранилище удерживает готовую
// разметку с пределом по числу и памяти. Видимая строка проходит перед дальними, чтобы прокрутка
// не ждала старого направления.

package dev.kirill.kandinsky.chat.rendering

import android.os.Handler
import android.os.Looper
import dev.kirill.kandinsky.chat.domain.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

class TextLayoutWorker internal constructor(
    private val makeLayout: (Message, TextLayoutKey) -> PreparedText,
) {
    constructor() : this(::PreparedText)

    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private val pending = LinkedBlockingDeque<Runnable>(32)
    private var visibleKeys = emptySet<TextLayoutKey>()
    private val pool =
        ThreadPoolExecutor(
            2,
            2,
            0,
            TimeUnit.MILLISECONDS,
            pending,
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
        // Готовим строку, если задача и очередь ещё живы, а результат передаём в поток экрана.
        // Повторно проверяем отмену перед выдачей, чтобы не удержать готовый рисунок зря.
        override fun run() {
            val resource = if (cancelled.get() || closed.get()) null else makeLayout(message, key)
            main.post {
                if (cancelled.get() || closed.get()) {
                    resource?.close()
                    completion(ticket, null)
                } else completion(ticket, resource)
            }
        }
    }

    // Обновляем набор видимых ключей и переставляем в начало те задачи, которые ещё в очереди.
    // Работник может уже забрать задачу, поэтому перед перестановкой проверяем её удаление.
    fun prioritizeVisible(keys: Set<TextLayoutKey>) {
        if (keys == visibleKeys) return
        visibleKeys = keys
        // Фоновый поток может забрать задачу между операциями с очередью.
        val queued = pending.toTypedArray()
        for (index in queued.indices.reversed()) {
            val job = queued[index] as Job
            if (job.key in keys && pool.remove(job)) pending.addFirst(job)
        }
    }

    // Отдаём задачу в ограниченную очередь с номером сцены. При полном запасе видимая строка
    // может убрать дальнюю, а отмена убирает ожидающий расчёт и сообщает о завершении без
    // результата.
    fun prepare(
        message: Message,
        key: TextLayoutKey,
        ticket: Int,
        completion: (Int, PreparedText?) -> Unit,
    ): Cancellation {
        val cancelled = AtomicBoolean(false)
        val job = Job(message, key, ticket, cancelled, completion)
        if (key in visibleKeys && pending.remainingCapacity() == 0) {
            val farthest = pending.lastOrNull { (it as Job).key !in visibleKeys } as Job?
            if (farthest != null && pool.remove(farthest)) {
                farthest.cancelled.set(true)
                main.post { farthest.completion(farthest.ticket, null) }
            }
        }
        try {
            pool.execute(job)
            if (key in visibleKeys && pool.remove(job)) pending.addFirst(job)
        } catch (e: RejectedExecutionException) {
            cancelled.set(true)
            main.post { completion(ticket, null) }
        }
        return Cancellation {
            cancelled.set(true)
            if (pool.remove(job)) main.post { completion(ticket, null) }
        }
    }

    // Помечаем очередь закрытой, останавливаем работников и отсылаем отмену ещё не взятым
    // задачам. Активные работы сами проверят закрытие перед передачей.
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
    internal var revision = 0
        private set

    var limit = 32 * 1024 * 1024

    // Берём готовую строку по её ключу. Порядок доступа обновляется, чтобы частые обращения
    // пережили следующее сокращение памяти.
    fun value(key: TextLayoutKey) = values[key]

    // Заменяем прежнюю разметку того же ключа и считаем стоимость новой. Слишком большой рисунок
    // пропускаем, затем проверяем предел общего объёма.
    fun insert(value: PreparedText) {
        revision++
        values.remove(value.key)?.let { bytes -= it.byteCost }
        if (value.byteCost > limit) return
        values[value.key] = value
        bytes += value.byteCost
        trim()
    }

    // Отпускаем записи с самым давним обращением при превышении числа или объёма. Номер
    // содержимого меняется, чтобы лента пересмотрела готовое окно.
    fun trim() {
        val i = values.entries.iterator()
        while ((bytes > limit || values.size > 256) && i.hasNext()) {
            bytes -= i.next().value.byteCost
            i.remove()
            revision++
        }
    }

    // Убираем все рисунки и сбрасываем счётчик памяти. Меняем номер набора, поскольку старые
    // записи больше не пригодны.
    fun clear() {
        revision++
        values.clear()
        bytes = 0
    }
}
