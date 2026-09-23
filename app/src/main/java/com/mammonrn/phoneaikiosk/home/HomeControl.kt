package com.mammonrn.phoneaikiosk.home

import android.app.Activity

/**
 * The Google Home menu at the top of the kiosk screen — what it does when
 * tapped, and whether it can do anything yet.
 *
 * NOT CONNECTED IN THIS PHASE, ON PURPOSE. Poom asked for the space and the
 * button now and the integration in the last phase. So the window is real, the
 * tap is real, and the only implementation is [NotConnected], which does
 * nothing and says so. There is no Google account, no network call and no
 * package lookup anywhere behind it.
 *
 * THE SEAM FOR LATER. MainActivity holds a HomeControl and never asks which one:
 * when there is a real one — opening the Google Home app inside lock task (it
 * would need adding to setLockTaskPackages beside Maps, a decision with its own
 * weight), or talking to the broker — it replaces [NotConnected] in one line
 * and the screen does not change.
 */
interface HomeControl {

    /** False until something can actually be controlled. */
    val available: Boolean

    /**
     * Does whatever the menu does. Only called when [available] is true;
     * returns a short Thai line for the screen if it could not.
     */
    fun open(activity: Activity): String?

    /** The only implementation this phase ships. */
    object NotConnected : HomeControl {
        override val available = false
        override fun open(activity: Activity): String? = null
    }
}
