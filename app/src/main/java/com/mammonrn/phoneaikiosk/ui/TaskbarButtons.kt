package com.mammonrn.phoneaikiosk.ui

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.mammonrn.phoneaikiosk.R

/**
 * The home screen's two taskbar buttons, redrawn (0.61.0, Poom): the Jarvis
 * button and the Control Panel button, bottom LEFT — the exit corner bottom
 * right is not touched, and nothing here makes the taskbar taller, so the
 * Jarvis window above keeps its 156dp.
 *
 *  A (the default, recommended): a 1995 Start button — the Jarvis icon and
 *    "ถามจาร์วิส" in bold, so what it does is written on it; the panel button
 *    its icon with "แผงควบคุม" under it.
 *  B: icons alone, bigger — the smallest taskbar, but the words are only read out.
 *  C: one wide "● พูดกับจาร์วิส" button, the panel as its icon alone.
 *
 * B and C exist for Poom to choose from (debug TEST_TASKBAR --ei style 1|2).
 */
object TaskbarButtons {
    const val A = 0
    const val B = 1
    const val C = 2

    @Volatile var style = A

    /** "แผงควบคุม" at 9sp needs this much to stay on one line (measured on the A07: at wrap it was cut to "แผง"). */
    private const val PANEL_A_WIDTH = 56

    fun apply(activity: Activity, which: Int = style) {
        style = which
        val jarvis = activity.findViewById<TextView>(R.id.jarvis_button) ?: return
        val panel = activity.findViewById<FrameLayout>(R.id.settings_button) ?: return
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val thai = ResourcesCompat.getFont(activity, R.font.plex_thai) ?: Typeface.DEFAULT
        fun icon(id: Int, size: Int): Drawable = ContextCompat.getDrawable(activity, id)!!.mutate().apply { setBounds(0, 0, dp(size), dp(size)) }

        // At least a finger's height (48dp, 0.63.0 layout check: they were 35dp).
        // The Jarvis window above stays well over its 156dp.
        jarvis.minHeight = dp(UiScale.TOUCH)
        panel.minimumHeight = dp(UiScale.TOUCH)
        jarvis.typeface = Typeface.create(thai, Typeface.BOLD)
        jarvis.compoundDrawablePadding = dp(6)
        jarvis.gravity = Gravity.CENTER
        when (which) {
            B -> {
                jarvis.text = ""
                jarvis.setCompoundDrawables(icon(R.drawable.ic_pixel_jarvis, 26), null, null, null)
                jarvis.setPadding(dp(11), dp(5), dp(5), dp(5))
            }
            C -> {
                jarvis.text = activity.getString(R.string.taskbar_jarvis_c)
                jarvis.textSize = UiScale.TEXT_BASE
                jarvis.setCompoundDrawables(null, null, null, null)
                jarvis.setPadding(dp(12), dp(6), dp(12), dp(6))
            }
            else -> {
                jarvis.text = activity.getString(R.string.taskbar_jarvis_a)
                jarvis.textSize = 13f
                jarvis.setCompoundDrawables(icon(R.drawable.ic_pixel_jarvis, 18), null, null, null)
                jarvis.setPadding(dp(8), dp(6), dp(10), dp(6))
            }
        }
        jarvis.contentDescription = activity.getString(R.string.jarvis_button_description)

        panel.removeAllViews()
        val lp = panel.layoutParams as LinearLayout.LayoutParams
        if (which == A) {
            val column = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            column.addView(ImageView(activity).apply { setImageResource(R.drawable.ic_pixel_control_panel) },
                LinearLayout.LayoutParams(dp(18), dp(18)))
            column.addView(TextView(activity).apply {
                text = activity.getString(R.string.taskbar_panel_a)
                textSize = 9f
                typeface = Typeface.create(thai, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(activity, R.color.retro_text))
                isSingleLine = true
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(PANEL_A_WIDTH), LinearLayout.LayoutParams.WRAP_CONTENT))
            panel.addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            lp.width = dp(PANEL_A_WIDTH + 8)
            panel.setPadding(dp(4), 0, dp(4), 0)
        } else {
            panel.addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_pixel_control_panel)
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(dp(if (which == B) 28 else 24), dp(if (which == B) 28 else 24), Gravity.CENTER))
            lp.width = dp(48)
            panel.setPadding(0, 0, 0, 0)
        }
        panel.layoutParams = lp
    }
}
