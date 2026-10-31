package com.glassring.widget

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
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

/** One-time setup screen: add the widget, optionally turn on usage access. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var stepsButton: Button
    private var lastAccess: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RefreshWorker.schedule(this)

        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(72), dp(24), dp(40))
        }

        fun text(s: String, size: Float, color: Int, mono: Boolean = false, bold: Boolean = false, bottom: Int = 12) =
            TextView(this).apply {
                text = s
                textSize = size
                setTextColor(color)
                typeface = when {
                    mono -> Typeface.MONOSPACE
                    bold -> Typeface.DEFAULT_BOLD
                    else -> Typeface.DEFAULT
                }
                setPadding(0, 0, 0, dp(bottom))
            }

        fun button(label: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTextColor(if (primary) 0xFF0B0B0B.toInt() else Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(if (primary) Color.WHITE else 0xFF242424.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
                topMargin = dp(10)
            }
            setOnClickListener { onClick() }
        }

        val grey = 0xFFB3B3B3.toInt()
        val faint = 0xFF8A8A8A.toInt()

        col.addView(text("GLANCE KIT", 12f, faint, mono = true))
        col.addView(text("Your day, battery, data and focus in one widget.", 26f, Color.WHITE, bold = true, bottom = 16))
        col.addView(
            text(
                "Use the arrows at the top of the widget to change page, or tap a dot to jump. " +
                    "Tap the ring to switch views: day, week, month or year on the time page, " +
                    "today or 7 days on steps and data, and start or pause on focus.",
                15f, grey, bottom = 8,
            ),
        )

        col.addView(button("Add widget to home screen", primary = true) {
            val mgr = getSystemService(AppWidgetManager::class.java)
            if (mgr != null && mgr.isRequestPinAppWidgetSupported) {
                mgr.requestPinAppWidget(ComponentName(this, RingWidgetReceiver::class.java), null, null)
            } else {
                status.text = "Long-press your home screen, then Widgets > Glance Kit."
            }
        })

        stepsButton = button("Allow step counting", primary = false) { askForSteps() }
        col.addView(stepsButton)
        col.addView(button("Open compass & level", primary = false) {
            startActivity(Intent(this, InstrumentsActivity::class.java))
        })

        col.addView(text("", 8f, grey, bottom = 24))
        col.addView(text("OPTIONAL · FULL-DAY DATA", 12f, faint, mono = true))
        col.addView(
            text(
                "The data page works without any permission, counting from when you installed the app. " +
                    "Turn on usage access to count the whole day.\n\n" +
                    "If Android says \"App was denied access\": tap Open app info, tap ⋮ in the top corner, " +
                    "choose Allow restricted settings, then come back and tap Usage access again. " +
                    "Android does this for apps installed outside the Play Store.",
                14f, grey, bottom = 4,
            ),
        )
        status = text("", 12f, Config.ACCENT, mono = true, bottom = 0)
        col.addView(status)
        col.addView(button("Usage access", primary = false) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        })
        col.addView(button("Open app info", primary = false) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF0B0B0B.toInt())
            addView(col)
        })
    }

    /** Steps need the "physical activity" permission. Asked once automatically, then from the button. */
    private fun askForSteps() {
        if (!StepCounter.granted(this)) {
            requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateStepsButton()
        CoroutineScope(Dispatchers.Default).launch { runCatching { refreshAll(applicationContext) } }
    }

    private fun updateStepsButton() {
        val ok = StepCounter.granted(this)
        stepsButton.text = if (ok) "Step counting is on" else "Allow step counting"
        stepsButton.isEnabled = !ok
        stepsButton.alpha = if (ok) 0.5f else 1f
    }

    override fun onResume() {
        super.onResume()
        updateStepsButton()
        val p = Store.prefs(this)
        if (StepCounter.available(this) && !StepCounter.granted(this) && !p.getBoolean("asked_steps", false)) {
            p.edit().putBoolean("asked_steps", true).apply()
            askForSteps()
        }
        val access = Collect.hasUsageAccess(this)
        status.text = if (access) "USAGE ACCESS: ON" else "USAGE ACCESS: OFF"
        // Redraw only when the permission actually changed.
        if (lastAccess != null && lastAccess != access) {
            CoroutineScope(Dispatchers.Default).launch { runCatching { refreshAll(applicationContext) } }
        }
        lastAccess = access
    }
}
