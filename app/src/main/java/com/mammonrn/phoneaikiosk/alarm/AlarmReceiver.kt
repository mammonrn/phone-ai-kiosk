package com.mammonrn.phoneaikiosk.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mammonrn.phoneaikiosk.voice.VoiceService

/**
 * AlarmManager's call. Hands the ringing to the voice service, which already
 * runs in the foreground, owns the speaker and the screen-waking, and is the
 * one place that knows whether a question is under way — then books the next.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(AlarmScheduler.EXTRA_ALARM_ID, -1)
        VoiceService.ringAlarm(context, id)
        AlarmScheduler.schedule(context)
    }
}
