package com.glassring.widget

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/** Shared controls for the focus timer: length presets and start / pause / resume / stop. */
object FocusUi {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    val PRESETS = longArrayOf(15, 25, 45, 60)

    /** Runs a timer action off the main thread, refreshes the widgets, then calls [done]. */
    fun act(c: Context, action: (Context) -> Unit, done: () -> Unit) {
        val app = c.applicationContext
        worker.execute {
            synchronized(updateLock) { action(app) }
            runCatching { refreshAll(app) }
            main.post(done)
        }
    }

    fun controls(c: Context, f: FocusInfo, haptics: Haptics, changed: () -> Unit): LinearLayout = Ui.vstack(c).apply {
        if (!f.running && !f.paused) {
            // Pick a length.
            val chosen = Store.focusMinutes(c)
            val row = Ui.hstack(c)
            PRESETS.forEachIndexed { i, m ->
                val on = m == chosen
                row.addView(Ui.text(c, "$m min", 13f, if (on) Ui.BG else Ui.WHITE, medium = true, gravity = Gravity.CENTER).apply {
                    background = Ui.rounded(if (on) Ui.WHITE else 0xFF1E1E1E.toInt(), Ui.dp(c, 18).toFloat())
                    setOnClickListener {
                        Store.setFocusMinutes(c, m)
                        haptics.tick(0.5f)
                        act(c, { }, changed)
                    }
                }, LinearLayout.LayoutParams(0, Ui.dp(c, 36), 1f).apply { if (i > 0) marginStart = Ui.dp(c, 8) })
            }
            addView(row)
            addView(Ui.spacer(c, 12))
        }
        addView(when {
            f.running -> Ui.pillRow(c,
                Ui.pill(c, "Pause", true) { haptics.release(); act(c, Focus::toggle, changed) },
                Ui.pill(c, "+5 min", false) { haptics.tick(0.6f); act(c, Focus::addFiveMinutes, changed) })
            f.paused -> Ui.pillRow(c,
                Ui.pill(c, "Resume", true) { haptics.lock(); act(c, Focus::toggle, changed) },
                Ui.pill(c, "Stop", false) { haptics.clear(); act(c, Focus::stop, changed) })
            else -> Ui.pillRow(c, Ui.pill(c, "Start ${Store.focusMinutes(c)} min", true) {
                haptics.lock(); act(c, Focus::toggle, changed)
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}

/** Full-screen focus: just the burning ring, the time and the controls. Keeps the screen on. */
class FocusActivity : Activity() {

    private lateinit var view: FocusView
    private lateinit var bottom: LinearLayout
    private lateinit var haptics: Haptics

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        haptics = Haptics(this)
        val root = Ui.vstack(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        val top = Ui.hstack(this).apply {
            setPadding(Ui.dp(context, 20), Ui.dp(context, 36), Ui.dp(context, 20), 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(Ui.label(this, "FOCUS"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(TextView(this).apply {
            text = "Done"
            textSize = 14f
            setTextColor(Ui.WHITE)
            setPadding(Ui.dp(context, 16), Ui.dp(context, 8), Ui.dp(context, 16), Ui.dp(context, 8))
            background = Ui.rounded(0xFF1C1C1C.toInt(), Ui.dp(context, 18).toFloat())
            setOnClickListener { finish() }
        })
        root.addView(top)
        view = FocusView(this).apply { onFinished = { load() } }
        root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        bottom = Ui.vstack(this).apply { setPadding(Ui.dp(context, 24), 0, Ui.dp(context, 24), Ui.dp(context, 40)) }
        root.addView(bottom)
        setContentView(root)

        window.setDecorFitsSystemWindows(false)
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        val f = Collect.focus(this)
        view.bind(f, Store.focusMinutes(this))
        bottom.removeAllViews()
        bottom.addView(FocusUi.controls(this, f, haptics) { load() })
    }

    companion object {
        fun open(c: Context) = c.startActivity(Intent(c, FocusActivity::class.java))
    }
}
