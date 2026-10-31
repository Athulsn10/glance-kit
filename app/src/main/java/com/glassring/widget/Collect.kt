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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
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
    val target: Int,
    val tempC: Float,
    val cycles: Int?,      // null when the phone doesn't report it
    val thermal: String,
)

data class DataInfo(
    val hasAccess: Boolean,
    val mobileBytes: Long,
    val wifiBytes: Long,
    val hourlyMobile: LongArray,
    val hourlyWifi: LongArray,
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
)

data class Snapshot(
    val page: Int,
    val time: TimeInfo,
    val battery: BatteryInfo,
    val data: DataInfo,
    val focus: FocusInfo,
)

/** Reads everything the widget shows, straight from the phone. No accounts, no internet. */
object Collect {

    fun snapshot(c: Context): Snapshot = Snapshot(
        page = Store.page(c),
        time = time(c),
        battery = battery(c),
        // Hour-by-hour queries are only needed when the data page is showing.
        data = data(c, hourly = Store.page(c) == Store.PAGE_DATA),
        focus = focus(c),
    )

    // ---------------- Time ----------------

    fun time(c: Context): TimeInfo {
        val now = LocalDateTime.now()
        val date = now.toLocalDate()
        val sod = now.toLocalTime().toSecondOfDay().toDouble()
        val dayFrac = sod / 86_400.0
        val mode = Store.timeMode(c)
        val dow = date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH).uppercase()
        val mon = date.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH).uppercase()

        return when (mode) {
            1 -> {
                val f = ((date.dayOfWeek.value - 1) + dayFrac) / 7.0
                val left = (1 - f) * 7
                TimeInfo(
                    mode, f.toFloat(), "OF WEEK",
                    "WEEK ${date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)} · $dow",
                    daysLeft(left), "Resets Monday", null, null,
                )
            }
            2 -> {
                val len = date.lengthOfMonth()
                val f = ((date.dayOfMonth - 1) + dayFrac) / len
                TimeInfo(
                    mode, f.toFloat(), "OF MONTH",
                    date.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH).uppercase(),
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
                val (rise, set) = Sun.times(date, Config.LATITUDE, Config.LONGITUDE, ZoneId.systemDefault())
                val nowT = now.toLocalTime()
                val sub = when {
                    rise != null && nowT.isBefore(rise) -> "Sunrise at ${fmtClock(rise)}"
                    set != null && nowT.isBefore(set) -> "Sunset in ${fmtMinutes(java.time.Duration.between(nowT, set).toMinutes())}"
                    else -> {
                        val tomorrow = Sun.times(date.plusDays(1), Config.LATITUDE, Config.LONGITUDE, ZoneId.systemDefault()).first
                        if (tomorrow != null) "Wind down. Sunrise ${fmtClock(tomorrow)}" else "Wind down."
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

    private fun daysLeft(days: Double): String =
        if (days < 1) "${fmtMinutes((days * 24 * 60).toLong())} left"
        else {
            val d = kotlin.math.ceil(days).toInt()
            if (d == 1) "1 day left" else "$d days left"
        }

    fun fmtMinutes(min: Long): String {
        val m = min.coerceAtLeast(0)
        return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    }

    fun fmtClock(t: LocalTime): String = t.format(DateTimeFormatter.ofPattern("h:mm", Locale.ENGLISH))

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
        val watts = if (plugged && validCurrent && mv > 0) abs(ua) / 1_000_000.0 * mv / 1000.0 else null

        val target = if (level < Config.CHARGE_LIMIT) Config.CHARGE_LIMIT else 100
        val minutes = if (charging && validCurrent && counter > 0 && level in 1 until target) {
            val fullUah = counter / (level / 100.0)
            val needUah = fullUah * (target - level) / 100.0
            (needUah / abs(ua) * 60).toInt().takeIf { it in 1..(24 * 60) }
        } else null

        val pm = c.getSystemService(PowerManager::class.java)
        val thermal = when (pm?.currentThermalStatus ?: 0) {
            PowerManager.THERMAL_STATUS_NONE -> "Cool"
            PowerManager.THERMAL_STATUS_LIGHT, PowerManager.THERMAL_STATUS_MODERATE -> "Warm"
            else -> "Hot"
        }

        return BatteryInfo(level, plugged, charging, watts, minutes, target, tempC, cycles, thermal)
    }

    // ---------------- Data usage ----------------

    fun hasUsageAccess(c: Context): Boolean {
        val ops = c.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    @Suppress("DEPRECATION")
    fun data(c: Context, hourly: Boolean = true): DataInfo {
        val hour = LocalTime.now().hour
        if (!hasUsageAccess(c)) {
            return DataInfo(false, 0, 0, LongArray(24), LongArray(24), hour)
        }
        val nsm = c.getSystemService(NetworkStatsManager::class.java)
            ?: return DataInfo(false, 0, 0, LongArray(24), LongArray(24), hour)
        val zone = ZoneId.systemDefault()
        val midnight = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()

        fun bytes(type: Int, start: Long, end: Long): Long = try {
            val b = nsm.querySummaryForDevice(type, null, start, end)
            b.rxBytes + b.txBytes
        } catch (e: Exception) {
            0L
        }

        val mobile = bytes(ConnectivityManager.TYPE_MOBILE, midnight, now)
        val wifi = bytes(ConnectivityManager.TYPE_WIFI, midnight, now)
        val hm = LongArray(24)
        val hw = LongArray(24)
        if (hourly) for (h in 0..hour) {
            val s = midnight + h * 3_600_000L
            val e = minOf(s + 3_600_000L, now)
            hm[h] = bytes(ConnectivityManager.TYPE_MOBILE, s, e)
            hw[h] = bytes(ConnectivityManager.TYPE_WIFI, s, e)
        }
        return DataInfo(true, mobile, wifi, hm, hw, hour)
    }

    fun fmtBytes(b: Long): String = when {
        b >= 1_000_000_000L -> String.format(Locale.ENGLISH, "%.2f GB", b / 1e9)
        b >= 1_000_000L -> String.format(Locale.ENGLISH, "%.0f MB", b / 1e6)
        b >= 1_000L -> String.format(Locale.ENGLISH, "%.0f KB", b / 1e3)
        else -> "$b B"
    }

    // ---------------- Focus ----------------

    fun focus(c: Context): FocusInfo {
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
