package com.mammonrn.phoneaikiosk.media.fx

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The equalizer, the balance and the tap for the bars, inside the player's
 * audio path (0.55.0). DefaultAudioSink hands its processors 16-bit PCM (it
 * converts 24-bit and float first), so that is the one format taken; any
 * other passes by untouched and says so ([working]).
 *
 * Settings come from the main thread ([settings], [balance]) and are picked
 * up at the next buffer. The tap keeps a mono copy of what goes out.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class AudioFx : BaseAudioProcessor() {

    @Volatile var settings: Eq.Settings = Eq.Settings()
    @Volatile var balance: Float = 0f
    /** The samples on their way out, for the bars. */
    val tap = Tap()

    /** The processor is in the path and seeing samples (false for a format it cannot take). */
    @Volatile var working = false
        private set

    private var chain: Eq.Chain? = null
    private var applied: Eq.Settings? = null
    private var appliedBalance = Float.NaN

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount !in 1..2) {
            working = false
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        chain = Eq.Chain(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        applied = null
        appliedBalance = Float.NaN
        working = true
        return inputAudioFormat   // same format out; always active, so the bars always have samples
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        chain?.reset()
        tap.restart(streamMetadata.positionOffsetUs, inputAudioFormat.sampleRate.coerceAtLeast(1))
    }

    override fun onReset() {
        chain = null
        working = false
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val chain = chain ?: return
        val s = settings
        if (s != applied) { chain.set(s); applied = s }
        val b = balance
        if (b != appliedBalance) { chain.setBalance(b); appliedBalance = b }

        val channels = inputAudioFormat.channelCount
        val bytes = inputBuffer.remaining()
        val out = replaceOutputBuffer(bytes)
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val frames = bytes / (2 * channels)
        for (f in 0 until frames) {
            var mono = 0.0
            for (ch in 0 until channels) {
                val x = input.getShort() / 32768.0
                val y = chain.process(x, ch).coerceIn(-1.0, 32767.0 / 32768.0)
                out.putShort((y * 32768.0).toInt().toShort())
                mono += y
            }
            tap.write((mono / channels).toFloat())
        }
        out.flip()
    }
}
