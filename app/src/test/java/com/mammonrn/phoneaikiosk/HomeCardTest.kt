package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.home.HomeCard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The "อุปกรณ์ในบ้าน" card (0.45.0): hidden until the broker has devices,
 * states in words, news only when a light changes. 0.46.0: a button only
 * where the broker gave a key, and the row changes only with the broker's
 * answer — the phone still holds no id, token or eWeLink address.
 */
class HomeCardTest {

    @After
    fun clear() {
        HomeCard.override = null
    }

    private fun dashboard(home: String) = """{"weather": {"ok": false}, "home": $home}"""

    private val connected = dashboard("""{"ok": true, "age_seconds": 0, "systems": [{"id": "ewelink",
        "name": "eWeLink", "devices": [
        {"name": "ไฟเพดาน", "room": "ห้องนั่งเล่น", "kind": "light", "online": true, "on": true, "channels": []},
        {"name": "สวิตช์ 3 ช่อง", "room": "", "kind": "switch", "online": true, "on": true, "channels": [true, false, false]},
        {"name": "ไฟหัวเตียง", "room": "ห้องนอน", "kind": "light", "online": true, "on": false, "channels": []},
        {"name": "ไฟหน้าบ้าน", "room": "", "kind": "switch", "online": false, "on": null, "channels": []}]}]}""")

    @Test
    fun `hidden until the broker has devices to show`() {
        assertNull(HomeCard.parse("""{"weather": {"ok": true}}"""))                          // an older broker
        assertNull(HomeCard.parse(dashboard("""{"ok": false, "error": "not-connected"}""")))
        assertNull(HomeCard.parse(dashboard("""{"ok": true, "systems": [{"id": "ewelink", "devices": []}]}""")))
        assertNull(HomeCard.parse("not json"))
        assertEquals(4, HomeCard.parse(connected)!!.systems.single().devices.size)
    }

    @Test
    fun `the state is a word, never a colour alone`() {
        val d = HomeCard.parse(connected)!!.systems.single().devices
        assertEquals(listOf("เปิด", "เปิด 1 จาก 3", "ปิด", "ออฟไลน์"), d.map(HomeCard::stateWord))
        assertEquals("ไฟเพดาน · ห้องนั่งเล่น", HomeCard.label(d[0]))
        assertEquals("สวิตช์ 3 ช่อง", HomeCard.label(d[1]))
        assertEquals("เปิดอยู่ 2 จาก 4", HomeCard.summary(HomeCard.parse(connected)!!.systems.single()))
    }

    @Test
    fun `news is a light changing, not the reading getting older`() {
        val now = HomeCard.parse(connected)!!
        val older = HomeCard.parse(connected.replace("\"age_seconds\": 0", "\"age_seconds\": 540"))!!
        assertEquals(HomeCard.signature(now), HomeCard.signature(older))
        val switched = HomeCard.parse(connected.replaceFirst("\"on\": true", "\"on\": false"))!!
        assertNotEquals(HomeCard.signature(now), HomeCard.signature(switched))
    }

    @Test
    fun `a failing broker that still has the last reading shows it, marked stale`() {
        val stale = HomeCard.parse(connected.replace("\"ok\": true", "\"ok\": false, \"error\": \"ewelink-500\"")
                                            .replace("\"age_seconds\": 0", "\"age_seconds\": 900"))!!
        assertTrue(stale.stale)
        assertEquals(900, stale.ageSeconds)
    }

    @Test
    fun `more than eight rows are counted, not squeezed in`() {
        val many = (1..11).joinToString(",") {
            """{"name": "ไฟ $it", "room": "", "kind": "light", "online": true, "on": false, "channels": []}"""
        }
        val card = HomeCard.parse(dashboard("""{"ok": true, "systems": [{"id": "ewelink", "name": "eWeLink",
            "devices": [$many]}]}"""))!!
        val (rows, more) = HomeCard.rows(card.systems.single())
        assertEquals(HomeCard.MAX_ROWS, rows.size)
        assertEquals(3, more)
    }

    private val switching = dashboard("""{"ok": true, "age_seconds": 0, "control": true, "systems": [{"id": "ewelink",
        "name": "eWeLink", "devices": [
        {"name": "Light1", "room": "ห้องนั่งเล่น", "kind": "plug", "online": true, "on": true, "channels": [], "target": "k1aa"},
        {"name": "Light2", "room": "ห้องนอน", "kind": "plug", "online": false, "on": null, "channels": [], "target": "k2bb"},
        {"name": "ไฟหน้าบ้าน", "room": "", "kind": "switch", "online": true, "on": false, "channels": [], "target": "k3cc"},
        {"name": "ไฟโต๊ะ", "room": "", "kind": "switch", "online": true, "on": false, "channels": []},
        {"name": "แปลก", "room": "", "kind": "light", "online": true, "on": false, "channels": [], "target": "https://x/y"}]}]}""")

    @Test
    fun `a button only where the broker gave a key, the light is online and switching is on`() {
        val card = HomeCard.parse(switching)!!
        val d = card.systems.single().devices
        assertEquals(listOf(true, false, true, false, false), d.map { HomeCard.canSwitch(card, it) })
        assertNull("a key that is not a key is dropped", d[4].target)
        assertEquals("สั่งปิด", HomeCard.buttonWord(d[0]))
        assertEquals("สั่งเปิด", HomeCard.buttonWord(d[2]))
        // ewelink-control off: the broker sends control=false and no keys.
        val stopped = HomeCard.parse(switching.replace("\"control\": true", "\"control\": false"))!!
        assertTrue(stopped.systems.single().devices.none { HomeCard.canSwitch(stopped, it) })
        assertFalse(HomeCard.parse(connected)!!.control)                      // a 0.45.0 broker: read only
    }

    @Test
    fun `the row changes only with what the broker says eWeLink did`() {
        val card = HomeCard.parse(switching)!!
        val done = HomeCard.parseSwitched("""{"ok": true, "result": "ok", "on": false, "online": true,
            "message": "ปิด Light1 แล้วครับ"}""")
        assertEquals("ปิด Light1 แล้วครับ", done.message)
        assertEquals(false, HomeCard.apply(card, "k1aa", done).systems.single().devices[0].on)
        val offline = HomeCard.parseSwitched("""{"ok": false, "result": "offline", "on": null, "online": false,
            "message": "Light1 ออฟไลน์อยู่ครับ ยังสั่งไม่ได้"}""")
        val after = HomeCard.apply(card, "k1aa", offline).systems.single().devices[0]
        assertEquals(true, after.on)                                            // not changed by the tap
        assertFalse(after.online)
        // A refusal or no answer: words, and nothing changes.
        assertEquals("ระบบขัดข้องครับ กรุณาลองใหม่อีกครั้ง", HomeCard.parseSwitched("").message)
        assertFalse(HomeCard.parseSwitched("""{"ok": false, "message": "สั่งไฟถี่เกินไปครับ"}""").ok)
        assertEquals(card, HomeCard.apply(card, "k1aa", HomeCard.parseSwitched("")))
    }

    @Test
    fun `the phone holds no id, token or eWeLink address`() {
        val code = listOf("src/main/java/com/mammonrn/phoneaikiosk/home/HomeCard.kt",
                          "src/main/java/com/mammonrn/phoneaikiosk/MainActivity.kt")
            .joinToString("\n") { file(it) }
        for (never in listOf("deviceid", "\"id\": ", "ewelink.cc", "coolkit", "Bearer ", "thing/status",
                             "outlet", "switches")) {
            assertFalse("the phone must not know $never", never in code.replace("TEST_HOME_CARD", ""))
        }
        // The one way to switch: the broker's route, with the card's key.
        val broker = file("src/main/java/com/mammonrn/phoneaikiosk/voice/Broker.kt")
        assertTrue("\"/v1/home/switch\"" in broker)
        assertFalse("coolkit" in broker || "ewelink.cc" in broker)
    }

    @Test
    fun `the card is a paged card with an eWeLink page, and the sample switch is debug only`() {
        val layout = file("src/main/res/layout/activity_main.xml")
        assertTrue("android:id=\"@+id/home_pages\"" in layout && "android:tag=\"ewelink|eWeLink\"" in layout)
        assertFalse("home_button" in layout)
        assertFalse("override =" in file("src/main/java/com/mammonrn/phoneaikiosk/MainActivity.kt"))
        assertTrue("HomeCard.override" in file("src/debug/java/com/mammonrn/phoneaikiosk/TestTriggerReceiver.kt"))
    }

    private fun file(path: String): String =
        listOf(File(path), File("app/$path")).first { it.exists() }.readText()
}
