package com.mammonrn.phoneaikiosk.notes

/**
 * The shopping list and notes by voice (0.62.0), carried out on the phone.
 * The broker recognises the sentence in code (server/kiosk_broker/notes.py)
 * and sends {"type": "note_add", "list", "text"} or {"type": "note_read", "list"};
 * the lists are only here, so only here can it be done or read.
 *
 * note_add is DONE BEFORE ANY WORD IS SAID (TurnPipeline.DONE_BEFORE_SPEAKING,
 * as the alarms): added → null, and the broker's "เพิ่ม … แล้วครับ" stands;
 * not added → the reason, which replaces it. So "เพิ่มแล้ว" is only heard
 * when the line is on the list.
 *
 * note_read IS the reply (TurnPipeline.ANSWERED_ON_PHONE): the lines still to
 * do, read from this phone's own file. The broker never sees the list.
 *
 * Plain Kotlin behind [Book], so NoteVoiceTest drives every case.
 */
object NoteVoice {

    /** The broker's bar (notes.MAX_TEXT_CHARS): the same number both ends. */
    const val MAX_VOICE_CHARS = 60

    /** Speech is cut by the broker at about 200 characters; a read-out stays under it. */
    const val MAX_SPOKEN = 180

    /** What adding by voice needs of the store. */
    interface Book {
        fun book(): NoteBook
        /** Applies [change] and saves; NOT_SAVED when the file could not be written. */
        fun edit(change: (NoteBook) -> NoteBook.Result): NoteBook.Result
        fun newId(): String
    }

    /** What was done: [added], and the words to say instead of the broker's (null = its words stand). */
    class Done(val added: Boolean, val instead: String?)

    fun add(listId: String, text: String, book: Book): Done {
        val name = spokenName(listId) ?: return Done(false, NO_SUCH_LIST)
        val t = NoteBook.clean(text)
        if (t.isEmpty()) return Done(false, NOTHING_HEARD)
        if (t.length > MAX_VOICE_CHARS) return Done(false, TOO_LONG)
        val result = book.edit { it.add(listId, t, book.newId()) }
        if (result.ok) return Done(true, null)
        return Done(false, when (result.refusal) {
            NoteBook.Refusal.DUPLICATE -> "มี ${short(t)} ใน${name}อยู่แล้วครับ"
            NoteBook.Refusal.FULL -> "${name}เต็มแล้วครับ ล้างที่ติ๊กแล้วออกก่อนนะครับ"
            NoteBook.Refusal.TOO_LONG -> TOO_LONG
            NoteBook.Refusal.EMPTY -> NOTHING_HEARD
            NoteBook.Refusal.MISSING -> NO_SUCH_LIST
            else -> NOT_SAVED
        })
    }

    /** The list read out: what is still to do, in the order added, under [MAX_SPOKEN] characters. */
    fun read(listId: String, book: NoteBook): String {
        val name = spokenName(listId) ?: return NO_SUCH_LIST
        val list = book.list(listId) ?: return NO_SUCH_LIST
        val pending = list.pending
        if (pending.isEmpty()) {
            return if (list.doneCount > 0) "${name}ติ๊กครบทุกอย่างแล้วครับ" else "${name}ยังว่างอยู่ครับ"
        }
        val unit = if (listId == NoteBook.SHOPPING) "อย่าง" else "เรื่อง"
        val head = "${name}มี ${pending.size} ${unit}ครับ "
        val said = ArrayList<String>()
        for ((i, item) in pending.withIndex()) {
            val left = pending.size - i - 1
            val tail = if (left > 0) " และอีก $left $unit ดูได้ที่หน้าโน้ตครับ" else ""
            val next = (said + item.text)
            if (said.isNotEmpty() && (head + joined(next) + tail).length > MAX_SPOKEN) break
            said += item.text
        }
        val rest = pending.size - said.size
        return head + joined(said) + if (rest > 0) " และอีก $rest $unit ดูได้ที่หน้าโน้ตครับ" else ""
    }

    /** "นม ไข่ และน้ำปลา": spaces, which the voice pauses on, and "และ" before the last. */
    fun joined(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(" ") + " และ" + items.last()
    }

    /** The lists a voice may name, as Jarvis says them. */
    fun spokenName(listId: String): String? = when (listId) {
        NoteBook.SHOPPING -> "รายการซื้อของ"
        NoteBook.NOTES -> "โน้ต"
        else -> null
    }

    /** A line said back whole, or not at all when long (never cut mid-word, DESIGN.md 7). */
    private fun short(text: String): String = if (text.length <= 30) text else "สิ่งนี้"

    const val NOTHING_HEARD = "ไม่ได้ยินว่าจะให้จดอะไรครับ ยังไม่ได้เพิ่ม"
    const val TOO_LONG = "สิ่งที่จะจดยาวเกินไปครับ ยังไม่ได้เพิ่ม"
    const val NO_SUCH_LIST = "ไม่พบรายการนั้นครับ"
    const val NOT_SAVED = "จดไม่สำเร็จครับ ยังไม่ได้เพิ่มในรายการ"
}
