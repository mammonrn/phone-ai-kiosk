package com.mammonrn.phoneaikiosk.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mammonrn.phoneaikiosk.MainActivity
import java.util.TimeZone

/**
 * Hands the next alarm to Android's AlarmManager.setAlarmClock.
 *
 * WHY setAlarmClock AND NOT OUR OWN TIMER. The kiosk's screen goes off after
 * five minutes and the phone then dozes; a timer inside the app does not run
 * in Doze. setAlarmClock is the one call Android promises to fire on time even
 * in Doze, because it is what clock apps use — and a Device Owner kiosk is a
 * clock app for this purpose. It needs USE_EXACT_ALARM (granted at install on
 * Android 13+, declared in the manifest) and is allowed to start the voice
 * service from the background when it fires.
 *
 * WHY NOT THE PHONE'S OWN CLOCK APP. Its alarm would ring in Samsung's alarm
 * screen, which lock task mode does not allow on top of the kiosk, and the
 * stop button would be in an app the kiosk cannot show. Ringing in our own
 * service keeps the whole thing inside the kiosk.
 *
 * Only the NEXT alarm is ever registered. When it rings, the receiver asks for
 * the next one again, and every change to the book does the same. Android
 * forgets alarms on reboot; the kiosk is HOME, so MainActivity starts at boot
 * and after every update, and it calls schedule() in onCreate.
 */
object AlarmScheduler {

    private const val TAG = "KioskAlarm"
    const val EXTRA_ALARM_ID = "alarm_id"

    fun schedule(context: Context, book: AlarmBook = AlarmStore.load(context)) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val ring = ringIntent(context, null)
        val next = book.next(System.currentTimeMillis(), TimeZone.getDefault())
        if (next == null) {
            manager.cancel(ring)
            Log.i(TAG, "no alarm on")
            return
        }
        val (alarm, at) = next
        val show = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), ringIntent(context, alarm.id))
            // The time and the id; never the name the household gave it.
            Log.i(TAG, "next alarm id=${alarm.id} at=${alarm.time}")
        } catch (e: SecurityException) {
            Log.w(TAG, "exact alarms refused: ${e.javaClass.simpleName}")
        }
    }

    private fun ringIntent(context: Context, id: Int?): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
        if (id != null) intent.putExtra(EXTRA_ALARM_ID, id)
        return PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
