package com.mammonrn.phoneaikiosk.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mammonrn.phoneaikiosk.KioskScreens
import com.mammonrn.phoneaikiosk.MainActivity
import com.mammonrn.phoneaikiosk.R

/**
 * The window every small Control Panel app shares (0.62.0: the timer and the
 * compass), the same parts as the recorder's and the camera's: the desktop,
 * a raised window, a navy title bar with the app's icon, its name and the X
 * (48dp, back where the app was opened from — [Origin]), the page in the
 * middle, and "กลับหน้าหลัก" (56dp) at the very bottom. Every size is a name
 * from [UiScale] (DESIGN.md 5ช).
 */
class ToolWindow(private val activity: Activity, private val r: Retro, icon: Int,
                 onClose: () -> Unit, onHome: () -> Unit) {

    val root: View
    val title: TextView
    val content: FrameLayout

    init {
        val outer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(r.color(R.color.retro_desktop))
            setPadding(r.dp(UiScale.FRAME), r.dp(UiScale.FRAME), r.dp(UiScale.FRAME), r.dp(UiScale.FRAME))
        }
        val window = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.retro_raised)
            setPadding(r.dp(UiScale.WINDOW_INSET), r.dp(UiScale.WINDOW_INSET), r.dp(UiScale.WINDOW_INSET), r.dp(UiScale.WINDOW_INSET))
        }
        outer.addView(window, LinearLayout.LayoutParams(Retro.MATCH, 0, 1f))
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.retro_titlebar)
            setPadding(r.dp(UiScale.SPACE_S), 0, 0, 0)
        }
        bar.addView(ImageView(activity).apply { setImageResource(icon) },
                    LinearLayout.LayoutParams(r.dp(UiScale.ICON_S), r.dp(UiScale.ICON_S)))
        title = TextView(activity).apply {
            setTextColor(r.color(R.color.retro_title_text))
            textSize = UiScale.TEXT_BASE
            typeface = Typeface.create(r.thai, Typeface.BOLD)
            setPadding(r.dp(UiScale.SPACE_S), 0, 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        bar.addView(title, LinearLayout.LayoutParams(0, Retro.WRAP, 1f))
        bar.addView(FrameLayout(activity).apply {
            setBackgroundResource(R.drawable.retro_button)
            contentDescription = Origin.closeWords(activity)
            isClickable = true
            setOnClickListener { onClose() }
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_pixel_close) },
                    FrameLayout.LayoutParams(r.dp(UiScale.ICON_M), r.dp(UiScale.ICON_M), Gravity.CENTER))
        }, LinearLayout.LayoutParams(r.dp(UiScale.TOUCH), r.dp(UiScale.TOUCH)))
        window.addView(bar, LinearLayout.LayoutParams(Retro.MATCH, Retro.WRAP))
        content = FrameLayout(activity)
        window.addView(content, LinearLayout.LayoutParams(Retro.MATCH, 0, 1f).apply { topMargin = r.dp(UiScale.WINDOW_INSET) })
        outer.addView(r.button(activity.getString(R.string.settings_home), big = true) { onHome() },
                      LinearLayout.LayoutParams(Retro.MATCH, r.dp(UiScale.PRIMARY)).apply { topMargin = r.dp(UiScale.FRAME) })
        root = outer
    }

    fun setPage(view: View) {
        content.removeAllViews()
        content.addView(view, FrameLayout.LayoutParams(Retro.MATCH, Retro.MATCH))
    }

    companion object {
        /** "กลับหน้าหลัก": every screen but home closes, and home comes up. */
        fun goHome(activity: Activity, reason: String) {
            KioskScreens.leaveAllButHome(reason)
            activity.startActivity(Intent(activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            activity.finish()
        }

        fun hideSystemBars(activity: Activity) {
            val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
