package dev.kirill.kandinsky.chat.media

import dev.kirill.kandinsky.chat.domain.Cancellation
import java.util.concurrent.atomic.AtomicBoolean

enum class MediaError {
    UNAVAILABLE,
    PERMISSION_DENIED,
    INTERRUPTED,
    CORRUPT,
    STORAGE_FULL,
    IO,
    CANCELLED,
}

class MediaException(val reason: MediaError) : RuntimeException(reason.name)

enum class CaptureKind {
    PHOTO
}

data class ThumbnailKey(val maxPixel: Int = 1024, val studioOriginal: Boolean = false)

class MediaCancellation : Cancellation {
    private val gate = Any()
    private var state = 0
    private var handler: (() -> Unit)? = null
    private var progress: ((Double) -> Unit)? = null

    // После отмены или завершения новый обработчик не устанавливается.
    fun onProgress(action: (Double) -> Unit) {
        synchronized(gate) { if (state == 0) progress = action }
    }

    fun reportProgress(value: Double) {
        val action = synchronized(gate) { if (state == 0) progress else null }
        action?.invoke(value.coerceIn(0.0, 1.0))
    }

    fun onCancel(action: () -> Unit) {
        val now =
            synchronized(gate) {
                if (state == 0) handler = action
                state == 1
            }
        if (now) action()
    }

    // Вызываем обработчик после снятия блокировки. Он сам может обратиться к этому объекту.
    override fun cancel() {
        val action =
            synchronized(gate) {
                if (state != 0) return
                state = 1
                handler.also {
                    handler = null
                    progress = null
                }
            }
        action?.invoke()
    }

    // Очищает обработчики, чтобы они не удерживали получателей после конца работы.
    fun finish(): Boolean =
        synchronized(gate) {
            if (state != 0) return false
            state = 2
            handler = null
            progress = null
            true
        }

    val isCancelled
        get() = synchronized(gate) { state == 1 }
}

class CaptureLease(val kind: CaptureKind, private val released: () -> Unit) {
    private val once = AtomicBoolean(false)

    fun release() {
        if (once.compareAndSet(false, true)) released()
    }
}

class PlaybackLease(private val released: () -> Unit) {
    private val once = AtomicBoolean(false)

    fun release() {
        if (once.compareAndSet(false, true)) released()
    }
}

interface CaptureAdmission {
    fun acquire(kind: CaptureKind, completion: (Result<CaptureLease>) -> Unit): Cancellation
}
