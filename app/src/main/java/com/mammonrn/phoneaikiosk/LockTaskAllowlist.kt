package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.voice.CameraAppLauncher
import com.mammonrn.phoneaikiosk.voice.MapsLauncher

/**
 * Everything that may be on screen while the kiosk is locked: this app,
 * Google Maps, Xiaomi Home. Nothing else can appear at all while the task is
 * locked, so this list is the boundary of the kiosk.
 *
 * Its own object, with no Android in it, so a plain JVM test can hold it to
 * exactly these three — adding a fourth is a decision, and that test is where
 * it gets noticed.
 */
object LockTaskAllowlist {
    fun packages(self: String): Array<String> =
        arrayOf(self, MapsLauncher.MAPS_PACKAGE, CameraAppLauncher.PACKAGE)
}
