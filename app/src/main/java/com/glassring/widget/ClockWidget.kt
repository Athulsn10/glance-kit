package com.glassring.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Bundle
import android.provider.AlarmClock
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Clock widget: the time (ticks by itself, no updates needed), date, next alarm, the sun or the
 * moon as it looks right now, and today's sun path with sunrise and sunset on it.
 * The sun's colour follows its height: deep red at the horizon, orange, then pale yellow at midday.
 */
class ClockWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        inBackground { ClockViews.update(context) }
        RefreshWorker.schedule(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        inBackground { ClockViews.update(context) } // resized: redraw the graph at the new width
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED) inBackground { ClockViews.update(context) }
    }

    override fun onDisabled(context: Context) = stopIfNoWidgets(context)
}

object ClockViews {

    fun ids(c: Context): IntArray =
        AppWidgetManager.getInstance(c).getAppWidgetIds(ComponentName(c, ClockWidget::class.java))

    fun update(c: Context) {
        val mgr = AppWidgetManager.getInstance(c)
        for (id in ids(c)) {
            val w = mgr.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 280)
            runCatching { mgr.updateAppWidget(id, views(c, w.toFloat())) }
                .onFailure { android.util.Log.w("GlanceKit", "Couldn't update clock widget", it) }
        }
    }

    fun views(c: Context, widthDp: Float): RemoteViews {
        val rv = RemoteViews(c.packageName, R.layout.clock_widget)
        val sun = Device.sunNow()
        val sky = Device.sky()

        // Sun (coloured by its height) or tonight's moon.
        val icon = if (sun.elevationDeg > -4) sunIcon(c, 72f, sun.elevationDeg)
        else SkyArt.moon(c, 72f, (Device.moon().age / 29.530588853).toFloat())
        rv.setImageViewBitmap(R.id.clock_sky, icon)

        // Next alarm.
        val alarm = Device.nextAlarm(c)
        rv.setTextViewText(R.id.clock_alarm, alarmText(alarm))
        rv.setTextColor(R.id.clock_alarm, if (alarm != null) Config.ACCENT else 0xFF8A8A8A.toInt())

        // Sun path for today.
        rv.setImageViewBitmap(R.id.clock_graph, sunPath(c, (widthDp - 32f).coerceAtLeast(160f), 48f, sky.sunrise, sky.sunset))

        // Taps: the time opens the Clock app; the alarm opens that alarm.
        val clock = clockAppIntent(c, 150)
        rv.setOnClickPendingIntent(R.id.clock_box, clock)
        rv.setOnClickPendingIntent(R.id.clock_sky, clock)
        val show = c.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.showIntent
        rv.setOnClickPendingIntent(R.id.clock_alarm, show ?: clock)
        rv.setOnClickPendingIntent(R.id.clock_graph, Views.openApp(c, "today", 151))
        return rv
    }

    private fun alarmText(at: java.time.LocalDateTime?): String {
        if (at == null) return "NO ALARM SET"
        val time = at.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
        val days = ChronoUnit.DAYS.between(LocalDate.now(), at.toLocalDate())
        val day = when (days) {
            0L -> "TODAY"
            1L -> "TOMORROW"
            else -> at.format(DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)).uppercase()
        }
        return "ALARM  $time · $day"
    }

    /** The phone's own Clock app, found through the "show alarms" action every clock app handles. */
    fun clockAppIntent(c: Context, code: Int): PendingIntent {
        val pm = c.packageManager
        val show = Intent(AlarmClock.ACTION_SHOW_ALARMS)
        val pkg = runCatching { pm.resolveActivity(show, 0)?.activityInfo?.packageName }.getOrNull()
        val launch = pkg?.let { pm.getLaunchIntentForPackage(it) } ?: show
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(c, code, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    // ---------------- Drawing ----------------

    /** Real-life sun colour for a height above the horizon, in degrees. */
    fun sunColor(elevation: Double): Int {
        val stops = arrayOf(
            -4.0 to 0xFFB8321F.toInt(),  // twilight: deep red
            0.0 to 0xFFE8482A.toInt(),   // on the horizon: red-orange
            4.0 to 0xFFFF6E2E.toInt(),   // golden hour: orange
            10.0 to 0xFFFF9A3D.toInt(),  // low sun: amber
            20.0 to 0xFFFFC452.toInt(),  // morning / afternoon: warm yellow
            35.0 to 0xFFFFE08A.toInt(),  // high: soft yellow
            55.0 to 0xFFFFF3CF.toInt(),  // midday: pale, almost white
        )
        if (elevation <= stops.first().first) return stops.first().second
        if (elevation >= stops.last().first) return stops.last().second
        for (i in 0 until stops.size - 1) {
            val (e0, c0) = stops[i]
            val (e1, c1) = stops[i + 1]
            if (elevation in e0..e1) return mix(c0, c1, ((elevation - e0) / (e1 - e0)).toFloat())
        }
        return stops.last().second
    }

    private fun mix(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )

    private fun lighter(col: Int, t: Float) = mix(col, Color.WHITE, t)

    /** Sun disc in its real colour, with a glow; near the horizon it sits half below a horizon line. */
    fun sunIcon(c: Context, sizeDp: Float, elevation: Double): Bitmap {
        val px = max(32, (sizeDp * c.resources.displayMetrics.density).toInt())
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val col = sunColor(elevation)
        val cx = px / 2f
        val r = px * 0.24f
        val low = elevation < 3
        val cy = if (low) px * 0.58f else px / 2f
        val glowR = px * 0.5f
        cv.drawCircle(cx, cy, glowR, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx, cy, glowR,
                intArrayOf(Color.argb(if (low) 120 else 90, Color.red(col), Color.green(col), Color.blue(col)), Color.TRANSPARENT),
                floatArrayOf(0.35f, 1f), Shader.TileMode.CLAMP)
        })
        if (low) cv.clipRect(0f, 0f, px.toFloat(), cy + r * 0.25f)
        cv.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx - r * 0.3f, cy - r * 0.3f, r * 1.4f,
                intArrayOf(lighter(col, 0.55f), col, mix(col, Color.BLACK, 0.12f)), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        })
        if (low) {
            cv.drawLine(px * 0.12f, cy + r * 0.25f, px * 0.88f, cy + r * 0.25f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF8A8A8A.toInt(); strokeWidth = px * 0.02f; strokeCap = Paint.Cap.ROUND
            })
        }
        return bmp
    }

    /**
     * Today's sun path: a curve above the horizon in daylight and dipping below at night.
     * The part already passed is bright; a dot marks the sun (or moon) now; sunrise and sunset
     * are marked on the horizon with their times.
     */
    fun sunPath(c: Context, widthDp: Float, heightDp: Float, rise: LocalTime?, set: LocalTime?): Bitmap {
        val s = c.resources.displayMetrics.density.coerceAtMost(3f)
        val w = (widthDp * s).toInt().coerceAtLeast(64)
        val h = (heightDp * s).toInt().coerceAtLeast(24)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(RingRenderer.BG)
        val cv = Canvas(bmp)
        val r0 = rise?.toSecondOfDay()?.div(86400f) ?: 0.25f
        val s0 = set?.toSecondOfDay()?.div(86400f) ?: 0.75f
        val night = (1f - (s0 - r0)).coerceAtLeast(0.01f)
        val horizon = h * 0.60f
        val up = h * 0.50f
        val down = h * 0.22f
        val pad = 6f * s
        fun x(t: Float) = pad + t * (w - 2 * pad)
        fun y(t: Float): Float {
            val v = when {
                t in r0..s0 -> sin(PI * (t - r0) / (s0 - r0)).toFloat()
                t > s0 -> -sin(PI * (t - s0) / night).toFloat()
                else -> -sin(PI * (t + 1 - s0) / night).toFloat()
            }
            return if (v >= 0) horizon - v * up else horizon - v * down
        }
        val now = LocalTime.now().toSecondOfDay() / 86400f
        val steps = 120

        // Soft fill under the daylight part of the curve.
        val fill = Path().apply {
            moveTo(x(r0), horizon)
            for (i in 0..steps) { val t = r0 + (s0 - r0) * i / steps; lineTo(x(t), y(t)) }
            lineTo(x(s0), horizon); close()
        }
        val noonCol = sunColor(30.0)
        cv.drawPath(fill, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, horizon - up, 0f, horizon,
                Color.argb(60, Color.red(noonCol), Color.green(noonCol), Color.blue(noonCol)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        })

        // Horizon.
        cv.drawLine(pad, horizon, w - pad, horizon, Paint().apply { color = 0xFF2E2E2E.toInt(); strokeWidth = 1f * s })

        // The curve: passed part bright, the rest dim; the night part dashed.
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * s; strokeCap = Paint.Cap.ROUND }
        val dash = DashPathEffect(floatArrayOf(3f * s, 3f * s), 0f)
        var prevX = x(0f); var prevY = y(0f)
        for (i in 1..steps * 2) {
            val t = i / (steps * 2f)
            val nx = x(t); val ny = y(t)
            val day = t in r0..s0
            line.pathEffect = if (day) null else dash
            line.color = when {
                t <= now && day -> Color.WHITE
                t <= now -> 0xFF6A6A6A.toInt()
                day -> 0xFF3E3E3E.toInt()
                else -> 0xFF2A2A2A.toInt()
            }
            cv.drawLine(prevX, prevY, nx, ny, line)
            prevX = nx; prevY = ny
        }

        // Sunrise and sunset marks with their times.
        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8A8A8A.toInt(); strokeWidth = 1.5f * s; strokeCap = Paint.Cap.ROUND }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF9A9A9A.toInt(); textSize = 9.5f * s; typeface = Typeface.MONOSPACE
        }
        val fmt = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
        if (rise != null) {
            cv.drawLine(x(r0), horizon - 3 * s, x(r0), horizon + 3 * s, tick)
            label.textAlign = Paint.Align.LEFT
            cv.drawText("↑ ${rise.format(fmt)}", (x(r0) - 14 * s).coerceAtLeast(0f), h - 2f * s, label)
        }
        if (set != null) {
            cv.drawLine(x(s0), horizon - 3 * s, x(s0), horizon + 3 * s, tick)
            label.textAlign = Paint.Align.RIGHT
            cv.drawText("↓ ${set.format(fmt)}", (x(s0) + 14 * s).coerceAtMost(w.toFloat()), h - 2f * s, label)
        }

        // Now: the sun in its colour, or a pale moon dot at night.
        val nx = x(now); val ny = y(now)
        val dayNow = now in r0..s0
        val dotCol = if (dayNow) sunColor(Device.sunNow().elevationDeg) else 0xFFD8DEE8.toInt()
        cv.drawCircle(nx, ny, 9f * s, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(nx, ny, 9f * s,
                intArrayOf(Color.argb(110, Color.red(dotCol), Color.green(dotCol), Color.blue(dotCol)), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP)
        })
        cv.drawCircle(nx, ny, 4f * s, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dotCol })
        return bmp
    }
}
