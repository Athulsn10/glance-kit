package com.glassring.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws the rings and charts. Each image is painted straight onto the card colour as a 16-bit
 * bitmap, which halves its size so all four pages fit comfortably in one widget update.
 * The card colour (#101010) is exactly representable in 16-bit, so there are no visible edges.
 */
object RingRenderer {

    const val BG = 0xFF101010.toInt()          // must match widget_bg
    const val WHITE = 0xFFFFFFFF.toInt()
    const val TRACK = 0xFF262626.toInt()
    const val GRID = 0xFF1E1E1E.toInt()
    const val FAINT = 0xFF2C2C2C.toInt()
    const val MUTED = 0xFF5C5C5C.toInt()
    const val SOFT = 0xFFBDBDBD.toInt()

    private fun newBitmap(c: Context, wDp: Float, hDp: Float, maxScale: Float): Pair<Bitmap, Float> {
        val s = min(c.resources.displayMetrics.density, maxScale)
        val bmp = Bitmap.createBitmap(max(16, (wDp * s).toInt()), max(8, (hDp * s).toInt()), Bitmap.Config.RGB_565)
        bmp.eraseColor(BG)
        return bmp to s
    }

    data class Arc(val start: Float, val sweep: Float, val color: Int)

    /** Ring with rounded arcs, optional marker ticks and an end dot. */
    fun ring(
        c: Context,
        sizeDp: Float,
        arcs: List<Arc>,
        head: Boolean = true,
        markers: List<Float> = emptyList(),
        dottedTrack: Boolean = false,
    ): Bitmap {
        val (bmp, _) = newBitmap(c, sizeDp, sizeDp, 2.25f)
        val px = bmp.width
        val cv = Canvas(bmp)
        val stroke = px * 0.09f
        val pad = stroke / 2f + px * 0.03f
        val rect = RectF(pad, pad, px - pad, px - pad)
        val r = rect.width() / 2f
        val cx = px / 2f
        val cy = px / 2f

        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = TRACK
            if (dottedTrack) {
                val seg = (2 * PI * r).toFloat() / 60f
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

        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Config.ACCENT
            strokeWidth = stroke * 0.25f
            strokeCap = Paint.Cap.ROUND
        }
        for (m in markers) {
            val (x1, y1) = point(cx, cy, r - stroke * 0.55f, m)
            val (x2, y2) = point(cx, cy, r + stroke * 0.55f, m)
            cv.drawLine(x1, y1, x2, y2, tick)
        }

        val first = arcs.firstOrNull()
        if (head && first != null && first.sweep > 0.002f) {
            val (x, y) = point(cx, cy, r, first.start + min(first.sweep, 0.999f))
            cv.drawCircle(x, y, stroke * 0.68f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Config.ACCENT })
            cv.drawCircle(x, y, stroke * 0.3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BG })
        }
        return bmp
    }

    /**
     * Bar chart. Each bar stacks [a] (accent, bottom) under [b] (white, top).
     * Bars after [lastIndex] are faint stubs (the future). With [highlight] set, that bar is drawn
     * in [highlightColor] and the others in [baseColor].
     */
    fun bars(
        c: Context,
        widthDp: Float,
        heightDp: Float,
        a: LongArray,
        b: LongArray? = null,
        lastIndex: Int = a.size - 1,
        highlight: Int = -1,
        baseColor: Int = WHITE,
        highlightColor: Int = Config.ACCENT,
        minPeak: Long = 1L,
        guide: Long = -1L,
    ): Bitmap {
        val (bmp, s) = newBitmap(c, widthDp, heightDp, 2f)
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val cv = Canvas(bmp)
        val n = a.size
        // Each bar sits centred in its own column (so it lines up with the axis labels),
        // and is never wider than 18dp so few bars don't turn into blocks.
        val slot = w / n
        val bw = min(slot * (if (n > 12) 0.72f else 0.5f), 18f * s)
        var peak = max(1L, minPeak)
        for (i in 0 until n) peak = max(peak, a[i] + (b?.get(i) ?: 0L))
        if (guide > 0) peak = max(peak, guide * 11 / 10)
        val minH = 3f * s
        val radius = min(bw / 2f, 4f * s)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // Faint guide lines at half and full height.
        val grid = Paint().apply { color = GRID; strokeWidth = 1f * s }
        cv.drawLine(0f, h / 2f, w, h / 2f, grid)
        cv.drawLine(0f, 1f, w, 1f, grid)

        for (i in 0 until n) {
            val x = i * slot + (slot - bw) / 2f
            val total = a[i] + (b?.get(i) ?: 0L)
            if (i > lastIndex || total <= 0L) {
                p.color = FAINT
                cv.drawRoundRect(RectF(x, h - minH, x + bw, h), radius, radius, p)
                continue
            }
            val barH = max(minH, total.toFloat() / peak * h)
            if (b == null) {
                p.color = when {
                    highlight < 0 -> baseColor
                    i == highlight -> highlightColor
                    else -> baseColor
                }
                cv.drawRoundRect(RectF(x, h - barH, x + bw, h), radius, radius, p)
            } else {
                val aH = barH * a[i] / total
                p.color = WHITE
                if (b[i] > 0L) cv.drawRoundRect(RectF(x, h - barH, x + bw, h), radius, radius, p)
                if (aH > 0f) {
                    p.color = Config.ACCENT
                    cv.drawRoundRect(RectF(x, h - aH, x + bw, h), radius, radius, p)
                }
            }
        }
        if (guide > 0) {
            val gy = h - guide.toFloat() / peak * h
            val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = MUTED
                strokeWidth = 1.2f * s
                pathEffect = DashPathEffect(floatArrayOf(4f * s, 4f * s), 0f)
            }
            cv.drawLine(0f, gy, w, gy, dash)
        }
        return bmp
    }

    /** Battery level over the last 24 hours; charging stretches in the accent colour. */
    fun batteryHistory(
        c: Context,
        widthDp: Float,
        heightDp: Float,
        pts: List<Sampler.BattPoint>,
        limit: Int,
        spanMinutes: Long = 24 * 60L,
    ): Bitmap {
        val (bmp, s) = newBitmap(c, widthDp, heightDp, 2f)
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val cv = Canvas(bmp)
        val dot = 4f * s
        val top = dot + 1f * s
        val bottom = h - dot - 1f * s
        fun y(level: Int) = bottom - (bottom - top) * level / 100f

        val grid = Paint().apply { color = GRID; strokeWidth = 1f * s }
        cv.drawLine(0f, y(100), w, y(100), grid)
        cv.drawLine(0f, y(50), w, y(50), grid)
        cv.drawLine(0f, bottom, w, bottom, grid)
        if (limit in 1..99) {
            val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = MUTED
                strokeWidth = 1f * s
                pathEffect = DashPathEffect(floatArrayOf(4f * s, 4f * s), 0f)
            }
            cv.drawLine(0f, y(limit), w, y(limit), dash)
        }
        val now = System.currentTimeMillis() / 60_000L
        val start = now - spanMinutes
        val left = 1f * s
        val right = w - dot - 1f * s
        fun x(m: Long) = left + ((m - start).toFloat() / spanMinutes).coerceIn(0f, 1f) * (right - left)
        val shown = pts.filter { it.minute >= start }
        if (shown.isEmpty()) return bmp
        if (shown.size == 1) {
            val p = shown[0]
            cv.drawCircle(x(p.minute), y(p.level), dot, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE })
            return bmp
        }

        val fill = Path().apply {
            moveTo(x(shown.first().minute), bottom)
            for (pt in shown) lineTo(x(pt.minute), y(pt.level))
            lineTo(x(shown.last().minute), bottom)
            close()
        }
        cv.drawPath(fill, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1C1C1C.toInt() })

        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.2f * s
            strokeCap = Paint.Cap.ROUND
        }
        for (i in 1 until shown.size) {
            val a = shown[i - 1]
            val b = shown[i]
            line.color = if (a.charging) Config.ACCENT else WHITE
            cv.drawLine(x(a.minute), y(a.level), x(b.minute), y(b.level), line)
        }
        val last = shown.last()
        cv.drawCircle(x(last.minute), y(last.level), dot, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE })
        return bmp
    }

    /**
     * Today's daylight: 48 half-hour bars whose height follows the sun. Past slots are white
     * (daylight) or grey (night), the current slot is the accent colour, the future is dim.
     */
    fun daylight(c: Context, widthDp: Float, heightDp: Float, now: Float, sunrise: Float?, sunset: Float?): Bitmap {
        val n = 48
        val heights = FloatArray(n)
        val day = BooleanArray(n)
        for (i in 0 until n) {
            val t = (i + 0.5f) / n
            if (sunrise != null && sunset != null && t in sunrise..sunset) {
                heights[i] = max(0.18f, sin(PI * (t - sunrise) / (sunset - sunrise)).toFloat())
                day[i] = true
            } else {
                heights[i] = 0.1f
            }
        }
        val (bmp, s) = newBitmap(c, widthDp, heightDp, 2f)
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val cv = Canvas(bmp)
        val gap = w * 0.006f
        val bw = (w - gap * (n - 1)) / n
        val radius = min(bw / 2f, 3f * s)
        val current = (now * n).toInt().coerceIn(0, n - 1)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until n) {
            val x = i * (bw + gap)
            val bh = max(3f * s, heights[i] * h)
            p.color = when {
                i == current -> Config.ACCENT
                i < current -> if (day[i]) WHITE else MUTED
                else -> if (day[i]) 0xFF3A3A3A.toInt() else FAINT
            }
            cv.drawRoundRect(RectF(x, h - bh, x + bw, h), radius, radius, p)
        }
        return bmp
    }

    /** Equal blocks for a week, month or year: past full, current in accent, future faint. */
    fun segments(c: Context, widthDp: Float, heightDp: Float, count: Int, fraction: Float): Bitmap {
        val (bmp, s) = newBitmap(c, widthDp, heightDp, 2f)
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val cv = Canvas(bmp)
        val gap = if (count > 12) w * 0.008f else w * 0.02f
        val bw = (w - gap * (count - 1)) / count
        val radius = min(bw / 2f, 4f * s)
        val pos = fraction * count
        val current = pos.toInt().coerceIn(0, count - 1)
        val barH = h * 0.62f
        val top = (h - barH) / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until count) {
            val x = i * (bw + gap)
            p.color = if (i < current) WHITE else FAINT
            cv.drawRoundRect(RectF(x, top, x + bw, top + barH), radius, radius, p)
            if (i == current) {
                val part = (pos - current).coerceIn(0.05f, 1f)
                p.color = Config.ACCENT
                cv.drawRoundRect(RectF(x, top + barH * (1 - part), x + bw, top + barH), radius, radius, p)
            }
        }
        return bmp
    }

    private fun point(cx: Float, cy: Float, r: Float, frac: Float): Pair<Float, Float> {
        val a = frac * 2 * PI
        return (cx + r * sin(a)).toFloat() to (cy - r * cos(a)).toFloat()
    }
}
