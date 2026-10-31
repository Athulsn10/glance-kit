package com.glassring.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Calm background scenes for the focus timer: rain, falling leaves, snow, fireflies and a
 * starry night. Each one is a small particle system drawn behind the ring. Scenes start
 * "already going" (particles spread over the screen) so there's never an empty first frame.
 */
abstract class Ambient(protected val w: Float, protected val h: Float, protected val density: Float) {
    protected val rnd = Random(11)
    protected val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected fun dp(v: Float) = v * density

    abstract fun step(dt: Float)
    abstract fun draw(c: Canvas)

    enum class Kind(val label: String) { NONE("None"), RAIN("Rain"), LEAVES("Leaves"), SNOW("Snow"), FIREFLIES("Fireflies"), STARS("Stars") }

    companion object {
        fun kind(c: android.content.Context): Kind =
            runCatching { Kind.valueOf(Store.prefs(c).getString("focus_scene", Kind.RAIN.name)!!) }.getOrDefault(Kind.RAIN)

        fun setKind(c: android.content.Context, k: Kind) = Store.prefs(c).edit().putString("focus_scene", k.name).apply()

        // One running scene per size, so a page redraw doesn't restart the rain.
        private val cache = HashMap<String, Ambient>()

        fun get(k: Kind, w: Int, h: Int, density: Float): Ambient? {
            if (k == Kind.NONE || w <= 0 || h <= 0) return null
            val key = "$k:$w:$h"
            return cache.getOrPut(key) {
                if (cache.size > 6) cache.clear()
                val fw = w.toFloat(); val fh = h.toFloat()
                when (k) {
                    Kind.RAIN -> Rain(fw, fh, density)
                    Kind.LEAVES -> Leaves(fw, fh, density)
                    Kind.SNOW -> Snow(fw, fh, density)
                    Kind.FIREFLIES -> Fireflies(fw, fh, density)
                    else -> Stars(fw, fh, density)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Rain: drops at three depths with soft motion-blur tails, drifting sheets of heavier rain,
// gusts that change the slant, crown splashes, beads of water sliding down the "glass",
// and lightning (with thunder when focus sounds are on).
// ---------------------------------------------------------------------------
private class Rain(w: Float, h: Float, d: Float) : Ambient(w, h, d), FocusSound.LightningListener {
    private class Drop(var x: Float, var y: Float, val z: Float)
    private class Splash(val x: Float, val y: Float, var t: Float, val z: Float, val vx: FloatArray, val vy: FloatArray)
    private class Bead(var x: Float, var y: Float, val r: Float, var sliding: Boolean, var speed: Float, var trail: Float)
    private val drops = ArrayList<Drop>()
    private val splashes = ArrayList<Splash>()
    private val beads = ArrayList<Bead>()
    private var time = 0f
    private var gust = 0f          // extra wind from a passing gust
    private var gustTarget = 0f
    private var nextGust = 6f
    private val ground get() = h - dp(6f)

    // Lightning.
    private var flashT = -1f       // seconds since the flash started, -1 = none
    private var flashPower = 1f
    private var nextFlash = 18f
    private val bolt = Path()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    init {
        repeat(190) { drops.add(Drop(rnd.nextFloat() * w * 1.3f - w * 0.15f, rnd.nextFloat() * h, 0.15f + rnd.nextFloat() * 0.85f)) }
        repeat(16) { beads.add(newBead(rnd.nextFloat() * h)) }
        FocusSound.listen(this)
    }

    private fun newBead(y: Float) = Bead(rnd.nextFloat() * w, y, dp(1.2f + rnd.nextFloat() * 2.6f), false, 0f, 0f)

    private fun wind() = dp(50f) + dp(25f) * sin(time * 0.27f) + gust

    /** Called by the sound service just before it plays thunder, so light and sound match. */
    override fun onLightning(power: Float) { strike(power) }

    private fun strike(power: Float) {
        flashT = 0f
        flashPower = power
        // A jagged bolt from the top, with a branch or two.
        bolt.reset()
        var x = w * (0.15f + rnd.nextFloat() * 0.7f)
        var y = 0f
        bolt.moveTo(x, y)
        val end = h * (0.45f + rnd.nextFloat() * 0.35f)
        while (y < end) {
            x += (rnd.nextFloat() - 0.5f) * dp(26f)
            y += dp(8f + rnd.nextFloat() * 16f)
            bolt.lineTo(x, y)
            if (rnd.nextFloat() < 0.12f) {
                var bx = x; var by = y
                val dir = if (rnd.nextBoolean()) 1 else -1
                bolt.moveTo(bx, by)
                repeat(3 + rnd.nextInt(4)) {
                    bx += dir * dp(6f + rnd.nextFloat() * 14f); by += dp(6f + rnd.nextFloat() * 12f)
                    bolt.lineTo(bx, by)
                }
                bolt.moveTo(x, y)
            }
        }
    }

    override fun step(dt: Float) {
        time += dt
        // Gusts come and go.
        nextGust -= dt
        if (nextGust <= 0) { gustTarget = dp(rnd.nextFloat() * 90f - 20f); nextGust = 5f + rnd.nextFloat() * 9f }
        gust += (gustTarget - gust) * (dt * 0.6f)
        if (abs(gustTarget - gust) < dp(2f)) gustTarget *= 0.98f

        // Lightning on its own only when the sound service isn't driving it.
        if (!FocusSound.active) {
            nextFlash -= dt
            if (nextFlash <= 0) { strike(0.6f + rnd.nextFloat() * 0.4f); nextFlash = 25f + rnd.nextFloat() * 40f }
        }
        if (flashT >= 0) { flashT += dt; if (flashT > 1.2f) flashT = -1f }

        val wind = wind()
        for (r in drops) {
            val speed = dp(520f + 950f * r.z)
            r.y += speed * dt
            r.x += wind * r.z * dt
            if (r.y > ground - (1 - r.z) * h * 0.28f) {
                if (r.z > 0.5f && splashes.size < 50) {
                    val n = 3
                    splashes.add(Splash(r.x, r.y, 0f, r.z,
                        FloatArray(n) { (rnd.nextFloat() - 0.5f) * dp(70f) * r.z },
                        FloatArray(n) { -dp(40f + rnd.nextFloat() * 50f) * r.z }))
                }
                r.y = -rnd.nextFloat() * h * 0.3f
                r.x = rnd.nextFloat() * w * 1.3f - w * 0.15f
            }
        }
        val it = splashes.iterator()
        while (it.hasNext()) { val s = it.next(); s.t += dt; if (s.t > 0.5f) it.remove() }

        // Beads on the glass: now and then one grows heavy and slides, leaving a trail.
        for (i in beads.indices) {
            val b = beads[i]
            if (!b.sliding && rnd.nextFloat() < dt * 0.04f) { b.sliding = true; b.speed = dp(18f + rnd.nextFloat() * 40f) }
            if (b.sliding) {
                b.y += b.speed * dt
                b.x += sin(time * 3f + i) * dp(4f) * dt
                b.trail = (b.trail + b.speed * dt).coerceAtMost(dp(60f))
                if (b.y > h + b.r) beads[i] = newBead(-b.r - rnd.nextFloat() * h * 0.2f)
            }
        }
    }

    override fun draw(c: Canvas) {
        // Lightning lights up the whole sky for a moment, with a flicker or two.
        val flash = if (flashT < 0) 0f else {
            val f = flashT
            val pulse = when {
                f < 0.06f -> f / 0.06f
                f < 0.12f -> 0.35f
                f < 0.18f -> 0.9f
                f < 0.26f -> 0.3f
                else -> 0.6f * exp(-(f - 0.26f) * 5f)
            }
            pulse * flashPower
        }
        if (flash > 0.01f) {
            paint.shader = LinearGradient(0f, 0f, 0f, h, Color.argb((150 * flash).toInt(), 190, 200, 235),
                Color.argb((40 * flash).toInt(), 120, 130, 160), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
            if (flashT < 0.35f) {
                glowPaint.strokeWidth = dp(7f)
                glowPaint.color = Color.argb((90 * flash).toInt(), 170, 190, 255)
                c.drawPath(bolt, glowPaint)
                glowPaint.strokeWidth = dp(1.8f)
                glowPaint.color = Color.argb((255 * flash).toInt().coerceAtMost(255), 240, 244, 255)
                c.drawPath(bolt, glowPaint)
            }
        }

        // Cool mist near the ground.
        paint.shader = LinearGradient(0f, h * 0.5f, 0f, h, 0x00000000, 0x28607080, Shader.TileMode.CLAMP)
        c.drawRect(0f, h * 0.5f, w, h, paint)
        paint.shader = null

        // Drifting sheets of heavier rain.
        val wind = wind()
        for (k in 0 until 3) {
            val cx = ((time * (dp(20f) + wind * 0.3f) + k * w * 0.45f) % (w * 1.6f)) - w * 0.3f
            val bw = w * 0.35f
            paint.shader = LinearGradient(cx - bw, 0f, cx + bw, 0f,
                intArrayOf(0x00A0B4C8, 0x14A0B4C8, 0x00A0B4C8), null, Shader.TileMode.CLAMP)
            c.drawRect(cx - bw, 0f, cx + bw, h, paint)
        }
        paint.shader = null

        // Drops: a faint long tail and a brighter head, like a camera's motion blur.
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        val lit = 1f + flash * 1.5f
        for (r in drops) {
            val speed = dp(520f + 950f * r.z)
            val len = dp(12f + 26f * r.z)
            val dx = wind * r.z / speed * len
            val a = ((30 + 120 * r.z) * lit).toInt().coerceAtMost(255)
            paint.strokeWidth = dp(0.5f + 1.1f * r.z)
            paint.color = Color.argb(a / 3, 180, 196, 214)
            c.drawLine(r.x, r.y, r.x - dx, r.y - len, paint)
            paint.color = Color.argb(a, 205, 218, 232)
            c.drawLine(r.x, r.y, r.x - dx * 0.35f, r.y - len * 0.35f, paint)
        }

        // Splashes: a flattened ripple and a small crown of droplets.
        for (s in splashes) {
            val p = s.t / 0.5f
            val a = ((1 - p) * 150 * s.z).toInt()
            paint.color = Color.argb(a, 200, 215, 230)
            paint.strokeWidth = dp(0.8f)
            val rw = dp(4f + 16f * p) * s.z
            c.drawOval(s.x - rw, s.y - rw * 0.22f, s.x + rw, s.y + rw * 0.22f, paint)
            paint.style = Paint.Style.FILL
            for (i in s.vx.indices) {
                val t = s.t
                c.drawCircle(s.x + s.vx[i] * t, s.y + s.vy[i] * t + dp(260f) * t * t, dp(0.9f) * s.z + 0.5f, paint)
            }
            paint.style = Paint.Style.STROKE
        }
        paint.style = Paint.Style.FILL

        // Beads of water on the glass, with a bright catch-light; sliding ones leave a trail.
        for (b in beads) {
            if (b.sliding && b.trail > 0) {
                paint.shader = LinearGradient(b.x, b.y - b.trail, b.x, b.y, 0x00C8D8E8, 0x30C8D8E8, Shader.TileMode.CLAMP)
                c.drawRoundRect(b.x - b.r * 0.45f, b.y - b.trail, b.x + b.r * 0.45f, b.y, b.r, b.r, paint)
                paint.shader = null
            }
            paint.color = Color.argb(70, 150, 170, 190)
            c.drawCircle(b.x, b.y, b.r, paint)
            paint.color = Color.argb(160, 235, 242, 250)
            c.drawCircle(b.x - b.r * 0.3f, b.y - b.r * 0.35f, b.r * 0.32f, paint)
        }
    }
}

// ---------------------------------------------------------------------------
// Leaves: autumn leaves that sway, spin and tumble (flip) as they fall.
// ---------------------------------------------------------------------------
private class Leaves(w: Float, h: Float, d: Float) : Ambient(w, h, d) {
    private class Leaf(var x: Float, var y: Float, val size: Float, val color: Int, var spin: Float,
                       val spinSpeed: Float, var phase: Float, val sway: Float, val fall: Float, var flip: Float, val flipSpeed: Float)
    private val leaves = ArrayList<Leaf>()
    private val colors = intArrayOf(0xFFB5562A.toInt(), 0xFFC77A2E.toInt(), 0xFF8E3B24.toInt(), 0xFFD49A3A.toInt(), 0xFF7A6A2E.toInt(), 0xFFA8482A.toInt())
    private val shape = Path().apply {
        // A simple leaf: two curves meeting at the tip, unit size.
        moveTo(0f, -1f)
        cubicTo(0.75f, -0.55f, 0.65f, 0.45f, 0f, 1f)
        cubicTo(-0.65f, 0.45f, -0.75f, -0.55f, 0f, -1f)
        close()
    }
    private val vein = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x55000000 }

    init { repeat(16) { leaves.add(newLeaf(rnd.nextFloat() * h)) } }

    private fun newLeaf(y: Float) = Leaf(
        x = rnd.nextFloat() * w, y = y, size = dp(7f + rnd.nextFloat() * 9f),
        color = colors[rnd.nextInt(colors.size)], spin = rnd.nextFloat() * 360f,
        spinSpeed = (rnd.nextFloat() - 0.5f) * 90f, phase = rnd.nextFloat() * 6.28f,
        sway = dp(18f + rnd.nextFloat() * 30f), fall = dp(28f + rnd.nextFloat() * 36f),
        flip = rnd.nextFloat() * 6.28f, flipSpeed = 1.2f + rnd.nextFloat() * 2f,
    )

    override fun step(dt: Float) {
        for (i in leaves.indices) {
            val l = leaves[i]
            l.phase += dt * 0.9f
            l.y += l.fall * dt
            l.x += cos(l.phase) * l.sway * dt
            l.spin += l.spinSpeed * dt + sin(l.phase) * 20f * dt
            l.flip += l.flipSpeed * dt
            if (l.y > h + l.size * 2) leaves[i] = newLeaf(-l.size * 2)
        }
    }

    override fun draw(c: Canvas) {
        for (l in leaves) {
            c.save()
            c.translate(l.x, l.y)
            c.rotate(l.spin)
            val flip = cos(l.flip) // tumbling: the leaf narrows as it turns edge-on
            c.scale(l.size * max(0.15f, abs(flip)), l.size)
            paint.color = l.color
            paint.alpha = if (flip < 0) 170 else 220 // underside a little darker
            c.drawPath(shape, paint)
            vein.strokeWidth = 0.08f
            c.drawLine(0f, -0.85f, 0f, 1.25f, vein) // midrib and a short stem
            c.restore()
        }
        paint.alpha = 255
    }
}

// ---------------------------------------------------------------------------
// Snow: soft flakes at different depths, drifting on a slow breeze.
// ---------------------------------------------------------------------------
private class Snow(w: Float, h: Float, d: Float) : Ambient(w, h, d) {
    private class Flake(var x: Float, var y: Float, val z: Float, var phase: Float)
    private val flakes = ArrayList<Flake>()
    private val soft: Bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).also { b ->
        Canvas(b).drawCircle(16f, 16f, 16f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(16f, 16f, 16f, intArrayOf(0xFFFFFFFF.toInt(), 0x88FFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        })
    }
    private val dst = android.graphics.RectF()
    private var time = 0f

    init { repeat(110) { flakes.add(Flake(rnd.nextFloat() * w, rnd.nextFloat() * h, 0.2f + rnd.nextFloat() * 0.8f, rnd.nextFloat() * 6.28f)) } }

    override fun step(dt: Float) {
        time += dt
        val breeze = dp(10f) * sin(time * 0.25f)
        for (f in flakes) {
            f.phase += dt * (0.6f + f.z)
            f.y += dp(14f + 40f * f.z) * dt
            f.x += (breeze + sin(f.phase) * dp(10f)) * f.z * dt
            if (f.y > h + 10) { f.y = -10f; f.x = rnd.nextFloat() * w }
            if (f.x < -10) f.x = w + 10 else if (f.x > w + 10) f.x = -10f
        }
    }

    override fun draw(c: Canvas) {
        for (f in flakes) {
            val r = dp(0.8f + 3.2f * f.z)
            paint.alpha = (70 + 170 * f.z).toInt()
            dst.set(f.x - r, f.y - r, f.x + r, f.y + r)
            c.drawBitmap(soft, null, dst, paint)
        }
        paint.alpha = 255
    }
}

// ---------------------------------------------------------------------------
// Fireflies: warm lights that wander slowly and glow on and off.
// ---------------------------------------------------------------------------
private class Fireflies(w: Float, h: Float, d: Float) : Ambient(w, h, d) {
    private class Fly(var x: Float, var y: Float, var heading: Float, var phase: Float, val rate: Float, val speed: Float)
    private val flies = ArrayList<Fly>()
    private val glow: Bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also { b ->
        Canvas(b).drawCircle(32f, 32f, 32f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(32f, 32f, 32f, intArrayOf(0xFFFFF6B0.toInt(), 0x66D8F060, 0x00B8E040), floatArrayOf(0f, 0.25f, 1f), Shader.TileMode.CLAMP)
        })
    }
    private val dst = android.graphics.RectF()

    init {
        repeat(22) {
            flies.add(Fly(rnd.nextFloat() * w, h * 0.25f + rnd.nextFloat() * h * 0.75f, rnd.nextFloat() * 6.28f,
                rnd.nextFloat() * 6.28f, 0.6f + rnd.nextFloat() * 1.2f, dp(8f + rnd.nextFloat() * 14f)))
        }
    }

    override fun step(dt: Float) {
        for (f in flies) {
            f.heading += (rnd.nextFloat() - 0.5f) * 2.4f * dt
            f.x += cos(f.heading) * f.speed * dt
            f.y += sin(f.heading) * f.speed * dt - dp(2f) * dt
            f.phase += f.rate * dt
            if (f.x < 0 || f.x > w) f.heading = PI.toFloat() - f.heading
            if (f.y < h * 0.15f || f.y > h) f.heading = -f.heading
            f.x = f.x.coerceIn(0f, w); f.y = f.y.coerceIn(h * 0.15f, h)
        }
    }

    override fun draw(c: Canvas) {
        // A hint of dark green at the bottom, like grass at night.
        paint.shader = LinearGradient(0f, h * 0.6f, 0f, h, 0x00000000, 0x2A1E3A1A, Shader.TileMode.CLAMP)
        c.drawRect(0f, h * 0.6f, w, h, paint)
        paint.shader = null
        for (f in flies) {
            val on = (0.5f + 0.5f * sin(f.phase)).let { it * it }
            val r = dp(6f + 10f * on)
            paint.alpha = (30 + 225 * on).toInt()
            dst.set(f.x - r, f.y - r, f.x + r, f.y + r)
            c.drawBitmap(glow, null, dst, paint)
        }
        paint.alpha = 255
    }
}

// ---------------------------------------------------------------------------
// Stars: a twinkling sky that turns very slowly, with the odd shooting star.
// ---------------------------------------------------------------------------
private class Stars(w: Float, h: Float, d: Float) : Ambient(w, h, d) {
    private class Star(val x: Float, val y: Float, val r: Float, var phase: Float, val rate: Float, val warm: Boolean)
    private class Meteor(var x: Float, var y: Float, val vx: Float, val vy: Float, var t: Float)
    private val stars = ArrayList<Star>()
    private var meteor: Meteor? = null
    private var nextMeteor = 4f
    private var angle = 0f

    init {
        repeat(140) {
            stars.add(Star(rnd.nextFloat() * w * 1.6f - w * 0.3f, rnd.nextFloat() * h * 1.6f - h * 0.3f,
                dp(0.4f + rnd.nextFloat() * rnd.nextFloat() * 1.6f), rnd.nextFloat() * 6.28f,
                0.5f + rnd.nextFloat() * 2f, rnd.nextFloat() < 0.2f))
        }
    }

    override fun step(dt: Float) {
        angle += dt * 0.25f // degrees per second: barely moving, like the real sky
        for (s in stars) s.phase += s.rate * dt
        nextMeteor -= dt
        meteor?.let { m ->
            m.t += dt; m.x += m.vx * dt; m.y += m.vy * dt
            if (m.t > 1.1f) meteor = null
        }
        if (nextMeteor <= 0 && meteor == null) {
            nextMeteor = 6f + rnd.nextFloat() * 10f
            val sp = dp(420f + rnd.nextFloat() * 200f)
            meteor = Meteor(rnd.nextFloat() * w * 0.8f + w * 0.1f, rnd.nextFloat() * h * 0.35f, -sp * 0.8f, sp * 0.45f, 0f)
        }
    }

    override fun draw(c: Canvas) {
        c.save()
        c.rotate(angle, w / 2, h * 1.2f)
        for (s in stars) {
            val tw = 0.55f + 0.45f * sin(s.phase)
            paint.color = if (s.warm) 0xFFFFE2C0.toInt() else 0xFFE6EEFF.toInt()
            paint.alpha = (60 + 195 * tw).toInt()
            c.drawCircle(s.x, s.y, s.r * (0.8f + 0.3f * tw), paint)
        }
        c.restore()
        meteor?.let { m ->
            val a = (sin(m.t / 1.1f * PI.toFloat()) * 220).toInt().coerceIn(0, 255)
            val tail = 0.12f
            paint.shader = LinearGradient(m.x, m.y, m.x - m.vx * tail, m.y - m.vy * tail,
                Color.argb(a, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            paint.strokeWidth = dp(1.6f)
            paint.strokeCap = Paint.Cap.ROUND
            c.drawLine(m.x, m.y, m.x - m.vx * tail, m.y - m.vy * tail, paint)
            paint.shader = null
        }
        paint.alpha = 255
    }
}
