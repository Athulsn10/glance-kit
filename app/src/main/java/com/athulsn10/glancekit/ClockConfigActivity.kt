package com.athulsn10.glancekit

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Clock widget settings: the background (smoked glass or none) and the font for the time. Opens from the widget's own settings
 * (long-press the widget, then the pencil / "Customise"), and from the app's Setup tab.
 * The choice applies to every Clock widget.
 */
class ClockConfigActivity : Activity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Leaving with Back keeps the widget (it works with the current font).
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        render()
    }

    private fun render() {
        val c = this
        val chosen = ClockFont.current(c)
        val list = Ui.vstack(c).apply { setPadding(Ui.dp(c, 20), Ui.dp(c, 28), Ui.dp(c, 20), Ui.dp(c, 32)) }
        list.addView(Ui.text(c, "Clock widget", 28f))
        val glass = ClockFont.background(c)
        list.addView(Ui.text(c, "Background", 13f, Ui.FAINT, mono = true).apply { setPadding(0, Ui.dp(c, 22), 0, Ui.dp(c, 10)) })
        val bgRow = Ui.hstack(c)
        fun bgPill(label: String, on: Boolean) = Ui.pill(c, label, glass == on) {
            ClockFont.setBackground(c, on)
            refresh()
            render()
        }
        bgRow.addView(bgPill("Smoked glass", true), LinearLayout.LayoutParams(0, Ui.dp(c, 44), 1f).apply { marginEnd = Ui.dp(c, 10) })
        bgRow.addView(bgPill("None", false), LinearLayout.LayoutParams(0, Ui.dp(c, 44), 1f))
        list.addView(bgRow)
        list.addView(Ui.text(c, "Font", 13f, Ui.FAINT, mono = true).apply { setPadding(0, Ui.dp(c, 26), 0, 0) })
        list.addView(Ui.text(c, "The font for the time.", 14f, Ui.SOFT).apply {
            setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 6))
        })
        for (f in ClockFont.entries) {
            val on = f == chosen
            val row = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(Ui.dp(c, 18), Ui.dp(c, 12), Ui.dp(c, 18), Ui.dp(c, 12))
                background = GradientDrawable().apply {
                    setColor(if (on) 0xFF1E1A16.toInt() else 0xFF141414.toInt())
                    cornerRadius = Ui.dp(c, 22).toFloat()
                    setStroke(Ui.dp(c, if (on) 2 else 1), if (on) 0xFFFFB547.toInt() else Ui.LINE)
                }
                isClickable = true
                contentDescription = "${f.label}, ${f.note}" + if (on) ", selected" else ""
                setOnClickListener { pick(f) }
            }
            row.addView(TextView(c).apply {
                text = "4:10"
                textSize = 46f
                letterSpacing = -0.02f
                includeFontPadding = false
                setTextColor(Ui.WHITE)
                typeface = ClockFont.typeface(c, f.light)
                minWidth = Ui.dp(c, 128)
            })
            val words = Ui.vstack(c).apply { setPadding(Ui.dp(c, 14), 0, 0, 0) }
            words.addView(Ui.text(c, f.label, 17f, Ui.WHITE, medium = true))
            words.addView(Ui.text(c, f.note, 13f, Ui.FAINT).apply { setPadding(0, Ui.dp(c, 4), 0, 0) })
            row.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (on) row.addView(Ui.text(c, "✓", 20f, 0xFFFFB547.toInt()))
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = Ui.dp(c, 10) })
        }
        list.addView(Ui.text(c, "Fonts: SIL Open Font License, from Google Fonts.", 11f, Ui.FAINT).apply {
            setPadding(0, Ui.dp(c, 20), 0, 0)
        })
        setContentView(ScrollView(c).apply {
            setBackgroundColor(Ui.BG)
            fitsSystemWindows = true
            addView(list)
        })
    }

    private fun refresh() {
        val app = applicationContext
        Thread { runCatching { ClockViews.update(app) } }.start()
    }

    private fun pick(f: ClockFont) {
        ClockFont.set(this, f)
        refresh()
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}
