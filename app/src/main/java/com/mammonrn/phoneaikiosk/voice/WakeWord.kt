package com.mammonrn.phoneaikiosk.voice

import android.content.Context
import java.io.File

/**
 * The wake word stage.
 *
 * Kept behind an interface with a deliberately inert default, because the
 * "สายฝน" model does not exist yet: training one needs a GPU or Colab, and the
 * phase 3 brief says to stop and ask before spending either. Everything
 * downstream — recording, transcribing, answering, speaking — is wired and
 * testable today through the adb trigger, and dropping a model file in is the
 * only change needed to make the phone listen for its name.
 *
 * Whatever implementation eventually lands, one property must hold: it runs on
 * the phone. No audio leaves the room before [Detector.accept] returns true.
 */
interface Detector {

    /** Whether this detector can actually detect anything. */
    val ready: Boolean

    /** A short word for the status line: "no-model", "listening", "loading". */
    val state: String

    /**
     * Feeds one frame of 16 kHz mono PCM and says whether the wake word ended
     * on it. Called from the capture thread, so it must not block.
     */
    fun accept(frame: ShortArray, length: Int): Boolean

    fun close() {}
}

/**
 * What runs until a model exists: it listens to nothing and fires never.
 *
 * It still reports the file it is waiting for, so the status line can say why
 * the phone is not responding to its name rather than looking broken.
 */
class NoModelDetector(context: Context) : Detector {

    private val expected = File(context.filesDir, MODEL_FILE)

    override val ready: Boolean get() = false

    override val state: String
        get() = if (expected.exists()) "model-unsupported" else "no-model"

    override fun accept(frame: ShortArray, length: Int): Boolean = false

    companion object {
        /**
         * Where a trained model will be looked for. Named here so the adb
         * instructions and the code cannot disagree about it.
         */
        const val MODEL_FILE = "wakeword.onnx"
    }
}
