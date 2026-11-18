package dev.kirill.kandinsky.chat.media.kernel

import org.junit.Assert.*
import org.junit.Test

internal class PhotoPresentationTest {
    private val expected =
        arrayOf(
            arrayOf(
                doubleArrayOf(16.0, 8.0),
                doubleArrayOf(48.0, 8.0),
                doubleArrayOf(16.0, 24.0),
                doubleArrayOf(48.0, 24.0),
            ),
            arrayOf(
                doubleArrayOf(48.0, 8.0),
                doubleArrayOf(16.0, 8.0),
                doubleArrayOf(48.0, 24.0),
                doubleArrayOf(16.0, 24.0),
            ),
            arrayOf(
                doubleArrayOf(48.0, 24.0),
                doubleArrayOf(16.0, 24.0),
                doubleArrayOf(48.0, 8.0),
                doubleArrayOf(16.0, 8.0),
            ),
            arrayOf(
                doubleArrayOf(16.0, 24.0),
                doubleArrayOf(48.0, 24.0),
                doubleArrayOf(16.0, 8.0),
                doubleArrayOf(48.0, 8.0),
            ),
            arrayOf(
                doubleArrayOf(8.0, 16.0),
                doubleArrayOf(8.0, 48.0),
                doubleArrayOf(24.0, 16.0),
                doubleArrayOf(24.0, 48.0),
            ),
            arrayOf(
                doubleArrayOf(24.0, 16.0),
                doubleArrayOf(24.0, 48.0),
                doubleArrayOf(8.0, 16.0),
                doubleArrayOf(8.0, 48.0),
            ),
            arrayOf(
                doubleArrayOf(24.0, 48.0),
                doubleArrayOf(24.0, 16.0),
                doubleArrayOf(8.0, 48.0),
                doubleArrayOf(8.0, 16.0),
            ),
            arrayOf(
                doubleArrayOf(8.0, 48.0),
                doubleArrayOf(8.0, 16.0),
                doubleArrayOf(24.0, 48.0),
                doubleArrayOf(24.0, 16.0),
            ),
        )

    private fun orientation(o: Int) {
        val p =
            PhotoPresentation.filling(
                8,
                4,
                o,
                if (o >= 5) 32.0 else 64.0,
                if (o >= 5) 64.0 else 32.0,
            )
        assertNotNull("PHOTO_PRESENTATION_VALID_" + o, p)
        val points = arrayOf(2.0 to 1.0, 6.0 to 1.0, 2.0 to 3.0, 6.0 to 3.0)
        points.forEachIndexed { i, q ->
            val v = p!!.point(q.first, q.second)
            assertEquals(expected[o - 1][i][0], v.first, 1e-8)
            assertEquals(expected[o - 1][i][1], v.second, 1e-8)
        }
    }

    @Test
    fun orientation1() {
        orientation(1)
    }

    @Test
    fun orientation2() {
        orientation(2)
    }

    @Test
    fun orientation3() {
        orientation(3)
    }

    @Test
    fun orientation4() {
        orientation(4)
    }

    @Test
    fun orientation5() {
        orientation(5)
    }

    @Test
    fun orientation6() {
        orientation(6)
    }

    @Test
    fun orientation7() {
        orientation(7)
    }

    @Test
    fun orientation8() {
        orientation(8)
    }

    @Test
    fun fill() {
        val p = PhotoPresentation.filling(8, 4, 1, 80.0, 80.0)!!
        assertEquals(20.0, p.a, 0.0)
        assertEquals(20.0, p.d, 0.0)
        assertEquals(-40.0, p.tx, 0.0)
        assertEquals(0.0, p.ty, 0.0)
    }

    @Test
    fun original2048FillsViewportWithCenteredCrop() {
        val p = PhotoPresentation.filling(2048, 1536, 1, 390.0, 844.0)
        assertNotNull("An original decode must reach native presentation", p)
        assertEquals(-367.6667, p!!.point(0.0, 0.0).first, 0.001)
        assertEquals(0.0, p.point(0.0, 0.0).second, 0.001)
        assertEquals(757.6667, p.point(2048.0, 1536.0).first, 0.001)
        assertEquals(844.0, p.point(2048.0, 1536.0).second, 0.001)
        val rotated = PhotoPresentation.filling(2048, 1536, 6, 390.0, 844.0)!!
        assertEquals(511.5, rotated.point(0.0, 0.0).first, 0.001)
        assertEquals(0.0, rotated.point(0.0, 0.0).second, 0.001)
        assertEquals(-121.5, rotated.point(2048.0, 1536.0).first, 0.001)
        assertEquals(844.0, rotated.point(2048.0, 1536.0).second, 0.001)
    }

    @Test
    fun invalidSource() {
        for (q in
            arrayOf(
                Triple(0, 4, 1),
                Triple(8, 0, 1),
                Triple(2049, 4, 1),
                Triple(8, 4, 0),
                Triple(8, 4, 9),
            )) assertNull(
            PhotoPresentation.filling(q.first, q.second, q.third, 80.0, 80.0)
        )
    }

    @Test
    fun invalidViewport() {
        for (q in
            arrayOf(
                0.0 to 80.0,
                80.0 to -1.0,
                Double.NaN to 80.0,
                80.0 to Double.POSITIVE_INFINITY,
                8193.0 to 80.0,
            )) assertNull(
            PhotoPresentation.filling(8, 4, 1, q.first, q.second)
        )
    }

    @Test
    fun stable() {
        val p = PhotoPresentation.filling(8, 4, 1, 80.0, 80.0)
        assertNotNull(p)
        repeat(1000) {
            assertEquals(p, PhotoPresentation.filling(8, 4, 1, 80.0, 80.0))
        }
    }

    @Test
    fun rotationSixFillsSquare() {
        val p = PhotoPresentation.filling(100, 50, 6, 200.0, 200.0)!!
        assertEquals(200.0, p.point(0.0, 0.0).first, 0.0)
        assertEquals(-100.0, p.point(0.0, 0.0).second, 0.0)
    }
}

private fun PhotoPresentation.point(x: Double, y: Double) =
    (a * x + c * y + tx) to (b * x + d * y + ty)
