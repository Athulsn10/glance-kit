package com.glassring.widget

import android.content.Context
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

private val TICK = longPreferencesKey("tick")

private val White = Color(0xFFFFFFFF)
private val Soft = Color(0xFFB3B3B3)
private val Faint = Color(0xFF8A8A8A)
private val Chip = Color(0xFF1F1F1F)
private val NavBg = Color(0xFF242424)
private val Dot = Color(0xFF4A4A4A)

private const val RING_DP = 96f
private const val CHART_W_DP = 200f

/** Redraws every copy of the widget with fresh data. */
suspend fun refreshAll(c: Context) {
    val manager = GlanceAppWidgetManager(c)
    val ids = manager.getGlanceIds(RingWidget::class.java)
    if (ids.isEmpty()) return
    val widget = RingWidget()
    for (id in ids) {
        updateAppWidgetState(c, id) { it[TICK] = System.currentTimeMillis() }
        widget.update(c, id)
    }
}

class RingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RingWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        RefreshWorker.schedule(context)
    }
}

class RingWidget : GlanceAppWidget() {
    // One layout for every size: renders once per update instead of once per size.
    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val tick = currentState<Preferences>()[TICK] ?: 0L
            val c = LocalContext.current
            // Collect once per refresh, not on every recomposition.
            val s = remember(tick) { Collect.snapshot(c) }
            Content(s)
        }
    }
}

// ---------------------------------------------------------------------------
// Layout
// ---------------------------------------------------------------------------

@Composable
private fun Content(s: Snapshot) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ImageProvider(R.drawable.widget_bg))
            .cornerRadius(28.dp)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 8.dp),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RingArea(s)
            Spacer(GlanceModifier.width(14.dp))
            Column(
                modifier = GlanceModifier.defaultWeight(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (s.page) {
                    Store.PAGE_BATTERY -> BatteryText(s)
                    Store.PAGE_DATA -> DataText(s)
                    Store.PAGE_FOCUS -> FocusText(s.focus)
                    else -> TimeText(s)
                }
            }
        }
        NavBar(s.page)
    }
}

/** ‹  • ▬ • •  › — big arrow buttons on both ends, dots in the middle (tap to jump). */
@Composable
private fun NavBar(page: Int) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavButton(R.drawable.ic_chevron_left, "Previous page", actionRunCallback<PrevPageAction>())
        Spacer(GlanceModifier.defaultWeight())
        for (i in 0 until Store.PAGE_COUNT) {
            Box(
                modifier = GlanceModifier
                    .padding(horizontal = 3.dp, vertical = 10.dp)
                    .clickable(goTo(i)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = GlanceModifier
                        .width(if (i == page) 18.dp else 6.dp)
                        .height(6.dp)
                        .cornerRadius(3.dp)
                        .background(if (i == page) White else Dot),
                ) {}
            }
        }
        Spacer(GlanceModifier.defaultWeight())
        NavButton(R.drawable.ic_chevron_right, "Next page", actionRunCallback<NextPageAction>())
    }
}

private fun goTo(i: Int): Action = when (i) {
    Store.PAGE_BATTERY -> actionRunCallback<GoBatteryAction>()
    Store.PAGE_DATA -> actionRunCallback<GoDataAction>()
    Store.PAGE_FOCUS -> actionRunCallback<GoFocusAction>()
    else -> actionRunCallback<GoTimeAction>()
}

@Composable
private fun NavButton(icon: Int, description: String, onClick: Action) {
    Box(
        modifier = GlanceModifier
            .size(40.dp)
            .cornerRadius(20.dp)
            .background(NavBg)
            .clickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(icon),
            contentDescription = description,
            modifier = GlanceModifier.size(20.dp),
        )
    }
}

@Composable
private fun RingArea(s: Snapshot) {
    val c = LocalContext.current
    val ring = when (s.page) {
        Store.PAGE_BATTERY -> RingRenderer.ring(
            c, RING_DP,
            listOf(RingRenderer.Arc(0f, s.battery.level / 100f, if (s.battery.plugged) Config.ACCENT else RingRenderer.WHITE)),
            markers = if (s.battery.target < 100) listOf(s.battery.target / 100f) else emptyList(),
        )
        Store.PAGE_DATA -> {
            val m = if (s.dataWeek) s.data.weekMobile.sum() else s.data.mobileBytes
            val w = if (s.dataWeek) s.data.weekWifi.sum() else s.data.wifiBytes
            val total = (m + w).coerceAtLeast(1)
            val mf = m.toFloat() / total
            val gap = if (m > 0 && w > 0) 0.025f else 0f
            RingRenderer.ring(
                c, RING_DP,
                listOf(
                    RingRenderer.Arc(0f, (mf - gap).coerceAtLeast(0f), Config.ACCENT),
                    RingRenderer.Arc(mf, (1f - mf - gap).coerceAtLeast(0f), RingRenderer.WHITE),
                ),
                head = false,
            )
        }
        Store.PAGE_FOCUS -> {
            val f = s.focus
            val frac = if (f.left > 0 && f.length > 0) f.left.toFloat() / f.length else 0f
            RingRenderer.ring(
                c, RING_DP,
                listOf(RingRenderer.Arc(0f, frac, Config.ACCENT)),
                head = frac > 0f,
                dottedTrack = true,
            )
        }
        else -> RingRenderer.ring(
            c, RING_DP,
            listOf(RingRenderer.Arc(0f, s.time.fraction, RingRenderer.WHITE)),
            markers = listOfNotNull(s.time.sunrise, s.time.sunset),
        )
    }

    Box(
        modifier = GlanceModifier
            .size(RING_DP.dp)
            .clickable(actionRunCallback<RingTapAction>()),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(ring),
            contentDescription = ringDescription(s),
            modifier = GlanceModifier.fillMaxSize(),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            when (s.page) {
                Store.PAGE_BATTERY -> {
                    BigNumber("${s.battery.level}", "%")
                    Caption(if (s.battery.charging) "CHARGING" else if (s.battery.plugged) "PLUGGED" else "BATTERY")
                }
                Store.PAGE_DATA -> {
                    val m = if (s.dataWeek) s.data.weekMobile.sum() else s.data.mobileBytes
                    val parts = Collect.fmtBytes(m).split(" ")
                    BigNumber(parts[0], parts.getOrElse(1) { "" }, small = true)
                    Caption("MOBILE")
                }
                Store.PAGE_FOCUS -> FocusCountdown(s.focus)
                else -> {
                    BigNumber("${(s.time.fraction * 100).toInt()}", "%")
                    Caption(s.time.caption)
                }
            }
        }
    }
}

private fun ringDescription(s: Snapshot): String = when (s.page) {
    Store.PAGE_BATTERY -> "Battery ${s.battery.level} percent. Tap to switch chart and stats."
    Store.PAGE_DATA -> "Mobile data ${Collect.fmtBytes(s.data.mobileBytes)}. Tap to switch today and week."
    Store.PAGE_FOCUS -> "Focus timer. Tap to start or pause."
    else -> "${(s.time.fraction * 100).toInt()} percent ${s.time.caption.lowercase()}. Tap to change period."
}

@Composable
private fun FocusCountdown(f: FocusInfo) {
    val c = LocalContext.current
    when {
        f.running -> {
            val rv = RemoteViews(c.packageName, R.layout.focus_chronometer).apply {
                setChronometer(R.id.chrono, SystemClock.elapsedRealtime() + f.left, null, true)
                setChronometerCountDown(R.id.chrono, true)
            }
            AndroidRemoteViews(rv)
            Caption("TAP TO PAUSE")
        }
        f.paused -> {
            Text(mmss(f.left), style = mono(18f, White))
            Caption("PAUSED")
        }
        else -> {
            Text(mmss(Config.FOCUS_MINUTES * 60_000L), style = mono(18f, White))
            Caption("TAP TO START")
        }
    }
}

private fun mmss(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(total / 60, total % 60)
}

// ---------------------------------------------------------------------------
// Right-hand side of each page
// ---------------------------------------------------------------------------

@Composable
private fun TimeText(s: Snapshot) {
    Label(s.time.label)
    Headline(s.time.headline)
    Sub(s.time.sub)
    Spacer(GlanceModifier.height(8.dp))
    Row {
        Stat("${s.battery.level}%", if (s.battery.charging) "CHARGING" else "BATTERY")
        Spacer(GlanceModifier.width(6.dp))
        Stat(Collect.fmtBytes(s.data.mobileBytes), "MOBILE")
    }
}

@Composable
private fun BatteryText(s: Snapshot) {
    val b = s.battery
    Label(
        when {
            b.charging -> "BATTERY · CHARGING"
            b.plugged -> "BATTERY · PLUGGED IN"
            else -> "BATTERY · ${b.thermal.uppercase()}"
        },
    )
    Headline(
        when {
            b.charging && b.watts != null && b.watts >= 0.5 -> String.format(java.util.Locale.ENGLISH, "+%.0f W", b.watts)
            b.plugged -> "Held at ${b.level}%"
            b.minutesLeft != null -> "~${Collect.fmtMinutes(b.minutesLeft.toLong())} left"
            else -> "${b.level}%"
        },
    )
    val health = b.healthPct?.let { "Health ~$it%" } ?: "Health: measuring"
    Sub(
        when {
            b.minutesToTarget != null -> "${b.target}% in ${Collect.fmtMinutes(b.minutesToTarget.toLong())} · $health"
            else -> "$health · ${b.tempC.toInt()}°C"
        },
    )
    Spacer(GlanceModifier.height(6.dp))
    if (s.batteryChart) {
        ChartImage(
            RingRenderer.batteryHistory(LocalContext.current, CHART_W_DP, 30f, s.batteryHistory, b.target),
            30f, "Battery level over the last 24 hours",
        )
        AxisLabels("24H AGO", "NOW")
    } else {
        Row {
            Stat(b.healthPct?.let { "$it%" } ?: "–", "HEALTH")
            Spacer(GlanceModifier.width(6.dp))
            Stat(b.cycles?.toString() ?: "–", "CYCLES")
            Spacer(GlanceModifier.width(6.dp))
            Stat("${b.tempC.toInt()}°C", "TEMP")
        }
    }
}

@Composable
private fun DataText(s: Snapshot) {
    val d = s.data
    val c = LocalContext.current
    if (s.dataWeek) {
        Label("DATA · LAST 7 DAYS")
        Headline(Collect.fmtBytes(d.weekMobile.sum()))
        Sub("mobile · ${Collect.fmtBytes(d.weekWifi.sum())} Wi-Fi")
        Spacer(GlanceModifier.height(6.dp))
        ChartImage(
            RingRenderer.bars(c, CHART_W_DP, 28f, d.weekMobile, d.weekWifi, highlight = 6),
            28f, "Data used each day this week",
        )
        AxisLabels("7D AGO", "TODAY")
    } else {
        Label(if (d.accurate) "DATA · TODAY" else "DATA · TODAY (SINCE INSTALL)")
        Headline(Collect.fmtBytes(d.mobileBytes))
        Sub("mobile · ${Collect.fmtBytes(d.wifiBytes)} Wi-Fi")
        Spacer(GlanceModifier.height(6.dp))
        ChartImage(
            RingRenderer.bars(c, CHART_W_DP, 28f, d.hourlyMobile, d.hourlyWifi, lastIndex = d.currentHour),
            28f, "Data used each hour today",
        )
        AxisLabels("12 AM", "11 PM")
    }
}

@Composable
private fun FocusText(f: FocusInfo) {
    Label(if (f.streak > 1) "FOCUS · ${f.streak}-DAY STREAK" else "FOCUS")
    Headline("${Collect.fmtMinutes(f.todayMs / 60_000L)} today")
    Spacer(GlanceModifier.height(4.dp))
    ChartImage(
        RingRenderer.bars(LocalContext.current, CHART_W_DP, 20f, f.week, null, highlight = 6, dimOthers = true),
        20f, "Focus time each day this week",
    )
    Spacer(GlanceModifier.height(6.dp))
    Row {
        when {
            f.running -> {
                Pill("Pause", true, actionRunCallback<RingTapAction>())
                Spacer(GlanceModifier.width(6.dp))
                Pill("+5 min", false, actionRunCallback<FocusPlusAction>())
            }
            f.paused -> {
                Pill("Resume", true, actionRunCallback<RingTapAction>())
                Spacer(GlanceModifier.width(6.dp))
                Pill("Stop", false, actionRunCallback<FocusStopAction>())
            }
            else -> Pill("Start ${Config.FOCUS_MINUTES} min", true, actionRunCallback<RingTapAction>())
        }
    }
}

// ---------------------------------------------------------------------------
// Small building blocks
// ---------------------------------------------------------------------------

private fun mono(sizeSp: Float, color: Color) =
    TextStyle(color = ColorProvider(color), fontSize = sizeSp.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)

@Composable
private fun ChartImage(bmp: android.graphics.Bitmap, heightDp: Float, description: String) {
    Image(
        provider = ImageProvider(bmp),
        contentDescription = description,
        contentScale = ContentScale.FillBounds,
        modifier = GlanceModifier.fillMaxWidth().height(heightDp.dp),
    )
}

@Composable
private fun AxisLabels(left: String, right: String) {
    Row(modifier = GlanceModifier.fillMaxWidth().padding(top = 2.dp)) {
        Text(left, style = mono(8f, Faint), maxLines = 1)
        Spacer(GlanceModifier.defaultWeight())
        Text(right, style = mono(8f, Faint), maxLines = 1)
    }
}

@Composable
private fun BigNumber(value: String, unit: String, small: Boolean = false) {
    val big = if (small) 20f else 26f
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, style = TextStyle(color = ColorProvider(White), fontSize = big.sp, fontWeight = FontWeight.Bold))
        if (unit.isNotEmpty()) {
            Text(
                unit,
                style = TextStyle(color = ColorProvider(Soft), fontSize = (big * 0.5f).sp, fontWeight = FontWeight.Medium),
                modifier = GlanceModifier.padding(start = 1.dp, bottom = 3.dp),
            )
        }
    }
}

@Composable
private fun Caption(text: String) {
    Text(text, style = mono(8f, Faint), maxLines = 1)
}

@Composable
private fun Label(text: String) {
    Text(text, style = mono(10f, Faint), maxLines = 1)
}

@Composable
private fun Headline(text: String) {
    Text(
        text,
        style = TextStyle(color = ColorProvider(White), fontSize = 22.sp, fontWeight = FontWeight.Bold),
        maxLines = 1,
        modifier = GlanceModifier.padding(top = 2.dp, bottom = 1.dp),
    )
}

@Composable
private fun Sub(text: String) {
    Text(text, style = TextStyle(color = ColorProvider(Soft), fontSize = 12.sp), maxLines = 1)
}

@Composable
private fun Stat(value: String, label: String) {
    Column(
        modifier = GlanceModifier
            .cornerRadius(10.dp)
            .background(Chip)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        Text(value, style = TextStyle(color = ColorProvider(White), fontSize = 13.sp, fontWeight = FontWeight.Bold), maxLines = 1)
        Text(label, style = mono(7f, Faint), maxLines = 1)
    }
}

@Composable
private fun Pill(text: String, primary: Boolean, onClick: Action) {
    Box(
        modifier = GlanceModifier
            .height(32.dp)
            .cornerRadius(16.dp)
            .background(if (primary) White else NavBg)
            .padding(horizontal = 14.dp)
            .clickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = TextStyle(
                color = ColorProvider(if (primary) Color(0xFF0B0B0B) else White),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------
// Taps
// ---------------------------------------------------------------------------

private suspend fun goPage(context: Context, page: Int) {
    Store.setPage(context, page)
    refreshAll(context)
}

class NextPageAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        goPage(context, Store.page(context) + 1)
}

class PrevPageAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        goPage(context, Store.page(context) - 1)
}

class GoTimeAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        goPage(context, Store.PAGE_TIME)
}

class GoBatteryAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        goPage(context, Store.PAGE_BATTERY)
}

class GoDataAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        goPage(context, Store.PAGE_DATA)
}

class GoFocusAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        goPage(context, Store.PAGE_FOCUS)
}

/** Tapping the ring switches the view on each page. */
class RingTapAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        when (Store.page(context)) {
            Store.PAGE_TIME -> Store.cycleTimeMode(context)
            Store.PAGE_BATTERY -> Store.toggleBatteryChart(context)
            Store.PAGE_DATA -> Store.toggleDataWeek(context)
            Store.PAGE_FOCUS -> Focus.toggle(context)
        }
        refreshAll(context)
    }
}

class FocusPlusAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Focus.addFiveMinutes(context)
        refreshAll(context)
    }
}

class FocusStopAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Focus.stop(context)
        refreshAll(context)
    }
}
