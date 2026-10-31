package com.glassring.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.View
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.Executors

private const val ACTION_NEXT = "com.glassring.widget.NEXT"
private const val ACTION_PREV = "com.glassring.widget.PREV"
private const val ACTION_GOTO = "com.glassring.widget.GOTO"
const val ACTION_RING = "com.glassring.widget.RING"
private const val ACTION_PLUS = "com.glassring.widget.PLUS"
private const val ACTION_STOP = "com.glassring.widget.STOP"
const val ACTION_FOCUS_DONE = "com.glassring.widget.FOCUS_DONE"
const val EXTRA_PAGE = "page"

private const val RING_DP = 76f
private const val CHART_W_DP = 320f
private const val CHART_H_DP = 44f

private val DOTS = intArrayOf(R.id.dot0, R.id.dot1, R.id.dot2, R.id.dot3, R.id.dot4, R.id.dot5)
private val AXIS4 = intArrayOf(R.id.a40, R.id.a41, R.id.a42, R.id.a43, R.id.a44)
private val AXIS7 = intArrayOf(R.id.a70, R.id.a71, R.id.a72, R.id.a73, R.id.a74, R.id.a75, R.id.a76)

private fun widgetIds(c: Context): IntArray =
    AppWidgetManager.getInstance(c).getAppWidgetIds(ComponentName(c, RingWidgetReceiver::class.java))

/** One lock for every widget update, so a background refresh can't undo a page switch. */
internal val updateLock = Any()

/** Off-main-thread work for taps, so the home screen never waits on us. */
private val worker = Executors.newSingleThreadExecutor()

/** Full redraw of every copy of the widget with fresh data. Called on taps, timers and charging ticks. */
fun refreshAll(c: Context) = synchronized(updateLock) {
    val ids = widgetIds(c)
    if (ids.isEmpty() && !Singles.anyPlaced(c)) return@synchronized
    val s = Collect.snapshot(c)
    if (ids.isNotEmpty()) AppWidgetManager.getInstance(c).updateAppWidget(ids, Views.root(c, s))
    Singles.updateAll(c, s)
}

/** Runs [block] on a background thread while keeping the broadcast alive until it's done. */
fun android.content.BroadcastReceiver.inBackground(block: () -> Unit) {
    val pending = goAsync()
    worker.execute {
        try {
            block()
        } catch (e: Exception) {
            android.util.Log.w("GlanceKit", "Widget update failed", e)
        } finally {
            pending.finish()
        }
    }
}

/**
 * Page switch. Only the visible page, title and dots change, sent as a tiny partial update.
 * All pages are already on the home screen, so this is instant and fades between them.
 */
fun showPage(c: Context, page: Int) = synchronized(updateLock) {
    Store.setPage(c, page)
    val ids = widgetIds(c)
    if (ids.isEmpty()) return@synchronized
    val p = Store.page(c)
    val rv = RemoteViews(c.packageName, R.layout.widget_root)
    rv.setDisplayedChild(R.id.flipper, p)
    Views.header(rv, p, Views.titleFor(c, p))
    AppWidgetManager.getInstance(c).partiallyUpdateAppWidget(ids, rv)
}

class RingWidgetReceiver : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        inBackground { refreshAll(context) }
        RefreshWorker.schedule(context)
    }

    override fun onEnabled(context: Context) {
        RefreshWorker.schedule(context)
    }

    /** Last widget removed: stop all background refreshes. */
    override fun onDisabled(context: Context) = stopIfNoWidgets(context)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        if (action !in OUR_ACTIONS) return
        inBackground { handle(context, intent, action) }
    }

    private fun handle(context: Context, intent: Intent, action: String) = synchronized(updateLock) {
        when (action) {
            ACTION_NEXT -> showPage(context, Store.page(context) + 1)
            ACTION_PREV -> showPage(context, Store.page(context) - 1)
            ACTION_GOTO -> showPage(context, intent.getIntExtra(EXTRA_PAGE, 0))
            ACTION_RING -> {
                // The page comes with the tap, so separate widgets act on their own page.
                when (intent.getIntExtra(EXTRA_PAGE, Store.page(context))) {
                    Store.PAGE_TIME -> Store.cycleTimeMode(context)
                    Store.PAGE_STEPS -> Store.toggleStepsWeek(context)
                    Store.PAGE_DATA -> Store.toggleDataWeek(context)
                    Store.PAGE_FOCUS -> Focus.toggle(context)
                    else -> Unit // battery: tap just refreshes
                }
                refreshAll(context)
            }
            ACTION_PLUS -> {
                Focus.addFiveMinutes(context)
                refreshAll(context)
            }
            ACTION_STOP -> {
                Focus.stop(context)
                refreshAll(context)
            }
            ACTION_FOCUS_DONE -> {
                Focus.onAlarm(context)
                refreshAll(context)
            }
            else -> Unit
        }
    }

    companion object {
        val OUR_ACTIONS = setOf(ACTION_NEXT, ACTION_PREV, ACTION_GOTO, ACTION_RING, ACTION_PLUS, ACTION_STOP, ACTION_FOCUS_DONE)
    }
}

/** Builds the RemoteViews for the widget and its four pages. */
object Views {

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val GREY = 0xFF8A8A8A.toInt()

    fun root(c: Context, s: Snapshot): RemoteViews {
        val rv = RemoteViews(c.packageName, R.layout.widget_root)
        rv.removeAllViews(R.id.flipper)
        // Order must match the Store.PAGE_* numbers.
        rv.addView(R.id.flipper, timePage(c, s))
        rv.addView(R.id.flipper, stepsPage(c, s))
        rv.addView(R.id.flipper, batteryPage(c, s))
        rv.addView(R.id.flipper, dataPage(c, s))
        rv.addView(R.id.flipper, focusPage(c, s))
        rv.addView(R.id.flipper, instrumentsPage(c, s))
        val page = Store.page(c)
        rv.setDisplayedChild(R.id.flipper, page)
        header(rv, page, titleFor(s, page))
        rv.setOnClickPendingIntent(R.id.prev, broadcast(c, ACTION_PREV, 1))
        rv.setOnClickPendingIntent(R.id.next, broadcast(c, ACTION_NEXT, 2))
        for (i in DOTS.indices) {
            rv.setOnClickPendingIntent(DOTS[i], broadcast(c, ACTION_GOTO, 10 + i) { putExtra(EXTRA_PAGE, i) })
        }
        return rv
    }

    /** One page on its own, used by the separate widgets when stretched wide. */
    fun page(c: Context, s: Snapshot, page: Int): RemoteViews = when (page) {
        Store.PAGE_STEPS -> stepsPage(c, s)
        Store.PAGE_BATTERY -> batteryPage(c, s)
        Store.PAGE_DATA -> dataPage(c, s)
        Store.PAGE_FOCUS -> focusPage(c, s)
        Store.PAGE_INSTRUMENTS -> instrumentsPage(c, s)
        else -> timePage(c, s)
    }

    fun header(rv: RemoteViews, page: Int, title: String) {
        rv.setTextViewText(R.id.title, title)
        for (i in DOTS.indices) {
            rv.setImageViewResource(DOTS[i], if (i == page) R.drawable.dot_on else R.drawable.dot_off)
        }
    }

    fun titleFor(s: Snapshot, page: Int): String = when (page) {
        Store.PAGE_INSTRUMENTS -> INSTRUMENTS_TITLE
        Store.PAGE_STEPS -> if (s.stepsWeek) "STEPS · LAST 7 DAYS" else "STEPS · TODAY"
        Store.PAGE_BATTERY -> batteryTitle(s.battery)
        Store.PAGE_DATA -> if (s.dataWeek) "DATA · LAST 7 DAYS" else "DATA · TODAY"
        Store.PAGE_FOCUS -> focusTitle(s.focus)
        else -> s.time.label
    }

    /** Cheap version for page switches: reads only what the title needs. */
    fun titleFor(c: Context, page: Int): String = when (page) {
        Store.PAGE_INSTRUMENTS -> INSTRUMENTS_TITLE
        Store.PAGE_STEPS -> if (Store.stepsWeek(c)) "STEPS · LAST 7 DAYS" else "STEPS · TODAY"
        Store.PAGE_BATTERY -> batteryTitle(Collect.battery(c))
        Store.PAGE_DATA -> if (Store.dataWeek(c)) "DATA · LAST 7 DAYS" else "DATA · TODAY"
        Store.PAGE_FOCUS -> focusTitle(Collect.focus(c))
        else -> Collect.time(c).label
    }

    private fun batteryTitle(b: BatteryInfo) = when {
        b.charging -> "BATTERY · CHARGING"
        b.plugged -> "BATTERY · PLUGGED IN"
        else -> "BATTERY · ${b.thermal.uppercase()}"
    }

    private fun focusTitle(f: FocusInfo) = when {
        f.running -> "FOCUS · SESSION ${f.sessions + 1}"
        f.paused -> "FOCUS · PAUSED"
        f.streak > 1 -> "FOCUS · ${f.streak}-DAY STREAK"
        else -> "FOCUS"
    }

    // ---------------- Pages ----------------

    private fun timePage(c: Context, s: Snapshot): RemoteViews {
        val t = s.time
        val rv = RemoteViews(c.packageName, R.layout.page_main)
        val markers = listOfNotNull(t.sunrise, t.sunset)
        rv.setImageViewBitmap(
            R.id.ring,
            RingRenderer.ring(c, RING_DP, listOf(RingRenderer.Arc(0f, t.fraction, WHITE)), markers = markers),
        )
        rv.setTextViewText(R.id.ring_value, number("${(t.fraction * 100).toInt()}", "%"))
        rv.setTextViewText(R.id.ring_caption, t.caption)
        rv.setTextViewText(R.id.headline, t.headline)
        val today = LocalDate.now()
        val week = today.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        when (t.mode) {
            0 -> {
                rv.setTextViewText(R.id.sub, t.sub)
                stats(rv, t.sunrise?.let { clock(it) } ?: "–", "SUNRISE", t.sunset?.let { clock(it) } ?: "–", "SUNSET")
                chart(rv, RingRenderer.daylight(c, CHART_W_DP, CHART_H_DP, t.fraction, t.sunrise, t.sunset))
                axis4(rv, "12 AM", "6 AM", "12 PM", "6 PM", "")
            }
            1 -> {
                rv.setTextViewText(R.id.sub, "Week of ${today.minusDays(today.dayOfWeek.value - 1L).format(dayMonth)}")
                stats(rv, "W$week", "WEEK", "${today.dayOfWeek.value}/7", "DAY")
                chart(rv, RingRenderer.segments(c, CHART_W_DP, CHART_H_DP, 7, t.fraction))
                axis7(rv, Array(7) { java.time.DayOfWeek.of(it + 1).getDisplayName(TextStyle.SHORT, Locale.ENGLISH).uppercase() })
            }
            2 -> {
                val len = today.lengthOfMonth()
                rv.setTextViewText(R.id.sub, today.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " ${today.year}")
                stats(rv, "${today.dayOfMonth}/$len", "DAY", "W$week", "WEEK")
                chart(rv, RingRenderer.segments(c, CHART_W_DP, CHART_H_DP, len, t.fraction))
                axis4(rv, "1", "${1 + len / 4}", "${1 + len / 2}", "${1 + 3 * len / 4}", "$len")
            }
            else -> {
                rv.setTextViewText(R.id.sub, "Q${(today.monthValue - 1) / 3 + 1} of ${today.year}")
                stats(rv, "${today.dayOfYear}", "DAY", "W$week", "WEEK")
                chart(rv, RingRenderer.segments(c, CHART_W_DP, CHART_H_DP, 12, t.fraction))
                axis4(rv, "JAN", "APR", "JUL", "OCT", "DEC")
            }
        }
        ringClick(c, rv, Store.PAGE_TIME)
        return rv
    }

    private fun stepsPage(c: Context, s: Snapshot): RemoteViews {
        val st = s.steps
        val rv = RemoteViews(c.packageName, R.layout.page_main)

        if (!st.available || !st.granted) {
            rv.setImageViewBitmap(R.id.ring, RingRenderer.ring(c, RING_DP, emptyList(), head = false))
            rv.setTextViewText(R.id.ring_value, "–")
            rv.setTextViewText(R.id.ring_caption, "STEPS")
            rv.setTextViewText(R.id.headline, if (st.available) "Count steps" else "No step sensor")
            rv.setTextViewText(R.id.sub, if (st.available) "Tap to allow step counting" else "This phone can't count steps")
            stats(rv, steps(st.goal), "GOAL", "–", "DAY STREAK")
            chart(rv, RingRenderer.bars(c, CHART_W_DP, CHART_H_DP, LongArray(24), null, lastIndex = -1))
            axis4(rv, "12 AM", "6 AM", "12 PM", "6 PM", "")
            // Opens the app, which asks for the permission.
            val open = PendingIntent.getActivity(
                c, 50,
                Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            rv.setOnClickPendingIntent(R.id.ring_box, open)
            rv.setOnClickPendingIntent(R.id.headline, open)
            rv.setOnClickPendingIntent(R.id.sub, open)
            return rv
        }

        val met = st.today >= st.goal
        val frac = (st.today.toFloat() / st.goal).coerceIn(0f, 1f)
        rv.setImageViewBitmap(
            R.id.ring,
            RingRenderer.ring(c, RING_DP, listOf(RingRenderer.Arc(0f, frac, if (met) Config.ACCENT else WHITE))),
        )
        rv.setTextViewText(R.id.ring_value, number("${st.today * 100 / st.goal}", "%"))
        rv.setTextViewText(R.id.ring_caption, if (met) "GOAL MET" else "OF GOAL")

        if (s.stepsWeek) {
            val total = st.week.sum()
            val activeDays = st.week.count { it > 0 }.coerceAtLeast(1)
            rv.setTextViewText(R.id.headline, steps(total))
            rv.setTextViewText(R.id.sub, "steps in 7 days · avg ${steps(total / activeDays)}")
            chart(rv, RingRenderer.bars(c, CHART_W_DP, CHART_H_DP, st.week, null, highlight = 6, minPeak = st.goal, guide = st.goal))
            axis7(rv, lastSevenDayNames())
        } else {
            rv.setTextViewText(R.id.headline, steps(st.today))
            rv.setTextViewText(R.id.sub, String.format(Locale.ENGLISH, "steps · %.1f km", st.km))
            val hour = LocalTime.now().hour
            chart(rv, RingRenderer.bars(c, CHART_W_DP, CHART_H_DP, st.hourly, null, lastIndex = hour, highlight = hour, minPeak = 500L))
            axis4(rv, "12 AM", "6 AM", "12 PM", "6 PM", "")
        }
        stats(
            rv,
            if (met) "Done" else steps(st.goal - st.today), if (met) "GOAL MET" else "TO GOAL",
            "${st.streak}", if (st.streak == 1) "DAY STREAK" else "DAYS STREAK",
        )
        ringClick(c, rv, Store.PAGE_STEPS)
        return rv
    }

    private const val INSTRUMENTS_TITLE = "INSTRUMENTS"

    /** Barometer on the widget, with Compass and Level buttons that open the live instruments. */
    private fun instrumentsPage(c: Context, s: Snapshot): RemoteViews {
        val b = s.baro
        val rv = RemoteViews(c.packageName, R.layout.page_focus)
        val hpa = b.hpa
        // Gauge from 970 to 1050 mbar, with a tick at standard sea-level pressure.
        val frac = if (hpa != null) ((hpa - 970f) / 80f).coerceIn(0f, 1f) else 0f
        rv.setImageViewBitmap(
            R.id.ring,
            RingRenderer.ring(
                c, RING_DP,
                if (hpa != null) listOf(RingRenderer.Arc(0f, frac, WHITE)) else emptyList(),
                markers = listOf((1013.25f - 970f) / 80f),
            ),
        )
        rv.setViewVisibility(R.id.chrono, View.GONE)
        rv.setTextViewText(R.id.ring_value, if (hpa != null) "${hpa.toInt()}" else "–")
        rv.setTextViewText(R.id.ring_caption, "MBAR")
        if (!b.available || hpa == null) {
            rv.setTextViewText(R.id.headline, if (b.available) "Measuring…" else "No barometer")
            rv.setTextViewText(R.id.sub, "Compass and level still work")
        } else {
            rv.setTextViewText(R.id.headline, b.trend)
            val extra = listOfNotNull(b.altitudeM?.let { "≈${it} m" }, b.change3h?.let { String.format(Locale.ENGLISH, "%+.1f/3h", it) })
            rv.setTextViewText(R.id.sub, (listOf(b.hint) + extra).joinToString(" · "))
        }
        rv.setTextViewText(R.id.btn_primary, "Compass")
        rv.setOnClickPendingIntent(R.id.btn_primary, openInstrument(c, "compass", 61))
        rv.setViewVisibility(R.id.btn_secondary, View.VISIBLE)
        rv.setTextViewText(R.id.btn_secondary, "Level")
        rv.setOnClickPendingIntent(R.id.btn_secondary, openInstrument(c, "level", 62))
        rv.setOnClickPendingIntent(R.id.ring_box, openInstrument(c, "barometer", 63))

        val nowMin = System.currentTimeMillis() / 60_000L
        val firstMin = b.history.firstOrNull()?.minute ?: nowMin
        val span = (nowMin - firstMin).coerceIn(60L, 24 * 60L)
        chart(rv, RingRenderer.pressureHistory(c, CHART_W_DP, CHART_H_DP, b.history, span))
        axis4(rv, ago(span) + " AGO", ago(span * 3 / 4), ago(span / 2), ago(span / 4), "NOW")
        return rv
    }

    fun openInstrument(c: Context, tab: String, code: Int): PendingIntent = PendingIntent.getActivity(
        c, code,
        Intent(c, InstrumentsActivity::class.java).putExtra("tab", tab).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Opens the app on one tab. */
    fun openApp(c: Context, tab: String, code: Int): PendingIntent = PendingIntent.getActivity(
        c, code,
        Intent(c, MainActivity::class.java).putExtra("tab", tab)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun steps(n: Long): String = String.format(Locale.ENGLISH, "%,d", n)

    private fun batteryPage(c: Context, s: Snapshot): RemoteViews {
        val b = s.battery
        val rv = RemoteViews(c.packageName, R.layout.page_main)
        rv.setImageViewBitmap(
            R.id.ring,
            RingRenderer.ring(
                c, RING_DP,
                listOf(RingRenderer.Arc(0f, b.level / 100f, if (b.plugged) Config.ACCENT else WHITE)),
                markers = if (b.target < 100) listOf(b.target / 100f) else emptyList(),
            ),
        )
        rv.setTextViewText(R.id.ring_value, number("${b.level}", "%"))
        rv.setTextViewText(R.id.ring_caption, if (b.charging) "CHARGING" else if (b.plugged) "PLUGGED" else "BATTERY")
        rv.setTextViewText(
            R.id.headline,
            when {
                b.charging && b.watts != null && b.watts >= 0.5 -> String.format(Locale.ENGLISH, "+%.1f W", b.watts)
                b.plugged -> "Held at ${b.level}%"
                b.minutesLeft != null -> "${Collect.fmtMinutes(b.minutesLeft.toLong())} left"
                else -> "${b.level}%"
            },
        )
        val first = when {
            b.minutesToTarget != null -> "${b.target}% in ${Collect.fmtMinutes(b.minutesToTarget.toLong())}"
            b.charging -> "Charging to ${b.target}%"
            b.plugged -> "Paused to protect battery"
            else -> "At current use"
        }
        rv.setTextViewText(R.id.sub, "$first · ${b.tempC.toInt()}°C")
        stats(
            rv,
            b.healthPct?.let { "~$it%" } ?: "–", "HEALTH",
            b.cycles?.toString() ?: b.thermal, if (b.cycles != null) "CYCLES" else "THERMAL",
        )

        // The chart covers only the time we have data for (up to 24 hours), so a fresh
        // install doesn't squash everything against the right edge.
        val nowMin = System.currentTimeMillis() / 60_000L
        val firstMin = s.batteryHistory.firstOrNull()?.minute ?: nowMin
        val span = (nowMin - firstMin).coerceIn(60L, 24 * 60L)
        chart(rv, RingRenderer.batteryHistory(c, CHART_W_DP, CHART_H_DP, s.batteryHistory, b.target, span))
        axis4(rv, ago(span) + " AGO", ago(span * 3 / 4), ago(span / 2), ago(span / 4), "NOW")
        ringClick(c, rv, Store.PAGE_BATTERY)
        return rv
    }

    private fun dataPage(c: Context, s: Snapshot): RemoteViews {
        val d = s.data
        val rv = RemoteViews(c.packageName, R.layout.page_main)
        val m = if (s.dataWeek) d.weekMobile.sum() else d.mobileBytes
        val w = if (s.dataWeek) d.weekWifi.sum() else d.wifiBytes
        val total = (m + w).coerceAtLeast(1)
        val mf = m.toFloat() / total
        val gap = if (m > 0 && w > 0) 0.03f else 0f
        rv.setImageViewBitmap(
            R.id.ring,
            RingRenderer.ring(
                c, RING_DP,
                listOf(
                    RingRenderer.Arc(0f, (mf - gap).coerceAtLeast(0f), Config.ACCENT),
                    RingRenderer.Arc(mf, (1f - mf - gap).coerceAtLeast(0f), WHITE),
                ),
                head = false,
            ),
        )
        val pct = if (m + w > 0) (m * 100 / (m + w)).toInt() else 0
        rv.setTextViewText(R.id.ring_value, number("$pct", "%"))
        rv.setTextViewText(R.id.ring_caption, "MOBILE")
        rv.setTextViewText(R.id.headline, Collect.fmtBytes(m))
        rv.setTextViewText(
            R.id.sub,
            (if (s.dataWeek) "mobile, last 7 days" else "mobile today") + if (!d.accurate && !s.dataWeek) " · since install" else "",
        )
        stats(rv, Collect.fmtBytes(w), "WI-FI", Collect.fmtBytes(m + w), "TOTAL")
        if (s.dataWeek) {
            chart(rv, RingRenderer.bars(c, CHART_W_DP, CHART_H_DP, d.weekMobile, d.weekWifi, minPeak = 50_000_000L))
            axis7(rv, lastSevenDayNames())
        } else {
            chart(
                rv,
                RingRenderer.bars(c, CHART_W_DP, CHART_H_DP, d.hourlyMobile, d.hourlyWifi, lastIndex = d.currentHour, minPeak = 10_000_000L),
            )
            axis4(rv, "12 AM", "6 AM", "12 PM", "6 PM", "")
        }
        ringClick(c, rv, Store.PAGE_DATA)
        return rv
    }

    private fun focusPage(c: Context, s: Snapshot): RemoteViews {
        val f = s.focus
        val rv = RemoteViews(c.packageName, R.layout.page_focus)
        val frac = if (f.left > 0 && f.length > 0) f.left.toFloat() / f.length else 0f
        rv.setImageViewBitmap(
            R.id.ring,
            RingRenderer.ring(c, RING_DP, listOf(RingRenderer.Arc(0f, frac, Config.ACCENT)), head = frac > 0f, dottedTrack = true),
        )
        if (f.running) {
            rv.setViewVisibility(R.id.chrono, View.VISIBLE)
            rv.setViewVisibility(R.id.ring_value, View.GONE)
            rv.setChronometer(R.id.chrono, SystemClock.elapsedRealtime() + f.left, null, true)
            rv.setChronometerCountDown(R.id.chrono, true)
            rv.setTextViewText(R.id.ring_caption, "LEFT")
        } else {
            rv.setViewVisibility(R.id.chrono, View.GONE)
            rv.setViewVisibility(R.id.ring_value, View.VISIBLE)
            rv.setTextViewText(R.id.ring_value, mmss(if (f.paused) f.left else Store.focusMinutes(c) * 60_000L))
            rv.setTextViewText(R.id.ring_caption, if (f.paused) "PAUSED" else "READY")
        }
        rv.setTextViewText(R.id.headline, Collect.fmtMinutes(f.todayMs / 60_000L))
        val subParts = mutableListOf("focused today")
        if (f.sessions > 0) subParts += "${f.sessions} done"
        if (f.streak > 1) subParts += "${f.streak}-day streak"
        rv.setTextViewText(R.id.sub, subParts.joinToString(" · "))
        val primary: String
        val secondary: String?
        val secondaryAction: String?
        when {
            f.running -> { primary = "Pause"; secondary = "+5 min"; secondaryAction = ACTION_PLUS }
            f.paused -> { primary = "Resume"; secondary = "Stop"; secondaryAction = ACTION_STOP }
            else -> { primary = "Start ${Store.focusMinutes(c)} min"; secondary = null; secondaryAction = null }
        }
        rv.setTextViewText(R.id.btn_primary, primary)
        rv.setOnClickPendingIntent(R.id.btn_primary, broadcast(c, ACTION_RING, 30) { putExtra(EXTRA_PAGE, Store.PAGE_FOCUS) })
        if (secondary != null && secondaryAction != null) {
            rv.setViewVisibility(R.id.btn_secondary, View.VISIBLE)
            rv.setTextViewText(R.id.btn_secondary, secondary)
            rv.setOnClickPendingIntent(R.id.btn_secondary, broadcast(c, secondaryAction, 31))
        } else {
            rv.setViewVisibility(R.id.btn_secondary, View.GONE)
        }
        // Scale to at least one hour so a few minutes don't fill the chart.
        chart(rv, RingRenderer.bars(c, CHART_W_DP, CHART_H_DP, f.week, null, highlight = 6, minPeak = 60 * 60_000L))
        axis7(rv, lastSevenDayNames())
        ringClick(c, rv, Store.PAGE_FOCUS)
        return rv
    }

    // ---------------- Helpers ----------------

    private val dayMonth = java.time.format.DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    private fun ringClick(c: Context, rv: RemoteViews, page: Int) {
        rv.setOnClickPendingIntent(R.id.ring_box, broadcast(c, ACTION_RING, 20 + page) { putExtra(EXTRA_PAGE, page) })
    }

    private fun chart(rv: RemoteViews, bmp: Bitmap) = rv.setImageViewBitmap(R.id.chart, bmp)

    private fun stats(rv: RemoteViews, v1: String, l1: String, v2: String, l2: String) {
        rv.setTextViewText(R.id.stat1_value, v1)
        rv.setTextViewText(R.id.stat1_label, l1)
        rv.setTextViewText(R.id.stat2_value, v2)
        rv.setTextViewText(R.id.stat2_label, l2)
    }

    /** "18H" or "45M" for axis labels. */
    private fun ago(minutes: Long): String = if (minutes >= 120) "${minutes / 60}H" else "${minutes}M"

    private fun axis4(rv: RemoteViews, vararg labels: String) {
        rv.setViewVisibility(R.id.axis4, View.VISIBLE)
        rv.setViewVisibility(R.id.axis7, View.GONE)
        for (i in AXIS4.indices) rv.setTextViewText(AXIS4[i], labels.getOrElse(i) { "" })
    }

    private fun axis7(rv: RemoteViews, labels: Array<String>) {
        rv.setViewVisibility(R.id.axis4, View.GONE)
        rv.setViewVisibility(R.id.axis7, View.VISIBLE)
        for (i in AXIS7.indices) rv.setTextViewText(AXIS7[i], labels.getOrElse(i) { "" })
    }

    private fun lastSevenDayNames(): Array<String> {
        val today = LocalDate.now()
        return Array(7) { i ->
            if (i == 6) "TODAY"
            else today.minusDays(6L - i).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).uppercase()
        }
    }

    /** "63%" with a smaller, softer unit. */
    fun number(value: String, unit: String): CharSequence {
        val sp = SpannableString(value + unit)
        if (unit.isNotEmpty()) {
            sp.setSpan(RelativeSizeSpan(0.55f), value.length, sp.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(ForegroundColorSpan(GREY), value.length, sp.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sp
    }

    private fun clock(fractionOfDay: Float): String =
        Collect.fmtClock(LocalTime.ofSecondOfDay((fractionOfDay * 86_399).toLong().coerceIn(0, 86_399)))

    private fun mmss(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.ENGLISH, "%02d:%02d", total / 60, total % 60)
    }

    fun broadcast(c: Context, action: String, code: Int, extras: Intent.() -> Unit = {}): PendingIntent {
        val i = Intent(c, RingWidgetReceiver::class.java).setAction(action).apply(extras)
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
