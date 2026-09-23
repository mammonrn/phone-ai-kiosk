package com.mammonrn.phoneaikiosk.alarm

import android.content.Context
import com.mammonrn.phoneaikiosk.voice.VoiceState

/**
 * Where the alarms live: one JSON string in the app's own SharedPreferences.
 * Nothing about them leaves the phone — the broker only ever sees the command.
 */
object AlarmStore {

    private const val PREFS = "alarms"
    private const val KEY = "book"

    fun load(context: Context): AlarmBook =
        AlarmBook.fromJson(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null))

    /** Saves, re-schedules the next ring, and tells the screen to redraw. */
    fun save(context: Context, book: AlarmBook) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, book.toJson()).apply()
        AlarmScheduler.schedule(context, book)
        VoiceState.alarmsVersion += 1
    }
}
