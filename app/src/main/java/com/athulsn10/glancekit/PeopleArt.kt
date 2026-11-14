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
            "kiss" -> { // a lipstick mark
                p.color = color
                c.drawOval(x - s * 0.5f, y - s * 0.34f, x + s * 0.04f, y + s * 0.08f, p)
                c.drawOval(x - s * 0.04f, y - s * 0.34f, x + s * 0.5f, y + s * 0.08f, p)
                c.drawOval(x - s * 0.42f, y - s * 0.2f, x + s * 0.42f, y + s * 0.36f, p)
                p.color = darker(color)
                c.drawRoundRect(x - s * 0.36f, y - s * 0.03f, x + s * 0.36f, y + s * 0.04f, s * 0.03f, s * 0.03f, p)
            }
            "hand" -> { // an open palm
                p.color = color
                c.drawRoundRect(x - s * 0.3f, y - s * 0.05f, x + s * 0.3f, y + s * 0.5f, s * 0.14f, s * 0.14f, p)
                for (k in 0 until 4) {
                    val fx = x - s * 0.3f + k * s * 0.155f
                    val top = y - s * (if (k == 1 || k == 2) 0.5f else 0.4f)
                    c.drawRoundRect(fx, top, fx + s * 0.135f, y + s * 0.15f, s * 0.07f, s * 0.07f, p)
                }
                c.save(); c.rotate(40f, x + s * 0.3f, y + s * 0.2f)
                c.drawRoundRect(x + s * 0.22f, y - s * 0.12f, x + s * 0.37f, y + s * 0.26f, s * 0.07f, s * 0.07f, p)
                c.restore()
            }
            "spark" -> { // a four-point burst
                val path = Path()
                p.color = color
                for (i in 0 until 8) {
                    val a = -PI / 2 + i * PI / 4
                    val rr = if (i % 2 == 0) s * 0.5f else s * 0.16f
                    val px = x + (rr * kotlin.math.cos(a)).toFloat()
                    val py = y + (rr * sin(a)).toFloat()
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close(); c.drawPath(path, p)
            }
            "bubble" -> { // a speech bubble with three dots
                val path = Path()
                p.color = color
                c.drawRoundRect(x - s * 0.5f, y - s * 0.4f, x + s * 0.5f, y + s * 0.24f, s * 0.2f, s * 0.2f, p)
                path.moveTo(x - s * 0.24f, y + s * 0.2f); path.lineTo(x - s * 0.3f, y + s * 0.48f); path.lineTo(x + s * 0.02f, y + s * 0.2f); path.close()
                c.drawPath(path, p)
                p.color = Color.rgb(30, 30, 30)
                for (k in -1..1) c.drawCircle(x + k * s * 0.22f, y - s * 0.08f, s * 0.07f, p)
            }
            "phone" -> { // a handset
                p.color = color
                c.save(); c.rotate(-35f, x, y)
                c.drawRoundRect(x - s * 0.46f, y - s * 0.12f, x + s * 0.46f, y + s * 0.1f, s * 0.1f, s * 0.1f, p)
                c.drawRoundRect(x - s * 0.5f, y - s * 0.12f, x - s * 0.2f, y + s * 0.3f, s * 0.1f, s * 0.1f, p)
                c.drawRoundRect(x + s * 0.2f, y - s * 0.12f, x + s * 0.5f, y + s * 0.3f, s * 0.1f, s * 0.1f, p)
                c.restore()
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

    /** What the in-app scene adds on top of the loop: blinking, and a hop when an avatar is tapped. */
    class Live(val clock: Long = 0L, val tapSide: Int = 0, val tapT: Float = 1f)

    /** The path the heart / plane travels between two avatars that are apart. */
    class Track(val xa: Float, val xb: Float, val x0: Float, val x1: Float, val y: Float, val head: Float, val sway: Boolean, val arc: Float)

    fun headSize(w: Float, h: Float) = min(h * 0.46f, w * 0.2f)

    fun track(w: Float, h: Float, scene: Scene): Track? {
        val gap = when (scene) { Scene.NEAR -> w * 0.34f; Scene.CITY -> w * 0.55f; Scene.FAR -> w * 0.7f; else -> return null }
        val head = headSize(w, h)
        val xa = w / 2 - gap / 2
        val xb = w / 2 + gap / 2
        val yl = h * 0.36f + head * 0.09f
        return if (scene == Scene.NEAR) {
            val amp = (gap / 2 - head * 0.75f).coerceAtLeast(w * 0.015f)
            Track(xa, xb, w / 2 - amp, w / 2 + amp, yl - head * 0.3f, head, true, head * 0.12f)
        } else {
            Track(xa, xb, xa + head * 0.66f, xb - head * 0.66f, yl, head, false, head * (if (scene == Scene.FAR) 0.42f else 0.22f))
        }
    }

    fun travellerKind(circle: Circle) = when (circle) { Circle.COUPLE -> "heart"; Circle.FRIENDS -> "plane"; else -> "home" }

    /** The symbol for an action, e.g. on the widget when one arrives. */
    fun actionSymbol(key: String?) = when (key) {
        "kiss" -> "kiss"; "highfive" -> "hand"; "poke" -> "spark"; "hangout" -> "bubble"
        "home" -> "home"; "call" -> "phone"; else -> "heart"
    }

    /**
     * Two-person scene in a [w]×[h] box (names drawn under the avatars).
     * [density] scales text and strokes. With [traveller] off, the heart / plane between two
     * people who are apart is left out (the widget animates it as its own layer).
     */
    fun pair(
        c: Canvas, w: Float, h: Float, density: Float, t: Float,
        circle: Circle, scene: Scene, me: Person?, other: Person?, meName: String = "You",
        action: PeopleAction? = null, traveller: Boolean = true, live: Live? = null,
    ) {
        val head = headSize(w, h)
        val gy = h * 0.36f
        val bob = sin(t * 2 * PI).toFloat() * head * 0.055f
        val bob2 = sin(t * 2 * PI + 1.4).toFloat() * head * 0.055f
        val ac = circle.accent
        val (moodMe, moodThem) = People.moods(circle, scene, me, other)
        val avMe = me?.avatar ?: Avatar()
        val avThem = other?.avatar ?: Avatar(gender = "f", style = "long")
        val couple = circle == Circle.COUPLE

        // side: -1 is me (left), 1 is them (right).
        fun who(side: Int, x: Float, y: Float, mood: Mood, arms: Arms = Arms.DOWN, look: Float = 0f, tilt: Float = 0f) {
            val tapped = live != null && live.tapSide == side && live.tapT < 1f
            val hop = if (live != null && tapped) abs(sin(live.tapT * 2 * PI)).toFloat() * (1 - live.tapT) * head * 0.3f else 0f
            val blink = live != null && (live.clock + (if (side < 0) 0L else 1700L)) % (if (side < 0) 3800L else 4300L) < 140L
            AvatarArt.draw(c, x, y - hop, head, if (side < 0) avMe else avThem,
                if (tapped) Mood.LAUGH else mood, if (tapped) Arms.UP else arms, look, tilt, blink)
        }

        val xa: Float
        val xb: Float
        when (scene) {
            Scene.TOGETHER -> {
                xa = w / 2 - head * 0.6f; xb = w / 2 + head * 0.6f
                glow(c, w / 2, gy + head * 0.1f, head * 1.1f, ac, 70)
                if (couple) {
                    who(-1, xa, gy + bob * 0.4f, moodMe, Arms.HUG, tilt = -7f)
                    who(1, xb, gy + bob * 0.4f, moodThem, Arms.HUG, tilt = 7f)
                } else {
                    who(-1, xa - head * 0.2f, gy + bob * 0.4f, moodMe, Arms.WAVE, tilt = -4f)
                    who(1, xb + head * 0.2f, gy + bob2 * 0.4f, moodThem, Arms.WAVE_L, tilt = 4f)
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
                val tr = track(w, h, scene)!!
                xa = tr.xa; xb = tr.xb
                val yl = gy + head * 0.09f
                dash.color = Color.rgb(70, 70, 70)
                dash.strokeWidth = 2f * density
                dash.pathEffect = DashPathEffect(floatArrayOf(5 * density, 5 * density), 0f)
                c.drawLine(xa + head * 0.66f, yl, xb - head * 0.66f, yl, dash)
                val wave = scene == Scene.NEAR || (!couple && scene != Scene.CITY)
                who(-1, xa, gy + bob, moodMe, if (couple && scene == Scene.FAR) Arms.HEART else if (wave) Arms.WAVE else Arms.DOWN, look = 1f)
                who(1, xb, gy + bob2, moodThem, Arms.DOWN, look = -1f)
                if (traveller) {
                    val hx: Float
                    val hy: Float
                    if (tr.sway) {
                        hx = w / 2 + sin(t * 2 * PI).toFloat() * (tr.x1 - tr.x0) / 2
                        hy = tr.y - abs(sin(t * 4 * PI)).toFloat() * head * 0.24f + head * 0.06f
                    } else {
                        hx = tr.x0 + (tr.x1 - tr.x0) * t
                        hy = tr.y - sin(t * PI).toFloat() * tr.arc
                    }
                    // Fade in at the start of the trip and out on arrival, so the loop has no jump.
                    val fade = if (tr.sway) 1f else min(1f, min(t / 0.1f, (1f - t) / 0.12f))
                    c.saveLayerAlpha(0f, 0f, w, h, (255 * fade).toInt().coerceIn(0, 255))
                    glow(c, hx, hy, head * 0.26f, ac, 130)
                    symbol(c, travellerKind(circle), hx, hy, head * 0.32f, ac)
                    c.restore()
                }
            }
            Scene.RECEIVED -> {
                xa = w / 2 - w * 0.2f; xb = w / 2 + w * 0.2f
                val wig = sin(t * 2 * PI * 4).toFloat() * head * 0.07f
                val key = action?.key
                // How each of us looks for what just arrived. A mood set for today still wins.
                val mine = me?.moodNow() ?: when (key) {
                    "hug", "kiss" -> Mood.SHY; "miss" -> Mood.LOVE; "highfive", "hangout" -> Mood.EXCITED; "poke" -> Mood.WOW
                    else -> moodMe
                }
                val theirs = other?.moodNow() ?: when (key) {
                    "hug" -> Mood.LOVE; "kiss" -> Mood.KISS; "miss" -> Mood.MISS; "highfive" -> Mood.LAUGH; "poke" -> Mood.WINK
                    "hangout" -> Mood.HAPPY; else -> moodThem
                }
                val theirArms = when (key) {
                    "hug" -> Arms.HUG; "highfive", "poke", "hangout" -> Arms.WAVE_L; "kiss" -> Arms.DOWN
                    else -> if (couple) Arms.HEART else Arms.WAVE_L
                }
                who(-1, xa, gy + bob * 0.4f, mine, if (key == "highfive") Arms.WAVE else Arms.DOWN)
                who(1, xb + wig, gy, theirs, theirArms, tilt = wig * 0.8f / density)
                if (key == "kiss") symbol(c, "kiss", xa + head * 0.31f, gy + head * 0.2f, head * 0.2f, 0xFFE8506E.toInt())
                dash.pathEffect = null
                dash.color = ac
                dash.strokeWidth = 2.2f * density
                for (sgn in intArrayOf(-1, 1)) for (k in 0 until 2) {
                    val x0 = xb + sgn * (head * 0.78f + k * head * 0.15f)
                    c.drawArc(x0 - head * 0.15f, gy - head * 0.24f, x0 + head * 0.15f, gy + head * 0.24f,
                        if (sgn > 0) 300f else 120f, 120f, false, dash)
                }
                val s = head * (0.4f + 0.09f * abs(sin(t * 2 * PI * 2)).toFloat())
                val col = action?.color ?: if (circle == Circle.FRIENDS) circle.soft else ac
                glow(c, w / 2, gy, head * 0.45f, col, 130)
                symbol(c, if (action != null) actionSymbol(key) else if (couple) "heart" else if (circle == Circle.FRIENDS) "star" else "home", w / 2, gy, s, col)
            }
            else -> { // PAUSED, WAITING
                xa = w / 2 - w * 0.2f; xb = w / 2 + w * 0.2f
                who(-1, xa, gy + bob * 0.4f, moodMe)
                if (other != null || scene == Scene.PAUSED) {
                    who(1, xb, gy + head * 0.04f, moodThem)
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
    fun drawGroup(
        c: Canvas, w: Float, h: Float, density: Float, t: Float, g: Group, me: Person?, others: List<Person>,
        traveller: Boolean = true, live: Live? = null,
    ) {
        if (g.circle == Circle.FAMILY) family(c, w, h, density, t, g.circle, g, me, others)
        else {
            val other = others.firstOrNull()
            pair(c, w, h, density, t, g.circle, People.scene(g, me, other), me, other,
                action = g.circle.action(g.lastAction), traveller = traveller, live = live)
        }
    }

    private fun frameScale(ctx: Context) = min(ctx.resources.displayMetrics.density, 1.5f)

    /**
     * Frames of the loop for the widget's flipper, which cross-fades them. Kept small (RGB_565,
     * capped scale, fewer frames when the widget is big) so they stay well under the widget size
     * limit. The heart / plane between two people who are apart is not drawn here: see [fly].
     */
    fun frames(ctx: Context, wDp: Float, hDp: Float, g: Group, me: Person?, others: List<Person>, n: Int = 6): List<Bitmap> {
        val scale = frameScale(ctx)
        val w = (wDp * scale).toInt().coerceAtLeast(60)
        val h = (hDp * scale).toInt().coerceAtLeast(40)
        val count = min(n, (FRAME_BUDGET / (w * h * 2)).coerceAtLeast(3))
        return (0 until count).map { i ->
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            b.eraseColor(RingRenderer.BG)
            drawGroup(Canvas(b), w.toFloat(), h.toFloat(), scale, i / count.toFloat(), g, me, others, traveller = false)
            b
        }
    }

    private const val FRAME_BUDGET = 3_000_000 // bytes for all frames of one widget

    /**
     * The travelling heart / plane as its own small picture, with where it goes (in dp, measured
     * from the middle of the scene). The launcher moves it with a view animation, so it glides at
     * the screen's refresh rate instead of jumping between a few frames.
     */
    class Fly(val bitmap: Bitmap, val sizeDp: Float, val trackDp: Float, val aboveCentreDp: Float, val sway: Boolean)

    fun fly(ctx: Context, wDp: Float, hDp: Float, g: Group, me: Person?, others: List<Person>): Fly? {
        if (g.circle == Circle.FAMILY) return null
        val scale = frameScale(ctx)
        val w = (wDp * scale).toInt().coerceAtLeast(60).toFloat()
        val h = (hDp * scale).toInt().coerceAtLeast(40).toFloat()
        val tr = track(w, h, People.scene(g, me, others.firstOrNull())) ?: return null
        val size = (tr.head * 0.9f).toInt().coerceAtLeast(12)
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        glow(c, size / 2f, size / 2f, tr.head * 0.26f, g.circle.accent, 130)
        symbol(c, travellerKind(g.circle), size / 2f, size / 2f, tr.head * 0.32f, g.circle.accent)
        return Fly(b, size / scale, (tr.x1 - tr.x0) / scale, (h / 2 - tr.y) / scale, tr.sway)
    }
}

/** The live, smoothly animated scene used inside the app. Animates only while on screen. */
class PeopleSceneView(ctx: Context) : View(ctx) {
    var group: Group? = null
    var me: Person? = null
    var others: List<Person> = emptyList()
    private val vis = android.graphics.Rect()
    private var tapSide = 0
    private var tapAt = 0L
    private var checked = false

    private val tick = object : Runnable {
        override fun run() {
            if (isShown && getGlobalVisibleRect(vis)) {
                invalidate()
                // Something arrived that hasn't been played on this phone yet: play it once, full
                // screen, the first time this scene is really on screen (not a neighbouring tab).
                if (!checked && vis.width() > width / 2) {
                    checked = true
                    val g = group
                    val m = me
                    if (g != null && m != null) PeopleFxOverlay.playIncoming(this@PeopleSceneView, g, m, others)
                }
            }
            postDelayed(this, if (isShown) 16L else 1000L)
        }
    }

    init {
        // Tap an avatar and it hops and laughs.
        setOnTouchListener { _, e ->
            if (e.action == android.view.MotionEvent.ACTION_DOWN && group?.circle != Circle.FAMILY) {
                tapSide = if (e.x < width / 2f) -1 else 1
                tapAt = SystemClock.uptimeMillis()
                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            }
            false
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); removeCallbacks(tick); post(tick) }

    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val g = group ?: return
        val now = SystemClock.uptimeMillis()
        val t = (now % 2400L) / 2400f
        val live = PeopleArt.Live(now, tapSide, ((now - tapAt) / 900f).coerceIn(0f, 1f))
        PeopleArt.drawGroup(canvas, width.toFloat(), height.toFloat(), resources.displayMetrics.density, t, g, me, others, live = live)
    }
}
