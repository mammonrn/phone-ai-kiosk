package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.home.HomeCard
import com.mammonrn.phoneaikiosk.home.HomeCard.Bulb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The "อุปกรณ์ในบ้าน" card: hidden until the broker has devices, news only
 * when a light changes. 0.47.0 (Poom): every light a pixel bulb — on, off
 * and offline look different in SHAPE, not only colour — nothing to press,
 * and the date moved to the Jarvis window.
 */
class HomeCardTest {

    @After
    fun clear() {
        HomeCard.override = null
    }

    private fun dashboard(home: String) = """{"weather": {"ok": false}, "home": $home}"""

    private val connected = dashboard("""{"ok": true, "age_seconds": 0, "systems": [{"id": "ewelink",
        "name": "eWeLink", "devices": [
        {"name": "ไฟหน้าบ้าน", "room": "ห้องนั่งเล่น", "kind": "switch", "online": true, "on": true, "channels": []},
        {"name": "ไฟเพดาน", "room": "ห้องนั่งเล่น", "kind": "switch", "online": true, "on": false, "channels": []},
        {"name": "Light1", "room": "ห้องนั่งเล่น", "kind": "plug", "online": true, "on": true, "channels": []},
        {"name": "Light2", "room": "ห้องนอน", "kind": "plug", "online": false, "on": null, "channels": []},
        {"name": "แปลก", "room": "", "kind": "light", "online": true, "on": null, "channels": []}]}]}""")

    @Test
    fun `hidden until the broker has devices to show`() {
        assertNull(HomeCard.parse("""{"weather": {"ok": true}}"""))                          // an older broker
        assertNull(HomeCard.parse(dashboard("""{"ok": false, "error": "not-connected"}""")))
        assertNull(HomeCard.parse(dashboard("""{"ok": true, "systems": [{"id": "ewelink", "devices": []}]}""")))
        assertNull(HomeCard.parse("not json"))
        assertEquals(5, HomeCard.parse(connected)!!.systems.single().devices.size)
    }

    @Test
    fun `each light is a bulb, and offline or unknown is never drawn as off`() {
        val d = HomeCard.parse(connected)!!.systems.single().devices
        assertEquals(listOf(Bulb.ON, Bulb.OFF, Bulb.ON, Bulb.OFFLINE, Bulb.UNKNOWN), d.map(HomeCard::bulb))
        assertEquals(listOf("ไฟหน้าบ้าน เปิดอยู่", "ไฟเพดาน ปิดอยู่", "Light1 เปิดอยู่", "Light2 ออฟไลน์",
                            "แปลก ไม่ทราบสถานะ"), d.map(HomeCard::spoken))
        assertEquals("เปิดอยู่ 2 จาก 5", HomeCard.summary(HomeCard.parse(connected)!!.systems.single()))
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
    fun `the dim line says only what the bulbs cannot`() {
        val card = HomeCard.parse(connected)!!
        assertEquals("", HomeCard.note(card, 0))
        val stale = HomeCard.parse(connected.replace("\"ok\": true", "\"ok\": false, \"error\": \"ewelink-500\"")
                                            .replace("\"age_seconds\": 0", "\"age_seconds\": 900"))!!
        assertTrue(stale.stale)
        assertEquals("และอีก 2 ดวง · ข้อมูลเมื่อ 15 นาทีก่อน", HomeCard.note(stale, 2))
    }

    @Test
    fun `more than nine bulbs are counted, not squeezed in`() {
        val many = (1..11).joinToString(",") {
            """{"name": "ไฟ $it", "room": "", "kind": "light", "online": true, "on": false, "channels": []}"""
        }
        val card = HomeCard.parse(dashboard("""{"ok": true, "systems": [{"id": "ewelink", "name": "eWeLink",
            "devices": [$many]}]}"""))!!
        val (shown, more) = HomeCard.bulbs(card.systems.single())
        assertEquals(HomeCard.MAX_BULBS, shown.size)
        assertEquals(2, more)
        assertEquals(0, HomeCard.MAX_BULBS % HomeCard.PER_ROW)
    }

    @Test
    fun `the three bulbs are our own pixel art and differ in shape`() {
        val on = file("src/main/res/drawable/ic_pixel_bulb_on.xml")
        val off = file("src/main/res/drawable/ic_pixel_bulb_off.xml")
        val offline = file("src/main/res/drawable/ic_pixel_bulb_offline.xml")
        for (icon in listOf(on, off, offline)) {
            assertTrue("android:viewportWidth=\"16\"" in icon && "Our own pixel art" in icon)
        }
        assertTrue("#FFD700" in on && "#FFD700" !in off && "#FFD700" !in offline)     // only ON is yellow
        assertTrue("M7,0h2v1h-2z" in on && "M7,0h2v1h-2z" !in off)                   // rays: ON only
        assertTrue("M4,4h8v5h-8z" in off && "M4,4h8v5h-8z" !in offline)             // offline is hollow
        assertTrue("M3,2h2v2h-2z" in offline)                                        // and crossed out
    }

    private val tappable = dashboard("""{"ok": true, "age_seconds": 0, "systems": [{"id": "ewelink",
        "name": "eWeLink", "devices": [
        {"name": "Light1", "kind": "plug", "online": true, "on": true, "channels": [], "icon": "bulb", "target": "k1aa"},
        {"name": "Light2", "kind": "plug", "online": false, "on": null, "channels": [], "icon": "fan", "reason": "offline"},
        {"name": "ไฟเพดาน", "kind": "switch", "online": true, "on": false, "channels": [], "reason": "not-allowed"},
        {"name": "โคม", "kind": "light", "online": true, "on": false, "channels": [], "reason": "stopped", "icon": "rocket"},
        {"name": "แปลก", "kind": "light", "online": true, "on": false, "channels": [], "target": "https://x"}]}]}""")

    @Test
    fun `a cell can be tapped only with the broker's key, and says why when it cannot`() {
        val d = HomeCard.parse(tappable)!!.systems.single().devices
        assertEquals(listOf(true, false, false, false, false), d.map(HomeCard::canTap))
        assertEquals("", HomeCard.whyNot(d[0]))
        assertEquals("Light2 ออฟไลน์อยู่ครับ สั่งไม่ได้", HomeCard.whyNot(d[1]))
        assertTrue("แผงควบคุม" in HomeCard.whyNot(d[2]))
        assertEquals("ตอนนี้ปิดการสั่งไฟไว้ครับ", HomeCard.whyNot(d[3]))
        // Icons: as chosen, by kind when none, and nothing that is not ours.
        assertEquals(listOf("bulb", "fan", "switch", "bulb", "bulb"), d.map { it.icon })
    }

    @Test
    fun `why a light cannot be tapped keeps to 70 characters with the longest name`() {
        val long = "ก".repeat(40)                          // home_settings.MAX_NAME on the broker
        val json = tappable.replace("\"Light2\"", "\"$long\"").replace("\"ไฟเพดาน\"", "\"$long\"")
            .replace("\"แปลก\"", "\"$long\"")
        for (device in HomeCard.parse(json)!!.systems.single().devices) {
            val why = HomeCard.whyNot(device)
            assertTrue("${why.length}: $why", why.length <= 70)
        }
    }

    @Test
    fun `the picture changes only with the broker's answer`() {
        val card = HomeCard.parse(tappable)!!
        val off = HomeCard.parseSwitched("""{"ok": true, "result": "ok", "on": false, "online": true,
            "message": "ปิด Light1 แล้วครับ"}""")
        assertEquals(false, HomeCard.apply(card, "k1aa", off).systems.single().devices[0].on)
        val failed = HomeCard.parseSwitched("")
        assertEquals("ระบบขัดข้องครับ กรุณาลองใหม่อีกครั้ง", failed.message)
        assertEquals(card, HomeCard.apply(card, "k1aa", failed))
        val offline = HomeCard.parseSwitched("""{"ok": false, "result": "offline", "on": true, "online": false,
            "message": "Light1 ออฟไลน์อยู่ครับ ยังสั่งไม่ได้"}""")
        val after = HomeCard.apply(card, "k1aa", offline).systems.single().devices[0]
        assertEquals(true, after.on)
        assertFalse(after.online)
        // The tap sets "กำลังสั่ง…" and waits; nothing in MainActivity sets a state from the tap itself.
        val main = file("src/main/java/com/mammonrn/phoneaikiosk/MainActivity.kt")
        val tap = main.substringAfter("private fun switchHome").substringBefore("\n    }\n")
        assertFalse("copy(on" in tap)
        assertTrue("HomeCard.apply(card, target, switched)" in tap)
    }

    @Test
    fun `every picture has its three states, drawn by us, differing in shape`() {
        for (icon in HomeCard.ICONS) {
            val on = file("src/main/res/drawable/ic_pixel_${icon}_on.xml")
            val off = file("src/main/res/drawable/ic_pixel_${icon}_off.xml")
            val offline = file("src/main/res/drawable/ic_pixel_${icon}_offline.xml")
            assertTrue(icon, listOf(on, off, offline).all { "Our own pixel art" in it && "viewportWidth=\"16\"" in it })
            assertTrue("$icon: only ON is yellow", "#FFD700" in on && "#FFD700" !in off && "#FFD700" !in offline)
            assertTrue("$icon: ON has more to it than colour", paths(on) > paths(off))
            assertTrue("$icon: offline is crossed out", "M3,2h2v2h-2z" in offline && "M3,2h2v2h-2z" !in off)
        }
    }

    private fun paths(xml: String) = Regex("M\\d+,\\d+").findAll(xml).count()

    @Test
    fun `the phone holds no id, token or eWeLink address, and switches only through the broker`() {
        val main = file("src/main/java/com/mammonrn/phoneaikiosk/MainActivity.kt")
        val card = file("src/main/java/com/mammonrn/phoneaikiosk/home/HomeCard.kt")
        for (never in listOf("deviceid", "\"id\": ", "ewelink.cc", "coolkit", "Bearer ", "thing/status",
                             "outlet", "switches")) {
            assertFalse("the phone must not know $never", never in (main + card).replace("TEST_HOME_CARD", ""))
        }
        val broker = file("src/main/java/com/mammonrn/phoneaikiosk/voice/Broker.kt")
        assertTrue("\"/v1/home/switch\"" in broker)
        assertFalse("coolkit" in broker || "ewelink.cc" in broker)
    }

    @Test
    fun `the date is on the Jarvis title bar, in the badge style, and gone from the taskbar`() {
        val layout = file("src/main/res/layout/activity_main.xml")
        assertFalse("taskbar_date" in layout)
        val date = layout.substringAfter("android:id=\"@+id/jarvis_date\"").substringBefore("/>")
        assertTrue("@color/retro_badge" in date && "10sp" in date && "@font/plex_thai" in date)
        val state = layout.substringAfter("android:id=\"@+id/jarvis_state\"").substringBefore("/>")
        assertTrue("layout_weight=\"1\"" in state && "ellipsize=\"end\"" in state && "maxLines=\"1\"" in state)
        // Same title bar: the date comes after the state, before the bar closes.
        val bar = layout.substringAfter("android:id=\"@+id/jarvis_state\"").substringBefore("</LinearLayout>")
        assertTrue("jarvis_date" in bar)
    }

    @Test
    fun `the card is a paged card with an eWeLink page, and the sample is debug only`() {
        val layout = file("src/main/res/layout/activity_main.xml")
        assertTrue("android:id=\"@+id/home_pages\"" in layout && "android:tag=\"ewelink|eWeLink\"" in layout)
        assertFalse("override =" in file("src/main/java/com/mammonrn/phoneaikiosk/MainActivity.kt"))
        assertTrue("HomeCard.override" in file("src/debug/java/com/mammonrn/phoneaikiosk/TestTriggerReceiver.kt"))
    }

    private fun file(path: String): String =
        listOf(File(path), File("app/$path")).first { it.exists() }.readText()
}
