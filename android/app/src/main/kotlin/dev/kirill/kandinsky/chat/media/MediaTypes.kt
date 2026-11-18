// Описывает ошибки и запросы медиа, а также общий порядок отмены и освобождения камеры и
// проигрывателя.

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

data class ThumbnailKey(
    val maxPixel: Int = 1024,
    val studioOriginal: Boolean = false,
    val gallery: Boolean = false,
)

class MediaCancellation : Cancellation {
    private val gate = Any()
    private var state = 0
    private var handler: (() -> Unit)? = null
    private var progress: ((Double) -> Unit)? = null

    // Сохраняет получателя хода только для активной работы. Завершившаяся передача не может
    // принять новую подписку.
    fun onProgress(action: (Double) -> Unit) {
        synchronized(gate) { if (state == 0) progress = action }
    }

    // Отправляет допустимую долю только действующему получателю и вызывает его вне блокировки.
    // Позднее обновление не держит завершённый запрос.
    fun reportProgress(value: Double) {
        val action = synchronized(gate) { if (state == 0) progress else null }
        action?.invoke(value.coerceIn(0.0, 1.0))
    }

    // Сохраняет действие для текущей работы или сразу вызывает его при уже отданной отмене. Так
    // сетевой запрос получит отмену даже после поздней установки обработчика.
    fun onCancel(action: () -> Unit) {
        val now =
            synchronized(gate) {
                if (state == 0) handler = action
                state == 1
            }
        if (now) action()
    }

    // Отмечает отмену один раз, очищает ссылки на обработчики и вызывает сохранённое действие вне
    // блокировки. Это позволяет остановке завершить связанную работу самостоятельно.
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

    // Отмечает конец только активной работы и очищает обе подписки. Код выдачи может отличить
    // завершение от отмены, не отдавая ненужный результат.
    fun finish(): Boolean =
        synchronized(gate) {
            if (state != 0) return false
            state = 2
            handler = null
            progress = null
            true
        }

    val isCancelled
        // Читает признак отмены под той же блокировкой, что и запись. Поток загрузки может
        // проверить его перед следующим шагом.
        get() = synchronized(gate) { state == 1 }
}

class CaptureLease(val kind: CaptureKind, private val released: () -> Unit) {
    private val once = AtomicBoolean(false)

    // Возвращает право камеры при первом вызове. Повторное закрытие не меняет очередь съёмки.
    fun release() {
        if (once.compareAndSet(false, true)) released()
    }
}

class PlaybackLease(private val released: () -> Unit) {
    private val once = AtomicBoolean(false)

    // Возвращает место проигрывателя один раз. Поздний вызов не должен снять место уже нового
    // просмотра.
    fun release() {
        if (once.compareAndSet(false, true)) released()
    }
}

interface CaptureAdmission {
    // Задаёт запрос на камеру и возвращает средство отмены, пока ответ ещё ожидается. Экран может
    // уйти до выдачи права.
    fun acquire(kind: CaptureKind, completion: (Result<CaptureLease>) -> Unit): Cancellation
}
