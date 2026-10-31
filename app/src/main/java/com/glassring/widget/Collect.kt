package com.glassring.widget

import android.app.AppOpsManager
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.IsoFields
import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

data class TimeInfo(
    val mode: Int,
    val fraction: Float,
    val caption: String,   // under the % in the ring
    val label: String,     // small mono line
    val headline: String,  // big line
    val sub: String,       // supporting line
    val sunrise: Float?,   // ring markers (day mode only), as fractions of the day
    val sunset: Float?,
)

data class BatteryInfo(
    val level: Int,
    val plugged: Boolean,
    val charging: Boolean,
    val watts: Double?,
    val minutesToTarget: Int?,
    val minutesLeft: Int?,      // estimated time left on battery
    val target: Int,
    val tempC: Float,
    val cycles: Int?,           // null when the phone doesn't report it
    val thermal: String,
    val chargeCounterUah: Long,
    val healthPct: Int?,        // estimated, null until enough samples
)

data class DataInfo(
    val accurate: Boolean,      // true when Usage access is on (counts the whole day)
    val mobileBytes: Long,
    val wifiBytes: Long,
    val hourlyMobile: LongArray,
    val hourlyWifi: LongArray,
    val weekMobile: LongArray,
    val weekWifi: LongArray,
    val currentHour: Int,
)

data class FocusInfo(
    val running: Boolean,
    val paused: Boolean,
    val endAt: Long,
    val left: Long,
    val length: Long,
    val todayMs: Long,
    val sessions: Int,
    val streak: Int,
    val week: LongArray,
)

data class Snapshot(
    val page: Int,
    val time: TimeInfo,
    val battery: BatteryInfo,
    val data: DataInfo,
    val focus: FocusInfo,
    val batteryChart: Boolean,
    val dataWeek: Boolean,
    val batteryHistory: List<Sampler.BattPoint>,
    val steps: StepsInfo,
    val stepsWeek: Boolean,
    val baro: BaroInfo,
)

/** Reads everything the widget shows, straight from the phone. No accounts, no internet. */
object Collect {

    fun snapshot(c: Context): Snapshot {
        val battery = battery(c)
        Sampler.sample(c, battery)
        StepCounter.sample(c)
        Barometer.sample(c)
        return Snapshot(
            page = Store.page(c),
            time = time(c),
            battery = battery,
            data = data(c),
            focus = focus(c),
            batteryChart = Store.batteryChart(c),
            dataWeek = Store.dataWeek(c),
            batteryHistory = Sampler.history(c),
            steps = StepCounter.info(c),
            stepsWeek = Store.stepsWeek(c),
            baro = Barometer.info(c),
        )
    }

    // ---------------- Time ----------------

    fun time(c: Context): TimeInfo {
        val now = LocalDateTime.now()
        val date = now.toLocalDate()
        val sod = now.toLocalTime().toSecondOfDay().toDouble()
        val dayFrac = sod / 86_400.0
        val mode = Store.timeMode(c)
        val dow = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).uppercase()
        val mon = date.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).uppercase()

        return when (mode) {
            1 -> {
                val f = ((date.dayOfWeek.value - 1) + dayFrac) / 7.0
                TimeInfo(
                    mode, f.toFloat(), "OF WEEK",
                    "WEEK ${date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)} · $dow",
                    daysLeft((1 - f) * 7), "Resets Monday", null, null,
                )
            }
            2 -> {
                val len = date.lengthOfMonth()
                val f = ((date.dayOfMonth - 1) + dayFrac) / len
                TimeInfo(
                    mode, f.toFloat(), "OF MONTH",
                    date.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH).uppercase(),
                    daysLeft((1 - f) * len), "Day ${date.dayOfMonth} of $len", null, null,
                )
            }
            3 -> {
                val len = date.lengthOfYear()
                val f = ((date.dayOfYear - 1) + dayFrac) / len
                TimeInfo(
                    mode, f.toFloat(), "OF ${date.year}",
                    "${date.year}",
                    daysLeft((1 - f) * len), "Day ${date.dayOfYear} of $len", null, null,
                )
            }
            else -> {
                val leftMin = ((86_400 - sod) / 60).toLong()
                val zone = ZoneId.systemDefault()
                val (rise, set) = Sun.times(date, Config.LATITUDE, Config.LONGITUDE, zone)
                val nowT = now.toLocalTime()
                val sub = when {
                    rise != null && nowT.isBefore(rise) -> "Sunrise at ${fmtClock(rise)}"
                    set != null && nowT.isBefore(set) -> "Sunset in ${fmtMinutes(Duration.between(nowT, set).toMinutes())}"
                    else -> {
                        val tomorrow = Sun.times(date.plusDays(1), Config.LATITUDE, Config.LONGITUDE, zone).first
                        if (tomorrow != null) "Sunrise at ${fmtClock(tomorrow)}" else "Wind down"
                    }
                }
                TimeInfo(
                    0, dayFrac.toFloat(), "OF TODAY",
                    "$dow ${date.dayOfMonth} $mon · DAY ${date.dayOfYear}",
                    "${fmtMinutes(leftMin)} left", sub,
                    rise?.let { it.toSecondOfDay() / 86_400f },
                    set?.let { it.toSecondOfDay() / 86_400f },
                )
            }
        }
    }

    /** How far through today we are, 0..1. */
    fun dayFraction(): Float = LocalTime.now().toSecondOfDay() / 86_400f

    /** Progress through the day, week, month and year, each 0..1. */
    fun periodFractions(): FloatArray {
        val now = LocalDateTime.now()
        val d = now.toLocalDate()
        val day = now.toLocalTime().toSecondOfDay() / 86_400.0
        return floatArrayOf(
            day.toFloat(),
            (((d.dayOfWeek.value - 1) + day) / 7.0).toFloat(),
            (((d.dayOfMonth - 1) + day) / d.lengthOfMonth()).toFloat(),
            (((d.dayOfYear - 1) + day) / d.lengthOfYear()).toFloat(),
        )
    }

    private fun daysLeft(days: Double): String =
        if (days < 1) "${fmtMinutes((days * 24 * 60).toLong())} left"
        else {
            val d = ceil(days).toInt()
            if (d == 1) "1 day left" else "$d days left"
        }

    fun fmtMinutes(min: Long): String {
        val m = min.coerceAtLeast(0)
        return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    }

    fun fmtClock(t: LocalTime): String = t.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))

    // ---------------- Battery ----------------

    fun battery(c: Context): BatteryInfo {
        val i: Intent? = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val rawLevel = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val level = if (rawLevel >= 0 && scale > 0) rawLevel * 100 / scale else 0
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING
        val tempC = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val mv = i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0

        // Android 14+ lets apps read the cycle count. Some phones report -1 or 0 (not available).
        val cycles = if (Build.VERSION.SDK_INT >= 34) {
            val v = i?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1) ?: -1
            if (v > 0) v else null
        } else null

        val bm = c.getSystemService(BatteryManager::class.java)
        val ua = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: Int.MIN_VALUE
        val counter = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: Int.MIN_VALUE

        val validCurrent = ua != Int.MIN_VALUE && ua != 0
        val validCounter = counter != Int.MIN_VALUE && counter > 0
        val watts = if (plugged && validCurrent && mv > 0) abs(ua) / 1_000_000.0 * mv / 1000.0 else null

        val target = if (level < Config.CHARGE_LIMIT) Config.CHARGE_LIMIT else 100
        val toTarget = if (charging && validCurrent && validCounter && level in 1 until target) {
            val fullUah = counter / (level / 100.0)
            val needUah = fullUah * (target - level) / 100.0
            (needUah / abs(ua) * 60).toInt().takeIf { it in 1..(24 * 60) }
        } else null

        val left = if (!plugged && validCurrent && validCounter) {
            (counter.toDouble() / abs(ua) * 60).toInt().takeIf { it in 15..(4 * 24 * 60) }
        } else null

        val pm = c.getSystemService(PowerManager::class.java)
        val thermal = when (pm?.currentThermalStatus ?: 0) {
            PowerManager.THERMAL_STATUS_NONE -> "Cool"
            PowerManager.THERMAL_STATUS_LIGHT, PowerManager.THERMAL_STATUS_MODERATE -> "Warm"
            else -> "Hot"
        }

        val est = Sampler.estimatedCapacityMah(c)
        val design = designCapacityMah(c)
        val health = if (est > 0 && design > 0) (est * 100 / design).coerceIn(40, 100) else null

        return BatteryInfo(
            level, plugged, charging, watts, toTarget, left, target, tempC, cycles, thermal,
            if (validCounter) counter.toLong() else 0L, health,
        )
    }

    private var cachedDesign = -1

    /** Factory capacity. Tries the system's power profile, else the value in Config. */
    fun designCapacityMah(c: Context): Int {
        if (cachedDesign > 0) return cachedDesign
        val fromSystem = try {
            val cls = Class.forName("com.android.internal.os.PowerProfile")
            val profile = cls.getConstructor(Context::class.java).newInstance(c)
            (cls.getMethod("getBatteryCapacity").invoke(profile) as Double).toInt()
        } catch (e: Throwable) {
            0
        }
        cachedDesign = if (fromSystem in 1000..20000) fromSystem else Config.DESIGN_CAPACITY_MAH
        return cachedDesign
    }

    // ---------------- Data usage ----------------

    fun hasUsageAccess(c: Context): Boolean {
        val ops = c.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    @Suppress("DEPRECATION")
    fun data(c: Context): DataInfo {
        val (hm, hw) = Sampler.hourly(c)
        val weekM = Store.lastSevenDays(Store.readDaily(c, Sampler.K_DAILY_MOBILE))
        val weekW = Store.lastSevenDays(Store.readDaily(c, Sampler.K_DAILY_WIFI))
        var mobile = weekM[6]
        var wifi = weekW[6]
        var accurate = false

        // With Usage access, the system's own totals cover the whole day (2 quick queries).
        if (hasUsageAccess(c)) {
            val nsm = c.getSystemService(NetworkStatsManager::class.java)
            if (nsm != null) {
                val midnight = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val now = System.currentTimeMillis()
                fun bytes(type: Int): Long = try {
                    val b = nsm.querySummaryForDevice(type, null, midnight, now)
                    b.rxBytes + b.txBytes
                } catch (e: Exception) {
                    -1L
                }
                val m = bytes(ConnectivityManager.TYPE_MOBILE)
                val w = bytes(ConnectivityManager.TYPE_WIFI)
                if (m >= 0 && w >= 0) {
                    mobile = m
                    wifi = w
                    weekM[6] = m
                    weekW[6] = w
                    accurate = true
                }
            }
        }
        return DataInfo(accurate, mobile, wifi, hm, hw, weekM, weekW, LocalTime.now().hour)
    }

    fun fmtBytes(b: Long): String = when {
        b >= 1_000_000_000L -> String.format(Locale.ENGLISH, "%.2f GB", b / 1e9)
        b >= 1_000_000L -> String.format(Locale.ENGLISH, "%.0f MB", b / 1e6)
        b >= 1_000L -> String.format(Locale.ENGLISH, "%.0f KB", b / 1e3)
        else -> "$b B"
    }

    // ---------------- Focus ----------------

    fun focus(c: Context): FocusInfo {
        if (Store.focusEndAt(c) in 1..System.currentTimeMillis()) Focus.onAlarm(c)
        val end = Store.focusEndAt(c)
        val paused = Store.focusPausedLeft(c)
        val now = System.currentTimeMillis()
        val running = end > now
        val left = when {
            running -> end - now
            paused > 0 -> paused
            else -> 0L
        }
        return FocusInfo(
            running = running,
            paused = !running && paused > 0,
            endAt = end,
            left = left,
            length = Store.focusLength(c),
            todayMs = Store.focusTodayMs(c),
            sessions = Store.sessionsToday(c),
            streak = Store.streak(c),
            week = Store.focusWeek(c),
        )
    }
}

/** Sunrise and sunset from the NOAA solar equations. Accurate to about a minute. */
object Sun {
    fun times(date: LocalDate, lat: Double, lon: Double, zone: ZoneId): Pair<LocalTime?, LocalTime?> {
        val gamma = 2 * Math.PI / 365.0 * (date.dayOfYear - 1)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
            0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma))
        val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) -
            0.006758 * cos(2 * gamma) + 0.000907 * sin(2 * gamma) -
            0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)
        val latR = Math.toRadians(lat)
        val x = cos(Math.toRadians(90.833)) / (cos(latR) * cos(decl)) - tan(latR) * tan(decl)
        if (x < -1 || x > 1) return null to null // polar day or night
        val ha = Math.toDegrees(acos(x))
        val riseUtcMin = 720 - 4 * (lon + ha) - eqTime
        val setUtcMin = 720 - 4 * (lon - ha) - eqTime
        fun toLocal(min: Double): LocalTime =
            date.atStartOfDay(ZoneOffset.UTC).plusSeconds((min * 60).toLong())
                .withZoneSameInstant(zone).toLocalTime()
        return toLocal(riseUtcMin) to toLocal(setUtcMin)
    }
}
