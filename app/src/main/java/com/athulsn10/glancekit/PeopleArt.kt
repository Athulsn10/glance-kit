package com.athulsn10.glancekit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws the People scenes: two avatars and what's between them (a heart, a paper plane, stars),
 * or a row of family members. [t] (0..1) loops the little animation.
 */
object PeopleArt {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    fun symbol(c: Canvas, kind: String, x: Float, y: Float, s: Float, color: Int) {
        when (kind) {
            "heart" -> AvatarArt.heart(c, x, y, s, color)
            "star" -> AvatarArt.star(c, x, y, s * 0.55f, color)
            "plane" -> {
                val path = Path()
                p.color = color
                path.moveTo(x - s * 0.5f, y); path.lineTo(x + s * 0.5f, y - s * 0.28f); path.lineTo(x - s * 0.05f, y + s * 0.38f); path.close()
                c.drawPath(path, p)
                path.reset(); p.color = darker(color)
                path.moveTo(x - s * 0.05f, y + s * 0.38f); path.lineTo(x + s * 0.5f, y - s * 0.28f); path.lineTo(x + s * 0.02f, y + s * 0.1f); path.close()
                c.drawPath(path, p)
            }
            "home" -> {
                val path = Path()
                p.color = color
                path.moveTo(x - s * 0.5f, y); path.lineTo(x, y - s * 0.45f); path.lineTo(x + s * 0.5f, y); path.close()
                c.drawPath(path, p)
                c.drawRoundRect(x - s * 0.36f, y - s * 0.04f, x + s * 0.36f, y + s * 0.42f, s * 0.06f, s * 0.06f, p)
                p.color = Color.rgb(40, 30, 20)
                c.drawRoundRect(x - s * 0.1f, y + s * 0.14f, x + s * 0.1f, y + s * 0.42f, s * 0.04f, s * 0.04f, p)
            }
        }
    }

    private fun darker(c: Int) = Color.rgb((Color.red(c) * 0.75f).toInt(), (Color.green(c) * 0.75f).toInt(), (Color.blue(c) * 0.75f).toInt())

    private fun glow(c: Canvas, x: Float, y: Float, r: Float, color: Int, alpha: Int) {
        glowP.color = color
        glowP.alpha = alpha
        glowP.maskFilter = BlurMaskFilter(r * 0.6f, BlurMaskFilter.Blur.NORMAL)
        c.drawCircle(x, y, r * 0.7f, glowP)
    }

    /**
     * Two-person scene in a [w]×[h] box (names drawn under the avatars).
     * [density] scales text and strokes.
     */
    fun pair(
        c: Canvas, w: Float, h: Float, density: Float, t: Float,
        circle: Circle, scene: Scene, me: Person?, other: Person?, meName: String = "You",
    ) {
        val head = min(h * 0.46f, w * 0.2f)
        val gy = h * 0.36f
        val bob = sin(t * 2 * PI).toFloat() * head * 0.055f
        val bob2 = sin(t * 2 * PI + 1.4).toFloat() * head * 0.055f
        val ac = circle.accent
        val (moodMe, moodThem) = People.moods(circle, scene, me, other)
        val avMe = me?.avatar ?: Avatar()
        val avThem = other?.avatar ?: Avatar(gender = "f", style = "long")
        val couple = circle == Circle.COUPLE
        val traveller = if (couple) "heart" else if (circle == Circle.FRIENDS) "plane" else "home"
        val xa: Float
        val xb: Float
        when (scene) {
            Scene.TOGETHER -> {
                xa = w / 2 - head * 0.6f; xb = w / 2 + head * 0.6f
                glow(c, w / 2, gy + head * 0.1f, head * 1.1f, ac, 70)
                if (couple) {
                    AvatarArt.draw(c, xa, gy + bob * 0.4f, head, avMe, moodMe, Arms.HUG, tilt = -7f)
                    AvatarArt.draw(c, xb, gy + bob * 0.4f, head, avThem, moodThem, Arms.HUG, tilt = 7f)
                } else {
                    AvatarArt.draw(c, xa - head * 0.2f, gy + bob * 0.4f, head, avMe, moodMe, Arms.WAVE, tilt = -4f)
                    AvatarArt.draw(c, xb + head * 0.2f, gy + bob2 * 0.4f, head, avThem, moodThem, Arms.WAVE, tilt = 4f)
                }
                for (k in 0 until 4) {
                    val q = (t + k / 4f) % 1f
                    val hx = w / 2 + sin((q + k) * 6.0).toFloat() * head * 0.36f + (k - 1.5f) * head * 0.16f
                    val hy = gy - head * 0.45f - q * head * 0.9f
                    val col = if (couple) ac else if (k % 2 == 0) ac else circle.soft
                    val sz = head * (0.2f + 0.13f * (1 - q))
                    c.saveLayerAlpha(0f, 0f, w, h, (255 * (1 - q)).toInt())
                    symbol(c, if (couple) "heart" else "star", hx, hy, sz, col)
                    c.restore()
                }
            }
            Scene.NEAR, Scene.CITY, Scene.FAR -> {
                val gap = when (scene) { Scene.NEAR -> w * 0.34f; Scene.CITY -> w * 0.55f; else -> w * 0.7f }
                xa = w / 2 - gap / 2; xb = w / 2 + gap / 2
                val yl = gy + head * 0.09f
                dash.color = Color.rgb(70, 70, 70)
                dash.strokeWidth = 2f * density
                dash.pathEffect = DashPathEffect(floatArrayOf(5 * density, 5 * density), 0f)
                c.drawLine(xa + head * 0.66f, yl, xb - head * 0.66f, yl, dash)
                val wave = scene == Scene.NEAR || (!couple && scene != Scene.CITY)
                AvatarArt.draw(c, xa, gy + bob, head, avMe, moodMe, if (couple && scene == Scene.FAR) Arms.HEART else if (wave) Arms.WAVE else Arms.DOWN, look = 1f)
                AvatarArt.draw(c, xb, gy + bob2, head, avThem, moodThem, Arms.DOWN, look = -1f)
                val hx: Float
                val hy: Float
                if (scene == Scene.NEAR) {
                    hx = w / 2 + sin(t * 2 * PI).toFloat() * (gap / 2 - head * 0.75f)
                    hy = yl - head * 0.24f - abs(sin(t * 4 * PI)).toFloat() * head * 0.24f
                } else {
                    hx = xa + head * 0.66f + (xb - xa - head * 1.32f) * t
                    hy = yl - sin(t * PI).toFloat() * head * (if (scene == Scene.FAR) 0.42f else 0.22f)
                }
                glow(c, hx, hy, head * 0.26f, ac, 130)
                symbol(c, traveller, hx, hy, head * 0.32f, ac)
            }
            Scene.RECEIVED -> {
                xa = w / 2 - w * 0.2f; xb = w / 2 + w * 0.2f
                val wig = sin(t * 2 * PI * 4).toFloat() * head * 0.07f
                AvatarArt.draw(c, xa, gy + bob * 0.4f, head, avMe, moodMe)
                AvatarArt.draw(c, xb + wig, gy, head, avThem, moodThem, if (couple) Arms.HEART else Arms.WAVE, tilt = wig * 0.8f / density)
                dash.pathEffect = null
                dash.color = ac
                dash.strokeWidth = 2.2f * density
                for (sgn in intArrayOf(-1, 1)) for (k in 0 until 2) {
                    val x0 = xb + sgn * (head * 0.78f + k * head * 0.15f)
                    c.drawArc(x0 - head * 0.15f, gy - head * 0.24f, x0 + head * 0.15f, gy + head * 0.24f,
                        if (sgn > 0) 300f else 120f, 120f, false, dash)
                }
                val s = head * (0.4f + 0.09f * abs(sin(t * 2 * PI * 2)).toFloat())
                glow(c, w / 2, gy, head * 0.45f, ac, 130)
                symbol(c, if (couple) "heart" else if (circle == Circle.FRIENDS) "star" else "home", w / 2, gy, s, if (circle == Circle.FRIENDS) circle.soft else ac)
            }
            else -> { // PAUSED, WAITING
                xa = w / 2 - w * 0.2f; xb = w / 2 + w * 0.2f
                AvatarArt.draw(c, xa, gy + bob * 0.4f, head, avMe, moodMe)
                if (other != null || scene == Scene.PAUSED) {
                    AvatarArt.draw(c, xb, gy + head * 0.04f, head, avThem, moodThem)
                    text.color = Color.rgb(170, 170, 210)
                    text.typeface = Typeface.DEFAULT_BOLD
                    val za = (150 + 100 * sin(t * 2 * PI)).toInt()
                    for ((i, k) in floatArrayOf(1f, 0.75f, 0.55f).withIndex()) {
                        text.textSize = head * 0.28f * k
                        text.alpha = za
                        c.drawText("z", xb + head * 0.45f + i * head * 0.15f, gy - head * 0.6f - i * head * 0.17f - t * head * 0.07f, text)
                    }
                } else {
                    // An empty seat waiting for someone: a dashed circle with a plus.
                    dash.color = Color.rgb(80, 80, 80)
                    dash.strokeWidth = 2f * density
                    dash.pathEffect = DashPathEffect(floatArrayOf(6 * density, 6 * density), t * 12 * density)
                    c.drawCircle(xb, gy, head * 0.5f, dash)
                    dash.pathEffect = null
                    c.drawLine(xb - head * 0.15f, gy, xb + head * 0.15f, gy, dash)
                    c.drawLine(xb, gy - head * 0.15f, xb, gy + head * 0.15f, dash)
                }
            }
        }
        // names
        text.alpha = 255
        text.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        text.textSize = 11.5f * density
        text.color = Color.rgb(110, 110, 110)
        val ny = gy + head * 0.89f + 14 * density
        c.drawText(meName, xa, ny, text)
        if (other != null) c.drawText(other.name, xb, ny, text)
    }

    /** Family: a row of up to five members, each with "home" or their distance from you. */
    fun family(c: Canvas, w: Float, h: Float, density: Float, t: Float, circle: Circle, g: Group, me: Person?, others: List<Person>) {
        val everyone = listOfNotNull(me) + others.take(4)
        val n = everyone.size.coerceAtLeast(1)
        val head = min(h * 0.38f, w / n * 0.62f)
        val gy = h * 0.32f
        val now = System.currentTimeMillis()
        everyone.forEachIndexed { i, person ->
            val x = w * (i + 0.5f) / n
            val b = sin(t * 2 * PI + i).toFloat() * head * 0.05f
            val received = g.lastFrom == person.uid && now - g.lastAt < People.RECEIVED_MS
            val mood = person.moodNow() ?: when {
                received -> Mood.LOVE
                person.paused || now - person.at > People.STALE_MS -> Mood.SLEEP
                person.atHome -> Mood.HAPPY
                else -> Mood.EXCITED
            }
            AvatarArt.draw(c, x, gy + b, head, person.avatar, mood, if (received) Arms.HEART else Arms.DOWN)
            if (received) {
                val s = head * (0.32f + 0.06f * abs(sin(t * 2 * PI * 2)).toFloat())
                glow(c, x + head * 0.55f, gy - head * 0.62f, head * 0.3f, circle.accent, 140)
                symbol(c, "home", x + head * 0.55f, gy - head * 0.64f, s, circle.accent)
            }
            text.alpha = 255
            text.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            text.textSize = 11.5f * density
            text.color = Color.rgb(150, 150, 150)
            val ny = gy + head * 0.89f + 13 * density
            c.drawText(if (person === me) "You" else person.name, x, ny, text)
            // chip
            val label = when {
                person.atHome && !person.paused -> "home"
                person === me -> "you"
                else -> People.distance(me, person)?.let { People.fmtDistance(it) } ?: "—"
            }
            text.textSize = 10f * density
            text.typeface = Typeface.DEFAULT_BOLD
            val tw = text.measureText(label) + (if (label == "home") 22 else 14) * density
            val cy = ny + 14 * density
            p.color = Color.rgb(32, 32, 32)
            c.drawRoundRect(x - tw / 2, cy - 9 * density, x + tw / 2, cy + 9 * density, 9 * density, 9 * density, p)
            if (label == "home") {
                symbol(c, "home", x - tw / 2 + 10 * density, cy - 1 * density, 10 * density, circle.soft)
                text.color = circle.soft
                c.drawText(label, x + 5 * density, cy + 3.5f * density, text)
            } else {
                text.color = Color.WHITE
                c.drawText(label, x, cy + 3.5f * density, text)
            }
        }
    }

    /** Draw whichever scene fits the group. */
    fun drawGroup(c: Canvas, w: Float, h: Float, density: Float, t: Float, g: Group, me: Person?, others: List<Person>) {
        if (g.circle == Circle.FAMILY) family(c, w, h, density, t, g.circle, g, me, others)
        else {
            val other = others.firstOrNull()
            pair(c, w, h, density, t, g.circle, People.scene(g, me, other), me, other)
        }
    }

    /**
     * A few frames of the loop, for the widget's flipper. Kept small (RGB_565, capped scale)
     * so all frames together stay well under the widget size limit.
     */
    fun frames(ctx: Context, wDp: Float, hDp: Float, g: Group, me: Person?, others: List<Person>, n: Int = 3): List<Bitmap> {
        val scale = min(ctx.resources.displayMetrics.density, 2f)
        val w = (wDp * scale).toInt().coerceAtLeast(60)
        val h = (hDp * scale).toInt().coerceAtLeast(40)
        return (0 until n).map { i ->
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            b.eraseColor(RingRenderer.BG)
            drawGroup(Canvas(b), w.toFloat(), h.toFloat(), scale, i / n.toFloat(), g, me, others)
            b
        }
    }
}

/** The live, smoothly animated scene used inside the app. Animates only while on screen. */
class PeopleSceneView(ctx: Context) : View(ctx) {
    var group: Group? = null
    var me: Person? = null
    var others: List<Person> = emptyList()
    private val vis = android.graphics.Rect()

    private val tick = object : Runnable {
        override fun run() {
            if (isShown && getGlobalVisibleRect(vis)) invalidate()
            postDelayed(this, if (isShown) 40L else 1000L)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); removeCallbacks(tick); post(tick) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val g = group ?: return
        val t = (SystemClock.uptimeMillis() % 2400L) / 2400f
        PeopleArt.drawGroup(canvas, width.toFloat(), height.toFloat(), resources.displayMetrics.density, t, g, me, others)
    }
}
