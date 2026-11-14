package com.athulsn10.glancekit

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
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
    )
    val OUTFITS = intArrayOf(
        rgb(255, 122, 150), rgb(104, 164, 255), rgb(255, 196, 90), rgb(120, 206, 156), rgb(186, 146, 255), rgb(246, 246, 246),
        rgb(40, 40, 48), rgb(255, 140, 90),
    )
    val BOY_STYLES = listOf("short", "spiky", "curly", "swoop", "buzz", "manbun")
    val GIRL_STYLES = listOf("long", "bob", "bun", "ponytail", "pigtails", "curly")
    val OUTFIT_STYLES = listOf("tee", "hoodie", "jacket", "stripes")
    val EXTRAS = listOf("glasses", "shades", "cap", "beanie", "headphones", "bandana", "bow", "flower", "catears", "earrings")
    val EXTRA_LABELS = mapOf(
        "glasses" to "Glasses", "shades" to "Shades", "cap" to "Cap", "beanie" to "Beanie", "headphones" to "Headphones",
        "bandana" to "Bandana", "bow" to "Bow", "flower" to "Flower", "catears" to "Cat ears", "earrings" to "Earrings",
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
        if (girl) when (st) {
            "long" -> c.rrect(X - r * 1.06f, Y - r * 0.5f, X + r * 1.06f, Y + r * 1.12f, r * 0.62f, hr)
            "bob" -> c.rrect(X - r * 1.1f, Y - r * 0.6f, X + r * 1.1f, Y + r * 0.62f, r * 0.5f, hr)
            "curly" -> for (deg in 0 until 360 step 30) {
                val t = Math.toRadians(deg.toDouble())
                val px = X + r * 0.95f * cos(t).toFloat(); val py = Y + r * 0.2f + r * 0.85f * sin(t).toFloat()
                c.oval(px - r * 0.32f, py - r * 0.32f, px + r * 0.32f, py + r * 0.32f, hr)
            }
            "ponytail" -> c.oval(X + r * 0.55f, Y - r * 0.55f, X + r * 1.45f, Y + r * 0.85f, hr)
            "pigtails" -> for (sx in intArrayOf(-1, 1)) {
                c.oval(X + sx * r * 1.05f - r * 0.38f, Y, X + sx * r * 1.05f + r * 0.38f, Y + r * 0.95f, hr)
                c.oval(X + sx * r * 0.92f - r * 0.12f, Y - r * 0.08f, X + sx * r * 0.92f + r * 0.12f, Y + r * 0.12f, PINK)
            }
        }
        if ("headphones" in acc) c.arcLine(X - r * 1.12f, Y - r * 1.2f, X + r * 1.12f, Y + r * 0.9f, 180f, 360f, rgb(46, 46, 52), r * 0.16f)

        // ---------- body ----------
        val bw = s * 0.64f
        val bh = s * 0.52f
        val by0 = Y + r * 0.78f
        if (a.outfit == "hoodie") c.oval(X - bw * 0.55f, by0 - r * 0.12f, X + bw * 0.55f, by0 + r * 0.42f, darker(sh))
        c.rrect(X - bw / 2, by0, X + bw / 2, by0 + bh, bw * 0.42f, sh)
        when (a.outfit) {
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
        val hand = s * 0.085f
        fun handAt(x: Float, y: Float) = c.oval(x - hand, y - hand, x + hand, y + hand, sk)
        val ay = by0 + r * 0.32f
        when (arms) {
            Arms.WAVE -> {
                handAt(X - bw / 2 - s * 0.02f, ay)
                c.line(X + bw / 2 - s * 0.06f, by0 + r * 0.2f, X + bw / 2 + s * 0.26f, by0 - r * 0.25f, sh, s * 0.11f)
                handAt(X + bw / 2 + s * 0.28f, by0 - r * 0.32f)
            }
            Arms.WAVE_L -> {
                handAt(X + bw / 2 + s * 0.02f, ay)
                c.line(X - bw / 2 + s * 0.06f, by0 + r * 0.2f, X - bw / 2 - s * 0.26f, by0 - r * 0.25f, sh, s * 0.11f)
                handAt(X - bw / 2 - s * 0.28f, by0 - r * 0.32f)
            }
            Arms.UP -> for (sx in intArrayOf(-1, 1)) {
                c.line(X + sx * bw * 0.4f, by0 + r * 0.2f, X + sx * r * 1.1f, Y + r * 0.34f, sh, s * 0.11f)
                handAt(X + sx * r * 1.16f, Y + r * 0.24f)
            }
            Arms.HUG -> for (sx in intArrayOf(-1, 1)) {
                c.line(X + sx * bw * 0.4f, by0 + r * 0.2f, X + sx * (bw / 2 + s * 0.2f), by0 + r * 0.05f, sh, s * 0.11f)
                handAt(X + sx * (bw / 2 + s * 0.22f), by0 + r * 0.03f)
            }
            Arms.HEART -> {
                heart(c, X, by0 + r * 0.22f, s * 0.3f, PINK)
                handAt(X - s * 0.13f, by0 + r * 0.32f); handAt(X + s * 0.13f, by0 + r * 0.32f)
            }
            Arms.DOWN -> { handAt(X - bw / 2 - s * 0.02f, ay); handAt(X + bw / 2 + s * 0.02f, ay) }
        }

        // ---------- head ----------
        if ("earrings" in acc) for (sx in intArrayOf(-1, 1)) {
            c.oval(X + sx * r * 0.98f - r * 0.07f, Y + r * 0.34f, X + sx * r * 0.98f + r * 0.07f, Y + r * 0.48f, rgb(255, 210, 90))
        }
        c.oval(X - r, Y - r, X + r, Y + r, sk)

        // ---------- hair on top ----------
        if (!girl) when (st) {
            "buzz" -> c.chord(X - r, Y - r * 1.02f, X + r, Y + r * 0.2f, 180f, 360f, hr)
            "spiky" -> {
                c.chord(X - r * 1.04f, Y - r * 1.04f, X + r * 1.04f, Y + r * 0.4f, 180f, 360f, hr)
                for (k in 0 until 5) {
                    val bx = X - r * 0.92f + k * r * 0.38f
                    c.poly(hr, bx, Y - r * 0.55f, bx + r * 0.2f, Y - r * 1.38f + (k % 2) * r * 0.16f, bx + r * 0.42f, Y - r * 0.6f)
                }
                c.poly(hr, X - r * 0.7f, Y - r * 0.6f, X - r * 0.35f, Y - r * 0.18f, X - r * 0.1f, Y - r * 0.6f)
            }
            "curly" -> {
                for (deg in 190 until 360 step 22) {
                    val t = Math.toRadians(deg.toDouble())
                    val px = X + r * 0.82f * cos(t).toFloat(); val py = Y + r * 0.82f * sin(t).toFloat()
                    c.oval(px - r * 0.32f, py - r * 0.32f, px + r * 0.32f, py + r * 0.32f, hr)
                }
                c.oval(X - r * 0.5f, Y - r * 1.08f, X + r * 0.5f, Y - r * 0.4f, hr)
            }
            "swoop" -> {
                c.chord(X - r * 1.04f, Y - r * 1.06f, X + r * 1.04f, Y + r * 0.45f, 180f, 360f, hr)
                c.chord(X - r * 1.15f, Y - r * 1.25f, X + r * 0.6f, Y + r * 0.1f, 270f, 360f, hr, pie = true)
                c.poly(hr, X - r * 0.9f, Y - r * 0.4f, X + r * 0.55f, Y - r * 0.85f, X + r * 0.2f, Y - r * 0.25f)
            }
            "manbun" -> {
                c.oval(X - r * 0.3f, Y - r * 1.4f, X + r * 0.3f, Y - r * 0.85f, hr)
                c.chord(X - r * 1.04f, Y - r * 1.06f, X + r * 1.04f, Y + r * 0.4f, 180f, 360f, hr)
            }
            else -> {
                c.chord(X - r * 1.04f, Y - r * 1.08f, X + r * 1.04f, Y + r * 0.55f, 180f, 360f, hr)
                for (dx in floatArrayOf(-0.55f, -0.15f, 0.3f)) {
                    c.chord(X + dx * r - r * 0.36f, Y - r * 0.62f, X + dx * r + r * 0.36f, Y + r * 0.05f, 180f, 360f, hr, pie = true)
                }
            }
        } else {
            if (st == "curly") {
                for (deg in 190 until 360 step 20) {
                    val t = Math.toRadians(deg.toDouble())
                    val px = X + r * 0.85f * cos(t).toFloat(); val py = Y + r * 0.85f * sin(t).toFloat()
                    c.oval(px - r * 0.3f, py - r * 0.3f, px + r * 0.3f, py + r * 0.3f, hr)
                }
            } else {
                c.chord(X - r * 1.05f, Y - r * 1.08f, X + r * 1.05f, Y + r * 0.45f, 180f, 360f, hr)
                if (st == "bob") {
                    c.chord(X - r, Y - r * 0.95f, X + r, Y + r * 0.1f, 180f, 360f, hr)
                } else {
                    c.chord(X - r, Y - r * 0.9f, X + r * 0.25f, Y + r * 0.15f, 200f, 360f, hr, pie = true)
                    c.chord(X - r * 0.2f, Y - r * 0.9f, X + r, Y + r * 0.05f, 180f, 340f, hr, pie = true)
                }
            }
            if (st == "bun") c.oval(X - r * 0.34f, Y - r * 1.48f, X + r * 0.34f, Y - r * 0.86f, hr)
        }
        c.arcLine(X - r * 0.7f, Y - r, X + r * 0.2f, Y - r * 0.2f, 210f, 250f, Color.argb(70, 255, 255, 255), lw)

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
        if ("glasses" in acc) {
            for (sx in intArrayOf(-1, 1)) {
                stroke.color = dark; stroke.strokeWidth = lw
                rf.set(X + sx * ex - er * 2.3f, ey - er * 2.1f, X + sx * ex + er * 2.3f, ey + er * 2.1f); c.drawOval(rf, stroke)
            }
            c.line(X - ex + er * 2.3f, ey, X + ex - er * 2.3f, ey, dark, lw)
        }
        if ("shades" in acc) {
            val g = rgb(24, 24, 30)
            for (sx in intArrayOf(-1, 1)) {
                c.rrect(X + sx * ex - er * 2.4f, ey - er * 1.6f, X + sx * ex + er * 2.4f, ey + er * 1.7f, er * 0.9f, g)
                c.line(X + sx * ex - er * 1.4f, ey - er * 0.8f, X + sx * ex - er * 0.4f, ey - er * 1.1f, rgb(110, 110, 130), lw)
            }
            c.line(X - ex + er * 2.4f, ey - er * 0.6f, X + ex - er * 2.4f, ey - er * 0.6f, g, lw)
        }
        if ("cap" in acc) {
            c.chord(X - r * 1.05f, Y - r * 1.12f, X + r * 1.05f, Y + r * 0.2f, 180f, 360f, rgb(84, 140, 232))
            c.rrect(X - r * 0.1f, Y - r * 0.48f, X + r * 1.3f, Y - r * 0.28f, r * 0.08f, rgb(60, 112, 200))
            c.oval(X - r * 0.08f, Y - r * 1.16f, X + r * 0.08f, Y - r * 1.02f, rgb(60, 112, 200))
        }
        if ("beanie" in acc) {
            c.chord(X - r * 1.06f, Y - r * 1.2f, X + r * 1.06f, Y + r * 0.1f, 180f, 360f, rgb(232, 96, 86))
            c.rrect(X - r * 1.08f, Y - r * 0.5f, X + r * 1.08f, Y - r * 0.25f, r * 0.1f, rgb(196, 72, 66))
            c.oval(X - r * 0.22f, Y - r * 1.42f, X + r * 0.22f, Y - r, rgb(250, 240, 230))
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
            c.rrect(X + sx * r * 1.02f - r * 0.2f, Y - r * 0.2f, X + sx * r * 1.02f + r * 0.2f, Y + r * 0.42f, r * 0.14f, rgb(255, 106, 61))
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
