package com.glassring.widget

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate

/** Small persistent state shared by every copy of the widget. */
object Store {
    const val PAGE_TIME = 0
    const val PAGE_STEPS = 1
    const val PAGE_BATTERY = 2
    const val PAGE_DATA = 3
    const val PAGE_FOCUS = 4
    const val PAGE_INSTRUMENTS = 5
    const val PAGE_COMPASS = 6
    const val PAGE_COUNT = 7

    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("glass_ring", Context.MODE_PRIVATE)

    val PAGE_NAMES = arrayOf("Today", "Steps", "Battery", "Data", "Focus", "Instruments", "Compass")

    /** Pages shown on the all-in-one widget, in order. Set in the app's Setup tab. */
    fun pageOrder(c: Context): List<Int> {
        val saved = prefs(c).getString("page_order", null)
            ?: return (0 until PAGE_COUNT).toList()
        val list = saved.split(',').mapNotNull { it.toIntOrNull() }.filter { it in 0 until PAGE_COUNT }.distinct()
        return list.ifEmpty { listOf(PAGE_TIME) }
    }

    fun setPageOrder(c: Context, order: List<Int>) {
        prefs(c).edit().putString("page_order", order.joinToString(",")).apply()
    }

    /** The page on show, always one of the enabled pages. */
    fun page(c: Context): Int {
        val p = prefs(c).getInt("page", PAGE_TIME)
        val order = pageOrder(c)
        return if (p in order) p else order.first()
    }

    fun setPage(c: Context, page: Int) = prefs(c).edit().putInt("page", page).apply()

    /** Next or previous enabled page, wrapping round. */
    fun stepPage(c: Context, delta: Int): Int {
        val order = pageOrder(c)
        val i = order.indexOf(page(c)).coerceAtLeast(0)
        return order[((i + delta) % order.size + order.size) % order.size]
    }

    /** 0 = day, 1 = week, 2 = month, 3 = year. */
    fun timeMode(c: Context) = prefs(c).getInt("time_mode", 0)
    fun cycleTimeMode(c: Context) = prefs(c).edit().putInt("time_mode", (timeMode(c) + 1) % 4).apply()

    /** Battery page: false = stats, true = 24-hour chart. */
    fun batteryChart(c: Context) = prefs(c).getBoolean("battery_chart", true)
    fun toggleBatteryChart(c: Context) = prefs(c).edit().putBoolean("battery_chart", !batteryChart(c)).apply()

    /** Steps page: false = today by hour, true = last 7 days. */
    fun stepsWeek(c: Context) = prefs(c).getBoolean("steps_week", false)
    fun toggleStepsWeek(c: Context) = prefs(c).edit().putBoolean("steps_week", !stepsWeek(c)).apply()

    /** Data page: false = today by hour, true = last 7 days. */
    fun dataWeek(c: Context) = prefs(c).getBoolean("data_week", false)
    fun toggleDataWeek(c: Context) = prefs(c).edit().putBoolean("data_week", !dataWeek(c)).apply()

    // ---- Day-keyed history ("epochDay=value;…"), used for 7-day charts ----

    fun readDaily(c: Context, key: String): MutableMap<Long, Long> {
        val out = HashMap<Long, Long>()
        val raw = prefs(c).getString(key, "") ?: ""
        for (part in raw.split(';')) {
            val i = part.indexOf('=')
            if (i <= 0) continue
            val d = part.substring(0, i).toLongOrNull() ?: continue
            val v = part.substring(i + 1).toLongOrNull() ?: continue
            out[d] = v
        }
        return out
    }

    fun writeDaily(c: Context, key: String, map: Map<Long, Long>, keepDays: Int = 8) {
        val today = LocalDate.now().toEpochDay()
        val s = map.filterKeys { it > today - keepDays }.entries
            .sortedBy { it.key }
            .joinToString(";") { "${it.key}=${it.value}" }
        prefs(c).edit().putString(key, s).apply()
    }

    /** Last [n] days, oldest first, today last. */
    fun lastDays(map: Map<Long, Long>, n: Int): LongArray {
        val today = LocalDate.now().toEpochDay()
        return LongArray(n) { i -> map[today - (n - 1) + i] ?: 0L }
    }

    /** Last 7 days, oldest first, today last. */
    fun lastSevenDays(map: Map<Long, Long>): LongArray {
        val today = LocalDate.now().toEpochDay()
        return LongArray(7) { i -> map[today - 6 + i] ?: 0L }
    }

    // ---- Focus timer state ----
    /** Wall-clock time the running session ends, or 0 when not running. */
    /** The session length you picked (15, 25, 45 or 60 minutes). */
    fun focusMinutes(c: Context) = prefs(c).getLong("focus_minutes", Config.FOCUS_MINUTES)
    fun setFocusMinutes(c: Context, m: Long) = prefs(c).edit().putLong("focus_minutes", m).apply()

    fun focusEndAt(c: Context) = prefs(c).getLong("focus_end", 0L)
    /** Time left on a paused session, or 0 when not paused. */
    fun focusPausedLeft(c: Context) = prefs(c).getLong("focus_paused_left", 0L)
    /** Length of the current session, for drawing the ring. */
    fun focusLength(c: Context) = prefs(c).getLong("focus_len", focusMinutes(c) * 60_000L)
    private fun segmentStart(c: Context) = prefs(c).getLong("focus_seg_start", 0L)

    fun setRunning(c: Context, endAt: Long, length: Long) = prefs(c).edit()
        .putLong("focus_end", endAt)
        .putLong("focus_paused_left", 0L)
        .putLong("focus_len", length)
        .putLong("focus_seg_start", System.currentTimeMillis())
        .apply()

    fun setPaused(c: Context, left: Long) {
        creditSegment(c)
        prefs(c).edit().putLong("focus_end", 0L).putLong("focus_paused_left", left).apply()
    }

    fun extend(c: Context, ms: Long) {
        val p = prefs(c)
        val end = focusEndAt(c)
        val left = focusPausedLeft(c)
        val e = p.edit().putLong("focus_len", focusLength(c) + ms)
        if (end > 0) e.putLong("focus_end", end + ms)
        if (left > 0) e.putLong("focus_paused_left", left + ms)
        e.apply()
    }

    /** Ends the session (finished or stopped). Counts the focused time. */
    fun finish(c: Context, completed: Boolean) {
        if (focusEndAt(c) > 0) creditSegment(c)
        val e = prefs(c).edit()
            .putLong("focus_end", 0L)
            .putLong("focus_paused_left", 0L)
            .putLong("focus_len", focusMinutes(c) * 60_000L)
        if (completed) e.putInt("focus_sessions", sessionsToday(c) + 1)
        e.apply()
    }

    private fun creditSegment(c: Context) {
        val start = segmentStart(c)
        if (start <= 0) return
        val end = focusEndAt(c)
        val now = System.currentTimeMillis().let { if (end > 0) minOf(it, end) else it }
        addFocus(c, (now - start).coerceAtLeast(0))
        prefs(c).edit().putLong("focus_seg_start", 0L).apply()
    }

    private fun addFocus(c: Context, ms: Long) {
        rollDay(c)
        val p = prefs(c)
        val today = LocalDate.now().toEpochDay()
        val last = p.getLong("focus_last_day", Long.MIN_VALUE)
        val streak = when (last) {
            today -> p.getInt("focus_streak", 1)
            today - 1 -> p.getInt("focus_streak", 0) + 1
            else -> 1
        }
        p.edit()
            .putLong("focus_today_ms", p.getLong("focus_today_ms", 0L) + ms)
            .putLong("focus_last_day", today)
            .putInt("focus_streak", streak)
            .apply()
        val daily = readDaily(c, "focus_daily")
        daily[today] = (daily[today] ?: 0L) + ms
        writeDaily(c, "focus_daily", daily)
    }

    /** Resets today's counters after midnight. */
    private fun rollDay(c: Context) {
        val p = prefs(c)
        val today = LocalDate.now().toEpochDay()
        if (p.getLong("focus_day", Long.MIN_VALUE) != today) {
            p.edit().putLong("focus_day", today).putLong("focus_today_ms", 0L).putInt("focus_sessions", 0).apply()
        }
    }

    fun focusTodayMs(c: Context): Long {
        rollDay(c)
        var ms = prefs(c).getLong("focus_today_ms", 0L)
        val start = segmentStart(c)
        val end = focusEndAt(c)
        if (end > 0 && start > 0) ms += (minOf(System.currentTimeMillis(), end) - start).coerceAtLeast(0)
        return ms
    }

    /** Focus minutes for the last 7 days, today last (includes a running session). */
    fun focusWeek(c: Context): LongArray {
        val week = lastSevenDays(readDaily(c, "focus_daily"))
        week[6] = focusTodayMs(c)
        return week
    }

    fun sessionsToday(c: Context): Int {
        rollDay(c)
        return prefs(c).getInt("focus_sessions", 0)
    }

    fun streak(c: Context): Int {
        val p = prefs(c)
        val last = p.getLong("focus_last_day", Long.MIN_VALUE)
        val today = LocalDate.now().toEpochDay()
        return if (last == today || last == today - 1) p.getInt("focus_streak", 0) else 0
    }
}
