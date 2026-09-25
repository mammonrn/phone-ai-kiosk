package com.mammonrn.phoneaikiosk.calc

import android.app.Activity
import android.view.View
import android.widget.LinearLayout

/**
 * A screen that shows [SlideDeck]'s pages (0.63.0): the calculator, and the
 * "เวลา" app (timer/TimerActivity: นาฬิกาปลุก · จับเวลา · นับถอยหลัง) — ONE swipe
 * component for both (Poom: "ใช้ SlideDeck เดียวกับเครื่องคิดเลข").
 */
interface SlideHost {
    val activity: Activity
    /** ■ □ □ in the window's title bar. */
    val pageDots: LinearLayout
    /** Puts the deck's frame into the window. */
    fun setPage(view: View)
    fun dp(value: Int): Int
    fun color(id: Int): Int
}
