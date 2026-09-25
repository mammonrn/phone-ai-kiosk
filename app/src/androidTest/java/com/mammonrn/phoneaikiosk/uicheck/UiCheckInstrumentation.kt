package com.mammonrn.phoneaikiosk.uicheck

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry

/**
 * The runner for scripts/ui-check (0.63.0): runs [UiCheckTest.everyScreen] and
 * nothing else.
 *
 * NOT AndroidJUnitRunner, ON PURPOSE. That runner finishes every activity when
 * a test starts, to begin clean — and on the kiosk the activity it finished was
 * the locked home screen: lock task went off for the whole walk and the walk
 * saw a home screen "on its way out" (seen on the A07, 2026-09-25, in the
 * system log: the home task closed the moment the run started). This one
 * leaves the kiosk exactly as it is.
 */
class UiCheckInstrumentation : Instrumentation() {

    private var args: Bundle = Bundle()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        args = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        super.onStart()
        InstrumentationRegistry.registerInstance(this, args)
        val result = Bundle()
        try {
            UiCheckTest().everyScreen()
            result.putString(REPORT_KEY_STREAMRESULT, "\nUI check: walked\n")
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            result.putString(REPORT_KEY_STREAMRESULT, "\nUI check FAILED: ${t.javaClass.simpleName}: ${t.message}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
