package com.mammonrn.phoneaikiosk.recorder

import com.mammonrn.phoneaikiosk.media.MediaKinds
import com.mammonrn.phoneaikiosk.voice.WakePause
import java.util.Locale
import kotlin.math.log10

/**
 * The voice recorder's rules with no Android in them (RecorderRulesTest):
 * the default name, what a new name may be, the list's order, how a length and
 * a level are shown, and — the part Poom locked — when Jarvis rests.
 */
object RecorderNames {

    /** Under the phone's storage: the file manager shows it, the music player opens it. */
    const val FOLDER = "Recordings/Kiosk"

    /** Poom's playlist rule (DESIGN 5ฌ) for a name a person types: 40 characters at most. */
    const val MAX_CHARS = 40

    /** Not in a name: a path separator, or what shared storage and a PC copy refuse. */
    const val FORBIDDEN = "/\\:*?\"<>|"

    enum class Problem { EMPTY, BAD_CHARACTER, DOTS, TOO_LONG, TAKEN }

    /** "บันทึก 2026-09-25 14.03": sorts by date as text, and says what it is. */
    fun defaultBase(year: Int, month: Int, day: Int, hour: Int, minute: Int): String =
        String.format(Locale.US, "บันทึก %04d-%02d-%02d %02d.%02d", year, month, day, hour, minute)

    /** The name a person sees: without the extension. */
    fun base(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0) fileName.substring(0, dot) else fileName
    }

    /** A recording in the list: any sound file in the recordings folder (MediaKinds). */
    fun isRecording(fileName: String): Boolean = MediaKinds.isAudio(fileName)

    /**
     * What is wrong with [typed] as a new name, or null. [others] are the file
     * names in the folder; [self] is the file being renamed (its own name, in
     * any case, is not a clash). A clash is by the name a person sees — "x.ogg"
     * and "x.m4a" would be two rows called "x" — and ignores case, as shared
     * storage does.
     */
    fun problem(typed: String, others: Collection<String>, self: String? = null): Problem? {
        val name = typed.trim()
        if (name.isEmpty()) return Problem.EMPTY
        if (name.any { it in FORBIDDEN || it < ' ' }) return Problem.BAD_CHARACTER
        if (name.all { it == '.' } || name.startsWith(".")) return Problem.DOTS
        if (name.codePointCount(0, name.length) > MAX_CHARS) return Problem.TOO_LONG
        val taken = others.any {
            !it.equals(self, ignoreCase = false) && isRecording(it) && base(it).equals(name, ignoreCase = true)
        }
        return if (taken) Problem.TAKEN else null
    }

    /** [base], or "base (2)", "base (3)"… — never over a recording already there. */
    fun freeBase(base: String, others: Collection<String>): String {
        val seen = others.filter(::isRecording).map { base(it).lowercase(Locale.ROOT) }.toSet()
        if (base.lowercase(Locale.ROOT) !in seen) return base
        var n = 2
        while ("$base ($n)".lowercase(Locale.ROOT) in seen) n += 1
        return "$base ($n)"
    }

    /** One row of the list. */
    data class Item(val name: String, val modifiedMs: Long, val bytes: Long)

    /** Newest first; two from the same moment by name, so the order never jumps. */
    fun newestFirst(items: List<Item>): List<Item> =
        items.sortedWith(compareByDescending<Item> { it.modifiedMs }.thenBy { it.name.lowercase(Locale.ROOT) })

    /** "0:07", "12:03", "1:02:03": elapsed and a recording's length. */
    fun length(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    /**
     * How full the level meter is, 0..1, from a frame's peak (16-bit): on a
     * decibel scale from -50 dBFS (a quiet room) to full scale, because a
     * straight line would show a voice across the room as nearly nothing.
     */
    fun level(peak: Int): Float {
        if (peak <= 0) return 0f
        val db = 20 * log10(peak.coerceAtMost(32767) / 32767.0)
        return ((db + 50) / 50).coerceIn(0.0, 1.0).toFloat()
    }
}

/**
 * RECORDING, PAUSED, STOPPED — AND WHEN THE WAKE WORD RESTS (Poom, locked:
 * "ระหว่างอัดเสียง คำปลุกพัก แล้วปล่อยทันทีเมื่อหยุดอัด").
 *
 * The hold on [WakePause] is taken while RECORDING and nowhere else:
 *  * start / resume take it (the wake word rests from the next frame);
 *  * pause lets it go at once, as the music player does ("release on pause,
 *    stop, end, or close", WakePause). A paused recorder hears nothing, so
 *    there is nothing for the wake word to spoil; and Hey Jarvis said while
 *    paused closes the screen, which stops and SAVES the recording — nothing
 *    is lost (DESIGN 5ฐ);
 *  * stop lets it go at once;
 *  * a lease that lapsed while still recording (the main thread held up past
 *    90 seconds) is taken again on the next renew, not left off mid-recording.
 *
 * Pure: the phone and the JVM test run the same object; WakePause is Android-free.
 */
class RecorderFlow(private val media: WakePause.Media?) {

    enum class State { IDLE, RECORDING, PAUSED }

    var state = State.IDLE
        private set

    private var hold: WakePause.Hold? = null

    /** Jarvis is resting because of this recorder. */
    val holding: Boolean get() = hold != null

    /** Frames should reach the recorder (MicTap open). */
    val tapping: Boolean get() = state == State.RECORDING

    fun start(): Boolean = move(State.IDLE, State.RECORDING)

    fun pause(): Boolean = move(State.RECORDING, State.PAUSED)

    fun resume(): Boolean = move(State.PAUSED, State.RECORDING)

    /** From recording or paused. False when there was nothing to stop. */
    fun stop(): Boolean {
        if (state == State.IDLE) return false
        state = State.IDLE
        sync()
        return true
    }

    /** Every WakePause.RENEW_MS while the screen or the process lives. */
    fun renew() {
        val h = hold ?: return sync()
        if (!WakePause.renew(h)) {
            hold = null
            sync()
        }
    }

    private fun move(from: State, to: State): Boolean {
        if (state != from) return false
        state = to
        sync()
        return true
    }

    private fun sync() {
        val want = state == State.RECORDING
        if (want && hold == null) {
            val h = WakePause.hold(WakePause.Source.RECORDER, media)
            // A question in progress quiets a new hold at once, and quieting
            // the recorder stops it — possibly before hold() returned. Never
            // keep a hold for a recorder that is no longer recording.
            if (state == State.RECORDING && hold == null) hold = h else WakePause.release(h)
        } else if (!want) {
            hold?.let { WakePause.release(it) }
            hold = null
        }
    }
}
