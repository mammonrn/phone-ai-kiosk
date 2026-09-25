package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.notes.NoteBook
import com.mammonrn.phoneaikiosk.notes.NoteBook.Refusal
import com.mammonrn.phoneaikiosk.notes.NoteVoice
import com.mammonrn.phoneaikiosk.voice.Answer
import com.mammonrn.phoneaikiosk.voice.Broker
import com.mammonrn.phoneaikiosk.voice.KioskAction
import com.mammonrn.phoneaikiosk.voice.SpokenAudio
import com.mammonrn.phoneaikiosk.voice.TurnPipeline
import com.mammonrn.phoneaikiosk.voice.VoiceSink
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.62.0, notes and the shopping list (DESIGN.md 5ณ): the list model (add,
 * tick, edit, remove, order, the file round trip), the voice's add and read
 * (notes/NoteVoice), the broker's action as the phone reads it, and the turn:
 * an add done before speaking, a read that IS the reply.
 */
class NoteBookTest {

    private var next = 0
    private fun id() = "i${next++}"

    private fun NoteBook.Result.book(): NoteBook = requireNotNull(book) { "refused: $refusal" }

    private fun shopping(vararg lines: String): NoteBook {
        var b = NoteBook.fresh()
        for (l in lines) b = b.add(NoteBook.SHOPPING, l, id()).book()
        return b
    }

    private fun texts(b: NoteBook, list: String = NoteBook.SHOPPING) = b.list(list)!!.ordered().map { it.text }

    // ------------------------------------------------------------ the model

    @Test
    fun `a new phone has the two built-in lists, empty`() {
        val b = NoteBook.fresh()
        assertEquals(listOf(NoteBook.SHOPPING, NoteBook.NOTES), b.lists.map { it.id })
        assertTrue(b.lists.all { it.items.isEmpty() && it.builtIn })
    }

    @Test
    fun `lines are added at the end, cleaned, and the ticked go under the rest`() {
        var b = shopping("นม", "  ไข่ \n ไก่ ", "น้ำปลา")
        assertEquals(listOf("นม", "ไข่ ไก่", "น้ำปลา"), texts(b))
        val egg = b.list(NoteBook.SHOPPING)!!.items[1]
        b = b.tick(NoteBook.SHOPPING, egg.id, true).book()
        assertEquals(listOf("นม", "น้ำปลา", "ไข่ ไก่"), texts(b))
        assertEquals(listOf("นม", "น้ำปลา"), b.list(NoteBook.SHOPPING)!!.pending.map { it.text })
        // Untick: back among the rest, in the order it was added.
        b = b.tick(NoteBook.SHOPPING, egg.id, false).book()
        assertEquals(listOf("นม", "ไข่ ไก่", "น้ำปลา"), texts(b))
    }

    @Test
    fun `an add is refused in words - empty, too long, the same line still to do, full`() {
        val b = shopping("นม")
        assertEquals(Refusal.EMPTY, b.add(NoteBook.SHOPPING, "   ", id()).refusal)
        assertEquals(Refusal.TOO_LONG, b.add(NoteBook.SHOPPING, "ก".repeat(NoteBook.MAX_TEXT + 1), id()).refusal)
        assertEquals(Refusal.DUPLICATE, b.add(NoteBook.SHOPPING, " น ม ", id()).refusal)
        assertEquals(Refusal.MISSING, b.add("nope", "นม", id()).refusal)
        var full = NoteBook.fresh()
        repeat(NoteBook.MAX_ITEMS) { full = full.add(NoteBook.SHOPPING, "ของ $it", id()).book() }
        assertEquals(Refusal.FULL, full.add(NoteBook.SHOPPING, "อีกอย่าง", id()).refusal)
    }

    @Test
    fun `the same line already ticked comes back unticked at the end, not twice`() {
        var b = shopping("นม", "ไข่")
        val milk = b.list(NoteBook.SHOPPING)!!.items[0]
        b = b.tick(NoteBook.SHOPPING, milk.id, true).book()
        val again = b.add(NoteBook.SHOPPING, "นม", id())
        assertEquals(milk.id, again.itemId)
        b = again.book()
        assertEquals(listOf("ไข่", "นม"), texts(b))
        assertEquals(2, b.list(NoteBook.SHOPPING)!!.items.size)
        assertEquals(0, b.list(NoteBook.SHOPPING)!!.doneCount)
    }

    @Test
    fun `edit, remove one, clear the ticked - the rest keep their order`() {
        var b = shopping("นม", "ไข่", "น้ำปลา", "ข้าว")
        val items = b.list(NoteBook.SHOPPING)!!.items
        b = b.edit(NoteBook.SHOPPING, items[1].id, "ไข่เป็ด").book()
        assertEquals(Refusal.EMPTY, b.edit(NoteBook.SHOPPING, items[1].id, " ").refusal)
        assertEquals(Refusal.DUPLICATE, b.edit(NoteBook.SHOPPING, items[1].id, "นม").refusal)
        b = b.remove(NoteBook.SHOPPING, items[0].id).book()
        assertEquals(Refusal.MISSING, b.remove(NoteBook.SHOPPING, items[0].id).refusal)
        assertEquals(listOf("ไข่เป็ด", "น้ำปลา", "ข้าว"), texts(b))
        b = b.tick(NoteBook.SHOPPING, items[2].id, true).book()
        b = b.clearDone(NoteBook.SHOPPING).book()
        assertEquals(listOf("ไข่เป็ด", "ข้าว"), texts(b))
    }

    @Test
    fun `lists of one's own - made, renamed, deleted - the built-in two stay`() {
        var b = NoteBook.fresh()
        val made = b.addList("  ของไปทะเล ", "L1")
        b = made.book()
        assertEquals("L1", made.itemId)
        assertEquals("ของไปทะเล", b.list("L1")!!.name)
        assertEquals(Refusal.DUPLICATE, b.addList("ของไปทะเล", "L2").refusal)
        assertEquals(Refusal.EMPTY, b.addList(" ", "L2").refusal)
        assertEquals(Refusal.TOO_LONG, b.addList("ก".repeat(NoteBook.MAX_NAME + 1), "L2").refusal)
        b = b.renameList("L1", "ทะเล").book()
        assertEquals("ทะเล", b.list("L1")!!.name)
        assertEquals(Refusal.BUILT_IN, b.removeList(NoteBook.SHOPPING).refusal)
        assertEquals(Refusal.BUILT_IN, b.removeList(NoteBook.NOTES).refusal)
        assertEquals(Refusal.BUILT_IN, b.renameList(NoteBook.SHOPPING, "x").refusal)
        b = b.removeList("L1").book()
        assertNull(b.list("L1"))
        assertEquals(2, b.lists.size)
    }

    @Test
    fun `the file round trip keeps every list, line, tick and order`() {
        var b = shopping("นม", "ไข่ \"ไก่\"", "น้ำปลา")
        b = b.tick(NoteBook.SHOPPING, b.list(NoteBook.SHOPPING)!!.items[0].id, true).book()
        b = b.add(NoteBook.NOTES, "รหัส wifi อยู่หลังเราเตอร์", id()).book()
        b = b.addList("ทะเล", "L1").book().add("L1", "ครีมกันแดด", id()).book()
        val back = NoteBook.decode(b.encode())!!
        assertEquals(b.lists, back.lists)
    }

    @Test
    fun `a damaged file loses only its damaged lines, and the built-in lists are always there`() {
        val text = """{"version":1,"lists":[
            {"id":"L1","name":"ทะเล","items":[{"id":"a","text":"ครีม"},{"id":"a","text":"ซ้ำ"},{"id":"b","text":"  "},{"text":"ไม่มี id"}]},
            {"id":"shopping","name":"","items":[{"id":"x","text":"นม","done":true}]},
            {"id":"","name":"ไม่มี id","items":[]},
            "not a list"]}"""
        val b = NoteBook.decode(text)!!
        assertEquals(listOf(NoteBook.SHOPPING, NoteBook.NOTES, "L1"), b.lists.map { it.id })
        assertEquals(listOf("ครีม"), b.list("L1")!!.items.map { it.text })
        assertTrue(b.list(NoteBook.SHOPPING)!!.items.single().done)
        assertNull(NoteBook.decode("not json"))
        assertNull(NoteBook.decode("{}"))
    }

    // ------------------------------------------------------------ the voice

    /** The store, without Android: an edit is kept unless [failSave]. */
    private class FakeBook(var book: NoteBook = NoteBook.fresh(), var failSave: Boolean = false) : NoteVoice.Book {
        private var n = 0
        override fun book() = book
        override fun edit(change: (NoteBook) -> NoteBook.Result): NoteBook.Result {
            val r = change(book)
            val next = r.book ?: return r
            if (failSave) return NoteBook.Result.no(Refusal.NOT_SAVED)
            book = next
            return r
        }
        override fun newId() = "v${n++}"
    }

    @Test
    fun `a spoken add - added is true and the broker's words stand`() {
        val store = FakeBook()
        val done = NoteVoice.add(NoteBook.SHOPPING, " นม ", store)
        assertTrue(done.added)
        assertNull(done.instead)
        assertEquals(listOf("นม"), store.book.list(NoteBook.SHOPPING)!!.items.map { it.text })
        assertTrue(NoteVoice.add(NoteBook.NOTES, "จ่ายค่าไฟ", store).added)
    }

    @Test
    fun `a spoken add that was not done says why, never added`() {
        val store = FakeBook()
        val empty = NoteVoice.add(NoteBook.SHOPPING, "   ", store)
        assertFalse(empty.added)
        assertEquals(NoteVoice.NOTHING_HEARD, empty.instead)
        assertTrue(store.book.list(NoteBook.SHOPPING)!!.items.isEmpty())

        val long = NoteVoice.add(NoteBook.SHOPPING, "ก".repeat(NoteVoice.MAX_VOICE_CHARS + 1), store)
        assertFalse(long.added)
        assertEquals(NoteVoice.TOO_LONG, long.instead)

        assertEquals(NoteVoice.NO_SUCH_LIST, NoteVoice.add("../x", "นม", store).instead)

        NoteVoice.add(NoteBook.SHOPPING, "นม", store)
        val twice = NoteVoice.add(NoteBook.SHOPPING, "นม", store)
        assertFalse(twice.added)
        assertEquals("มี นม ในรายการซื้อของอยู่แล้วครับ", twice.instead)

        store.failSave = true
        val unsaved = NoteVoice.add(NoteBook.SHOPPING, "ไข่", store)
        assertFalse(unsaved.added)
        assertEquals(NoteVoice.NOT_SAVED, unsaved.instead)
        assertEquals(listOf("นม"), store.book.list(NoteBook.SHOPPING)!!.items.map { it.text })
        for (words in listOf(empty, long, twice, unsaved).map { it.instead!! }) assertFalse(words.contains("แล้วครับ") && words.startsWith("เพิ่ม"))
    }

    @Test
    fun `a list read out - what is still to do, in order, short enough to say`() {
        var b = shopping("นม", "ไข่", "น้ำปลา")
        b = b.tick(NoteBook.SHOPPING, b.list(NoteBook.SHOPPING)!!.items[1].id, true).book()
        assertEquals("รายการซื้อของมี 2 อย่างครับ นม และน้ำปลา", NoteVoice.read(NoteBook.SHOPPING, b))
        assertEquals("โน้ตยังว่างอยู่ครับ", NoteVoice.read(NoteBook.NOTES, b))
        val allDone = b.list(NoteBook.SHOPPING)!!.items.fold(b) { acc, it -> acc.tick(NoteBook.SHOPPING, it.id, true).book() }
        assertEquals("รายการซื้อของติ๊กครบทุกอย่างแล้วครับ", NoteVoice.read(NoteBook.SHOPPING, allDone))
        assertEquals(NoteVoice.NO_SUCH_LIST, NoteVoice.read("L1", b))

        var many = NoteBook.fresh()
        repeat(40) { many = many.add(NoteBook.SHOPPING, "ของใช้ในบ้านชิ้นที่ $it", id()).book() }
        val said = NoteVoice.read(NoteBook.SHOPPING, many)
        assertTrue(said, said.length <= NoteVoice.MAX_SPOKEN)
        assertTrue(said.startsWith("รายการซื้อของมี 40 อย่างครับ ของใช้ในบ้านชิ้นที่ 0"))
        assertTrue(said, Regex("และอีก \\d+ อย่าง ดูได้ที่หน้าโน้ตครับ$").containsMatchIn(said))
    }

    // ------------------------------------------------------------ the broker's action, the turn

    @Test
    fun `the broker's note actions as the phone reads them - bounded, never dropped`() {
        val add = Broker.parseAction(JSONObject("""{"type":"note_add","list":"shopping","text":"  นม "}"""))!!
        assertEquals(KioskAction.NOTE_ADD, add.type)
        assertEquals(mapOf("list" to "shopping", "text" to "นม"), add.params)
        // An empty or long line still arrives, so NoteVoice refuses it in words
        // instead of the broker's "เพิ่มแล้ว" standing.
        assertNotNull(Broker.parseAction(JSONObject("""{"type":"note_add","list":"shopping","text":""}""")))
        val long = Broker.parseAction(JSONObject("""{"type":"note_add","list":"shopping","text":"${"ก".repeat(500)}"}"""))!!
        assertEquals(NoteVoice.MAX_VOICE_CHARS + 1, long.params["text"]!!.length)
        val read = Broker.parseAction(JSONObject("""{"type":"note_read","list":"notes"}"""))!!
        assertEquals(mapOf("list" to "notes"), read.params)
        assertTrue(KioskAction.NOTE_ADD in com.mammonrn.phoneaikiosk.voice.DONE_BEFORE_SPEAKING)
        assertTrue(KioskAction.NOTE_READ in com.mammonrn.phoneaikiosk.voice.ANSWERED_ON_PHONE)
    }

    private class NoteSink : VoiceSink {
        override var stt = "idle"
        override var chat = "idle"
        override var tts = "idle"
        override var heard = ""
        override var reply = ""
        override var lastError = ""
    }

    private fun turn(action: KioskAction, reply: String, perform: (KioskAction) -> String?): Pair<List<String>, NoteSink> {
        val sink = NoteSink()
        val spoken = mutableListOf<String>()
        val order = mutableListOf<String>()
        TurnPipeline(
            transcribe = { "เพิ่ม นม ในรายการซื้อของ" },
            ask = { _, _ -> Answer(reply, "c1", action) },
            speak = { spoken += it; order += "speak"; SpokenAudio(ByteArray(10), "") },
            play = { true },
            sayLocally = { spoken += "local:$it"; true },
            perform = { order += "perform"; perform(it) },
            state = sink,
        ).run(ByteArray(TurnPipeline.WAV_HEADER_BYTES + 100), null)
        assertEquals(listOf("perform", "speak"), order)          // done BEFORE a word is said
        return spoken to sink
    }

    @Test
    fun `an add is done before speaking - the broker's words only when added, else the reason`() {
        val add = KioskAction(KioskAction.NOTE_ADD, "", mapOf("list" to "shopping", "text" to "นม"))
        val (ok, okSink) = turn(add, "เพิ่ม นม ในรายการซื้อของแล้วครับ") { null }
        assertEquals(listOf("เพิ่ม นม ในรายการซื้อของแล้วครับ"), ok)
        assertEquals("", okSink.lastError)
        val (no, noSink) = turn(add, "เพิ่ม นม ในรายการซื้อของแล้วครับ") { NoteVoice.NOT_SAVED }
        assertEquals(listOf(NoteVoice.NOT_SAVED), no)
        assertEquals("action-failed", noSink.lastError)
    }

    @Test
    fun `a read is answered from the phone's list - the words are the reply, not a failure`() {
        val read = KioskAction(KioskAction.NOTE_READ, "", mapOf("list" to "shopping"))
        val (said, sink) = turn(read, "รายการอยู่ในหน้าโน้ตของแผงควบคุมครับ") { "รายการซื้อของมี 1 อย่างครับ นม" }
        assertEquals(listOf("รายการซื้อของมี 1 อย่างครับ นม"), said)
        assertEquals("", sink.lastError)
        assertEquals("รายการซื้อของมี 1 อย่างครับ นม", sink.reply)
    }
}
