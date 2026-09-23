package com.mammonrn.phoneaikiosk.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.util.Log

/**
 * The sound. The phone's own alarm tone, played as an ALARM — USAGE_ALARM is
 * the stream that rings through the ring/silent switch and at the alarm
 * volume, which is what an alarm is for — looping until stopped, and for no
 * more than [MAX_RING_MS] so a kiosk in an empty house does not ring all day.
 */
class AlarmRinger(private val context: Context) {

    private var player: MediaPlayer? = null

    val ringing: Boolean get() = player != null

    fun start(): Boolean {
        if (player != null) return true
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return false
        return try {
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                setDataSource(context, uri)
                isLooping = true
                prepare()
                start()
            }
            true
        } catch (e: Exception) {
            Log.w("KioskAlarm", "ring failed: ${e.javaClass.simpleName}")
            player?.release()
            player = null
            false
        }
    }

    fun stop() {
        player?.runCatching { stop(); release() }
        player = null
    }

    companion object {
        const val MAX_RING_MS = 10 * 60_000L
    }
}
