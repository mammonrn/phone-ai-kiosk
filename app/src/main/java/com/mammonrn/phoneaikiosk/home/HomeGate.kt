package com.mammonrn.phoneaikiosk.home

/**
 * What the kiosk may ever switch in the home, decided in code (Poom,
 * 2026-09-23). NOT WIRED YET: the read-only round lists devices; switching
 * is the round after, and it will pass through here and nowhere else.
 *
 * THE RULES, in order, each a refusal the model cannot talk its way past:
 *  1. NEVER a camera, a door, a lock, a garage, a gate, a blind or an alarm,
 *     whatever the allowlist says — by device type AND by a word in its name,
 *     so a smart plug named "ประตูหน้าบ้าน" is refused too.
 *  2. Only a device type that is a light or a plug (on/off, dimmable, colour).
 *  3. Only a device Poom put on the allowlist, by its stable device id — not
 *     by name, which anyone with the Google Home app can rename.
 *  4. Only on or off. No dimming, colour, scenes or automations here.
 *
 * Pure: HomeGateTest holds it on the JVM.
 */
object HomeGate {

    /** Home APIs device types this gate can ever allow (supported-device-types). */
    val SWITCHABLE_TYPES = setOf(
        "OnOffLightDevice", "DimmableLightDevice", "ColorTemperatureLightDevice",
        "ExtendedColorLightDevice", "OnOffPluginUnitDevice", "DimmablePlugInUnitDevice",
    )

    /** Types that are refused before anything else is looked at. */
    private val FORBIDDEN_TYPE_WORDS = listOf(
        "camera", "doorlock", "door", "lock", "garage", "gate", "window", "blind", "covering",
        "alarm", "security", "siren", "doorbell", "thermostat", "valve",
    )

    /** Names that are refused, whatever type the device claims to be. */
    private val FORBIDDEN_NAME_WORDS = listOf(
        "กล้อง", "ประตู", "กุญแจ", "ล็อก", "ล็อค", "โรงรถ", "รั้ว", "ม่าน", "หน้าต่าง", "สัญญาณกันขโมย",
        "กันขโมย", "ไซเรน", "กริ่ง", "camera", "door", "lock", "garage", "gate", "alarm", "siren",
    )

    enum class Command { ON, OFF }

    data class Device(val id: String, val type: String, val name: String)

    sealed class Verdict {
        object Allowed : Verdict()
        data class Refused(val reason: String) : Verdict()
    }

    fun check(device: Device, command: Command?, allowlist: Set<String>): Verdict {
        val type = device.type.lowercase()
        FORBIDDEN_TYPE_WORDS.firstOrNull { it in type }?.let { return Verdict.Refused("forbidden-type:$it") }
        val name = device.name.lowercase()
        FORBIDDEN_NAME_WORDS.firstOrNull { it in name }?.let { return Verdict.Refused("forbidden-name") }
        if (device.type !in SWITCHABLE_TYPES) return Verdict.Refused("not-a-light-or-plug")
        if (device.id !in allowlist) return Verdict.Refused("not-on-allowlist")
        if (command == null) return Verdict.Refused("only-on-or-off")
        return Verdict.Allowed
    }
}
