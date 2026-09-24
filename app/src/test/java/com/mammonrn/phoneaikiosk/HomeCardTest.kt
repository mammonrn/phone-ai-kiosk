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
 * states in words, news only when a light changes, and nothing on the phone
 * that could switch anything.
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

    @Test
    fun `the phone holds nothing that could switch a light`() {
        val code = listOf("src/main/java/com/mammonrn/phoneaikiosk/home/HomeCard.kt",
                          "src/main/java/com/mammonrn/phoneaikiosk/MainActivity.kt")
            .joinToString("\n") { file(it) }
        for (never in listOf("deviceid", "\"id\": ", "ewelink.cc", "coolkit", "Bearer ", "thing/status")) {
            assertFalse("the phone must not know $never", never in code.replace("TEST_HOME_CARD", ""))
        }
        assertFalse(File("src/main/java/com/mammonrn/phoneaikiosk/home/HomeControl.kt").exists() ||
                    File("app/src/main/java/com/mammonrn/phoneaikiosk/home/HomeControl.kt").exists())
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
