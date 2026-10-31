package com.glassring.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.View

/**
 * Live RAM: a ring and a rolling 2-minute chart. It reads the system's memory figure every
 * 2 seconds, but only while it is on screen, so it costs nothing when you're elsewhere.
 */
class RamView(context: Context) : View(context) {

    private var ram = Device.ram(context)
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            ram = Device.ram(context)
            samples.addLast(ram.used.toFloat() / ram.total.coerceAtLeast(1))
            while (samples.size > 60) samples.removeFirst()
            invalidate()
            handler.postDelayed(this, 2000)
        }
    }

    companion object {
        // Shared, so the chart keeps its history when the tab redraws.
        private val samples = ArrayDeque<Float>()   // used fraction, oldest first
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(tick)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        handler.removeCallbacks(tick)
        if (isVisible) handler.post(tick)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, dp(210f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val used = ram.used.toFloat() / ram.total.coerceAtLeast(1)
        val ringSize = dp(110f)
        val stroke = ringSize * 0.09f
        val rect = RectF(stroke, stroke, ringSize - stroke, ringSize - stroke)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND }
        p.color = RingRenderer.TRACK
        canvas.drawOval(rect, p)
        p.color = if (ram.low || used > 0.9f) Config.ACCENT else RingRenderer.WHITE
        canvas.drawArc(rect, -90f, used * 360f, false, p)

        val center = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = RingRenderer.WHITE; textSize = sp(24f); textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        canvas.drawText("${(used * 100).toInt()}%", ringSize / 2, ringSize / 2 + sp(6f), center)
        val cap = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Ui.FAINT; textSize = sp(9f); textAlign = Paint.Align.CENTER; typeface = Typeface.MONOSPACE
        }
        canvas.drawText("RAM USED", ringSize / 2, ringSize / 2 + sp(22f), cap)

        // Numbers to the right of the ring.
        val left = ringSize + dp(20f)
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = RingRenderer.WHITE; textSize = sp(24f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val soft = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.SOFT; textSize = sp(13f) }
        canvas.drawText("${Collect.fmtBytes(ram.free)} free", left, dp(40f), big)
        canvas.drawText("${Collect.fmtBytes(ram.used)} used of ${Collect.fmtBytes(ram.total)}", left, dp(64f), soft)
        val status = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (ram.low) Config.ACCENT else Ui.FAINT; textSize = sp(12f)
        }
        canvas.drawText(
            if (ram.low) "Low memory: Android is closing apps" else "Live · updates every 2 seconds",
            left, dp(88f), status,
        )

        // Rolling chart, last 2 minutes.
        val top = ringSize + dp(24f)
        val h = height - top - dp(18f)
        val box = RectF(0f, top, width.toFloat(), top + h)
        canvas.drawRoundRect(box, dp(12f), dp(12f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF151515.toInt() })
        if (samples.size >= 2) {
            val lo = (samples.min() - 0.05f).coerceAtLeast(0f)
            val hi = (samples.max() + 0.05f).coerceAtMost(1f)
            val range = (hi - lo).coerceAtLeast(0.05f)
            val pad = dp(8f)
            val path = Path()
            samples.forEachIndexed { i, v ->
                val x = box.left + pad + (box.width() - 2 * pad) * i / 59f
                val y = box.bottom - pad - (box.height() - 2 * pad) * (v - lo) / range
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = dp(2f); color = RingRenderer.WHITE
                strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
            })
        }
        val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7A7A7A.toInt(); textSize = sp(9f); typeface = Typeface.MONOSPACE }
        canvas.drawText("2 MIN AGO", 0f, height.toFloat() - dp(2f), axis)
        axis.textAlign = Paint.Align.RIGHT
        canvas.drawText("NOW", width.toFloat(), height.toFloat() - dp(2f), axis)
    }
}
