package com.glassring.widget

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
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
private val PAGE_PARAM = ActionParameters.Key<Int>("page")

private val White = Color(0xFFFFFFFF)
private val Soft = Color(0xB3FFFFFF)
private val Faint = Color(0x99FFFFFF)
private val ChipBg = Color(0x14FFFFFF)

/** Redraws every copy of the widget with fresh data. */
suspend fun refreshAll(c: Context) {
    val manager = GlanceAppWidgetManager(c)
    val widget = RingWidget()
    for (id in manager.getGlanceIds(RingWidget::class.java)) {
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
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            // Reading the tick makes the widget redraw whenever refreshAll() runs.
            currentState<Preferences>()[TICK]
            val c = LocalContext.current
            Content(Collect.snapshot(c))
        }
    }
}

// ---------------------------------------------------------------------------
// Layout
// ---------------------------------------------------------------------------

@Composable
private fun Content(s: Snapshot) {
    val size: DpSize = LocalSize.current
    val ringDp = minOf(size.height.value - 30f, size.width.value * 0.42f).coerceIn(72f, 150f)

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(28.dp)
            .background(ImageProvider(R.drawable.glass_bg)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Row(
            modifier = GlanceModifier.fillMaxSize().padding(start = 14.dp, end = 16.dp, top = 10.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RingArea(s, ringDp)
            Spacer(GlanceModifier.width(14.dp))
            // Tapping the text side slides to the next page.
            Column(
                modifier = GlanceModifier
                    .defaultWeight()
                    .fillMaxHeight()
                    .clickable(actionRunCallback<NextPageAction>()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (s.page) {
                    Store.PAGE_BATTERY -> BatteryText(s.battery)
                    Store.PAGE_DATA -> DataText(s.data, size.width.value - ringDp - 60f)
                    Store.PAGE_FOCUS -> FocusText(s.focus)
                    else -> TimeText(s)
                }
            }
        }
        PageDots(s.page)
    }
}

@Composable
private fun PageDots(page: Int) {
    Row(
        modifier = GlanceModifier.padding(bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until Store.PAGE_COUNT) {
            // Each dot has a bigger invisible tap area so it's easy to hit.
            Box(
                modifier = GlanceModifier
                    .padding(horizontal = 4.dp, vertical = 6.dp)
                    .clickable(actionRunCallback<GoToPageAction>(actionParametersOf(PAGE_PARAM to i))),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = GlanceModifier
                        .width(if (i == page) 14.dp else 4.dp)
                        .height(4.dp)
                        .cornerRadius(2.dp)
                        .background(if (i == page) White else Color(0x4DFFFFFF)),
                ) {}
            }
        }
    }
}

@Composable
private fun RingArea(s: Snapshot, ringDp: Float) {
    val c = LocalContext.current
    val ring = when (s.page) {
        Store.PAGE_BATTERY -> RingRenderer.ring(
            c, ringDp,
            listOf(RingRenderer.Arc(0f, s.battery.level / 100f, 0xFFFFFFFF.toInt(), true)),
            markers = if (s.battery.target < 100) listOf(s.battery.target / 100f) else emptyList(),
        )
        Store.PAGE_DATA -> {
            val total = (s.data.mobileBytes + s.data.wifiBytes).coerceAtLeast(1)
            val m = if (s.data.hasAccess) s.data.mobileBytes.toFloat() / total else 0f
            val gap = 0.02f
            RingRenderer.ring(
                c, ringDp,
                listOf(
                    RingRenderer.Arc(0f, (m - gap).coerceAtLeast(0f), Config.ACCENT, true),
                    RingRenderer.Arc(m, (1f - m - gap).coerceAtLeast(0f), 0xD9FFFFFF.toInt(), false),
                ),
                head = false,
            )
        }
        Store.PAGE_FOCUS -> {
            val f = s.focus
            val frac = if (f.left > 0 && f.length > 0) f.left.toFloat() / f.length else 0f
            RingRenderer.ring(
                c, ringDp,
                listOf(RingRenderer.Arc(0f, frac, Config.ACCENT, true)),
                head = frac > 0f,
                dottedTrack = true,
            )
        }
        else -> RingRenderer.ring(
            c, ringDp,
            listOf(RingRenderer.Arc(0f, s.time.fraction, 0xFFFFFFFF.toInt(), true)),
            markers = listOfNotNull(s.time.sunrise, s.time.sunset),
        )
    }

    Box(
        modifier = GlanceModifier
            .size(ringDp.dp)
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
                    BigNumber("${s.battery.level}", "%", ringDp)
                    Caption(if (s.battery.charging) "CHARGING" else if (s.battery.plugged) "HELD" else "BATTERY")
                }
                Store.PAGE_DATA -> {
                    if (s.data.hasAccess) {
                        val parts = Collect.fmtBytes(s.data.mobileBytes).split(" ")
                        BigNumber(parts[0], parts.getOrElse(1) { "" }, ringDp * 0.9f)
                        Caption("MOBILE")
                    } else {
                        Caption("TAP TEXT")
                    }
                }
                Store.PAGE_FOCUS -> FocusCountdown(s.focus, ringDp)
                else -> {
                    BigNumber("${(s.time.fraction * 100).toInt()}", "%", ringDp)
                    Caption(s.time.caption)
                }
            }
        }
    }
}

private fun ringDescription(s: Snapshot): String = when (s.page) {
    Store.PAGE_BATTERY -> "Battery ${s.battery.level} percent"
    Store.PAGE_DATA -> "Mobile data today ${Collect.fmtBytes(s.data.mobileBytes)}"
    Store.PAGE_FOCUS -> "Focus timer"
    else -> "${(s.time.fraction * 100).toInt()} percent ${s.time.caption.lowercase()}"
}

@Composable
private fun FocusCountdown(f: FocusInfo, ringDp: Float) {
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
            Text(mmss(f.left), style = mono((ringDp * 0.15f).coerceIn(14f, 24f), White))
            Caption("PAUSED · TAP")
        }
        else -> {
            Text(mmss(Config.FOCUS_MINUTES * 60_000L), style = mono((ringDp * 0.15f).coerceIn(14f, 24f), White))
            Caption("TAP TO START")
        }
    }
}

private fun mmss(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(total / 60, total % 60)
}

// ---------------------------------------------------------------------------
// Right-hand text for each page
// ---------------------------------------------------------------------------

@Composable
private fun TimeText(s: Snapshot) {
    Label(s.time.label)
    Headline(s.time.headline)
    Sub(s.time.sub)
    Spacer(GlanceModifier.height(6.dp))
    val parts = mutableListOf("${s.battery.level}%" + if (s.battery.charging) " charging" else "")
    if (s.data.hasAccess) parts += "${Collect.fmtBytes(s.data.mobileBytes)} today"
    Chip(parts.joinToString("  ·  "))
}

@Composable
private fun BatteryText(b: BatteryInfo) {
    Label(
        when {
            b.charging -> "BATTERY · CHARGING"
            b.plugged -> "BATTERY · HELD AT ${b.level}%"
            else -> "BATTERY · ON BATTERY"
        },
    )
    Headline(
        when {
            b.watts != null && b.watts >= 0.5 -> "+%.0f W".format(b.watts)
            else -> "${b.tempC.toInt()}°C"
        },
    )
    Sub(
        when {
            b.minutesToTarget != null -> "${b.target}% in ${Collect.fmtMinutes(b.minutesToTarget.toLong())}"
            b.charging -> "Charging to ${b.target}%"
            else -> "Battery is ${b.thermal.lowercase()}"
        },
    )
    Spacer(GlanceModifier.height(6.dp))
    Row {
        Stat(b.cycles?.toString() ?: "–", "CYCLES")
        Spacer(GlanceModifier.width(6.dp))
        Stat("${b.tempC.toInt()}°C", "TEMP")
        Spacer(GlanceModifier.width(6.dp))
        Stat(b.thermal, "THERMAL")
    }
}

@Composable
private fun DataText(d: DataInfo, widthDp: Float) {
    if (!d.hasAccess) {
        Label("DATA · NEEDS ACCESS")
        Headline("Allow access")
        Text(
            "Tap here, find Glance Kit, turn on Usage access.",
            style = TextStyle(color = ColorProvider(Soft), fontSize = 12.sp),
            maxLines = 3,
            modifier = GlanceModifier.clickable(
                actionStartActivity(
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                ),
            ),
        )
        return
    }
    Label("DATA · SINCE MIDNIGHT")
    Headline("${Collect.fmtBytes(d.mobileBytes)} mobile")
    Sub("${Collect.fmtBytes(d.wifiBytes)} on Wi-Fi")
    Spacer(GlanceModifier.height(6.dp))
    val c = LocalContext.current
    val w = widthDp.coerceIn(80f, 260f)
    Image(
        provider = ImageProvider(RingRenderer.bars(c, w, 24f, d.hourlyMobile, d.hourlyWifi, d.currentHour)),
        contentDescription = "Data used each hour today",
        modifier = GlanceModifier.width(w.dp).height(24.dp),
    )
}

@Composable
private fun FocusText(f: FocusInfo) {
    Label(if (f.running || f.paused) "FOCUS · SESSION ${f.sessions + 1}" else "FOCUS")
    Headline(Collect.fmtMinutes(f.todayMs / 60_000L))
    Sub(if (f.streak > 1) "Focused today · ${f.streak}-day streak" else "Focused today")
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
private fun BigNumber(value: String, unit: String, ringDp: Float) {
    val big = (ringDp * 0.21f).coerceIn(16f, 32f)
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, style = TextStyle(color = ColorProvider(White), fontSize = big.sp, fontWeight = FontWeight.Medium))
        if (unit.isNotEmpty()) {
            Text(
                unit,
                style = TextStyle(color = ColorProvider(Faint), fontSize = (big * 0.48f).sp),
                modifier = GlanceModifier.padding(bottom = 3.dp),
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
        style = TextStyle(color = ColorProvider(White), fontSize = 24.sp, fontWeight = FontWeight.Medium),
        maxLines = 1,
        modifier = GlanceModifier.padding(top = 2.dp, bottom = 2.dp),
    )
}

@Composable
private fun Sub(text: String) {
    Text(text, style = TextStyle(color = ColorProvider(Soft), fontSize = 12.sp), maxLines = 2)
}

@Composable
private fun Chip(text: String) {
    Box(
        modifier = GlanceModifier
            .cornerRadius(12.dp)
            .background(ChipBg)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(text, style = TextStyle(color = ColorProvider(Color(0xDBFFFFFF)), fontSize = 11.sp), maxLines = 1)
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(
        modifier = GlanceModifier
            .cornerRadius(10.dp)
            .background(ChipBg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(value, style = TextStyle(color = ColorProvider(White), fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1)
        Text(label, style = mono(7f, Faint), maxLines = 1)
    }
}

@Composable
private fun Pill(text: String, primary: Boolean, onClick: androidx.glance.action.Action) {
    Box(
        modifier = GlanceModifier
            .height(32.dp)
            .cornerRadius(16.dp)
            .background(if (primary) White else Color(0x24FFFFFF))
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

class NextPageAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Store.setPage(context, Store.page(context) + 1)
        refreshAll(context)
    }
}

class GoToPageAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Store.setPage(context, parameters[PAGE_PARAM] ?: 0)
        refreshAll(context)
    }
}

/** Tapping the ring does something different on each page. */
class RingTapAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        when (Store.page(context)) {
            Store.PAGE_TIME -> Store.cycleTimeMode(context)
            Store.PAGE_FOCUS -> Focus.toggle(context)
            else -> Unit // battery and data: just refresh
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
