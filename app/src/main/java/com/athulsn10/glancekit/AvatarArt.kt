package com.athulsn10.glancekit

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** What an avatar looks like. Stored as JSON, shared with the people you pair with. */
data class Avatar(
    val gender: String = "m",          // "m" boy, "f" girl
    val skin: Int = 1,                 // index into AvatarArt.SKINS
    val hair: Int = 0,                 // index into AvatarArt.HAIRS
    val style: String = "short",       // see AvatarArt.BOY_STYLES / GIRL_STYLES
    val outfit: String = "tee",        // tee, hoodie, jacket, stripes
    val color: Int = 1,                // index into AvatarArt.OUTFITS
    val acc: Set<String> = emptySet(), // see AvatarArt.EXTRAS
) {
    fun toJson(): JSONObject = JSONObject()
        .put("gender", gender).put("skin", skin).put("hair", hair).put("style", style)
        .put("outfit", outfit).put("color", color).put("acc", JSONArray(acc.toList()))

    fun toMap(): Map<String, Any> = mapOf(
        "gender" to gender, "skin" to skin, "hair" to hair, "style" to style,
        "outfit" to outfit, "color" to color, "acc" to acc.toList(),
    )

    companion object {
        fun fromJson(o: JSONObject?): Avatar {
            if (o == null) return Avatar()
            val a = o.optJSONArray("acc")
            return Avatar(
                o.optString("gender", "m"), o.optInt("skin", 1), o.optInt("hair", 0), o.optString("style", "short"),
                o.optString("outfit", "tee"), o.optInt("color", 1),
                if (a == null) emptySet() else (0 until a.length()).map { a.getString(it) }.toSet(),
            ).sane()
        }

        @Suppress("UNCHECKED_CAST")
        fun fromMap(m: Map<String, Any?>?): Avatar {
            if (m == null) return Avatar()
            return Avatar(
                m["gender"] as? String ?: "m", (m["skin"] as? Number)?.toInt() ?: 1, (m["hair"] as? Number)?.toInt() ?: 0,
                m["style"] as? String ?: "short", m["outfit"] as? String ?: "tee", (m["color"] as? Number)?.toInt() ?: 1,
                ((m["acc"] as? List<Any?>)?.mapNotNull { it as? String } ?: emptyList()).toSet(),
            ).sane()
        }
    }

    /** Clamp anything out of range (e.g. from an older or newer app version). */
    fun sane() = copy(
        gender = if (gender == "f") "f" else "m",
        skin = skin.coerceIn(0, AvatarArt.SKINS.size - 1),
        hair = hair.coerceIn(0, AvatarArt.HAIRS.size - 1),
        style = if (style in AvatarArt.BOY_STYLES || style in AvatarArt.GIRL_STYLES) style else if (gender == "f") "long" else "short",
        outfit = if (outfit in AvatarArt.OUTFIT_STYLES) outfit else "tee",
        color = color.coerceIn(0, AvatarArt.OUTFITS.size - 1),
        acc = AvatarArt.oneEach(acc),
    )
}

enum class Mood(val label: String) {
    HAPPY("Happy"), LOVE("In love"), EXCITED("Excited"), LAUGH("Laughing"), WINK("Wink"), SHY("Shy"),
    SAD("Sad"), MISS("Missing you"), SLEEP("Sleepy"), WOW("Surprised"), POUT("Pouty"), KISS("Kiss");

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name }
    }
}

/** WAVE raises the right arm, WAVE_L the left; UP raises both. */
enum class Arms { DOWN, WAVE, WAVE_L, UP, HUG, HEART }

/**
 * Cute chibi avatars drawn with Canvas: a big round head, a little body, twelve moods.
 * Original art, no images. Coordinates follow the design mock-ups (tools: scratch scripts).
 */
object AvatarArt {
    val SKINS = intArrayOf(rgb(255, 226, 200), rgb(243, 198, 156), rgb(216, 164, 120), rgb(170, 114, 76), rgb(118, 76, 50))
    val HAIRS = intArrayOf(
        rgb(38, 30, 30), rgb(96, 60, 38), rgb(178, 112, 58), rgb(236, 204, 132), rgb(214, 84, 70), rgb(70, 74, 96),
        rgb(240, 160, 200), rgb(120, 200, 230), rgb(180, 150, 250),
        rgb(206, 206, 214), rgb(120, 200, 140),
    )
    val OUTFITS = intArrayOf(
        rgb(255, 122, 150), rgb(104, 164, 255), rgb(255, 196, 90), rgb(120, 206, 156), rgb(186, 146, 255), rgb(246, 246, 246),
        rgb(40, 40, 48), rgb(255, 140, 90),
        rgb(64, 190, 190), rgb(52, 72, 128), rgb(150, 52, 70), rgb(132, 150, 84),
    )
    val BOY_STYLES = listOf("short", "spiky", "curly", "swoop", "buzz", "manbun")
    val GIRL_STYLES = listOf("long", "bob", "bun", "ponytail", "pigtails", "curly")
    // New looks go at the end: an older app that doesn't know one shows a plain tee instead.
    val OUTFIT_STYLES = listOf("tee", "hoodie", "jacket", "stripes", "sweater", "polo", "print", "overalls", "suit", "dress")
    val OUTFIT_LABELS = mapOf(
        "tee" to "Tee", "hoodie" to "Hoodie", "jacket" to "Jacket", "stripes" to "Stripes", "sweater" to "Sweater",
        "polo" to "Polo", "print" to "Heart tee", "overalls" to "Overalls", "suit" to "Suit", "dress" to "Dress",
    )
    val EXTRAS = listOf("glasses", "shades", "cap", "beanie", "headphones", "bandana", "bow", "flower", "catears", "earrings", "crown", "scarf")
    val EXTRA_LABELS = mapOf(
        "glasses" to "Glasses", "shades" to "Shades", "cap" to "Cap", "beanie" to "Beanie", "headphones" to "Headphones",
        "bandana" to "Bandana", "bow" to "Bow", "flower" to "Flower", "catears" to "Cat ears", "earrings" to "Earrings",
        "crown" to "Crown", "scarf" to "Scarf",
    )

    /**
     * Where each extra sits. An avatar wears at most one extra per spot, so hats, ears and
     * headphones can't pile up on the same head.
     */
    val EXTRA_SLOTS = mapOf(
        "glasses" to "eyes", "shades" to "eyes",
        "cap" to "head", "beanie" to "head", "bandana" to "head", "catears" to "head", "headphones" to "head",
        "bow" to "clip", "flower" to "clip",
        "earrings" to "ears",
        "crown" to "head", "scarf" to "neck",
    )

    /** Keep known extras only, one per spot (the first in [EXTRAS] order wins). */
    fun oneEach(acc: Set<String>): Set<String> {
        val taken = HashSet<String>()
        return EXTRAS.filter { it in acc && taken.add(EXTRA_SLOTS[it] ?: it) }.toSet()
    }

    /** Tap an extra: take it off if it's on, otherwise put it on in place of whatever shares its spot. */
    fun toggleExtra(acc: Set<String>, e: String): Set<String> {
        if (e in acc) return acc - e
        val slot = EXTRA_SLOTS[e]
        return acc.filter { EXTRA_SLOTS[it] != slot }.toSet() + e
    }

    /** Sixteen starting looks. */
    val PRESETS: List<Pair<String, Avatar>> = listOf(
        "Classic" to Avatar("m", 1, 0, "short", "hoodie", 1),
        "Shades" to Avatar("m", 1, 0, "spiky", "jacket", 6, setOf("shades")),
        "Beats" to Avatar("m", 3, 0, "curly", "hoodie", 3, setOf("headphones")),
        "Nerd" to Avatar("m", 0, 3, "swoop", "stripes", 1, setOf("glasses")),
        "Cozy" to Avatar("m", 4, 0, "buzz", "hoodie", 7, setOf("beanie")),
        "Skater" to Avatar("m", 2, 1, "short", "tee", 5, setOf("cap")),
        "Bun" to Avatar("m", 1, 1, "manbun", "jacket", 2),
        "Rebel" to Avatar("m", 0, 4, "spiky", "tee", 6, setOf("bandana")),
        "Sweet" to Avatar("f", 1, 1, "bun", "tee", 4, setOf("bow")),
        "Kitty" to Avatar("f", 0, 6, "pigtails", "tee", 4, setOf("catears")),
        "DJ" to Avatar("f", 2, 1, "ponytail", "jacket", 6, setOf("headphones")),
        "Sunny" to Avatar("f", 4, 0, "curly", "tee", 2, setOf("flower", "earrings")),
        "Cool" to Avatar("f", 1, 7, "bob", "stripes", 0, setOf("shades")),
        "Bookish" to Avatar("f", 3, 0, "long", "hoodie", 3, setOf("glasses")),
        "Winter" to Avatar("f", 0, 3, "long", "hoodie", 0, setOf("beanie", "earrings")),
        "Dreamy" to Avatar("f", 2, 8, "bob", "tee", 4, setOf("flower")),
    )

    /**
     * Held while drawing avatars or scenes. The paints and paths here are shared, so drawing
     * from two threads at once (the app's screen and a widget refresh) would mix them up.
     */
    val DRAW_LOCK = Any()

    private const val INK = 0xFF342222.toInt()
    private const val MOUTH = 0xFFB0404C.toInt()
    private const val TONGUE = 0xFFFF8C96.toInt()
    private const val PINK = 0xFFFF7A96.toInt()
    private const val WHITE = Color.WHITE

    private fun rgb(r: Int, g: Int, b: Int) = Color.rgb(r, g, b)
    private fun darker(c: Int, k: Float = 0.82f) = Color.rgb((Color.red(c) * k).toInt(), (Color.green(c) * k).toInt(), (Color.blue(c) * k).toInt())

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    private val rf = RectF()

    private fun Canvas.oval(l: Float, t: Float, r: Float, b: Float, c: Int) { fill.color = c; rf.set(l, t, r, b); drawOval(rf, fill) }
    private fun Canvas.rrect(l: Float, t: Float, r: Float, b: Float, rad: Float, c: Int) { fill.color = c; rf.set(l, t, r, b); drawRoundRect(rf, rad, rad, fill) }
    /** PIL-style chord/pieslice: angles in degrees, clockwise from 3 o'clock. */
    private fun Canvas.chord(l: Float, t: Float, r: Float, b: Float, start: Float, end: Float, c: Int, pie: Boolean = false) {
        fill.color = c; rf.set(l, t, r, b); drawArc(rf, start, end - start, pie, fill)
    }
    private fun Canvas.arcLine(l: Float, t: Float, r: Float, b: Float, start: Float, end: Float, c: Int, w: Float) {
        stroke.color = c; stroke.strokeWidth = w; rf.set(l, t, r, b)
        var sweep = end - start
        if (sweep < 0) sweep += 360f
        drawArc(rf, start, sweep, false, stroke)
    }
    private fun Canvas.line(x0: Float, y0: Float, x1: Float, y1: Float, c: Int, w: Float) { stroke.color = c; stroke.strokeWidth = w; drawLine(x0, y0, x1, y1, stroke) }
    private fun Canvas.poly(c: Int, vararg p: Float) {
        path.reset(); path.moveTo(p[0], p[1])
        var i = 2
        while (i < p.size) { path.lineTo(p[i], p[i + 1]); i += 2 }
        path.close(); fill.color = c; drawPath(path, fill)
    }

    fun heart(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        path.reset()
        val n = 48
        for (i in 0 until n) {
            val t = 2 * PI * i / n
            val x = 16 * sin(t).let { it * it * it }
            val y = 13 * cos(t) - 5 * cos(2 * t) - 2 * cos(3 * t) - cos(4 * t)
            val px = cx + (x * s / 32).toFloat()
            val py = cy - (y * s / 32).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close(); fill.color = color; c.drawPath(path, fill)
    }

    fun star(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        path.reset()
        for (i in 0 until 10) {
            val a = -PI / 2 + i * PI / 5
            val rr = if (i % 2 == 0) r else r * 0.45f
            val px = cx + (rr * cos(a)).toFloat()
            val py = cy + (rr * sin(a)).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close(); fill.color = color; c.drawPath(path, fill)
    }

    /**
     * Draw an avatar. [s] is the head's diameter in px, ([cx], [cy]) the head's centre.
     * The figure spans roughly from cy - 0.75s (hair, hats) to cy + 1.05s (feet).
     */
    fun draw(
        c: Canvas, cx: Float, cy: Float, s: Float, a: Avatar,
        mood: Mood = Mood.HAPPY, arms: Arms = Arms.DOWN, look: Float = 0f, tilt: Float = 0f, blink: Boolean = false,
    ) {
        val r = s / 2
        val X = cx
        val Y = cy
        val sk = SKINS[a.skin]
        val hr = HAIRS[a.hair]
        val sh = OUTFITS[a.color]
        val lw = max(2f, r * 0.07f)
        val girl = a.gender == "f"
        val st = a.style
        val acc = a.acc
        c.save()
        if (tilt != 0f) c.rotate(tilt, X, Y + r * 0.6f)

        // ---------- hair behind the head ----------
        val hi = Color.argb(80, 255, 255, 255)
        val lo = darker(hr, 0.78f)
        fun shape(col: Int) { path.close(); fill.color = col; c.drawPath(path, fill) }
        if (girl) when (st) {
            "long" -> { // falls behind the shoulders, wavy at the ends
                path.reset(); path.moveTo(X - r * 1.06f, Y - r * 0.2f)
                path.cubicTo(X - r * 1.32f, Y + r * 0.7f, X - r * 1.1f, Y + r * 1.25f, X - r * 0.8f, Y + r * 1.5f)
                path.quadTo(X - r * 0.4f, Y + r * 1.32f, X, Y + r * 1.46f)
                path.quadTo(X + r * 0.4f, Y + r * 1.32f, X + r * 0.8f, Y + r * 1.5f)
                path.cubicTo(X + r * 1.1f, Y + r * 1.25f, X + r * 1.32f, Y + r * 0.7f, X + r * 1.06f, Y - r * 0.2f)
                shape(lo)
                for (k in -3..3) if (k != 0) c.line(X + k * r * 0.3f, Y + r * 0.9f, X + k * r * 0.27f, Y + r * 1.4f, Color.argb(50, 0, 0, 0), max(1f, lw * 0.5f))
            }
            "bob" -> {
                path.reset(); path.moveTo(X - r * 1.1f, Y - r * 0.3f)
                path.cubicTo(X - r * 1.28f, Y + r * 0.55f, X - r * 1.0f, Y + r * 0.98f, X - r * 0.55f, Y + r * 0.88f)
                path.lineTo(X + r * 0.55f, Y + r * 0.88f)
                path.cubicTo(X + r * 1.0f, Y + r * 0.98f, X + r * 1.28f, Y + r * 0.55f, X + r * 1.1f, Y - r * 0.3f)
                shape(lo)
            }
            "curly" -> for (deg in 0 until 360 step 24) {
                val t = Math.toRadians(deg.toDouble())
                val px = X + r * 1.0f * cos(t).toFloat(); val py = Y + r * 0.22f + r * 0.92f * sin(t).toFloat()
                c.oval(px - r * 0.27f, py - r * 0.27f, px + r * 0.27f, py + r * 0.27f, if (deg % 48 == 0) hr else lo)
            }
            "ponytail" -> {
                path.reset(); path.moveTo(X + r * 0.7f, Y - r * 0.78f)
                path.cubicTo(X + r * 1.75f, Y - r * 0.95f, X + r * 1.65f, Y + r * 0.6f, X + r * 1.15f, Y + r * 1.15f)
                path.cubicTo(X + r * 1.3f, Y + r * 0.4f, X + r * 1.1f, Y - r * 0.1f, X + r * 0.75f, Y - r * 0.2f)
                shape(hr)
                c.arcLine(X + r * 0.9f, Y - r * 0.7f, X + r * 1.5f, Y + r * 0.6f, 300f, 20f, hi, lw * 1.2f)
                c.oval(X + r * 0.76f, Y - r * 0.74f, X + r * 0.98f, Y - r * 0.5f, PINK)
            }
            "pigtails" -> for (sx in intArrayOf(-1, 1)) {
                path.reset(); path.moveTo(X + sx * r * 0.95f, Y - r * 0.1f)
                path.cubicTo(X + sx * r * 1.65f, Y + r * 0.1f, X + sx * r * 1.55f, Y + r * 0.95f, X + sx * r * 1.1f, Y + r * 1.2f)
                path.cubicTo(X + sx * r * 0.98f, Y + r * 0.7f, X + sx * r * 0.9f, Y + r * 0.3f, X + sx * r * 0.95f, Y - r * 0.1f)
                shape(hr)
                c.oval(X + sx * r * 0.98f - r * 0.12f, Y - r * 0.12f, X + sx * r * 0.98f + r * 0.12f, Y + r * 0.12f, PINK)
            }
        }
        if ("headphones" in acc) c.arcLine(X - r * 1.1f, Y - r * 1.22f, X + r * 1.1f, Y + r * 0.9f, 180f, 360f, rgb(52, 52, 60), r * 0.15f)

        // ---------- body ----------
        val bw = s * 0.64f
        val bh = s * 0.52f
        val by0 = Y + r * 0.78f
        val shadeC = darker(sh, 0.86f)
        val baseC = if (a.outfit == "overalls") rgb(244, 238, 228) else sh
        // Little shoes, so the body stands on something.
        for (sx in intArrayOf(-1, 1)) c.oval(X + sx * bw * 0.2f - s * 0.1f, by0 + bh - s * 0.04f, X + sx * bw * 0.2f + s * 0.1f, by0 + bh + s * 0.055f, rgb(58, 54, 66))
        if (a.outfit == "hoodie") c.oval(X - bw * 0.58f, by0 - r * 0.22f, X + bw * 0.58f, by0 + r * 0.5f, shadeC)
        // Torso: rounded shoulders, a touch wider than the waist.
        path.reset(); path.moveTo(X - bw * 0.52f, by0 + bh * 0.3f)
        path.cubicTo(X - bw * 0.52f, by0 + bh * 0.02f, X - bw * 0.3f, by0, X, by0)
        path.cubicTo(X + bw * 0.3f, by0, X + bw * 0.52f, by0 + bh * 0.02f, X + bw * 0.52f, by0 + bh * 0.3f)
        path.lineTo(X + bw * 0.45f, by0 + bh * 0.84f); path.quadTo(X + bw * 0.44f, by0 + bh, X + bw * 0.28f, by0 + bh)
        path.lineTo(X - bw * 0.28f, by0 + bh); path.quadTo(X - bw * 0.44f, by0 + bh, X - bw * 0.45f, by0 + bh * 0.84f)
        shape(baseC)
        // A hem, and for knitwear a ribbed band.
        if (a.outfit in setOf("hoodie", "sweater")) c.rrect(X - bw * 0.43f, by0 + bh * 0.86f, X + bw * 0.43f, by0 + bh, r * 0.08f, shadeC)
        else if (a.outfit in setOf("tee", "stripes", "polo", "print")) c.line(X - bw * 0.36f, by0 + bh * 0.93f, X + bw * 0.36f, by0 + bh * 0.93f, shadeC, max(1f, lw * 0.7f))
        // The head hides the top fifth of the body, so details start below that.
        val top = by0 + r * 0.22f
        when (a.outfit) {
            "sweater" -> {
                c.rrect(X - bw * 0.24f, top - r * 0.04f, X + bw * 0.24f, top + r * 0.1f, r * 0.06f, darker(sh, 0.85f))
                val yy = by0 + bh * 0.56f
                c.line(X - bw / 2 + r * 0.07f, yy - r * 0.11f, X + bw / 2 - r * 0.07f, yy - r * 0.11f, WHITE, max(1f, lw * 0.6f))
                for (k in -2..2) c.oval(X + k * bw * 0.17f - r * 0.045f, yy - r * 0.045f, X + k * bw * 0.17f + r * 0.045f, yy + r * 0.045f, WHITE)
                c.line(X - bw / 2 + r * 0.07f, yy + r * 0.11f, X + bw / 2 - r * 0.07f, yy + r * 0.11f, WHITE, max(1f, lw * 0.6f))
            }
            "polo" -> {
                c.poly(WHITE, X - r * 0.34f, top - r * 0.06f, X - r * 0.02f, top - r * 0.06f, X - r * 0.14f, top + r * 0.2f)
                c.poly(WHITE, X + r * 0.34f, top - r * 0.06f, X + r * 0.02f, top - r * 0.06f, X + r * 0.14f, top + r * 0.2f)
                c.line(X, top + r * 0.02f, X, by0 + bh * 0.6f, darker(sh, 0.78f), max(1f, lw * 0.6f))
                for (k in 0 until 2) c.oval(X - r * 0.035f, top + r * (0.16f + k * 0.16f), X + r * 0.035f, top + r * (0.23f + k * 0.16f), WHITE)
            }
            "print" -> heart(c, X, by0 + bh * 0.58f, bw * 0.42f, if (a.color == 5) PINK else WHITE)
            "overalls" -> {
                c.rrect(X - bw * 0.36f, by0 + bh * 0.42f, X + bw * 0.36f, by0 + bh, bw * 0.24f, sh)
                c.rrect(X - bw * 0.3f, by0 + bh * 0.42f, X + bw * 0.3f, by0 + bh * 0.7f, r * 0.05f, sh)
                for (sx in intArrayOf(-1, 1)) {
                    c.line(X + sx * bw * 0.22f, top - r * 0.04f, X + sx * bw * 0.22f, by0 + bh * 0.46f, sh, r * 0.1f)
                    c.oval(X + sx * bw * 0.22f - r * 0.05f, by0 + bh * 0.44f, X + sx * bw * 0.22f + r * 0.05f, by0 + bh * 0.44f + r * 0.1f, rgb(255, 214, 102))
                }
            }
            "suit" -> {
                val dk = darker(sh, 0.78f)
                c.poly(WHITE, X - r * 0.24f, top - r * 0.08f, X + r * 0.24f, top - r * 0.08f, X, by0 + bh * 0.72f)
                c.poly(rgb(226, 74, 84), X - r * 0.07f, top, X + r * 0.07f, top, X + r * 0.06f, by0 + bh * 0.56f, X, by0 + bh * 0.66f, X - r * 0.06f, by0 + bh * 0.56f)
                for (sx in intArrayOf(-1, 1)) {
                    c.poly(dk, X + sx * r * 0.36f, top - r * 0.08f, X + sx * r * 0.22f, top - r * 0.08f, X + sx * r * 0.02f, by0 + bh * 0.72f, X + sx * r * 0.2f, by0 + bh * 0.5f)
                }
            }
            "dress" -> {
                val dk = darker(sh, 0.85f)
                c.poly(sh, X - bw * 0.4f, by0 + bh * 0.42f, X + bw * 0.4f, by0 + bh * 0.42f, X + bw * 0.62f, by0 + bh, X - bw * 0.62f, by0 + bh)
                c.rrect(X - bw * 0.62f, by0 + bh - r * 0.1f, X + bw * 0.62f, by0 + bh + r * 0.02f, r * 0.05f, dk)
                c.rrect(X - bw * 0.46f, by0 + bh * 0.4f, X + bw * 0.46f, by0 + bh * 0.52f, r * 0.05f, dk)
                for (sx in intArrayOf(-1, 1)) c.oval(X + sx * r * 0.15f - r * 0.13f, top - r * 0.08f, X + sx * r * 0.15f + r * 0.13f, top + r * 0.12f, WHITE)
            }
            "hoodie" -> {
                for (sx in intArrayOf(-1, 1)) {
                    c.line(X + sx * r * 0.12f, by0 + r * 0.08f, X + sx * r * 0.14f, by0 + r * 0.42f, WHITE, lw)
                    c.oval(X + sx * r * 0.14f - lw, by0 + r * 0.42f - lw, X + sx * r * 0.14f + lw, by0 + r * 0.42f + lw, WHITE)
                }
                c.rrect(X - bw * 0.28f, by0 + bh * 0.48f, X + bw * 0.28f, by0 + bh * 0.78f, r * 0.08f, darker(sh, 0.9f))
            }
            "jacket" -> {
                val dk = darker(sh, 0.8f)
                c.poly(dk, X - r * 0.32f, by0, X, by0 + r * 0.32f, X - r * 0.06f, by0 + bh * 0.9f, X - bw * 0.42f, by0 + bh * 0.4f)
                c.poly(dk, X + r * 0.32f, by0, X, by0 + r * 0.32f, X + r * 0.06f, by0 + bh * 0.9f, X + bw * 0.42f, by0 + bh * 0.4f)
                c.line(X, by0 + r * 0.3f, X, by0 + bh * 0.95f, rgb(220, 220, 220), max(1f, lw / 2))
            }
            "stripes" -> for (k in 0 until 3) {
                val yy = by0 + bh * (0.28f + k * 0.24f)
                c.line(X - bw / 2 + r * 0.06f, yy, X + bw / 2 - r * 0.06f, yy, WHITE, r * 0.08f)
            }
        }

        // ---------- arms ----------
        val hand = s * 0.08f
        val longSleeve = a.outfit in setOf("hoodie", "jacket", "sweater", "suit")
        val sleeveC = darker(baseC, 0.93f)
        fun handAt(x: Float, y: Float) = c.oval(x - hand, y - hand, x + hand, y + hand, sk)
        /** Shoulder to wrist: a sleeve (long, or short with a bare forearm), then the hand. */
        fun arm(x0: Float, y0: Float, x1: Float, y1: Float) {
            if (longSleeve) {
                c.line(x0, y0, x1, y1, sleeveC, s * 0.125f)
                c.line(x0 + (x1 - x0) * 0.9f, y0 + (y1 - y0) * 0.9f, x1, y1, darker(baseC, 0.8f), s * 0.125f)
            } else {
                c.line(x0, y0, x1, y1, sk, s * 0.095f)
                c.line(x0, y0, x0 + (x1 - x0) * 0.42f, y0 + (y1 - y0) * 0.42f, sleeveC, s * 0.135f)
            }
            handAt(x1 + (x1 - x0) * 0.14f, y1 + (y1 - y0) * 0.14f)
        }
        val shx = bw * 0.43f
        val shy = by0 + bh * 0.22f
        fun down(sx: Int) = arm(X + sx * shx, shy, X + sx * bw * 0.57f, by0 + bh * 0.6f)
        when (arms) {
            Arms.WAVE -> { down(-1); arm(X + shx, shy, X + bw / 2 + s * 0.22f, by0 - r * 0.2f) }
            Arms.WAVE_L -> { down(1); arm(X - shx, shy, X - bw / 2 - s * 0.22f, by0 - r * 0.2f) }
            Arms.UP -> for (sx in intArrayOf(-1, 1)) arm(X + sx * shx, shy, X + sx * r * 1.06f, Y + r * 0.4f)
            Arms.HUG -> for (sx in intArrayOf(-1, 1)) arm(X + sx * shx, shy, X + sx * (bw / 2 + s * 0.18f), by0 + r * 0.08f)
            Arms.HEART -> {
                for (sx in intArrayOf(-1, 1)) arm(X + sx * shx, shy, X + sx * s * 0.17f, by0 + r * 0.36f)
                heart(c, X, by0 + r * 0.24f, s * 0.3f, PINK)
            }
            Arms.DOWN -> { down(-1); down(1) }
        }

        // ---------- head ----------
        if ("earrings" in acc) for (sx in intArrayOf(-1, 1)) {
            c.oval(X + sx * r * 0.98f - r * 0.07f, Y + r * 0.34f, X + sx * r * 0.98f + r * 0.07f, Y + r * 0.48f, rgb(255, 210, 90))
        }
        c.oval(X - r, Y - r, X + r, Y + r, sk)

        // ---------- hair on top ----------
        val h2 = darker(hr, 0.92f)
        val h3 = Color.rgb(Color.red(hr) + (255 - Color.red(hr)) * 7 / 100, Color.green(hr) + (255 - Color.green(hr)) * 7 / 100, Color.blue(hr) + (255 - Color.blue(hr)) * 7 / 100)
        /** A soft band of light across the crown, plus a thinner bright line inside it. */
        fun shine() {
            c.arcLine(X - r * 0.78f, Y - r * 0.98f, X + r * 0.62f, Y - r * 0.02f, 208f, 292f, Color.argb(26, 255, 255, 255), lw * 3.2f)
            c.arcLine(X - r * 0.74f, Y - r * 0.98f, X + r * 0.56f, Y - r * 0.02f, 222f, 276f, Color.argb(95, 255, 255, 255), lw * 1.1f)
        }
        fun dome(grow: Float, low: Float) { c.chord(X - r * grow, Y - r * (grow + 0.04f), X + r * grow, Y + r * low, 180f, 360f, hr); shine() }
        /** One lock of hair: a tapered leaf from its root to its tip, bulging to one side. */
        fun lock(x0: Float, y0: Float, x1: Float, y1: Float, w: Float, col: Int) {
            val mx = (x0 + x1) / 2; val my = (y0 + y1) / 2
            val len = hypot(x1 - x0, y1 - y0).coerceAtLeast(1f)
            val nx = -(y1 - y0) / len * w * r; val ny = (x1 - x0) / len * w * r
            path.reset(); path.moveTo(x0, y0); path.quadTo(mx + nx * 1.7f, my + ny * 1.7f, x1, y1); path.quadTo(mx - nx * 0.7f, my - ny * 0.7f, x0, y0)
            shape(col)
        }
        /** Fine strands combed from the parting towards the hairline. */
        fun combed(px: Float, py: Float, vararg ends: Float) {
            var i = 0
            while (i < ends.size) {
                val ex1 = X + ends[i] * r; val ey1 = Y + ends[i + 1] * r
                path.reset(); path.moveTo(px, py); path.quadTo((px + ex1) / 2, py + (ey1 - py) * 0.15f, ex1, ey1)
                stroke.color = Color.argb(60, Color.red(lo), Color.green(lo), Color.blue(lo)); stroke.strokeWidth = max(1f, lw * 0.5f); c.drawPath(path, stroke)
                i += 2
            }
        }
        /** Locks swept across the forehead from a side parting, with a wisp at each temple. */
        fun sweptFringe() {
            val px = X - r * 0.42f; val py = Y - r * 0.98f
            combed(px, py, -1.0f, -0.4f, -0.7f, -0.6f, 0.3f, -0.62f, 0.75f, -0.5f, 1.0f, -0.36f)
            lock(X + r * 0.55f, Y - r * 0.86f, X + r * 1.03f, Y + r * 0.02f, -0.14f, h2)
            lock(X - r * 0.72f, Y - r * 0.7f, X - r * 1.02f, Y + r * 0.04f, 0.13f, h2)
            lock(X + r * 0.1f, Y - r * 0.95f, X + r * 0.82f, Y - r * 0.16f, -0.2f, hr)
            lock(X - r * 0.25f, Y - r * 0.96f, X + r * 0.38f, Y - r * 0.14f, -0.22f, h3)
            lock(X - r * 0.5f, Y - r * 0.92f, X - r * 0.12f, Y - r * 0.16f, -0.2f, hr)
            lock(X - r * 0.62f, Y - r * 0.82f, X - r * 0.66f, Y - r * 0.2f, 0.14f, h2)
        }
        /** A straight fringe made of separate locks, so the ends are soft and uneven. */
        fun bluntFringe(drop: Float) {
            combed(X, Y - r * 1.02f, -0.9f, -0.5f, -0.45f, -0.6f, 0.45f, -0.6f, 0.9f, -0.5f)
            for (k in 0 until 7) {
                val x = X - r * 0.84f + k * r * 0.28f
                val d = drop + (if (k % 2 == 0) 0.05f else 0f) - (if (k == 3) 0.03f else 0f)
                lock(x, Y - r * 0.86f, x + (k - 3) * r * 0.02f, Y - r * d, if (k % 2 == 0) 0.17f else -0.17f, if (k % 3 == 1) h3 else if (k % 2 == 0) hr else h2)
            }
        }
        if (!girl) when (st) {
            "buzz" -> {
                c.chord(X - r, Y - r * 1.02f, X + r, Y + r * 0.2f, 180f, 360f, hr)
                c.arcLine(X - r * 0.9f, Y - r * 0.92f, X + r * 0.9f, Y + r * 0.34f, 205f, 335f, lo, max(1f, lw * 0.6f))
            }
            "spiky" -> { // tufts standing up from the crown, with a short jagged fringe
                dome(1.04f, 0.4f)
                for (k in 0 until 6) {
                    val x0 = X - r * 0.8f + k * r * 0.32f
                    lock(x0, Y - r * 0.62f, X - r * 0.98f + k * r * 0.4f, Y - r * (1.42f - (k % 2) * 0.14f), if (k < 3) 0.17f else -0.17f, if (k % 2 == 0) hr else h2)
                }
                for (k in 0 until 4) {
                    val x0 = X - r * 0.7f + k * r * 0.46f
                    lock(x0, Y - r * 0.74f, x0 + r * 0.08f, Y - r * (0.12f + (k % 2) * 0.08f), 0.16f, if (k % 2 == 0) hr else h3)
                }
            }
            "curly" -> {
                for (deg in 185 until 360 step 17) {
                    val t = Math.toRadians(deg.toDouble())
                    val px = X + r * 0.86f * cos(t).toFloat(); val py = Y - r * 0.02f + r * 0.86f * sin(t).toFloat()
                    c.oval(px - r * 0.27f, py - r * 0.27f, px + r * 0.27f, py + r * 0.27f, hr)
                }
                c.oval(X - r * 0.6f, Y - r * 1.12f, X + r * 0.6f, Y - r * 0.38f, hr)
                for (deg in 200 until 350 step 34) {
                    val t = Math.toRadians(deg.toDouble())
                    val px = X + r * 0.9f * cos(t).toFloat(); val py = Y - r * 0.06f + r * 0.9f * sin(t).toFloat()
                    c.arcLine(px - r * 0.14f, py - r * 0.14f, px + r * 0.14f, py + r * 0.14f, 180f, 300f, hi, max(1f, lw * 0.7f))
                }
            }
            "swoop" -> {
                dome(1.05f, 0.42f)
                path.reset(); path.moveTo(X - r * 1.0f, Y - r * 0.32f)
                path.cubicTo(X - r * 0.95f, Y - r * 1.5f, X + r * 0.55f, Y - r * 1.55f, X + r * 1.02f, Y - r * 0.5f)
                path.cubicTo(X + r * 0.5f, Y - r * 0.86f, X - r * 0.25f, Y - r * 0.7f, X - r * 1.0f, Y - r * 0.32f)
                shape(hr)
                c.arcLine(X - r * 0.6f, Y - r * 1.12f, X + r * 0.7f, Y - r * 0.1f, 220f, 295f, hi, lw * 1.5f)
                c.arcLine(X - r * 0.5f, Y - r * 1.1f, X + r * 0.9f, Y - r * 0.1f, 225f, 295f, lo, max(1f, lw * 0.6f))
            }
            "manbun" -> {
                c.oval(X - r * 0.32f, Y - r * 1.44f, X + r * 0.32f, Y - r * 0.86f, hr)
                c.oval(X - r * 0.16f, Y - r * 1.3f, X + r * 0.02f, Y - r * 1.16f, hi)
                dome(1.04f, 0.4f)
                c.arcLine(X - r * 0.9f, Y - r * 0.92f, X + r * 0.9f, Y + r * 0.3f, 205f, 335f, lo, max(1f, lw * 0.6f))
            }
            else -> { dome(1.06f, 0.5f); sweptFringe() }
        } else {
            if (st == "curly") {
                for (deg in 185 until 360 step 17) {
                    val t = Math.toRadians(deg.toDouble())
                    val px = X + r * 0.88f * cos(t).toFloat(); val py = Y - r * 0.02f + r * 0.88f * sin(t).toFloat()
                    c.oval(px - r * 0.27f, py - r * 0.27f, px + r * 0.27f, py + r * 0.27f, hr)
                }
                c.oval(X - r * 0.6f, Y - r * 1.12f, X + r * 0.6f, Y - r * 0.38f, hr)
            } else {
                dome(1.07f, 0.5f)
                when (st) {
                    "long" -> { // curtain fringe from a centre parting, and a lock over each shoulder
                        combed(X, Y - r * 1.04f, -1.0f, -0.3f, -0.6f, -0.55f, 0.6f, -0.55f, 1.0f, -0.3f)
                        for (sx in intArrayOf(-1, 1)) {
                            path.reset(); path.moveTo(X + sx * r * 0.84f, Y - r * 0.5f)
                            path.cubicTo(X + sx * r * 0.8f, Y + r * 0.6f, X + sx * r * 0.96f, Y + r * 1.0f, X + sx * r * 0.78f, Y + r * 1.4f)
                            path.cubicTo(X + sx * r * 1.22f, Y + r * 1.2f, X + sx * r * 1.34f, Y + r * 0.4f, X + sx * r * 1.09f, Y - r * 0.5f)
                            shape(hr)
                            lock(X, Y - r * 0.9f, X + sx * r * 0.97f, Y + r * 0.1f, -sx * 0.12f, hr)
                            lock(X + sx * r * 0.02f, Y - r * 0.92f, X + sx * r * 0.84f, Y - r * 0.1f, -sx * 0.17f, h3)
                            lock(X + sx * r * 0.04f, Y - r * 0.94f, X + sx * r * 0.5f, Y - r * 0.3f, -sx * 0.16f, h2)
                        }
                    }
                    "bob" -> {
                        bluntFringe(0.14f)
                        for (sx in intArrayOf(-1, 1)) {
                            path.reset(); path.moveTo(X + sx * r * 0.84f, Y - r * 0.3f)
                            path.cubicTo(X + sx * r * 0.82f, Y + r * 0.3f, X + sx * r * 0.86f, Y + r * 0.6f, X + sx * r * 0.62f, Y + r * 0.84f)
                            path.cubicTo(X + sx * r * 1.1f, Y + r * 0.92f, X + sx * r * 1.3f, Y + r * 0.4f, X + sx * r * 1.1f, Y - r * 0.3f)
                            shape(hr)
                        }
                    }
                    "pigtails" -> { bluntFringe(0.2f); c.line(X, Y - r * 0.6f, X, Y - r * 1.05f, lo, max(1f, lw * 0.6f)) }
                    else -> sweptFringe() // bun, ponytail
                }
            }
            if (st == "bun") {
                c.oval(X - r * 0.36f, Y - r * 1.52f, X + r * 0.36f, Y - r * 0.88f, hr)
                c.oval(X - r * 0.18f, Y - r * 1.38f, X + r * 0.02f, Y - r * 1.22f, hi)
            }
        }

        // ---------- face ----------
        val ey = Y + r * 0.12f
        val ex = r * 0.38f
        val er = r * 0.12f
        val lk = look * er * 0.5f
        fun dotEye(x: Float, big: Float = 1f) {
            c.oval(x - er * big, ey - er * 1.25f * big, x + er * big, ey + er * 1.25f * big, INK)
            c.oval(x - er * 0.2f * big + lk, ey - er * 0.95f * big, x + er * 0.5f * big + lk, ey - er * 0.2f * big, WHITE)
            c.oval(x - er * 0.55f + lk, ey + er * 0.35f, x - er * 0.25f + lk, ey + er * 0.65f, WHITE)
        }
        fun closed(x: Float, up: Boolean) {
            if (up) c.arcLine(x - er * 1.3f, ey - er * 0.6f, x + er * 1.3f, ey + er * 1.9f, 200f, 340f, INK, lw)
            else c.arcLine(x - er * 1.3f, ey - er * 1.6f, x + er * 1.3f, ey + er * 0.6f, 20f, 160f, INK, lw)
        }
        var eyes = when (mood) {
            Mood.HAPPY, Mood.POUT -> "dot"; Mood.LOVE -> "heart"; Mood.EXCITED -> "star"; Mood.LAUGH, Mood.KISS -> "up"
            Mood.WINK -> "wink"; Mood.SHY, Mood.SLEEP -> "down"; Mood.SAD, Mood.MISS -> "sad"; Mood.WOW -> "big"
        }
        if (blink && eyes in setOf("dot", "big", "sad")) eyes = "down"
        val brow = darker(hr, 0.9f)
        for (sx in intArrayOf(-1, 1)) {
            val x = X + sx * ex
            when (eyes) {
                "dot" -> dotEye(x)
                "big" -> dotEye(x, 1.25f)
                "heart" -> {
                    heart(c, x, ey, er * 3f, PINK)
                    c.oval(x - er * 0.75f, ey - er * 0.85f, x - er * 0.3f, ey - er * 0.4f, rgb(255, 220, 230))
                }
                "star" -> star(c, x, ey, er * 1.55f, rgb(255, 196, 70))
                "up" -> closed(x, true)
                "down" -> closed(x, false)
                "wink" -> if (sx < 0) dotEye(x) else closed(x, true)
                "sad" -> {
                    dotEye(x)
                    c.line(x - sx * er * 1.3f, ey - er * 2.5f, x + sx * er * 1.2f, ey - er * 2f, brow, lw)
                }
            }
        }
        if (mood == Mood.POUT) for (sx in intArrayOf(-1, 1)) {
            val x = X + sx * ex
            c.line(x - sx * er * 1.3f, ey - er * 2f, x + sx * er * 1.2f, ey - er * 2.6f, brow, lw)
        }
        if (mood == Mood.SAD || mood == Mood.MISS) {
            val tx = X + ex + er * 0.6f; val ty = ey + er * 1.6f
            val tear = rgb(150, 205, 255)
            c.oval(tx - er * 0.45f, ty, tx + er * 0.45f, ty + er, tear)
            c.poly(tear, tx - er * 0.42f, ty + er * 0.3f, tx, ty - er * 0.6f, tx + er * 0.42f, ty + er * 0.3f)
        }

        // blush
        val bs = if (mood in setOf(Mood.SHY, Mood.LOVE, Mood.KISS)) 1.35f else 1f
        for (sx in intArrayOf(-1, 1)) {
            val bx = X + sx * r * 0.62f
            c.oval(bx - r * 0.17f * bs, ey + r * 0.16f, bx + r * 0.17f * bs, ey + r * 0.16f + r * 0.17f * bs, Color.argb(150, 255, 130, 140))
            if (mood == Mood.SHY || mood == Mood.LOVE) for (k in 0 until 3) {
                c.line(bx - r * 0.1f + k * r * 0.08f, ey + r * 0.2f, bx - r * 0.13f + k * r * 0.08f, ey + r * 0.28f, Color.argb(170, 240, 100, 120), max(1f, lw / 2))
            }
        }

        // mouth
        val my = Y + r * 0.44f
        when (mood) {
            Mood.EXCITED, Mood.LAUGH, Mood.LOVE -> {
                c.chord(X - r * 0.2f, my - r * 0.14f, X + r * 0.2f, my + r * 0.2f, 0f, 180f, MOUTH)
                c.chord(X - r * 0.11f, my + r * 0.02f, X + r * 0.11f, my + r * 0.2f, 0f, 180f, TONGUE)
            }
            Mood.SAD, Mood.MISS -> c.arcLine(X - r * 0.14f, my, X + r * 0.14f, my + r * 0.2f, 200f, 340f, MOUTH, lw)
            Mood.WOW -> c.oval(X - r * 0.1f, my - r * 0.06f, X + r * 0.1f, my + r * 0.18f, MOUTH)
            Mood.SLEEP -> c.oval(X - r * 0.06f, my, X + r * 0.06f, my + r * 0.1f, MOUTH)
            Mood.KISS -> {
                c.arcLine(X - r * 0.06f, my - r * 0.08f, X + r * 0.1f, my + r * 0.04f, 270f, 90f, MOUTH, lw)
                c.arcLine(X - r * 0.06f, my + r * 0.02f, X + r * 0.1f, my + r * 0.14f, 270f, 90f, MOUTH, lw)
            }
            Mood.SHY -> {
                path.reset()
                for (i in 0 until 5) {
                    val px = X - r * 0.14f + i * r * 0.07f
                    val py = my + if (i % 2 == 1) r * 0.035f else -r * 0.01f
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                stroke.color = MOUTH; stroke.strokeWidth = lw; c.drawPath(path, stroke)
            }
            Mood.POUT -> c.arcLine(X - r * 0.1f, my + r * 0.02f, X + r * 0.1f, my + r * 0.16f, 200f, 340f, MOUTH, lw)
            else -> c.arcLine(X - r * 0.16f, my - r * 0.14f, X + r * 0.16f, my + r * 0.1f, 20f, 160f, MOUTH, lw)
        }

        // ---------- accessories ----------
        val dark = rgb(40, 40, 40)
        if ("scarf" in acc) {
            val sc = rgb(226, 74, 84)
            c.rrect(X - r * 0.62f, Y + r * 0.8f, X + r * 0.62f, Y + r * 1.06f, r * 0.12f, sc)
            c.rrect(X + r * 0.18f, Y + r * 0.95f, X + r * 0.44f, Y + r * 1.5f, r * 0.08f, sc)
            for (k in 0 until 2) c.line(X + r * 0.2f, Y + r * (1.24f + k * 0.12f), X + r * 0.42f, Y + r * (1.24f + k * 0.12f), WHITE, max(1f, lw * 0.6f))
        }
        if ("crown" in acc) {
            val gold = rgb(255, 204, 72)
            c.poly(gold, X - r * 0.5f, Y - r * 0.82f, X - r * 0.56f, Y - r * 1.3f, X - r * 0.26f, Y - r * 1.04f, X, Y - r * 1.4f,
                X + r * 0.26f, Y - r * 1.04f, X + r * 0.56f, Y - r * 1.3f, X + r * 0.5f, Y - r * 0.82f)
            c.rrect(X - r * 0.52f, Y - r * 0.92f, X + r * 0.52f, Y - r * 0.76f, r * 0.05f, rgb(236, 170, 50))
            c.oval(X - r * 0.07f, Y - r * 0.92f, X + r * 0.07f, Y - r * 0.78f, rgb(226, 74, 84))
        }
        if ("glasses" in acc) {
            for (sx in intArrayOf(-1, 1)) {
                c.rrect(X + sx * ex - er * 2.3f, ey - er * 1.9f, X + sx * ex + er * 2.3f, ey + er * 2.0f, er * 1.3f, Color.argb(36, 255, 255, 255))
                stroke.color = dark; stroke.strokeWidth = lw * 1.1f
                rf.set(X + sx * ex - er * 2.3f, ey - er * 1.9f, X + sx * ex + er * 2.3f, ey + er * 2.0f); c.drawRoundRect(rf, er * 1.3f, er * 1.3f, stroke)
                c.line(X + sx * ex - er * 1.5f, ey - er * 1.2f, X + sx * ex - er * 0.9f, ey - er * 1.45f, Color.argb(150, 255, 255, 255), max(1f, lw * 0.7f))
            }
            c.arcLine(X - ex + er * 2.3f, ey - er * 0.9f, X + ex - er * 2.3f, ey + er * 0.5f, 200f, 340f, dark, lw * 1.1f)
        }
        if ("shades" in acc) {
            val g = rgb(28, 28, 36)
            for (sx in intArrayOf(-1, 1)) {
                c.rrect(X + sx * ex - er * 2.4f, ey - er * 1.6f, X + sx * ex + er * 2.4f, ey + er * 1.8f, er * 1.1f, g)
                c.line(X + sx * ex - er * 1.5f, ey - er * 0.7f, X + sx * ex - er * 0.5f, ey - er * 1.1f, rgb(120, 120, 150), lw)
            }
            c.line(X - ex + er * 2.4f, ey - er * 0.6f, X + ex - er * 2.4f, ey - er * 0.6f, g, lw * 1.2f)
        }
        if ("cap" in acc) {
            val cc = rgb(84, 140, 232); val cd = rgb(58, 108, 196)
            c.chord(X - r * 1.06f, Y - r * 1.16f, X + r * 1.06f, Y + r * 0.24f, 180f, 360f, cc)
            c.arcLine(X - r * 0.5f, Y - r * 1.16f, X + r * 0.5f, Y + r * 0.24f, 200f, 340f, cd, max(1f, lw * 0.6f))
            // a curved peak, seen from slightly above
            path.reset(); path.moveTo(X - r * 0.35f, Y - r * 0.47f)
            path.quadTo(X + r * 0.6f, Y - r * 0.62f, X + r * 1.42f, Y - r * 0.3f)
            path.quadTo(X + r * 0.7f, Y - r * 0.2f, X - r * 0.35f, Y - r * 0.3f)
            shape(cd)
            c.rrect(X - r * 1.06f, Y - r * 0.54f, X + r * 1.06f, Y - r * 0.4f, r * 0.06f, cd)
            c.oval(X - r * 0.09f, Y - r * 1.2f, X + r * 0.09f, Y - r * 1.06f, cd)
        }
        if ("beanie" in acc) {
            val bc = rgb(232, 96, 86); val bd = rgb(196, 72, 66)
            c.chord(X - r * 1.07f, Y - r * 1.22f, X + r * 1.07f, Y + r * 0.12f, 180f, 360f, bc)
            for (k in -2..2) c.line(X + k * r * 0.3f, Y - r * (1.1f - abs(k) * 0.12f), X + k * r * 0.36f, Y - r * 0.6f, bd, max(1f, lw * 0.6f))
            c.rrect(X - r * 1.1f, Y - r * 0.56f, X + r * 1.1f, Y - r * 0.26f, r * 0.14f, bd)
            for (k in -4..4) c.line(X + k * r * 0.23f, Y - r * 0.5f, X + k * r * 0.23f, Y - r * 0.32f, bc, max(1f, lw * 0.6f))
            c.oval(X - r * 0.22f, Y - r * 1.44f, X + r * 0.22f, Y - r * 1.0f, rgb(250, 240, 230))
            c.oval(X - r * 0.12f, Y - r * 1.38f, X + r * 0.02f, Y - r * 1.24f, WHITE)
        }
        if ("bandana" in acc) {
            val b = rgb(230, 70, 80)
            c.rrect(X - r * 1.02f, Y - r * 0.62f, X + r * 1.02f, Y - r * 0.38f, r * 0.1f, b)
            c.poly(b, X + r * 0.95f, Y - r * 0.55f, X + r * 1.35f, Y - r * 0.75f, X + r * 1.3f, Y - r * 0.35f)
        }
        if ("catears" in acc) for (sx in intArrayOf(-1, 1)) {
            c.poly(rgb(40, 40, 46), X + sx * r * 0.85f, Y - r * 0.55f, X + sx * r * 0.78f, Y - r * 1.35f, X + sx * r * 0.25f, Y - r * 0.92f)
            c.poly(rgb(255, 160, 180), X + sx * r * 0.75f, Y - r * 0.7f, X + sx * r * 0.72f, Y - r * 1.16f, X + sx * r * 0.4f, Y - r * 0.92f)
        }
        if ("headphones" in acc) for (sx in intArrayOf(-1, 1)) {
            c.rrect(X + sx * r * 1.03f - r * 0.21f, Y - r * 0.24f, X + sx * r * 1.03f + r * 0.21f, Y + r * 0.44f, r * 0.16f, rgb(52, 52, 60))
            c.rrect(X + sx * r * 1.07f - r * 0.12f, Y - r * 0.14f, X + sx * r * 1.07f + r * 0.12f, Y + r * 0.34f, r * 0.1f, rgb(255, 122, 80))
        }
        if ("bow" in acc) {
            val bx = X + r * 0.62f; val byy = Y - r * 0.8f
            c.poly(PINK, bx, byy, bx - r * 0.38f, byy - r * 0.24f, bx - r * 0.38f, byy + r * 0.24f)
            c.poly(PINK, bx, byy, bx + r * 0.38f, byy - r * 0.24f, bx + r * 0.38f, byy + r * 0.24f)
            c.oval(bx - r * 0.1f, byy - r * 0.1f, bx + r * 0.1f, byy + r * 0.1f, rgb(230, 90, 120))
        }
        if ("flower" in acc) {
            val fx = X - r * 0.7f; val fy = Y - r * 0.72f
            for (k in 0 until 5) {
                val t = k * 2 * PI / 5
                val px = fx + r * 0.14f * cos(t).toFloat(); val py = fy + r * 0.14f * sin(t).toFloat()
                c.oval(px - r * 0.12f, py - r * 0.12f, px + r * 0.12f, py + r * 0.12f, rgb(255, 236, 140))
            }
            c.oval(fx - r * 0.08f, fy - r * 0.08f, fx + r * 0.08f, fy + r * 0.08f, rgb(255, 150, 80))
        }
        c.restore()
    }
}
