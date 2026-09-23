package com.mammonrn.phoneaikiosk.home

/**
 * The READ-ONLY view of the home (round "Google Home, read only", Poom
 * 2026-09-23): what devices there are, what type, and whether each is on —
 * nothing here switches anything.
 *
 * The Google Home APIs SDK is not on Maven; Poom downloads it and it lives in
 * app/libs. The code that talks to it (src/googlehome/, compiled only when the
 * AARs are there — see app/build.gradle.kts) turns the SDK's devices into
 * [HomeDevice]; everything from there on — the order, the words, which ones
 * could ever be switched — is here, pure, and tested (HomeSummaryTest).
 */
data class HomeDevice(
    /** The SDK's stable device id: what the future allowlist names. */
    val id: String,
    /** The name the household gave it in the Google Home app. */
    val name: String,
    /** The Home APIs device type, e.g. "OnOffLightDevice". */
    val type: String,
    /** On or off, or null when the device has no on/off to read. */
    val on: Boolean?,
    val online: Boolean,
    /** Where it lives, as the Google Home app names the room; may be empty. */
    val room: String = "",
)

object HomeSummary {

    enum class Kind { LIGHT, PLUG, OTHER, FORBIDDEN }

    data class Row(val device: HomeDevice, val kind: Kind, val state: String, val label: String)

    /** What sort of thing it is, with HomeGate's never-list checked first. */
    fun kind(device: HomeDevice): Kind {
        val probe = HomeGate.check(HomeGate.Device(device.id, device.type, device.name),
                                   HomeGate.Command.ON, setOf(device.id))
        if (probe is HomeGate.Verdict.Refused && probe.reason.startsWith("forbidden")) return Kind.FORBIDDEN
        return when {
            device.type.contains("Light") -> Kind.LIGHT
            device.type.contains("PlugIn", ignoreCase = true) -> Kind.PLUG
            else -> Kind.OTHER
        }
    }

    fun state(device: HomeDevice): String = when {
        !device.online -> "ออฟไลน์"
        device.on == true -> "เปิด"
        device.on == false -> "ปิด"
        else -> "ไม่มีสถานะเปิดปิด"
    }

    private fun kindWord(kind: Kind): String = when (kind) {
        Kind.LIGHT -> "ไฟ"
        Kind.PLUG -> "ปลั๊ก"
        Kind.OTHER -> "อุปกรณ์อื่น"
        Kind.FORBIDDEN -> "ห้ามควบคุม"
    }

    /**
     * Every device, lights first, then plugs, then the rest, forbidden last;
     * by room and name within each. One line each for the Control Panel and
     * adb: "ไฟห้องนั่งเล่น · ไฟ · เปิด".
     */
    fun rows(devices: List<HomeDevice>): List<Row> =
        devices.map { d ->
            val kind = kind(d)
            val name = d.name.trim().ifEmpty { "ไม่มีชื่อ" }
            Row(d, kind, state(d), "$name · ${kindWord(kind)} · ${state(d)}")
        }.sortedWith(compareBy<Row>({ it.kind.ordinal }, { it.device.room }, { it.device.name }))
}
