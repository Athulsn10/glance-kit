package com.glassring.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Widgets can't draw arcs or charts, so they're drawn into small bitmaps here.
 * Flat, solid drawing (no blur or glow) keeps this fast. Bitmaps are drawn at most 2.5×
 * so they stay small when sent to the home screen.
 */
object RingRenderer {

    const val WHITE = 0xFFFFFFFF.toInt()
    const val TRACK = 0xFF2A2A2A.toInt()
    const val MUTED = 0xFF5C5C5C.toInt()
    const val FAINT = 0xFF333333.toInt()
    const val SURFACE = 0xFF141414.toInt()

    data class Arc(val start: Float, val sweep: Float, val color: Int)

    private fun scale(c: Context) = min(c.resources.displayMetrics.density, 2.5f)

    private fun newBitmap(c: Context, wDp: Float, hDp: Float): Pair<Bitmap, Float> {
        val s = scale(c)
        val w = max(16, (wDp * s).toInt())
        val h = max(8, (hDp * s).toInt())
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) to s
    }

    /**
     * @param arcs   arcs as fractions of a full turn, starting at 12 o'clock
     * @param head   draw a dot at the end of the first arc
     * @param markers small accent ticks at these fractions (sunrise, sunset, charge limit)
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
        val (bmp, _) = newBitmap(c, sizeDp, sizeDp)
        val px = bmp.width
        val cv = Canvas(bmp)
        val stroke = px * 0.085f
        val pad = stroke / 2f + px * 0.02f
        val rect = RectF(pad, pad, px - pad, px - pad)
        val r = rect.width() / 2f
        val cx = px / 2f
        val cy = px / 2f

        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = TRACK
            if (dottedTrack) {
                val seg = (2 * Math.PI * r).toFloat() / 60f
                pathEffect = DashPathEffect(floatArrayOf(seg * 0.35f, seg * 0.65f), 0f)
            }
        }
        cv.drawOval(rect, track)

        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
        }
        for (a in arcs) {
            if (a.sweep <= 0.002f) continue
            p.color = a.color
            cv.drawArc(rect, -90f + a.start * 360f, min(a.sweep, 0.999f) * 360f, false, p)
        }

        val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Config.ACCENT
            strokeWidth = stroke * 0.22f
            strokeCap = Paint.Cap.ROUND
        }
        for (m in markers) {
            val (x1, y1) = point(cx, cy, r - stroke * 0.5f, m)
            val (x2, y2) = point(cx, cy, r + stroke * 0.5f, m)
            cv.drawLine(x1, y1, x2, y2, markerPaint)
        }

        if (head && arcs.isNotEmpty() && arcs.first().sweep > 0.002f) {
            val first = arcs.first()
            val (x, y) = point(cx, cy, r, first.start + min(first.sweep, 0.999f))
            cv.drawCircle(x, y, stroke * 0.62f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Config.ACCENT })
            cv.drawCircle(x, y, stroke * 0.28f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SURFACE })
        }
        return bmp
    }

    /**
     * Bar chart. Each bar is a stack of [a] (accent, bottom) and [b] (white, top).
     * Bars after [lastIndex] are drawn as faint stubs (future hours). [highlight] gets full colour,
     * others are dimmed when [dimOthers] is set.
     */
    fun bars(
        c: Context,
        widthDp: Float,
        heightDp: Float,
        a: LongArray,
        b: LongArray?,
        lastIndex: Int = a.size - 1,
        highlight: Int = -1,
        dimOthers: Boolean = false,
    ): Bitmap {
        val (bmp, s) = newBitmap(c, widthDp, heightDp)
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val cv = Canvas(bmp)
        val n = a.size
        val gap = if (n > 12) w * 0.012f else w * 0.035f
        val bw = (w - gap * (n - 1)) / n
        var peak = 1L
        for (i in 0 until n) peak = max(peak, a[i] + (b?.get(i) ?: 0L))
        val minH = 2f * s
        val radius = min(bw / 2f, 3f * s)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until n) {
            val x = i * (bw + gap)
            if (i > lastIndex) {
                p.color = FAINT
                cv.drawRoundRect(RectF(x, h - minH, x + bw, h), radius, radius, p)
                continue
            }
            val av = a[i]
            val bv = b?.get(i) ?: 0L
            val total = av + bv
            val dim = dimOthers && i != highlight
            if (total <= 0L) {
                p.color = FAINT
                cv.drawRoundRect(RectF(x, h - minH, x + bw, h), radius, radius, p)
                continue
            }
            val barH = max(minH, total.toFloat() / peak * h)
            val aH = barH * av / total
            // Whole bar in the "b" colour, then the "a" part over the bottom.
            p.color = if (dim) MUTED else WHITE
            if (bv > 0L) cv.drawRoundRect(RectF(x, h - barH, x + bw, h), radius, radius, p)
            if (aH > 0f) {
                p.color = if (dim) 0xFF7A3A26.toInt() else Config.ACCENT
                cv.drawRoundRect(RectF(x, h - aH, x + bw, h), radius, radius, p)
            }
        }
        return bmp
    }

    /** Battery level over the last 24 hours. Charging stretches are drawn in the accent colour. */
    fun batteryHistory(c: Context, widthDp: Float, heightDp: Float, pts: List<Sampler.BattPoint>, limit: Int): Bitmap {
        val (bmp, s) = newBitmap(c, widthDp, heightDp)
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val cv = Canvas(bmp)
        val top = 2f * s
        val bottom = h - 2f * s
        fun y(level: Int) = bottom - (bottom - top) * level / 100f

        // Guide at the charge limit.
        if (limit in 1..99) {
            val g = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = FAINT
                strokeWidth = 1f * s
                pathEffect = DashPathEffect(floatArrayOf(3f * s, 3f * s), 0f)
            }
            cv.drawLine(0f, y(limit), w, y(limit), g)
        }
        val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FAINT; strokeWidth = 1f * s }
        cv.drawLine(0f, bottom, w, bottom, base)

        if (pts.size < 2) return bmp
        val now = System.currentTimeMillis() / 60_000L
        val start = now - 24 * 60
        fun x(m: Long) = ((m - start).toFloat() / (24 * 60)).coerceIn(0f, 1f) * w

        val fill = Path()
        fill.moveTo(x(pts.first().minute), bottom)
        for (pt in pts) fill.lineTo(x(pt.minute), y(pt.level))
        fill.lineTo(x(pts.last().minute), bottom)
        fill.close()
        cv.drawPath(fill, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1FFFFFFF })

        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * s
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            line.color = if (a.charging) Config.ACCENT else WHITE
            cv.drawLine(x(a.minute), y(a.level), x(b.minute), y(b.level), line)
        }
        val last = pts.last()
        cv.drawCircle(x(last.minute), y(last.level), 3f * s, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE })
        return bmp
    }

    private fun point(cx: Float, cy: Float, r: Float, frac: Float): Pair<Float, Float> {
        val a = frac * 2 * Math.PI
        return (cx + r * sin(a)).toFloat() to (cy - r * cos(a)).toFloat()
    }
}
