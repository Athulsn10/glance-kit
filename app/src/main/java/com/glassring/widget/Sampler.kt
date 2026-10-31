package com.glassring.widget

import android.content.Context
import android.net.TrafficStats
import java.time.LocalDate
import java.time.LocalTime

/**
 * Records small bits of history every time the widget refreshes, so the charts have data.
 * Everything here is cheap: a few counter reads and one SharedPreferences write.
 *
 * Data usage comes from TrafficStats, which needs no permission. It counts from when the
 * app was installed (or last sampled), so the first day starts part-way through.
 */
object Sampler {

    private const val K_LAST_MOBILE = "ts_last_mobile"
    private const val K_LAST_TOTAL = "ts_last_total"
    private const val K_DAY = "ts_day"
    private const val K_HOURLY_MOBILE = "ts_hourly_mobile"
    private const val K_HOURLY_WIFI = "ts_hourly_wifi"
    const val K_DAILY_MOBILE = "data_daily_mobile"
    const val K_DAILY_WIFI = "data_daily_wifi"
    private const val K_BATT_HISTORY = "batt_history"
    private const val K_CAPACITY = "batt_capacity_mah"

    /** Call on every refresh. */
    fun sample(c: Context, battery: BatteryInfo?) {
        sampleData(c)
        if (battery != null) sampleBattery(c, battery)
    }

    // ---------------- Data ----------------

    private fun sampleData(c: Context) {
        val mobileNow = (TrafficStats.getMobileRxBytes() + TrafficStats.getMobileTxBytes()).coerceAtLeast(0)
        val totalNow = (TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes()).coerceAtLeast(0)
        if (totalNow <= 0L) return // counters unsupported

        val p = Store.prefs(c)
        val today = LocalDate.now().toEpochDay()
        val hour = LocalTime.now().hour

        var hourlyMobile = parse(p.getString(K_HOURLY_MOBILE, null))
        var hourlyWifi = parse(p.getString(K_HOURLY_WIFI, null))
        if (p.getLong(K_DAY, Long.MIN_VALUE) != today) {
            hourlyMobile = LongArray(24)
            hourlyWifi = LongArray(24)
        }

        val lastMobile = p.getLong(K_LAST_MOBILE, -1L)
        val lastTotal = p.getLong(K_LAST_TOTAL, -1L)
        if (lastTotal >= 0) {
            // Counters reset on reboot: then everything since boot is new.
            val dMobile = if (mobileNow >= lastMobile) mobileNow - lastMobile else mobileNow
            val dTotal = if (totalNow >= lastTotal) totalNow - lastTotal else totalNow
            val dWifi = (dTotal - dMobile).coerceAtLeast(0)
            hourlyMobile[hour] += dMobile
            hourlyWifi[hour] += dWifi

            val dm = Store.readDaily(c, K_DAILY_MOBILE)
            val dw = Store.readDaily(c, K_DAILY_WIFI)
            dm[today] = (dm[today] ?: 0L) + dMobile
            dw[today] = (dw[today] ?: 0L) + dWifi
            Store.writeDaily(c, K_DAILY_MOBILE, dm)
            Store.writeDaily(c, K_DAILY_WIFI, dw)
        }

        p.edit()
            .putLong(K_LAST_MOBILE, mobileNow)
            .putLong(K_LAST_TOTAL, totalNow)
            .putLong(K_DAY, today)
            .putString(K_HOURLY_MOBILE, hourlyMobile.joinToString(","))
            .putString(K_HOURLY_WIFI, hourlyWifi.joinToString(","))
            .apply()
    }

    fun hourly(c: Context): Pair<LongArray, LongArray> {
        val p = Store.prefs(c)
        if (p.getLong(K_DAY, Long.MIN_VALUE) != LocalDate.now().toEpochDay()) return LongArray(24) to LongArray(24)
        return parse(p.getString(K_HOURLY_MOBILE, null)) to parse(p.getString(K_HOURLY_WIFI, null))
    }

    private fun parse(s: String?): LongArray {
        val out = LongArray(24)
        if (s.isNullOrEmpty()) return out
        val parts = s.split(',')
        for (i in 0 until minOf(24, parts.size)) out[i] = parts[i].toLongOrNull() ?: 0L
        return out
    }

    // ---------------- Battery ----------------

    /** One point per 5 minutes (or on a change of 1% or plug state), last 24 hours. */
    private fun sampleBattery(c: Context, b: BatteryInfo) {
        val p = Store.prefs(c)
        val nowMin = System.currentTimeMillis() / 60_000L
        val pts = history(c).toMutableList()
        val last = pts.lastOrNull()
        val changed = last == null || last.level != b.level || last.charging != b.plugged
        if (changed || nowMin - last!!.minute >= 5) {
            pts += BattPoint(nowMin, b.level, b.plugged)
        }
        val cutoff = nowMin - 24 * 60
        val kept = pts.filter { it.minute >= cutoff }.takeLast(400)
        p.edit().putString(K_BATT_HISTORY, kept.joinToString(";") { "${it.minute}:${it.level}:${if (it.charging) 1 else 0}" }).apply()

        // Capacity estimate: charge counter at this level, scaled to 100%.
        // Only trusted mid-range, and smoothed so it doesn't jump around.
        if (b.chargeCounterUah > 0 && b.level in 20..95) {
            val est = b.chargeCounterUah / 1000.0 / (b.level / 100.0)
            val old = p.getFloat(K_CAPACITY, 0f).toDouble()
            val smoothed = if (old <= 0) est else old * 0.9 + est * 0.1
            p.edit().putFloat(K_CAPACITY, smoothed.toFloat()).apply()
        }
    }

    data class BattPoint(val minute: Long, val level: Int, val charging: Boolean)

    fun history(c: Context): List<BattPoint> {
        val raw = Store.prefs(c).getString(K_BATT_HISTORY, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(';').mapNotNull { part ->
            val f = part.split(':')
            if (f.size != 3) return@mapNotNull null
            val m = f[0].toLongOrNull() ?: return@mapNotNull null
            val l = f[1].toIntOrNull() ?: return@mapNotNull null
            BattPoint(m, l, f[2] == "1")
        }
    }

    /** Estimated full capacity in mAh, or 0 when not known yet. */
    fun estimatedCapacityMah(c: Context): Int = Store.prefs(c).getFloat(K_CAPACITY, 0f).toInt()
}
