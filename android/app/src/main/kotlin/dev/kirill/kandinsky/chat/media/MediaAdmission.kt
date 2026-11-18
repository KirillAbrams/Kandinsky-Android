package dev.kirill.kandinsky.chat.media

import android.os.Handler
import android.os.Looper
import dev.kirill.kandinsky.chat.domain.Cancellation
import java.util.UUID

private object ProcessMediaGate {
    var playback: UUID? = null
    var capture: UUID? = null
    var pendingCapture: UUID? = null
    val previews = linkedMapOf<UUID, () -> Unit>()
    var drain: Pair<UUID, () -> Unit>? = null
    val observers = linkedMapOf<UUID, () -> Unit>()
}

class MediaAdmission : CaptureAdmission {
    private class PendingCapture(
        val kind: CaptureKind,
        val token: MediaCancellation,
        val completion: (Result<CaptureLease>) -> Unit,
    ) {
        val id = UUID.randomUUID()
        var stopped = false
        var scheduled = false
    }

    private class PendingPlayback(
        val id: UUID,
        val token: MediaCancellation,
        val completion: (Result<PlaybackLease>) -> Unit,
    ) {
        var scheduled = false
    }

    companion object {
        private val main = Handler(Looper.getMainLooper())

        private fun checkMain() {
            check(Looper.myLooper() == Looper.getMainLooper())
        }

        private fun releaseOnMain(action: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
        }

        val previewOwnerCount
            get() = ProcessMediaGate.previews.size

        val previewsAllowed
            get() =
                ProcessMediaGate.playback == null &&
                    ProcessMediaGate.capture == null &&
                    ProcessMediaGate.pendingCapture == null

        fun reservePreview(studioAlbum: Boolean = false, stop: () -> Unit): PlaybackLease? {
            checkMain()
            if (!previewsAllowed || ProcessMediaGate.previews.size >= (if (studioAlbum) 4 else 2))
                return null
            val id = UUID.randomUUID()
            ProcessMediaGate.previews[id] = stop
            return PlaybackLease {
                releaseOnMain {
                    if (ProcessMediaGate.previews.remove(id) != null) {
                        if (ProcessMediaGate.previews.isEmpty())
                            ProcessMediaGate.drain?.second?.invoke()
                        notifyPreviewAvailability()
                    }
                }
            }
        }

        fun observePreviewAvailability(action: () -> Unit): UUID {
            checkMain()
            val id = UUID.randomUUID()
            ProcessMediaGate.observers[id] = action
            return id
        }

        fun removePreviewObserver(id: UUID) {
            checkMain()
            ProcessMediaGate.observers.remove(id)
        }

        // Обработчик может отписаться во время вызова. Поэтому обходим копию списка.
        private fun notifyPreviewAvailability() {
            if (previewsAllowed) ProcessMediaGate.observers.values.toList().forEach { it() }
        }

        // Обходит копию набора, поскольку остановка может освободить место и изменить исходный
        // список.
        private fun stopPreviews() {
            ProcessMediaGate.previews.values.toList().forEach { it() }
        }

        private fun clearDrain(id: UUID) {
            if (ProcessMediaGate.drain?.first == id) ProcessMediaGate.drain = null
        }
    }

    private var closed = false
    private var playbackID: UUID? = null
    private var captureID: UUID? = null
    private var pendingCapture: PendingCapture? = null
    private var pendingPlayback: PendingPlayback? = null
    private var stopper: ((() -> Unit) -> Unit)? = null
    @Volatile
    var playbackActiveCount = 0
        private set

    @Volatile
    var captureActiveCount = 0
        private set

    // После закрытия владельца новый обработчик не устанавливается.
    fun installPlaybackStopper(stop: (() -> Unit) -> Unit) {
        checkMain()
        if (!closed) stopper = stop
    }

    private fun <T> rejected(error: MediaError, completion: (Result<T>) -> Unit): Cancellation {
        val token = MediaCancellation()
        token.onCancel {
            main.post { completion(Result.failure(MediaException(MediaError.CANCELLED))) }
        }
        main.post { if (token.finish()) completion(Result.failure(MediaException(error))) }
        return token
    }

    fun requestPlayback(completion: (Result<PlaybackLease>) -> Unit): Cancellation {
        checkMain()
        if (closed) return rejected(MediaError.CANCELLED, completion)
        if (
            captureID != null ||
                pendingCapture != null ||
                playbackID != null ||
                ProcessMediaGate.playback != null ||
                ProcessMediaGate.capture != null ||
                ProcessMediaGate.pendingCapture != null
        )
            return rejected(MediaError.UNAVAILABLE, completion)
        val id = UUID.randomUUID()
        val token = MediaCancellation()
        playbackID = id
        ProcessMediaGate.playback = id
        playbackActiveCount = 1
        pendingPlayback = PendingPlayback(id, token, completion)
        token.onCancel {
            main.post {
                val p = pendingPlayback
                if (p?.id == id) {
                    pendingPlayback = null
                    clearDrain(id)
                    if (ProcessMediaGate.playback == id) ProcessMediaGate.playback = null
                    if (playbackID == id) {
                        playbackID = null
                        playbackActiveCount = 0
                    }
                    p.completion(Result.failure(MediaException(MediaError.CANCELLED)))
                    tryGrant()
                    notifyPreviewAvailability()
                }
            }
        }
        ProcessMediaGate.drain = id to { tryGrantPlayback() }
        stopPreviews()
        tryGrantPlayback()
        return token
    }

    private fun tryGrantPlayback() {
        val p = pendingPlayback ?: return
        if (
            closed ||
                pendingCapture != null ||
                ProcessMediaGate.previews.isNotEmpty() ||
                p.scheduled
        )
            return
        p.scheduled = true
        main.post {
            if (pendingPlayback?.id == p.id && p.token.finish()) {
                pendingPlayback = null
                clearDrain(p.id)
                if (closed) {
                    if (ProcessMediaGate.playback == p.id) ProcessMediaGate.playback = null
                    playbackID = null
                    playbackActiveCount = 0
                    p.completion(Result.failure(MediaException(MediaError.CANCELLED)))
                    notifyPreviewAvailability()
                } else
                    p.completion(
                        Result.success(
                            PlaybackLease {
                                releaseOnMain {
                                    if (ProcessMediaGate.playback == p.id)
                                        ProcessMediaGate.playback = null
                                    if (playbackID == p.id) {
                                        playbackID = null
                                        playbackActiveCount = 0
                                        tryGrant()
                                    }
                                    notifyPreviewAvailability()
                                }
                            }
                        )
                    )
            }
        }
    }

    override fun acquire(
        kind: CaptureKind,
        completion: (Result<CaptureLease>) -> Unit,
    ): Cancellation {
        checkMain()
        if (closed) return rejected(MediaError.CANCELLED, completion)
        if (
            captureID != null ||
                pendingCapture != null ||
                ProcessMediaGate.capture != null ||
                ProcessMediaGate.pendingCapture != null ||
                (ProcessMediaGate.playback != null && ProcessMediaGate.playback != playbackID)
        )
            return rejected(MediaError.UNAVAILABLE, completion)
        val token = MediaCancellation()
        val p = PendingCapture(kind, token, completion)
        pendingCapture = p
        ProcessMediaGate.pendingCapture = p.id
        token.onCancel {
            main.post {
                if (pendingCapture?.id == p.id) {
                    pendingCapture = null
                    clearDrain(p.id)
                    if (ProcessMediaGate.pendingCapture == p.id)
                        ProcessMediaGate.pendingCapture = null
                    if (ProcessMediaGate.capture == p.id) ProcessMediaGate.capture = null
                    if (captureID == p.id) {
                        captureID = null
                        captureActiveCount = 0
                    }
                    p.completion(Result.failure(MediaException(MediaError.CANCELLED)))
                    tryGrantPlayback()
                    notifyPreviewAvailability()
                }
            }
        }
        ProcessMediaGate.drain = p.id to { tryGrant() }
        stopPreviews()
        val acknowledged = {
            main.post {
                val current = pendingCapture
                if (current?.id == p.id) {
                    current.stopped = true
                    tryGrant()
                }
            }
            Unit
        }
        stopper?.invoke(acknowledged) ?: acknowledged()
        return token
    }

    // Выдаёт ожидающее разрешение записи только после подтверждения остановки и освобождения
    // конкурирующих
    // владельцев.
    private fun tryGrant() {
        val p = pendingCapture ?: return
        if (
            closed ||
                playbackID != null ||
                ProcessMediaGate.playback != null ||
                ProcessMediaGate.capture != null ||
                ProcessMediaGate.previews.isNotEmpty() ||
                ProcessMediaGate.pendingCapture != p.id ||
                !p.stopped ||
                p.scheduled
        )
            return
        p.scheduled = true
        captureID = p.id
        ProcessMediaGate.capture = p.id
        ProcessMediaGate.pendingCapture = null
        captureActiveCount = 1
        clearDrain(p.id)
        main.post {
            if (pendingCapture?.id == p.id && p.token.finish()) {
                pendingCapture = null
                if (closed) {
                    if (ProcessMediaGate.capture == p.id) ProcessMediaGate.capture = null
                    captureID = null
                    captureActiveCount = 0
                    p.completion(Result.failure(MediaException(MediaError.CANCELLED)))
                    notifyPreviewAvailability()
                } else
                    p.completion(
                        Result.success(
                            CaptureLease(p.kind) {
                                releaseOnMain {
                                    if (ProcessMediaGate.capture == p.id)
                                        ProcessMediaGate.capture = null
                                    if (captureID == p.id) {
                                        captureID = null
                                        captureActiveCount = 0
                                    }
                                    notifyPreviewAvailability()
                                }
                            }
                        )
                    )
            }
        }
    }

    fun close() {
        checkMain()
        if (closed) return
        closed = true
        pendingCapture?.token?.cancel()
        pendingPlayback?.token?.cancel()
        val stop = stopper
        stopper = null
        stop?.invoke {}
    }
}

internal class PreviewLifetime(private var lease: PlaybackLease?) {
    private var work = 0
    private var retired = false

    fun begin() {
        check(!retired)
        work++
    }

    fun end() {
        check(work > 0)
        work--
        releaseIfRetired()
    }

    fun retire() {
        retired = true
        releaseIfRetired()
    }

    private fun releaseIfRetired() {
        if (retired && work == 0) {
            lease?.release()
            lease = null
        }
    }
}
