package com.glassring.widget

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The focus timer, drawn live: a glowing ring that burns down with embers drifting off its
 * tip, a calm breathing ring while idle, today's sessions as dots, and confetti when a session
 * ends. Animates only while it is on screen.
 */
class FocusView(context: Context) : View(context) {

    // ----- State -----
    private var running = false
    private var paused = false
    private var endAt = 0L
    private var left = 0L
    private var length = 1L
    private var sessions = 0
    private var idleMs = 25 * 60_000L
    var goal = 4
    /** Rounded corners for the scene, to sit neatly inside a card (0 in full screen). */
    var cornerDp = 18f
    private var lastFrame = 0L
    private val clip = android.graphics.Path()
    /** Called once when a running session reaches zero on screen. */
    var onFinished: (() -> Unit)? = null

    private val haptics = Haptics(context)
    private var lastSecond = -1L
    private var halfwayDone = false
    private var finishedFired = false

    fun bind(f: FocusInfo, idleMinutes: Long) {
        running = f.running; paused = f.paused; endAt = f.endAt; left = f.left
        length = f.length.coerceAtLeast(1); sessions = f.sessions
        idleMs = idleMinutes * 60_000L
        halfwayDone = remaining() < length / 2
        finishedFired = false
        lastSecond = -1
        invalidate()
    }

    private fun remaining(): Long = when {
        running -> (endAt - System.currentTimeMillis()).coerceAtLeast(0)
        paused -> left
        else -> idleMs
    }

    // ----- Particles -----
    private class Bit(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float,
                      val max: Float, val size: Float, val color: Int, var rot: Float = 0f, val spin: Float = 0f)
    private val embers = ArrayList<Bit>()
    private val rnd = Random(7)

    // ----- Paints -----
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = 0xFF1E1E1E.toInt() }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val glowArc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val timeP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        fontFeatureSettings = "tnum" // digits keep the same width, so the time doesn't wobble
    }
    private val labelP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.MONOSPACE; letterSpacing = 0.18f
    }
    private val oval = RectF()
    private val vis = Rect()

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val t = now / 1000f
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2
        val cy = h * 0.45f
        val r = min(w * 0.36f, h * 0.36f)
        val stroke = r * 0.075f
        val accent = Config.ACCENT

        // The calm background scene (rain, leaves, snow...), behind everything else.
        val dt = if (lastFrame == 0L) 1 / 30f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.05f)
        lastFrame = now
        Ambient.get(Ambient.kind(context), width, height, resources.displayMetrics.density)?.let { scene ->
            scene.step(dt)
            canvas.save()
            if (cornerDp > 0) {
                clip.reset()
                clip.addRoundRect(0f, 0f, w, h, dp(cornerDp), dp(cornerDp), android.graphics.Path.Direction.CW)
                canvas.clipPath(clip)
            }
            scene.draw(canvas)
            canvas.restore()
        }

        val rem = remaining()
        val frac = when {
            running || paused -> (rem.toFloat() / length).coerceIn(0f, 1f)
            else -> 1f
        }
        feel(rem)

        // Breathing glow behind the ring (slow while idle, gentle while running).
        val breath = if (running) 0.5f + 0.5f * sin(t * 1.6f) else 0.5f + 0.5f * sin(t * 0.9f)
        val haloA = if (running) (40 + 30 * breath).toInt() else if (paused) 18 else (20 + 25 * breath).toInt()
        fill.shader = RadialGradient(cx, cy, r * 1.45f,
            intArrayOf(Color.argb(haloA, Color.red(accent), Color.green(accent), Color.blue(accent)), Color.TRANSPARENT),
            floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * 1.45f, fill)
        fill.shader = null
        // Darken the inside of the ring a little so the time stays easy to read over the scene.
        fill.color = 0xB30B0B0B.toInt()
        canvas.drawCircle(cx, cy, r - stroke * 0.5f, fill)

        // Minute ticks around the outside: lit for the time that's left.
        val ticks = 60
        val lit = (frac * ticks).toInt()
        tick.strokeWidth = dp(1.6f)
        for (i in 0 until ticks) {
            val a = (-90f + i * 6f) * (PI / 180).toFloat()
            val big = i % 5 == 0
            val r1 = r + stroke * 1.6f
            val r2 = r1 + if (big) dp(9f) else dp(5f)
            tick.color = if (i < lit && (running || paused)) (if (big) Color.WHITE else 0xFF8A8A8A.toInt()) else 0xFF2A2A2A.toInt()
            canvas.drawLine(cx + r1 * cos(a), cy + r1 * sin(a), cx + r2 * cos(a), cy + r2 * sin(a), tick)
        }

        // Track and the burning arc.
        oval.set(cx - r, cy - r, cx + r, cy + r)
        track.strokeWidth = stroke
        canvas.drawOval(oval, track)
        val sweep = 360f * frac
        if (sweep > 0.5f) {
            // Dark at the start, bright at the burning tip.
            val f = (sweep / 360f).coerceIn(0.002f, 1f)
            val sg = SweepGradient(cx, cy,
                intArrayOf(0xFF7A2A12.toInt(), accent, 0xFFFFB077.toInt()), floatArrayOf(0f, f * 0.7f, f))
            sg.setLocalMatrix(Matrix().apply { setRotate(-90f, cx, cy) })
            arc.shader = sg
            arc.strokeWidth = stroke
            arc.alpha = if (paused) 120 else 255
            if (running) {
                glowArc.color = accent
                glowArc.strokeWidth = stroke * 2.2f
                glowArc.alpha = (60 + 40 * breath).toInt()
                glowArc.maskFilter = BlurMaskFilter(stroke * 1.4f, BlurMaskFilter.Blur.NORMAL)
                canvas.drawArc(oval, -90f, sweep, false, glowArc)
            }
            canvas.drawArc(oval, -90f, sweep, false, arc)
        }

        // The tip: a hot dot that pulses, throwing off embers while running.
        val tipA = ((-90f + sweep) * PI / 180).toFloat()
        val tx = cx + r * cos(tipA)
        val ty = cy + r * sin(tipA)
        if (running || paused) {
            val pulse = if (running) 1f + 0.25f * sin(t * 6f) else 1f
            fill.shader = RadialGradient(tx, ty, stroke * 2.4f * pulse,
                intArrayOf(0xCCFFD2A8.toInt(), Color.argb(90, Color.red(accent), Color.green(accent), Color.blue(accent)), Color.TRANSPARENT),
                floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(tx, ty, stroke * 2.4f * pulse, fill)
            fill.shader = null
            fill.color = Color.WHITE
            canvas.drawCircle(tx, ty, stroke * 0.42f, fill)
        }
        if (running && embers.size < 46 && rnd.nextFloat() < 0.7f) {
            val out = 0.6f + rnd.nextFloat()
            val side = (rnd.nextFloat() - 0.5f) * 1.2f
            embers.add(Bit(tx, ty,
                vx = (cos(tipA) * out - sin(tipA) * side) * dp(26f),
                vy = (sin(tipA) * out + cos(tipA) * side) * dp(26f) - dp(10f),
                life = 0f, max = 0.9f + rnd.nextFloat() * 0.9f,
                size = dp(1.2f + rnd.nextFloat() * 1.8f),
                color = if (rnd.nextFloat() < 0.3f) 0xFFFFD2A8.toInt() else accent))
        }
        stepAndDraw(canvas, embers, 1 / 30f, gravity = -dp(6f), rect = false)

        // Time, fitted inside the ring.
        val txt = String.format(Locale.ENGLISH, "%02d:%02d", rem / 60_000, (rem / 1000) % 60)
        timeP.textSize = 100f
        val target = r * 1.25f
        timeP.textSize = 100f * target / timeP.measureText("00:00")
        timeP.alpha = if (paused) (150 + 100 * breath).toInt() else 255
        canvas.drawText(txt, cx, cy + timeP.textSize * 0.34f, timeP)

        labelP.textSize = dp(11f)
        labelP.color = if (running) accent else 0xFF8A8A8A.toInt()
        canvas.drawText(mood(rem), cx, cy + timeP.textSize * 0.34f + dp(30f), labelP)

        // Today's sessions as dots, towards a daily goal.
        val dots = maxOf(goal, sessions)
        val gap = dp(18f)
        val y = h - dp(30f)
        val x0 = cx - (dots - 1) * gap / 2
        for (i in 0 until dots) {
            val done = i < sessions
            fill.color = if (done) accent else 0xFF2A2A2A.toInt()
            canvas.drawCircle(x0 + i * gap, y, if (done) dp(5f) else dp(4f), fill)
        }
        labelP.textSize = dp(10f)
        labelP.color = 0xFF8A8A8A.toInt()
        canvas.drawText(
            if (sessions >= goal) "DAILY GOAL REACHED · $sessions TODAY" else "$sessions OF $goal SESSIONS TODAY",
            cx, y + dp(22f), labelP)

        // Confetti after a finished session.
        if (now < celebrateUntil) {
            if (confetti.isEmpty() && now - celebrateStart < 200) burst(cx, cy, r)
            stepAndDraw(canvas, confetti, 1 / 30f, gravity = dp(260f), rect = true)
        } else confetti.clear()

    }

    /**
     * Animation clock. It runs on its own instead of from onDraw: when you swipe to another
     * tab and back, Android can reuse the last picture without calling onDraw, which froze the
     * timer. ~30 fps while on screen; when off screen it only checks once a second.
     */
    private val frame = object : Runnable {
        override fun run() {
            val onScreen = isShown && getGlobalVisibleRect(vis) && windowVisibility == VISIBLE
            if (onScreen) invalidate()
            val busy = running || SystemClock.uptimeMillis() < celebrateUntil || embers.isNotEmpty() ||
                Ambient.kind(context) != Ambient.Kind.NONE
            postDelayed(this, if (!onScreen) 1000L else if (busy) 33L else 66L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(frame)
        post(frame)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) { removeCallbacks(frame); if (isAttachedToWindow) post(frame) }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frame)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        removeCallbacks(frame)
        if (isVisible && isAttachedToWindow) post(frame)
    }

    private fun mood(rem: Long): String {
        if (paused) return "PAUSED · TAP RESUME"
        if (!running) return if (SystemClock.uptimeMillis() < celebrateUntil) "SESSION COMPLETE" else "READY WHEN YOU ARE"
        val p = 1f - rem.toFloat() / length
        return when {
            rem <= 60_000 -> "FINAL MINUTE"
            p < 0.08f -> "SETTLING IN"
            p < 0.45f -> "DEEP FOCUS"
            p < 0.55f -> "HALFWAY THERE"
            p < 0.85f -> "KEEP GOING"
            else -> "FINAL STRETCH"
        }
    }

    /** Haptic cues: a click at halfway, a tick each second for the last 10, a celebration at zero. */
    private fun feel(rem: Long) {
        if (!running) return
        val sec = rem / 1000
        if (sec == lastSecond) return
        val first = lastSecond < 0
        lastSecond = sec
        if (first) return
        if (!halfwayDone && rem < length / 2) { halfwayDone = true; haptics.click(0.7f) }
        if (sec in 1..10) haptics.tick(0.35f + (10 - sec) * 0.06f)
        if (rem <= 0 && !finishedFired) {
            finishedFired = true
            running = false
            celebrate(haptics)
            onFinished?.invoke()
        }
    }

    private fun stepAndDraw(canvas: Canvas, list: ArrayList<Bit>, dt: Float, gravity: Float, rect: Boolean) {
        val it = list.iterator()
        while (it.hasNext()) {
            val b = it.next()
            b.life += dt
            if (b.life >= b.max) { it.remove(); continue }
            b.vy += gravity * dt
            b.x += b.vx * dt; b.y += b.vy * dt
            b.rot += b.spin * dt
            val a = (1f - b.life / b.max)
            fill.color = b.color
            fill.alpha = (255 * a).toInt()
            if (rect) {
                canvas.save()
                canvas.rotate(b.rot, b.x, b.y)
                canvas.drawRect(b.x - b.size, b.y - b.size * 0.45f, b.x + b.size, b.y + b.size * 0.45f, fill)
                canvas.restore()
            } else canvas.drawCircle(b.x, b.y, b.size * (0.5f + a * 0.5f), fill)
        }
        fill.alpha = 255
    }

    private fun burst(cx: Float, cy: Float, r: Float) {
        val colors = intArrayOf(Config.ACCENT, Color.WHITE, 0xFFFFB077.toInt(), 0xFF8A8A8A.toInt(), 0xFFFFD2A8.toInt())
        repeat(90) {
            val a = rnd.nextFloat() * 2 * PI.toFloat()
            val sp = dp(140f + rnd.nextFloat() * 260f)
            confetti.add(Bit(cx + cos(a) * r * 0.3f, cy + sin(a) * r * 0.3f,
                vx = cos(a) * sp, vy = sin(a) * sp - dp(160f),
                life = 0f, max = 1.6f + rnd.nextFloat() * 1.2f,
                size = dp(3f + rnd.nextFloat() * 3f), color = colors[rnd.nextInt(colors.size)],
                rot = rnd.nextFloat() * 360f, spin = (rnd.nextFloat() - 0.5f) * 720f))
        }
    }

    companion object {
        // Shared, so the celebration survives the page redrawing when the session is counted.
        private var celebrateUntil = 0L
        private var celebrateStart = 0L
        private val confetti = ArrayList<Bit>()

        fun celebrate(h: Haptics?) {
            celebrateStart = SystemClock.uptimeMillis()
            celebrateUntil = celebrateStart + 3200
            confetti.clear()
            h?.celebrate()
        }
    }
}
