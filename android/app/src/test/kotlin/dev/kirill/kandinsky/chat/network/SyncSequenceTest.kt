// Проверяем продвижение номера последнего события и отклонение порций, которые нельзя применить
// последовательно.

package dev.kirill.kandinsky.chat.network

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncSequenceTest {
    // Передаём три соседних номера после сохранённого. Получаем последний номер такой порции,
    // чтобы при следующем чтении продолжить с правильного места.
    @Test
    fun contiguousBatchAdvancesOnce() {
        assertEquals(43L, SyncSequence.next(40, listOf(41, 42, 43)))
    }

    // Повторяем старые номера и дубли внутри порции. Сдвиг учитывает только новые события по
    // порядку.
    @Test
    fun duplicateReplayDoesNotAdvanceTwice() {
        assertEquals(43L, SyncSequence.next(41, listOf(40, 41, 42, 42, 43)))
    }

    // Передаём пустую порцию и проверяем прежний номер. Без событий точка следующего запроса не
    // должна сдвигаться.
    @Test
    fun emptyBatchPreservesCursor() {
        assertEquals(40L, SyncSequence.next(40, emptyList()))
    }

    // Пропускаем один ожидаемый номер и требуем ошибку. Продолжение через пробел могло бы
    // навсегда потерять событие.
    @Test(expected = IllegalArgumentException::class)
    fun gapIsRejectedBeforeApplyingTheBatch() {
        SyncSequence.next(40, listOf(41, 43))
    }

    // Передаём 129 событий и требуем отказ. Порция за предельным размером не должна пройти
    // дальше.
    @Test(expected = IllegalArgumentException::class)
    fun oversizedBatchIsRejected() {
        SyncSequence.next(0, (1L..129L).toList())
    }

    // Передаём отрицательный номер и проверяем отказ. Неверное значение не может стать частью
    // позиции синхронизации.
    @Test(expected = IllegalArgumentException::class)
    fun negativeSequenceIsRejected() {
        SyncSequence.next(0, listOf(-1))
    }
}
