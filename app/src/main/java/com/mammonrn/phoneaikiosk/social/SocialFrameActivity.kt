package com.mammonrn.phoneaikiosk.social

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Point
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.mammonrn.phoneaikiosk.R
import com.mammonrn.phoneaikiosk.ui.Origin
import com.mammonrn.phoneaikiosk.ui.ToolWindow
import com.mammonrn.phoneaikiosk.ui.UiScale

/**
 * The 1995 window around Facebook, Instagram and YouTube (Poom 2026-09-26: the app in a
 * window nearly the size of the screen, the kiosk's own frame around it).
 *
 * HOW. The visited app is launched as a FREEFORM window (the system's own windowing,
 * switched on by adb: `settings put global enable_freeform_support 1`) with its bounds
 * set to this screen's well ([hole]). This screen is an ordinary full-screen kiosk
 * screen, opened first; it starts the app once its own opening is over: the desktop, a raised
 * window, the navy title bar (the app's own-drawn icon, its name, the Jarvis button,
 * the X) and a sunken well the app's window sits in. The kiosk's screens never become
 * windows: only the visited app's launch asks for one (SocialVisit.begin), and without
 * freeform support ([canFrame]) the app opens full screen as before, with no frame.
 *
 * THE BOUNDARY IS UNCHANGED. The app is on the lock task list only for the visit
 * (SocialVisit); lock task stays on the whole time. Every way out ends the visit:
 *  - the X: [SocialVisit.end] (the kiosk's own list again, which closes the app's task),
 *    then back where the app was opened from ([Origin]);
 *  - Back out of the app, the app closed or put away: this screen becomes the top one
 *    again ([onTopResumedActivityChanged]) and the visit is left as when a kiosk screen
 *    comes forward (YouTube may play on: SocialVisit.leave);
 *  - "Hey Jarvis", the screen off, the limit: as before, in SocialVisit; its end closes
 *    this screen ([SocialVisit.onEnd]).
 * This screen does not count as "a kiosk screen in front" (SocialVisit.kioskResumed):
 * it is under the app all the time.
 *
 * States (ux-ui-design): opening (the well says so until the app's window covers it) ·
 * the app gone (this screen closes itself, nothing left behind).
 */
class SocialFrameActivity : Activity() {

    companion object {
        const val EXTRA_APP = "social_frame_app"
        private const val TAG = "KioskSocial"
        /** WindowConfiguration.WINDOWING_MODE_FREEFORM. */
        private const val FREEFORM = 5
        /**
         * ActivityOptions' own bundle key for the launch windowing mode (its setter is not a
         * public API; the key is what ActivityOptions(Bundle) reads, checked in the A07's
         * framework.jar). Put in the bundle that startActivity takes.
         */
        private const val KEY_WINDOWING_MODE = "android.activity.windowingMode"
        private const val FREEFORM_SETTING = "enable_freeform_support"

        /** The system can put an app in a window: the feature, and freeform switched on. */
        fun canFrame(context: Context): Boolean =
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT) &&
                Settings.Global.getInt(context.contentResolver, FREEFORM_SETTING, 0) != 0

        fun intent(context: Context, app: SocialVisit.App, origin: String?): Intent =
            Intent(context, SocialFrameActivity::class.java).putExtra(EXTRA_APP, app.name).also {
                if (origin != null) Origin.from(it, origin)
            }

        private fun dp(context: Context, value: Int): Int = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()

        private fun screenSize(activity: Activity): Point =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activity.windowManager.currentWindowMetrics.bounds.let { Point(it.width(), it.height()) }
            } else {
                @Suppress("DEPRECATION")
                Point().also { activity.windowManager.defaultDisplay.getRealSize(it) }
            }

        /**
         * The well's inside, in screen pixels: the frame, the window's inset and bevel on
         * the sides and the bottom; above it the title bar too. The same sizes the layout
         * below uses, so the app's window lands exactly in the well.
         */
        fun hole(activity: Activity): Rect {
            val size = screenSize(activity)
            val side = dp(activity, UiScale.FRAME) + dp(activity, UiScale.WINDOW_INSET) + dp(activity, UiScale.BEVEL)
            val top = side + dp(activity, UiScale.TOUCH) + dp(activity, UiScale.WINDOW_INSET)
            return Rect(side, top, size.x - side, size.y - side)
        }

        /** startActivity's options for the visited app: a freeform window in the well. */
        fun launchOptions(activity: Activity): Bundle =
            ActivityOptions.makeBasic().setLaunchBounds(hole(activity)).toBundle().apply {
                putInt(KEY_WINDOWING_MODE, FREEFORM)
            }
    }

    private lateinit var app: SocialVisit.App
    /** The app has been above this screen once: its going away is now worth looking at. */
    private var appWasOnTop = false
    /** The app was asked for (SocialVisit.launchInFrame), once, when this screen's opening was over. */
    private var launched = false
    /** What SocialVisit calls when the visit ends: this frame closes too. */
    private val onEndHook: () -> Unit = { if (!isFinishing) Origin.close(this, "social-frame-end") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = runCatching { SocialVisit.App.valueOf(intent.getStringExtra(EXTRA_APP) ?: "") }
            .getOrDefault(SocialVisit.App.FACEBOOK)
        // Recreated with no visit (the process came back, or the app failed to start): nothing to frame.
        if (SocialVisit.activePackage() == null) {
            Log.i(TAG, "frame closed: no visit")
            finish()
            return
        }
        // Drawn to the screen's very edges, as hole() assumes (bars hidden, the camera cut-out too).
        window.attributes = window.attributes.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        setContentView(build())
        ToolWindow.hideSystemBars(this)
        SocialVisit.onEnd = onEndHook
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { close("social-frame-back") }
        }
    }

    override fun onResume() {
        super.onResume()
        ToolWindow.hideSystemBars(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (SocialVisit.onEnd === onEndHook) SocialVisit.onEnd = null
    }

    /**
     * The top screen changed. Lost: the app came above (its start). Back again after
     * that: the app was closed, backed out of, or put away — as a kiosk screen coming
     * forward, the visit is left (SocialVisit.leave: it ends, or YouTube plays on), and
     * this frame closes too.
     */
    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        if (isFinishing) return
        if (!isTopResumedActivity) { if (launched) appWasOnTop = true; return }
        if (!launched || !appWasOnTop) return
        Log.i(TAG, "frame on top again: the app went away")
        SocialVisit.appLeft(this)
        if (!isFinishing) Origin.close(this, "social-frame-app-gone")
    }

    /**
     * This screen's opening is over: now the app, over it (its failure ends the visit, and
     * onEnd closes this screen). Not sooner: every kiosk screen that starts while the kiosk
     * is locked brings its task forward, and One UI then sends any freeform window to the
     * back (A07 log: "MultiWindowEnableController: dismissMultiWindowMode: freeform to back",
     * from LockTaskController.startLockTaskMode, queued behind this screen's opening
     * transition). Started at once, the app went behind this screen within 0.4 s.
     */
    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        if (launched || isFinishing) return
        launched = true
        SocialVisit.launchInFrame(this)
    }

    @Deprecated("Superseded by OnBackInvokedDispatcher on API 33+, still the path below it.")
    @Suppress("DEPRECATION")
    override fun onBackPressed() = close("social-frame-back")

    /** The X: the visit ends (the app's task closes with it) and back where it came from. */
    private fun close(reason: String) {
        SocialVisit.end(this, reason)
        if (!isFinishing) Origin.close(this, reason)
    }

    private fun color(id: Int) = ContextCompat.getColor(this, id)

    private fun build(): FrameLayout {
        val thai = ResourcesCompat.getFont(this, R.font.plex_thai) ?: Typeface.DEFAULT
        val frame = dp(this, UiScale.FRAME)
        val inset = dp(this, UiScale.WINDOW_INSET)
        val root = FrameLayout(this).apply {
            setBackgroundColor(color(R.color.retro_desktop))
            setPadding(frame, frame, frame, frame)
        }
        val window = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.retro_raised)
            setPadding(inset, inset, inset, inset)
        }
        root.addView(window, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // The title bar: the app's own-drawn icon, its name, (the Jarvis button, put in
        // before the X by JarvisBadges), the X. The same parts as ToolWindow's.
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.retro_titlebar)
            setPadding(dp(context, UiScale.SPACE_S), 0, 0, 0)
        }
        bar.addView(ImageView(this).apply { setImageResource(icon()) },
            LinearLayout.LayoutParams(dp(this, UiScale.ICON_S), dp(this, UiScale.ICON_S)))
        bar.addView(TextView(this).apply {
            text = name()
            setTextColor(color(R.color.retro_title_text))
            textSize = UiScale.TEXT_BASE
            typeface = Typeface.create(thai, Typeface.BOLD)
            setPadding(dp(context, UiScale.SPACE_S), 0, 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(FrameLayout(this).apply {
            setBackgroundResource(R.drawable.retro_button)
            contentDescription = Origin.closeWords(this@SocialFrameActivity)
            isClickable = true
            setOnClickListener { close("social-frame-close") }
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_pixel_close) },
                FrameLayout.LayoutParams(dp(context, UiScale.ICON_M), dp(context, UiScale.ICON_M), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(this, UiScale.TOUCH), dp(this, UiScale.TOUCH)))
        window.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, UiScale.TOUCH)))
        // The well the app's window sits in; until it does, it says what is happening.
        val well = FrameLayout(this).apply { setBackgroundResource(R.drawable.retro_sunken) }
        well.addView(TextView(this).apply {
            text = getString(R.string.social_frame_opening, name())
            setTextColor(color(R.color.retro_text))
            textSize = UiScale.TEXT_BASE
            typeface = thai
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        window.addView(well, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = inset })
        // The app's window is placed by hole(); the well is placed by this layout. Said
        // in the log when they differ, so a misplaced frame is found by numbers, not photos.
        well.post {
            val at = IntArray(2).also { well.getLocationOnScreen(it) }
            val bevel = dp(this, UiScale.BEVEL)
            val drawn = Rect(at[0] + bevel, at[1] + bevel, at[0] + well.width - bevel, at[1] + well.height - bevel)
            val asked = hole(this)
            Log.i(TAG, "frame well matches=${drawn == asked} drawn=${drawn.toShortString()} asked=${asked.toShortString()}")
        }
        return root
    }

    private fun name() = getString(when (app) {
        SocialVisit.App.FACEBOOK -> R.string.social_facebook
        SocialVisit.App.INSTAGRAM -> R.string.social_instagram
        SocialVisit.App.YOUTUBE -> R.string.social_youtube
    })

    private fun icon() = when (app) {
        SocialVisit.App.FACEBOOK -> R.drawable.ic_pixel_facebook
        SocialVisit.App.INSTAGRAM -> R.drawable.ic_pixel_instagram
        SocialVisit.App.YOUTUBE -> R.drawable.ic_pixel_tv
    }
}
