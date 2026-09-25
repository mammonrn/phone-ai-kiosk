package com.mammonrn.phoneaikiosk.snap

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

/**
 * Pictures of screens that need no real data (0.63.0, Poom), compared in CI
 * (verifyPaparazziDebug) with the ones in app/src/test/snapshots/images. Most of
 * the kiosk is drawn in code inside its Activities, which Paparazzi cannot run:
 * those are covered on the phone by scripts/ui-check.
 */
class ScreenSnapshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    @Test fun homeLayout() {
        val view = paparazzi.inflate<android.view.View>(com.mammonrn.phoneaikiosk.R.layout.activity_main)
        paparazzi.snapshot(view)
    }
}
