package com.mammonrn.phoneaikiosk

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log

/**
 * "Hey Jarvis" always ends on the home screen (0.43.0, Poom).
 *
 * Seen on the A07: the identity check was left open, Hey Jarvis answered, and
 * the screen stayed on the identity check — the service only brought the kiosk
 * back over Maps and the camera app, never over our own screens.
 *
 * EVERY SCREEN, INCLUDING ONES NOT WRITTEN YET. This is fed by the Application's
 * activity lifecycle callbacks ([KioskApp]), so any activity of this app is
 * tracked the moment it is created, with nothing to remember when adding one.
 * When a capture starts (wake word, the Jarvis button, adb) and the home screen
 * is not the one in front, [leaveAllButHome] closes every other screen of ours
 * and VoiceService brings MainActivity forward.
 *
 * A screen that must end in a particular way implements [Leavable]: the
 * identity check cancels — never a pass — and its camera is closed before it
 * goes. Everything else is simply finished.
 */
object KioskScreens {

    /** A screen with its own safe way to close when Jarvis takes over. */
    interface Leavable {
        fun leaveForJarvis()
    }

    private const val TAG = "KioskScreens"

    /** Open screens of ours other than home, oldest first. Main thread only. */
    private val open = LinkedHashMap<Int, Entry>()

    private class Entry(val name: String, val leave: () -> Unit)

    @Volatile
    var homeInFront: Boolean = false

    /** The screen in front now (debug layout report, tools/layout/check_layout.py). */
    @Volatile var resumed: java.lang.ref.WeakReference<Activity>? = null
        private set

    /** The screens other than home that are open now, by class name (dumpsys, tests). */
    fun openScreens(): List<String> = open.values.map { it.name }

    /**
     * Closes every open screen but home, each its own way. Returns how many.
     * Main thread only.
     */
    fun leaveAllButHome(reason: String): Int {
        val leaving = open.values.toList()
        for (entry in leaving.asReversed()) {
            runCatching { entry.leave() }
                .onFailure { e -> runCatching { Log.w(TAG, "could not close ${entry.name}: ${e.javaClass.simpleName}") } }
        }
        if (leaving.isNotEmpty()) {
            // Wrapped: the JVM test's android.util.Log is a stub that throws.
            runCatching { Log.i(TAG, "closed ${leaving.size} screen(s) for $reason: " + leaving.joinToString(",") { it.name }) }
        }
        return leaving.size
    }

    // --- the registry, kept free of Activity so the JVM test can drive it ------

    internal fun opened(key: Int, name: String, leave: () -> Unit) {
        open[key] = Entry(name, leave)
    }

    internal fun closed(key: Int) {
        open.remove(key)
    }

    internal fun homeResumed(yes: Boolean) {
        homeInFront = yes
    }

    internal fun reset() {
        open.clear()
        homeInFront = false
    }

    /** What decides, given an activity: home is never closed; a Leavable leaves its own way. */
    internal fun leaveFor(activity: Any, finish: () -> Unit): () -> Unit =
        if (activity is Leavable) ({ activity.leaveForJarvis() }) else finish

    /** Registered once, by [KioskApp]. */
    val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (activity is MainActivity) return
            opened(System.identityHashCode(activity), activity.javaClass.simpleName,
                   leaveFor(activity) { activity.finish() })
        }

        override fun onActivityResumed(activity: Activity) {
            resumed = java.lang.ref.WeakReference(activity)
            if (activity is MainActivity) homeResumed(true)
            // 0.65.0: a kiosk screen in front ends a Facebook or Instagram visit.
            com.mammonrn.phoneaikiosk.social.SocialVisit.kioskResumed(activity)
        }

        override fun onActivityPaused(activity: Activity) {
            if (activity is MainActivity) homeResumed(false)
        }

        override fun onActivityDestroyed(activity: Activity) {
            if (activity !is MainActivity) closed(System.identityHashCode(activity))
        }

        // 0.61.0: Jarvis's state on every screen (ui/JarvisBadge), added once the screen's own views are in.
        override fun onActivityStarted(activity: Activity) = com.mammonrn.phoneaikiosk.ui.JarvisBadges.attach(activity)
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }
}

/** The process's Application: exists to register [KioskScreens.callbacks] before any screen. */
class KioskApp : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(KioskScreens.callbacks)
    }
}
