package com.athulsn10.glancekit

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Full-screen live instruments: compass, spirit level and barometer.
 * Sensors run only while this screen is open and in front; they stop the moment you leave.
 */
class InstrumentsActivity : Activity(), SensorEventListener {

    private lateinit var sm: SensorManager
    private lateinit var content: FrameLayout
    private lateinit var tabs: List<TextView>
    private var compass: CompassView? = null
    private var level: LevelView? = null
    private var baro: BarometerView? = null
    private var current = TAB_COMPASS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sm = getSystemService(SensorManager::class.java)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(28))
        }
        tabs = listOf("Compass", "Level", "Barometer").mapIndexed { i, label ->
            TextView(this).apply {
                text = label
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                setPadding(dp(18), 0, dp(18), 0)
                setOnClickListener { show(i) }
                bar.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply {
                    marginStart = dp(4); marginEnd = dp(4)
                })
            }
        }
        // Haptics on/off, remembered.
        val haptics = Haptics(this)
        TextView(this).apply {
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            fun paint() {
                text = if (haptics.enabled) "Haptics on" else "Haptics off"
                setTextColor(if (haptics.enabled) ACCENT else 0xFF8A8A8A.toInt())
                background = GradientDrawable().apply { cornerRadius = 22 * d; setColor(0xFF1C1C1C.toInt()) }
            }
            paint()
            setOnClickListener {
                haptics.enabled = !haptics.enabled
                if (haptics.enabled) haptics.click()
                paint()
            }
            bar.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginStart = dp(4) })
        }
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        // Immersive: hide the status and navigation bars; swipe from an edge to see them.
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        current = when (intent?.getStringExtra("tab")) {
            "level" -> TAB_LEVEL
            "barometer" -> TAB_BARO
            else -> TAB_COMPASS
        }
        show(current)
    }

    private fun show(tab: Int) {
        current = tab
        tabs.forEachIndexed { i, t ->
            val on = i == tab
            t.setTextColor(if (on) BG else Color.WHITE)
            t.background = GradientDrawable().apply {
                cornerRadius = 22 * resources.displayMetrics.density
                setColor(if (on) Color.WHITE else 0xFF1C1C1C.toInt())
            }
        }
        content.removeAllViews()
        compass = null; level = null; baro = null
        val v: View = when (tab) {
            TAB_LEVEL -> LevelView(this).also { level = it }
            TAB_BARO -> BarometerView(this).also { baro = it }
            else -> CompassView(this).also { compass = it }
        }
        content.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        register()
    }

    /** Only the sensors the visible tab needs, and only while the screen is in front. */
    private fun register() {
        sm.unregisterListener(this)
        when (current) {
            TAB_COMPASS -> sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
                sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            } ?: compass?.setUnavailable()
            TAB_LEVEL -> (sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER))?.let {
                sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
            TAB_BARO -> sm.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
                sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            } ?: baro?.setUnavailable()
        }
    }

    override fun onResume() {
        super.onResume()
        register()
    }

    override fun onPause() {
        super.onPause()
        sm.unregisterListener(this)
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> compass?.onRotation(e.values)
            Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> level?.onGravity(e.values[0], e.values[1], e.values[2])
            Sensor.TYPE_PRESSURE -> baro?.onPressure(e.values[0])
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR || sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
            compass?.lowAccuracy = accuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
        }
    }

    companion object {
        const val TAB_COMPASS = 0
        const val TAB_LEVEL = 1
        const val TAB_BARO = 2
        const val BG = 0xFF0B0B0B.toInt()
        val ACCENT get() = Config.ACCENT
    }
}

// ---------------------------------------------------------------------------
// Shared drawing helpers
// ---------------------------------------------------------------------------

private fun View.dp(v: Float) = v * resources.displayMetrics.density
private fun View.sp(v: Float) = v * resources.displayMetrics.scaledDensity

private fun textPaint(color: Int, size: Float, bold: Boolean = false, mono: Boolean = false) =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        textAlign = Paint.Align.CENTER
        typeface = when {
            mono -> Typeface.MONOSPACE
            bold -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else -> Typeface.DEFAULT
        }
    }

private const val GREY = 0xFF8A8A8A.toInt()
private const val DIM = 0xFF3A3A3A.toInt()
private const val TRACK = 0xFF262626.toInt()

// ---------------------------------------------------------------------------
// Compass
// ---------------------------------------------------------------------------

class CompassView(context: Context) : View(context) {

    private var heading = 0f          // smoothed, degrees 0..360
    private var hasReading = false
    private var unavailable = false
    private var lastNotch = Int.MIN_VALUE
    private var onTarget = false
    private val haptics = Haptics(context)
    private val prefs = Store.prefs(context)
    /** Bearing to aim for, or -1 for none. Tap the dial to set or clear it. */
    private var target = prefs.getFloat("compass_target", -1f)
    var lowAccuracy = false
        set(v) {
            if (v && !field) haptics.warn()
            field = v
        }
    var showTitle = true

    init {
        isClickable = true
        isHapticFeedbackEnabled = false
        setOnClickListener {
            if (!hasReading) return@setOnClickListener
            if (target < 0f) {
                target = heading.roundToInt() % 360f
                onTarget = true
                haptics.set()
            } else {
                target = -1f
                onTarget = false
                haptics.clear()
            }
            prefs.edit().putFloat("compass_target", target).apply()
            invalidate()
        }
    }

    private val rot = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orient = FloatArray(3)

    fun setUnavailable() { unavailable = true; invalidate() }

    fun onRotation(v: FloatArray) {
        SensorManager.getRotationMatrixFromVector(rot, v)
        // Flat: use as is. Held upright: measure where the back camera points.
        val m = if (abs(rot[8]) < 0.7f) {
            SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
            remapped
        } else rot
        SensorManager.getOrientation(m, orient)
        val raw = ((Math.toDegrees(orient[0].toDouble()).toFloat() + 360f) % 360f)
        if (!hasReading) { heading = raw; hasReading = true }
        // Smooth, taking the short way round past north.
        val diff = ((raw - heading + 540f) % 360f) - 180f
        heading = (heading + diff * 0.18f + 360f) % 360f
        feel()
        invalidate()
    }

    /** Signed degrees from the heading to the target (positive = turn right). */
    private fun toTarget() = ((target - heading + 540f) % 360f) - 180f

    /**
     * Haptics, like turning a notched dial: a faint notch every 5°, firmer every 30°,
     * a click at E, S and W and a deep thud at north. With a target set, pulses speed up
     * as you swing towards it and it locks on with a swell and a click.
     */
    private fun feel() {
        val near = target >= 0f && abs(toTarget()) < 20f
        if (target >= 0f) {
            val off = abs(toTarget())
            val lockNow = if (onTarget) off < 3f else off < 1.5f   // a little hysteresis, no chatter
            if (lockNow && !onTarget) haptics.lock()
            if (!lockNow && onTarget) haptics.release()
            onTarget = lockNow
            if (!lockNow && near) haptics.proximity(1f - off / 20f)
        }
        val notch = Math.floorDiv(heading.roundToInt(), 5)
        if (lastNotch == Int.MIN_VALUE) { lastNotch = notch; return }
        if (notch == lastNotch) return
        val up = (notch - lastNotch + 72) % 72 < 36
        val crossed = (((if (up) notch else lastNotch) * 5) % 360 + 360) % 360
        lastNotch = notch
        if (near) return // the target pulses take over here
        when {
            crossed == 0 -> haptics.north()
            crossed % 90 == 0 -> haptics.click(0.9f)
            crossed % 30 == 0 -> haptics.tick(0.55f)
            else -> haptics.detent(0.22f)
        }
    }

    private val tickMajor = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
    private val tickMid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREY; strokeCap = Paint.Cap.ROUND }
    private val tickMinor = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DIM; strokeCap = Paint.Cap.ROUND }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = InstrumentsActivity.ACCENT }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height * 0.46f
        val r = min(width, height) * 0.40f

        if (showTitle) canvas.drawText("COMPASS · MAGNETIC NORTH", cx, dp(64f), textPaint(GREY, sp(12f), mono = true))

        if (unavailable) {
            canvas.drawText("No compass sensor", cx, cy, textPaint(Color.WHITE, sp(18f)))
            return
        }

        // Fixed pointer at the top.
        val tri = Path().apply {
            moveTo(cx, cy - r - dp(18f))
            lineTo(cx - dp(9f), cy - r - dp(34f))
            lineTo(cx + dp(9f), cy - r - dp(34f))
            close()
        }
        canvas.drawPath(tri, accent)

        // Rotating dial.
        canvas.save()
        canvas.rotate(-heading, cx, cy)
        tickMajor.strokeWidth = dp(3f); tickMid.strokeWidth = dp(2f); tickMinor.strokeWidth = dp(1.5f)
        for (deg in 0 until 360 step 2) {
            val (p, len) = when {
                deg % 30 == 0 -> tickMajor to dp(18f)
                deg % 10 == 0 -> tickMid to dp(12f)
                else -> tickMinor to dp(7f)
            }
            val a = Math.toRadians(deg.toDouble())
            val sx = cx + (r * sin(a)).toFloat()
            val sy = cy - (r * cos(a)).toFloat()
            val ex = cx + ((r - len) * sin(a)).toFloat()
            val ey = cy - ((r - len) * cos(a)).toFloat()
            canvas.drawLine(sx, sy, ex, ey, if (deg == 0) accent.apply { strokeWidth = dp(4f) } else p)
        }
        val cardinalPaint = textPaint(Color.WHITE, sp(22f), bold = true)
        val northPaint = textPaint(InstrumentsActivity.ACCENT, sp(24f), bold = true)
        val numPaint = textPaint(GREY, sp(11f), mono = true)
        val labels = mapOf(0 to "N", 90 to "E", 180 to "S", 270 to "W")
        for (deg in 0 until 360 step 30) {
            val a = Math.toRadians(deg.toDouble())
            val label = labels[deg]
            val rr = if (label != null) r - dp(44f) else r - dp(34f)
            val x = cx + (rr * sin(a)).toFloat()
            val y = cy - (rr * cos(a)).toFloat()
            canvas.save()
            canvas.rotate(deg.toFloat(), x, y)
            if (label != null) {
                canvas.drawText(label, x, y + dp(8f), if (deg == 0) northPaint else cardinalPaint)
            } else {
                canvas.drawText("$deg", x, y + dp(4f), numPaint)
            }
            canvas.restore()
        }
        if (target >= 0f) {
            // Target marker just outside the dial.
            val a = Math.toRadians(target.toDouble())
            val tr = r + dp(12f)
            canvas.drawCircle(cx + (tr * sin(a)).toFloat(), cy - (tr * cos(a)).toFloat(), dp(6f),
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (onTarget) InstrumentsActivity.ACCENT else Color.WHITE })
        }
        canvas.restore()

        // Centre readout (doesn't rotate).
        val deg = heading.roundToInt() % 360
        canvas.drawText("$deg°", cx, cy + dp(6f), textPaint(Color.WHITE, sp(56f), bold = true))
        canvas.drawText(direction(deg), cx, cy + dp(40f), textPaint(GREY, sp(16f), mono = true))

        if (target >= 0f) {
            val t = toTarget()
            val line = when {
                onTarget -> "ON TARGET ${target.roundToInt()}°"
                t > 0 -> "TARGET ${target.roundToInt()}° · ${abs(t).roundToInt()}° RIGHT"
                else -> "TARGET ${target.roundToInt()}° · ${abs(t).roundToInt()}° LEFT"
            }
            canvas.drawText(line, cx, cy + r + dp(48f),
                textPaint(if (onTarget) InstrumentsActivity.ACCENT else Color.WHITE, sp(13f), mono = true))
        }
        val hint = when {
            lowAccuracy -> "Move the phone in a figure-8 to calibrate"
            target >= 0f -> "Tap to clear the target"
            else -> "Tap to lock this bearing as a target"
        }
        canvas.drawText(hint, cx, cy + r + dp(74f), textPaint(if (lowAccuracy) InstrumentsActivity.ACCENT else GREY, sp(13f)))
    }

    private fun direction(deg: Int): String {
        val names = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        return names[(((deg + 11.25) % 360) / 22.5).toInt() % 16]
    }
}

// ---------------------------------------------------------------------------
// Spirit level
// ---------------------------------------------------------------------------

class LevelView(context: Context) : View(context) {

    private var gx = 0f
    private var gy = 0f
    private var gz = 9.81f
    private var started = false
    private var isLevel = false
    private var lastStep = Int.MIN_VALUE
    private var lastFlat: Boolean? = null
    private val haptics = Haptics(context)
    private val prefs = Store.prefs(context)

    // Zero points, set with a long-press (for a surface that isn't quite flat, or a case).
    private var zx = prefs.getFloat("level_zx", 0f)
    private var zy = prefs.getFloat("level_zy", 0f)
    private var zEdge = prefs.getFloat("level_ze", 0f)

    // Latest readings, worked out on each sensor update.
    private var flat = true
    private var ax = 0f
    private var ay = 0f
    private var edgeOff = 0f
    private val tilt get() = if (flat) hypot(ax, ay) else abs(edgeOff)
    private val calibrated get() = zx != 0f || zy != 0f || zEdge != 0f

    init {
        isHapticFeedbackEnabled = false
        isLongClickable = true
        setOnLongClickListener {
            if (calibrated) {
                zx = 0f; zy = 0f; zEdge = 0f
                haptics.clear()
            } else {
                if (flat) { zx += ax; zy += ay } else zEdge += edgeOff
                haptics.set()
            }
            prefs.edit().putFloat("level_zx", zx).putFloat("level_zy", zy).putFloat("level_ze", zEdge).apply()
            compute(); invalidate()
            true
        }
    }

    fun onGravity(x: Float, y: Float, z: Float) {
        if (!started) { gx = x; gy = y; gz = z; started = true }
        val k = 0.15f
        gx += (x - gx) * k
        gy += (y - gy) * k
        gz += (z - gz) * k
        compute()
        feel()
        invalidate()
    }

    private fun compute() {
        val g = sqrt(gx * gx + gy * gy + gz * gz).coerceAtLeast(0.1f)
        flat = abs(gz) / g > 0.8f
        ax = Math.toDegrees(atan2(gx.toDouble(), gz.toDouble())).toFloat() - zx
        ay = Math.toDegrees(atan2(gy.toDouble(), gz.toDouble())).toFloat() - zy
        val a = Math.toDegrees(atan2(gx.toDouble(), gy.toDouble())).toFloat()
        edgeOff = a - (a / 90f).roundToInt() * 90f - zEdge
    }

    /**
     * Haptics: a faint notch for every degree inside 10°, pulses that speed up and
     * strengthen as you close in on level, a swell-and-click when it locks level and a soft
     * fall when it slips off. Switching between surface and edge gives a thud.
     */
    private fun feel() {
        if (lastFlat != null && lastFlat != flat) haptics.tick(0.8f)
        lastFlat = flat
        val t = tilt
        val levelNow = if (isLevel) t < 0.7f else t < 0.4f   // hysteresis, so it doesn't chatter
        if (levelNow && !isLevel) haptics.lock()
        if (!levelNow && isLevel) haptics.release()
        isLevel = levelNow
        if (levelNow) { lastStep = 0; return }
        if (t < 3f) {
            haptics.proximity(1f - t / 3f)
        } else if (t < 10f) {
            val step = t.toInt()
            if (lastStep != Int.MIN_VALUE && step != lastStep) haptics.detent(0.3f)
            lastStep = step
            return
        }
        lastStep = t.toInt()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height * 0.46f
        canvas.drawText(if (flat) "LEVEL · SURFACE" else "LEVEL · EDGE", cx, dp(64f), textPaint(GREY, sp(12f), mono = true))
        val level = isLevel

        if (flat) {
            // Bullseye: the bubble drifts towards the raised side, like a real one.
            val tilt = hypot(ax, ay)
            val r = min(width, height) * 0.38f
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f); color = TRACK }
            canvas.drawCircle(cx, cy, r, ring)
            canvas.drawCircle(cx, cy, r * 0.5f, ring)
            ring.color = if (level) InstrumentsActivity.ACCENT else GREY
            canvas.drawCircle(cx, cy, dp(30f), ring)
            canvas.drawLine(cx - r, cy, cx + r, cy, Paint().apply { color = TRACK; strokeWidth = dp(1f) })
            canvas.drawLine(cx, cy - r, cx, cy + r, Paint().apply { color = TRACK; strokeWidth = dp(1f) })

            // 10° of tilt moves the bubble to the edge.
            var bx = ax / 10f * r
            var by = -ay / 10f * r
            val dist = hypot(bx, by)
            val maxD = r - dp(26f)
            if (dist > maxD) { bx *= maxD / dist; by *= maxD / dist }
            canvas.drawCircle(cx + bx, cy + by, dp(24f), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (level) InstrumentsActivity.ACCENT else Color.WHITE
            })

            canvas.drawText(String.format(Locale.ENGLISH, "%.1f°", tilt), cx, cy + r + dp(64f), textPaint(Color.WHITE, sp(44f), bold = true))
            canvas.drawText(
                String.format(Locale.ENGLISH, "X %.1f°   ·   Y %.1f°", abs(ax), abs(ay)),
                cx, cy + r + dp(92f), textPaint(GREY, sp(13f), mono = true),
            )
        } else {
            // Edge: angle from the nearest horizontal or vertical, shown as a tube level.
            val off = edgeOff
            val w = width * 0.8f
            val h = dp(56f)
            val tube = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            canvas.drawRoundRect(tube, h / 2, h / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF161616.toInt() })
            canvas.drawRoundRect(tube, h / 2, h / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = dp(2f); color = TRACK
            })
            val mark = Paint().apply { color = if (level) InstrumentsActivity.ACCENT else GREY; strokeWidth = dp(2f) }
            canvas.drawLine(cx - dp(34f), tube.top + dp(8f), cx - dp(34f), tube.bottom - dp(8f), mark)
            canvas.drawLine(cx + dp(34f), tube.top + dp(8f), cx + dp(34f), tube.bottom - dp(8f), mark)
            val maxOff = w / 2 - h / 2
            val bx = (-off / 10f * maxOff).coerceIn(-maxOff, maxOff)
            val bubble = RectF(cx + bx - dp(30f), cy - h / 2 + dp(8f), cx + bx + dp(30f), cy + h / 2 - dp(8f))
            canvas.drawRoundRect(bubble, h, h, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (level) InstrumentsActivity.ACCENT else Color.WHITE
            })
            canvas.drawText(String.format(Locale.ENGLISH, "%.1f°", abs(off)), cx, cy - h - dp(24f), textPaint(Color.WHITE, sp(56f), bold = true))
            canvas.drawText("Rest the phone's edge on the surface", cx, cy + h + dp(48f), textPaint(GREY, sp(13f)))
        }

        val hint = if (calibrated) "Custom zero set · long-press to reset" else "Long-press to set this angle as zero"
        canvas.drawText(hint, cx, height - dp(52f), textPaint(GREY, sp(12f)))
        if (level) {
            canvas.drawText("LEVEL", cx, height - dp(24f), textPaint(InstrumentsActivity.ACCENT, sp(14f), mono = true))
        }
    }
}

// ---------------------------------------------------------------------------
// Barometer
// ---------------------------------------------------------------------------

class BarometerView(context: Context) : View(context) {

    private var hpa: Float? = null
    private var unavailable = false
    private val live = ArrayDeque<Float>()
    private val info = Barometer.info(context)

    fun setUnavailable() { unavailable = true; invalidate() }

    fun onPressure(v: Float) {
        val prev = hpa
        hpa = if (prev == null) v else prev + (v - prev) * 0.2f
        live.addLast(hpa!!)
        while (live.size > 600) live.removeFirst()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        canvas.drawText("BAROMETER", cx, dp(64f), textPaint(GREY, sp(12f), mono = true))
        if (unavailable) {
            canvas.drawText("No barometer", cx, height * 0.4f, textPaint(Color.WHITE, sp(18f)))
            return
        }
        val p = hpa ?: info.hpa
        val top = height * 0.24f
        canvas.drawText(p?.let { String.format(Locale.ENGLISH, "%.1f", it) } ?: "–", cx, top, textPaint(Color.WHITE, sp(64f), bold = true))
        canvas.drawText("mbar", cx, top + dp(30f), textPaint(GREY, sp(14f), mono = true))

        // Stats row.
        val alt = p?.let { SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, it).toInt() }
        val rowY = top + dp(92f)
        val third = width / 3f
        stat(canvas, third * 0.5f, rowY, alt?.let { "≈$it m" } ?: "–", "ALTITUDE")
        stat(canvas, third * 1.5f, rowY, info.change3h?.let { String.format(Locale.ENGLISH, "%+.1f", it) } ?: "–", "3H CHANGE")
        stat(canvas, third * 2.5f, rowY, info.trend, "TREND")
        canvas.drawText(info.hint, cx, rowY + dp(56f), textPaint(InstrumentsActivity.ACCENT, sp(15f)))

        // Live line (last ~10 minutes of readings) and 24-hour history.
        val chartW = width - dp(48f)
        val left = dp(24f)
        drawLine(canvas, "LIVE", live.toList(), left, rowY + dp(100f), chartW, dp(90f))
        drawLine(canvas, "LAST 24 HOURS", info.history.map { it.hpa }, left, rowY + dp(240f), chartW, dp(90f))
    }

    private fun stat(canvas: Canvas, x: Float, y: Float, value: String, label: String) {
        canvas.drawText(value, x, y, textPaint(Color.WHITE, sp(18f), bold = true))
        canvas.drawText(label, x, y + dp(20f), textPaint(GREY, sp(10f), mono = true))
    }

    private fun drawLine(canvas: Canvas, title: String, values: List<Float>, left: Float, top: Float, w: Float, h: Float) {
        val label = textPaint(GREY, sp(10f), mono = true).apply { textAlign = Paint.Align.LEFT }
        canvas.drawText(title, left, top - dp(10f), label)
        val box = RectF(left, top, left + w, top + h)
        canvas.drawRoundRect(box, dp(12f), dp(12f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF141414.toInt() })
        if (values.size < 2) {
            canvas.drawText("Collecting…", box.centerX(), box.centerY() + dp(5f), textPaint(GREY, sp(12f)))
            return
        }
        var lo = values.min()
        var hi = values.max()
        val mid = (lo + hi) / 2f
        val minRange = if (title == "LIVE") 0.4f else 4f
        if (hi - lo < minRange) { lo = mid - minRange / 2; hi = mid + minRange / 2 }
        val pad = dp(10f)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = box.left + pad + (box.width() - 2 * pad) * i / (values.size - 1)
            val y = box.bottom - pad - (box.height() - 2 * pad) * (v - lo) / (hi - lo)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(2f); color = Color.WHITE
            strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        })
        val r = textPaint(GREY, sp(10f), mono = true).apply { textAlign = Paint.Align.RIGHT }
        canvas.drawText(String.format(Locale.ENGLISH, "%.1f", hi), box.right - dp(8f), box.top + dp(14f), r)
        canvas.drawText(String.format(Locale.ENGLISH, "%.1f", lo), box.right - dp(8f), box.bottom - dp(6f), r)
    }
}
