package com.mammonrn.phoneaikiosk.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Where the playlists are kept (0.59.0): the app's own files, as
 * PlaylistBook's text. Read once, then held; every change is written at once,
 * off the main thread. Guarded by one lock: the screens change it, the voice
 * thread reads it.
 *
 * THE FIRST TIME 0.59.0 RUNS there is no file yet, and the book is made from
 * what 0.58 kept (PlaylistBook.migrate): the music player's list becomes
 * "รายการเดิม" — Poom: "ย้ายเป็น playlist ชื่อ รายการเดิม อัตโนมัติ ห้ามหาย".
 * A file that cannot be read is set aside (".bad"), never written over.
 *
 * Only counts are logged; never a list's name or a song's.
 */
object PlaylistStore {

    private const val FILE = "playlists.txt"
    private const val TAG = "KioskMusic"
    private val lock = Any()
    private var book: PlaylistBook? = null
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "kiosk-playlists") }
    private val main = Handler(Looper.getMainLooper())
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Reads under the lock. */
    fun <T> read(context: Context, block: (PlaylistBook) -> T): T = synchronized(lock) { block(load(context)) }

    /** Changes under the lock, then keeps it and tells the screens. */
    fun <T> edit(context: Context, block: (PlaylistBook) -> T): T {
        val (result, text) = synchronized(lock) {
            val b = load(context)
            block(b) to b.encode()
        }
        save(context, text)
        main.post { for (l in listeners) l() }
        return result
    }

    private fun load(context: Context): PlaylistBook {
        book?.let { return it }
        val dir = context.applicationContext.filesDir
        val file = File(dir, FILE)
        if (file.isFile) {
            val read = runCatching { PlaylistBook.decode(file.readText()) }.getOrNull()
            if (read != null) return read.also { book = it }
            runCatching { file.renameTo(File(dir, "$FILE.bad")) }
            Log.w(TAG, "playlists unreadable: set aside")
        }
        // 0.59.0's first start: what 0.58 kept becomes playlists.
        val session = runCatching { Session.decode(File(dir, "music_session.txt").readText()) }.getOrNull()
        val nas = runCatching { MusicShelf.nasTracks(context) }.getOrDefault(emptyList())
        val made = PlaylistBook.migrate(session, nas)
        book = made
        Log.i(TAG, "playlists made: lists=${made.lists.size} songs=${made.lists.sumOf { it.items.size }}")
        save(context, made.encode())
        return made
    }

    private fun save(context: Context, text: String) {
        val dir = context.applicationContext.filesDir
        writer.execute {
            runCatching {
                val file = File(dir, FILE)
                val tmp = File(dir, "$FILE.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            }.onFailure { Log.w(TAG, "playlists not saved: ${it.javaClass.simpleName}") }
        }
    }
}
