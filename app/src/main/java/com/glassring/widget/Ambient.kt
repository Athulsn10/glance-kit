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
// Rain: three depths of streaks, slanted by a soft wind, with splashes and ripples.
// ---------------------------------------------------------------------------
private class Rain(w: Float, h: Float, d: Float) : Ambient(w, h, d) {
    private class Drop(var x: Float, var y: Float, val z: Float)
    private class Splash(var x: Float, var y: Float, var t: Float, val z: Float)
    private val drops = ArrayList<Drop>()
    private val splashes = ArrayList<Splash>()
    private var time = 0f
    private val ground get() = h - dp(6f)

    init {
        repeat(150) { drops.add(Drop(rnd.nextFloat() * w * 1.2f - w * 0.1f, rnd.nextFloat() * h, 0.25f + rnd.nextFloat() * 0.75f)) }
    }

    private fun wind() = dp(60f) + dp(30f) * sin(time * 0.3f)

    override fun step(dt: Float) {
        time += dt
        val wind = wind()
        for (r in drops) {
            val speed = dp(520f + 900f * r.z)
            r.y += speed * dt
            r.x += wind * r.z * dt
            if (r.y > ground - (1 - r.z) * h * 0.25f) {
                if (r.z > 0.55f && splashes.size < 40) splashes.add(Splash(r.x, r.y, 0f, r.z))
                r.y = -rnd.nextFloat() * h * 0.3f
                r.x = rnd.nextFloat() * w * 1.2f - w * 0.1f
            }
        }
        val it = splashes.iterator()
        while (it.hasNext()) { val s = it.next(); s.t += dt; if (s.t > 0.45f) it.remove() }
    }

    override fun draw(c: Canvas) {
        // A faint cool mist towards the bottom.
        paint.shader = LinearGradient(0f, h * 0.55f, 0f, h, 0x00000000, 0x22607080, Shader.TileMode.CLAMP)
        c.drawRect(0f, h * 0.55f, w, h, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        val wind = wind()
        for (r in drops) {
            val speed = dp(520f + 900f * r.z)
            val len = dp(10f + 22f * r.z)
            val dx = wind * r.z / speed * len
            paint.strokeWidth = dp(0.6f + 1.1f * r.z)
            paint.color = Color.argb((40 + 120 * r.z).toInt(), 190, 205, 220)
            c.drawLine(r.x, r.y, r.x - dx, r.y - len, paint)
        }
        for (s in splashes) {
            val p = s.t / 0.45f
            val a = ((1 - p) * 140 * s.z).toInt()
            paint.color = Color.argb(a, 200, 215, 230)
            paint.strokeWidth = dp(0.8f)
            // Ripple ring, flattened like water seen at an angle.
            val rw = dp(4f + 14f * p) * s.z
            c.drawOval(s.x - rw, s.y - rw * 0.25f, s.x + rw, s.y + rw * 0.25f, paint)
            // Two tiny droplets thrown up.
            val up = dp(10f) * s.z * sin(p * PI.toFloat())
            paint.style = Paint.Style.FILL
            c.drawCircle(s.x - dp(4f) * p, s.y - up, dp(0.9f), paint)
            c.drawCircle(s.x + dp(5f) * p, s.y - up * 0.8f, dp(0.8f), paint)
            paint.style = Paint.Style.STROKE
        }
        paint.style = Paint.Style.FILL
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
