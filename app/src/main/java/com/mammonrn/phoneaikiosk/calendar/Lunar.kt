package com.mammonrn.phoneaikiosk.calendar

import java.time.LocalDate

/**
 * วันพระ, COMPUTED — not an official announcement (Poom 0.65: no agency publishes a
 * list that can be read without signing up; the screen says "คำนวณ ไม่ใช่ประกาศทางการ").
 *
 * The Thai lunar year here runs from ขึ้น 1 ค่ำ เดือนอ้าย (late November or December)
 * and is named by the พ.ศ. of the Songkran inside it. Its months alternate 29 and 30
 * days (อ้าย 29, ยี่ 30, …, สิบสอง 30); an อธิกมาส year repeats เดือนแปด (30 days,
 * 384 in all), an อธิกวาร year gives เดือนเจ็ด a 30th day (355). วันพระ are ขึ้น 8,
 * ขึ้น 15, แรม 8 and the month's last day (แรม 14 or แรม 15).
 *
 * The year's kind comes from the สุริยยาตร์ numbers of its จุลศักราช (พ.ศ. − 1181):
 * - หรคุณ H = ⌊(292207·CS + 373) / 800⌋ + 1, ดิถี = (H + ⌊(11H + 650) / 692⌋) mod 30.
 * - อธิกมาส when the ดิถี is 24–29 or 0–5 and next year's is not (so never two in a row).
 * - อธิกวาร (only in a year without อธิกมาส) when the year begins before the mean
 *   moon has reached its first lunar day: the mean ดิถี of the first day, counted
 *   from the same หรคุณ, is below 1 — below 0.91 in the year straight after an
 *   อธิกมาส (fitted: 2543 had it at 0.86, 2548 did not at 0.96).
 *
 * Checked against every วันพระ of myhora.com's calendars 2541–2570 (1,488 days, all
 * equal in day and lunar month) and against the 49 วันพระ of 2569 printed by สวท.
 * ประจวบคีรีขันธ์ (with its one misprint, 26 April for 24 April, taken from myhora).
 * Neither is official; see [uncertain] for the years where a day may be off.
 */
object Lunar {

    data class HolyDay(val date: LocalDate, val month: Int, val secondEighth: Boolean, val waxing: Boolean, val day: Int) {
        /** "ขึ้น 8 ค่ำ เดือนหก", "แรม 15 ค่ำ เดือนแปดหลัง". */
        val label: String get() =
            "${if (waxing) "ขึ้น" else "แรม"} $day ค่ำ เดือน${MONTH_NAMES[month - 1]}${if (secondEighth) "หลัง" else ""}"
    }

    /** The first year computed; its start is the one fixed date (myhora and every later year agree). */
    const val FIRST_YEAR = 2541
    const val LAST_YEAR = 2600
    /** The last year checked against a printed calendar; later years are computed only. */
    const val LAST_CHECKED = 2570
    private val FIRST_START: LocalDate = LocalDate.of(1997, 11, 30)

    private val MONTH_NAMES = listOf("อ้าย", "ยี่", "สาม", "สี่", "ห้า", "หก", "เจ็ด", "แปด", "เก้า", "สิบ", "สิบเอ็ด", "สิบสอง")

    private fun horakhun(cs: Long) = (292207 * cs + 373) / 800 + 1
    private fun tithi(cs: Long): Long { val h = horakhun(cs); return (h + (11 * h + 650) / 692) % 30 }
    private fun inWindow(t: Long) = t >= 24 || t <= 5

    /** เดือนแปดสองหน. */
    fun adhikamasa(be: Int): Boolean {
        val cs = (be - 1181).toLong()
        return inWindow(tithi(cs)) && !inWindow(tithi(cs + 1))
    }

    /** The epoch day whose หรคุณ is 0: Songkran's เถลิงศก of CS 1388 is 16 April 2026. */
    private val ZERO = LocalDate.of(2026, 4, 16).toEpochDay() - horakhun(1388)

    /** The mean ดิถี (0 until 30) at the start of [day]. */
    private fun meanTithi(day: Long): Double = Math.floorMod(703 * (day - ZERO) + 650, 692L * 30) / 692.0

    private fun limit(be: Int) = if (adhikamasa(be - 1)) 0.91 else 1.0

    private fun adhikavara(be: Int, start: Long) = !adhikamasa(be) && meanTithi(start) < limit(be)

    /** The epoch day of ขึ้น 1 ค่ำ เดือนอ้าย of each year, FIRST_YEAR..LAST_YEAR+1. */
    private val starts: LongArray by lazy {
        val out = LongArray(LAST_YEAR - FIRST_YEAR + 2)
        var s = FIRST_START.toEpochDay()
        for (i in out.indices) {
            out[i] = s
            val be = FIRST_YEAR + i
            s += if (adhikamasa(be)) 384 else if (adhikavara(be, s)) 355 else 354
        }
        out
    }

    /** เดือนเจ็ด has a 30th day. */
    fun adhikavara(be: Int): Boolean = be in FIRST_YEAR..LAST_YEAR && adhikavara(be, starts[be - FIRST_YEAR])

    /**
     * A year past the checked ones whose อธิกวาร was a close call: its วันพระ from
     * เดือนเจ็ด on may be a day off. (The close calls up to 2570 all came out right.)
     */
    fun uncertain(be: Int): Boolean {
        if (be <= LAST_CHECKED || be > LAST_YEAR || adhikamasa(be)) return false
        val t = meanTithi(starts[be - FIRST_YEAR])
        return kotlin.math.abs(t - limit(be)) < 0.1 || (adhikamasa(be - 1) && t >= 0.86 && t < 1.0)
    }

    /** The lunar year (named by its พ.ศ.) that [date] falls in, or null outside FIRST_YEAR..LAST_YEAR. */
    fun yearOf(date: LocalDate): Int? {
        val d = date.toEpochDay()
        for (i in 0 until starts.size - 1) if (d >= starts[i] && d < starts[i + 1]) return FIRST_YEAR + i
        return null
    }

    /** Every วันพระ of the lunar year [be]. */
    fun holyDays(be: Int): List<HolyDay> {
        if (be !in FIRST_YEAR..LAST_YEAR) return emptyList()
        var s = starts[be - FIRST_YEAR]
        val extra = adhikavara(be)
        val months = buildList {
            for (m in 1..8) add(Triple(m, false, if (m == 7 && extra) 30 else if (m % 2 == 1) 29 else 30))
            if (adhikamasa(be)) add(Triple(8, true, 30))
            for (m in 9..12) add(Triple(m, false, if (m % 2 == 1) 29 else 30))
        }
        val out = ArrayList<HolyDay>(52)
        for ((m, second, length) in months) {
            for (d in intArrayOf(8, 15, 23, length)) {
                out.add(HolyDay(LocalDate.ofEpochDay(s + d - 1), m, second, d <= 15, if (d <= 15) d else d - 15))
            }
            s += length
        }
        return out
    }

    private val byDate: Map<LocalDate, HolyDay> by lazy {
        (FIRST_YEAR..LAST_YEAR).flatMap { holyDays(it) }.associateBy { it.date }
    }

    /** The วันพระ on [date], if it is one. */
    fun on(date: LocalDate): HolyDay? = byDate[date]

    /** The วันพระ between [from] and [to], both included. */
    fun between(from: LocalDate, to: LocalDate): List<HolyDay> {
        val a = yearOf(from) ?: FIRST_YEAR
        val b = yearOf(to) ?: LAST_YEAR
        if (to.isBefore(FIRST_START)) return emptyList()
        return (a..b).flatMap { holyDays(it) }.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
    }
}
