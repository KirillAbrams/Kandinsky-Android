package dev.kirill.kandinsky.chat.network

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncSequenceTest {
    /**
     * Передаёт непрерывную группу событий после сохранённой позиции
     * Требует новую позицию ровно по последнему принятому событию
     */
    @Test fun contiguousBatchAdvancesOnce() { assertEquals(43L, SyncSequence.next(40, listOf(41, 42, 43))) }
    /**
     * Передаёт прежние и повторяющиеся номера вместе с новыми событиями
     * Проверяет продвижение только по непрерывному новому продолжению без двойного учёта
     */
    @Test fun duplicateReplayDoesNotAdvanceTwice() { assertEquals(43L, SyncSequence.next(41, listOf(40, 41, 42, 42, 43))) }
    /**
     * Передаёт пустую группу после сохранённой позиции истории
     * Требует прежнее значение без дополнительного продвижения
     */
    @Test fun emptyBatchPreservesCursor() { assertEquals(40L, SyncSequence.next(40, emptyList())) }
    /**
     * Передаёт группу с пропущенным номером между новыми событиями
     * Требует отказа вместо принятия неполной последовательности
     */
    @Test(expected = IllegalArgumentException::class)
    fun gapIsRejectedBeforeApplyingTheBatch() { SyncSequence.next(40, listOf(41, 43)) }
    /**
     * Передаёт группу событий больше установленного предела
     * Требует отказа до вычисления новой подтверждённой позиции
     */
    @Test(expected = IllegalArgumentException::class)
    fun oversizedBatchIsRejected() { SyncSequence.next(0, (1L..129L).toList()) }
    /**
     * Передаёт отрицательный номер события в новой группе
     * Проверка последовательности должна отклонить его как недопустимый
     */
    @Test(expected = IllegalArgumentException::class)
    fun negativeSequenceIsRejected() { SyncSequence.next(0, listOf(-1)) }
}
