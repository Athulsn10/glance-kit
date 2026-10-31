package com.glassring.widget

import android.content.Context
import java.time.LocalDate

/** Small persistent state shared by every copy of the widget. */
object Store {
    const val PAGE_TIME = 0
    const val PAGE_BATTERY = 1
    const val PAGE_DATA = 2
    const val PAGE_FOCUS = 3
    const val PAGE_COUNT = 4

    private fun prefs(c: Context) = c.getSharedPreferences("glass_ring", Context.MODE_PRIVATE)

    fun page(c: Context) = prefs(c).getInt("page", PAGE_TIME)
    fun setPage(c: Context, page: Int) =
        prefs(c).edit().putInt("page", ((page % PAGE_COUNT) + PAGE_COUNT) % PAGE_COUNT).apply()

    /** 0 = day, 1 = week, 2 = month, 3 = year. */
    fun timeMode(c: Context) = prefs(c).getInt("time_mode", 0)
    fun cycleTimeMode(c: Context) = prefs(c).edit().putInt("time_mode", (timeMode(c) + 1) % 4).apply()

    // ---- Focus timer state ----
    /** Wall-clock time the running session ends, or 0 when not running. */
    fun focusEndAt(c: Context) = prefs(c).getLong("focus_end", 0L)
    /** Time left on a paused session, or 0 when not paused. */
    fun focusPausedLeft(c: Context) = prefs(c).getLong("focus_paused_left", 0L)
    /** Length of the current session, for drawing the ring. */
    fun focusLength(c: Context) = prefs(c).getLong("focus_len", Config.FOCUS_MINUTES * 60_000L)
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
            .putLong("focus_len", Config.FOCUS_MINUTES * 60_000L)
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
