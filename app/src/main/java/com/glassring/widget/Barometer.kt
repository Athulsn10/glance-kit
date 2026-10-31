package com.glassring.widget

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager

data class BaroInfo(
    val available: Boolean,
    val hpa: Float?,             // latest reading
    val history: List<Barometer.Point>,
    val change3h: Float?,        // hPa over the last ~3 hours, null until there's enough history
) {
    /** Rough altitude from standard sea-level pressure. Weather shifts it by tens of metres. */
    val altitudeM: Int? get() = hpa?.let { SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, it).toInt() }

    val trend: String
        get() = when {
            change3h == null -> "Measuring trend"
            change3h <= -2f -> "Falling fast"
            change3h <= -0.7f -> "Falling"
            change3h >= 2f -> "Rising fast"
            change3h >= 0.7f -> "Rising"
            else -> "Steady"
        }

    /** The classic barometer reading of the trend. A hint, not a forecast. */
    val hint: String
        get() = when {
            change3h == null -> "Trend after a few hours"
            change3h <= -2f -> "Storm or strong wind possible"
            change3h <= -0.7f -> "Rain more likely"
            change3h >= 0.7f -> "Clearing up"
            else -> "Settled weather"
        }
}

/** Air pressure from the barometer, sampled at most every 10 minutes, kept for 24 hours. */
object Barometer {

    private const val K_HISTORY = "baro_history"
    private const val MIN_GAP_MIN = 10L

    data class Point(val minute: Long, val hpa: Float)

    fun available(c: Context) = SensorReader.has(c, Sensor.TYPE_PRESSURE)

    /** Reads the sensor only if the last sample is older than 10 minutes. */
    fun sample(c: Context) {
        if (!available(c)) return
        val now = System.currentTimeMillis() / 60_000L
        val pts = history(c).toMutableList()
        val last = pts.lastOrNull()
        if (last != null && now - last.minute < MIN_GAP_MIN) return
        val hpa = SensorReader.readOnce(c, Sensor.TYPE_PRESSURE)?.getOrNull(0) ?: return
        if (hpa < 300f || hpa > 1100f) return
        pts += Point(now, hpa)
        val kept = pts.filter { it.minute >= now - 24 * 60 }.takeLast(300)
        Store.prefs(c).edit()
            .putString(K_HISTORY, kept.joinToString(";") { "${it.minute}:${"%.2f".format(java.util.Locale.ENGLISH, it.hpa)}" })
            .apply()
    }

    fun history(c: Context): List<Point> {
        val raw = Store.prefs(c).getString(K_HISTORY, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(';').mapNotNull { part ->
            val i = part.indexOf(':')
            if (i <= 0) return@mapNotNull null
            val m = part.substring(0, i).toLongOrNull() ?: return@mapNotNull null
            val v = part.substring(i + 1).toFloatOrNull() ?: return@mapNotNull null
            Point(m, v)
        }
    }

    fun info(c: Context): BaroInfo {
        val ok = available(c)
        val h = history(c)
        val last = h.lastOrNull()
        // Compare with the reading closest to 3 hours ago (needs at least 1.5 hours of history).
        val change = if (last != null) {
            val target = last.minute - 180
            val ref = h.minByOrNull { kotlin.math.abs(it.minute - target) }
            if (ref != null && last.minute - ref.minute >= 90) last.hpa - ref.hpa else null
        } else null
        return BaroInfo(ok, last?.hpa, h, change)
    }
}
