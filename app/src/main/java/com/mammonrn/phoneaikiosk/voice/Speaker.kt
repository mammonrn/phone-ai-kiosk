package com.mammonrn.phoneaikiosk.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch

/**
 * Saying the answer out loud, with a way to still say it when the good voice is
 * unavailable.
 *
 * Cloud audio first, because that is the voice that was chosen. The phone's own
 * engine second, because a kiosk that goes silent when the network drops is a
 * kiosk that looks broken — and the fallback is free, offline and instant.
 */
class Speaker(private val context: Context) {

    @Volatile
    private var engine: TextToSpeech? = null

    @Volatile
    var lastUsed: String = "none"
        private set

    /** Starts the on-device engine warming up; it takes a moment to initialise. */
    fun warmUp() {
        if (engine != null) return
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.language = Locale("th", "TH")
            }
        }
    }

    /** Plays audio from the broker. Returns false if it could not be played. */
    fun play(audio: ByteArray, suffix: String): Boolean {
        // MediaPlayer wants a file or a descriptor, so this is the one place a
        // temporary file exists. Written into the app's cache, played, and
        // deleted in the same call.
        val file = File(context.cacheDir, "reply.$suffix")
        return try {
            file.writeBytes(audio)
            val done = CountDownLatch(1)
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener { done.countDown() }
                setOnErrorListener { _, _, _ -> done.countDown(); true }
                prepare()
                start()
            }
            done.await()
            player.release()
            lastUsed = "cloud"
            true
        } catch (e: Exception) {
            false
        } finally {
            file.delete()
        }
    }

    /** The on-device engine. Worse voice, but it is there when nothing else is. */
    fun sayLocally(text: String): Boolean {
        val tts = engine ?: return false
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kiosk")
        if (result == TextToSpeech.SUCCESS) {
            lastUsed = "device"
            return true
        }
        return false
    }

    fun shutdown() {
        engine?.shutdown()
        engine = null
    }
}
