package com.athulsn10.glancekit

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Clock widget settings: the background (smoked glass or none). Opens from the widget's own
 * settings (long-press the widget, then the pencil / "Customise"), and from the app's Setup tab.
 * The choice applies to every Clock widget.
 */
class ClockConfigActivity : Activity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Leaving with Back keeps the widget as it is.
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        render()
    }

    private fun render() {
        val c = this
        val list = Ui.vstack(c).apply { setPadding(Ui.dp(c, 20), Ui.dp(c, 28), Ui.dp(c, 20), Ui.dp(c, 32)) }
        list.addView(Ui.text(c, "Clock widget", 28f))
        val glass = ClockStyle.background(c)
        list.addView(Ui.text(c, "Background", 13f, Ui.FAINT, mono = true).apply { setPadding(0, Ui.dp(c, 22), 0, Ui.dp(c, 10)) })
        val bgRow = Ui.hstack(c)
        fun bgPill(label: String, on: Boolean) = Ui.pill(c, label, glass == on) {
            ClockStyle.setBackground(c, on)
            refresh()
            render()
        }
        bgRow.addView(bgPill("Smoked glass", true), LinearLayout.LayoutParams(0, Ui.dp(c, 44), 1f).apply { marginEnd = Ui.dp(c, 10) })
        bgRow.addView(bgPill("None", false), LinearLayout.LayoutParams(0, Ui.dp(c, 44), 1f))
        list.addView(bgRow)
        list.addView(Ui.text(c, "Smoked glass puts a see-through dark layer behind the clock. With None, the clock sits straight on your wallpaper and its text turns dark on light wallpapers.", 13f, Ui.FAINT).apply {
            setPadding(0, Ui.dp(c, 12), 0, 0)
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
}
