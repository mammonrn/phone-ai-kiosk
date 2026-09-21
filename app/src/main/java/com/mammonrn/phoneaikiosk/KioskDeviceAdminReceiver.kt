package com.mammonrn.phoneaikiosk

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context

/**
 * The device admin component this app is provisioned as.
 *
 * It carries no callbacks yet — it exists because `dpm set-device-owner` needs
 * a [DeviceAdminReceiver] to point at, and [DevicePolicyManager] calls need an
 * admin component to authenticate with.
 *
 * NOTE ON THE COMPONENT NAME: the class stays in `com.mammonrn.phoneaikiosk`
 * even in debug builds. `applicationIdSuffix = ".debug"` changes the *package
 * id* the system installs under, not the Java package the class is compiled
 * into, so the adb component for a debug build is
 *
 *     com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.KioskDeviceAdminReceiver
 *      ^ application id (with suffix)  ^ class name (no suffix)
 *
 * Getting that wrong is the usual reason `dpm set-device-owner` reports that
 * it cannot find the admin.
 */
class KioskDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        fun componentName(context: Context): ComponentName =
            ComponentName(context.applicationContext, KioskDeviceAdminReceiver::class.java)
    }
}
