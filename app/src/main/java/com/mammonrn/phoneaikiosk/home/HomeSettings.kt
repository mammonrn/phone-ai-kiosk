package com.mammonrn.phoneaikiosk.home

import org.json.JSONObject

/**
 * The Control Panel's "ไฟในบ้าน" page (0.47.0, Poom 2026-09-24), as data: what
 * the broker's GET /v1/home/devices says, and what its answer to a change
 * says. Pure (org.json only), so HomeSettingsTest holds it on the JVM.
 *
 * THE PHONE IS ONLY A SCREEN. A device is known by the broker's opaque [key]
 * and a channel by its NUMBER; names and permissions are kept on the VPS.
 * Nothing here can address eWeLink or knows a device id.
 */
object HomeSettings {

    /** The formal line when the broker gave none (an older broker, nginx). */
    const val FAILED = "ระบบขัดข้อง กรุณาลองใหม่อีกครั้ง"

    /** At most, as the broker checks too. */
    const val MAX_NAME = 40

    data class Channel(val index: Int, val name: String, val ownName: String, val ewelinkName: String,
                       val on: Boolean?, val allowed: Boolean, val active: Boolean, val clash: List<String>,
                       val icon: String = "bulb", val iconChosen: Boolean = false, val voice: Boolean = true)

    data class Device(val key: String, val name: String, val ownName: String, val ewelinkName: String,
                      val room: String, val kind: String, val online: Boolean, val on: Boolean?,
                      val allowed: Boolean, val clash: List<String>, val channels: List<Channel>,
                      val icon: String = "bulb", val iconChosen: Boolean = false, val voice: Boolean = true,
                      /** 0.53.4: WiFi signal, dBm, and the broker's word for it; null/"" when not sent. */
                      val rssi: Int? = null, val signal: String = "")

    /** A name voice cannot use ("ไฟ" alone). "" when it can. */
    const val NOT_FOR_VOICE = "ชื่อนี้กว้างเกินไป จาร์วิสจะสั่งผิดดวง กรุณาตั้งชื่อที่บอกว่าดวงไหน"

    /** [error] is "" when the list is good; [control] false after `ewelink-control off`. */
    data class Page(val devices: List<Device>, val control: Boolean, val error: String)

    /** A change's answer: the new page when it worked, the broker's words either way. */
    data class Changed(val ok: Boolean, val message: String, val page: Page?)

    private val KEY = Regex("^[A-Za-z0-9_-]{1,64}$")

    /**
     * Debug builds only (TEST_LIGHTS_PAGE over adb): a sample list, so the page
     * can be seen on the A07 before the VPS has the route. Null in release;
     * nothing in main code sets it.
     */
    @Volatile var override: String? = null

    fun parse(body: String): Page = try {
        parse(JSONObject(body))
    } catch (e: Exception) {
        Page(emptyList(), false, "unreadable")
    }

    fun parse(json: JSONObject): Page {
        val refusal = json.optJSONObject("error")
        if (refusal != null) return Page(emptyList(), false, com.mammonrn.phoneaikiosk.ui.ScreenWords.formal(refusal.optString("message")).ifEmpty { FAILED })
        val devices = ArrayList<Device>()
        val list = json.optJSONArray("devices")
        for (i in 0 until (list?.length() ?: 0)) {
            val d = list!!.optJSONObject(i) ?: continue
            val key = d.optString("key")
            if (!KEY.matches(key)) continue
            val channels = ArrayList<Channel>()
            val chs = d.optJSONArray("channels")
            for (j in 0 until (chs?.length() ?: 0)) {
                val c = chs!!.optJSONObject(j) ?: continue
                channels += Channel(c.optInt("channel", j), c.optString("name"), c.optString("own_name"),
                                    c.optString("ewelink_name"), bool(c, "on"), c.optBoolean("allowed"),
                                    c.optBoolean("active", true), strings(c, "clash"),
                                    icon(c, "switch"), c.optBoolean("icon_chosen"), c.optBoolean("voice", true))
            }
            val kind = d.optString("kind")
            devices += Device(key, d.optString("name"), d.optString("own_name"), d.optString("ewelink_name"),
                              d.optString("room"), kind, d.optBoolean("online"), bool(d, "on"),
                              d.optBoolean("allowed"), strings(d, "clash"), channels,
                              icon(d, HomeCard.defaultIcon(kind)), d.optBoolean("icon_chosen"),
                              d.optBoolean("voice", true),
                              if (d.has("rssi") && !d.isNull("rssi")) d.optInt("rssi") else null,
                              d.optString("signal").take(12))
        }
        val error = when {
            json.optString("error") == "not-connected" -> "not-connected"
            devices.isEmpty() && !json.optBoolean("ok", false) -> json.optString("error").ifEmpty { "unreadable" }
            else -> ""
        }
        return Page(devices, json.optBoolean("control", true), error)
    }

    fun parseChanged(body: String): Changed = try {
        val json = JSONObject(body)
        val refusal = json.optJSONObject("error")
        if (!json.optBoolean("ok", false) || refusal != null) {
            Changed(false, com.mammonrn.phoneaikiosk.ui.ScreenWords.formal(refusal?.optString("message").orEmpty()).ifEmpty { FAILED }, null)
        } else {
            Changed(true, com.mammonrn.phoneaikiosk.ui.ScreenWords.formal(json.optString("message")).ifEmpty { "บันทึกแล้ว" },
                    json.optJSONObject("view")?.let { parse(it) })
        }
    } catch (e: Exception) {
        Changed(false, FAILED, null)
    }

    /** "ปลั๊ก · ห้องนอน · ออฟไลน์": what it is, where, and whether it answers. */
    fun describe(device: Device): String = listOfNotNull(
        when {
            device.channels.isNotEmpty() -> "สวิตช์ ${device.channels.size} ช่อง"
            device.kind == "plug" -> "ปลั๊ก"
            device.kind == "switch" -> "สวิตช์ไฟ"
            else -> "ไฟ"
        },
        device.room.ifEmpty { null },
        if (device.online) "ออนไลน์" else "ออฟไลน์",
    ).joinToString(" · ")

    /**
     * "สัญญาณ WiFi: อ่อน (-74 dBm)", or "" with no reading. Offline, it is the
     * last value the device reported, and says so. Thresholds are the broker's
     * (ewelink.signal_word, MetaGeek's table).
     */
    fun signalLine(device: Device): String {
        val rssi = device.rssi ?: return ""
        val word = device.signal.ifEmpty { return "" }
        return "สัญญาณ WiFi: $word ($rssi dBm)" + if (device.online) "" else " · ค่าที่อ่านได้ครั้งล่าสุด"
    }

    /** The state in words, never a colour alone. */
    fun state(online: Boolean, on: Boolean?): String = when {
        !online -> "ออฟไลน์"
        on == true -> "เปิดอยู่"
        on == false -> "ปิดอยู่"
        else -> "ไม่ทราบสถานะ"
    }

    /** Where the name on screen came from, so Poom knows what voice will hear. */
    fun source(ownName: String, ewelinkName: String): String = when {
        ownName.isNotEmpty() -> "ชื่อที่ตั้งไว้"
        ewelinkName.isNotEmpty() -> "ชื่อจาก eWeLink"
        else -> "ยังไม่ได้ตั้งชื่อ"
    }

    /** The warning under a name voice cannot tell from another. "" when none. */
    fun clashWarning(clash: List<String>): String =
        if (clash.isEmpty()) "" else "ชื่อซ้ำกับ " + clash.joinToString(", ") { "\"$it\"" } +
            " จาร์วิสจะแยกไม่ออก กรุณาเปลี่ยนชื่อ"

    private fun icon(json: JSONObject, fallback: String): String =
        json.optString("icon").takeIf { it in HomeCard.ICONS } ?: fallback

    private fun bool(json: JSONObject, name: String): Boolean? =
        if (!json.has(name) || json.isNull(name)) null else json.optBoolean(name)

    private fun strings(json: JSONObject, name: String): List<String> {
        val array = json.optJSONArray(name) ?: return emptyList()
        return (0 until array.length()).map { array.optString(it) }.filter { it.isNotEmpty() }
    }
}
