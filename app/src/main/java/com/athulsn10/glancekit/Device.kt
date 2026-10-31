package com.athulsn10.glancekit

import android.app.ActivityManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.graphics.drawable.Drawable
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
        val (rise, set) = Sun.times(LocalDate.now(), Place.latitude, Place.longitude, zone)
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

    data class SunNow(val elevationDeg: Double, val solarNoon: LocalTime?, val up: Boolean)

    /** Where the sun is right now (NOAA equations), and when it's highest today. */
    fun sunNow(): SunNow {
        val zone = ZoneId.systemDefault()
        val now = java.time.ZonedDateTime.now(zone)
        val date = now.toLocalDate()
        val gamma = 2 * PI / 365.0 * (date.dayOfYear - 1 + (now.hour - 12) / 24.0)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * kotlin.math.sin(gamma) -
            0.014615 * cos(2 * gamma) - 0.040849 * kotlin.math.sin(2 * gamma))
        val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * kotlin.math.sin(gamma) -
            0.006758 * cos(2 * gamma) + 0.000907 * kotlin.math.sin(2 * gamma) -
            0.002697 * cos(3 * gamma) + 0.00148 * kotlin.math.sin(3 * gamma)
        val offsetMin = now.offset.totalSeconds / 60.0
        val minutes = now.hour * 60 + now.minute + now.second / 60.0
        val trueSolar = minutes + eqTime + 4 * Place.longitude - offsetMin
        val ha = Math.toRadians(trueSolar / 4 - 180)
        val lat = Math.toRadians(Place.latitude)
        val cosZen = kotlin.math.sin(lat) * kotlin.math.sin(decl) + cos(lat) * cos(decl) * cos(ha)
        val elev = 90 - Math.toDegrees(kotlin.math.acos(cosZen.coerceIn(-1.0, 1.0)))
        val noonMin = 720 - 4 * Place.longitude - eqTime + offsetMin
        val noon = runCatching { LocalTime.ofSecondOfDay(((noonMin * 60).toLong()).coerceIn(0, 86_399)) }.getOrNull()
        return SunNow(elev, noon, elev > -0.833)
    }

    // ---------------- Most-used apps ----------------

    data class AppUse(val label: String, val icon: Drawable?, val ms: Long, val pkg: String = "")
    data class AppUsage(
        val apps: List<AppUse>,
        val totalMs: Long,
        val unlocks: Int,
        val allowed: Boolean,
        val recent: List<AppUse> = emptyList(),   // used in the last 15 minutes
    )

    /** Screen time per app since midnight, most used first. Needs Usage access. */
    fun topApps(c: Context, limit: Int = 8): AppUsage {
        if (!Collect.hasUsageAccess(c)) return AppUsage(emptyList(), 0, 0, false)
        val usm = c.getSystemService(UsageStatsManager::class.java) ?: return AppUsage(emptyList(), 0, 0, false)
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = System.currentTimeMillis()
        val pm = c.packageManager
        val home = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        val skip = setOf(c.packageName, home, "com.android.systemui")
        val stats = runCatching { usm.queryAndAggregateUsageStats(start, end) }.getOrNull() ?: emptyMap()
        val used = stats.values
            .filter { it.totalTimeInForeground >= 60_000 && it.packageName !in skip }
            .sortedByDescending { it.totalTimeInForeground }
        val total = used.sumOf { it.totalTimeInForeground }
        fun describe(pkg: String, ms: Long): AppUse {
            val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
            return AppUse(
                info?.let { pm.getApplicationLabel(it).toString() } ?: pkg.substringAfterLast('.'),
                info?.let { runCatching { pm.getApplicationIcon(it) }.getOrNull() },
                ms, pkg,
            )
        }
        val apps = used.take(limit).map { describe(it.packageName, it.totalTimeInForeground) }
        val recent = stats.values
            .filter { end - it.lastTimeUsed < 15 * 60_000 && it.packageName !in skip && it.totalTimeInForeground > 0 }
            .sortedByDescending { it.lastTimeUsed }
            .take(8)
            .map { describe(it.packageName, end - it.lastTimeUsed) }
        // Unlocks today: each time the lock screen is dismissed.
        var unlocks = 0
        runCatching {
            val ev = usm.queryEvents(start, end)
            val e = UsageEvents.Event()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.KEYGUARD_HIDDEN) unlocks++
            }
        }
        return AppUsage(apps, total, unlocks, true, recent)
    }

    // ---------------- Storage per app ----------------

    data class AppSize(val label: String, val icon: Drawable?, val pkg: String, val app: Long, val data: Long, val cache: Long) {
        val total get() = app + data + cache
    }
    data class AppStorage(val apps: List<AppSize>, val allowed: Boolean) {
        val totalCache get() = apps.sumOf { it.cache }
    }

    /** Size, data and cache of every app with an icon. Needs Usage access. */
    fun appStorage(c: Context): AppStorage {
        if (!Collect.hasUsageAccess(c)) return AppStorage(emptyList(), false)
        val ssm = c.getSystemService(StorageStatsManager::class.java) ?: return AppStorage(emptyList(), false)
        val pm = c.packageManager
        val user = android.os.Process.myUserHandle()
        val pkgs = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.distinct()
        val list = pkgs.mapNotNull { pkg ->
            runCatching {
                val st = ssm.queryStatsForPackage(android.os.storage.StorageManager.UUID_DEFAULT, pkg, user)
                val info = pm.getApplicationInfo(pkg, 0)
                AppSize(pm.getApplicationLabel(info).toString(), runCatching { pm.getApplicationIcon(info) }.getOrNull(),
                    pkg, st.appBytes, (st.dataBytes - st.cacheBytes).coerceAtLeast(0), st.cacheBytes)
            }.getOrNull()
        }
        return AppStorage(list, true)
    }

    // ---------------- RAM ----------------

    data class Ram(val total: Long, val free: Long, val low: Boolean, val threshold: Long) {
        val used get() = total - free
    }

    fun ram(c: Context): Ram {
        val mi = ActivityManager.MemoryInfo()
        c.getSystemService(ActivityManager::class.java)?.getMemoryInfo(mi)
        return Ram(mi.totalMem, mi.availMem, mi.lowMemory, mi.threshold)
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
