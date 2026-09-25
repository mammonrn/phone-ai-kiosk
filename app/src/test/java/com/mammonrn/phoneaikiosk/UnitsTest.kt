package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.calc.Units
import com.mammonrn.phoneaikiosk.calc.Units.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The "หน่วย" tab (0.61.0). Every expected value was worked by hand and again in Python. */
class UnitsTest {

    private fun near(expected: Double, actual: Double?) {
        assertNotNull(actual)
        assertEquals(expected, actual!!, maxOf(1e-9, kotlin.math.abs(expected) * 1e-9))
    }

    @Test
    fun `thai area - rai, ngan, square wa`() {
        near(400.0, Units.convert(Kind.AREA, 1.0, "rai", "sqwa"))          // 1,600 ÷ 4
        near(4000.0, Units.convert(Kind.AREA, 2.5, "rai", "m2"))           // 2.5 × 1,600
        near(4.0, Units.convert(Kind.AREA, 1.0, "rai", "ngan"))            // 1,600 ÷ 400
        near(2.529285264, Units.convert(Kind.AREA, 1.0, "acre", "rai"))    // 4,046.8564224 ÷ 1,600
    }

    @Test
    fun `length`() {
        near(152.4, Units.convert(Kind.LENGTH, 5.0, "foot", "cm"))         // 5 × 0.3048 ÷ 0.01
        near(6.0, Units.convert(Kind.LENGTH, 3.0, "wa", "m"))              // 3 × 2
        near(6.2137119223733395, Units.convert(Kind.LENGTH, 10.0, "km", "mile"))   // 10,000 ÷ 1,609.344
    }

    @Test
    fun `mass, thai weights too`() {
        near(1.2, Units.convert(Kind.MASS, 1.0, "chang", "kg"))
        near(907.18474, Units.convert(Kind.MASS, 2.0, "lb", "g"))           // 2 × 0.45359237 ÷ 0.001
        near(25.0, Units.convert(Kind.MASS, 100.0, "baht_w", "tamlueng"))   // 100 × 15 g ÷ 60 g
    }

    @Test
    fun `volume`() {
        near(20.0, Units.convert(Kind.VOLUME, 1.0, "thang", "l"))
        near(720.0, Units.convert(Kind.VOLUME, 3.0, "cup", "ml"))           // 3 × 240 ml
        near(18.92705892, Units.convert(Kind.VOLUME, 5.0, "gal_us", "l"))  // 5 × 3.785411784
    }

    @Test
    fun `temperature, and nothing below absolute zero`() {
        near(212.0, Units.convert(Kind.TEMPERATURE, 100.0, "c", "f"))      // 100 × 9/5 + 32
        near(37.0, Units.convert(Kind.TEMPERATURE, 98.6, "f", "c"))        // (98.6 − 32) × 5/9
        near(298.15, Units.convert(Kind.TEMPERATURE, 25.0, "c", "k"))
        near(-273.15, Units.convert(Kind.TEMPERATURE, 0.0, "k", "c"))
        near(-40.0, Units.convert(Kind.TEMPERATURE, -40.0, "c", "f"))      // the one point they agree
        assertNull(Units.convert(Kind.TEMPERATURE, -300.0, "c", "k"))
        assertNull(Units.convert(Kind.TEMPERATURE, -1.0, "k", "c"))
    }

    @Test
    fun `speed, energy, power`() {
        near(27.77777777777778, Units.convert(Kind.SPEED, 100.0, "kmh", "mps"))   // 100 ÷ 3.6
        near(18.52, Units.convert(Kind.SPEED, 10.0, "knot", "kmh"))              // 10 × 1.852
        near(3412.141633127942, Units.convert(Kind.ENERGY, 1.0, "kwh", "btu"))    // 3,600,000 ÷ 1,055.05585262
        near(2092.0, Units.convert(Kind.ENERGY, 500.0, "kcal", "kj"))             // 500 × 4.184
        near(3.51685284204, Units.convert(Kind.POWER, 12000.0, "btuh", "kw"))     // an air conditioner's 12,000 BTU/h
        near(2.719243234607809, Units.convert(Kind.POWER, 2.0, "kw", "ps"))       // 2,000 ÷ 735.49875
    }

    @Test
    fun `edges - zero, negative, huge, not a number, unknown units`() {
        near(0.0, Units.convert(Kind.LENGTH, 0.0, "m", "km"))
        assertNull(Units.convert(Kind.LENGTH, -1.0, "m", "km"))
        near(-10.0, Units.convert(Kind.SPEED, -10.0, "mps", "mps"))           // a direction, allowed
        assertNull(Units.convert(Kind.LENGTH, Double.NaN, "m", "km"))
        assertNull(Units.convert(Kind.LENGTH, Double.POSITIVE_INFINITY, "m", "km"))
        assertNull(Units.convert(Kind.AREA, 1e308, "km2", "m2"))             // overflows: no answer, not Infinity
        assertNull(Units.convert(Kind.LENGTH, 1.0, "m", "rai"))              // not a length
        near(1.0, Units.convert(Kind.MASS, 1.0, "kg", "kg"))
    }

    @Test
    fun `every unit has its words, and a whole kind converts at once`() {
        val strings = listOf(File("src/main/res/values/strings_units.xml"), File("app/src/main/res/values/strings_units.xml"))
            .first { it.exists() }.readText()
        for ((kind, units) in Units.UNITS) {
            assertTrue("kind $kind", "name=\"units_kind_${kind.name.lowercase()}\"" in strings)
            for (u in units) assertTrue("unit ${u.key}", "name=\"unit_${u.key}\"" in strings)
        }
        val row = Units.all(Kind.AREA, 1.0, "rai")!!
        assertEquals(Units.UNITS.getValue(Kind.AREA).size, row.size)
        near(1600.0, row.first { it.first.key == "m2" }.second)
    }
}
