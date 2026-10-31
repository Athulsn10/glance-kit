package com.glassring.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** One-time setup screen: grant data access and add the widget. */
class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RefreshWorker.schedule(this)

        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(72), dp(24), dp(32))
            setBackgroundColor(0xFF0B0B0B.toInt())
        }

        fun text(s: String, size: Float, color: Int, mono: Boolean = false) = TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (mono) typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(12))
        }

        fun button(label: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTextColor(if (primary) 0xFF0B0B0B.toInt() else Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(if (primary) Color.WHITE else 0x24FFFFFF)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
                topMargin = dp(12)
            }
            setOnClickListener { onClick() }
        }

        col.addView(text("GLANCE KIT", 12f, 0x99FFFFFF.toInt(), mono = true))
        col.addView(text("Your day, battery, data and focus in one widget.", 26f, Color.WHITE))
        col.addView(
            text(
                "Tap the text side of the widget to slide to the next page, or tap a dot to jump. " +
                    "Tap the ring for extras: week, month and year on the time page, and start or pause on the focus page.",
                15f, 0xB3FFFFFF.toInt(),
            ),
        )

        status = text("", 13f, 0xFFFF6A3D.toInt(), mono = true)
        col.addView(status)

        col.addView(button("1. Allow data usage access", primary = false) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        })
        col.addView(button("2. Add widget to home screen", primary = true) {
            val mgr = getSystemService(AppWidgetManager::class.java)
            if (mgr != null && mgr.isRequestPinAppWidgetSupported) {
                mgr.requestPinAppWidget(ComponentName(this, RingWidgetReceiver::class.java), null, null)
            } else {
                status.text = "Long-press your home screen > Widgets > Glass Ring."
            }
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF0B0B0B.toInt())
            addView(col)
        })
    }

    override fun onResume() {
        super.onResume()
        status.text = if (Collect.hasUsageAccess(this)) "DATA ACCESS: ON" else "DATA ACCESS: OFF · STEP 1 NEEDED"
        // Redraw widgets so a newly granted permission shows straight away.
        CoroutineScope(Dispatchers.Default).launch { runCatching { refreshAll(applicationContext) } }
    }
}
