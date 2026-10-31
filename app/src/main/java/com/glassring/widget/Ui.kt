package com.glassring.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Small toolkit for the app's dashboard: solid dark cards, stats, charts and pills. */
object Ui {
    const val BG = 0xFF0B0B0B.toInt()
    const val CARD = RingRenderer.BG          // same colour the charts are drawn on
    const val WHITE = 0xFFFFFFFF.toInt()
    const val SOFT = 0xFFBDBDBD.toInt()
    const val FAINT = 0xFF8A8A8A.toInt()
    const val LINE = 0xFF262626.toInt()

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    /** Usable chart width in dp inside a card. */
    fun chartWidthDp(c: Context): Float {
        val m = c.resources.displayMetrics
        return m.widthPixels / m.density - 32f - 36f
    }

    fun text(
        c: Context,
        s: CharSequence,
        sizeSp: Float,
        color: Int = WHITE,
        medium: Boolean = false,
        mono: Boolean = false,
        gravity: Int = Gravity.START,
    ) = TextView(c).apply {
        text = s
        textSize = sizeSp
        setTextColor(color)
        this.gravity = gravity
        includeFontPadding = false
        typeface = when {
            mono -> Typeface.MONOSPACE
            medium -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else -> Typeface.DEFAULT
        }
        if (mono) letterSpacing = 0.1f
    }

    fun label(c: Context, s: String) = text(c, s, 11f, FAINT, mono = true)

    fun vstack(c: Context, gapDp: Int = 0) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        if (gapDp > 0) {
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = GradientDrawable().apply { setSize(1, dp(c, gapDp)) }
        }
    }

    fun hstack(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun rounded(color: Int, radiusPx: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx
    }

    /** A solid card with a title. Add content to the returned column. */
    fun card(c: Context, title: String? = null): LinearLayout = vstack(c).apply {
        background = rounded(CARD, dp(c, 24).toFloat()).apply { setStroke(dp(c, 1), LINE) }
        setPadding(dp(c, 18), dp(c, 16), dp(c, 18), dp(c, 18))
        if (title != null) {
            addView(label(c, title))
            addView(spacer(c, 12))
        }
    }

    fun spacer(c: Context, hDp: Int) = View(c).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(c, hDp))
    }

    fun params(c: Context, topDp: Int = 12) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(c, topDp)
        }

    /** One value with a small label under it. */
    fun stat(c: Context, value: CharSequence, label: String, big: Boolean = false) = vstack(c).apply {
        addView(text(c, value, if (big) 22f else 17f, WHITE, medium = true))
        addView(text(c, label, 9f, FAINT, mono = true).apply { setPadding(0, dp(c, 5), 0, 0) })
    }

    /** Stats laid out in rows of [cols]. */
    fun statGrid(c: Context, items: List<Pair<CharSequence, String>>, cols: Int = 3): LinearLayout {
        val grid = vstack(c, 16)
        items.chunked(cols).forEach { row ->
            val r = hstack(c).apply { gravity = Gravity.TOP }
            row.forEach { (v, l) ->
                r.addView(stat(c, v, l), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            repeat(cols - row.size) {
                r.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
            }
            grid.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return grid
    }

    fun image(c: Context, bmp: Bitmap, heightDp: Int, stretch: Boolean = true) = ImageView(c).apply {
        setImageBitmap(bmp)
        scaleType = if (stretch) ImageView.ScaleType.FIT_XY else ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, heightDp))
    }

    /** Axis labels: [spread] = one centred label per column (days); otherwise quarters + end label. */
    fun axis(c: Context, labels: List<String>, spread: Boolean): LinearLayout {
        val row = hstack(c).apply { setPadding(0, dp(c, 6), 0, 0) }
        labels.forEachIndexed { i, s ->
            val last = !spread && i == labels.lastIndex
            val t = text(c, s, 9f, 0xFF7A7A7A.toInt(), mono = true, gravity = if (spread) Gravity.CENTER else Gravity.START)
            if (last) row.addView(t) else row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return row
    }

    /** Card with a chart and its axis. */
    fun chartCard(c: Context, title: String, bmp: Bitmap, heightDp: Int, axis: List<String>, spread: Boolean, note: String? = null) =
        card(c, title).apply {
            addView(image(c, bmp, heightDp))
            addView(axis(c, axis, spread))
            if (note != null) {
                addView(spacer(c, 10))
                addView(text(c, note, 12f, FAINT))
            }
        }

    /** Big ring with a value in the middle, and a headline + sub to the right. */
    fun hero(c: Context, ring: Bitmap, value: CharSequence, caption: String, headline: CharSequence, sub: CharSequence): LinearLayout {
        val row = hstack(c)
        val box = FrameLayout(c)
        box.addView(ImageView(c).apply { setImageBitmap(ring) }, FrameLayout.LayoutParams(dp(c, 136), dp(c, 136)))
        val center = vstack(c).apply { gravity = Gravity.CENTER }
        center.addView(text(c, value, 30f, WHITE, medium = true, gravity = Gravity.CENTER))
        center.addView(text(c, caption, 9f, FAINT, mono = true, gravity = Gravity.CENTER).apply { setPadding(0, dp(c, 4), 0, 0) })
        box.addView(center, FrameLayout.LayoutParams(dp(c, 136), dp(c, 136)))
        row.addView(box)
        val col = vstack(c).apply { setPadding(dp(c, 18), 0, 0, 0) }
        col.addView(text(c, headline, 26f, WHITE, medium = true))
        col.addView(text(c, sub, 14f, SOFT).apply { setPadding(0, dp(c, 8), 0, 0) })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    fun pill(c: Context, s: String, primary: Boolean, onClick: () -> Unit) = TextView(c).apply {
        text = s
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        setTextColor(if (primary) BG else WHITE)
        background = rounded(if (primary) WHITE else 0xFF1E1E1E.toInt(), dp(c, 24).toFloat())
        setPadding(dp(c, 20), 0, dp(c, 20), 0)
        minHeight = dp(c, 48)
        setOnClickListener { onClick() }
    }

    /** A row of pills that share the width. */
    fun pillRow(c: Context, vararg pills: TextView) = hstack(c).apply {
        pills.forEachIndexed { i, p ->
            addView(p, LinearLayout.LayoutParams(0, dp(c, 48), 1f).apply { if (i > 0) marginStart = dp(c, 8) })
        }
    }
}
