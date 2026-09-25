package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.compass.Heading
import com.mammonrn.phoneaikiosk.compass.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** The compass's and the level's sums (0.62.0, DESIGN.md 5ด). */
class CompassTest {

    @Test
    fun `headings to Thai words, each 45 degrees centred on its point`() {
        assertEquals("เหนือ", Heading.thai(0.0))
        assertEquals("เหนือ", Heading.thai(22.4))
        assertEquals("ตะวันออกเฉียงเหนือ", Heading.thai(22.5))
        assertEquals("ตะวันออกเฉียงเหนือ", Heading.thai(45.0))
        assertEquals("ตะวันออก", Heading.thai(90.0))
        assertEquals("ตะวันออกเฉียงใต้", Heading.thai(135.0))
        assertEquals("ใต้", Heading.thai(180.0))
        assertEquals("ตะวันตกเฉียงใต้", Heading.thai(225.0))
        assertEquals("ตะวันตก", Heading.thai(270.0))
        assertEquals("ตะวันตกเฉียงเหนือ", Heading.thai(315.0))
        assertEquals("ตะวันตกเฉียงเหนือ", Heading.thai(337.4))
        assertEquals("NE", Heading.letters(40.0))
    }

    @Test
    fun `north wraps at 0 and 360`() {
        assertEquals("เหนือ", Heading.thai(337.5))
        assertEquals("เหนือ", Heading.thai(359.9))
        assertEquals("เหนือ", Heading.thai(360.0))
        assertEquals("เหนือ", Heading.thai(-10.0))
        assertEquals("ตะวันตก", Heading.thai(-90.0))
        assertEquals("ตะวันออก", Heading.thai(450.0))
        // Shown degrees are 0..359: 359.6 reads 0, never 360.
        assertEquals(0, Heading.shown(359.6))
        assertEquals(359, Heading.shown(359.4))
        assertEquals(0, Heading.shown(360.0))
        assertEquals(270, Heading.shown(-90.0))
        assertEquals(0.0, Heading.normalize(-0.0), 1e-9)
    }

    @Test
    fun `smoothing takes the short way round north`() {
        assertEquals(10.0, Heading.smooth(null, 10.0, 0.2), 1e-9)
        // From 359 towards 1: forward through 0, not back through 180.
        val a = Heading.smooth(359.0, 1.0, 0.5)
        assertEquals(0.0, a, 1e-9)
        val b = Heading.smooth(1.0, 359.0, 0.5)
        assertEquals(0.0, b, 1e-9)
        val c = Heading.smooth(350.0, 10.0, 0.25)
        assertEquals(355.0, c, 1e-9)
        assertEquals(100.0, Heading.smooth(90.0, 110.0, 0.5), 1e-9)
    }

    @Test
    fun `the figure 8 warning when the magnetometer is unsure or the field is not the Earth's`() {
        assertEquals(Heading.Accuracy.HIGH, Heading.accuracy(3))
        assertEquals(Heading.Accuracy.UNRELIABLE, Heading.accuracy(0))
        assertEquals(Heading.Accuracy.UNKNOWN, Heading.accuracy(-1))
        assertEquals(Heading.Accuracy.UNKNOWN, Heading.accuracy(null))
        assertTrue(Heading.needsFigureEight(Heading.Accuracy.LOW, true))
        assertTrue(Heading.needsFigureEight(Heading.Accuracy.UNRELIABLE, true))
        assertTrue(Heading.needsFigureEight(Heading.Accuracy.HIGH, false))
        assertFalse(Heading.needsFigureEight(Heading.Accuracy.MEDIUM, true))
        assertFalse(Heading.needsFigureEight(Heading.Accuracy.UNKNOWN, true))
        assertTrue(Heading.fieldOk(42.0))
        assertFalse(Heading.fieldOk(120.0))
        assertFalse(Heading.fieldOk(5.0))
    }

    @Test
    fun `upright or flat, with a margin so it does not flick at 45 degrees`() {
        assertFalse(Heading.upright(false, 50.0))
        assertTrue(Heading.upright(false, 60.0))
        assertTrue(Heading.upright(true, 40.0))
        assertFalse(Heading.upright(true, 30.0))
    }

    private val g = 9.81

    /** Gravity as the accelerometer reads it with the right edge lifted by [right]° and the top by [top]°. */
    private fun flat(right: Double, top: Double): Level.Reading.Flat {
        val z = g
        val reading = Level.read(z * Math.tan(Math.toRadians(right)), z * Math.tan(Math.toRadians(top)), z)
        return reading as Level.Reading.Flat
    }

    @Test
    fun `lying flat - two axes, the higher side positive`() {
        val level = flat(0.0, 0.0)
        assertEquals(0.0, level.right, 1e-9)
        assertTrue(level.level)
        val tilted = flat(3.0, -2.0)
        assertEquals(3.0, tilted.right, 1e-6)
        assertEquals(-2.0, tilted.top, 1e-6)
        assertFalse(tilted.level)
        assertEquals(1, Level.side(tilted.right))
        assertEquals(-1, Level.side(tilted.top))
    }

    @Test
    fun `level within plus or minus one degree, inclusive`() {
        assertTrue(flat(1.0, -1.0).level)
        assertTrue(flat(0.99, 0.5).level)
        assertFalse(flat(1.01, 0.0).level)
        assertFalse(flat(0.0, -1.2).level)
        assertEquals(0, Level.side(1.0))
        assertEquals(0, Level.side(-1.0))
        assertEquals(1, Level.side(1.01))
    }

    @Test
    fun `standing on its bottom edge - one axis`() {
        val a = Math.toRadians(2.0)
        val r = Level.read(g * sin(a), g * cos(a), 0.3) as Level.Reading.Upright
        assertEquals(2.0, r.right, 1e-6)
        assertFalse(r.level)
        val ok = Level.read(-g * sin(Math.toRadians(0.5)), g * cos(Math.toRadians(0.5)), 0.0) as Level.Reading.Upright
        assertTrue(ok.level)
        assertEquals(-0.5, ok.right, 1e-6)
    }

    @Test
    fun `on its side, upside down, or falling - no reading`() {
        assertEquals(Level.Reading.Sideways, Level.read(g, 0.5, 0.5))
        assertEquals(Level.Reading.Sideways, Level.read(0.2, -g, 0.5))
        assertNull(Level.read(0.1, 0.1, 0.1))
    }

    @Test
    fun `the bubble stops at its window's edge`() {
        assertEquals(0.3, Level.bubble(3.0), 1e-9)
        assertEquals(1.0, Level.bubble(25.0), 1e-9)
        assertEquals(-1.0, Level.bubble(-40.0), 1e-9)
    }

    @Test
    fun `the low pass moves part of the way`() {
        val first = Level.lowPass(null, doubleArrayOf(1.0, 2.0, 3.0), 0.2)
        assertEquals(listOf(1.0, 2.0, 3.0), first.toList())
        val next = Level.lowPass(first, doubleArrayOf(2.0, 2.0, 3.0), 0.5)
        assertEquals(1.5, next[0], 1e-9)
    }
}
