package com.mammonrn.phoneaikiosk.calc

/**
 * The calculator's "หน่วย" tab (0.61.0, Poom): one value in one unit, shown in
 * every unit of its kind at once. Plain Kotlin — UnitsTest drives every factor
 * against hand-worked examples.
 *
 * Each unit is its size in the kind's base unit (metre, square metre,
 * kilogram, litre, metre per second, joule, watt); temperature is the one kind
 * that is not a plain factor and has its own formulas.
 *
 * Thai units are the legal ones (พ.ร.บ.มาตราชั่งตวงวัด): 1 วา = 2 m, 1 ศอก =
 * 0.5 m, 1 คืบ = 0.25 m; 1 ไร่ = 1,600 m², 1 งาน = 400 m², 1 ตารางวา = 4 m²;
 * 1 ชั่ง = 1.2 kg, 1 ตำลึง = 60 g, 1 บาท = 15 g (the weight, not a baht of
 * gold, which is 15.244 g — the ราคา tab); 1 ถัง = 20 litres.
 */
object Units {

    enum class Kind { LENGTH, AREA, MASS, VOLUME, TEMPERATURE, SPEED, ENERGY, POWER }

    /** [key] names the unit's words in strings_units.xml ("unit_<key>"); [size] is in the kind's base unit. */
    class Unit(val key: String, val size: Double)

    val UNITS: Map<Kind, List<Unit>> = mapOf(
        Kind.LENGTH to listOf(
            Unit("mm", 0.001), Unit("cm", 0.01), Unit("m", 1.0), Unit("km", 1000.0),
            Unit("inch", 0.0254), Unit("foot", 0.3048), Unit("yard", 0.9144), Unit("mile", 1609.344),
            Unit("kheup", 0.25), Unit("sok", 0.5), Unit("wa", 2.0), Unit("sen", 40.0)),
        Kind.AREA to listOf(
            Unit("m2", 1.0), Unit("km2", 1_000_000.0), Unit("hectare", 10_000.0),
            Unit("sqwa", 4.0), Unit("ngan", 400.0), Unit("rai", 1600.0),
            Unit("ft2", 0.09290304), Unit("acre", 4046.8564224)),
        Kind.MASS to listOf(
            Unit("g", 0.001), Unit("kg", 1.0), Unit("tonne", 1000.0),
            Unit("oz", 0.028349523125), Unit("lb", 0.45359237),
            Unit("baht_w", 0.015), Unit("tamlueng", 0.06), Unit("chang", 1.2)),
        Kind.VOLUME to listOf(
            Unit("ml", 0.001), Unit("l", 1.0), Unit("m3", 1000.0),
            Unit("tsp", 0.005), Unit("tbsp", 0.015), Unit("cup", 0.24),
            Unit("gal_us", 3.785411784), Unit("thang", 20.0)),
        Kind.TEMPERATURE to listOf(Unit("c", 1.0), Unit("f", 1.0), Unit("k", 1.0)),
        Kind.SPEED to listOf(
            Unit("mps", 1.0), Unit("kmh", 1000.0 / 3600.0), Unit("mph", 1609.344 / 3600.0), Unit("knot", 1852.0 / 3600.0)),
        Kind.ENERGY to listOf(
            Unit("j", 1.0), Unit("kj", 1000.0), Unit("kwh", 3_600_000.0), Unit("kcal", 4184.0), Unit("btu", 1055.05585262)),
        Kind.POWER to listOf(
            Unit("w", 1.0), Unit("kw", 1000.0), Unit("hp", 745.69987158227), Unit("ps", 735.49875), Unit("btuh", 0.29307107017)),
    )

    fun unit(kind: Kind, key: String): Unit? = UNITS[kind]?.firstOrNull { it.key == key }

    /** Absolute zero, in each temperature unit: nothing is colder. */
    private const val ZERO_K = 0.0

    /**
     * [value] in [from], in unit [to] of the same kind. Null for an unknown
     * unit, a value that is not a finite number, a negative length/area/mass/
     * volume/energy/power (a negative speed is a direction and is allowed), or
     * a temperature below absolute zero.
     */
    fun convert(kind: Kind, value: Double, from: String, to: String): Double? {
        if (!value.isFinite()) return null
        val f = unit(kind, from) ?: return null
        val t = unit(kind, to) ?: return null
        if (kind == Kind.TEMPERATURE) {
            val kelvin = when (f.key) {
                "c" -> value + 273.15
                "f" -> (value - 32.0) * 5.0 / 9.0 + 273.15
                else -> value
            }
            if (kelvin < ZERO_K - 1e-9) return null
            return when (t.key) {
                "c" -> kelvin - 273.15
                "f" -> (kelvin - 273.15) * 9.0 / 5.0 + 32.0
                else -> kelvin
            }
        }
        if (value < 0 && kind != Kind.SPEED) return null
        return (value * f.size / t.size).takeIf { it.isFinite() }
    }

    /** [value] in [from], in every unit of its kind, in the list's order. */
    fun all(kind: Kind, value: Double, from: String): List<Pair<Unit, Double>>? {
        val units = UNITS[kind] ?: return null
        val out = units.map { u -> u to (convert(kind, value, from, u.key) ?: return null) }
        return out
    }
}
