package com.glassring.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Widgets can't draw arcs, so the rings and bar charts are drawn into bitmaps here.
 * Drawing in software lets the glow (shadow layer) work on any paint.
 */
object RingRenderer {

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val TRACK = 0x1FFFFFFF
    private const val DIM_DOTS = 0x33FFFFFF

    data class Arc(val start: Float, val sweep: Float, val color: Int, val glow: Boolean)

    /**
     * @param arcs   arcs as fractions of a full turn, starting at 12 o'clock
     * @param head   draw a glowing dot at the end of the first arc
     * @param markers small accent dots at these fractions (sunrise, sunset, charge limit)
     * @param dottedTrack dotted background ring (focus page)
     */
    fun ring(
        c: Context,
        sizeDp: Float,
        arcs: List<Arc>,
        head: Boolean = true,
        markers: List<Float> = emptyList(),
        dottedTrack: Boolean = false,
    ): Bitmap {
        val px = max(48, (sizeDp * c.resources.displayMetrics.density).toInt())
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val stroke = px * 0.058f
        val pad = stroke * 1.9f
        val rect = RectF(pad, pad, px - pad, px - pad)
        val r = rect.width() / 2f
        val cx = px / 2f
        val cy = px / 2f

        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = if (dottedTrack) DIM_DOTS else TRACK
            if (dottedTrack) {
                val circ = (2 * Math.PI * r).toFloat()
                val seg = circ / 60f
                pathEffect = DashPathEffect(floatArrayOf(seg * 0.3f, seg * 0.7f), 0f)
            }
        }
        cv.drawOval(rect, track)

        for (a in arcs) {
            if (a.sweep <= 0.001f) continue
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                strokeCap = Paint.Cap.ROUND
                color = a.color
                if (a.glow) setShadowLayer(stroke * 0.9f, 0f, 0f, withAlpha(a.color, 0x99))
            }
            cv.drawArc(rect, -90f + a.start * 360f, a.sweep.coerceAtMost(0.9999f) * 360f, false, p)
        }

        val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Config.ACCENT }
        for (m in markers) {
            val (x, y) = point(cx, cy, r, m)
            cv.drawCircle(x, y, stroke * 0.38f, markerPaint)
        }

        if (head && arcs.isNotEmpty()) {
            val first = arcs.first()
            val (x, y) = point(cx, cy, r, first.start + first.sweep)
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = WHITE
                setShadowLayer(stroke * 1.1f, 0f, 0f, Config.ACCENT)
            }
            cv.drawCircle(x, y, stroke * 0.72f, dot)
        }
        return bmp
    }

    /** 24 hourly bars: mobile in accent, Wi-Fi in white, future hours as faint stubs. */
    fun bars(c: Context, widthDp: Float, heightDp: Float, mobile: LongArray, wifi: LongArray, currentHour: Int): Bitmap {
        val d = c.resources.displayMetrics.density
        val w = max(24, (widthDp * d).toInt())
        val h = max(8, (heightDp * d).toInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val n = 24
        val gap = w * 0.012f
        val bw = (w - gap * (n - 1)) / n
        var peak = 1L
        for (i in 0 until n) peak = max(peak, mobile[i] + wifi[i])
        val minH = 2 * d
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val radius = bw / 2.5f
        for (i in 0 until n) {
            val x = i * (bw + gap)
            if (i > currentHour) {
                p.color = 0x1FFFFFFF
                cv.drawRoundRect(RectF(x, h - minH, x + bw, h.toFloat()), radius, radius, p)
                continue
            }
            val total = mobile[i] + wifi[i]
            val barH = max(minH, (total.toFloat() / peak) * h)
            val mobileH = if (total > 0) barH * mobile[i] / total else 0f
            // Wi-Fi on the bottom, mobile on top.
            p.color = 0xBFFFFFFF.toInt()
            cv.drawRoundRect(RectF(x, h - barH, x + bw, h.toFloat()), radius, radius, p)
            if (mobileH > 0f) {
                p.color = Config.ACCENT
                cv.drawRoundRect(RectF(x, h - barH, x + bw, h - barH + mobileH), radius, radius, p)
            }
        }
        return bmp
    }

    private fun point(cx: Float, cy: Float, r: Float, frac: Float): Pair<Float, Float> {
        val a = frac * 2 * Math.PI
        return (cx + r * sin(a)).toFloat() to (cy - r * cos(a)).toFloat()
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)
}
