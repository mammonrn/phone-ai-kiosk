package com.mammonrn.phoneaikiosk.home

import org.json.JSONObject

/**
 * The "อุปกรณ์ในบ้าน" card (0.45.0, Poom): what the broker says about the
 * house's lights. It replaces the Google Home placeholder.
 *
 * SMALL ON PURPOSE (0.47.0, Poom 2026-09-24): the lights are switched by
 * voice and set up in the Control Panel's "ไฟในบ้าน" page, so the home card
 * only answers "what is on right now" — every light as a small pixel bulb
 * and its name, three to a row: yellow with rays = on, grey = off, a hollow
 * bulb crossed out = offline (its state is unknown, so it is never drawn as
 * off). No buttons: six 48dp tiles pushed Jarvis toward its 156dp floor.
 *
 * WHAT THE PHONE GETS. The broker's /v1/dashboard `home` panel: per light
 * (one per channel of a multi-way switch) a NAME, room, online and on/off.
 * No device id, no token, no account.
 *
 * SHOWN ONLY WHEN THERE IS SOMETHING TO SHOW: [parse] is null until the broker
 * says ok with at least one device — not connected, failing with nothing
 * cached, or an older broker with no `home` at all all keep the card hidden.
 *
 * Pure (org.json only), so HomeCardTest holds it on the JVM.
 */
object HomeCard {

    /** Bulbs to a row on the card. */
    const val PER_ROW = 3

    /** The card never grows past this many bulbs; the rest is "และอีก N ดวง". */
    const val MAX_BULBS = 9

    data class Device(val name: String, val room: String, val kind: String, val online: Boolean,
                      val on: Boolean?, val channels: List<Boolean>)

    data class System(val id: String, val name: String, val devices: List<Device>)

    data class Card(val systems: List<System>, val ageSeconds: Int, val stale: Boolean)

    /**
     * Debug builds only (TEST_HOME_CARD over adb): a sample panel, so the card
     * can be seen on the A07 without touching the real lights. Null in
     * release; nothing in main code sets it.
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
                    devices += Device(name, d.optString("room", "").trim(), d.optString("kind", ""),
                                      d.optBoolean("online", false),
                                      if (d.isNull("on") || !d.has("on")) null else d.optBoolean("on"),
                                      channels)
                }
                if (devices.isNotEmpty()) systems += System(s.optString("id", ""), s.optString("name", ""), devices)
            }
            if (systems.isEmpty()) null
            else Card(systems, home.optInt("age_seconds", 0), stale = !ok)
        }
    } catch (e: Exception) {
        null
    }

    fun parse(dashboard: String): Card? =
        parse(runCatching { JSONObject(override ?: dashboard).optJSONObject("home") }.getOrNull())

    /** The word for a light's state. Words, never a colour alone. */
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

    /** What a bulb shows. UNKNOWN (online, state not reported) is drawn like OFFLINE: not "off". */
    enum class Bulb { ON, OFF, OFFLINE, UNKNOWN }

    fun bulb(device: Device): Bulb = when {
        !device.online -> Bulb.OFFLINE
        onCount(device) > 0 -> Bulb.ON
        device.channels.isNotEmpty() || device.on == false -> Bulb.OFF
        else -> Bulb.UNKNOWN
    }

    /** What a screen reader says for a bulb: the name and the state in words. */
    fun spoken(device: Device): String = device.name + " " + when (bulb(device)) {
        Bulb.ON -> "เปิดอยู่"
        Bulb.OFF -> "ปิดอยู่"
        Bulb.OFFLINE -> "ออฟไลน์"
        Bulb.UNKNOWN -> "ไม่ทราบสถานะ"
    }

    /** The bulbs a card shows, and how many did not fit. */
    fun bulbs(system: System): Pair<List<Device>, Int> =
        system.devices.take(MAX_BULBS) to (system.devices.size - MAX_BULBS).coerceAtLeast(0)

    /** The dim line under the bulbs: only when there is something to add. */
    fun note(card: Card, more: Int): String = buildList {
        if (more > 0) add("และอีก $more ดวง")
        if (card.stale && card.ageSeconds >= 60) add("ข้อมูลเมื่อ ${card.ageSeconds / 60} นาทีก่อน")
    }.joinToString(" · ")

    /** The folded card's one line: "เปิดอยู่ 2 จาก 5". */
    fun summary(system: System): String =
        "เปิดอยู่ ${system.devices.count { onCount(it) > 0 }} จาก ${system.devices.size}"

    /** What counts as news: any light going on or off, or coming online. Not the age. */
    fun signature(card: Card): String = card.systems.joinToString("|") { s ->
        s.id + ":" + s.devices.joinToString(",") { "${it.name}=${stateWord(it)}" }
    }
}
