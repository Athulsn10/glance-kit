package com.athulsn10.glancekit

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import java.time.LocalDate
import java.time.LocalTime

data class StepsInfo(
    val available: Boolean,   // phone has a step counter
    val granted: Boolean,     // physical activity permission given
    val today: Long,
    val hourly: LongArray,
    val week: LongArray,      // last 7 days, today last
    val goal: Long,
    val streak: Int,          // days in a row at or over the goal (today counts once met)
    val month: LongArray,     // last 30 days, today last
) {
    val km: Double get() = today * Config.STRIDE_METRES / 1000.0

    /** Rough walking estimate: about 0.04 kcal per step. */
    val kcal: Int get() = (today * 0.04).toInt()

    /** Hours today with at least 250 steps. */
    val activeHours: Int get() = hourly.count { it >= 250 }

    val bestHour: Int? get() = hourly.indices.maxByOrNull { hourly[it] }?.takeIf { hourly[it] > 0 }
}

/**
 * Daily steps from the phone's hardware step counter.
 *
 * The chip counts steps all the time on its own (Android already keeps it on). On each widget
 * refresh we read its running total once, which takes a moment and costs almost nothing, and add
 * the difference since the last reading to today's count. Nothing streams in the background.
 */
object StepCounter {

    private const val K_LAST = "steps_last_counter"
    private const val K_DAY = "steps_day"
    private const val K_HOURLY = "steps_hourly"
    const val K_DAILY = "steps_daily"

    fun available(c: Context): Boolean =
        c.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

    fun granted(c: Context): Boolean =
        c.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    /** Reads the counter once and updates today's totals. Safe to call often. */
    fun sample(c: Context) {
        if (!granted(c)) return
        val total = readCounter(c) ?: return
        val p = Store.prefs(c)
        val today = LocalDate.now().toEpochDay()
        val hour = LocalTime.now().hour

        var hourly = parse(p.getString(K_HOURLY, null))
        if (p.getLong(K_DAY, Long.MIN_VALUE) != today) hourly = LongArray(24)

        val last = p.getLong(K_LAST, -1L)
        if (last >= 0) {
            // The counter restarts from zero when the phone reboots.
            val delta = if (total >= last) total - last else total
            if (delta > 0) {
                hourly[hour] += delta
                val daily = Store.readDaily(c, K_DAILY)
                daily[today] = (daily[today] ?: 0L) + delta
                Store.writeDaily(c, K_DAILY, daily, keepDays = 40)
            }
        }
        p.edit()
            .putLong(K_LAST, total)
            .putLong(K_DAY, today)
            .putString(K_HOURLY, hourly.joinToString(","))
            .apply()
    }

    fun info(c: Context): StepsInfo {
        val ok = available(c)
        val allowed = granted(c)
        val p = Store.prefs(c)
        val todayDay = LocalDate.now().toEpochDay()
        val hourly = if (p.getLong(K_DAY, Long.MIN_VALUE) == todayDay) parse(p.getString(K_HOURLY, null)) else LongArray(24)
        val daily = Store.readDaily(c, K_DAILY)
        val week = Store.lastSevenDays(daily)
        val goal = Config.STEP_GOAL

        // Streak: count back from yesterday; today adds one once the goal is met.
        var streak = 0
        var d = todayDay - 1
        while ((daily[d] ?: 0L) >= goal) {
            streak++
            d--
        }
        if ((daily[todayDay] ?: 0L) >= goal) streak++

        return StepsInfo(ok, allowed, week[6], hourly, week, goal, streak, Store.lastDays(daily, 30))
    }

    /** One quick reading of the counter's running total. */
    private fun readCounter(c: Context): Long? =
        SensorReader.readOnce(c, Sensor.TYPE_STEP_COUNTER)?.getOrNull(0)?.toLong()

    private fun parse(s: String?): LongArray {
        val out = LongArray(24)
        if (s.isNullOrEmpty()) return out
        val parts = s.split(',')
        for (i in 0 until minOf(24, parts.size)) out[i] = parts[i].toLongOrNull() ?: 0L
        return out
    }
}
