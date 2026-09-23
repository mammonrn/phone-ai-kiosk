package com.mammonrn.phoneaikiosk.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Does this phone have an on-device recognizer, and does it have Thai?
 *
 * The third transcriber Poom asked to compare is Android's own. Before it could
 * be used there are two facts to establish, and this establishes them WITHOUT
 * RECOGNISING ANYTHING: no startListening, no audio, no microphone. It asks,
 * writes the answer to VoiceState.deviceStt for dumpsys, and destroys the
 * recognizer it created to ask.
 *
 * WHY IT STOPS AT ASKING. The only way to use it without a second microphone is
 * RecognizerIntent.EXTRA_AUDIO_SOURCE (API 33), which hands the recognizer the
 * audio this app already captured. Its documentation says: "If this extra is not
 * set or the recognizer does not support this feature, the recognizer will open
 * the mic" — and nothing on the page says which recognizers support it. The
 * kiosk holds the microphone for the wake word the whole time, so a recognizer
 * that quietly ignored the extra would open a SECOND AudioRecord beside it,
 * which is the one thing Poom ruled out. That is reported in TESTING.md with
 * the evidence and a design that cannot overlap; it is not switched on here.
 *
 * Main thread only: SpeechRecognizer's documentation says its methods "must be
 * invoked only from the main application thread".
 */
object DeviceSttProbe {

    const val LANGUAGE = "th-TH"

    fun probe(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            VoiceState.deviceStt = "sdk ${Build.VERSION.SDK_INT} < 33: cannot check languages"
            return
        }
        val available = runCatching {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        }.getOrDefault(false)
        if (!available) {
            VoiceState.deviceStt = "on-device recognizer: not available"
            return
        }
        val recognizer = runCatching {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        }.getOrElse {
            VoiceState.deviceStt = "on-device recognizer: create failed ${it.javaClass.simpleName}"
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
        VoiceState.deviceStt = "on-device recognizer: asking about $LANGUAGE…"
        try {
            recognizer.checkRecognitionSupport(intent, context.mainExecutor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        VoiceState.deviceStt = describe(
                            installed = support.installedOnDeviceLanguages,
                            supported = support.supportedOnDeviceLanguages,
                            pending = support.pendingOnDeviceLanguages,
                        )
                        recognizer.destroy()
                    }

                    override fun onError(error: Int) {
                        VoiceState.deviceStt = "on-device recognizer: support check error $error"
                        recognizer.destroy()
                    }
                })
        } catch (e: Exception) {
            VoiceState.deviceStt = "on-device recognizer: support check threw ${e.javaClass.simpleName}"
            recognizer.destroy()
        }
    }

    /** One line for dumpsys. Pure, for the test. */
    fun describe(installed: List<String>, supported: List<String>, pending: List<String>): String {
        fun has(list: List<String>) = list.any { it.equals(LANGUAGE, true) || it.equals("th", true) }
        val thai = when {
            has(installed) -> "INSTALLED"
            has(pending) -> "downloading"
            has(supported) -> "supported, not downloaded"
            else -> "not offered"
        }
        return "on-device recognizer: available; $LANGUAGE $thai " +
            "(installed ${installed.size}, supported ${supported.size}); NOT used for turns"
    }
}
