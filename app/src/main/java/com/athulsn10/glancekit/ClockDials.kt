package com.athulsn10.glancekit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The four round dials on the Clock widget (concept E, 1.23): sky (sun in its real colour or
 * tonight's moon, with the weather on top), temperature gauge, air quality ring and next alarm.
 * Each is drawn on a 72-unit grid and scaled to the dial size, so it matches the design.
 */
object ClockDials {

    const val NIGHT_ACCENT = 0xFFB9C6FF.toInt()
    private const val DISC = 0x610A0A09        // dark see-through disc behind every dial
    private const val TRACK = 0x29FFFFFF       // unlit part of a ring

    /** Dial numbers: the system medium font, matching the time. */
    @Suppress("UNUSED_PARAMETER")
    private fun font(c: Context): Typeface = ClockStyle.dialFont

    /** A blank dial: the disc and (optionally) the full track ring. Returns the canvas scale (px per unit). */
    private class Dial(c: Context, sizeDp: Float, track: Boolean = true) {
        val px = max(40, (sizeDp * c.resources.displayMetrics.density.coerceAtMost(3.5f)).toInt())
        val bmp: Bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val k = px / 72f
        val ring = RectF(5 * k, 5 * k, 67 * k, 67 * k) // radius 31, centre 36
        init {
            cv.drawCircle(36 * k, 36 * k, 36 * k, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DISC })
            if (track) cv.drawCircle(36 * k, 36 * k, 31 * k, stroke(TRACK))
        }
        fun stroke(col: Int, w: Float = 4f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = w * k; strokeCap = Paint.Cap.ROUND; color = col
        }
        /** Progress from 12 o'clock, clockwise. */
        fun progress(fraction: Float, col: Int) {
            val f = fraction.coerceIn(0f, 1f)
            if (f <= 0.005f) return
            cv.drawArc(ring, -90f, 360f * f, false, stroke(col))
        }
        fun text(c: Context, s: String, sizeUnits: Float, cy: Float, col: Int = Color.WHITE) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = font(c); textSize = sizeUnits * k; color = col; textAlign = Paint.Align.CENTER
            }
            val fm = p.fontMetrics
            cv.drawText(s, 36 * k, cy * k - (fm.ascent + fm.descent) / 2, p)
        }
    }

    /**
     * Sun or moon now. The ring shows how much of the day (or night) has gone; the body is the
     * sun in its real colour or the moon in its real phase, with clouds/rain on top when there are any.
     */
    fun sky(c: Context, sizeDp: Float, night: Boolean, fractionGone: Float, accent: Int, elevation: Double,
            moonPhase: Float, weather: Weather.Sky?): Bitmap {
        val d = Dial(c, sizeDp)
        d.progress(fractionGone, accent)
        val k = d.k
        // Soft light behind the body.
        val glowR = 19 * k
        d.cv.drawCircle(36 * k, 36 * k, glowR, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(36 * k, 36 * k, glowR,
                intArrayOf(Color.argb(if (night) 40 else 60, Color.red(accent), Color.green(accent), Color.blue(accent)), Color.TRANSPARENT),
                floatArrayOf(0.4f, 1f), Shader.TileMode.CLAMP)
        })
        // The body, drawn by the same art the old widget used, sized to sit inside the ring.
        val bodyDp = sizeDp * (if (night && (weather == null || weather == Weather.Sky.CLEAR)) 0.40f else 0.62f)
        val body = WeatherArt.icon(c, bodyDp, elevation, moonPhase, weather)
        val side = (if (night && (weather == null || weather == Weather.Sky.CLEAR)) 26f else 40f) * k
        val dst = RectF(36 * k - side / 2, 36 * k - side / 2, 36 * k + side / 2, 36 * k + side / 2)
        d.cv.drawBitmap(body, null, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return d.bmp
    }

    /**
     * Temperature: a 270° gauge from today's low (blue) to high (orange) with a dot at the
     * temperature now, and the number in the middle. [tempC] null = no weather yet.
     */
    fun temperature(c: Context, sizeDp: Float, tempC: Int?, low: Int?, high: Int?): Bitmap {
        val d = Dial(c, sizeDp, track = false)
        val k = d.k
        val cx = 36 * k
        val gauge = d.stroke(TRACK)
        if (tempC != null) {
            gauge.color = Color.WHITE
            gauge.shader = SweepGradient(cx, cx, intArrayOf(0xFF6FB7FF.toInt(), 0xFFFF8A3D.toInt(), 0xFFFF8A3D.toInt()),
                floatArrayOf(0f, 0.75f, 1f)).apply { setLocalMatrix(Matrix().apply { setRotate(135f, cx, cx) }) }
        }
        d.cv.drawArc(d.ring, 135f, 270f, false, gauge)
        if (tempC == null) {
            d.text(c, "–", 22f, 36f)
            return d.bmp
        }
        val f = if (low != null && high != null && high > low) ((tempC - low).toFloat() / (high - low)).coerceIn(0f, 1f) else 0.5f
        val a = Math.toRadians(135.0 + 270.0 * f)
        val dx = cx + (31 * k * cos(a)).toFloat()
        val dy = cx + (31 * k * sin(a)).toFloat()
        d.cv.drawCircle(dx, dy, 5.5f * k, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A1A19.toInt() })
        d.cv.drawCircle(dx, dy, 3.6f * k, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        d.text(c, "$tempC°", if (tempC <= -10 || tempC >= 100) 18f else 22f, 37f)
        return d.bmp
    }

    /** Air quality: ring filled up to AQI 300 in the category colour, "AQI" over the number. */
    fun air(c: Context, sizeDp: Float, aqi: Int?, color: Int): Bitmap {
        val d = Dial(c, sizeDp)
        if (aqi == null) {
            d.text(c, "–", 22f, 36f)
            return d.bmp
        }
        d.progress(max(0.04f, aqi / 300f), color)
        d.text(c, "AQI", 9f, 25f, 0xB3FFFFFF.toInt())
        d.text(c, aqi.toString(), if (aqi >= 100) 19f else 22f, 41f)
        return d.bmp
    }

    /** Next alarm: a small alarm-clock glyph over the time, or "OFF". */
    fun alarm(c: Context, sizeDp: Float, time: String?): Bitmap {
        val d = Dial(c, sizeDp)
        val k = d.k
        // 24-unit glyph scaled to 17 units, centred at x 36, top at y 17.
        val g = 17f / 24f
        val ox = 36f - 12f * g
        val oy = 16f
        fun X(v: Float) = (ox + v * g) * k
        fun Y(v: Float) = (oy + v * g) * k
        val p = d.stroke(if (time != null) Color.WHITE else 0x99FFFFFF.toInt(), 1.8f * g)
        p.strokeJoin = Paint.Join.ROUND
        d.cv.drawCircle(X(12f), Y(13f), 7f * g * k, p)
        d.cv.drawPath(Path().apply {
            moveTo(X(12f), Y(9.5f)); lineTo(X(12f), Y(13f)); lineTo(X(14.2f), Y(14.6f))
            moveTo(X(5f), Y(4.5f)); lineTo(X(2.8f), Y(6.6f))
            moveTo(X(19f), Y(4.5f)); lineTo(X(21.2f), Y(6.6f))
        }, p)
        if (time != null) d.text(c, time, if (time.length > 4) 15f else 17f, 46f)
        else d.text(c, "OFF", 13f, 46f, 0x99FFFFFF.toInt())
        return d.bmp
    }
}
