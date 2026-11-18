// Управляет системным проигрывателем в рабочем потоке и передаёт состояние в поток экрана.
// Поддерживает местный файл и чтение видео по частям.

package dev.kirill.kandinsky.chat.media

import android.media.MediaPlayer
import android.os.Handler
import android.view.Surface
import java.io.File
import java.io.FileInputStream

internal class PlayerSession(
    private val worker: Handler,
    private val main: Handler,
    private val tally: (Int) -> Unit,
    private val changed: (Long, Long, Boolean) -> Unit,
    private val ready: (Result<Long>) -> Unit,
    private val buffering: (Boolean) -> Unit = {},
    private val loop: Boolean = false,
    private val reportProgress: Boolean = false,
) {
    @Volatile private var closing = false
    private var native: MediaPlayer? = null
    @Volatile private var source: MediaPlaybackSource? = null
    private var attachedOutput: Surface? = null

    private var prepared = false
    private var refreshAfterSeek = false
    private var seekDone: ((Boolean) -> Unit)? = null

    private val clock =
        object : Runnable {
            // Пока ролик играет, считывает время и продолжительность и повторяет сообщение через
            // четверть секунды. Пауза и закрытие прекращают обновления.
            override fun run() {
                val p = native ?: return
                if (!closing && prepared && p.isPlaying) {
                    val pos = p.currentPosition.toLong()
                    val duration = p.duration.toLong()
                    main.post { if (!closing) changed(pos, duration, true) }
                    worker.postDelayed(this, 250)
                }
            }
        }

    // Заворачивает готовый файл в обычный источник и передаёт общему открытию. Местный ролик идёт
    // тем же путём, что и сетевой.
    fun open(file: File) {
        open(MediaPlaybackSource(file = file))
    }

    // Настраивает проигрыватель, подключает файл или части и готовит видео вне потока экрана.
    // Передаёт готовность, перемотку, буферизацию и отказ только пока сеанс жив.
    fun open(source: MediaPlaybackSource) {
        this.source = source
        worker.post {
            if (closing) return@post
            try {
                val p = MediaPlayer()
                native = p
                tally(1)
                p.setVolume(0f, 0f)
                p.isLooping = loop
                p.setOnSeekCompleteListener { value ->
                    val pos = value.currentPosition.toLong()
                    val duration = value.duration.toLong()
                    val playing = value.isPlaying
                    val done = seekDone
                    seekDone = null
                    main.post {
                        if (!closing) {
                            changed(pos, duration, playing)
                            done?.invoke(true)
                        } else done?.invoke(false)
                    }
                    if (refreshAfterSeek) {
                        refreshAfterSeek = false
                        refreshFrame(value)
                    }
                }
                p.setOnCompletionListener { value ->
                    val duration = value.duration.toLong()
                    main.post { if (!closing) changed(duration, duration, false) }
                }
                p.setOnInfoListener { _, what, _ ->
                    if (
                        what == MediaPlayer.MEDIA_INFO_BUFFERING_START ||
                            what == MediaPlayer.MEDIA_INFO_BUFFERING_END
                    )
                        main.post {
                            if (!closing) buffering(what == MediaPlayer.MEDIA_INFO_BUFFERING_START)
                        }
                    false
                }
                p.setOnErrorListener { _, _, _ ->
                    main.post {
                        if (!closing) ready(Result.failure(MediaException(MediaError.CORRUPT)))
                    }
                    true
                }
                if (source.dataSource != null) p.setDataSource(source.dataSource)
                else FileInputStream(checkNotNull(source.file)).use { p.setDataSource(it.fd) }
                p.prepare()
                prepared = true
                val duration = p.duration.toLong()
                main.post { if (!closing) ready(Result.success(duration)) }
            } catch (t: Throwable) {
                main.post { if (!closing) ready(Result.failure(t)) }
            }
        }
    }

    // Запускает уже подготовленное видео и включает отчёт времени, если он нужен экрану. Убирает
    // прежний таймер, чтобы обновления не дублировались.
    fun resume() {
        worker.post {
            val p = native
            if (!closing && prepared && p != null) {
                runCatching {
                        p.start()
                        worker.removeCallbacks(clock)
                        if (reportProgress) worker.post(clock)
                    }
                    .onFailure { main.post { ready(Result.failure(it)) } }
            }
        }
    }

    // Останавливает отчёт и ставит проигрыватель на паузу, затем возвращает текущую позицию.
    // Позже можно продолжить с того же места.
    fun pause(position: ((Long) -> Unit)? = null) {
        worker.post {
            worker.removeCallbacks(clock)
            val p = native
            if (!closing && prepared && p != null) {
                if (p.isPlaying) p.pause()
                val value = p.currentPosition.toLong()
                main.post { if (!closing) position?.invoke(value) }
            }
        }
    }

    // Передаёт новую позицию в рабочую очередь и завершает прежний ожидающий ответ отказом.
    // Неподготовленный или закрытый проигрыватель не обещает перемотку.
    fun seek(ms: Long, completion: (Boolean) -> Unit) {
        worker.post {
            val p = native
            if (closing || !prepared || p == null) {
                main.post { completion(false) }
                return@post
            }
            val previous = seekDone
            seekDone = completion
            if (previous != null) main.post { previous(false) }
            try {
                p.seekTo(ms, MediaPlayer.SEEK_CLOSEST)
            } catch (t: Throwable) {
                seekDone = null
                main.post { completion(false) }
            }
        }
    }

    // Просит снова вывести кадр около текущей позиции, если видео стоит. При незаконченной
    // перемотке откладывает обновление, иначе новый кадр мог бы оказаться не с того места.
    private fun refreshFrame(p: MediaPlayer) {
        if (!closing && prepared) {
            if (seekDone != null) {
                refreshAfterSeek = true
            } else
                p.seekTo(
                    p.currentPosition.toLong().coerceAtMost(maxOf(0, p.duration - 1).toLong()),
                    MediaPlayer.SEEK_CLOSEST,
                )
        }
    }

    // Меняет поверхность на рабочем потоке и при необходимости обновляет кадр на паузе. После
    // ответа старый вывод можно освободить.
    fun attach(surface: Surface?, completion: () -> Unit) {
        worker.post {
            if (!closing && attachedOutput !== surface)
                runCatching {
                    native?.let { p ->
                        p.setSurface(surface)
                        attachedOutput = surface
                        if (surface != null && prepared && !p.isPlaying && p.currentPosition > 0)
                            refreshFrame(p)
                    }
                }
            main.post(completion)
        }
    }

    // Закрывает чтение, затем снимает поверхность, слушателей и проигрыватель. Возвращает
    // последнюю позицию, завершает отложенную перемотку отказом и сообщает, что ресурсы
    // освобождены.
    fun release(position: ((Long) -> Unit)? = null, completed: () -> Unit) {
        if (closing) {
            worker.post { main.post(completed) }
            return
        }
        closing = true
        source?.close()
        source = null
        worker.post {
            worker.removeCallbacks(clock)
            val p = native
            native = null
            val saved = if (p != null && prepared) runCatching { p.currentPosition.toLong() }.getOrNull() else null
            if (p != null) {
                runCatching { p.setSurface(null) }
                p.setOnSeekCompleteListener(null)
                p.setOnCompletionListener(null)
                p.setOnErrorListener(null)
                p.setOnInfoListener(null)
                p.release()
                tally(-1)
            }
            prepared = false
            attachedOutput = null
            val pending = seekDone
            seekDone = null
            main.post {
                pending?.invoke(false)
                saved?.let { position?.invoke(it) }
                completed()
            }
        }
    }
}
