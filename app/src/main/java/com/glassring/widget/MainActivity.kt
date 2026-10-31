package com.glassring.widget

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.IsoFields
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The app: a swipeable dashboard with one tab per widget page, plus Phone and Setup.
 * Each tab shows more than the widget can: bigger charts and extra metrics.
 */
class MainActivity : Activity(), SensorEventListener {

    private val tabs = listOf("Today", "Steps", "Battery", "Data", "Focus", "Instruments", "Phone", "Setup")

    private lateinit var pager: ViewPager2
    private lateinit var strip: LinearLayout
    private lateinit var stripScroll: HorizontalScrollView
    private val adapter = PageAdapter()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var snap: Snapshot? = null
    private var resumed = false

    private lateinit var sm: SensorManager
    private var liveCompass: CompassView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RefreshWorker.schedule(this)
        sm = getSystemService(SensorManager::class.java)

        val root = Ui.vstack(this).apply { setBackgroundColor(Ui.BG) }
        // Draw under the status and navigation bars, but keep content clear of them.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        val header = Ui.vstack(this).apply { setPadding(Ui.dp(context, 20), Ui.dp(context, 16), Ui.dp(context, 20), Ui.dp(context, 4)) }
        header.addView(Ui.label(this, "GLANCE KIT"))
        header.addView(Ui.text(this, todayTitle(), 26f, Ui.WHITE, medium = true).apply { setPadding(0, Ui.dp(context, 6), 0, 0) })
        root.addView(header)

        strip = Ui.hstack(this).apply { setPadding(Ui.dp(context, 16), Ui.dp(context, 10), Ui.dp(context, 16), Ui.dp(context, 10)) }
        stripScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        }
        tabs.forEachIndexed { i, name ->
            strip.addView(TextView(this).apply {
                text = name
                textSize = 14f
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setPadding(Ui.dp(context, 16), 0, Ui.dp(context, 16), 0)
                setOnClickListener { pager.currentItem = i }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 40)).apply {
                marginEnd = Ui.dp(this@MainActivity, 6)
            })
        }
        root.addView(stripScroll)

        pager = ViewPager2(this).apply {
            adapter = this@MainActivity.adapter
            offscreenPageLimit = 1
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    highlight(position)
                    updateSensors()
                }
            })
        }
        root.addView(pager, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        val start = tabs.indexOfFirst { it.equals(intent?.getStringExtra("tab") ?: "", ignoreCase = true) }
        if (start >= 0) pager.setCurrentItem(start, false)
        highlight(pager.currentItem)

        // First launch: ask for step counting once.
        val p = Store.prefs(this)
        if (StepCounter.available(this) && !StepCounter.granted(this) && !p.getBoolean("asked_steps", false)) {
            p.edit().putBoolean("asked_steps", true).apply()
            askForSteps()
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        reload()
        updateSensors()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        updateSensors()
    }

    /** Collects fresh data off the main thread, then redraws the tabs. */
    private fun reload(alsoWidgets: Boolean = false) {
        io.execute {
            val s = runCatching { Collect.snapshot(applicationContext) }.getOrNull()
            if (alsoWidgets) runCatching { refreshAll(applicationContext) }
            main.post {
                if (s != null) snap = s
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun highlight(pos: Int) {
        for (i in 0 until strip.childCount) {
            val t = strip.getChildAt(i) as TextView
            val on = i == pos
            t.setTextColor(if (on) Ui.BG else Ui.WHITE)
            t.background = Ui.rounded(if (on) Ui.WHITE else 0xFF161616.toInt(), Ui.dp(this, 20).toFloat())
        }
        val tab = strip.getChildAt(pos)
        stripScroll.post { stripScroll.smoothScrollTo((tab.left - Ui.dp(this, 40)).coerceAtLeast(0), 0) }
    }

    // ---------------- Live compass on the Instruments tab ----------------

    private fun updateSensors() {
        sm.unregisterListener(this)
        if (resumed && pager.currentItem == TAB_INSTRUMENTS && liveCompass != null) {
            sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == Sensor.TYPE_ROTATION_VECTOR) liveCompass?.onRotation(e.values)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        liveCompass?.lowAccuracy = accuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
    }

    // ---------------- Permissions ----------------

    private fun askForSteps() {
        if (!StepCounter.granted(this)) requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        reload(alsoWidgets = true)
    }

    // ---------------- Pages ----------------

    private inner class PageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = tabs.size
        override fun getItemViewType(position: Int) = position
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val frame = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            return object : RecyclerView.ViewHolder(frame) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val frame = holder.itemView as FrameLayout
            frame.removeAllViews()
            val scroll = ScrollView(frame.context).apply { isVerticalScrollBarEnabled = false }
            val col = Ui.vstack(frame.context).apply { setPadding(Ui.dp(context, 16), Ui.dp(context, 8), Ui.dp(context, 16), Ui.dp(context, 32)) }
            val s = snap
            if (s == null) {
                col.addView(Ui.text(frame.context, "Loading…", 15f, Ui.FAINT))
            } else {
                runCatching { buildPage(position, s, col) }.onFailure {
                    col.addView(Ui.text(frame.context, "Couldn't load this tab: ${it.message}", 13f, Ui.FAINT))
                }
            }
            scroll.addView(col)
            frame.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            if (position == TAB_INSTRUMENTS) updateSensors()
        }
    }

    private fun buildPage(i: Int, s: Snapshot, col: LinearLayout) {
        when (i) {
            TAB_TODAY -> todayPage(s, col)
            TAB_STEPS -> stepsPage(s, col)
            TAB_BATTERY -> batteryPage(s, col)
            TAB_DATA -> dataPage(s, col)
            TAB_FOCUS -> focusPage(s, col)
            TAB_INSTRUMENTS -> instrumentsPage(s, col)
            TAB_PHONE -> phonePage(s, col)
            else -> setupPage(col)
        }
    }

    private fun add(col: LinearLayout, v: View, top: Int = 12) = col.addView(v, Ui.params(this, top))

    private val chartW get() = Ui.chartWidthDp(this)
    private val days7 get() = Array(7) { i ->
        val d = LocalDate.now().minusDays(6L - i)
        if (i == 6) "TODAY" else d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).uppercase()
    }.toList()

    private fun big(value: String, unit: String): CharSequence {
        val sp = android.text.SpannableString(value + unit)
        if (unit.isNotEmpty()) {
            sp.setSpan(android.text.style.RelativeSizeSpan(0.5f), value.length, sp.length, 0)
            sp.setSpan(android.text.style.ForegroundColorSpan(Ui.FAINT), value.length, sp.length, 0)
        }
        return sp
    }

    // ----- Today -----

    private fun todayPage(s: Snapshot, col: LinearLayout) {
        val day = Collect.dayFraction()
        val sky = Device.sky()
        val markers = listOfNotNull(sky.sunrise?.let { it.toSecondOfDay() / 86_400f }, sky.sunset?.let { it.toSecondOfDay() / 86_400f })
        val leftMin = ((1 - day) * 24 * 60).toLong()
        add(col, Ui.card(this).apply {
            addView(Ui.hero(
                context,
                RingRenderer.ring(context, 136f, listOf(RingRenderer.Arc(0f, day, Ui.WHITE)), markers = markers),
                big("${(day * 100).toInt()}", "%"), "OF TODAY",
                "${Collect.fmtMinutes(leftMin)} left",
                when {
                    sky.sunrise != null && LocalTime.now().isBefore(sky.sunrise) -> "Sunrise at ${Collect.fmtClock(sky.sunrise)}"
                    sky.daylightLeft != null && !sky.daylightLeft.isZero -> "${Device.fmtDuration(sky.daylightLeft)} of daylight left"
                    else -> "The sun has set"
                },
            ))
        }, 4)

        // Four progress rings.
        val periods = Collect.periodFractions()
        add(col, Ui.card(this, "PROGRESS").apply {
            val row = Ui.hstack(context)
            listOf("DAY", "WEEK", "MONTH", "YEAR").forEachIndexed { k, name ->
                val f = periods[k]
                val cell = Ui.vstack(context).apply { gravity = Gravity.CENTER_HORIZONTAL }
                val box = FrameLayout(context)
                box.addView(ImageView(context).apply {
                    setImageBitmap(RingRenderer.ring(context, 64f, listOf(RingRenderer.Arc(0f, f, if (k == 0) Ui.WHITE else Config.ACCENT)), head = false))
                }, FrameLayout.LayoutParams(Ui.dp(context, 64), Ui.dp(context, 64)))
                box.addView(Ui.text(context, "${(f * 100).toInt()}%", 13f, Ui.WHITE, medium = true, gravity = Gravity.CENTER),
                    FrameLayout.LayoutParams(Ui.dp(context, 64), Ui.dp(context, 64)))
                cell.addView(box)
                cell.addView(Ui.text(context, name, 9f, Ui.FAINT, mono = true, gravity = Gravity.CENTER).apply { setPadding(0, Ui.dp(context, 8), 0, 0) })
                row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            addView(row)
        })

        add(col, Ui.chartCard(
            this, "DAYLIGHT",
            RingRenderer.daylight(this, chartW, 70f, day, markers.getOrNull(0), markers.getOrNull(1)), 70,
            listOf("12 AM", "6 AM", "12 PM", "6 PM", ""), spread = false,
        ))

        val moon = Device.moon()
        add(col, Ui.card(this, "SKY").apply {
            addView(Ui.statGrid(context, listOf(
                (sky.sunrise?.let { Collect.fmtClock(it) } ?: "–") to "SUNRISE",
                (sky.sunset?.let { Collect.fmtClock(it) } ?: "–") to "SUNSET",
                Device.fmtDuration(sky.dayLength) to "DAY LENGTH",
                (sky.goldenEvening?.let { "≈ " + Collect.fmtClock(it) } ?: "–") to "GOLDEN HOUR",
                moon.name to "MOON",
                "${moon.illumination}%" to "MOON LIT",
            )))
            addView(Ui.spacer(context, 14))
            addView(Ui.text(context, if (moon.daysToFull == 0) "Full moon tonight." else "Full moon in ${moon.daysToFull} days.", 13f, Ui.FAINT))
        })

        val alarm = Device.nextAlarm(this)
        val today = LocalDate.now()
        add(col, Ui.card(this, "CALENDAR").apply {
            addView(Ui.statGrid(context, listOf(
                (alarm?.let { Collect.fmtClock(it.toLocalTime()) } ?: "None") to "NEXT ALARM",
                (alarm?.let { Device.fmtDuration(Duration.between(LocalDateTime.now(), it)) } ?: "–") to "ALARM IN",
                "W${today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}" to "WEEK",
                "${today.dayOfYear}" to "DAY OF YEAR",
                "${today.lengthOfYear() - today.dayOfYear}" to "DAYS LEFT IN ${today.year}",
                "Q${(today.monthValue - 1) / 3 + 1}" to "QUARTER",
            )))
        })
    }

    // ----- Steps -----

    private fun stepsPage(s: Snapshot, col: LinearLayout) {
        val st = s.steps
        if (!st.available || !st.granted) {
            add(col, Ui.card(this, "STEPS").apply {
                addView(Ui.text(context, if (st.available) "Turn on step counting" else "This phone has no step counter", 22f, Ui.WHITE, medium = true))
                addView(Ui.spacer(context, 8))
                addView(Ui.text(context, "Steps come from the phone's built-in step counter. No account or Google Fit needed, and it uses almost no battery.", 14f, Ui.SOFT))
                if (st.available) {
                    addView(Ui.spacer(context, 16))
                    addView(Ui.pill(context, "Allow step counting", true) { askForSteps() })
                }
            }, 4)
            return
        }
        val met = st.today >= st.goal
        add(col, Ui.card(this).apply {
            addView(Ui.hero(
                context,
                RingRenderer.ring(context, 136f, listOf(RingRenderer.Arc(0f, (st.today.toFloat() / st.goal).coerceIn(0f, 1f), if (met) Config.ACCENT else Ui.WHITE))),
                big(fmt(st.today), ""), "STEPS",
                if (met) "Goal met" else "${st.today * 100 / st.goal}% of goal",
                String.format(Locale.ENGLISH, "%.2f km · %d kcal", st.km, st.kcal),
            ))
        }, 4)

        val weekTotal = st.week.sum()
        val bestIdx = st.week.indices.maxByOrNull { st.week[it] } ?: 6
        add(col, Ui.card(this, "TODAY").apply {
            addView(Ui.statGrid(context, listOf(
                (if (met) "Done" else fmt(st.goal - st.today)) to "TO GOAL",
                "${st.streak}" to "DAY STREAK",
                "${st.activeHours}" to "ACTIVE HOURS",
                (st.bestHour?.let { hourLabel(it) } ?: "–") to "BEST HOUR",
                fmt(st.goal) to "DAILY GOAL",
                String.format(Locale.ENGLISH, "%.0f cm", Config.STRIDE_METRES * 100) to "STRIDE",
            )))
        })
        add(col, Ui.chartCard(
            this, "BY HOUR",
            RingRenderer.bars(this, chartW, 90f, st.hourly, null, lastIndex = LocalTime.now().hour, highlight = LocalTime.now().hour, minPeak = 500L), 90,
            listOf("12 AM", "6 AM", "12 PM", "6 PM", ""), spread = false,
        ))
        add(col, Ui.chartCard(
            this, "LAST 7 DAYS",
            RingRenderer.bars(this, chartW, 90f, st.week, null, highlight = 6, minPeak = st.goal, guide = st.goal), 90,
            days7, spread = true,
            note = "${fmt(weekTotal)} steps this week · avg ${fmt(weekTotal / 7)} a day · best ${days7[bestIdx].lowercase().replaceFirstChar { it.uppercase() }}",
        ))
        add(col, Ui.chartCard(
            this, "LAST 30 DAYS",
            RingRenderer.bars(this, chartW, 90f, st.month, null, highlight = 29, minPeak = st.goal, guide = st.goal), 90,
            listOf("30D AGO", "22D", "15D", "7D", "TODAY"), spread = false,
            note = "${st.month.count { it >= st.goal }} of 30 days at goal · ${fmt(st.month.sum())} steps",
        ))
    }

    // ----- Battery -----

    private fun batteryPage(s: Snapshot, col: LinearLayout) {
        val b = s.battery
        val x = Device.batteryExtras(this, s.batteryHistory)
        add(col, Ui.card(this).apply {
            addView(Ui.hero(
                context,
                RingRenderer.ring(context, 136f, listOf(RingRenderer.Arc(0f, b.level / 100f, if (b.plugged) Config.ACCENT else Ui.WHITE)),
                    markers = if (b.target < 100) listOf(b.target / 100f) else emptyList()),
                big("${b.level}", "%"), if (b.charging) "CHARGING" else if (b.plugged) "PLUGGED IN" else "BATTERY",
                when {
                    b.charging && b.watts != null && b.watts >= 0.5 -> String.format(Locale.ENGLISH, "+%.1f W", b.watts)
                    b.plugged -> "Held at ${b.level}%"
                    b.minutesLeft != null -> "${Collect.fmtMinutes(b.minutesLeft.toLong())} left"
                    else -> "${b.level}%"
                },
                when {
                    b.minutesToTarget != null -> "${b.target}% in ${Collect.fmtMinutes(b.minutesToTarget.toLong())}"
                    b.plugged -> "Charging paused to protect the battery"
                    x.drainPerHour != null -> String.format(Locale.ENGLISH, "Using about %.1f%% an hour", x.drainPerHour)
                    else -> "At current use"
                },
            ))
        }, 4)
        add(col, Ui.card(this, "HEALTH").apply {
            addView(Ui.statGrid(context, listOf(
                (b.healthPct?.let { "~$it%" } ?: "–") to "EST. HEALTH",
                (b.cycles?.toString() ?: "–") to "CYCLES",
                x.healthStatus to "STATUS",
                (if (x.capacityMah > 0) "${fmt(x.capacityMah.toLong())} mAh" else "–") to "FULL CHARGE",
                "${fmt(x.designMah.toLong())} mAh" to "WHEN NEW",
                x.technology to "TYPE",
            )))
            addView(Ui.spacer(context, 14))
            addView(Ui.text(context, "Health is estimated from how much the battery holds now compared with when new. It settles after a few charges.", 12f, Ui.FAINT))
        })
        add(col, Ui.card(this, "RIGHT NOW").apply {
            addView(Ui.statGrid(context, listOf(
                "${b.tempC.toInt()}°C" to "TEMPERATURE",
                b.thermal to "THERMAL",
                String.format(Locale.ENGLISH, "%.2f V", x.voltage) to "VOLTAGE",
                x.source to "POWER",
                (x.drainPerHour?.let { String.format(Locale.ENGLISH, "%.1f%%/h", it) } ?: "–") to "AVG DRAIN",
                "${x.chargesToday}" to "CHARGES TODAY",
            )))
        })
        val nowMin = System.currentTimeMillis() / 60_000L
        val span = (nowMin - (s.batteryHistory.firstOrNull()?.minute ?: nowMin)).coerceIn(60L, 24 * 60L)
        add(col, Ui.chartCard(
            this, "LEVEL",
            RingRenderer.batteryHistory(this, chartW, 110f, s.batteryHistory, b.target, span), 110,
            listOf(ago(span) + " AGO", ago(span * 3 / 4), ago(span / 2), ago(span / 4), "NOW"), spread = false,
            note = "Orange = charging. The dashed line is your ${b.target}% limit.",
        ))
    }

    // ----- Data -----

    private fun dataPage(s: Snapshot, col: LinearLayout) {
        val d = s.data
        val total = (d.mobileBytes + d.wifiBytes).coerceAtLeast(1)
        val mf = d.mobileBytes.toFloat() / total
        val gap = if (d.mobileBytes > 0 && d.wifiBytes > 0) 0.03f else 0f
        add(col, Ui.card(this).apply {
            addView(Ui.hero(
                context,
                RingRenderer.ring(context, 136f, listOf(
                    RingRenderer.Arc(0f, (mf - gap).coerceAtLeast(0f), Config.ACCENT),
                    RingRenderer.Arc(mf, (1f - mf - gap).coerceAtLeast(0f), Ui.WHITE),
                ), head = false),
                big("${(mf * 100).toInt()}", "%"), "MOBILE",
                Collect.fmtBytes(d.mobileBytes),
                "mobile today · ${Collect.fmtBytes(d.wifiBytes)} on Wi-Fi",
            ))
        }, 4)
        val net = Device.network(this)
        add(col, Ui.card(this, "CONNECTION").apply {
            addView(Ui.statGrid(context, listOf(
                net.type to "NETWORK",
                (if (net.online) "Online" else "No internet") to "STATUS",
                (if (net.metered) "Metered" else "Unmetered") to "BILLING",
                (net.downMbps?.let { "~$it Mbps" } ?: "–") to "EST. SPEED",
                Collect.fmtBytes(d.mobileBytes + d.wifiBytes) to "TOTAL TODAY",
                (d.hourlyMobile.indices.maxByOrNull { d.hourlyMobile[it] + d.hourlyWifi[it] }
                    ?.takeIf { d.hourlyMobile[it] + d.hourlyWifi[it] > 0 }?.let { hourLabel(it) } ?: "–") to "BUSIEST HOUR",
            )))
        })
        add(col, Ui.chartCard(
            this, "TODAY BY HOUR",
            RingRenderer.bars(this, chartW, 100f, d.hourlyMobile, d.hourlyWifi, lastIndex = d.currentHour, minPeak = 10_000_000L), 100,
            listOf("12 AM", "6 AM", "12 PM", "6 PM", ""), spread = false,
            note = "Orange = mobile, white = Wi-Fi." + if (!d.accurate) " Counting since install; turn on Usage access in Setup for full days." else "",
        ))
        val wm = d.weekMobile.sum()
        val ww = d.weekWifi.sum()
        add(col, Ui.chartCard(
            this, "LAST 7 DAYS",
            RingRenderer.bars(this, chartW, 100f, d.weekMobile, d.weekWifi, minPeak = 50_000_000L), 100,
            days7, spread = true,
            note = "${Collect.fmtBytes(wm)} mobile · ${Collect.fmtBytes(ww)} Wi-Fi · avg ${Collect.fmtBytes(wm / 7)} mobile a day",
        ))
    }

    // ----- Focus -----

    private fun focusPage(s: Snapshot, col: LinearLayout) {
        val f = s.focus
        val frac = if (f.left > 0 && f.length > 0) f.left.toFloat() / f.length else 0f
        add(col, Ui.card(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            val box = FrameLayout(context)
            box.addView(ImageView(context).apply {
                setImageBitmap(RingRenderer.ring(context, 220f, listOf(RingRenderer.Arc(0f, frac, Config.ACCENT)), head = frac > 0f, dottedTrack = true))
            }, FrameLayout.LayoutParams(Ui.dp(context, 220), Ui.dp(context, 220)))
            val center = Ui.vstack(context).apply { gravity = Gravity.CENTER }
            if (f.running) {
                center.addView(Chronometer(context).apply {
                    base = SystemClock.elapsedRealtime() + f.left
                    isCountDown = true
                    textSize = 44f
                    setTextColor(Ui.WHITE)
                    typeface = android.graphics.Typeface.MONOSPACE
                    start()
                })
            } else {
                val ms = if (f.paused) f.left else Config.FOCUS_MINUTES * 60_000L
                center.addView(Ui.text(context, String.format(Locale.ENGLISH, "%02d:%02d", ms / 60_000, (ms / 1000) % 60), 44f, Ui.WHITE, mono = true, gravity = Gravity.CENTER))
            }
            center.addView(Ui.text(context, if (f.running) "FOCUSING" else if (f.paused) "PAUSED" else "READY", 10f, Ui.FAINT, mono = true, gravity = Gravity.CENTER).apply {
                setPadding(0, Ui.dp(context, 8), 0, 0)
            })
            box.addView(center, FrameLayout.LayoutParams(Ui.dp(context, 220), Ui.dp(context, 220)))
            addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(Ui.spacer(context, 16))
            val act: (() -> Unit) -> Unit = { a -> io.execute { a(); runCatching { refreshAll(applicationContext) } }; main.postDelayed({ reload() }, 150) }
            addView(when {
                f.running -> Ui.pillRow(context,
                    Ui.pill(context, "Pause", true) { act { Focus.toggle(applicationContext) } },
                    Ui.pill(context, "+5 min", false) { act { Focus.addFiveMinutes(applicationContext) } })
                f.paused -> Ui.pillRow(context,
                    Ui.pill(context, "Resume", true) { act { Focus.toggle(applicationContext) } },
                    Ui.pill(context, "Stop", false) { act { Focus.stop(applicationContext) } })
                else -> Ui.pillRow(context, Ui.pill(context, "Start ${Config.FOCUS_MINUTES} min", true) { act { Focus.toggle(applicationContext) } })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }, 4)
        val week = f.week
        val best = week.indices.maxByOrNull { week[it] } ?: 6
        add(col, Ui.card(this, "STATS").apply {
            addView(Ui.statGrid(context, listOf(
                Collect.fmtMinutes(f.todayMs / 60_000) to "TODAY",
                "${f.sessions}" to "SESSIONS",
                "${f.streak}" to "DAY STREAK",
                Collect.fmtMinutes(week.sum() / 60_000) to "THIS WEEK",
                Collect.fmtMinutes(week.sum() / 7 / 60_000) to "DAILY AVG",
                (if (week[best] > 0) days7[best] else "–") to "BEST DAY",
            )))
        })
        add(col, Ui.chartCard(
            this, "LAST 7 DAYS",
            RingRenderer.bars(this, chartW, 100f, week, null, highlight = 6, minPeak = 60 * 60_000L), 100,
            days7, spread = true,
        ))
    }

    // ----- Instruments -----

    private fun instrumentsPage(s: Snapshot, col: LinearLayout) {
        val compass = CompassView(this).apply { showTitle = false }
        liveCompass = compass
        add(col, Ui.card(this).apply {
            addView(compass, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 400)))
        }, 4)
        add(col, Ui.pillRow(this,
            Ui.pill(this, "Full screen", true) { openInstrument("compass") },
            Ui.pill(this, "Spirit level", false) { openInstrument("level") },
        ))
        val b = s.baro
        add(col, Ui.card(this, "AIR PRESSURE").apply {
            addView(Ui.text(context, big(b.hpa?.let { String.format(Locale.ENGLISH, "%.1f", it) } ?: "–", " mbar"), 40f, Ui.WHITE, medium = true))
            addView(Ui.spacer(context, 8))
            addView(Ui.text(context, "${b.trend} · ${b.hint}", 14f, Ui.SOFT))
            addView(Ui.spacer(context, 16))
            addView(Ui.statGrid(context, listOf(
                (b.altitudeM?.let { "≈$it m" } ?: "–") to "ALTITUDE",
                (b.change3h?.let { String.format(Locale.ENGLISH, "%+.1f", it) } ?: "–") to "3H CHANGE (MBAR)",
                "${b.history.size}" to "READINGS",
            )))
        })
        val nowMin = System.currentTimeMillis() / 60_000L
        val span = (nowMin - (b.history.firstOrNull()?.minute ?: nowMin)).coerceIn(60L, 24 * 60L)
        add(col, Ui.chartCard(
            this, "PRESSURE",
            RingRenderer.pressureHistory(this, chartW, 110f, b.history, span), 110,
            listOf(ago(span) + " AGO", ago(span * 3 / 4), ago(span / 2), ago(span / 4), "NOW"), spread = false,
            note = "Falling pressure often means rain or wind; rising means clearing.",
        ))
        add(col, Ui.pill(this, "Live barometer", false) { openInstrument("barometer") })
    }

    private fun openInstrument(tab: String) =
        startActivity(Intent(this, InstrumentsActivity::class.java).putExtra("tab", tab))

    // ----- Phone -----

    private fun phonePage(s: Snapshot, col: LinearLayout) {
        val p = Device.phone(this)
        val used = if (p.storageTotal > 0) 1f - p.storageFree.toFloat() / p.storageTotal else 0f
        add(col, Ui.card(this).apply {
            addView(Ui.hero(
                context,
                RingRenderer.ring(context, 136f, listOf(RingRenderer.Arc(0f, used, if (used > 0.9f) Config.ACCENT else Ui.WHITE)), head = false),
                big("${(used * 100).toInt()}", "%"), "STORAGE USED",
                "${Collect.fmtBytes(p.storageFree)} free",
                "of ${Collect.fmtBytes(p.storageTotal)}",
            ))
        }, 4)
        add(col, Ui.card(this, "SYSTEM").apply {
            addView(Ui.statGrid(context, listOf(
                Collect.fmtBytes(p.ramFree) to "RAM FREE",
                Collect.fmtBytes(p.ramTotal) to "RAM TOTAL",
                Device.fmtUptime(p.uptimeMs) to "SINCE RESTART",
                p.android to "VERSION",
                p.patch to "SECURITY PATCH",
                (p.patchAgeDays?.let { "$it days" } ?: "–") to "PATCH AGE",
            )))
            if ((p.patchAgeDays ?: 0) > 60) {
                addView(Ui.spacer(context, 14))
                addView(Ui.text(context, "Your security patch is over 2 months old. Check Settings > System > Software updates.", 12f, Config.ACCENT))
            }
            if (p.uptimeMs > 7L * 24 * 3_600_000) {
                addView(Ui.spacer(context, 10))
                addView(Ui.text(context, "Not restarted for over a week. A restart can help if the phone feels slow.", 12f, Ui.FAINT))
            }
        })
        add(col, Ui.card(this, "MODES").apply {
            addView(Ui.statGrid(context, listOf(
                (if (p.darkMode) "On" else "Off") to "DARK MODE",
                (if (p.batterySaver) "On" else "Off") to "BATTERY SAVER",
                (if (p.dnd) "On" else "Off") to "DO NOT DISTURB",
                p.ringer to "RINGER",
                Device.network(context).type to "NETWORK",
                p.model to "MODEL",
            )))
        })
    }

    // ----- Setup -----

    private fun setupPage(col: LinearLayout) {
        add(col, Ui.card(this, "WIDGETS").apply {
            addView(Ui.text(context, "Add any of these to your home screen. The all-in-one has every page; the others show one thing each and grow to show a chart when you stretch them.", 13f, Ui.SOFT))
            addView(Ui.spacer(context, 8))
            listOf(
                "All-in-one" to RingWidgetReceiver::class.java,
                "Today" to TodayWidget::class.java,
                "Steps" to StepsWidget::class.java,
                "Battery" to BatteryWidget::class.java,
                "Data" to DataWidget::class.java,
                "Focus" to FocusWidget::class.java,
                "Pressure" to PressureWidget::class.java,
                "Compass" to CompassWidget::class.java,
            ).forEach { (name, cls) ->
                val row = Ui.hstack(context).apply { setPadding(0, Ui.dp(context, 10), 0, 0) }
                row.addView(Ui.text(context, name, 16f, Ui.WHITE, medium = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(Ui.pill(context, "Add", false) { pin(cls) }, LinearLayout.LayoutParams(Ui.dp(context, 88), Ui.dp(context, 44)))
                addView(row)
            }
        }, 4)
        add(col, Ui.card(this, "PERMISSIONS").apply {
            val stepsOn = StepCounter.granted(context)
            addView(Ui.text(context, if (stepsOn) "Step counting is on." else "Step counting is off.", 15f, Ui.WHITE, medium = true))
            if (!stepsOn) {
                addView(Ui.spacer(context, 10))
                addView(Ui.pill(context, "Allow step counting", true) { askForSteps() })
            }
            addView(Ui.spacer(context, 20))
            val usage = Collect.hasUsageAccess(context)
            addView(Ui.text(context, if (usage) "Usage access is on." else "Usage access is off (optional).", 15f, Ui.WHITE, medium = true))
            addView(Ui.spacer(context, 6))
            addView(Ui.text(context, "With it, the data page counts the whole day instead of from install. If Android says \"App was denied access\", tap App info, then ⋮ > Allow restricted settings, and try again.", 13f, Ui.FAINT))
            addView(Ui.spacer(context, 12))
            addView(Ui.pillRow(context,
                Ui.pill(context, "Usage access", false) { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
                Ui.pill(context, "App info", false) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }))
        })
        add(col, Ui.card(this, "ABOUT").apply {
            val v = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "–"
            addView(Ui.text(context, "Glance Kit $v", 15f, Ui.WHITE, medium = true))
            addView(Ui.spacer(context, 6))
            addView(Ui.text(context, "Everything is read on your phone. No accounts, no tracking, nothing leaves the device.", 13f, Ui.FAINT))
        })
    }

    private fun pin(cls: Class<*>) {
        val mgr = getSystemService(AppWidgetManager::class.java)
        if (mgr != null && mgr.isRequestPinAppWidgetSupported) {
            mgr.requestPinAppWidget(ComponentName(this, cls), null, null)
        }
    }

    // ---------------- Helpers ----------------

    private fun fmt(n: Long) = String.format(Locale.ENGLISH, "%,d", n)

    private fun ago(minutes: Long) = if (minutes >= 120) "${minutes / 60}H" else "${minutes}M"

    private fun hourLabel(h: Int) = when {
        h == 0 -> "12 AM"
        h < 12 -> "$h AM"
        h == 12 -> "12 PM"
        else -> "${h - 12} PM"
    }

    private fun todayTitle(): String =
        LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH))

    companion object {
        const val TAB_TODAY = 0
        const val TAB_STEPS = 1
        const val TAB_BATTERY = 2
        const val TAB_DATA = 3
        const val TAB_FOCUS = 4
        const val TAB_INSTRUMENTS = 5
        const val TAB_PHONE = 6
    }
}
