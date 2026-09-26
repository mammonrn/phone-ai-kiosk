package com.mammonrn.phoneaikiosk.weather

/**
 * The weather window's place name, from what Android's Geocoder says about the
 * phone's own position: sub-district, district and province, in Thai.
 *
 * PURE, no Android in it: the Geocoder's Thai answers vary (the same field is
 * "อำเภอเมืองเชียงราย" on one phone and "เมืองเชียงราย" on another, Bangkok puts
 * its เขต where a province puts its อำเภอ, or in the locality instead), and that
 * is a question about strings.
 *
 * WHAT IT GIVES is a list of names, most detailed first, e.g.
 *   "ต.ท่าสุด อ.เมืองเชียงราย เชียงราย", "อ.เมืองเชียงราย เชียงราย",
 *   "อ.เมืองเชียงราย", "เชียงราย"
 * and the screen takes the first that fits its title bar whole (MainActivity),
 * so a long name loses a part rather than being cut in the middle of a word.
 * Empty when nothing usable came back: the title then keeps what it had.
 * Never a coordinate — nothing numeric is ever built here.
 */
object PlaceName {

    /** The Geocoder's fields this reads (android.location.Address), any of them null. */
    data class Fields(
        val subLocality: String? = null,
        val locality: String? = null,
        val subAdminArea: String? = null,
        val adminArea: String? = null,
    )

    private val BANGKOK = setOf("กรุงเทพมหานคร", "กรุงเทพฯ", "กรุงเทพ", "กทม.", "Bangkok")
    private const val BANGKOK_SHORT = "กรุงเทพฯ"

    private enum class Level { SUB, DISTRICT, PROVINCE }

    /** One field, read: what level it is, its bare name, and whether it was Bangkok-style. */
    private data class Part(val level: Level, val name: String, val bangkokStyle: Boolean)

    /** Prefix → level, longest first so "อำเภอ" is not read as "อ.". */
    private val PREFIXES = listOf(
        "จังหวัด" to Part(Level.PROVINCE, "", false),
        "จ." to Part(Level.PROVINCE, "", false),
        "อำเภอ" to Part(Level.DISTRICT, "", false),
        "อ." to Part(Level.DISTRICT, "", false),
        "เขต" to Part(Level.DISTRICT, "", true),
        "ตำบล" to Part(Level.SUB, "", false),
        "ต." to Part(Level.SUB, "", false),
        "แขวง" to Part(Level.SUB, "", true),
    )

    private fun read(raw: String?): Part? {
        val text = raw?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
        if (text.isEmpty()) return null
        // A number is not a place (a postcode, a house number): never shown.
        if (text.any { it.isDigit() }) return null
        if (text in BANGKOK) return Part(Level.PROVINCE, BANGKOK_SHORT, false)
        for ((prefix, part) in PREFIXES) {
            if (text.startsWith(prefix) && text.length > prefix.length) {
                return part.copy(name = text.substring(prefix.length).trim())
            }
        }
        // Without a prefix the field it came in decides (candidates, bare).
        return null
    }

    /**
     * The names, most detailed first, no repeats. See the class comment.
     */
    fun candidates(fields: Fields): List<String> {
        var sub: Part? = null
        var district: Part? = null
        var province: Part? = null

        // Prefixed fields say what they are, wherever the Geocoder put them.
        for (raw in listOf(fields.subLocality, fields.locality, fields.subAdminArea, fields.adminArea)) {
            val part = read(raw) ?: continue
            when (part.level) {
                Level.SUB -> if (sub == null) sub = part
                Level.DISTRICT -> if (district == null) district = part
                Level.PROVINCE -> if (province == null) province = part
            }
        }
        // Unprefixed ones are read by the field they came in: adminArea is the
        // province, subAdminArea the district. Locality and subLocality without
        // a prefix are too often the town or the province again to trust.
        fun bare(raw: String?): String? {
            val text = raw?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
            if (text.isEmpty() || text.any { it.isDigit() } || text.startsWith("เทศบาล")) return null
            return text.takeIf { read(it) == null && it !in BANGKOK }
        }
        if (province == null) bare(fields.adminArea)?.let { province = Part(Level.PROVINCE, it, false) }
        if (district == null) bare(fields.subAdminArea)?.let { district = Part(Level.DISTRICT, it, false) }

        val bangkok = province?.name == BANGKOK_SHORT
        fun show(part: Part?): String? {
            val p = part ?: return null
            if (p.name.isEmpty()) return null
            return when (p.level) {
                Level.PROVINCE -> p.name
                // Bangkok's are เขต and แขวง, a province's อำเภอ and ตำบล — whichever the
                // Geocoder said, or the province says, is written.
                Level.DISTRICT -> if (p.bangkokStyle || bangkok) "เขต${p.name}" else "อ.${p.name}"
                Level.SUB -> if (p.bangkokStyle || bangkok) "แขวง${p.name}" else "ต.${p.name}"
            }
        }
        val p = show(province)
        // A district named like its province adds nothing ("เขตกรุงเทพฯ").
        val d = show(district)?.takeUnless { district?.name == province?.name }
        // A ตำบล may share its อำเภอ's name (ต.แม่สาย อ.แม่สาย): both are real, both kept.
        val s = show(sub)?.takeUnless { sub?.name == province?.name }

        fun join(vararg parts: String?) = parts.filterNotNull().joinToString(" ")
        return listOf(
            join(s, d, p),
            join(d, p),
            join(s, p).takeIf { d == null },
            join(d),
            join(p),
            join(s).takeIf { d == null && p == null },
        ).filterNotNull().filter { it.isNotBlank() }.distinct()
    }
}
