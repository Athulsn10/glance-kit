package com.athulsn10.glancekit

import android.content.Context
import android.graphics.Typeface

/**
 * Clock widget style settings. The time is a live TextClock, which can only use the phone's own
 * fonts (Android doesn't let a widget's TextClock load fonts bundled in the app), so there's no
 * font choice: the time uses the system light font and the dial numbers the system medium font.
 */
object ClockStyle {
    private const val PREF_BG = "clock_bg"

    /** Smoked-glass background behind the clock (on unless turned off). */
    fun background(c: Context): Boolean = Store.prefs(c).getBoolean(PREF_BG, true)

    fun setBackground(c: Context, on: Boolean) {
        Store.prefs(c).edit().putBoolean(PREF_BG, on).apply()
    }

    /** Dial numbers: the system medium font, to match the time. */
    val dialFont: Typeface by lazy { Typeface.create("sans-serif-medium", Typeface.NORMAL) }

    /** "00:00" width and line height of the system light font, in em, for sizing the time. */
    const val TIME_WIDTH_EM = 2.45f
    const val TIME_LINE_EM = 1.20f
}
