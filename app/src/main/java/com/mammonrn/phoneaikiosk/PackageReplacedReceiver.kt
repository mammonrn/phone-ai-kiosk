package com.mammonrn.phoneaikiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Puts the kiosk back on screen after this app is updated.
 *
 * FOUND ON THE A07. Installing an update kills the app's process; that ends
 * lock task mode with it, and Android then shows whatever task was underneath
 * — twice now: the Play Store once, One UI Home once, both left there by
 * earlier ten-tap exits. Nothing launched HOME again, so the phone sat
 * unlocked until somebody pressed Home. The kiosk is still the persistent
 * HOME (pressing Home brought it straight back, locked), so all that was
 * missing was something to start it.
 *
 * ACTION_MY_PACKAGE_REPLACED is delivered to a manifest receiver of the
 * package that was replaced and to nobody else, so this cannot be triggered by
 * another app. MainActivity starting is what re-enters lock task mode, exactly
 * as it does at boot. A background activity start is allowed here because a
 * Device Owner is exempt from those restrictions; if the platform ever refused
 * it, the log says so and pressing Home still recovers it as before.
 */
class PackageReplacedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val start = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(start)
            Log.i(TAG, "updated: kiosk brought back")
        } catch (e: Exception) {
            Log.w(TAG, "updated: could not bring the kiosk back: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "KioskUpdate"
    }
}
