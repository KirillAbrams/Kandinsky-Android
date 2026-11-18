package dev.kirill.kandinsky.chat.rendering

import org.junit.Assert.*
import org.junit.Test

class MediaPreparationWindowTest {
    @Test
    fun leadStaysWithinThreeShortViewportsBeforeTheNextMotionSample() {
        val window = MediaPreparationWindow()
        window.observe(10000.0, 1.0, 150.0, 1.0)
        assertTrue(window.before <= 450)
        assertTrue(window.after <= 450)
        window.observe(8000.0, 1.02, 150.0, 1.0)
        assertTrue(window.before <= 450)
        window.observe(8000.0, 1.021, 60.0, 1.0)
        assertTrue(window.before <= 180)
        assertTrue(window.after <= 180)
        window.observe(8000.0, 2.0, 60.0, 1.0)
        assertTrue(window.before <= 180)
    }

    @Test
    fun directionSpeedIdleAndRebaseStayBounded() {
        val window = MediaPreparationWindow()
        window.observe(10000.0, 1.0, 680.0, 1.0)
        window.observe(8200.0, 1.02, 680.0, 1.0)
        assertEquals(2040.0, window.before, 0.001)
        assertEquals(272.0, window.after, 0.001)
        window.rebase(50000.0)
        window.observe(58200.0, 1.04, 680.0, 1.0)
        assertEquals(2040.0, window.before, 0.001)
        window.observe(60000.0, 1.06, 680.0, 1.0)
        assertEquals(272.0, window.before, 0.001)
        assertEquals(2040.0, window.after, 0.001)
        window.observe(Double.POSITIVE_INFINITY, 1.08, 680.0, 1.0)
        assertEquals(2040.0, window.after, 0.001)
        window.observe(60001.0, 2.0, 680.0, 1.0)
        assertEquals(500.0, window.before, 0.001)
        assertEquals(272.0, window.after, 0.001)
    }
}
