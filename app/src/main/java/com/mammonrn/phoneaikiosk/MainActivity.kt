package com.mammonrn.phoneaikiosk

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/**
 * Phase 0: proves the project builds, installs and launches.
 *
 * Deliberately a plain [Activity] with a single [TextView] — no Compose, no
 * AppCompat, no Device Owner, no kiosk lock. Those arrive in later phases.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = TextView(this).apply {
            text = getString(R.string.phase_0_ok)
            textSize = 20f
            gravity = Gravity.CENTER
        }

        setContentView(text)
    }
}
