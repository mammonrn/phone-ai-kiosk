package com.mammonrn.phoneaikiosk.voice

/**
 * THE VOICE RECORDER READS THE WAKE WORD'S OWN MICROPHONE (0.61.0).
 *
 * VoiceService holds one AudioRecord for as long as the kiosk is up, and the
 * capture thread is its only owner (Recorder: "two AudioRecords at once is the
 * bug that shipped in versionCode 4"). A voice recorder that opened a second
 * capture — MediaRecorder, or another AudioRecord — would be betting on
 * Android's concurrent-capture rules (API 29+): which of two captures gets the
 * sound and which gets SILENCE depends on the source (VOICE_RECOGNITION vs
 * MIC), which one started last, which one is privacy-sensitive, and on the
 * vendor's audio HAL. Within one app (one UID) AOSP generally lets both run,
 * but "generally" is a recorder that saves a silent file on some phone.
 *
 * So nothing opens a second capture. While the recorder is recording, the
 * capture thread hands each frame it already read (16 kHz mono 16-bit, the
 * same frames the wake word hears) to the recorder's [Sink] as well. The
 * recorder never touches the microphone, VoiceService never closes it, and
 * the Jarvis button keeps working during a recording because the capture loop
 * never stopped reading (DESIGN.md 12: the button always works).
 *
 * WHAT IS NOT PASSED ON: frames while a Jarvis turn is in progress ([offer]'s
 * `turnBusy`: the question, the thinking and the answer). A question asked with
 * the button must not end up in the recording; the recorder is stopped by the
 * turn anyway (WakePause.Media.quietForJarvis, then the screen closes, DESIGN 11).
 *
 * NO ANDROID IN HERE (MicTapTest). The capture thread calls [offer] sixteen
 * times a second: with no sink it is one volatile read and a return.
 */
object MicTap {

    /** Where the frames go. Called on the capture thread: copy what you keep and return at once. */
    fun interface Sink {
        fun frame(samples: ShortArray, count: Int, peak: Int)
    }

    @Volatile
    private var sink: Sink? = null

    /** Frames handed on since the last [open], and frames held back during a Jarvis turn. */
    @Volatile var passed = 0L
        private set
    @Volatile var heldBack = 0L
        private set

    /** Log lines: counts and states only. */
    @Volatile
    var log: (String) -> Unit = {}

    /** The recorder starts reading. A second open replaces the first (there is one recorder). */
    fun open(to: Sink) {
        passed = 0
        heldBack = 0
        sink = to
        log("mic handed to recorder (shared stream, no second capture)")
    }

    /** The recorder stops reading. Only the sink that is open can close it. */
    fun close(from: Sink) {
        if (sink !== from) return
        sink = null
        log("mic back frames=$passed held_back=$heldBack")
    }

    val isOpen: Boolean get() = sink != null

    /**
     * The capture thread's call, every frame. Returns true if the frame went to
     * the recorder. [turnBusy] is "a Jarvis turn is in progress".
     */
    fun offer(frame: ShortArray, read: Int, peak: Int, turnBusy: Boolean): Boolean {
        val to = sink ?: return false
        if (read <= 0) return false
        if (turnBusy) {
            heldBack += 1
            return false
        }
        passed += 1
        to.frame(frame, read, peak)
        return true
    }
}
