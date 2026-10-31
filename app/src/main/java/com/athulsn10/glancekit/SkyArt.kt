package com.athulsn10.glancekit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Drawn-in-code moon and sun for the Today tab. The moon shows the real phase for the date,
 * with soft surface patches and small craters (an original pattern, not a photo).
 */
object SkyArt {

    // Darker "seas" and small craters, in unit-disk coordinates.
    private val MARIA = arrayOf(
        floatArrayOf(-0.30f, -0.25f, 0.30f), floatArrayOf(0.10f, -0.35f, 0.22f), floatArrayOf(0.25f, 0.05f, 0.26f),
        floatArrayOf(-0.15f, 0.20f, 0.20f), floatArrayOf(0.05f, 0.40f, 0.15f), floatArrayOf(-0.45f, 0.05f, 0.16f),
    )
    private val CRATERS = arrayOf(
        floatArrayOf(0.35f, -0.55f, 0.07f), floatArrayOf(-0.55f, 0.45f, 0.06f), floatArrayOf(0.55f, 0.35f, 0.05f),
        floatArrayOf(-0.05f, -0.62f, 0.05f), floatArrayOf(0.62f, -0.15f, 0.04f), floatArrayOf(-0.35f, 0.62f, 0.045f),
        floatArrayOf(0.15f, 0.65f, 0.035f),
    )

    private var cacheKey = ""
    private var cache: Bitmap? = null

    /** @param phase 0 = new, 0.5 = full, 1 = new again. */
    fun moon(c: Context, sizeDp: Float, phase: Float): Bitmap {
        val px = max(32, (sizeDp * c.resources.displayMetrics.density).toInt())
        val key = "$px:${(phase * 200).toInt()}"
        cache?.let { if (key == cacheKey) return it }

        val n = px * 2 // draw at 2x, then scale down for smooth edges
        val pixels = IntArray(n * n)
        val r = n / 2f - 1f
        val cxy = n / 2f
        val k = cos(2 * PI * phase).toFloat()
        val waxing = phase < 0.5f
        for (yy in 0 until n) {
            val y = (yy - cxy) / r
            if (abs(y) > 1f) continue
            val xt = sqrt(1f - y * y) * k
            for (xx in 0 until n) {
                val x = (xx - cxy) / r
                val d2 = x * x + y * y
                if (d2 > 1f) continue
                var shade = 0f
                for (m in MARIA) {
                    val d = hypot(x - m[0], y - m[1])
                    if (d < m[2]) shade += (1 - d / m[2]) * 26f
                }
                for (cr in CRATERS) {
                    val d = hypot(x - cr[0], y - cr[1])
                    if (d < cr[2]) shade += 18f * (1 - abs(d / cr[2] - 0.7f))
                }
                val limb = (1 - sqrt(max(0f, 1 - d2))) * 30f
                val v = max(120f, 232f - shade - limb).toInt()
                val lit = if (waxing) x > xt else x < -xt
                val col = if (lit) {
                    rgb(v, v, v - 4)
                } else {
                    val dv = (34 + (v - 120) * 0.08f).toInt()
                    rgb(dv, dv, dv + 2)
                }
                pixels[yy * n + xx] = col
            }
        }
        val big = Bitmap.createBitmap(pixels, n, n, Bitmap.Config.ARGB_8888)
        val out = Bitmap.createScaledBitmap(big, px, px, true)
        big.recycle()
        cache = out
        cacheKey = key
        return out
    }

    /** Sun disc with soft glow and rays; greyed out and half-set below the horizon at night. */
    fun sun(c: Context, sizeDp: Float, up: Boolean): Bitmap {
        val px = max(32, (sizeDp * c.resources.displayMetrics.density).toInt())
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val cx = px / 2f
        val cy = px / 2f
        val r = px * 0.26f
        if (up) {
            val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(cx, cy, px * 0.5f, intArrayOf(0x55FF8A3D, 0x00FF6A3D), null, Shader.TileMode.CLAMP)
            }
            cv.drawCircle(cx, cy, px * 0.5f, glow)
            val ray = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFB066.toInt()
                strokeWidth = px * 0.025f
                strokeCap = Paint.Cap.ROUND
            }
            for (i in 0 until 12) {
                val a = i * PI / 6
                val r1 = r * 1.3f
                val r2 = r * (if (i % 2 == 0) 1.75f else 1.55f)
                cv.drawLine(
                    cx + (r1 * cos(a)).toFloat(), cy + (r1 * sin(a)).toFloat(),
                    cx + (r2 * cos(a)).toFloat(), cy + (r2 * sin(a)).toFloat(), ray,
                )
            }
            cv.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(cx - r * 0.3f, cy - r * 0.3f, r * 1.4f,
                    intArrayOf(0xFFFFE3A3.toInt(), 0xFFFFB04D.toInt(), Config.ACCENT), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            })
        } else {
            // Below the horizon: a dim half-disc under a horizon line.
            cv.save()
            cv.clipRect(0f, 0f, px.toFloat(), cy + r * 0.15f)
            cv.drawCircle(cx, cy + r * 0.55f, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4A4A4A.toInt() })
            cv.restore()
            cv.drawLine(px * 0.12f, cy + r * 0.15f, px * 0.88f, cy + r * 0.15f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF8A8A8A.toInt(); strokeWidth = px * 0.02f; strokeCap = Paint.Cap.ROUND
            })
        }
        return bmp
    }

    private fun rgb(r: Int, g: Int, b: Int) =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
}
