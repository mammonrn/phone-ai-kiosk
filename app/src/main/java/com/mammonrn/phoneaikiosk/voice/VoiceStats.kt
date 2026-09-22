package com.mammonrn.phoneaikiosk.voice

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * The counters an eight-hour wake word test needs, and nothing else.
 *
 * The acceptance criteria Poom set are a hit rate at three metres and a false
 * positive rate per eight hours. Neither can be argued about from a feeling, so
 * they are counted here, persisted so a reboot mid-test does not lose the run,
 * and dumped in a shape `adb shell` can read.
 *
 * A "false positive" is a detection that nobody confirmed. The test procedure is
 * that the observer confirms real wakes as they happen, so anything left
 * unconfirmed is counted against the rate — which is the conservative direction.
 */
class VoiceStats(context: Context) {

    private val prefs = context.getSharedPreferences("voice_stats", Context.MODE_PRIVATE)

    private val wakes = AtomicInteger(prefs.getInt(KEY_WAKES, 0))
    private val confirmed = AtomicInteger(prefs.getInt(KEY_CONFIRMED, 0))
    private val turns = AtomicInteger(prefs.getInt(KEY_TURNS, 0))
    private val errors = AtomicInteger(prefs.getInt(KEY_ERRORS, 0))

    fun recordWake() = bump(KEY_WAKES, wakes)

    /** Called when a human says "yes, that one was me". */
    fun recordConfirmed() = bump(KEY_CONFIRMED, confirmed)

    fun recordTurn() = bump(KEY_TURNS, turns)

    fun recordError() = bump(KEY_ERRORS, errors)

    private fun bump(key: String, counter: AtomicInteger) {
        prefs.edit().putInt(key, counter.incrementAndGet()).apply()
    }

    fun startedAt(): Long = prefs.getLong(KEY_STARTED, 0L)

    fun reset() {
        wakes.set(0); confirmed.set(0); turns.set(0); errors.set(0)
        prefs.edit()
            .putInt(KEY_WAKES, 0).putInt(KEY_CONFIRMED, 0)
            .putInt(KEY_TURNS, 0).putInt(KEY_ERRORS, 0)
            .putLong(KEY_STARTED, System.currentTimeMillis())
            .apply()
    }

    /**
     * One block of plain text, for `adb shell dumpsys` to carry out.
     *
     * Deliberately not JSON: the thing reading it is a person on a Windows
     * machine at the end of an eight-hour run.
     */
    fun report(): String {
        val started = startedAt()
        val elapsedMs = if (started > 0) System.currentTimeMillis() - started else 0L
        val hours = elapsedMs / 3_600_000.0
        val unconfirmed = (wakes.get() - confirmed.get()).coerceAtLeast(0)
        val perEightHours = if (hours > 0) unconfirmed / hours * 8.0 else 0.0

        val since = if (started > 0)
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(started)) else "never reset"

        return buildString {
            appendLine("wake word test")
            appendLine("  since              : $since")
            appendLine("  elapsed hours      : %.2f".format(hours))
            appendLine("  detections         : ${wakes.get()}")
            appendLine("  confirmed by human : ${confirmed.get()}")
            appendLine("  unconfirmed        : $unconfirmed")
            appendLine("  unconfirmed per 8h : %.2f   (target: at most 1)".format(perEightHours))
            appendLine("  completed turns    : ${turns.get()}")
            appendLine("  errors             : ${errors.get()}")
            appendLine()
            appendLine("  Hit rate is measured by hand: say the wake word 20 times at 3 m,")
            appendLine("  count how many raised 'detections'. Target is at least 18 of 20.")
        }
    }

    private companion object {
        const val KEY_WAKES = "wakes"
        const val KEY_CONFIRMED = "confirmed"
        const val KEY_TURNS = "turns"
        const val KEY_ERRORS = "errors"
        const val KEY_STARTED = "started_at"
    }
}
