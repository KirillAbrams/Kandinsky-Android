package dev.kirill.kandinsky.chat.media

import android.os.Handler
import android.os.Looper
import dev.kirill.kandinsky.chat.domain.Cancellation
import java.util.UUID

// Части одного ролика читаются один раз. Ожидающая передача уступает показанному содержимому.
internal class MediaVideoPreparation {
    private class Entry(val key: String, val work: (MediaCancellation) -> Unit) {
        val owners = mutableSetOf<UUID>()
        var generation = 0
        var done = false
        var request: Cancellation? = null
        var fetch: MediaCancellation? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val queue = MediaDecodeQueue(1)
    private val entries = mutableListOf<Entry>()
    private var active: Entry? = null
    private var paused = false
    private var closed = false

    fun acquire(key: String, work: (MediaCancellation) -> Unit): Cancellation? {
        if (closed) return null
        val entry =
            entries.firstOrNull { it.key == key }
                ?: run {
                    if (entries.size >= 8) return null
                    Entry(key, work).also { entries.add(it) }
                }
        val owner = UUID.randomUUID()
        entry.owners.add(owner)
        val token = MediaCancellation()
        token.onCancel {
            main.post {
                entry.owners.remove(owner)
                if (entry.owners.isEmpty()) {
                    entries.remove(entry)
                    stop(entry)
                }
                startNext()
            }
        }
        startNext()
        return token
    }

    fun setPaused(value: Boolean) {
        paused = value
        if (value) active?.let(::stop) else startNext()
    }

    private fun stop(entry: Entry) {
        entry.generation++
        entry.fetch?.cancel()
        entry.request?.cancel()
        entry.fetch = null
        entry.request = null
        if (active === entry) active = null
    }

    private fun startNext() {
        if (closed || paused || active != null) return
        val entry = entries.firstOrNull { !it.done } ?: return
        active = entry
        val generation = ++entry.generation
        val fetch = MediaCancellation()
        entry.fetch = fetch
        entry.request =
            queue.submit(work = { entry.work(fetch) }, dispose = {}) {
                if (entry.generation == generation) {
                    fetch.finish()
                    entry.done = true
                    entry.fetch = null
                    entry.request = null
                    active = null
                    startNext()
                }
            }
    }

    fun clear() {
        val old = entries.toList()
        entries.clear()
        old.forEach(::stop)
    }

    fun close() {
        closed = true
        clear()
        queue.close()
    }
}
