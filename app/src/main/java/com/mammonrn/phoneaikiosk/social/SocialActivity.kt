package com.mammonrn.phoneaikiosk.social

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.content.res.ResourcesCompat
import com.mammonrn.phoneaikiosk.R
import com.mammonrn.phoneaikiosk.auth.IdentityGate
import com.mammonrn.phoneaikiosk.ui.Origin
import com.mammonrn.phoneaikiosk.ui.Retro
import com.mammonrn.phoneaikiosk.ui.Retro.Companion.MATCH
import com.mammonrn.phoneaikiosk.ui.Retro.Companion.WRAP
import com.mammonrn.phoneaikiosk.ui.ToolWindow
import com.mammonrn.phoneaikiosk.ui.UiScale

/**
 * The step between the Control Panel and Facebook or Instagram (0.65.0, Poom): the
 * identity check, then the app for one visit (SocialVisit). Opened, it asks for the
 * face at once; passed, it opens the app and closes itself, so Back out of the app
 * lands on the Control Panel it came from.
 *
 * States (ux-ui-design): about to check (why, and how to come back) · not installed
 * (install from the Play Store, after the same check) · refused (why, try again) ·
 * could not open (try again).
 */
class SocialActivity : Activity() {

    companion object {
        const val EXTRA_APP = "social_app"
        /** scripts/ui-check: draw the page without starting the identity check (its camera is never photographed). */
        const val EXTRA_NO_CHECK = "social_no_check"
        private const val TAG = "KioskSocial"

        fun intent(context: Context, app: SocialVisit.App): Intent =
            Intent(context, SocialActivity::class.java).putExtra(EXTRA_APP, app.name)
    }

    private enum class Want { OPEN, INSTALL }

    private lateinit var r: Retro
    private lateinit var frame: ToolWindow
    private lateinit var app: SocialVisit.App
    private var want = Want.OPEN
    private var problem = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = runCatching { SocialVisit.App.valueOf(intent.getStringExtra(EXTRA_APP) ?: "") }.getOrDefault(SocialVisit.App.FACEBOOK)
        r = Retro(this, ResourcesCompat.getFont(this, R.font.plex_thai) ?: Typeface.DEFAULT)
        frame = ToolWindow(this, r, icon(app),
            onClose = { Origin.close(this, "social-close") }, onHome = { ToolWindow.goHome(this, "social-home") })
        frame.title.text = name()
        setContentView(frame.root)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { Origin.close(this, "social-back") }
        }
        ToolWindow.hideSystemBars(this)
        draw()
        if (savedInstanceState == null && !intent.getBooleanExtra(EXTRA_NO_CHECK, false) &&
            SocialVisit.installed(this, app) != null) check(Want.OPEN)
    }

    override fun onResume() {
        super.onResume()
        ToolWindow.hideSystemBars(this)
    }

    @Deprecated("Superseded by OnBackInvokedDispatcher on API 33+, still the path below it.")
    @Suppress("DEPRECATION")
    override fun onBackPressed() = Origin.close(this, "social-back")

    private fun name() = getString(if (app == SocialVisit.App.FACEBOOK) R.string.social_facebook else R.string.social_instagram)

    private fun installName() = getString(if (app == SocialVisit.App.FACEBOOK) R.string.social_facebook_lite else R.string.social_instagram)

    private fun draw() {
        val body = r.column().apply { setPadding(r.dp(UiScale.SPACE_M), r.dp(UiScale.SPACE_M), r.dp(UiScale.SPACE_M), r.dp(UiScale.SPACE_M)) }
        val installed = SocialVisit.installed(this, app) != null
        val lp = { top: Int -> LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(top) } }
        if (installed) {
            body.addView(r.bold(getString(R.string.social_why_check, name()), UiScale.TEXT_BASE), lp(0))
            if (problem.isNotEmpty()) body.addView(r.text(problem, UiScale.TEXT_BASE).apply { setTextColor(r.color(R.color.retro_bad)) }, lp(UiScale.SPACE_S))
            body.addView(r.button(getString(R.string.social_open, name()), big = true) { check(Want.OPEN) },
                LinearLayout.LayoutParams(MATCH, r.dp(UiScale.PRIMARY)).apply { topMargin = r.dp(UiScale.SPACE_M) })
            body.addView(r.text(getString(R.string.social_how_back, name(), (SocialVisit.LIMIT_MS / 60_000).toInt()), UiScale.TEXT_NOTE, dim = true), lp(UiScale.SPACE_M))
        } else {
            body.addView(r.bold(getString(R.string.social_not_installed, installName()), UiScale.TEXT_BASE), lp(0))
            if (problem.isNotEmpty()) body.addView(r.text(problem, UiScale.TEXT_BASE).apply { setTextColor(r.color(R.color.retro_bad)) }, lp(UiScale.SPACE_S))
            body.addView(r.button(getString(R.string.social_install), big = true) { check(Want.INSTALL) },
                LinearLayout.LayoutParams(MATCH, r.dp(UiScale.PRIMARY)).apply { topMargin = r.dp(UiScale.SPACE_M) })
            body.addView(r.text(getString(R.string.social_install_note, installName()), UiScale.TEXT_NOTE, dim = true), lp(UiScale.SPACE_M))
        }
        frame.setPage(ScrollView(this).apply { addView(body) })
    }

    private fun check(what: Want) {
        want = what
        problem = ""
        IdentityGate.ask(this)
    }

    @Deprecated("startActivityForResult's partner; this app has no androidx.activity.")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != IdentityGate.REQUEST) return
        if (!IdentityGate.passed(data)) {
            problem = IdentityGate.refusal(this, data)
            Log.i(TAG, "check not passed app=${app.name.lowercase()}")
            draw()
            return
        }
        val result = if (want == Want.INSTALL) SocialVisit.openPlayStore(this, app) else SocialVisit.open(this, app)
        Log.i(TAG, "after check app=${app.name.lowercase()} want=${want.name.lowercase()} result=${result.name.lowercase()}")
        if (result == SocialVisit.Result.OPENED) { finish(); return }
        problem = getString(if (result == SocialVisit.Result.NOT_INSTALLED) R.string.social_gone else R.string.social_failed, name())
        draw()
    }

    private fun icon(app: SocialVisit.App) =
        if (app == SocialVisit.App.FACEBOOK) R.drawable.ic_pixel_facebook else R.drawable.ic_pixel_instagram
}
