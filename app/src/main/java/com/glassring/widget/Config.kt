package com.glassring.widget

/** Things you might want to change. */
object Config {
    /** Location for sunrise and sunset. Default: Thrissur. No location permission needed. */
    const val LATITUDE = 10.5276
    const val LONGITUDE = 76.2144

    /** Accent colour (ARGB). */
    val ACCENT: Int = 0xFFFF6A3D.toInt()

    /** Length of one focus session. */
    const val FOCUS_MINUTES = 25L

    /** Battery charge limit you use (Pixel's "Charging optimization" holds at 80%). */
    const val CHARGE_LIMIT = 80
}
