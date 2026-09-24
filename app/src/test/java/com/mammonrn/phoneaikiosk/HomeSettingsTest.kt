package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.home.HomeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Control Panel's "ไฟในบ้าน" page (0.47.0): read from the broker's answer,
 * a change shown only as the broker answered it, and nothing on the phone
 * that could reach eWeLink or name a device by its id.
 */
class HomeSettingsTest {

    /** The shape home_settings.view sends, for Poom's house (Switch1: three channels, no names). */
    private val house = """{"ok": true, "age_seconds": 0, "control": true, "devices": [
        {"key": "a1b2c3d4e5f60708", "name": "Light2", "own_name": "", "ewelink_name": "Light2",
         "room": "ห้องนอน", "kind": "plug", "online": false, "on": null, "allowed": true, "clash": [],
         "rssi": -70, "signal": "พอใช้", "channels": []},
        {"key": "0f1e2d3c4b5a6978", "name": "Switch1", "own_name": "", "ewelink_name": "Switch1",
         "room": "Livingroom", "kind": "switch", "online": true, "on": true, "allowed": true, "clash": [],
         "rssi": -52, "signal": "ดี", "channels": [
           {"channel": 0, "name": "ไฟหน้าบ้าน", "own_name": "ไฟหน้าบ้าน", "ewelink_name": "", "on": true,
            "allowed": true, "active": true, "clash": [], "icon": "fan", "icon_chosen": true},
           {"channel": 1, "name": "Switch1 ช่อง 2", "own_name": "", "ewelink_name": "", "on": false,
            "allowed": false, "active": true, "clash": ["Light1"]},
           {"channel": 2, "name": "Switch1 ช่อง 3", "own_name": "", "ewelink_name": "", "on": false,
            "allowed": true, "active": false, "clash": []}]},
        {"key": "https://evil/x", "name": "bad key", "channels": []}]}"""

    @Test
    fun `the page reads names, state, permission and where each name came from`() {
        val page = HomeSettings.parse(house)
        assertEquals("", page.error)
        assertEquals(listOf("Light2", "Switch1"), page.devices.map { it.name })       // a key that is not one: dropped
        val light2 = page.devices[0]
        assertEquals("ปลั๊ก · ห้องนอน · ออฟไลน์", HomeSettings.describe(light2))
        assertEquals("ออฟไลน์", HomeSettings.state(light2.online, light2.on))
        val switch = page.devices[1]
        assertEquals("สวิตช์ 3 ช่อง · Livingroom · ออนไลน์", HomeSettings.describe(switch))
        val (ch1, ch2, ch3) = switch.channels
        assertEquals("ชื่อที่ตั้งเอง", HomeSettings.source(ch1.ownName, ch1.ewelinkName))
        assertEquals("ยังไม่ได้ตั้งชื่อ", HomeSettings.source(ch2.ownName, ch2.ewelinkName))
        assertEquals("ชื่อจาก eWeLink", HomeSettings.source("", "Channel1"))
        assertEquals("เปิดอยู่", HomeSettings.state(true, ch1.on))
        assertFalse(ch3.active)
        // Icons: chosen, or the kind's own (a switch's channel is a switch, a plug a bulb).
        assertEquals("fan", ch1.icon)
        assertTrue(ch1.iconChosen)
        assertEquals("switch", ch2.icon)
        assertEquals("bulb", light2.icon)
        assertTrue(ch1.voice)
        assertEquals("ชื่อซ้ำกับ \"Light1\" จาร์วิสจะแยกไม่ออก กรุณาเปลี่ยนชื่อ", HomeSettings.clashWarning(ch2.clash))
        assertEquals("", HomeSettings.clashWarning(ch1.clash))
    }

    @Test
    fun `not connected, a refusal and nonsense all come back as words`() {
        assertEquals("not-connected", HomeSettings.parse("""{"ok": false, "error": "not-connected"}""").error)
        assertEquals("ส่งคำขอถี่เกินไปครับ กรุณารอสักครู่",
            HomeSettings.parse("""{"ok": false, "error": {"code": "rate", "message": "ส่งคำขอถี่เกินไปครับ กรุณารอสักครู่"}}""").error)
        assertEquals(HomeSettings.FAILED, HomeSettings.parse("""{"ok": false, "error": {"code": "http_404", "message": ""}}""").error)
        assertEquals("unreadable", HomeSettings.parse("<html>").error)
    }

    @Test
    fun `a change is shown only as the broker answered it`() {
        val saved = HomeSettings.parseChanged("""{"ok": true, "message": "บันทึกชื่อแล้วครับ สั่งด้วยเสียงได้ทันที",
            "view": $house}""")
        assertTrue(saved.ok)
        assertEquals("บันทึกชื่อแล้วครับ สั่งด้วยเสียงได้ทันที", saved.message)
        assertEquals(2, saved.page!!.devices.size)
        val clash = HomeSettings.parseChanged("""{"ok": false, "error": {"code": "duplicate",
            "message": "ชื่อ \"หน้าบ้าน\" ซ้ำกับ \"ไฟหน้าบ้าน\" ครับ"}}""")
        assertFalse(clash.ok)
        assertTrue("ซ้ำกับ" in clash.message)
        assertNull(clash.page)
        assertEquals(HomeSettings.FAILED, HomeSettings.parseChanged("").message)
    }

    @Test
    fun `the page holds no id, and cannot switch a light`() {
        val code = listOf("src/main/java/com/mammonrn/phoneaikiosk/home/HomeSettings.kt",
                          "src/main/java/com/mammonrn/phoneaikiosk/settings/LightsPage.kt").joinToString("\n") { file(it) }
        for (never in listOf("deviceid", "ewelink.cc", "coolkit", "/v1/home/switch", "homeSwitch", "outlet")) {
            assertFalse("the page must not know $never", never in code)
        }
        val broker = file("src/main/java/com/mammonrn/phoneaikiosk/voice/Broker.kt")
        assertTrue("\"/v1/home/devices\"" in broker && "\"/v1/home/name\"" in broker && "\"/v1/home/allow\"" in broker)
        // Quotes in a string resource are dropped unless escaped (seen on the A07, 0.47.0).
        val strings = file("src/main/res/values/strings.xml")
        for (line in strings.lines().filter { "name=\"lights_" in it }) {
            val value = line.substringAfter(">").substringBeforeLast("<")
            assertFalse("unescaped quote in $line", Regex("""(?<!\\)"""").containsMatchIn(value))
        }
        // In the Control Panel, with its own icon.
        assertTrue("it.lights.open()" in file("src/main/java/com/mammonrn/phoneaikiosk/settings/SettingsActivity.kt"))
    }

    private fun file(path: String): String =
        listOf(File(path), File("app/$path")).first { it.exists() }.readText()

    @Test
    fun `each device says its WiFi signal in words and dBm, and when it is only the last reading`() {
        val page = HomeSettings.parse(house)
        val light2 = page.devices.first { it.name == "Light2" }
        val switch1 = page.devices.first { it.name == "Switch1" }
        assertEquals("สัญญาณ WiFi: พอใช้ (-70 dBm) · ค่าที่อ่านได้ครั้งล่าสุด", HomeSettings.signalLine(light2))
        assertEquals("สัญญาณ WiFi: ดี (-52 dBm)", HomeSettings.signalLine(switch1))
        // An older broker sends neither: no line at all.
        val old = HomeSettings.parse(house.replace("\"rssi\": -52, \"signal\": \"ดี\", ", ""))
        assertEquals("", HomeSettings.signalLine(old.devices.first { it.name == "Switch1" }))
    }
}
