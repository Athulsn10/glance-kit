package com.athulsn10.glancekit

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Remembers the last two actions in each group, on this phone only, so we can tell when one is
 * answered with the same thing ("hug returned"). Nothing here is sent anywhere.
 */
object PeopleHistory {
    const val RETURN_MS = 30 * 60 * 1000L

    class Event(val from: String, val action: String, val at: Long)

    private fun key(gid: String) = "people_hist_$gid"

    /** Newest first. */
    fun events(c: Context, gid: String): List<Event> =
        (Store.prefs(c).getString(key(gid), "") ?: "").split(';').mapNotNull { s ->
            val p = s.split('|')
            val at = p.getOrNull(2)?.toLongOrNull()
            if (p.size == 3 && at != null) Event(p[0], p[1], at) else null
        }

    fun note(c: Context, gid: String, from: String, action: String, at: Long = System.currentTimeMillis()) {
        val list = events(c, gid)
        val top = list.firstOrNull()
        // The same action can be seen twice (a push, then the cloud copy a moment later).
        if (top != null && top.from == from && top.action == action && abs(top.at - at) < 20_000) return
        val keep = (listOf(Event(from, action, at)) + list).take(2)
        Store.prefs(c).edit().putString(key(gid), keep.joinToString(";") { "${it.from}|${it.action}|${it.at}" }).apply()
    }

    /** True when [from] doing [action] at [at] answers the same action from someone else. */
    fun returned(c: Context, gid: String, from: String, action: String, at: Long = System.currentTimeMillis()): Boolean =
        events(c, gid).any { it.from != from && it.action == action && at - it.at > -60_000 && at - it.at < RETURN_MS }

    private fun seenKey(gid: String) = "people_fx_seen_$gid"
    fun seen(c: Context, gid: String) = Store.prefs(c).getLong(seenKey(gid), 0L)
    fun markSeen(c: Context, gid: String, at: Long) = Store.prefs(c).edit().putLong(seenKey(gid), at).apply()
}

/**
 * The full-screen moment for each action: the two avatars act it out (run in for a hug, a kiss
 * on the cheek, a jumping high five...). Kept light on purpose: the avatars carry it, with
 * at most a handful of hearts or stars and a small screen shake. Everything is drawn from [p] (0..1), so it's the same on every phone and easy to test.
 * "Me" is always on the left, as in the scene on the People tab.
 */
object PeopleFx {

    class Spec(
        val circle: Circle, val action: PeopleAction,
        val me: Avatar, val them: Avatar, val themName: String,
        val sentByMe: Boolean, val returned: Boolean,
    )

    fun duration(s: Spec) = if (s.returned) 4200L else 3600L

    /** Whole-overlay opacity: fades in and out at the ends. */
    fun fade(p: Float) = min(seg(p, 0f, 0.07f), 1f - seg(p, 0.9f, 1f))

    /** Words under the avatars. */
    fun caption(s: Spec): Pair<String, String> {
        val name = s.themName.ifBlank { "them" }
        val big = if (!s.sentByMe) "${s.themName.ifBlank { "Someone" }} ${s.action.past}" else when (s.action.key) {
            "hug" -> "You sent a hug"
            "miss" -> "You miss $name"
            "kiss" -> "You sent a kiss"
            "highfive" -> "You sent a high five"
            "poke" -> "You poked $name"
            "hangout" -> "You asked to hang out"
            "home" -> "You're home safe"
            "call" -> "You asked for a call"
            "love" -> "You sent love"
            else -> "Sent"
        }
        val small = when {
            s.returned && s.sentByMe -> "Right back at $name"
            s.returned -> "Right back at you"
            s.sentByMe -> "On its way to $name"
            else -> ""
        }
        return big to small
    }

    /** When things collide (as fractions of the timeline): these shake and flash the screen. */
    private fun impacts(key: String, returned: Boolean): FloatArray {
        val base = when (key) {
            "hug" -> floatArrayOf(0.44f)
            "highfive" -> floatArrayOf(0.46f)
            "poke" -> floatArrayOf(0.42f, 0.56f)
            "kiss" -> floatArrayOf(0.5f)
            "home", "love" -> floatArrayOf(0.5f)
            else -> floatArrayOf()
        }
        return if (returned && key != "poke") base + 0.66f else base
    }

    /** Sideways screen shake in dp at [p]. Soft actions don't shake. */
    fun shake(p: Float, s: Spec): Float {
        val amp = when (s.action.key) { "highfive" -> 9f; "poke" -> 7f; "hug" -> 5f; else -> 0f }
        if (amp == 0f) return 0f
        var out = 0f
        for (ti in impacts(s.action.key, s.returned)) {
            val d = p - ti
            if (d > 0f && d < 0.07f) out += sin(d * 260f) * (1f - d / 0.07f) * amp
        }
        return out
    }

    // ---------- timeline helpers ----------
    private fun seg(p: Float, a: Float, b: Float) = ((p - a) / (b - a)).coerceIn(0f, 1f)
    private fun ease(x: Float) = x * x * (3f - 2f * x)
    private fun outCubic(x: Float) = 1f - (1f - x) * (1f - x) * (1f - x)
    /** Overshoots a little, then settles: for things that pop in. */
    private fun pop(x: Float): Float { val y = x - 1f; return 1f + 2.7f * y * y * y + 1.7f * y * y }
    private fun bump(x: Float) = sin(x * PI).toFloat()
    private fun hops(x: Float, n: Int) = if (x <= 0f || x >= 1f) 0f else abs(sin(x * PI * n)).toFloat()
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun alpha(color: Int, a: Float) = Color.argb((255 * a.coerceIn(0f, 1f)).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    /** A repeatable "random" 0..1 for particle [i], so every frame agrees on where things are. */
    private fun rnd(i: Int, k: Int): Float {
        var x = i * 374761393 + k * 668265263
        x = (x xor (x ushr 13)) * 1274126177
        return ((x xor (x ushr 16)) and 0xFFFF) / 65535f
    }

    private class Actor(val home: Float, y: Float) {
        var x = home
        var y = y
        var mood = Mood.HAPPY
        var arms = Arms.DOWN
        var tilt = 0f
        var look = 0f
        var sx = 1f
        var sy = 1f
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()
    private val CONFETTI = intArrayOf(0xFFFF7A96.toInt(), 0xFF5EC4FF.toInt(), 0xFFFFCE54.toInt(), 0xFF6FD6A0.toInt(), 0xFF9B8CFF.toInt(), 0xFFFFFFFF.toInt())

    private fun put(c: Canvas, a: Actor, av: Avatar, head: Float) {
        c.save()
        c.scale(a.sx, a.sy, a.x, a.y + head * 1.05f)
        AvatarArt.draw(c, a.x, a.y, head, av, a.mood, a.arms, a.look, a.tilt)
        c.restore()
    }

    // ---------- particles ----------
    /** Symbols flying out from a point. */
    private fun burst(c: Canvas, head: Float, p: Float, kind: String, color: Int, cx: Float, cy: Float, n: Int, at: Float, reach: Float, salt: Int = 0) {
        val q = seg(p, at, at + 0.26f)
        if (q <= 0f || q >= 1f) return
        for (i in 0 until n) {
            val ang = 2 * PI * i / n + rnd(i, 7 + salt) * 0.8
            val d = outCubic(q) * reach * (0.55f + rnd(i, 8 + salt) * 0.6f)
            val x = cx + (cos(ang) * d).toFloat()
            val y = cy + (sin(ang) * d).toFloat() + q * q * head * 0.5f
            PeopleArt.symbol(c, kind, x, y, head * (0.16f + rnd(i, 9 + salt) * 0.16f), alpha(color, 1f - q * q))
        }
    }

    /** Confetti falling over the whole screen. */
    private fun confetti(c: Canvas, w: Float, h: Float, head: Float, p: Float, from: Float) {
        for (i in 0 until 26) {
            val t0 = from + rnd(i, 11) * 0.12f
            val q = seg(p, t0, t0 + 0.4f + rnd(i, 12) * 0.12f)
            if (q <= 0f || q >= 1f) continue
            val x = w * rnd(i, 13) + sin(q * 9f + i) * w * 0.04f
            val y = h * (-0.05f + q * (0.7f + rnd(i, 14) * 0.5f))
            val s = head * (0.05f + rnd(i, 15) * 0.05f)
            fill.color = alpha(CONFETTI[i % CONFETTI.size], min(1f, (1f - q) * 3f))
            c.save()
            c.rotate(q * 720f * (rnd(i, 16) - 0.5f) + i * 40f, x, y)
            c.drawRoundRect(x - s, y - s * 0.45f, x + s, y + s * 0.45f, s * 0.2f, s * 0.2f, fill)
            c.restore()
        }
    }

    /** Thin drops falling: the mood before a "miss you" lands. */
    private fun drizzle(c: Canvas, w: Float, h: Float, p: Float, color: Int, until: Float, density: Float) {
        val strength = seg(p, 0.08f, 0.2f) * (1f - seg(p, until - 0.06f, until + 0.04f))
        if (strength <= 0f) return
        line.strokeWidth = 2f * density
        for (i in 0 until 12) {
            val q = (p * (1.6f + rnd(i, 21)) + rnd(i, 22)) % 1f
            val x = w * rnd(i, 23)
            val y = h * (q * 1.1f - 0.05f)
            line.color = alpha(color, 0.3f * strength * (1f - q))
            c.drawLine(x, y, x - 3f * density, y + 14f * density, line)
        }
    }

    /** Rings spreading across the screen from a point (a ringing phone, a landing). */
    private fun rings(c: Canvas, p: Float, color: Int, cx: Float, cy: Float, from: Float, until: Float, reach: Float, density: Float) {
        if (p < from || p > until + 0.2f) return
        for (k in 0 until 2) {
            val q = ((p - from) * 3.2f - k * 0.5f)
            val r = q % 1f
            if (q < 0f || p - r / 3.2f > until) continue
            line.color = alpha(color, 0.5f * (1f - r))
            line.strokeWidth = 3f * density * (1f - r) + density
            c.drawCircle(cx, cy, r * reach, line)
        }
    }

    private fun label(c: Canvas, w: Float, s: String, x0: Float, y: Float, size: Float, color: Int, k: Float, tilt: Float = 0f) {
        if (k <= 0f) return
        text.typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        text.textSize = size * k
        text.color = color
        val half = text.measureText(s) / 2 + size * 0.3f
        val x = if (half * 2 >= w) w / 2 else x0.coerceIn(half, w - half) // never off the edge
        c.save(); c.rotate(tilt, x, y)
        c.drawText(s, x, y, text)
        c.restore()
    }

    // ---------- the show ----------
    fun draw(c: Canvas, w: Float, h: Float, density: Float, p: Float, s: Spec) {
        val key = s.action.key
        val col = s.action.color
        val head = min(w * 0.3f, h * 0.17f)
        val cy = h * 0.44f
        val mid = w / 2

        // Backdrop: dim the app, with a soft glow in the action's colour behind the avatars.
        c.drawColor(Color.argb(232, 8, 8, 10))
        val beat = 1f + 0.06f * sin(p * 2 * PI * 6).toFloat()
        fill.shader = RadialGradient(mid, cy + head * 0.2f, max(w, h) * 0.55f * beat, alpha(col, 0.34f), alpha(col, 0f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill)
        fill.shader = null

        // Both slide in from their own side.
        val enter = outCubic(seg(p, 0f, 0.12f))
        val a = Actor(w * 0.24f, cy)   // me, left
        val b = Actor(w * 0.76f, cy)   // them, right
        val snd = if (s.sentByMe) a else b
        val rcv = if (s.sentByMe) b else a
        val dir = if (s.sentByMe) 1f else -1f // the way the sender faces
        val slide = (1f - enter) * w * 0.5f
        a.look = 1f; b.look = -1f
        val towards = if (dir > 0) Arms.WAVE else Arms.WAVE_L      // sender's arm on the receiver's side
        val back = if (dir > 0) Arms.WAVE_L else Arms.WAVE         // receiver's arm on the sender's side
        var front = true // draw the sender in front
        val hit = impacts(key, s.returned)

        when (key) {
            // Run to each other, squeeze, sway.
            "hug" -> {
                val run = ease(seg(p, 0.16f, 0.42f))
                val crouch = bump(seg(p, 0.1f, 0.17f))
                val squeeze = bump(seg(p, 0.42f, 0.52f))
                val held = seg(p, 0.42f, 0.46f)
                snd.x = lerp(snd.home, mid - dir * head * 0.5f, run)
                rcv.x = lerp(rcv.home, mid + dir * head * 0.5f, run)
                snd.y -= hops(seg(p, 0.16f, 0.42f), 3) * head * 0.14f
                rcv.y -= hops(seg(p, 0.19f, 0.42f), 2) * head * 0.1f
                for (o in arrayOf(snd, rcv)) {
                    o.sy = 1f - 0.07f * crouch + 0.05f * squeeze
                    o.sx = 1f + 0.05f * crouch - 0.07f * squeeze
                    if (p > 0.14f) o.arms = Arms.HUG
                }
                snd.mood = if (held > 0f) Mood.LOVE else Mood.EXCITED
                rcv.mood = if (held > 0f) Mood.LAUGH else if (p > 0.2f) Mood.EXCITED else Mood.WOW
                val sway = sin(p * 2 * PI * 2.2f).toFloat() * 3f * held
                a.tilt = 8f * held + sway
                b.tilt = -8f * held + sway
                if (s.returned) {
                    val jump = bump(seg(p, 0.62f, 0.76f)) * head * 0.32f
                    a.y -= jump; b.y -= jump
                }
            }
            // Walk over, lean in, a kiss on the cheek. They go wide-eyed, then shy.
            "kiss" -> {
                val go = ease(seg(p, 0.14f, 0.38f))
                val lean = ease(seg(p, 0.4f, 0.5f)) - ease(seg(p, 0.6f, 0.7f))
                val after = seg(p, 0.5f, 0.54f)
                rcv.x = lerp(rcv.home, mid + dir * head * 0.56f, go)
                snd.x = lerp(snd.home, mid - dir * head * 0.5f, go) + dir * head * 0.3f * lean - dir * head * 0.2f * ease(seg(p, 0.62f, 0.74f))
                snd.y -= hops(seg(p, 0.14f, 0.38f), 2) * head * 0.1f
                snd.tilt = dir * 15f * lean
                snd.y += head * 0.06f * lean
                snd.mood = if (p < 0.34f) Mood.HAPPY else if (p < 0.64f) Mood.KISS else Mood.WINK
                rcv.mood = if (after <= 0f) Mood.HAPPY else if (p < 0.64f) Mood.WOW else if (s.returned && p > 0.7f) Mood.LOVE else Mood.SHY
                rcv.x += dir * head * 0.05f * bump(seg(p, 0.5f, 0.6f))
                rcv.tilt = dir * 5f * bump(seg(p, 0.5f, 0.62f)) + sin(p * 2 * PI * 5).toFloat() * 2.5f * seg(p, 0.64f, 0.7f)
                rcv.sx = 1f + 0.05f * bump(seg(p, 0.5f, 0.58f))
            }
            // Apart and thinking of them: a thought bubble, then a heart crosses the distance.
            "miss" -> {
                val landed = seg(p, 0.62f, 0.66f)
                snd.mood = if (landed > 0f) Mood.SHY else Mood.MISS
                snd.arms = Arms.HEART
                snd.tilt = dir * 5f * ease(seg(p, 0.12f, 0.3f)) + sin(p * 2 * PI * 1.5f).toFloat() * 1.5f
                rcv.mood = if (landed > 0f) Mood.LOVE else Mood.SAD
                rcv.arms = if (landed > 0f) Arms.HEART else Arms.DOWN
                rcv.y -= bump(seg(p, 0.62f, 0.74f)) * head * 0.2f
                rcv.tilt = -dir * 5f * landed
            }
            // Run in, jump, slap hands at the top.
            "highfive" -> {
                val run = ease(seg(p, 0.14f, 0.38f))
                val jump = bump(seg(p, 0.36f, 0.56f))
                val done = p > hit[0]
                snd.x = lerp(snd.home, mid - dir * head * 0.62f, run)
                rcv.x = lerp(rcv.home, mid + dir * head * 0.62f, run)
                for (o in arrayOf(snd, rcv)) {
                    o.y -= hops(seg(p, 0.14f, 0.36f), 2) * head * 0.1f + jump * head * 0.5f
                    o.sy = 1f + 0.08f * jump
                    o.mood = if (done) Mood.LAUGH else Mood.EXCITED
                }
                if (p > 0.32f && p < 0.62f) { snd.arms = towards; rcv.arms = back } else if (p >= 0.62f) { snd.arms = Arms.UP; rcv.arms = Arms.UP }
                if (s.returned) { val j2 = bump(seg(p, 0.64f, 0.78f)) * head * 0.3f; a.y -= j2; b.y -= j2 }
            }
            // Sneak up and jab their cheek, twice. They squish away and pout.
            "poke" -> {
                val go = ease(seg(p, 0.12f, 0.34f))
                val jab = bump(seg(p, 0.38f, 0.46f)) + bump(seg(p, 0.52f, 0.6f))
                snd.x = lerp(snd.home, rcv.home - dir * head * 1.22f, go) + dir * head * 0.1f * jab
                snd.y -= hops(seg(p, 0.12f, 0.34f), 3) * head * 0.06f
                snd.tilt = dir * 6f * jab
                snd.arms = if (p > 0.3f && p < 0.66f) towards else Arms.DOWN
                snd.mood = if (p < 0.62f) Mood.WINK else Mood.LAUGH
                rcv.x += dir * head * 0.14f * jab
                rcv.tilt = dir * 9f * jab
                rcv.sx = 1f - 0.08f * jab
                rcv.mood = if (p < 0.4f) Mood.HAPPY else if (p < 0.64f) Mood.WOW else if (p < 0.8f) Mood.POUT else Mood.LAUGH
                front = false
            }
            // A speech bubble, a paper plane across, and they jump for it.
            "hangout" -> {
                snd.arms = towards
                snd.mood = Mood.EXCITED
                snd.y -= hops(seg(p, 0.14f, 0.32f), 2) * head * 0.1f
                val got = seg(p, 0.62f, 0.66f)
                rcv.mood = if (got > 0f) Mood.EXCITED else Mood.HAPPY
                rcv.arms = if (got > 0f) Arms.UP else Arms.DOWN
                rcv.y -= hops(seg(p, 0.62f, 0.86f), 2) * head * 0.28f
            }
            // Walk up to a glowing house; everyone relaxes.
            "home" -> {
                val go = ease(seg(p, 0.2f, 0.46f))
                snd.x = lerp(snd.home, mid - dir * head * 1.0f, go)
                snd.y -= hops(seg(p, 0.2f, 0.46f), 3) * head * 0.08f
                val inn = p > hit[0]
                snd.mood = if (inn) Mood.LAUGH else Mood.HAPPY
                snd.arms = if (inn) Arms.UP else Arms.DOWN
                rcv.mood = if (p > 0.56f) Mood.LOVE else Mood.HAPPY
                rcv.y -= bump(seg(p, 0.56f, 0.68f)) * head * 0.18f
                rcv.arms = if (p > 0.56f) back else Arms.DOWN
            }
            // A ringing phone; the rings reach them.
            "call" -> {
                snd.arms = towards
                snd.mood = Mood.EXCITED
                snd.tilt = sin(p * 2 * PI * 9).toFloat() * 2f * seg(p, 0.2f, 0.26f) * (1f - seg(p, 0.66f, 0.72f))
                rcv.mood = if (p < 0.48f) Mood.HAPPY else if (p < 0.62f) Mood.WOW else Mood.EXCITED
                rcv.arms = if (p > 0.62f) back else Arms.DOWN
                rcv.y -= bump(seg(p, 0.48f, 0.58f)) * head * 0.16f
            }
            // "love" and anything new: a big beating heart between them.
            else -> {
                snd.arms = Arms.HEART
                snd.mood = Mood.LOVE
                snd.tilt = dir * 4f * ease(seg(p, 0.14f, 0.3f))
                val got = p > 0.5f
                rcv.mood = if (got) Mood.LOVE else Mood.SHY
                rcv.arms = if (got) Arms.HEART else Arms.DOWN
                rcv.y -= bump(seg(p, 0.5f, 0.62f)) * head * 0.2f
            }
        }
        a.x -= slide; b.x += slide

        // ----- things behind the avatars -----
        when (key) {
            "miss" -> drizzle(c, w, h, p, col, 0.62f, density)
            "home" -> {
                val k = pop(seg(p, 0.12f, 0.26f))
                if (k > 0f) {
                    val warm = 0.5f + 0.5f * seg(p, 0.5f, 0.56f)
                    fill.shader = RadialGradient(mid, cy, head * 1.6f, alpha(col, 0.5f * warm), alpha(col, 0f), Shader.TileMode.CLAMP)
                    c.drawCircle(mid, cy, head * 1.6f, fill)
                    fill.shader = null
                    PeopleArt.symbol(c, "home", mid, cy + head * 0.42f, head * 1.3f * k, col)
                }
            }
            "call" -> rings(c, p, col, snd.x, cy - head * 1.15f, 0.2f, 0.68f, head * 2.6f, density)
            "hug", "kiss", "highfive", "poke", "hangout" -> {}
            else -> {
                val k = pop(seg(p, 0.14f, 0.3f))
                val pulse = 1f + 0.1f * abs(sin(p * 2 * PI * 5)).toFloat()
                if (k > 0f) PeopleArt.symbol(c, "heart", mid, cy - head * 0.1f, head * 1.0f * k * pulse, col)
            }
        }

        // ----- the avatars -----
        if (front) { put(c, rcv, if (s.sentByMe) s.them else s.me, head); put(c, snd, if (s.sentByMe) s.me else s.them, head) }
        else { put(c, snd, if (s.sentByMe) s.me else s.them, head); put(c, rcv, if (s.sentByMe) s.them else s.me, head) }

        // ----- things in front -----
        val heartKind = "heart"
        when (key) {
            "hug" -> {
                burst(c, head, p, heartKind, col, mid, cy - head * 0.5f, 5, hit[0], head * 1.5f)
                if (s.returned) burst(c, head, p, "star", 0xFFFFCE54.toInt(), mid, cy - head * 0.5f, 6, 0.66f, head * 1.8f, salt = 40)
            }
            "kiss" -> {
                val k = pop(seg(p, hit[0], hit[0] + 0.08f))
                // The mark stays on the cheek that faces the sender.
                if (k > 0f) PeopleArt.symbol(c, "kiss", rcv.x - dir * head * 0.31f, rcv.y + head * 0.2f, head * 0.26f * k, 0xFFE8506E.toInt())
                val say = seg(p, hit[0], hit[0] + 0.06f) * (1f - seg(p, 0.7f, 0.78f))
                label(c, w, "mwah!", snd.x - dir * head * 0.2f, cy - head * 0.85f, head * 0.34f, 0xFFFFFFFF.toInt(), pop(say), dir * -8f)
                // One small heart floats up from the kissed cheek.
                val up = seg(p, 0.62f, 0.86f)
                if (up > 0f && up < 1f) PeopleArt.symbol(c, heartKind, rcv.x + dir * head * 0.15f + sin(up * 5f) * head * 0.08f,
                    rcv.y - head * (0.65f + 0.6f * up), head * 0.22f, alpha(0xFFFF7A96.toInt(), 1f - up * up))
                if (s.returned) burst(c, head, p, heartKind, col, mid, cy - head * 0.6f, 5, 0.7f, head * 1.4f, salt = 40)
            }
            "miss" -> {
                // Thought bubble with them in it.
                val k = pop(seg(p, 0.12f, 0.24f)) * (1f - seg(p, 0.66f, 0.74f))
                if (k > 0.02f) {
                    val br = head * 0.46f * k
                    val bx = snd.x - dir * head * 0.3f
                    val by = snd.y - head * 1.22f
                    fill.color = Color.argb(240, 255, 255, 255)
                    c.drawCircle(snd.x - dir * head * 0.05f, snd.y - head * 0.62f, head * 0.05f * k, fill)
                    c.drawCircle(snd.x - dir * head * 0.14f, snd.y - head * 0.74f, head * 0.08f * k, fill)
                    c.drawCircle(bx, by, br, fill)
                    c.save()
                    path.reset(); path.addCircle(bx, by, br * 0.92f, Path.Direction.CW)
                    c.clipPath(path)
                    AvatarArt.draw(c, bx, by + br * 0.08f, br * 1.15f, if (s.sentByMe) s.them else s.me, Mood.HAPPY)
                    c.restore()
                }
                // The heart's trip, with a fading trail.
                val x0 = snd.x + dir * head * 0.55f
                val x1 = rcv.x - dir * head * 0.5f
                for (j in 3 downTo 0) {
                    val q = seg(p - j * 0.016f, 0.3f, 0.62f)
                    if (q <= 0f || q >= 1f) continue
                    val e = ease(q)
                    val x = lerp(x0, x1, e)
                    val y = cy + head * 0.1f - bump(q) * head * 0.7f
                    PeopleArt.symbol(c, heartKind, x, y, head * (0.34f - j * 0.06f), alpha(col, 1f - j * 0.28f))
                }
                if (s.returned) burst(c, head, p, heartKind, 0xFFFF7A96.toInt(), mid, cy - head * 0.5f, 5, 0.7f, head * 1.5f, salt = 40)
            }
            "highfive" -> {
                val hy = cy - head * 0.5f + head * 0.2f
                val k = seg(p, hit[0], hit[0] + 0.12f)
                if (k > 0f && k < 1f) PeopleArt.symbol(c, "spark", mid, hy, head * (0.4f + 0.5f * outCubic(k)), alpha(0xFFFFFFFF.toInt(), 1f - k))
                burst(c, head, p, "star", 0xFFFFCE54.toInt(), mid, hy, 5, hit[0], head * 1.5f)
                if (s.returned) confetti(c, w, h, head, p, 0.66f)
            }
            "poke" -> {
                for ((n, ti) in hit.withIndex()) {
                    val k = seg(p, ti - 0.01f, ti + 0.1f)
                    if (k <= 0f || k >= 1f) continue
                    val px = rcv.x - dir * head * 0.5f
                    val py = rcv.y + head * 0.16f
                    PeopleArt.symbol(c, "spark", px, py, head * (0.3f + 0.5f * outCubic(k)), alpha(col, 1f - k))
                    label(c, w, if (n == 0) "poke!" else "poke poke!", rcv.x + dir * head * 0.1f, rcv.y - head * (0.95f + 0.2f * k), head * 0.3f, alpha(0xFFFFFFFF.toInt(), 1f - k * k), pop(min(1f, k * 3f)), dir * 6f)
                }
            }
            "hangout" -> {
                // Speech bubble over the sender.
                val k = pop(seg(p, 0.14f, 0.26f)) * (1f - seg(p, 0.78f, 0.86f))
                if (k > 0.02f) {
                    val bx = (snd.x + dir * head * 0.35f).coerceIn(head * 1.05f, w - head * 1.05f)
                    val by = snd.y - head * 1.3f
                    val bw = head * 1.0f * k
                    val bh = head * 0.34f * k
                    fill.color = Color.WHITE
                    c.drawRoundRect(bx - bw, by - bh, bx + bw, by + bh, bh * 0.8f, bh * 0.8f, fill)
                    path.reset()
                    path.moveTo(snd.x - head * 0.1f, by + bh * 0.8f); path.lineTo(snd.x, by + bh * 1.7f); path.lineTo(snd.x + head * 0.16f, by + bh * 0.8f); path.close()
                    c.drawPath(path, fill)
                    label(c, w, "Hang out?", bx, by + head * 0.1f * k, head * 0.28f, Color.rgb(24, 24, 28), k)
                }
                // The plane, bobbing across with a dotted trail.
                val q = seg(p, 0.3f, 0.62f)
                if (q > 0f && q < 1f) {
                    val x0 = snd.x + dir * head * 0.6f
                    val x1 = rcv.x - dir * head * 0.55f
                    fun py(t: Float) = cy - head * 0.2f - sin(t * 2 * PI).toFloat() * head * 0.3f
                    for (j in 1..7) {
                        val t = q - j * 0.035f
                        if (t <= 0f) break
                        fill.color = alpha(0xFFFFFFFF.toInt(), 0.5f - j * 0.06f)
                        c.drawCircle(lerp(x0, x1, t), py(t), head * 0.025f, fill)
                    }
                    c.save()
                    if (dir < 0) c.scale(-1f, 1f, lerp(x0, x1, q), py(q))
                    PeopleArt.symbol(c, "plane", lerp(x0, x1, q), py(q), head * 0.5f, col)
                    c.restore()
                }
                burst(c, head, p, "star", 0xFFFFCE54.toInt(), rcv.x, rcv.y - head * 0.7f, 5, 0.62f, head * 1.2f)
                if (s.returned) confetti(c, w, h, head, p, 0.66f)
            }
            "home" -> {
                // A tick above the house once they're in.
                val k = pop(seg(p, hit[0], hit[0] + 0.1f))
                if (k > 0f) {
                    val tx = mid + head * 0.5f
                    val ty = cy - head * 0.3f
                    fill.color = 0xFF6FD6A0.toInt()
                    c.drawCircle(tx, ty, head * 0.26f * k, fill)
                    line.color = Color.WHITE; line.strokeWidth = head * 0.07f * k
                    c.drawLine(tx - head * 0.12f * k, ty, tx - head * 0.03f * k, ty + head * 0.1f * k, line)
                    c.drawLine(tx - head * 0.03f * k, ty + head * 0.1f * k, tx + head * 0.13f * k, ty - head * 0.09f * k, line)
                }
                burst(c, head, p, "star", 0xFFFFCE54.toInt(), mid, cy - head * 0.3f, 5, hit[0], head * 1.6f)
            }
            "call" -> {
                val k = pop(seg(p, 0.12f, 0.22f))
                val wig = sin(p * 2 * PI * 14).toFloat() * 14f * seg(p, 0.2f, 0.24f) * (1f - seg(p, 0.66f, 0.72f))
                if (k > 0f) {
                    val px = snd.x
                    val py = cy - head * 1.15f
                    c.save(); c.rotate(wig, px, py)
                    PeopleArt.symbol(c, "phone", px, py, head * 0.62f * k, col)
                    c.restore()
                }
                val k2 = pop(seg(p, 0.5f, 0.6f))
                if (k2 > 0f) PeopleArt.symbol(c, "phone", rcv.x, cy - head * 1.15f, head * 0.5f * k2, 0xFFFFFFFF.toInt())
            }
            else -> {
                burst(c, head, p, heartKind, col, mid, cy - head * 0.5f, 5, 0.5f, head * 1.5f)
            }
        }

        // A quick flash when things collide.
        var flash = 0f
        for (ti in hit) { val d = p - ti; if (d > 0f && d < 0.06f) flash = max(flash, 1f - d / 0.06f) }
        if (flash > 0f) c.drawColor(alpha(if (key == "kiss" || key == "love") col else 0xFFFFFFFF.toInt(), flash * 0.08f))

        // Names and words.
        text.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        text.textSize = 13f * density
        text.color = alpha(0xFFFFFFFF.toInt(), 0.55f * enter)
        val ny = cy + head * 1.05f + 22f * density
        c.drawText("You", a.x, ny, text)
        c.drawText(s.themName, b.x, ny, text)
        val (big, small) = caption(s)
        val show = ease(seg(p, 0.3f, 0.42f))
        if (show > 0f) {
            val ty = cy + head * 1.05f + 78f * density + (1f - show) * 14f * density
            text.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            text.textSize = min(28f * density, w * 0.075f)
            text.color = alpha(0xFFFFFFFF.toInt(), show)
            c.drawText(big, mid, ty, text)
            if (small.isNotEmpty()) {
                text.textSize = 15f * density
                text.color = alpha(if (s.returned) col else 0xFFBDBDBD.toInt(), show)
                c.drawText(small, mid, ty + 28f * density, text)
            }
        }
    }
}

/** Plays one [PeopleFx] moment over the whole window, then removes itself. Tap to skip. */
class PeopleFxOverlay(ctx: Context, private val spec: PeopleFx.Spec) : View(ctx) {
    private val start = SystemClock.uptimeMillis()
    private val total = PeopleFx.duration(spec)
    private var shaken: View? = null
    private var done = false

    private val frame = object : Runnable {
        override fun run() {
            if (done) return
            val p = (SystemClock.uptimeMillis() - start) / total.toFloat()
            if (p >= 1f) { finish(); return }
            alpha = PeopleFx.fade(p)
            // The app underneath shakes too, so the hit is felt by the whole screen.
            shaken?.translationX = PeopleFx.shake(p, spec) * resources.displayMetrics.density
            invalidate()
            postOnAnimation(this)
        }
    }

    init {
        alpha = 0f
        isClickable = true
        contentDescription = PeopleFx.caption(spec).first
        setOnClickListener { finish() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); postOnAnimation(frame) }
    override fun onDetachedFromWindow() { finish(); super.onDetachedFromWindow() }

    private fun finish() {
        if (done) return
        done = true
        shaken?.translationX = 0f
        removeCallbacks(frame)
        post { (parent as? ViewGroup)?.removeView(this) }
    }

    override fun onDraw(canvas: Canvas) {
        if (done) return
        val p = ((SystemClock.uptimeMillis() - start) / total.toFloat()).coerceIn(0f, 1f)
        canvas.save()
        canvas.translate(PeopleFx.shake(p, spec) * resources.displayMetrics.density, 0f)
        PeopleFx.draw(canvas, width.toFloat(), height.toFloat(), resources.displayMetrics.density, p, spec)
        canvas.restore()
    }

    companion object {
        private const val TAG = "people_fx_overlay"

        private fun activity(c: Context): Activity? {
            var x: Context? = c
            while (x != null) {
                if (x is Activity) return x
                x = (x as? ContextWrapper)?.baseContext
            }
            return null
        }

        /** Start a moment over [activity]. False if one is already playing or animations are off. */
        fun play(activity: Activity, spec: PeopleFx.Spec): Boolean {
            if (!ValueAnimator.areAnimatorsEnabled()) return false // the phone has animations turned off
            val decor = activity.window?.decorView as? ViewGroup ?: return false
            if (decor.findViewWithTag<View>(TAG) != null) return false
            val v = PeopleFxOverlay(activity, spec)
            v.tag = TAG
            v.shaken = activity.findViewById(android.R.id.content)
            decor.addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            return true
        }

        /**
         * If the latest action in [g] came from someone else and hasn't been played on this phone
         * yet, play it now. Called when the group's scene comes on screen.
         */
        fun playIncoming(view: View, g: Group, me: Person, others: List<Person>) {
            val c = view.context
            val from = g.lastFrom ?: return
            if (from == me.uid) return
            val action = g.circle.action(g.lastAction) ?: return
            if (System.currentTimeMillis() - g.lastAt > People.RECEIVED_MS) return
            // The same action shows up twice, a few seconds apart (push, then the cloud copy).
            if (g.lastAt <= PeopleHistory.seen(c, g.id) + 10_000) return
            val sender = others.firstOrNull { it.uid == from } ?: return
            val act = activity(c) ?: return
            PeopleHistory.note(c, g.id, from, action.key, g.lastAt)
            val returned = PeopleHistory.returned(c, g.id, from, action.key, g.lastAt)
            if (play(act, PeopleFx.Spec(g.circle, action, me.avatar, sender.avatar, sender.name, sentByMe = false, returned = returned))) {
                PeopleHistory.markSeen(c, g.id, g.lastAt)
            }
        }
    }
}
