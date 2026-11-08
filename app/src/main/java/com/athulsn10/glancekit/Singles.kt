package com.athulsn10.glancekit

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import java.util.Locale

/**
 * Separate widgets, one per item. Each has two looks and Android picks one by size:
 *  - compact (2×2): title, a ring with the key number, one line under it
 *  - wide (about 4×2 and up): the same page as the all-in-one widget, with its chart
 */
object Singles {

    /** Kind of single widget: which page it shows, its title and the app tab it opens. */
    enum class Kind(val page: Int, val title: String, val tab: String, val cls: Class<out AppWidgetProvider>, val icon: Int) {
        TODAY(Store.PAGE_TIME, "TODAY", "Today", TodayWidget::class.java, R.drawable.ic_w_today),
        STEPS(Store.PAGE_STEPS, "STEPS", "Steps", StepsWidget::class.java, R.drawable.ic_w_steps),
        BATTERY(Store.PAGE_BATTERY, "BATTERY", "Battery", BatteryWidget::class.java, R.drawable.ic_w_battery),
        DATA(Store.PAGE_DATA, "DATA", "Data", DataWidget::class.java, R.drawable.ic_w_data),
        FOCUS(Store.PAGE_FOCUS, "FOCUS", "Focus", FocusWidget::class.java, R.drawable.ic_w_focus),
        PRESSURE(Store.PAGE_INSTRUMENTS, "PRESSURE", "Instruments", PressureWidget::class.java, R.drawable.ic_w_pressure),
        COMPASS(-1, "COMPASS", "Instruments", CompassWidget::class.java, R.drawable.ic_w_compass),
    }

    private const val SMALL_RING_DP = 92f

    private fun ids(c: Context, k: Kind): IntArray =
        AppWidgetManager.getInstance(c).getAppWidgetIds(ComponentName(c, k.cls))

    fun anyPlaced(c: Context) = Kind.values().any { ids(c, it).isNotEmpty() }

    fun updateAll(c: Context, s: Snapshot) {
        val mgr = AppWidgetManager.getInstance(c)
        for (k in Kind.values()) {
            val ids = ids(c, k)
            if (ids.isEmpty()) continue
            runCatching { mgr.updateAppWidget(ids, views(c, s, k)) }
                .onFailure { android.util.Log.w("GlanceKit", "Couldn't update ${k.name} widget", it) }
        }
    }

    /** The live compass widget: a dial that turns, fed by CompassService while the screen is on. */
    fun compassViews(c: Context): RemoteViews {
        val live = CompassService.isLive(c)
        val rv = RemoteViews(c.packageName, R.layout.compass_widget)
        CompassService.bind(c, rv, CompassService.lastHeading(c), live)
        rv.setOnClickPendingIntent(
            R.id.single_root,
            if (live) Views.openInstrument(c, "compass", 130) else CompassService.startIntent(c),
        )
        return rv
    }

    fun updateCompass(c: Context) {
        val ids = ids(c, Kind.COMPASS)
        if (ids.isNotEmpty()) AppWidgetManager.getInstance(c).updateAppWidget(ids, compassViews(c))
        // The all-in-one's compass page: new status and tap action.
        val main = CompassService.mainIds(c)
        if (main.isNotEmpty()) {
            val live = CompassService.isLive(c)
            val rv = RemoteViews(c.packageName, R.layout.widget_root)
            rv.setTextViewText(R.id.status, if (live) "LIVE · TAP FOR FULL COMPASS" else "TAP TO GO LIVE")
            rv.setOnClickPendingIntent(R.id.compass_page,
                if (live) Views.openInstrument(c, "compass", 131) else CompassService.startIntent(c))
            AppWidgetManager.getInstance(c).partiallyUpdateAppWidget(main, rv)
        }
    }

    private fun views(c: Context, s: Snapshot, k: Kind): RemoteViews {
        if (k == Kind.COMPASS) return compassViews(c)
        val small = small(c, s, k)
        val wide = RemoteViews(c.packageName, R.layout.single_wide)
        wide.setTextViewText(R.id.title, Views.titleFor(s, k.page))
        wide.removeAllViews(R.id.single_content)
        val page = Views.page(c, s, k.page)
        // On a separate widget the ring opens the app (Focus and Pressure keep their own taps),
        // so it never changes the all-in-one widget's view.
        if (k != Kind.FOCUS && k != Kind.PRESSURE) {
            page.setOnClickPendingIntent(R.id.ring_box, Views.openApp(c, k.tab, 100 + k.ordinal))
        }
        wide.addView(R.id.single_content, page)
        wide.setOnClickPendingIntent(R.id.title, Views.openApp(c, k.tab, 100 + k.ordinal))
        // Android picks the largest layout that fits the widget's current size.
        return RemoteViews(mapOf(SizeF(100f, 100f) to small, SizeF(250f, 140f) to wide))
    }

    private fun small(c: Context, s: Snapshot, k: Kind): RemoteViews {
        val rv = RemoteViews(c.packageName, R.layout.single_small)
        rv.setTextViewText(R.id.title, k.title)
        rv.setImageViewResource(R.id.title_icon, k.icon)
        rv.setViewVisibility(R.id.chrono, View.GONE)
        rv.setViewVisibility(R.id.ring_value, View.VISIBLE)
        val white = RingRenderer.WHITE
        var open = Views.openApp(c, k.tab, 100 + k.ordinal)

        when (k) {
            Kind.TODAY -> {
                val f = Collect.dayFraction()
                val sky = Device.sky()
                val markers = listOfNotNull(sky.sunrise?.let { it.toSecondOfDay() / 86_400f }, sky.sunset?.let { it.toSecondOfDay() / 86_400f })
                ring(c, rv, listOf(RingRenderer.Arc(0f, f, white)), markers = markers)
                rv.setTextViewText(R.id.ring_value, Views.number("${(f * 100).toInt()}", "%"))
                rv.setTextViewText(R.id.ring_caption, "OF TODAY")
                rv.setTextViewText(R.id.sub, "${Collect.fmtMinutes(((1 - f) * 1440).toLong())} left")
            }
            Kind.STEPS -> {
                val st = s.steps
                if (!st.granted) {
                    ring(c, rv, emptyList(), head = false)
                    rv.setTextViewText(R.id.ring_value, "–")
                    rv.setTextViewText(R.id.ring_caption, "STEPS")
                    rv.setTextViewText(R.id.sub, "Tap to turn on")
                } else {
                    val met = st.today >= st.goal
                    ring(c, rv, listOf(RingRenderer.Arc(0f, (st.today.toFloat() / st.goal).coerceIn(0f, 1f), if (met) Config.ACCENT else white)))
                    rv.setTextViewText(R.id.ring_value, String.format(Locale.ENGLISH, "%,d", st.today))
                    rv.setTextViewText(R.id.ring_caption, if (met) "GOAL MET" else "STEPS")
                    rv.setTextViewText(R.id.sub, String.format(Locale.ENGLISH, "%.1f km · %d%%", st.km, st.today * 100 / st.goal))
                }
            }
            Kind.BATTERY -> {
                val b = s.battery
                ring(c, rv, listOf(RingRenderer.Arc(0f, b.level / 100f, if (b.plugged) Config.ACCENT else white)),
                    markers = if (b.target < 100) listOf(b.target / 100f) else emptyList())
                rv.setTextViewText(R.id.ring_value, Views.number("${b.level}", "%"))
                rv.setTextViewText(R.id.ring_caption, if (b.charging) "CHARGING" else if (b.plugged) "PLUGGED" else "BATTERY")
                rv.setTextViewText(R.id.sub, when {
                    b.charging && b.watts != null && b.watts >= 0.5 -> String.format(Locale.ENGLISH, "+%.1f W", b.watts)
                    b.minutesLeft != null -> "${Collect.fmtMinutes(b.minutesLeft.toLong())} left"
                    b.healthPct != null -> "Health ~${b.healthPct}%"
                    else -> "${b.tempC.toInt()}°C"
                })
            }
            Kind.DATA -> {
                val d = s.data
                val total = (d.mobileBytes + d.wifiBytes).coerceAtLeast(1)
                val mf = d.mobileBytes.toFloat() / total
                val gap = if (d.mobileBytes > 0 && d.wifiBytes > 0) 0.03f else 0f
                ring(c, rv, listOf(
                    RingRenderer.Arc(0f, (mf - gap).coerceAtLeast(0f), Config.ACCENT),
                    RingRenderer.Arc(mf, (1f - mf - gap).coerceAtLeast(0f), white),
                ), head = false)
                val parts = Collect.fmtBytes(d.mobileBytes).split(" ")
                rv.setTextViewText(R.id.ring_value, Views.number(parts[0], " " + parts.getOrElse(1) { "" }))
                rv.setTextViewText(R.id.ring_caption, "MOBILE")
                rv.setTextViewText(R.id.sub, "${Collect.fmtBytes(d.wifiBytes)} Wi-Fi")
            }
            Kind.FOCUS -> {
                val f = s.focus
                val frac = if (f.left > 0 && f.length > 0) f.left.toFloat() / f.length else 0f
                ring(c, rv, listOf(RingRenderer.Arc(0f, frac, Config.ACCENT)), head = frac > 0f, dotted = true)
                if (f.running) {
                    rv.setViewVisibility(R.id.chrono, View.VISIBLE)
                    rv.setViewVisibility(R.id.ring_value, View.GONE)
                    rv.setChronometer(R.id.chrono, SystemClock.elapsedRealtime() + f.left, null, true)
                    rv.setChronometerCountDown(R.id.chrono, true)
                } else {
                    val ms = if (f.paused) f.left else Store.focusMinutes(c) * 60_000L
                    rv.setTextViewText(R.id.ring_value, String.format(Locale.ENGLISH, "%02d:%02d", ms / 60_000, (ms / 1000) % 60))
                }
                rv.setTextViewText(R.id.ring_caption, if (f.running) "TAP TO PAUSE" else if (f.paused) "PAUSED" else "TAP TO START")
                rv.setTextViewText(R.id.sub, "${Collect.fmtMinutes(f.todayMs / 60_000)} today")
                // Tapping the ring starts and pauses the timer; the title still opens the app.
                rv.setOnClickPendingIntent(R.id.ring_box,
                    Views.broadcast(c, ACTION_RING, 120) { putExtra(EXTRA_PAGE, Store.PAGE_FOCUS) })
                rv.setOnClickPendingIntent(R.id.title, open)
                return rv
            }
            Kind.PRESSURE -> {
                val b = s.baro
                val hpa = b.hpa
                ring(c, rv, if (hpa != null) listOf(RingRenderer.Arc(0f, ((hpa - 970f) / 80f).coerceIn(0f, 1f), white)) else emptyList(),
                    markers = listOf((1013.25f - 970f) / 80f))
                rv.setTextViewText(R.id.ring_value, hpa?.let { "${it.toInt()}" } ?: "–")
                rv.setTextViewText(R.id.ring_caption, "MBAR")
                rv.setTextViewText(R.id.sub, if (hpa != null) b.trend else "Measuring…")
            }
            Kind.COMPASS -> Unit // has its own layout, see compassViews()
        }
        rv.setOnClickPendingIntent(R.id.single_root, open)
        return rv
    }

    private fun ring(
        c: Context,
        rv: RemoteViews,
        arcs: List<RingRenderer.Arc>,
        head: Boolean = true,
        markers: List<Float> = emptyList(),
        dotted: Boolean = false,
    ) = rv.setImageViewBitmap(R.id.ring, RingRenderer.ring(c, SMALL_RING_DP, arcs, head, markers, dotted))
}

/** Stops background refreshes only when no Glance Kit widget of any kind is left. */
fun stopIfNoWidgets(c: Context) {
    val main = AppWidgetManager.getInstance(c).getAppWidgetIds(ComponentName(c, RingWidgetReceiver::class.java))
    if (main.isEmpty() && !Singles.anyPlaced(c) && ClockViews.ids(c).isEmpty() && PeopleViews.ids(c).isEmpty()) {
        RefreshWorker.cancelAll(c)
        Focus.cancelAlarm(c)
        Weather.cancel(c)
    }
}

/** Shared behaviour for every separate widget. */
open class SingleWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        inBackground { refreshAll(context) }
        RefreshWorker.schedule(context)
    }

    override fun onEnabled(context: Context) = RefreshWorker.schedule(context)

    override fun onDisabled(context: Context) = stopIfNoWidgets(context)

    /** Resizing switches between the compact and wide look. */
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: android.os.Bundle) {
        inBackground { refreshAll(context) }
    }
}

class TodayWidget : SingleWidget()
class StepsWidget : SingleWidget()
class BatteryWidget : SingleWidget()
class DataWidget : SingleWidget()
class FocusWidget : SingleWidget()
class PressureWidget : SingleWidget()
class CompassWidget : SingleWidget() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        super.onUpdate(context, manager, ids)
        // Turned on in the app before the widget was added: start now (works while the app is open).
        if (CompassService.isLive(context)) CompassService.start(context)
    }

    override fun onDisabled(context: Context) {
        CompassService.stop(context)
        super.onDisabled(context)
    }
}
