// Принимает кадры с номером привязки и передаёт экрану последний допустимый. Отклонённые и
// заменённые кадры закрываются, чтобы не копиться в памяти.

package dev.kirill.kandinsky.chat.media.kernel

internal class MediaSurfaceMailbox<T : AutoCloseable>(
    private val schedule: (() -> Unit) -> Unit,
    render: (T?) -> Unit,
) : AutoCloseable {
    private val gate = Any()
    private var binding = 0L
    private var sequence = 0L
    private var active = false
    private var closed = false
    private var pending: T? = null
    private var displayed: T? = null
    private var dirty = false
    private var clearDisplay = false
    private var scheduled = false
    private var render: ((T?) -> Unit)? = render

    // Назначает новый номер источника, убирает ожидающий кадр и по просьбе сохраняет показанный.
    // Поздний кадр со старым номером больше не сможет его вытеснить.
    fun begin(preservingDisplay: Boolean = false): Long? {
        var old: T?
        val token: Long?
        val notify: Boolean
        synchronized(gate) {
            if (closed) return null
            if (binding == Long.MAX_VALUE) {
                closed = true
                active = false
                token = null
            } else {
                binding++
                sequence = 0
                active = true
                token = binding
            }
            old = pending
            pending = null
            clearDisplay = !preservingDisplay || displayed == null
            dirty = true
            notify = markNotice()
        }
        old?.close()
        if (notify) enqueueNotice()
        return token
    }

    // Проверяет номер источника и порядок кадра. Если новый кадр подходит, заменяет ожидающий,
    // иначе закрывает сам новый. В обоих случаях лишний ресурс освобождается.
    fun offer(token: Long, serial: Long, frame: T): Boolean {
        val old: T?
        val accepted: Boolean
        val notify: Boolean
        synchronized(gate) {
            accepted = active && !closed && token == binding && serial > sequence
            if (accepted) {
                sequence = serial
                old = pending
                pending = frame
                dirty = true
                notify = markNotice()
            } else {
                old = frame
                notify = false
            }
        }
        old?.close()
        if (notify) enqueueNotice()
        return accepted
    }

    // Сбрасывает картинку и текущую привязку, сохраняя возможность открыть другую. Это подходит
    // для повторного использования элемента ленты.
    fun clear() {
        invalidate(terminal = false)
    }

    // Завершает работу с кадрами и больше не принимает привязок. После очистки убирает и действие
    // показа.
    override fun close() {
        invalidate(terminal = true)
    }

    // Прерывает текущую привязку и переносит очистку картинки на запланированное обновление.
    // Ожидающий кадр закрывается сразу.
    private fun invalidate(terminal: Boolean) {
        val old: T?
        val notify: Boolean
        synchronized(gate) {
            if (closed) return
            closed = terminal || binding == Long.MAX_VALUE
            if (binding != Long.MAX_VALUE) binding++
            active = false
            old = pending
            pending = null
            clearDisplay = true
            dirty = true
            notify = markNotice()
        }
        old?.close()
        if (notify) enqueueNotice()
    }

    // Даёт назначить одно обновление, пока прежнее ещё ждёт. При большом потоке кадров экран
    // получает последнюю версию.
    private fun markNotice(): Boolean {
        if (scheduled) return false
        scheduled = true
        return true
    }

    // Ставит показ в очередь и снимает отметку назначения перед чтением кадра. Это позволяет
    // следующему изменению снова запланировать показ.
    private fun enqueueNotice() = schedule {
        synchronized(gate) { scheduled = false }
        flush()
    }

    // При изменении выбирает следующий кадр или очищает картинку, затем вызывает показ.
    // Освобождает старый кадр даже при ошибке обновления.
    fun flush() {
        var old: T? = null
        try {
            synchronized(gate) {
                if (!dirty) return
                dirty = false
                if (clearDisplay || pending != null) {
                    old = displayed
                    displayed = if (active && !closed) pending else null
                    pending = null
                }
                clearDisplay = false
                render?.invoke(displayed)
                if (closed) render = null
            }
        } finally {
            old?.close()
        }
    }
}
