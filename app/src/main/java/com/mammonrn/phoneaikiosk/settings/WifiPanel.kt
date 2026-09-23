package com.mammonrn.phoneaikiosk.settings

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.mammonrn.phoneaikiosk.KioskDeviceAdminReceiver
import com.mammonrn.phoneaikiosk.LockTaskAllowlist

/**
 * The Control Panel's WiFi button (0.42.0, Poom's choice of the options).
 *
 * In lock task mode only allowlisted packages can come on screen, and the
 * system settings app is not one of them — it is the whole phone's settings.
 * So the button allows it FOR ONE VISIT: the settings package joins the list,
 * the system's WiFi panel opens over the kiosk, and the moment a kiosk screen
 * is back in front ([restore], from SettingsActivity.onResume and
 * MainActivity.onResume) the list is exactly [LockTaskAllowlist] again.
 *
 * THE RISK POOM ACCEPTED: while the panel is open, its own "settings" link can
 * reach the rest of the settings app. That is still inside the locked task —
 * Home and Recents stay off — and Back returns to the kiosk, which closes it.
 */
object WifiPanel {

    const val SETTINGS_PACKAGE = "com.android.settings"
    private const val TAG = "KioskWifi"

    /** Opens the WiFi panel. False if there is no way to (not device owner, no panel). */
    fun open(activity: Activity): Boolean {
        val dpm = activity.getSystemService(DevicePolicyManager::class.java)
        if (dpm == null || !dpm.isDeviceOwnerApp(activity.packageName)) return false
        val admin = KioskDeviceAdminReceiver.componentName(activity)
        dpm.setLockTaskPackages(admin, LockTaskAllowlist.packages(activity.packageName) + SETTINGS_PACKAGE)
        for (action in listOf(Settings.Panel.ACTION_WIFI, Settings.ACTION_WIFI_SETTINGS)) {
            try {
                activity.startActivity(Intent(action).setPackage(SETTINGS_PACKAGE))
                Log.i(TAG, "opened ${action.substringAfterLast('.')}")
                return true
            } catch (e: ActivityNotFoundException) {
                Log.i(TAG, "no ${action.substringAfterLast('.')}")
            } catch (e: SecurityException) {
                Log.i(TAG, "refused ${action.substringAfterLast('.')}")
            }
        }
        restore(activity)
        return false
    }

    /** The kiosk's own allowlist again. Cheap; called on every kiosk resume. */
    fun restore(context: Context) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        val admin = KioskDeviceAdminReceiver.componentName(context)
        val wanted = LockTaskAllowlist.packages(context.packageName)
        if (!dpm.getLockTaskPackages(admin).toSet().equals(wanted.toSet())) {
            dpm.setLockTaskPackages(admin, wanted)
            Log.i(TAG, "allowlist restored")
        }
    }
}
