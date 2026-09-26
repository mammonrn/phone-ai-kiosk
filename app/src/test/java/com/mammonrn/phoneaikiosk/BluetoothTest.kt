package com.mammonrn.phoneaikiosk

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.media.AudioDeviceInfo
import com.mammonrn.phoneaikiosk.settings.BluetoothModel
import com.mammonrn.phoneaikiosk.settings.BluetoothModel.Device
import com.mammonrn.phoneaikiosk.settings.BluetoothModel.Kind
import com.mammonrn.phoneaikiosk.settings.BluetoothModel.Link
import com.mammonrn.phoneaikiosk.settings.BluetoothModel.Radio
import com.mammonrn.phoneaikiosk.settings.BluetoothVoice
import com.mammonrn.phoneaikiosk.voice.Broker
import com.mammonrn.phoneaikiosk.voice.DONE_BEFORE_SPEAKING
import com.mammonrn.phoneaikiosk.voice.KioskAction
import com.mammonrn.phoneaikiosk.voice.Recorder
import com.mammonrn.phoneaikiosk.voice.isDoneWords
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bluetooth in the Control Panel and by voice (0.68, Poom): the states and their
 * words and pictures, the voice deck's truthful answers, the broker's action
 * bounded, and the wake word's microphone kept on the phone.
 */
class BluetoothTest {

    // ------------------------------------------------------------ states

    @Test fun `each radio state is its own value`() {
        assertEquals(Radio.UNSUPPORTED, BluetoothModel.radioOf(false, true, BluetoothAdapter.STATE_ON))
        assertEquals(Radio.NO_PERMISSION, BluetoothModel.radioOf(true, false, BluetoothAdapter.STATE_ON))
        assertEquals(Radio.ON, BluetoothModel.radioOf(true, true, BluetoothAdapter.STATE_ON))
        assertEquals(Radio.OFF, BluetoothModel.radioOf(true, true, BluetoothAdapter.STATE_OFF))
        assertEquals(Radio.TURNING_ON, BluetoothModel.radioOf(true, true, BluetoothAdapter.STATE_TURNING_ON))
        assertEquals(Radio.TURNING_OFF, BluetoothModel.radioOf(true, true, BluetoothAdapter.STATE_TURNING_OFF))
    }

    @Test fun `a device's link comes from its profiles, then its ACL link`() {
        val c = BluetoothProfile.STATE_CONNECTED; val ing = BluetoothProfile.STATE_CONNECTING
        val d = BluetoothProfile.STATE_DISCONNECTED; val out = BluetoothProfile.STATE_DISCONNECTING
        assertEquals(Link.CONNECTED, BluetoothModel.linkOf(listOf(d, c)))
        assertEquals(Link.CONNECTED, BluetoothModel.linkOf(listOf(ing, c)))
        assertEquals(Link.CONNECTING, BluetoothModel.linkOf(listOf(d, ing)))
        assertEquals(Link.DISCONNECTING, BluetoothModel.linkOf(listOf(out, d)))
        assertEquals(Link.NOT_CONNECTED, BluetoothModel.linkOf(listOf(d, d)))
        assertEquals(Link.NOT_CONNECTED, BluetoothModel.linkOf(emptyList()))
        // A keyboard: no audio profile, its ACL link seen up.
        assertEquals(Link.CONNECTED, BluetoothModel.linkOf(listOf(d), aclUp = true))
        assertEquals(Link.NOT_CONNECTED, BluetoothModel.linkOf(listOf(d), aclUp = false))
    }

    @Test fun `the kind comes from the Bluetooth class`() {
        val av = BluetoothClass.Device.Major.AUDIO_VIDEO
        assertEquals(Kind.HEADPHONES, BluetoothModel.kindOf(av, BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES))
        assertEquals(Kind.HEADPHONES, BluetoothModel.kindOf(av, BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET))
        assertEquals(Kind.SPEAKER, BluetoothModel.kindOf(av, BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER))
        assertEquals(Kind.CAR, BluetoothModel.kindOf(av, BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO))
        assertEquals(Kind.AUDIO, BluetoothModel.kindOf(av, BluetoothClass.Device.AUDIO_VIDEO_VIDEO_MONITOR))
        assertEquals(Kind.INPUT, BluetoothModel.kindOf(BluetoothClass.Device.Major.PERIPHERAL, 0))
        assertEquals(Kind.OTHER, BluetoothModel.kindOf(-1, -1))
    }

    private fun dev(name: String, link: Link = Link.NOT_CONNECTED, kind: Kind = Kind.SPEAKER) = Device("k-$name", name, kind, link)

    @Test fun `the tile differs by shape for off, on, busy and connected, and says the state`() {
        val off = BluetoothModel.look(Radio.OFF, emptyList())
        val on = BluetoothModel.look(Radio.ON, listOf(dev("a")))
        val busy = BluetoothModel.look(Radio.ON, listOf(dev("a", Link.CONNECTING)))
        val turning = BluetoothModel.look(Radio.TURNING_ON, emptyList())
        val linked = BluetoothModel.look(Radio.ON, listOf(dev("a", Link.CONNECTING), dev("b", Link.CONNECTED)))
        assertEquals(4, setOf(off.icon, on.icon, busy.icon, linked.icon).size)
        assertEquals(busy.icon, turning.icon)
        assertEquals(R.string.bt_tile_connected, linked.label)
        assertEquals(R.string.bt_tile_off, off.label)
        assertEquals(R.string.bt_tile_unsupported, BluetoothModel.look(Radio.UNSUPPORTED, emptyList()).label)
        assertEquals(R.string.bt_tile_no_permission, BluetoothModel.look(Radio.NO_PERMISSION, emptyList()).label)
        // Every label differs: a state is never shown in another's words.
        val labels = Radio.values().map { BluetoothModel.look(it, emptyList()).label } +
            listOf(on.label, busy.label, linked.label)
        assertEquals(labels.size - 1, labels.toSet().size)      // ON with nothing = on.label, counted twice
    }

    @Test fun `connected devices come first`() {
        val list = BluetoothModel.ordered(listOf(dev("ข"), dev("ก", Link.CONNECTED), dev("ค", Link.CONNECTING)))
        assertEquals(listOf("ก", "ค", "ข"), list.map { it.name })
    }

    @Test fun `pairing outcomes come from the bond states`() {
        val none = BluetoothDevice.BOND_NONE; val ing = BluetoothDevice.BOND_BONDING; val done = BluetoothDevice.BOND_BONDED
        assertEquals(true, BluetoothModel.pairOutcome(ing, done))
        assertEquals(false, BluetoothModel.pairOutcome(ing, none))
        assertNull(BluetoothModel.pairOutcome(none, ing))
        assertNull(BluetoothModel.pairOutcome(none, none))
    }

    @Test fun `a name is never empty or endless`() {
        assertEquals("หูฟัง", BluetoothModel.nameOf("หูฟัง", "WH-1000", "ไม่มีชื่อ"))
        assertEquals("WH-1000", BluetoothModel.nameOf("  ", "WH-1000", "ไม่มีชื่อ"))
        assertEquals("ไม่มีชื่อ", BluetoothModel.nameOf(null, null, "ไม่มีชื่อ"))
        assertEquals(60, BluetoothModel.nameOf("x".repeat(99), null, "-").length)
    }

    // ------------------------------------------------------------ the voice deck

    private class Deck(override var radio: Radio, override val paired: List<Device> = emptyList(),
                       val works: Boolean = true) : BluetoothVoice.Deck {
        val turned = ArrayList<Boolean>()
        override fun turn(on: Boolean): Boolean {
            turned.add(on)
            if (works) radio = if (on) Radio.ON else Radio.OFF
            return works
        }
    }

    private fun words(s: String?) = s!!.removePrefix(com.mammonrn.phoneaikiosk.voice.DONE_MARK)

    @Test fun `on and off are done before speaking, in the phone's words`() {
        val deck = Deck(Radio.OFF)
        val said = BluetoothVoice.perform("on", "", deck)
        assertTrue(isDoneWords(said)); assertEquals("เปิดบลูทูธแล้วครับ", words(said))
        assertEquals(listOf(true), deck.turned)
        val off = BluetoothVoice.perform("off", "", deck)
        assertTrue(isDoneWords(off)); assertEquals("ปิดบลูทูธแล้วครับ", words(off))
    }

    @Test fun `already so is said as already so, and nothing is switched`() {
        val deck = Deck(Radio.ON)
        assertEquals("บลูทูธเปิดอยู่แล้วครับ", words(BluetoothVoice.perform("on", "", deck)))
        assertTrue(deck.turned.isEmpty())
    }

    @Test fun `a refused switch is never said as done`() {
        val said = BluetoothVoice.perform("on", "", Deck(Radio.OFF, works = false))
        assertFalse(isDoneWords(said))
        assertEquals(BluetoothVoice.ON_FAILED, said)
        assertEquals(BluetoothVoice.OFF_FAILED, BluetoothVoice.perform("off", "", Deck(Radio.ON, works = false)))
    }

    @Test fun `no Bluetooth and no permission are said`() {
        assertEquals(BluetoothVoice.UNSUPPORTED, BluetoothVoice.perform("on", "", Deck(Radio.UNSUPPORTED)))
        assertEquals(BluetoothVoice.NO_PERMISSION, BluetoothVoice.perform("connect", "x", Deck(Radio.NO_PERMISSION)))
        assertNull(BluetoothVoice.perform("pair", "", Deck(Radio.ON)))
    }

    @Test fun `a device not paired is said to be not found`() {
        val said = BluetoothVoice.perform("connect", "ลำโพงครัว", Deck(Radio.ON, listOf(dev("หูฟังโซนี่"))))
        assertEquals("ไม่พบอุปกรณ์ชื่อ “ลำโพงครัว” ในรายการที่เคยจับคู่ครับ", said)
    }

    @Test fun `connecting is not claimed - the system screen is named`() {
        val said = BluetoothVoice.perform("connect", "หูฟังโซนี่", Deck(Radio.ON, listOf(dev("หูฟังโซนี่"))))
        assertFalse(isDoneWords(said))
        assertTrue(said!!.contains("หน้าตั้งค่าบลูทูธของระบบ"))
        val off = BluetoothVoice.perform("disconnect", "หูฟังโซนี่", Deck(Radio.ON, listOf(dev("หูฟังโซนี่", Link.CONNECTED))))
        assertFalse(isDoneWords(off))
    }

    @Test fun `a device already as asked is said so, done`() {
        val deck = Deck(Radio.ON, listOf(dev("หูฟังโซนี่", Link.CONNECTED), dev("ลำโพง JBL")))
        assertEquals("หูฟังโซนี่ เชื่อมต่ออยู่แล้วครับ", words(BluetoothVoice.perform("connect", "หูฟัง โซนี่", deck)))
        assertEquals("ลำโพง JBL ไม่ได้เชื่อมต่ออยู่ครับ", words(BluetoothVoice.perform("disconnect", "jbl", deck)))
    }

    @Test fun `two near names are asked back, a blank name is asked for`() {
        val deck = Deck(Radio.ON, listOf(dev("ลำโพงห้องนอน"), dev("ลำโพงห้องครัว")))
        assertTrue(BluetoothVoice.perform("connect", "ลำโพง", deck)!!.startsWith("หมายถึง"))
        assertEquals(BluetoothVoice.WHICH, BluetoothVoice.perform("connect", " ", deck))
    }

    @Test fun `connecting with the radio off says to turn it on first`() {
        assertEquals(BluetoothVoice.RADIO_OFF, BluetoothVoice.perform("connect", "x", Deck(Radio.OFF)))
        assertTrue(isDoneWords(BluetoothVoice.perform("disconnect", "x", Deck(Radio.OFF))))
    }

    // ------------------------------------------------------------ the broker's action

    @Test fun `the broker's bluetooth action is parsed bounded and allowlisted`() {
        val on = Broker.parseAction(JSONObject("""{"type":"bluetooth","command":"on"}"""))!!
        assertEquals(KioskAction.BLUETOOTH, on.type)
        assertEquals("on", on.params["command"]); assertEquals("", on.params["name"])
        val c = Broker.parseAction(JSONObject("""{"type":"bluetooth","command":"connect","name":"  หูฟัง  "}"""))!!
        assertEquals("หูฟัง", c.params["name"])
        assertNull(Broker.parseAction(JSONObject("""{"type":"bluetooth","command":"pair","name":"x"}""")))
        assertNull(Broker.parseAction(JSONObject("""{"type":"bluetooth","command":"forget","name":"x"}""")))
        assertNull(Broker.parseAction(JSONObject("""{"type":"bluetooth"}""")))
        assertNull(Broker.parseAction(JSONObject().put("type", "bluetooth").put("command", "connect").put("name", "x".repeat(61))))
        assertTrue(KioskAction.BLUETOOTH in DONE_BEFORE_SPEAKING)
    }

    // ------------------------------------------------------------ the microphone

    @Test fun `the wake word's microphone is the phone's own`() {
        val sco = AudioDeviceInfo.TYPE_BLUETOOTH_SCO to 7
        val ble = AudioDeviceInfo.TYPE_BLE_HEADSET to 8
        val builtIn = AudioDeviceInfo.TYPE_BUILTIN_MIC to 3
        assertEquals(3, Recorder.builtInMic(listOf(sco, ble, builtIn)))
        assertEquals(3, Recorder.builtInMic(listOf(builtIn, sco)))
        assertNull(Recorder.builtInMic(listOf(sco, ble)))
        assertNull(Recorder.builtInMic(emptyList()))
    }

    private val src = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }

    @Test fun `nothing in the app takes a headset's microphone`() {
        // SCO and the communication device are how audio capture moves to a headset;
        // the kiosk uses neither (the wake word stays on the phone's microphone).
        val banned = Regex("startBluetoothSco|setBluetoothScoOn|setCommunicationDevice|MODE_IN_COMMUNICATION|MODE_IN_CALL")
        val hits = src.walk().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            f.readLines().withIndex().filter { (_, l) -> !l.trim().startsWith("*") && !l.trim().startsWith("//") && banned.containsMatchIn(l) }
                .map { "${f.name}:${it.index + 1}" }
        }.toList()
        assertTrue(hits.joinToString(), hits.isEmpty())
        assertTrue(File(src, "com/mammonrn/phoneaikiosk/voice/Recorder.kt").readText().contains("setPreferredDevice"))
    }

    @Test fun `no player pins the sound to the phone's speaker`() {
        // Bluetooth headphones and speakers get Jarvis, music, video and the radio by
        // the system's own routing: nothing here chooses an output device.
        val banned = Regex("TYPE_BUILTIN_SPEAKER|setSpeakerphoneOn|setPreferredDevice")
        val hits = src.walk().filter { it.isFile && it.extension == "kt" && it.name != "Recorder.kt" }.flatMap { f ->
            f.readLines().withIndex().filter { (_, l) -> banned.containsMatchIn(l) }.map { "${f.name}:${it.index + 1}" }
        }.toList()
        assertTrue(hits.joinToString(), hits.isEmpty())
    }

    // ------------------------------------------------------------ manifest and icons

    @Test fun `the manifest asks for Bluetooth the narrow way`() {
        val m = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.exists() }
            .readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        assertTrue(m.contains("android.permission.BLUETOOTH_CONNECT"))
        assertTrue(Regex("""BLUETOOTH_SCAN"\s+android:usesPermissionFlags="neverForLocation"""").containsMatchIn(m))
        assertTrue(Regex("""android.permission.BLUETOOTH"\s+android:maxSdkVersion="30"""").containsMatchIn(m))
        assertFalse("no BLUETOOTH_PRIVILEGED: not a system app", m.contains("BLUETOOTH_PRIVILEGED"))
        assertFalse("no fine location for Bluetooth", m.contains("ACCESS_FINE_LOCATION"))
        assertTrue(m.contains(".settings.BluetoothActivity"))
    }

    @Test fun `the pairing allows only the settings package, and the kiosk list is unchanged`() {
        assertEquals(listOf("com.android.settings"), com.mammonrn.phoneaikiosk.settings.BluetoothSystem.PAIRING_PACKAGES)
        assertEquals(3, LockTaskAllowlist.packages("self").size)
    }

    @Test fun `the Bluetooth icons are 16x16 rectangles in at most four colours`() {
        val res = listOf(File("src/main/res/drawable"), File("app/src/main/res/drawable")).first { it.exists() }
        val rect = Regex("""^(M\d+,\d+h\d+v\d+h-\d+z\s*)+$""")
        for (name in listOf("bt_on", "bt_off", "bt_busy", "bt_linked", "bt_light")) {
            val xml = File(res, "ic_pixel_$name.xml").readText()
            assertTrue(name, xml.contains("""android:viewportWidth="16"""") && xml.contains("""android:viewportHeight="16""""))
            val paths = Regex("""pathData="([^"]+)"""").findAll(xml).map { it.groupValues[1].replace(Regex("\\s+"), " ").trim() }.toList()
            assertTrue(name, paths.isNotEmpty() && paths.all { rect.matches(it) })
            assertTrue(name, Regex("""fillColor="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toSet().size <= 4)
        }
        assertNotEquals(File(res, "ic_pixel_bt_busy.xml").readText(), File(res, "ic_pixel_bt_linked.xml").readText())
    }
}
