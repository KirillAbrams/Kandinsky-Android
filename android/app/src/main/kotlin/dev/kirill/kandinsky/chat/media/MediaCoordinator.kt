package dev.kirill.kandinsky.chat.media

import android.os.*
import android.view.Surface
import dev.kirill.kandinsky.chat.domain.*
import java.util.concurrent.atomic.AtomicInteger

data class PlaybackTarget(
    val attachment: Attachment,
    val messageID: MessageID,
    val kind: MessageKind? = null,
    val loopPreview: Boolean = false,
)

class MediaCoordinator(private val admission: MediaAdmission, private val media: MediaStore) {
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("Kandinsky-one-player").apply { start() }
    private val worker = Handler(thread.looper)
    private var session: PlayerSession? = null
    private var retiring: PlayerSession? = null
    private var lease: PlaybackLease? = null
    private var request: Cancellation? = null
    private var desiredOutput: Surface? = null

    val currentTarget: PlaybackTarget?
        get() = desired

    private var desired: PlaybackTarget? = null
    private var desiredSource: MediaPlaybackSource? = null
    private var wantsPlaying = true
    private var playbackKey: VideoPlaybackKey? = null
    private var cacheGeneration = 0
    private var restoring = false
    private var generation = 0
    private var stopping = false
    private var closed = false
    private val completions = mutableListOf<() -> Unit>()
    private val nativeCount = AtomicInteger()
    private val peak = AtomicInteger()
    val peakNativePlayers
        get() = peak.get()

    val activePlayerCount
        get() = nativeCount.get()

    var currentMessageID: MessageID? = null
        private set

    var isReady = false
        private set

    var isBuffering = false
        private set

    fun retry() {
        desired?.let { play(it, inlineVisible) }
    }

    var isPlaying = false
        private set

    var durationMs = 0L
        private set

    var positionMs = 0L
        private set

    var downloadProgress: Double? = null
        private set

    var lastError: Throwable? = null
        private set

    var onChange: (() -> Unit)? = null
    var onDetachOutput: (() -> Unit)? = null
    var presentationOwner: Any? = null
    val isMuted = true

    private val observers = linkedMapOf<java.util.UUID, () -> Unit>()

    fun observeChanges(callback: () -> Unit): java.util.UUID =
        java.util.UUID.randomUUID().also { observers[it] = callback }

    fun removeChangeObserver(token: java.util.UUID) {
        observers.remove(token)
    }

    var inlineVisible = false
        private set

    fun setInlineVisible(value: Boolean) {
        if (inlineVisible != value) {
            inlineVisible = value
        }
    }

    init {
        admission.installPlaybackStopper { done -> stop(done) }
    }

    fun apply(delta: MessageDelta) {
        val target = desired ?: return
        if (target.messageID in delta.removedIDs) {
            stop()
            return
        }
        val row =
            (delta.updated + delta.inserted).firstOrNull { it.id == target.messageID } ?: return
        val stillPresent =
            row.attachments.any { source ->
                source.relativePath == target.attachment.relativePath &&
                    source.mime == target.attachment.mime &&
                    source.byteSize == target.attachment.byteSize
            }
        if (!stillPresent || target.kind != null && row.kind != target.kind) stop()
    }

    // При получении файла или потокового источника проверяет поколение запроса, чтобы поздний ответ
    // не начал
    // другое воспроизведение.
    fun play(
        target: PlaybackTarget,
        inline: Boolean = false,
        startPaused: Boolean = false,
        localOnly: Boolean = false,
    ) {
        if (closed) return
        stop()
        if (!target.attachment.mime.startsWith("video/")) {
            lastError = MediaException(MediaError.UNAVAILABLE)
            notifyChanged()
            return
        }
        wantsPlaying = !startPaused
        inlineVisible = inline
        desired = target
        currentMessageID = target.messageID
        downloadProgress = null
        lastError = null
        notifyChanged()
        val ticket = generation
        request =
            media.playbackSource(target.attachment, localOnly = localOnly) { result ->
                if (ticket == generation && !closed) {
                    request = null
                    result.fold(
                        { playPrepared(target, it) },
                        {
                            lastError = it
                            notifyChanged()
                        },
                    )
                } else result.getOrNull()?.close()
            }
    }

    private fun playPrepared(target: PlaybackTarget, source: MediaPlaybackSource) {
        if (closed) return
        desired = target
        desiredSource = source
        generation++
        lastError = null
        downloadProgress = null
        notifyChanged()
        if (!stopping) main.post { advance() }
    }

    private fun advance() {
        if (closed || stopping || session != null || request != null) return
        val target = desired ?: return
        val source = desiredSource ?: return
        val ticket = generation
        request =
            admission.requestPlayback { result ->
                request = null
                result.fold(
                    { owner ->
                        if (closed || ticket != generation) {
                            owner.release()
                            main.post { advance() }
                            return@fold
                        }
                        lease = owner
                        currentMessageID = target.messageID
                        isReady = false
                        positionMs = 0
                        playbackKey = VideoPlaybackKey(target.messageID, target.attachment)
                        cacheGeneration = media.videoPlayback.generation
                        restoring = false
                        val next =
                            PlayerSession(
                                worker,
                                main,
                                { delta ->
                                    val count = nativeCount.addAndGet(delta)
                                    peak.updateAndGet { maxOf(it, count) }
                                    main.post { if (!closed) notifyChanged() }
                                },
                                { pos, duration, playing ->
                                    if (ticket == generation && !closed) {
                                        positionMs = pos
                                        durationMs = duration
                                        isPlaying = playing && wantsPlaying
                                        notifyChanged()
                                    }
                                },
                                { prepared ->
                                    if (ticket == generation && !closed)
                                        prepared.fold(
                                            { duration ->
                                                durationMs = duration
                                                val saved = playbackKey?.let { media.videoPlayback.position(it) } ?: 0L
                                                if (saved > 0) {
                                                    restoring = true
                                                    val position = saved.coerceAtMost(maxOf(0, duration - 1))
                                                    session?.seek(position) { done ->
                                                        if (ticket != generation || closed) return@seek
                                                        restoring = false
                                                        isReady = done
                                                        if (done) positionMs = position
                                                        if (done && wantsPlaying) resume()
                                                        notifyChanged()
                                                    }
                                                } else {
                                                    isReady = true
                                                    if (wantsPlaying) resume()
                                                    notifyChanged()
                                                }
                                            },
                                            { error ->
                                                lastError = error
                                                stop()
                                            },
                                        )
                                },
                                { buffering ->
                                    if (ticket == generation && !closed) {
                                        isBuffering = buffering
                                        notifyChanged()
                                    }
                                },
                                loop = target.kind == MessageKind.ROUND || target.loopPreview,
                                reportProgress = true,
                            )
                        session = next
                        try {
                            next.open(source)
                            desiredOutput?.let { next.attach(it) {} }
                        } catch (t: Throwable) {
                            lastError = t
                            stop()
                        }
                    },
                    { error ->
                        if (ticket == generation) {
                            lastError = error
                            desired = null
                            currentMessageID = null
                            notifyChanged()
                        }
                    },
                )
            }
    }

    fun pause() {
        wantsPlaying = false
        isPlaying = false
        isBuffering = false
        val ticket = generation
        session?.pause paused@{ position ->
            if (ticket != generation || wantsPlaying || restoring) return@paused
            positionMs = position
            playbackKey?.let { media.videoPlayback.rememberPosition(position, it, cacheGeneration) }
            notifyChanged()
        }
        notifyChanged()
    }

    fun resume() {
        wantsPlaying = true
        if (closed || !isReady || session == null) return
        isPlaying = true
        session?.resume()
        notifyChanged()
    }

    fun seek(ms: Long, completion: (Boolean) -> Unit = {}) {
        val s = session
        if (s == null) {
            completion(false)
            return
        }
        s.seek(ms.coerceIn(0, maxOf(0, durationMs)), completion)
    }

    internal fun rememberFrame(render: (VideoPlaybackCache, VideoPlaybackKey, Int) -> Unit) {
        if (!restoring) playbackKey?.let { render(media.videoPlayback, it, cacheGeneration) }
    }

    fun attach(surface: Surface?, detached: () -> Unit = {}) {
        desiredOutput = surface
        val s = session ?: retiring
        if (s == null) main.post(detached) else s.attach(surface, detached)
    }

    fun stop(completed: () -> Unit = {}) {
        desired = null
        desiredSource?.close()
        desiredSource = null
        generation++
        stopCurrent(completed)
    }

    private fun stopCurrent(completed: () -> Unit) {
        completions += completed
        request?.cancel()
        request = null
        if (stopping) return
        stopping = true
        isPlaying = false
        isReady = false
        isBuffering = false
        currentMessageID = null
        downloadProgress = null
        inlineVisible = false
        desiredOutput = null
        val old = session
        retiring = old
        onDetachOutput?.invoke()
        session = null
        fun finish() {
            lease?.release()
            lease = null
            stopping = false
            val callbacks = completions.toList()
            completions.clear()
            notifyChanged()
            callbacks.forEach { it() }
            // Новый подготовленный источник мог появиться, пока система освобождала прежний
            main.post { advance() }
        }
        val oldKey = playbackKey
        val oldGeneration = cacheGeneration
        val mayRememberPosition = !restoring
        playbackKey = null
        if (old != null)
            old.release(position = { value ->
                if (oldKey != null && mayRememberPosition)
                    media.videoPlayback.rememberPosition(value, oldKey, oldGeneration)
            }) {
                retiring = null
                finish()
            }
        else main.post { finish() }
    }

    private fun notifyChanged() {
        onChange?.invoke()
        if (observers.isNotEmpty())
            for ((token, callback) in observers.toList()) if (observers[token] === callback)
                callback()
    }

    // После закрытия новые запросы воспроизведения не принимаются.
    fun close() {
        if (closed) return
        closed = true
        observers.clear()
        desired = null
        desiredSource?.close()
        desiredSource = null
        generation++
        stopCurrent {
            onChange = null
            onDetachOutput = null
            thread.quitSafely()
        }
    }
}
