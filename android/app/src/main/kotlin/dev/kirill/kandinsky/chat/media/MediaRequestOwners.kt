package dev.kirill.kandinsky.chat.media

import java.util.UUID

// Последний получатель останавливает передачу, даже когда очередь экрана ещё занята.
internal class MediaRequestOwners {
    val cancellation = MediaCancellation()
    private val gate = Any()
    private val owners = mutableSetOf<UUID>()
    private var closed = false

    fun insert(owner: UUID): Boolean =
        synchronized(gate) {
            if (closed) false
            else {
                owners.add(owner)
                true
            }
        }

    fun remove(owner: UUID) {
        val shouldCancel =
            synchronized(gate) {
                (owners.remove(owner) && owners.isEmpty()).also { if (it) closed = true }
            }
        if (shouldCancel) cancellation.cancel()
    }

    val isClosed
        get() = synchronized(gate) { closed }

    fun close() {
        synchronized(gate) {
            closed = true
            owners.clear()
        }
        cancellation.cancel()
    }
}
