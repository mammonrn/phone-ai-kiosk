package com.mammonrn.phoneaikiosk.home

import org.json.JSONObject

/**
 * The "อุปกรณ์ในบ้าน" card (0.45.0, Poom): what the broker says about the
 * house's lights. It replaces the Google Home placeholder. Since 0.46.0 a
 * light the broker allows can be switched with a tap.
 *
 * WHAT THE PHONE GETS, AND WHAT IT DOES NOT. The broker's /v1/dashboard
 * `home` panel carries, per system (eWeLink today, room for more), each light
 * (one row per channel of a multi-way switch) by NAME, room, online and
 * on/off. No device id, no token, no account. A light the broker lets this
 * screen switch also carries `target`: an opaque key the broker made from
 * the id with its own secret. The phone sends it back in POST
 * /v1/home/switch and the broker does every check again (the stop switch,
 * its per-minute limit, the allowlist). A stolen phone can switch only what
 * the card already shows, and only until Poom revokes its token or runs
 * `ewelink-control off`.
 *
 * SHOWN ONLY WHEN THERE IS SOMETHING TO SHOW: [parse] is null until the broker
 * says ok with at least one device — not connected, failing with nothing
 * cached, or an older broker with no `home` at all all keep the card hidden.
 *
 * Pure (org.json only), so HomeCardTest holds it on the JVM.
 */
object HomeCard {

    /** The card never grows past this many rows; the rest is "และอีก N รายการ". */
    const val MAX_ROWS = 8

    data class Device(val name: String, val room: String, val kind: String, val online: Boolean,
                      val on: Boolean?, val channels: List<Boolean>, val target: String? = null)

    data class System(val id: String, val name: String, val devices: List<Device>)

    /** [control]: the broker lets this screen switch at all (false after `ewelink-control off`). */
    data class Card(val systems: List<System>, val ageSeconds: Int, val stale: Boolean,
                    val control: Boolean = false)

    /** What the broker answered to a tap: what eWeLink really did, in words. */
    data class Switched(val ok: Boolean, val on: Boolean?, val online: Boolean?, val message: String)

    /** A key as the broker makes them: short, and nothing that could be a URL or a header. */
    private val TARGET = Regex("^[A-Za-z0-9_-]{1,64}$")

    private const val FAILED = "ระบบขัดข้องครับ กรุณาลองใหม่อีกครั้ง"

    /**
     * Debug builds only (TEST_HOME_CARD over adb): a sample panel, so the card
     * can be seen on the A07 before an account is connected. Null in release;
     * nothing in main code sets it.
     */
    @Volatile var override: String? = null

    /** The card, or null when it should stay hidden. Never throws. */
    fun parse(home: JSONObject?): Card? = try {
        if (home == null) null else {
            val ok = home.optBoolean("ok", false)
            // A failure with the last good devices still attached comes as
            // ok=false with the same fields; it is shown, marked stale.
            val systems = ArrayList<System>()
            val list = home.optJSONArray("systems")
            for (i in 0 until (list?.length() ?: 0)) {
                val s = list!!.optJSONObject(i) ?: continue
                val devices = ArrayList<Device>()
                val rows = s.optJSONArray("devices")
                for (j in 0 until (rows?.length() ?: 0)) {
                    val d = rows!!.optJSONObject(j) ?: continue
                    val name = d.optString("name", "").trim()
                    if (name.isEmpty()) continue
                    val channels = ArrayList<Boolean>()
                    d.optJSONArray("channels")?.let { c -> for (k in 0 until c.length()) channels += c.optBoolean(k) }
                    val target = d.optString("target", "").takeIf { TARGET.matches(it) }
                    devices += Device(name, d.optString("room", "").trim(), d.optString("kind", ""),
                                      d.optBoolean("online", false),
                                      if (d.isNull("on") || !d.has("on")) null else d.optBoolean("on"),
                                      channels, target)
                }
                if (devices.isNotEmpty()) systems += System(s.optString("id", ""), s.optString("name", ""), devices)
            }
            if (systems.isEmpty()) null
            else Card(systems, home.optInt("age_seconds", 0), stale = !ok,
                      control = home.optBoolean("control", false))
        }
    } catch (e: Exception) {
        null
    }

    fun parse(dashboard: String): Card? =
        parse(runCatching { JSONObject(override ?: dashboard).optJSONObject("home") }.getOrNull())

    /** The word on the right of a row. Words, never a colour alone. */
    fun stateWord(device: Device): String = when {
        !device.online -> "ออฟไลน์"
        device.channels.isNotEmpty() -> "เปิด ${device.channels.count { it }} จาก ${device.channels.size}"
        device.on == true -> "เปิด"
        device.on == false -> "ปิด"
        else -> "—"
    }

    /** Lit right now, counting each channel of a multi-way switch as one light. */
    fun onCount(device: Device): Int = if (!device.online) 0 else
        if (device.channels.isNotEmpty()) device.channels.count { it } else if (device.on == true) 1 else 0

    /** "ไฟเพดาน · ห้องนั่งเล่น": the name, then the room when there is one. */
    fun label(device: Device): String =
        if (device.room.isEmpty()) device.name else "${device.name} · ${device.room}"

    /** The folded card's one line: "เปิดอยู่ 2 จาก 5". */
    fun summary(system: System): String =
        "เปิดอยู่ ${system.devices.count { onCount(it) > 0 }} จาก ${system.devices.size}"

    /** What counts as news: any light going on or off, or coming online. Not the age. */
    fun signature(card: Card): String = card.systems.joinToString("|") { s ->
        s.id + ":" + s.devices.joinToString(",") { "${it.name}=${stateWord(it)}" }
    }

    /**
     * A row gets a button only when the broker gave it a key, switching is not
     * stopped, the light is online and its state is known. An offline light has
     * no button: a tap that can only fail is worse than no button (DESIGN 5ง).
     */
    fun canSwitch(card: Card, device: Device): Boolean =
        card.control && device.target != null && device.online && device.channels.isEmpty() && device.on != null

    /** The button says what a tap will do. */
    fun buttonWord(device: Device): String = if (device.on == true) "สั่งปิด" else "สั่งเปิด"

    /** The broker's answer to POST /v1/home/switch; a refusal's message is shown as it is. */
    fun parseSwitched(body: String): Switched = try {
        val json = JSONObject(body)
        Switched(json.optBoolean("ok", false),
                 if (json.has("on") && !json.isNull("on")) json.optBoolean("on") else null,
                 if (json.has("online") && !json.isNull("online")) json.optBoolean("online") else null,
                 json.optString("message", "").trim().take(120).ifEmpty { FAILED })
    } catch (e: Exception) {
        Switched(false, null, null, FAILED)
    }

    /** The card with what eWeLink confirmed for one row, until the next reading. */
    fun apply(card: Card, target: String, switched: Switched): Card = card.copy(systems = card.systems.map { s ->
        s.copy(devices = s.devices.map { d ->
            if (d.target != target) d
            else d.copy(on = switched.on ?: d.on, online = switched.online ?: d.online)
        })
    })

    /** The rows a page shows, and how many did not fit. */
    fun rows(system: System): Pair<List<Device>, Int> =
        system.devices.take(MAX_ROWS) to (system.devices.size - MAX_ROWS).coerceAtLeast(0)
}
