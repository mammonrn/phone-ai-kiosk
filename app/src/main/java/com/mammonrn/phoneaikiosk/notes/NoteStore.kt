package com.mammonrn.phoneaikiosk.notes

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Where the notes live (0.62.0): files/notes.json, the app's own file — not
 * shared storage, not backed up anywhere, never sent. A file that cannot be
 * read is kept as notes.bad.json (never deleted) and an empty book is used.
 *
 * Used from the screen (main thread) and from a spoken note_add (the voice's
 * network thread), so every read and edit holds one lock. An edit is kept only
 * when it was written: a failed write leaves the book as it was and says
 * NOT_SAVED, so Jarvis never says "เพิ่มแล้ว" for a line that is not there.
 *
 * Logs (KioskNotes) carry counts and outcomes, never a line's words or a list's name.
 */
object NoteStore : NoteVoice.Book {
    const val TAG = "KioskNotes"
    private const val FILE = "notes.json"

    private var context: Context? = null
    private var book: NoteBook? = null
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** One more for every kept edit: a screen redraws only for an edit it has not drawn. */
    @Volatile var version = 0
        private set

    fun remember(context: Context) {
        if (this.context == null) this.context = context.applicationContext
    }

    fun book(context: Context): NoteBook {
        remember(context)
        return book()
    }

    override fun book(): NoteBook = synchronized(lock) {
        book ?: load(requireNotNull(context) { "NoteStore.remember first" }).also { book = it }
    }

    fun edit(context: Context, change: (NoteBook) -> NoteBook.Result): NoteBook.Result {
        remember(context)
        return edit(change)
    }

    override fun edit(change: (NoteBook) -> NoteBook.Result): NoteBook.Result {
        val result = synchronized(lock) {
            val result = change(book())
            val next = result.book ?: return@synchronized result
            if (!save(requireNotNull(context), next)) return@synchronized NoteBook.Result.no(NoteBook.Refusal.NOT_SAVED)
            book = next
            version += 1
            result
        }
        if (result.ok) main.post { for (l in listeners) l() }
        return result
    }

    /** A new line's or list's id: short, random, meaningless outside this file. */
    override fun newId(): String = java.lang.Long.toHexString(java.security.SecureRandom().nextLong() and 0xFFFFFFFFFFL)

    private fun load(context: Context): NoteBook {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return NoteBook.fresh()
        val read = runCatching { NoteBook.decode(file.readText()) }.getOrNull()
        if (read != null) {
            Log.i(TAG, "notes read lists=${read.lists.size} items=${read.lists.sumOf { it.items.size }}")
            return read
        }
        file.renameTo(File(context.filesDir, "notes.bad.json"))
        Log.w(TAG, "notes unreadable: kept aside, empty book used")
        return NoteBook.fresh()
    }

    private fun save(context: Context, book: NoteBook): Boolean = runCatching {
        val file = File(context.filesDir, FILE)
        val tmp = File(file.path + ".tmp")
        tmp.writeText(book.encode())
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "rename" }
        }
        true
    }.getOrElse {
        Log.w(TAG, "notes not saved: ${it.javaClass.simpleName}")
        false
    }
}
