package com.glassring.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.max

/**
 * The Clock widget's sky picture: the sun in its real colour (or tonight's moon), with the
 * current weather drawn on top - clouds, rain, snow, lightning or mist.
 */
object WeatherArt {

    fun icon(c: Context, sizeDp: Float, elevation: Double, moonPhase: Float, sky: Weather.Sky?): Bitmap {
        val n = max(32, (sizeDp * c.resources.displayMetrics.density).toInt())
        // No weather (or clear): the sun or moon on its own, as before.
        if (sky == null || sky == Weather.Sky.CLEAR) {
            return if (elevation > -4) ClockViews.sunIcon(c, sizeDp, elevation)
            else SkyArt.moon(c, sizeDp, moonPhase)
        }
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val night = elevation <= -4
        val f = n.toFloat()

        fun body(cx: Float, cy: Float, scale: Float) {
            if (night) {
                val m = SkyArt.moon(c, sizeDp, moonPhase)
                val sz = f * 0.62f * scale
                // Soft moonlight behind it.
                cv.drawCircle(cx, cy, sz * 0.8f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(cx, cy, sz * 0.8f, intArrayOf(0x33DCE4F0, 0x00DCE4F0), null, Shader.TileMode.CLAMP)
                })
                cv.drawBitmap(m, null, RectF(cx - sz / 2, cy - sz / 2, cx + sz / 2, cy + sz / 2), Paint(Paint.FILTER_BITMAP_FLAG))
            } else drawSun(cv, f, cx, cy, scale, elevation)
        }

        when (sky) {
            Weather.Sky.PARTLY -> {
                body(f * 0.62f, f * 0.38f, 0.9f)
                cloud(cv, f * 0.44f, f * 0.56f, f * 0.62f, dark = false)
            }
            Weather.Sky.CLOUDY -> {
                body(f * 0.66f, f * 0.34f, 0.7f)
                cloud(cv, f * 0.62f, f * 0.42f, f * 0.5f, dark = true)
                cloud(cv, f * 0.42f, f * 0.58f, f * 0.62f, dark = false)
            }
            Weather.Sky.RAIN -> {
                body(f * 0.66f, f * 0.30f, 0.7f)
                cloud(cv, f * 0.46f, f * 0.42f, f * 0.66f, dark = true)
                rain(cv, f * 0.46f, f * 0.42f, f * 0.66f, f)
            }
            Weather.Sky.STORM -> {
                cloud(cv, f * 0.5f, f * 0.38f, f * 0.72f, dark = true)
                bolt(cv, f * 0.5f, f * 0.38f, f * 0.72f)
                rain(cv, f * 0.5f, f * 0.38f, f * 0.72f, f)
            }
            Weather.Sky.SNOW -> {
                body(f * 0.66f, f * 0.30f, 0.7f)
                cloud(cv, f * 0.46f, f * 0.40f, f * 0.66f, dark = false)
                snow(cv, f * 0.46f, f * 0.40f, f * 0.66f, f)
            }
            Weather.Sky.FOG -> {
                body(f / 2, f / 2, 0.85f)
                fog(cv, f / 2, f * 0.55f, f * 0.8f)
            }
            else -> body(f / 2, f / 2, 1f)
        }
        return bmp
    }

    /** Sun disc with glow in its real colour for this height. */
    fun drawSun(cv: Canvas, f: Float, cx: Float, cy: Float, scale: Float, elevation: Double) {
        val col = ClockViews.sunColor(elevation)
        val r = f * 0.24f * scale
        val glow = f * 0.5f * scale
        cv.drawCircle(cx, cy, glow, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx, cy, glow,
                intArrayOf(Color.argb(95, Color.red(col), Color.green(col), Color.blue(col)), Color.TRANSPARENT),
                floatArrayOf(0.3f, 1f), Shader.TileMode.CLAMP)
        })
        cv.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx - r * 0.3f, cy - r * 0.3f, r * 1.45f,
                intArrayOf(blend(col, Color.WHITE, 0.55f), col, blend(col, Color.BLACK, 0.1f)),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        })
    }

    private fun blend(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )

    /** A soft cloud of overlapping puffs: light on top, shaded underneath, with a faint shadow. */
    private fun cloud(cv: Canvas, cx: Float, cy: Float, w: Float, dark: Boolean) {
        val path = Path()
        val puffs = arrayOf(
            floatArrayOf(-0.28f, 0.10f, 0.26f), floatArrayOf(-0.02f, -0.08f, 0.34f), floatArrayOf(0.28f, 0.06f, 0.27f),
            floatArrayOf(0.10f, 0.16f, 0.24f), floatArrayOf(-0.18f, 0.18f, 0.20f),
        )
        for (p in puffs) path.addCircle(cx + p[0] * w, cy + p[1] * w, p[2] * w, Path.Direction.CW)
        path.addRoundRect(RectF(cx - 0.5f * w, cy + 0.05f * w, cx + 0.52f * w, cy + 0.38f * w), 0.16f * w, 0.16f * w, Path.Direction.CW)
        // Shadow.
        cv.save()
        cv.translate(w * 0.015f, w * 0.04f)
        cv.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x66000000
            maskFilter = BlurMaskFilter(w * 0.05f, BlurMaskFilter.Blur.NORMAL)
        })
        cv.restore()
        val top = if (dark) 0xFF969CA6.toInt() else 0xFFECEFF4.toInt()
        val bottom = if (dark) 0xFF4E5460.toInt() else 0xFF969EAC.toInt()
        cv.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, cy - 0.4f * w, 0f, cy + 0.4f * w, top, bottom, Shader.TileMode.CLAMP)
        })
    }

    private fun rain(cv: Canvas, cx: Float, cy: Float, w: Float, f: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xEB8CBEFF.toInt(); strokeWidth = max(2f, f * 0.022f); strokeCap = Paint.Cap.ROUND
        }
        val n = 4
        for (i in 0 until n) {
            val x = cx - 0.36f * w + i * (0.72f * w / (n - 1))
            val y = cy + 0.46f * w + (i % 2) * 0.1f * w
            val len = 0.16f * w
            cv.drawLine(x, y, x - len * 0.26f, y + len, p)
        }
    }

    private fun snow(cv: Canvas, cx: Float, cy: Float, w: Float, f: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF0F0F6FF.toInt() }
        val r = f * 0.022f
        for (d in arrayOf(floatArrayOf(-0.3f, 0.55f), floatArrayOf(0f, 0.66f), floatArrayOf(0.3f, 0.55f), floatArrayOf(-0.15f, 0.8f), floatArrayOf(0.17f, 0.82f))) {
            cv.drawCircle(cx + d[0] * w, cy + d[1] * w, r, p)
        }
    }

    private fun bolt(cv: Canvas, cx: Float, cy: Float, w: Float) {
        val pts = arrayOf(0.06f to 0.42f, -0.1f to 0.72f, 0.02f to 0.72f, -0.08f to 0.98f, 0.16f to 0.62f, 0.03f to 0.62f, 0.13f to 0.42f)
        val path = Path()
        pts.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(cx + x * w, cy + y * w) else path.lineTo(cx + x * w, cy + y * w) }
        path.close()
        cv.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD250.toInt() })
    }

    private fun fog(cv: Canvas, cx: Float, cy: Float, w: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val bars = arrayOf(0f to 0.8f, 0.08f to 0.7f, -0.06f to 0.76f, 0.04f to 0.6f)
        bars.forEachIndexed { i, (dx, ww) ->
            val y = cy - 0.1f * w + i * 0.16f * w
            p.color = Color.argb(200 - i * 25, 200, 206, 214)
            cv.drawRoundRect(RectF(cx - ww / 2 * w + dx * w, y, cx + ww / 2 * w + dx * w, y + 0.07f * w), 0.035f * w, 0.035f * w, p)
        }
    }
}
