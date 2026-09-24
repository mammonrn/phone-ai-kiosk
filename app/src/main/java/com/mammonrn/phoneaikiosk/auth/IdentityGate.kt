package com.mammonrn.phoneaikiosk.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.mammonrn.phoneaikiosk.R

/**
 * NOTHING IS DELETED WITHOUT THE OWNER (0.60.0, Poom: "ลบต้องยืนยันตัวตน
 * (ใบหน้า/pattern) ก่อน ถ้าข้ามได้ถือเป็นบั๊กความปลอดภัย"). Every way to delete —
 * a file or folder in the file manager, a playlist, emptying a playlist — asks
 * for the face or the pattern after "are you sure", and only a pass deletes.
 *
 * The same check as deleting the face or pattern itself (SettingsActivity):
 * VerifyActivity in VERIFY mode. Nothing enrolled is not a pass: the answer
 * says to set the face or the pattern first.
 */
object IdentityGate {

    const val REQUEST = 6001

    @Suppress("DEPRECATION")
    fun ask(activity: Activity) =
        activity.startActivityForResult(VerifyActivity.intent(activity, VerifyActivity.Mode.VERIFY), REQUEST)

    fun passed(data: Intent?): Boolean =
        data?.getStringExtra(VerifyActivity.EXTRA_OUTCOME) == VerifyActivity.OUTCOME_PASSED

    /** Why nothing was deleted, in words for the screen. */
    fun refusal(context: Context, data: Intent?): String = context.getString(
        when (data?.getStringExtra(VerifyActivity.EXTRA_OUTCOME)) {
            VerifyActivity.OUTCOME_NOTHING_ENROLLED -> R.string.gate_nothing_enrolled
            VerifyActivity.OUTCOME_CANCELLED, null -> R.string.gate_cancelled
            else -> R.string.gate_failed
        })
}
