// Ограничивает обычный просмотр, тихие ролики и камеру на уровне приложения. Передача права ждёт
// настоящего выхода прежнего владельца.

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

        // Требует поток экрана перед изменением общих прав. Это не даёт двум потокам выдать одно
        // место одновременно.
        private fun checkMain() {
            check(Looper.myLooper() == Looper.getMainLooper())
        }

        // Возвращает право в том же потоке, где его выдали. Если закрытие пришло из фона, ставит
        // возврат в очередь экрана.
        private fun releaseOnMain(action: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
        }

        val previewOwnerCount
            // Читает общее количество открытых тихих просмотров. Очередь может учесть занятые
            // места до запуска следующего.
            get() = ProcessMediaGate.previews.size

        val previewsAllowed
            // Проверяет, что большой просмотр и камера свободны и съёмка не ждёт. При ожидающей
            // съёмке новые тихие места уже закрыты.
            get() =
                ProcessMediaGate.playback == null &&
                    ProcessMediaGate.capture == null &&
                    ProcessMediaGate.pendingCapture == null

        // Проверяет предел и добавляет остановку для тихого ролика. Когда последний возвращает
        // место, уведомляет того, кто ждал полной остановки.
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

        // Запоминает получателя под отдельным номером. Экран сможет снова попросить подготовку
        // после освобождения места.
        fun observePreviewAvailability(action: () -> Unit): UUID {
            checkMain()
            val id = UUID.randomUUID()
            ProcessMediaGate.observers[id] = action
            return id
        }

        // Снимает подписку в потоке экрана. Старый элемент не удерживается общим списком
        // получателей.
        fun removePreviewObserver(id: UUID) {
            checkMain()
            ProcessMediaGate.observers.remove(id)
        }

        // Обходит копию подписок, только если места снова доступны. Подписчик может снять себя,
        // не пропуская других.
        private fun notifyPreviewAvailability() {
            if (previewsAllowed) ProcessMediaGate.observers.values.toList().forEach { it() }
        }

        // Вызывает остановку у копии списка. Старые места могут вернуться сразу, не ломая обход
        // оставшихся.
        private fun stopPreviews() {
            ProcessMediaGate.previews.values.toList().forEach { it() }
        }

        // Убирает действие после выхода, если номер совпал с ожидавшим. Поздняя отмена не стирает
        // другой запрос.
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

    // Передаёт запросу камеры способ дождаться остановки большого просмотра. Для закрытого
    // владельца обработчик не сохраняется.
    fun installPlaybackStopper(stop: (() -> Unit) -> Unit) {
        checkMain()
        if (!closed) stopper = stop
    }

    // Ставит отказ в очередь экрана и допускает отмену до выдачи. Получатель получает только один
    // исход.
    private fun <T> rejected(error: MediaError, completion: (Result<T>) -> Unit): Cancellation {
        val token = MediaCancellation()
        token.onCancel {
            main.post { completion(Result.failure(MediaException(MediaError.CANCELLED))) }
        }
        main.post { if (token.finish()) completion(Result.failure(MediaException(error))) }
        return token
    }

    // Занимает общий номер просмотра, просит тихие ролики закрыться и ждёт. При отмене снимает
    // ожидание и освобождает только свой номер.
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

    // Выдаёт право, когда не осталось тихих владельцев и камера не ждёт. После возврата права
    // общая очередь может продолжить.
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

    // Бронирует ожидающую съёмку, закрывает тихий показ и просит подтверждение остановки большого
    // просмотра. Снимает ожидание при отмене до выдачи камеры.
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

    // Проверяет подтверждение остановки и отсутствие других владельцев. Потом выдаёт разрешение
    // камере и помнит его до закрытия съёмки.
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

    // Прекращает выдачу новых прав и отменяет ожидающие и текущие действия просмотра. Запоздалый
    // экран не продолжит запуск.
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

    // При открытом праве добавляет одну работу просмотра. Закрытый просмотр не начинает чтение
    // снова.
    fun begin() {
        check(!retired)
        work++
    }

    // Снимает одну законченную работу и пробует вернуть место. Если закрытие уже запрошено,
    // последняя работа открывает путь следующей.
    fun end() {
        check(work > 0)
        work--
        releaseIfRetired()
    }

    // Отмечает конец использования, чтобы право вернулось после оставшихся операций. Это не даёт
    // открыть новый проигрыватель, пока прежний закрывается.
    fun retire() {
        retired = true
        releaseIfRetired()
    }

    // Возвращает место после снятия просмотра и завершения всех работ, затем убирает ссылку.
    // Место освобождается только один раз.
    private fun releaseIfRetired() {
        if (retired && work == 0) {
            lease?.release()
            lease = null
        }
    }
}
