package com.mammonrn.phoneaikiosk.settings

import com.mammonrn.phoneaikiosk.media.MusicLibrary
import com.mammonrn.phoneaikiosk.voice.doneWords

/**
 * A spoken Bluetooth command (0.68, Poom), done on this phone BEFORE the reply is
 * said. The broker recognises the sentence in code and sends
 * {"type":"bluetooth","command":"on"|"off"|"connect"|"disconnect","name":…};
 * only the phone knows its Bluetooth, so the words said are always the phone's:
 * [doneWords] when it was done (or already so), otherwise the reason — never a
 * claim of something not done.
 *
 * Connecting and disconnecting a paired device have no public API (BluetoothLink),
 * so they are not done by voice: the answer says where they are done, unless the
 * device is already in the state asked for.
 *
 * Plain Kotlin: BluetoothTest.
 */
object BluetoothVoice {

    val COMMANDS = setOf("on", "off", "connect", "disconnect")
    const val MAX_NAME = 60

    const val GUESS_MIN = 0.6
    const val GUESS_MARGIN = 0.15

    interface Deck {
        val radio: BluetoothModel.Radio
        val paired: List<BluetoothModel.Device>
        /** Turns the radio on or off and waits for it; true when it got there. */
        fun turn(on: Boolean): Boolean
    }

    const val UNSUPPORTED = "เครื่องนี้ไม่มีบลูทูธครับ"
    const val NO_PERMISSION = "แอปยังไม่ได้รับสิทธิ์ใช้บลูทูธครับ ต้องให้ผู้ดูแลตั้งค่าเครื่องครับ"
    const val ON_FAILED = "เปิดบลูทูธเองไม่ได้ครับ เปิดได้ที่หน้าบลูทูธในแผงควบคุม"
    const val OFF_FAILED = "ปิดบลูทูธเองไม่ได้ครับ ปิดได้ที่หน้าบลูทูธในแผงควบคุม"
    const val RADIO_OFF = "บลูทูธปิดอยู่ครับ สั่งเปิดบลูทูธก่อนนะครับ"
    const val WHICH = "ต้องการอุปกรณ์ชื่ออะไรครับ"

    sealed class Found {
        data class One(val device: BluetoothModel.Device) : Found()
        data class Two(val a: BluetoothModel.Device, val b: BluetoothModel.Device) : Found()
        data object None : Found()
    }

    /** The same spoken name (spaces and case aside), then one containing it, then the closest by likeness. */
    fun find(devices: List<BluetoothModel.Device>, spoken: String): Found {
        val want = squash(spoken)
        if (want.isEmpty()) return Found.None
        devices.firstOrNull { squash(it.name) == want }?.let { return Found.One(it) }
        // "ลำโพง" finds "ลำโพง JBL"; "หูฟังโซนี่ของผม" finds "หูฟังโซนี่" (a name of 3+ letters inside what was said).
        val containing = devices.filter { d ->
            val n = squash(d.name)
            n.contains(want) || (n.length >= 3 && want.contains(n))
        }
        if (containing.size == 1) return Found.One(containing[0])
        val pool = containing.ifEmpty { devices }
        val ranked = pool.map { it to MusicLibrary.likeness(squash(it.name), want) }.sortedByDescending { it.second }
        val best = ranked.firstOrNull() ?: return Found.None
        if (best.second < GUESS_MIN && containing.isEmpty()) return Found.None
        val second = ranked.getOrNull(1)
        if (second != null && best.second - second.second < GUESS_MARGIN) return Found.Two(best.first, second.first)
        return Found.One(best.first)
    }

    /** The words to say: [doneWords] when done or already so, else the reason. Null for an unknown command. */
    fun perform(command: String, name: String, deck: Deck): String? {
        if (command !in COMMANDS) return null
        val radio = deck.radio
        if (radio == BluetoothModel.Radio.UNSUPPORTED) return UNSUPPORTED
        if (radio == BluetoothModel.Radio.NO_PERMISSION) return NO_PERMISSION
        return when (command) {
            "on" -> when (radio) {
                BluetoothModel.Radio.ON -> doneWords("บลูทูธเปิดอยู่แล้วครับ")
                else -> if (deck.turn(true)) doneWords("เปิดบลูทูธแล้วครับ") else ON_FAILED
            }
            "off" -> when (radio) {
                BluetoothModel.Radio.OFF -> doneWords("บลูทูธปิดอยู่แล้วครับ")
                else -> if (deck.turn(false)) doneWords("ปิดบลูทูธแล้วครับ") else OFF_FAILED
            }
            else -> link(command, name.trim().take(MAX_NAME), radio, deck)
        }
    }

    private fun link(command: String, name: String, radio: BluetoothModel.Radio, deck: Deck): String {
        if (name.isEmpty()) return WHICH
        if (radio != BluetoothModel.Radio.ON) {
            return if (command == "disconnect") doneWords("บลูทูธปิดอยู่ครับ ไม่ได้เชื่อมต่ออุปกรณ์ใดอยู่") else RADIO_OFF
        }
        val device = when (val found = find(deck.paired, name)) {
            is Found.One -> found.device
            is Found.Two -> return "หมายถึง “${found.a.name}” หรือ “${found.b.name}” ครับ"
            Found.None -> return "ไม่พบอุปกรณ์ชื่อ “$name” ในรายการที่เคยจับคู่ครับ"
        }
        val connected = device.link == BluetoothModel.Link.CONNECTED
        return when {
            command == "connect" && connected -> doneWords("${device.name} เชื่อมต่ออยู่แล้วครับ")
            command == "disconnect" && device.link == BluetoothModel.Link.NOT_CONNECTED ->
                doneWords("${device.name} ไม่ได้เชื่อมต่ออยู่ครับ")
            command == "connect" ->
                "การเชื่อมต่อ ${device.name} ต้องกดในหน้าตั้งค่าบลูทูธของระบบครับ เปิดได้จากหน้าบลูทูธในแผงควบคุม"
            else ->
                "การยกเลิกการเชื่อมต่อ ${device.name} ต้องกดในหน้าตั้งค่าบลูทูธของระบบครับ เปิดได้จากหน้าบลูทูธในแผงควบคุม"
        }
    }

    private fun squash(s: String) = s.lowercase().filter { !it.isWhitespace() }
}
