package com.glassring.widget

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos

/** Extra details shown in the app. Everything here is free to read, with no permission. */
object Device {

    // ---------------- Sky ----------------

    data class Moon(val name: String, val illumination: Int, val daysToFull: Int, val age: Double)

    /** Moon phase from the average lunar cycle. Accurate to within about a day. */
    fun moon(now: Long = System.currentTimeMillis()): Moon {
        val synodic = 29.530588853
        val knownNew = 947182440000L // 6 Jan 2000, 18:14 UTC
        var age = ((now - knownNew) / 86_400_000.0) % synodic
        if (age < 0) age += synodic
        val illum = ((1 - cos(2 * PI * age / synodic)) / 2 * 100).toInt()
        val name = when {
            age < 1.84 -> "New moon"
            age < 5.53 -> "Waxing crescent"
            age < 9.22 -> "First quarter"
            age < 12.91 -> "Waxing gibbous"
            age < 16.61 -> "Full moon"
            age < 20.30 -> "Waning gibbous"
            age < 23.99 -> "Last quarter"
            age < 27.68 -> "Waning crescent"
            else -> "New moon"
        }
        var toFull = (14.765 - age) % synodic
        if (toFull < 0) toFull += synodic
        return Moon(name, illum, toFull.toInt(), age)
    }

    data class Sky(
        val sunrise: LocalTime?,
        val sunset: LocalTime?,
        val dayLength: Duration?,
        val daylightLeft: Duration?,
        val goldenEvening: LocalTime?,   // start of evening golden hour
    )

    fun sky(): Sky {
        val zone = ZoneId.systemDefault()
        val (rise, set) = Sun.times(LocalDate.now(), Config.LATITUDE, Config.LONGITUDE, zone)
        val now = LocalTime.now()
        val length = if (rise != null && set != null) Duration.between(rise, set) else null
        val left = when {
            rise == null || set == null -> null
            now.isBefore(rise) -> length
            now.isBefore(set) -> Duration.between(now, set)
            else -> Duration.ZERO
        }
        return Sky(rise, set, length, left, set?.minusMinutes(40))
    }

    /** Next alarm set in the Clock app, if any. */
    fun nextAlarm(c: Context): LocalDateTime? {
        val info = c.getSystemService(AlarmManager::class.java)?.nextAlarmClock ?: return null
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(info.triggerTime), ZoneId.systemDefault())
    }

    // ---------------- Battery extras ----------------

    data class BatteryExtras(
        val voltage: Float,
        val source: String,
        val technology: String,
        val healthStatus: String,
        val capacityMah: Int,
        val designMah: Int,
        val drainPerHour: Float?,     // average % per hour while unplugged (last 24h)
        val chargesToday: Int,
    )

    fun batteryExtras(c: Context, history: List<Sampler.BattPoint>): BatteryExtras {
        val i: Intent? = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val mv = i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val source = when (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) {
            BatteryManager.BATTERY_PLUGGED_AC -> "Wall charger"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            BatteryManager.BATTERY_PLUGGED_DOCK -> "Dock"
            else -> "On battery"
        }
        val health = when (i?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0) ?: 0) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheating"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
            else -> "Unknown"
        }
        val tech = i?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "–"

        // Drain: sum of drops between unplugged points, divided by the unplugged time.
        var drop = 0
        var minutes = 0L
        var charges = 0
        val todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toEpochSecond() / 60
        for (k in 1 until history.size) {
            val a = history[k - 1]
            val b = history[k]
            if (!a.charging && !b.charging && b.level <= a.level) {
                drop += a.level - b.level
                minutes += b.minute - a.minute
            }
            if (!a.charging && b.charging && b.minute >= todayStart) charges++
        }
        val drain = if (minutes >= 60 && drop > 0) drop * 60f / minutes else null

        return BatteryExtras(
            mv / 1000f, source, tech, health,
            Sampler.estimatedCapacityMah(c), Collect.designCapacityMah(c), drain, charges,
        )
    }

    // ---------------- Network ----------------

    data class Net(val type: String, val metered: Boolean, val online: Boolean, val downMbps: Int?)

    fun network(c: Context): Net {
        val cm = c.getSystemService(ConnectivityManager::class.java) ?: return Net("Unknown", false, false, null)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return Net("Offline", false, false, null)
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Other"
        } + if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) " + VPN" else ""
        val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val down = caps.linkDownstreamBandwidthKbps.takeIf { it > 0 }?.let { it / 1000 }
        return Net(type, metered, online, down)
    }

    // ---------------- Phone ----------------

    data class Phone(
        val storageFree: Long,
        val storageTotal: Long,
        val ramFree: Long,
        val ramTotal: Long,
        val uptimeMs: Long,
        val android: String,
        val patch: String,
        val patchAgeDays: Long?,
        val model: String,
        val darkMode: Boolean,
        val batterySaver: Boolean,
        val dnd: Boolean,
        val ringer: String,
    )

    fun phone(c: Context): Phone {
        val fs = StatFs(Environment.getDataDirectory().path)
        val mem = ActivityManager.MemoryInfo().also { c.getSystemService(ActivityManager::class.java)?.getMemoryInfo(it) }
        val patchDate = runCatching { LocalDate.parse(Build.VERSION.SECURITY_PATCH) }.getOrNull()
        val patchAge = patchDate?.let { Duration.between(it.atStartOfDay(), LocalDate.now().atStartOfDay()).toDays() }
        val night = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val pm = c.getSystemService(PowerManager::class.java)
        val nm = c.getSystemService(NotificationManager::class.java)
        val dnd = (nm?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL) != NotificationManager.INTERRUPTION_FILTER_ALL
        val ringer = when (c.getSystemService(AudioManager::class.java)?.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "Silent"
            AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
            else -> "Ring"
        }
        return Phone(
            fs.availableBytes, fs.totalBytes, mem.availMem, mem.totalMem,
            SystemClock.elapsedRealtime(),
            "Android ${Build.VERSION.RELEASE}",
            patchDate?.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)) ?: "–",
            patchAge,
            "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
            night,
            pm?.isPowerSaveMode == true,
            dnd,
            ringer,
        )
    }

    fun fmtDuration(d: Duration?): String {
        if (d == null) return "–"
        val m = d.toMinutes().coerceAtLeast(0)
        return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    }

    fun fmtUptime(ms: Long): String {
        val h = ms / 3_600_000
        return if (h >= 48) "${h / 24} days" else if (h >= 1) "${h}h ${(ms / 60_000) % 60}m" else "${ms / 60_000}m"
    }
}
