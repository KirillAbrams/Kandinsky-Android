package dev.kirill.kandinsky.chat.media.kernel

internal class MediaSurfaceMailbox<T : AutoCloseable>(
    private val schedule: (() -> Unit) -> Unit,
    private val mayPublish: ((T) -> Boolean)? = null,
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

    // Заменённый или отклонённый кадр закрывает самостоятельно, поэтому владение передаётся даже
    // при
    // отказе.
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

    fun clear() {
        invalidate(false, null)
    }

    fun clear(binding: Long): Boolean = invalidate(false, binding)

    override fun close() {
        invalidate(true, null)
    }

    private fun invalidate(terminal: Boolean, expected: Long?): Boolean {
        val old: T?
        val notify: Boolean
        synchronized(gate) {
            if (closed || (expected != null && (!active || expected != binding))) return false
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
        return true
    }

    private fun markNotice(): Boolean {
        if (scheduled) return false
        scheduled = true
        return true
    }

    private fun enqueueNotice() = schedule {
        synchronized(gate) { scheduled = false }
        flush()
    }

    fun flush() {
        var old: T? = null
        try {
            synchronized(gate) {
                if (!dirty) return
                val next = pending
                if (next != null && active && !closed && mayPublish?.invoke(next) == false) {
                    if (clearDisplay) {
                        old = displayed
                        displayed = null
                        clearDisplay = false
                        render?.invoke(null)
                    }
                    return
                }
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
