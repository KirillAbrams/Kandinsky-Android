// Хранит получателей общего запроса и останавливает передачу сразу после ухода последнего. Снять
// подписку можно, пока очередь экрана ещё занята.

package dev.kirill.kandinsky.chat.media

import java.util.UUID

// Последний получатель останавливает передачу, даже когда очередь экрана ещё занята.
internal class MediaRequestOwners {
    val cancellation = MediaCancellation()
    private val gate = Any()
    private val owners = mutableSetOf<UUID>()
    private var closed = false

    // Добавляет получателя только в открытую передачу под общей блокировкой. Поздняя подписка
    // получает отказ и сможет выбрать новый запрос.
    fun insert(owner: UUID): Boolean =
        synchronized(gate) {
            if (closed) false
            else {
                owners.add(owner)
                true
            }
        }

    // Убирает известного получателя и закрывает запрос, если список опустел. Повторное удаление
    // неизвестного получателя не отменяет чужую работу.
    fun remove(owner: UUID) {
        val shouldCancel =
            synchronized(gate) {
                (owners.remove(owner) && owners.isEmpty()).also { if (it) closed = true }
            }
        if (shouldCancel) cancellation.cancel()
    }

    val isClosed
        // Читает признак закрытия под той же блокировкой, что и запись. Очередь может безопасно
        // выбрать новую передачу вместо закрытой.
        get() = synchronized(gate) { closed }

    // Закрывает запрос для новых подписок, очищает список и вызывает отмену. Даже если подписчики
    // не снялись по одному, сеть больше не нужна.
    fun close() {
        synchronized(gate) {
            closed = true
            owners.clear()
        }
        cancellation.cancel()
    }
}
