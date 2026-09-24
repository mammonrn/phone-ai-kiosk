package com.mammonrn.phoneaikiosk.ui

import android.app.Activity
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.mammonrn.phoneaikiosk.R

/**
 * The Control Panel's 1995 parts for the screens that are shared between
 * activities (0.59.0: the folder browser, the playlists page, the picker).
 * The same parts as each activity's own — raised buttons, option rows, sunken
 * fields — and every size from [UiScale] (UiScaleTest reads this file too).
 */
class Retro(val activity: Activity, val thai: Typeface) {

    fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), activity.resources.displayMetrics).toInt()

    fun color(id: Int): Int = ContextCompat.getColor(activity, id)

    fun text(value: CharSequence, sp: Float, dim: Boolean = false) = TextView(activity).apply {
        text = value
        textSize = sp
        typeface = thai
        setTextColor(color(if (dim) R.color.retro_dim else R.color.retro_text))
    }

    fun bold(value: CharSequence, sp: Float) = text(value, sp).apply { typeface = Typeface.create(thai, Typeface.BOLD) }

    fun label(value: String) = bold(value, UiScale.TEXT_NOTE)

    /** A raised 1995 button that sinks while pressed; dim and dead when not [enabled]. */
    fun button(value: String, big: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) = TextView(activity).apply {
        text = value
        textSize = if (big) UiScale.TEXT_HEADING else UiScale.TEXT_BASE
        typeface = Typeface.create(thai, Typeface.BOLD)
        gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.retro_button)
        setPadding(dp(UiScale.SPACE_M), 0, dp(UiScale.SPACE_M), 0)
        minWidth = dp(UiScale.TOUCH)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setEnabledButton(this, enabled, onClick)
    }

    fun setEnabledButton(view: TextView, enabled: Boolean, onClick: () -> Unit) {
        view.isEnabled = enabled
        view.isClickable = enabled
        view.setTextColor(color(if (enabled) R.color.retro_text else R.color.retro_dim))
        view.setOnClickListener(if (enabled) View.OnClickListener { onClick() } else null)
    }

    /** A 1995 option button: ● chosen, ○ not, the word beside it; a 48dp target, no fill. */
    fun option(value: String, on: Boolean, onClick: () -> Unit) = TextView(activity).apply {
        text = (if (on) "● " else "○ ") + value
        textSize = UiScale.TEXT_BASE
        typeface = Typeface.create(thai, if (on) Typeface.BOLD else Typeface.NORMAL)
        setTextColor(color(R.color.retro_text))
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(UiScale.SPACE_S), 0, dp(UiScale.SPACE_S), 0)
        minWidth = dp(UiScale.TOUCH)
        contentDescription = value + if (on) " (เลือกอยู่)" else ""
        isClickable = true
        setOnClickListener { if (!on) onClick() }
    }

    fun field(value: String) = EditText(activity).apply {
        setText(value)
        typeface = thai
        textSize = UiScale.TEXT_HEADING
        setSingleLine()
        imeOptions = EditorInfo.IME_ACTION_DONE
        setBackgroundResource(R.drawable.retro_field)
        setPadding(dp(UiScale.SPACE_S), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S))
        setTextColor(color(R.color.retro_text))
    }

    /** A line that says what is wrong, hidden until then. */
    fun errorLine() = text("", UiScale.TEXT_NOTE).apply {
        setTextColor(color(R.color.retro_bad))
        visibility = View.GONE
    }

    fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    fun row() = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** Two buttons side by side, equal width, 48dp (56dp when [big]). */
    fun pair(a: String, onA: () -> Unit, b: String, onB: () -> Unit, big: Boolean = false): View = row().apply {
        val h = dp(if (big) UiScale.PRIMARY else UiScale.TOUCH)
        addView(button(a, big) { onA() }, LinearLayout.LayoutParams(0, h, 1f))
        addView(button(b, big) { onB() }, LinearLayout.LayoutParams(0, h, 1f).apply { marginStart = dp(UiScale.SPACE_S) })
    }

    fun hideKeyboard(view: View) {
        activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
