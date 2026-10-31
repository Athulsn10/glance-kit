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
    private var apps: Device.AppUsage? = null
    private var storage: Device.AppStorage? = null
    private var appsTab = 0 // 0 = screen time, 1 = storage
    private val focusHaptics by lazy { Haptics(this) }
    @Volatile private var storageAt = 0L
    private val phoneTick = object : Runnable {
        override fun run() {
            if (resumed && pager.currentItem == TAB_PHONE) reload()
            main.postDelayed(this, 60_000)
        }
    }
    private var resumed = false

    private lateinit var sm: SensorManager
    private var liveCompass: CompassView? = null
    private val scrollPos = HashMap<Int, Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RefreshWorker.schedule(this)
        sm = getSystemService(SensorManager::class.java)

        val root = Ui.vstack(this).apply { setBackgroundColor(Ui.BG) }
        // Draw under the status and navigation bars, but keep content clear of them.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
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
        // No cross-fade when a tab redraws with fresh data.
        (pager.getChildAt(0) as? RecyclerView)?.itemAnimator = null
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

    /** Opened again from a widget while already open: just switch tab. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val i = tabs.indexOfFirst { it.equals(intent.getStringExtra("tab") ?: "", ignoreCase = true) }
        if (i >= 0) pager.currentItem = i
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        io.shutdown()
        sm.unregisterListener(this)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        reload()
        updateSensors()
        main.removeCallbacks(phoneTick)
        main.postDelayed(phoneTick, 60_000)
        // Keep the live compass widget running (the app being open lets it start).
        if (CompassService.isLive(this)) CompassService.start(this)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(phoneTick)
        resumed = false
        updateSensors()
    }

    /** Collects fresh data off the main thread, then redraws the tabs. */
    private fun reload(alsoWidgets: Boolean = false, forceStorage: Boolean = false) {
        if (isDestroyed || io.isShutdown) return
        io.execute {
            val s = runCatching { Collect.snapshot(applicationContext) }.getOrNull()
            val a = runCatching { Device.topApps(applicationContext) }.getOrNull()
            // App sizes change slowly and are costly to add up: refresh at most every 5 minutes.
            val now = SystemClock.elapsedRealtime()
            val st = if (storage == null || forceStorage || now - storageAt > 300_000) {
                runCatching { Device.appStorage(applicationContext) }.getOrNull()?.also { storageAt = now }
            } else null
            if (alsoWidgets) runCatching { refreshAll(applicationContext) }
            main.post {
                if (isDestroyed) return@post
                if (s != null) snap = s
                if (a != null) apps = a
                if (st != null) storage = st
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
        if (StepCounter.granted(this)) return
        val p = Store.prefs(this)
        val askedBefore = p.getBoolean("asked_steps_dialog", false)
        if (askedBefore && !shouldShowRequestPermissionRationale(Manifest.permission.ACTIVITY_RECOGNITION)) {
            // Android won't show the prompt again after two "Don't allow"s: open the app's settings.
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            return
        }
        p.edit().putBoolean("asked_steps_dialog", true).apply()
        requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), 1)
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
            // Reuse the page's scroll view and swap only its content, then put the scroll
            // position back before the next frame is drawn. Rebuilding the whole page and
            // restoring the position a frame later is what made the screen flash.
            val scroll = (frame.getChildAt(0) as? ScrollView) ?: ScrollView(frame.context).apply {
                isVerticalScrollBarEnabled = false
                frame.addView(this, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            val y = if (scroll.childCount > 0) scroll.scrollY else scrollPos[position] ?: 0
            scrollPos[position] = y
            val col = Ui.vstack(frame.context).apply { setPadding(Ui.dp(context, 16), Ui.dp(context, 8), Ui.dp(context, 16), Ui.dp(context, 32)) }
            val s = snap
            if (s == null) {
                col.addView(Ui.text(frame.context, "Loading…", 15f, Ui.FAINT))
            } else {
                runCatching { buildPage(position, s, col) }.onFailure {
                    col.addView(Ui.text(frame.context, "Couldn't load this tab: ${it.message}", 13f, Ui.FAINT))
                }
            }
            scroll.removeAllViews()
            scroll.addView(col)
            if (y > 0) {
                scroll.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        scroll.viewTreeObserver.removeOnPreDrawListener(this)
                        scroll.scrollTo(0, y)
                        return true
                    }
                })
            }
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

        add(col, Ui.card(this, "SKY").apply {
            addView(Ui.statGrid(context, listOf(
                (sky.sunrise?.let { Collect.fmtClock(it) } ?: "–") to "SUNRISE",
                (sky.sunset?.let { Collect.fmtClock(it) } ?: "–") to "SUNSET",
                Device.fmtDuration(sky.dayLength) to "DAY LENGTH",
                (sky.goldenEvening?.let { "≈ " + Collect.fmtClock(it) } ?: "–") to "GOLDEN HOUR",
                Device.fmtDuration(sky.daylightLeft) to "DAYLIGHT LEFT",
            )))
        })

        // Sun: where it is now.
        val sun = Device.sunNow()
        val nowT = LocalTime.now()
        add(col, skyCard(
            "SUN",
            SkyArt.sun(this, 88f, sun.up),
            when {
                sun.up -> "Sun is up"
                sky.sunrise != null && nowT.isBefore(sky.sunrise) -> "Before sunrise"
                else -> "Sun has set"
            },
            when {
                sun.up && sky.daylightLeft != null -> "${Device.fmtDuration(sky.daylightLeft)} of daylight left"
                sky.sunrise != null && nowT.isBefore(sky.sunrise) -> "Rises at ${Collect.fmtClock(sky.sunrise)}"
                else -> "Rises tomorrow around ${sky.sunrise?.let { Collect.fmtClock(it) } ?: "–"}"
            },
            listOf(
                String.format(Locale.ENGLISH, "%.0f°", sun.elevationDeg) to "HEIGHT IN SKY",
                (sun.solarNoon?.let { Collect.fmtClock(it) } ?: "–") to "HIGHEST AT",
                (sky.goldenEvening?.let { "≈ " + Collect.fmtClock(it) } ?: "–") to "GOLDEN HOUR",
            ),
        ))

        // Moon: tonight's real phase.
        val moon = Device.moon()
        val synodic = 29.530588853
        val toNew = ((synodic - moon.age) % synodic).toInt()
        add(col, skyCard(
            "MOON",
            SkyArt.moon(this, 88f, (moon.age / synodic).toFloat()),
            moon.name,
            "${moon.illumination}% lit · " + if (moon.daysToFull == 0) "full moon tonight" else "full moon in ${moon.daysToFull} days",
            listOf(
                "${moon.illumination}%" to "ILLUMINATED",
                String.format(Locale.ENGLISH, "%.1f days", moon.age) to "MOON AGE",
                (if (toNew == 0) "Tonight" else "$toNew days") to "NEW MOON IN",
            ),
        ))

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

    /** Sun or moon card: picture, name and line beside it, three stats below. */
    private fun skyCard(title: String, art: android.graphics.Bitmap, headline: String, sub: String, stats: List<Pair<CharSequence, String>>) =
        Ui.card(this, title).apply {
            val row = Ui.hstack(context)
            row.addView(ImageView(context).apply { setImageBitmap(art) }, LinearLayout.LayoutParams(Ui.dp(context, 88), Ui.dp(context, 88)))
            val col = Ui.vstack(context).apply { setPadding(Ui.dp(context, 18), 0, 0, 0) }
            col.addView(Ui.text(context, headline, 22f, Ui.WHITE, medium = true))
            col.addView(Ui.text(context, sub, 14f, Ui.SOFT).apply { setPadding(0, Ui.dp(context, 8), 0, 0) })
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(row)
            addView(Ui.spacer(context, 18))
            addView(Ui.statGrid(context, stats))
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
        add(col, Ui.card(this).apply {
            addView(FocusView(context).apply {
                bind(f, Store.focusMinutes(context))
                onFinished = { reload(alsoWidgets = true) }
                setOnClickListener { FocusActivity.open(context) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 380)))
            addView(Ui.spacer(context, 10))
            addView(Ui.label(context, "SCENE"))
            addView(Ui.spacer(context, 8))
            addView(FocusUi.scenes(context, focusHaptics) { adapter.notifyItemChanged(TAB_FOCUS) })
            addView(Ui.spacer(context, 14))
            addView(FocusUi.controls(context, f, focusHaptics) { reload() })
            addView(Ui.spacer(context, 10))
            addView(Ui.pill(context, "Full screen", false) { FocusActivity.open(context) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 44)))
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
        add(col, Ui.card(this, "HOW IT WORKS").apply {
            addView(Ui.text(context,
                "Pick a length and start. Work on one thing until the ring burns down, then take a 5-minute break. " +
                    "Finished sessions count towards today's 4 dots, your streak and the weekly chart. " +
                    "It keeps running on the home screen widget and buzzes when time is up, even with the app closed. " +
                    "Tap the ring for a distraction-free full screen.",
                13f, Ui.SOFT))
        })
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
        add(col, ramCard())
        add(col, topAppsCard())
        add(col, cacheCard())
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

    /** Most-used apps today (screen time) or biggest apps (storage), as two tabs. */
    private fun topAppsCard(): LinearLayout = Ui.card(this, "APPS").apply {
        // Segmented tabs.
        val seg = Ui.hstack(context)
        listOf("Screen time", "Storage").forEachIndexed { i, name ->
            val on = i == appsTab
            seg.addView(Ui.text(context, name, 13f, if (on) Ui.BG else Ui.WHITE, medium = true, gravity = android.view.Gravity.CENTER).apply {
                background = Ui.rounded(if (on) Ui.WHITE else 0xFF1E1E1E.toInt(), Ui.dp(context, 18).toFloat())
                setOnClickListener {
                    appsTab = i
                    adapter.notifyItemChanged(TAB_PHONE)
                }
            }, LinearLayout.LayoutParams(0, Ui.dp(context, 36), 1f).apply { if (i > 0) marginStart = Ui.dp(context, 8) })
        }
        addView(seg)
        addView(Ui.spacer(context, 16))

        val u = apps
        if (u == null || !u.allowed) {
            addView(Ui.text(context, "Turn on usage access to see your apps' screen time and storage.", 14f, Ui.SOFT))
            addView(Ui.spacer(context, 12))
            addView(Ui.pill(context, "Usage access", true) { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) })
            return@apply
        }
        if (appsTab == 0) {
            addView(Ui.statGrid(context, listOf(
                Collect.fmtMinutes(u.totalMs / 60_000) to "SCREEN TIME",
                "${u.unlocks}" to "UNLOCKS",
                "${u.apps.size}" to "APPS USED",
            )))
            if (u.apps.isEmpty()) {
                addView(Ui.spacer(context, 12))
                addView(Ui.text(context, "No app used for more than a minute yet today.", 13f, Ui.FAINT))
                return@apply
            }
            val top = u.apps.first().ms.coerceAtLeast(1)
            u.apps.forEachIndexed { i, app ->
                addView(appRow(app.icon, app.label, null, Collect.fmtMinutes(app.ms / 60_000), app.ms.toFloat() / top, i == 0, null))
            }
            addView(Ui.spacer(context, 12))
            addView(Ui.text(context, "Updates every minute while this tab is open.", 12f, Ui.FAINT))
        } else {
            val st = storage
            if (st == null || !st.allowed) {
                addView(Ui.text(context, "Loading…", 13f, Ui.FAINT)); return@apply
            }
            val sorted = st.apps.sortedByDescending { it.total }
            addView(Ui.statGrid(context, listOf(
                Collect.fmtBytes(sorted.sumOf { it.total }) to "ALL APPS",
                Collect.fmtBytes(st.totalCache) to "CACHE",
                "${sorted.size}" to "APPS",
            )))
            val top = sorted.firstOrNull()?.total?.coerceAtLeast(1L) ?: 1L
            sorted.take(10).forEachIndexed { i, a ->
                addView(appRow(a.icon, a.label,
                    "app ${Collect.fmtBytes(a.app)} · data ${Collect.fmtBytes(a.data)} · cache ${Collect.fmtBytes(a.cache)}",
                    Collect.fmtBytes(a.total), a.total.toFloat() / top, i == 0, a.pkg))
            }
            addView(Ui.spacer(context, 12))
            addView(Ui.text(context, "Tap an app to open its storage settings.", 12f, Ui.FAINT))
        }
    }

    /** One app row: icon, name (and optional detail), bar, value. */
    private fun appRow(icon: android.graphics.drawable.Drawable?, label: String, detail: String?, value: String, frac: Float, first: Boolean, pkg: String?): LinearLayout {
        val c = this
        val row = Ui.hstack(c).apply { setPadding(0, Ui.dp(c, if (first) 18 else 12), 0, 0) }
        row.addView(ImageView(c).apply { setImageDrawable(icon) }, LinearLayout.LayoutParams(Ui.dp(c, 32), Ui.dp(c, 32)))
        val mid = Ui.vstack(c).apply { setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 12), 0) }
        mid.addView(Ui.text(c, label, 14f, Ui.WHITE, medium = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        if (detail != null) mid.addView(Ui.text(c, detail, 11f, Ui.FAINT).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, Ui.dp(c, 3), 0, 0)
        })
        val bar = Ui.hstack(c).apply { setPadding(0, Ui.dp(c, 6), 0, 0) }
        val f = frac.coerceIn(0.02f, 1f)
        bar.addView(View(c).apply { background = Ui.rounded(if (first) Config.ACCENT else Ui.WHITE, Ui.dp(c, 3).toFloat()) },
            LinearLayout.LayoutParams(0, Ui.dp(c, 6), f))
        bar.addView(View(c), LinearLayout.LayoutParams(0, Ui.dp(c, 6), (1f - f).coerceAtLeast(0.001f)))
        mid.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Ui.text(c, value, 14f, Ui.SOFT))
        if (pkg != null) row.setOnClickListener { openAppInfo(pkg) }
        return row
    }

    private fun openAppInfo(pkg: String) =
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))

    /** Live RAM, apps active in the last 15 minutes, and a shortcut to Android's memory page. */
    private fun ramCard(): LinearLayout = Ui.card(this, "MEMORY").apply {
        addView(RamView(context), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val recent = apps?.recent.orEmpty()
        if (recent.isNotEmpty()) {
            addView(Ui.spacer(context, 18))
            addView(Ui.label(context, "ACTIVE IN THE LAST 15 MIN"))
            val row = Ui.hstack(context).apply { setPadding(0, Ui.dp(context, 10), 0, 0) }
            recent.take(6).forEach { app ->
                val cell = Ui.vstack(context).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL }
                cell.addView(ImageView(context).apply { setImageDrawable(app.icon) }, LinearLayout.LayoutParams(Ui.dp(context, 36), Ui.dp(context, 36)))
                cell.addView(Ui.text(context, app.label, 10f, Ui.SOFT, gravity = android.view.Gravity.CENTER).apply {
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, Ui.dp(context, 6), 0, 0)
                })
                cell.addView(Ui.text(context, if (app.ms < 60_000) "now" else "${app.ms / 60_000}m ago", 9f, Ui.FAINT, mono = true, gravity = android.view.Gravity.CENTER).apply {
                    setPadding(0, Ui.dp(context, 3), 0, 0)
                })
                row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            addView(row)
        }
        addView(Ui.spacer(context, 14))
        addView(Ui.text(context, "Android doesn't let apps see each other's memory. For RAM per app, open Developer options > Memory.", 12f, Ui.FAINT))
        addView(Ui.spacer(context, 10))
        addView(Ui.pill(context, "Developer options", false) {
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
        })
    }

    /** Cache per app, biggest first, with the system "clear app cache" dialog. */
    private fun cacheCard(): LinearLayout = Ui.card(this, "CACHE").apply {
        val st = storage
        if (st == null || !st.allowed) {
            addView(Ui.text(context, "Turn on usage access to see how much cache each app holds.", 14f, Ui.SOFT))
            return@apply
        }
        val list = st.apps.filter { it.cache > 0 }.sortedByDescending { it.cache }
        addView(Ui.text(context, Collect.fmtBytes(st.totalCache), 30f, Ui.WHITE, medium = true))
        addView(Ui.text(context, "cache across ${list.size} apps", 13f, Ui.SOFT).apply { setPadding(0, Ui.dp(context, 6), 0, 0) })
        addView(Ui.spacer(context, 10))
        addView(Ui.text(context,
            "Android only lets the system clear apps' own cache. Tap an app below and choose Clear cache, " +
                "or clear everything at once from a computer with: adb shell pm trim-caches 999G",
            12f, Ui.FAINT))
        addView(Ui.spacer(context, 12))
        addView(Ui.pill(context, "Clear shared-storage cache", false) { clearAllCache() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 44)))
        val top = list.firstOrNull()?.cache?.coerceAtLeast(1L) ?: 1L
        list.take(10).forEachIndexed { i, a ->
            addView(appRow(a.icon, a.label, null, Collect.fmtBytes(a.cache), a.cache.toFloat() / top, i == 0, a.pkg))
        }
        addView(Ui.spacer(context, 12))
        addView(Ui.text(context, "Tap an app to open its storage page.", 12f, Ui.FAINT))
    }

    private fun clearAllCache() {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        if (!android.os.Environment.isExternalStorageManager()) {
            // One-time: let Glance Kit request the system's clear-cache dialog.
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            }
            return
        }
        runCatching {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(android.os.storage.StorageManager.ACTION_CLEAR_APP_CACHE), REQ_CLEAR_CACHE)
        }
    }

    @Deprecated("Uses the platform result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CLEAR_CACHE) reload(forceStorage = true)
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
        add(col, pagesCard(), 12)
        add(col, Ui.card(this, "LIVE COMPASS WIDGET").apply {
            val live = CompassService.isLive(context)
            val placed = CompassService.hasHost(context)
            addView(Ui.text(context, when {
                live && placed -> "Live compass is on."
                live -> "Live compass is on. Add the Compass widget, or the Compass page on the all-in-one widget, to see it."
                else -> "Live compass is off."
            }, 15f, if (live) Config.ACCENT else Ui.WHITE, medium = true))
            addView(Ui.spacer(context, 6))
            addView(Ui.text(context, "Keeps the Compass widget pointing north. It runs only while the screen is on and shows a quiet notification.", 13f, Ui.FAINT))
            addView(Ui.spacer(context, 12))
            val toggle = Ui.pill(context, if (live) "Turn off" else "Turn on", !live) {
                if (live) {
                    CompassService.stop(applicationContext)
                } else {
                    if (android.os.Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
                    CompassService.enable(applicationContext)
                    if (!placed) pin(CompassWidget::class.java)
                }
                adapter.notifyItemChanged(TAB_SETUP)
            }
            if (placed) addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 44)))
            else addView(Ui.pillRow(context, toggle, Ui.pill(context, "Add Compass widget", false) { pin(CompassWidget::class.java) }))
        }, 12)
        add(col, Ui.card(this, "PERMISSIONS").apply {
            fun row(title: String, on: Boolean, why: String, onLabel: String, offLabel: String, action: () -> Unit) {
                val head = Ui.hstack(context).apply { gravity = Gravity.CENTER_VERTICAL }
                head.addView(Ui.text(context, title, 15f, Ui.WHITE, medium = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                head.addView(Ui.text(context, if (on) "ON" else "OFF", 11f, if (on) Config.ACCENT else Ui.FAINT, mono = true))
                addView(head)
                addView(Ui.spacer(context, 4))
                addView(Ui.text(context, why, 12f, Ui.FAINT))
                addView(Ui.spacer(context, 10))
                addView(Ui.pill(context, if (on) offLabel else onLabel, !on) { action() },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 44)))
                addView(Ui.spacer(context, 20))
            }
            val appInfo = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
            val steps = StepCounter.granted(context)
            row("Step counting", steps, "Counts your steps from the phone's own step sensor.",
                "Allow step counting", "Turn off in App info > Permissions") { if (steps) appInfo() else askForSteps() }
            row("Usage access", Collect.hasUsageAccess(context),
                "Full-day data use, most-used apps, app storage and cache. If Android says \"App was denied access\", open App info, tap ⋮ > Allow restricted settings, and try again.",
                "Allow usage access", "Turn off usage access") { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            val notif = android.os.Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            row("Notifications", notif, "Only for the live compass's quiet \"on\" notification.",
                "Allow notifications", "Turn off notifications") {
                if (notif || android.os.Build.VERSION.SDK_INT < 33) {
                    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                } else requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            }
            val files = android.os.Environment.isExternalStorageManager()
            row("All files access", files, "Only so Clear cache can open Android's clear-cache window.",
                "Allow all files access", "Turn off all files access") {
                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
            }
        })
        add(col, Ui.card(this, "ABOUT").apply {
            val v = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "–"
            addView(Ui.text(context, "Glance Kit $v", 15f, Ui.WHITE, medium = true))
            addView(Ui.spacer(context, 6))
            addView(Ui.text(context, "Everything is read on your phone. No accounts, no tracking, nothing leaves the device.", 13f, Ui.FAINT))
            addView(Ui.spacer(context, 8))
            fun link(icon: Int, title: String, sub: String, onClick: () -> Unit) {
                val r = Ui.hstack(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, Ui.dp(context, 12), 0, Ui.dp(context, 12))
                    setOnClickListener { onClick() }
                }
                r.addView(ImageView(context).apply {
                    setImageResource(icon)
                    val pad = Ui.dp(context, 9)
                    setPadding(pad, pad, pad, pad)
                    imageTintList = android.content.res.ColorStateList.valueOf(Ui.WHITE)
                    background = Ui.rounded(0xFF1C1C1C.toInt(), Ui.dp(context, 20).toFloat())
                }, LinearLayout.LayoutParams(Ui.dp(context, 40), Ui.dp(context, 40)))
                val t = Ui.vstack(context).apply { setPadding(Ui.dp(context, 14), 0, 0, 0) }
                t.addView(Ui.text(context, title, 15f, Ui.WHITE, medium = true))
                t.addView(Ui.text(context, sub, 12f, Ui.FAINT).apply { setPadding(0, Ui.dp(context, 3), 0, 0) })
                r.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(r)
            }
            link(R.drawable.ic_bug, "Report a bug", "Email with your phone and app details filled in") {
                email("Glance Kit $v: bug report",
                    "What happened:\n\n\nWhat you expected:\n\n\nSteps to reproduce:\n1. \n\n" +
                        "---\nApp: Glance Kit $v\nPhone: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n" +
                        "Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n")
            }
            link(R.drawable.ic_mail, "Contact the developer", DEV_EMAIL) { email("Glance Kit $v", "") }
            link(R.drawable.ic_idea, "Request a feature", "Open an issue on GitHub") { openUrl("$REPO_URL/issues/new") }
            link(R.drawable.ic_code, "Source code and contributions", REPO_URL.removePrefix("https://")) { openUrl(REPO_URL) }
        })
    }

    /** Choose, order and hide the all-in-one widget's pages. Drag the handle to reorder. */
    private fun pagesCard(): LinearLayout = Ui.card(this, "ALL-IN-ONE WIDGET PAGES").apply {
        val ctx = context
        val order = Store.pageOrder(ctx)
        val hidden = (0 until Store.PAGE_COUNT).filter { it !in order }
        addView(Ui.text(ctx, "Drag the handle to reorder. Tap − to hide a page and + to bring it back. The widget updates straight away.", 13f, Ui.SOFT))
        addView(Ui.spacer(ctx, 8))

        fun save(newOrder: List<Int>) {
            Store.setPageOrder(applicationContext, newOrder)
            io.execute {
                runCatching {
                    refreshAll(applicationContext)
                    if (CompassService.isLive(applicationContext) && !CompassService.hasHost(applicationContext)) {
                        CompassService.halt(applicationContext)
                    }
                }
            }
            adapter.notifyItemChanged(TAB_SETUP)
        }

        fun iconBtn(res: Int, enabled: Boolean, primary: Boolean, desc: String, onClick: () -> Unit) = ImageView(ctx).apply {
            setImageResource(res)
            contentDescription = desc
            val pad = Ui.dp(ctx, 10)
            setPadding(pad, pad, pad, pad)
            imageTintList = android.content.res.ColorStateList.valueOf(
                if (!enabled) 0xFF3A3A3A.toInt() else if (primary) Ui.BG else Ui.WHITE)
            background = Ui.rounded(if (primary && enabled) Ui.WHITE else 0xFF1C1C1C.toInt(), Ui.dp(ctx, 20).toFloat())
            if (enabled) setOnClickListener { focusHaptics.tick(0.5f); onClick() }
        }

        fun row(page: Int, number: String, on: Boolean): LinearLayout = Ui.hstack(ctx).apply {
            tag = page
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(ctx, 5), 0, Ui.dp(ctx, 5))
            background = Ui.rounded(Ui.CARD, Ui.dp(ctx, 14).toFloat())
            if (on) {
                addView(ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_drag)
                    contentDescription = "Drag to reorder"
                    val pad = Ui.dp(ctx, 10)
                    setPadding(pad, pad, pad, pad)
                    imageTintList = android.content.res.ColorStateList.valueOf(Ui.FAINT)
                }, LinearLayout.LayoutParams(Ui.dp(ctx, 44), Ui.dp(ctx, 44)))
            } else addView(View(ctx), LinearLayout.LayoutParams(Ui.dp(ctx, 44), Ui.dp(ctx, 44)))
            addView(Ui.text(ctx, number, 12f, Ui.FAINT, mono = true), LinearLayout.LayoutParams(Ui.dp(ctx, 24), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(Ui.text(ctx, Store.PAGE_NAMES[page], 16f, if (on) Ui.WHITE else Ui.FAINT, medium = true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val canHide = order.size > 1 // keep at least one page
            addView(
                if (on) iconBtn(R.drawable.ic_minus, canHide, false, "Remove ${Store.PAGE_NAMES[page]}") { save(order - page) }
                else iconBtn(R.drawable.ic_plus, true, true, "Add ${Store.PAGE_NAMES[page]}") { save(order + page) },
                LinearLayout.LayoutParams(Ui.dp(ctx, 40), Ui.dp(ctx, 40)))
        }

        val list = Ui.vstack(ctx)
        order.forEachIndexed { i, page ->
            val r = row(page, "${i + 1}", true)
            r.getChildAt(0).setOnTouchListener(dragger(list, r) {
                val newOrder = (0 until list.childCount).map { list.getChildAt(it).tag as Int }
                if (newOrder != order) save(newOrder)
            })
            list.addView(r)
        }
        addView(list)
        if (hidden.isNotEmpty()) {
            addView(Ui.spacer(ctx, 14))
            addView(Ui.label(ctx, "HIDDEN"))
            addView(Ui.spacer(ctx, 4))
            hidden.forEach { addView(row(it, "", false)) }
        }
        addView(Ui.spacer(ctx, 12))
        addView(Ui.text(ctx,
            "The Compass page shows your last heading and moves live while the live compass is on, " +
                "using the same sensor feed as the Compass widget, so it adds no extra battery use.",
            12f, Ui.FAINT))
    }

    /**
     * Drag-to-reorder for a row in [list]. The dragged row follows your finger; when it passes
     * halfway over a neighbour, the neighbour hops to the other side (with a haptic tick).
     */
    private fun dragger(list: LinearLayout, row: View, onDrop: () -> Unit): View.OnTouchListener {
        var startY = 0f
        fun renumber() {
            for (i in 0 until list.childCount) ((list.getChildAt(i) as LinearLayout).getChildAt(1) as TextView).text = "${i + 1}"
        }
        return View.OnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.parent.requestDisallowInterceptTouchEvent(true) // keep the page and tabs from scrolling
                    startY = e.rawY
                    row.elevation = Ui.dp(this, 10).toFloat()
                    row.background = Ui.rounded(0xFF1E1E1E.toInt(), Ui.dp(this, 14).toFloat())
                    row.animate().scaleX(1.03f).scaleY(1.03f).setDuration(120).start()
                    focusHaptics.tick(0.6f)
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - startY
                    val idx = list.indexOfChild(row)
                    if (dy > 0 && idx < list.childCount - 1) {
                        val next = list.getChildAt(idx + 1)
                        if (dy > next.height / 2f) {
                            list.removeView(next); list.addView(next, idx)
                            startY += next.height
                            focusHaptics.detent(0.4f); renumber()
                        }
                    } else if (dy < 0 && idx > 0) {
                        val prev = list.getChildAt(idx - 1)
                        if (-dy > prev.height / 2f) {
                            list.removeView(prev); list.addView(prev, idx)
                            startY -= prev.height
                            focusHaptics.detent(0.4f); renumber()
                        }
                    }
                    row.translationY = e.rawY - startY
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    row.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(150).withEndAction {
                        row.elevation = 0f
                        row.background = Ui.rounded(Ui.CARD, Ui.dp(this, 14).toFloat())
                        onDrop()
                    }.start()
                    focusHaptics.click(0.5f)
                    true
                }
                else -> false
            }
        }
    }

    private fun email(subject: String, body: String) {
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(DEV_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            if (body.isNotEmpty()) putExtra(Intent.EXTRA_TEXT, body)
        }
        runCatching { startActivity(i) }.onFailure {
            android.widget.Toast.makeText(this, "No email app found. Write to $DEV_EMAIL", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
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
        const val TAB_SETUP = 7
        const val DEV_EMAIL = "athulsn34@gmail.com"
        const val REPO_URL = "https://github.com/Athulsn10/glance-kit"
        private const val REQ_CLEAR_CACHE = 7
    }
}
