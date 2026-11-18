package dev.kirill.kandinsky.chat.domain

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class StudioTransferTest {
    private val source =
        StudioTransfer(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            250,
            1000,
            "image/jpeg",
        )

    @Test
    fun partialByteCountProducesAValidPercentageWithoutRoundingToCompletion() {
        assertTrue(source.isValid)
        assertEquals(0.25, source.progress, 0.000001)
        assertTrue(source.copy(received = 1000).isValid)
    }

    @Test
    fun impossibleByteCountsAndMalformedIdentitiesAreRejected() {
        for (invalid in
            listOf(
                source.copy(received = -1),
                source.copy(received = 1001),
                source.copy(total = 0),
                source.copy(total = 20 * 1024 * 1024L + 1),
                source.copy(id = "not-an-upload"),
                source.copy(deviceId = "not-a-device"),
            )) assertFalse(invalid.toString(), invalid.isValid)
    }
}
