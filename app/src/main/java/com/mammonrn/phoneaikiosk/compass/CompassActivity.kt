package com.mammonrn.phoneaikiosk.compass

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.mammonrn.phoneaikiosk.R
import com.mammonrn.phoneaikiosk.ui.Origin
import com.mammonrn.phoneaikiosk.ui.Retro
import com.mammonrn.phoneaikiosk.ui.Retro.Companion.MATCH
import com.mammonrn.phoneaikiosk.ui.Retro.Companion.WRAP
import com.mammonrn.phoneaikiosk.ui.ToolWindow
import com.mammonrn.phoneaikiosk.ui.UiScale
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * เข็มทิศ / ระดับน้ำ (0.62.0, Poom approved, DESIGN.md 5ด): one app, two modes
 * switched by the option row at the top.
 *
 *  * The compass: the rotation vector when the phone has one (a fusion the
 *    phone does for us), else the accelerometer and the magnetometer by
 *    SensorManager.getRotationMatrix. Lying flat it reads where the top of
 *    the phone points; held up it reads where the back camera looks (the
 *    matrix is remapped). Degrees, the Thai word, and — in words, not colour —
 *    a warning to wave the phone in a figure 8 when the magnetometer says it
 *    is unsure or the field is not the Earth's. Magnetic north.
 *  * The level: gravity (the accelerometer's, filtered), two axes lying flat,
 *    one standing on its bottom edge; "ได้ระดับ" within ±1°.
 *
 * SENSORS ONLY WHILE THIS SCREEN IS IN FRONT: registered in onResume, all of
 * them let go in onPause — leaving, the screen going off, and Hey Jarvis
 * closing every screen (DESIGN 11) all pass through onPause. Only the mode
 * shown has its sensors on. Portrait only, like every screen (manifest), so
 * the display's rotation never has to be allowed for.
 *
 * Logs: which sensors exist and the accuracy word; never a heading.
 */
class CompassActivity : Activity(), SensorEventListener {

    private lateinit var r: Retro
    private lateinit var pixel: Typeface
    private lateinit var frame: ToolWindow
    private lateinit var sensors: SensorManager

    private var rotationSensor: Sensor? = null
    private var magnetSensor: Sensor? = null
    private var accelSensor: Sensor? = null
    private var gravitySensor: Sensor? = null

    private var levelMode = false
    private var listening = false

    // The compass's state.
    private var heading: Double? = null
    private var upright = false
    private var accuracyStatus: Int? = null
    private var fieldOk = true
    private val gravityNow = FloatArray(3)
    private var haveGravity = false
    private val magnetNow = FloatArray(3)
    private var haveMagnet = false
    private val rotation = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)

    // The level's state.
    private var levelGravity: DoubleArray? = null

    private var lastDrawn = 0L

    // The live parts of the page.
    private var readout: TextView? = null
    private var word: TextView? = null
    private var how: TextView? = null
    private var accuracyLine: TextView? = null
    private var warning: TextView? = null
    private var dial: CompassDial? = null
    private var bubble: BubbleView? = null
    private var levelWord: TextView? = null
    private var axes: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val thai = ResourcesCompat.getFont(this, R.font.plex_thai) ?: Typeface.DEFAULT
        pixel = ResourcesCompat.getFont(this, R.font.press_start_2p) ?: Typeface.MONOSPACE
        r = Retro(this, thai)
        sensors = getSystemService(SensorManager::class.java)
        rotationSensor = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        magnetSensor = sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        accelSensor = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gravitySensor = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)
        Log.i(TAG, "sensors rotation=${rotationSensor != null} magnet=${magnetSensor != null} " +
            "accel=${accelSensor != null} gravity=${gravitySensor != null}")
        levelMode = getPreferences(Context.MODE_PRIVATE).getBoolean(KEY_LEVEL, false)
        frame = ToolWindow(this, r, R.drawable.ic_pixel_compass, onClose = { closeApp() }, onHome = { goHome() })
        setContentView(frame.root)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { finish() }
        }
        ToolWindow.hideSystemBars(this)
        draw()
    }

    override fun onResume() {
        super.onResume()
        ToolWindow.hideSystemBars(this)
        listen(true)
    }

    override fun onPause() {
        listen(false)
        super.onPause()
    }

    @Deprecated("Superseded by OnBackInvokedDispatcher on API 33+, still the path below it.")
    @Suppress("DEPRECATION")
    override fun onBackPressed() = finish()

    private fun closeApp() = Origin.close(this, "compass-close")

    private fun goHome() = ToolWindow.goHome(this, "compass-home")

    private fun switchMode(level: Boolean) {
        listen(false)
        levelMode = level
        getPreferences(Context.MODE_PRIVATE).edit().putBoolean(KEY_LEVEL, level).apply()
        draw()
        listen(true)
    }

    // ------------------------------------------------------------ sensors

    /** On: only the sensors the mode on screen needs. Off: every one of them. */
    private fun listen(on: Boolean) {
        if (!on) {
            if (listening) sensors.unregisterListener(this)
            listening = false
            Log.i(TAG, "sensors off")
            return
        }
        if (listening) return
        val wanted = if (levelMode) listOfNotNull(gravitySensor ?: accelSensor)
            else if (magnetSensor == null) emptyList()
            else listOfNotNull(rotationSensor, magnetSensor, if (rotationSensor == null) accelSensor else null)
        for (sensor in wanted) sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        listening = wanted.isNotEmpty()
        Log.i(TAG, "sensors on mode=${if (levelMode) "level" else "compass"} count=${wanted.size}")
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type != Sensor.TYPE_MAGNETIC_FIELD || accuracyStatus == accuracy) return
        accuracyStatus = accuracy
        Log.i(TAG, "magnetometer accuracy=${Heading.accuracy(accuracy)}")
        refresh(force = true)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, magnetNow, 0, 3)
                haveMagnet = true
                fieldOk = Heading.fieldOk(Heading.strength(magnetNow[0], magnetNow[1], magnetNow[2]))
                if (accuracyStatus != event.accuracy) onAccuracyChanged(event.sensor, event.accuracy)
                if (rotationSensor == null) compassFromParts()
            }
            Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GRAVITY -> {
                if (levelMode) {
                    val g = doubleArrayOf(event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble())
                    // TYPE_GRAVITY is filtered already; the raw accelerometer is not.
                    levelGravity = if (event.sensor.type == Sensor.TYPE_GRAVITY) g
                        else Level.lowPass(levelGravity, g, LEVEL_ALPHA)
                } else {
                    for (i in 0..2) gravityNow[i] = if (haveGravity) gravityNow[i] + 0.2f * (event.values[i] - gravityNow[i]) else event.values[i]
                    haveGravity = true
                    compassFromParts()
                }
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                compassFrom(rotation)
            }
        }
        refresh(force = false)
    }

    /** No rotation vector: the accelerometer's gravity and the magnetometer's field. */
    private fun compassFromParts() {
        if (!haveGravity || !haveMagnet) return
        if (SensorManager.getRotationMatrix(rotation, null, gravityNow, magnetNow)) compassFrom(rotation)
    }

    private fun compassFrom(matrix: FloatArray) {
        // matrix[8]: the screen's normal against the sky's; its angle is the tilt.
        val tilt = Math.toDegrees(acos(matrix[8].toDouble().coerceIn(-1.0, 1.0)))
        upright = Heading.upright(upright, tilt)
        val used = if (upright) {
            SensorManager.remapCoordinateSystem(matrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
            remapped
        } else matrix
        SensorManager.getOrientation(used, orientation)
        heading = Heading.smooth(heading, Math.toDegrees(orientation[0].toDouble()), HEADING_ALPHA)
    }

    // ------------------------------------------------------------ drawing

    private fun draw() {
        readout = null; word = null; how = null; accuracyLine = null; warning = null
        dial = null; bubble = null; levelWord = null; axes = null
        frame.title.text = getString(R.string.compass_title)
        val page = r.column().apply { setPadding(0, 0, 0, r.dp(UiScale.SPACE_S)) }
        page.addView(r.row().apply {
            addView(r.option(getString(R.string.compass_mode_compass), !levelMode) { switchMode(false) },
                    LinearLayout.LayoutParams(0, r.dp(UiScale.TOUCH), 1f))
            addView(r.option(getString(R.string.compass_mode_level), levelMode) { switchMode(true) },
                    LinearLayout.LayoutParams(0, r.dp(UiScale.TOUCH), 1f))
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        if (levelMode) drawLevel(page) else drawCompass(page)
        frame.setPage(ScrollView(this).apply { addView(page) })
        refresh(force = true)
    }

    private fun lcdText(): TextView = TextView(this).apply {
        typeface = pixel
        textSize = UiScale.LCD_DIGITS
        setTextColor(r.color(R.color.retro_lcd))
        gravity = Gravity.CENTER
        maxLines = 1
    }

    private fun drawCompass(page: LinearLayout) {
        if (magnetSensor == null) {
            page.addView(r.text(getString(R.string.compass_no_sensor), UiScale.TEXT_BASE).apply { setTextColor(r.color(R.color.retro_bad)) },
                         LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_L) })
            return
        }
        page.addView(r.column().apply {
            setBackgroundColor(r.color(R.color.retro_dark))
            setPadding(r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_M), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_M))
            readout = lcdText()
            addView(readout, LinearLayout.LayoutParams(MATCH, WRAP))
            word = r.bold("", UiScale.TEXT_VALUE).apply {
                setTextColor(r.color(R.color.retro_lcd))
                gravity = Gravity.CENTER
            }
            addView(word, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
        how = r.text("", UiScale.TEXT_NOTE, dim = true).apply { gravity = Gravity.CENTER }
        page.addView(how, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_XS) })
        dial = CompassDial(this, r).apply {
            setBackgroundResource(R.drawable.retro_sunken)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        page.addView(dial, LinearLayout.LayoutParams(MATCH, r.dp(UiScale.DIAL)).apply { topMargin = r.dp(UiScale.SPACE_S) })
        accuracyLine = r.text("", UiScale.TEXT_BASE)
        page.addView(accuracyLine, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
        warning = r.bold(getString(R.string.compass_figure_eight), UiScale.TEXT_BASE).apply {
            setTextColor(r.color(R.color.retro_bad))
            setBackgroundResource(R.drawable.retro_field)
            setPadding(r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S))
            visibility = View.GONE
        }
        page.addView(warning, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_XS) })
        page.addView(r.text(getString(R.string.compass_note), UiScale.TEXT_NOTE, dim = true),
                     LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
    }

    private fun drawLevel(page: LinearLayout) {
        if (gravitySensor == null && accelSensor == null) {
            page.addView(r.text(getString(R.string.level_no_sensor), UiScale.TEXT_BASE).apply { setTextColor(r.color(R.color.retro_bad)) },
                         LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_L) })
            return
        }
        levelWord = r.bold("", UiScale.TEXT_DISPLAY).apply { gravity = Gravity.CENTER }
        page.addView(levelWord, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
        bubble = BubbleView(this, r).apply {
            setBackgroundResource(R.drawable.retro_sunken)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        page.addView(bubble, LinearLayout.LayoutParams(MATCH, r.dp(UiScale.DIAL)).apply { topMargin = r.dp(UiScale.SPACE_S) })
        axes = r.text("", UiScale.TEXT_ITEM)
        page.addView(axes, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
        page.addView(r.text(getString(R.string.level_note), UiScale.TEXT_NOTE, dim = true),
                     LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
    }

    /** The numbers and words, at most ten times a second. */
    private fun refresh(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastDrawn < DRAW_MS) return
        lastDrawn = now
        if (levelMode) refreshLevel() else refreshCompass()
    }

    private fun refreshCompass() {
        val h = heading
        if (h == null) {
            readout?.text = "---°"
            word?.text = getString(R.string.compass_waiting)
            how?.text = ""
        } else {
            val degrees = Heading.shown(h)
            readout?.text = "%d° %s".format(degrees, Heading.letters(h))
            readout?.contentDescription = getString(R.string.compass_reading_desc, degrees, Heading.thai(h))
            word?.text = Heading.thai(h)
            how?.text = getString(if (upright) R.string.compass_how_upright else R.string.compass_how_flat)
            dial?.heading = h.toFloat()
        }
        val accuracy = Heading.accuracy(accuracyStatus)
        accuracyLine?.text = getString(R.string.compass_accuracy, getString(when (accuracy) {
            Heading.Accuracy.HIGH -> R.string.compass_accuracy_high
            Heading.Accuracy.MEDIUM -> R.string.compass_accuracy_medium
            Heading.Accuracy.LOW -> R.string.compass_accuracy_low
            Heading.Accuracy.UNRELIABLE -> R.string.compass_accuracy_unreliable
            Heading.Accuracy.UNKNOWN -> R.string.compass_accuracy_unknown
        }))
        warning?.let {
            val show = Heading.needsFigureEight(accuracy, fieldOk)
            it.visibility = if (show) View.VISIBLE else View.GONE
            it.text = getString(if (fieldOk) R.string.compass_figure_eight else R.string.compass_interference)
        }
    }

    private fun refreshLevel() {
        val g = levelGravity
        val reading = g?.let { Level.read(it[0], it[1], it[2]) }
        bubble?.reading = reading
        val (headline, detail) = when (reading) {
            null -> getString(R.string.compass_waiting) to ""
            is Level.Reading.Flat -> getString(if (reading.level) R.string.level_ok else R.string.level_not) to
                getString(R.string.level_flat_axes, sideWords(reading.right, R.string.level_right_high, R.string.level_left_high),
                          sideWords(reading.top, R.string.level_top_high, R.string.level_bottom_high))
            is Level.Reading.Upright -> getString(if (reading.level) R.string.level_ok else R.string.level_not) to
                getString(R.string.level_upright_axis, sideWords(reading.right, R.string.level_right_high, R.string.level_left_high))
            Level.Reading.Sideways -> getString(R.string.level_sideways) to ""
        }
        val ok = (reading as? Level.Reading.Flat)?.level ?: (reading as? Level.Reading.Upright)?.level ?: false
        levelWord?.let {
            it.text = headline
            it.setTextColor(r.color(if (ok) R.color.retro_good else R.color.retro_text))
        }
        axes?.text = detail
    }

    /** "ขวาสูงกว่า 1.4°" / "ตรง (0.3°)": which side is higher, in words, with the angle. */
    private fun sideWords(angle: Double, plus: Int, minus: Int): String = when (Level.side(angle)) {
        1 -> getString(plus, "%.1f".format(angle))
        -1 -> getString(minus, "%.1f".format(-angle))
        else -> getString(R.string.level_axis_ok, "%.1f".format(kotlin.math.abs(angle)))
    }

    // ------------------------------------------------------------ the pictures

    /**
     * The dial: a ring that turns so its north stays north, the four points in
     * Thai on it, and a fixed navy pointer at the top — where the phone points.
     * The north tip is red and longer: its shape tells it apart as well as its colour.
     */
    private class CompassDial(context: Context, private val r: Retro) : View(context) {
        var heading = 0f
            set(value) { if (kotlin.math.abs(value - field) >= 0.5f) { field = value; invalidate() } }

        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = r.color(R.color.retro_text) }
        private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = r.color(R.color.retro_text) }
        private val north = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = r.color(R.color.retro_bad) }
        private val pointer = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = r.color(R.color.retro_title) }
        private val letters = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = r.color(R.color.retro_text)
            typeface = Typeface.create(r.thai, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,
                UiScale.TEXT_BASE, context.resources.displayMetrics)
        }
        private val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = r.color(R.color.retro_light) }
        private val path = Path()
        private val names = listOf("เหนือ", "ออก", "ใต้", "ตก")

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val radius = min(width, height) / 2f - r.dp(UiScale.SPACE_L)
            canvas.drawCircle(cx, cy, radius, face)
            ring.strokeWidth = r.dp(UiScale.BEVEL).toFloat()
            canvas.drawCircle(cx, cy, radius, ring)
            canvas.save()
            canvas.rotate(-heading, cx, cy)
            for (d in 0 until 360 step 15) {
                val long = d % 90 == 0
                val a = Math.toRadians(d.toDouble())
                val inner = radius - r.dp(if (long) UiScale.SPACE_M else UiScale.SPACE_XS)
                tick.strokeWidth = r.dp(if (long) UiScale.BEVEL else UiScale.HAIRLINE).toFloat()
                canvas.drawLine(cx + (inner * sin(a)).toFloat(), cy - (inner * cos(a)).toFloat(),
                                cx + (radius * sin(a)).toFloat(), cy - (radius * cos(a)).toFloat(), tick)
            }
            val labelR = radius - r.dp(UiScale.SPACE_L) * 2
            for ((i, name) in names.withIndex()) {
                val a = Math.toRadians(i * 90.0)
                val x = cx + (labelR * sin(a)).toFloat()
                val y = cy - (labelR * cos(a)).toFloat() + letters.textSize / 3
                letters.color = r.color(if (i == 0) R.color.retro_bad else R.color.retro_text)
                canvas.drawText(name, x, y, letters)
            }
            // The needle: north long and red, south short and dark.
            val w = r.dp(UiScale.SPACE_S).toFloat()
            path.reset()
            path.moveTo(cx, cy - labelR + letters.textSize); path.lineTo(cx - w, cy); path.lineTo(cx + w, cy); path.close()
            canvas.drawPath(path, north)
            path.reset()
            path.moveTo(cx, cy + labelR / 2); path.lineTo(cx - w, cy); path.lineTo(cx + w, cy); path.close()
            canvas.drawPath(path, tick)
            canvas.restore()
            // The phone's own direction: a fixed pointer above the ring.
            val p = r.dp(UiScale.SPACE_M).toFloat()
            path.reset()
            path.moveTo(cx, cy - radius + p); path.lineTo(cx - p, cy - radius - p / 2); path.lineTo(cx + p, cy - radius - p / 2); path.close()
            canvas.drawPath(path, pointer)
        }
    }

    /**
     * The bubble. Lying flat: a round window, crosshairs, a ring of ±1° in the
     * middle and the bubble going to the high side. Standing: a tube with two
     * marks at ±1°. Level: the bubble is FILLED and sits inside the ring;
     * otherwise it is an outline — the shape says it, the colour only helps.
     */
    private class BubbleView(context: Context, private val r: Retro) : View(context) {
        var reading: Level.Reading? = null
            set(value) { field = value; invalidate() }

        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = r.color(R.color.retro_text) }
        private val well = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = r.color(R.color.retro_light) }
        private val bubbleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = r.color(R.color.retro_good) }
        private val bubbleLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = r.color(R.color.retro_text) }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            line.strokeWidth = r.dp(UiScale.HAIRLINE).toFloat()
            bubbleLine.strokeWidth = r.dp(UiScale.BEVEL).toFloat()
            val bubbleR = r.dp(UiScale.SPACE_L).toFloat()
            when (val now = reading) {
                is Level.Reading.Upright -> {
                    val half = width / 2f - r.dp(UiScale.SPACE_L)
                    val tubeH = bubbleR * 2 + r.dp(UiScale.SPACE_S)
                    canvas.drawRect(cx - half, cy - tubeH / 2, cx + half, cy + tubeH / 2, well)
                    canvas.drawRect(cx - half, cy - tubeH / 2, cx + half, cy + tubeH / 2, line)
                    val mark = (half - bubbleR) * (Level.TOLERANCE / Level.FULL_SCALE).toFloat() + bubbleR
                    canvas.drawLine(cx - mark, cy - tubeH / 2, cx - mark, cy + tubeH / 2, line)
                    canvas.drawLine(cx + mark, cy - tubeH / 2, cx + mark, cy + tubeH / 2, line)
                    val x = cx + (half - bubbleR) * Level.bubble(now.right).toFloat()
                    drawBubble(canvas, x, cy, bubbleR, now.level)
                }
                else -> {
                    val radius = min(width, height) / 2f - r.dp(UiScale.SPACE_S)
                    canvas.drawCircle(cx, cy, radius, well)
                    canvas.drawCircle(cx, cy, radius, line)
                    canvas.drawLine(cx - radius, cy, cx + radius, cy, line)
                    canvas.drawLine(cx, cy - radius, cx, cy + radius, line)
                    val travel = radius - bubbleR
                    canvas.drawCircle(cx, cy, bubbleR + travel * (Level.TOLERANCE / Level.FULL_SCALE).toFloat(), line)
                    if (now is Level.Reading.Flat) {
                        // Screen y grows downwards: a higher top edge sends the bubble up.
                        val x = cx + travel * Level.bubble(now.right).toFloat()
                        val y = cy - travel * Level.bubble(now.top).toFloat()
                        drawBubble(canvas, x, y, bubbleR, now.level)
                    }
                }
            }
        }

        private fun drawBubble(canvas: Canvas, x: Float, y: Float, radius: Float, level: Boolean) {
            if (level) canvas.drawCircle(x, y, radius, bubbleFill)
            canvas.drawCircle(x, y, radius, bubbleLine)
        }
    }

    companion object {
        private const val TAG = "KioskCompass"
        private const val KEY_LEVEL = "level_mode"
        private const val DRAW_MS = 100L
        private const val HEADING_ALPHA = 0.2
        private const val LEVEL_ALPHA = 0.2
    }
}
