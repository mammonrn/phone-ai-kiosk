package com.mammonrn.phoneaikiosk.radio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Where the station list lives (0.61.0): files/radio_stations.json, the app's
 * own file. The first time, it is copied from the seed in the APK
 * (assets/radio_stations.json, the stations measured to play). From then on the
 * person's edits are what is kept; an update of the app never overwrites them.
 *
 * A file that cannot be read is kept as radio_stations.bad.json (never deleted)
 * and the seed is used, so the radio always has a list.
 *
 * Small (at most RadioBook.MAX_STATIONS lines), so it is read and written on
 * the calling thread. Main thread only.
 */
object RadioStore {

    private const val FILE = "radio_stations.json"
    private const val SEED = "radio_stations.json"
    private const val TAG = "KioskRadio"

    private var book: RadioBook? = null
    private val main = Handler(Looper.getMainLooper())
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun book(context: Context): RadioBook = book ?: load(context.applicationContext).also { book = it }

    /** Applies [change]; on success the list is saved and every screen redraws. */
    fun edit(context: Context, change: (RadioBook) -> RadioBook.Result): RadioBook.Result {
        val result = change(book(context))
        val next = result.book ?: return result
        book = next
        save(context.applicationContext, next)
        main.post { for (l in listeners) l() }
        return result
    }

    /** A new station's id: short, random, and meaningless outside this list. */
    fun newId(): String = java.lang.Long.toHexString(java.security.SecureRandom().nextLong() and 0xFFFFFFFFFFL)

    private fun load(context: Context): RadioBook {
        val file = File(context.filesDir, FILE)
        if (file.exists()) {
            val read = runCatching { RadioBook.decode(file.readText()) }.getOrNull()
            if (read != null) return read
            val kept = File(context.filesDir, "radio_stations.bad.json")
            file.renameTo(kept)
            Log.w(TAG, "station list unreadable: kept aside, seed used")
        }
        val seed = runCatching { context.assets.open(SEED).use { it.readBytes().toString(Charsets.UTF_8) } }
            .getOrNull()?.let(RadioBook::decode) ?: RadioBook(emptyList())
        save(context, seed)
        Log.i(TAG, "station list seeded: ${seed.stations.size}")
        return seed
    }

    private fun save(context: Context, book: RadioBook) {
        runCatching {
            val file = File(context.filesDir, FILE)
            val tmp = File(file.path + ".tmp")
            tmp.writeText(book.encode())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }.onFailure { Log.w(TAG, "station list not saved: ${it.javaClass.simpleName}") }
    }
}
