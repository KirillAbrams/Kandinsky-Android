package dev.kirill.kandinsky.chat.network

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncSequenceTest {
    @Test
    fun contiguousBatchAdvancesOnce() {
        assertEquals(43L, SyncSequence.next(40, listOf(41, 42, 43)))
    }

    @Test
    fun duplicateReplayDoesNotAdvanceTwice() {
        assertEquals(43L, SyncSequence.next(41, listOf(40, 41, 42, 42, 43)))
    }

    @Test
    fun emptyBatchPreservesCursor() {
        assertEquals(40L, SyncSequence.next(40, emptyList()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun gapIsRejectedBeforeApplyingTheBatch() {
        SyncSequence.next(40, listOf(41, 43))
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedBatchIsRejected() {
        SyncSequence.next(0, (1L..129L).toList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeSequenceIsRejected() {
        SyncSequence.next(0, listOf(-1))
    }
}
