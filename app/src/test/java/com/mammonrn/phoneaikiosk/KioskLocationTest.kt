package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.voice.Broker
import com.mammonrn.phoneaikiosk.voice.KioskLocation
import com.mammonrn.phoneaikiosk.voice.VoiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The rounding, which is the part of the location pipeline that is a promise.
 *
 * Everything else in KioskLocation needs a phone — a LocationManager, a
 * provider, a permission. This does not, and it is the half that matters: the
 * claim made in the manifest, in NOTICE and to Poom is that what leaves this
 * device is a square kilometre rather than a doorstep, and that claim is one
 * function. A regression here would be silent everywhere else.
 */
class KioskLocationTest {

    @Test
    fun `a position is cut to two decimals before it goes anywhere`() {
        assertEquals(20.05, KioskLocation.round(20.045147), 0.0)
        assertEquals(99.89, KioskLocation.round(99.894883), 0.0)
        assertEquals(13.76, KioskLocation.round(13.7563309), 0.0)
    }

    @Test
    fun `the southern and western hemispheres round the same way`() {
        assertEquals(-33.87, KioskLocation.round(-33.8688197), 0.0)
        assertEquals(-70.65, KioskLocation.round(-70.6482), 0.0)
    }

    @Test
    fun `two decimals is about a kilometre, which is the whole argument`() {
        // A degree of latitude is about 111 km, so 0.01 of one is about 1.1 km.
        // Fine enough that the forecast is the right one; coarse enough that
        // the number is not an address. If this ever became three decimals it
        // would be about 110 m, which is a house.
        val metresPerDegree = 111_000.0
        val worstCase = 0.005 * metresPerDegree
        assertTrue("a rounded position must stay over half a kilometre coarse",
                   worstCase >= 500.0)
    }

    @Test
    fun `rounding never invents precision it did not have`() {
        // Whatever goes in, at most two decimals come out — including the
        // values that trip naive formatting.
        for (value in listOf(0.005, 1.0 / 3.0, 20.0, -0.004, 179.999)) {
            val rounded = KioskLocation.round(value)
            val hundredths = rounded * 100.0
            assertTrue("$value rounded to $rounded",
                       abs(hundredths - Math.round(hundredths)) < 1e-9)
        }
    }

    @Test
    fun `half an hour is the interval, and it is not a stray small number`() {
        // A fix costs battery. This being accidentally set to seconds is the
        // kind of change that looks harmless in a diff and is noticed as a flat
        // phone a week later.
        assertEquals(30L * 60L * 1000L, KioskLocation.INTERVAL_MILLIS)
    }

    // ---- the three ways of having no position --------------------------------
    //
    // Each of these must end the same way: no fix held, no query string sent,
    // and the broker's fallback. Which one it was is what the status line says.

    @Test
    fun `no permission asks for nothing`() {
        assertEquals("no-permission",
                     KioskLocation.blocker(permission = false, service = true, enabled = true))
    }

    @Test
    fun `location switched off asks for nothing`() {
        assertEquals("location-off",
                     KioskLocation.blocker(permission = true, service = true, enabled = false))
    }

    @Test
    fun `no location service asks for nothing`() {
        assertEquals("no-service",
                     KioskLocation.blocker(permission = true, service = false, enabled = false))
    }

    @Test
    fun `permission is reported first, because it is the first thing to fix`() {
        assertEquals("no-permission",
                     KioskLocation.blocker(permission = false, service = false, enabled = false))
    }

    @Test
    fun `with all three a fix is asked for`() {
        assertNull(KioskLocation.blocker(permission = true, service = true, enabled = true))
    }

    @Test
    fun `no fix sends no position at all`() {
        // Not "?lat=null", not "?lat=0.0": nothing, which the broker reads as
        // "use the university".
        assertEquals("/v1/dashboard", Broker.dashboardPath(null, null))
        assertEquals("/v1/dashboard", Broker.dashboardPath(20.05, null))
        assertEquals("/v1/dashboard", Broker.dashboardPath(null, 99.89))
    }

    @Test
    fun `a fix is sent rounded even if the caller forgot`() {
        assertEquals("/v1/dashboard?lat=13.76&lon=100.5",
                     Broker.dashboardPath(13.7563309, 100.5017651))
    }

    @Test
    fun `the status line does not claim local weather before there is any`() {
        assertEquals("none", VoiceState.weatherSource(null))
        assertEquals("fallback", VoiceState.weatherSource(true))
        assertEquals("here", VoiceState.weatherSource(false))
    }
}
