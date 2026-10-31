package com.athulsn10.glancekit

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Where you are, roughly, for sunrise, sunset, weather and air quality.
 *
 * Uses Android's *approximate* location only (no GPS, no precise location), and only while the
 * app is open. The result is rounded to about 1 km and kept on the phone. Without permission,
 * the default location in [Config] is used.
 */
object Place {
    private const val K_LAT = "place_lat"
    private const val K_LON = "place_lon"
    private const val K_AT = "place_at"

    @Volatile private var lat = Config.DEFAULT_LATITUDE
    @Volatile private var lon = Config.DEFAULT_LONGITUDE
    @Volatile private var loaded = false

    val latitude get() = lat
    val longitude get() = lon

    /** Load the saved location. Cheap; call at any entry point that needs it. */
    fun load(c: Context) {
        val p = Store.prefs(c)
        if (p.contains(K_LAT) && p.contains(K_LON)) {
            lat = p.getFloat(K_LAT, Config.DEFAULT_LATITUDE.toFloat()).toDouble()
            lon = p.getFloat(K_LON, Config.DEFAULT_LONGITUDE.toFloat()).toDouble()
        } else {
            lat = Config.DEFAULT_LATITUDE
            lon = Config.DEFAULT_LONGITUDE
        }
        loaded = true
    }

    fun ensureLoaded(c: Context) { if (!loaded) load(c) }

    fun granted(c: Context) =
        c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** True when we have a location from the phone (not the built-in default). */
    fun isFromPhone(c: Context) = Store.prefs(c).contains(K_LAT)

    /** When the location was last updated, or 0. */
    fun updatedAt(c: Context) = Store.prefs(c).getLong(K_AT, 0L)

    /** Forget the phone's location and go back to the default. */
    fun clear(c: Context) {
        Store.prefs(c).edit().remove(K_LAT).remove(K_LON).remove(K_AT).apply()
        load(c)
    }

    private val worker = Executors.newSingleThreadExecutor()

    /**
     * Ask Android for the current approximate location (foreground only). Calls [done] with
     * true if it changed by more than a few km, so the caller can refresh widgets.
     */
    fun refresh(c: Context, done: (Boolean) -> Unit = {}) {
        if (!granted(c)) { done(false); return }
        val lm = c.getSystemService(LocationManager::class.java) ?: run { done(false); return }
        val provider = when {
            lm.isProviderEnabled(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> { done(false); return }
        }
        try {
            lm.getCurrentLocation(provider, null, worker) { loc: Location? -> done(save(c, loc)) }
        } catch (e: SecurityException) {
            done(false)
        }
    }

    private fun save(c: Context, loc: Location?): Boolean {
        if (loc == null) return false
        // Round to 2 decimals (about 1 km): plenty for sunrise and weather, and more private.
        val la = (loc.latitude * 100).roundToInt() / 100.0
        val lo = (loc.longitude * 100).roundToInt() / 100.0
        val moved = !isFromPhone(c) || kotlin.math.abs(la - lat) > 0.05 || kotlin.math.abs(lo - lon) > 0.05
        Store.prefs(c).edit()
            .putFloat(K_LAT, la.toFloat()).putFloat(K_LON, lo.toFloat())
            .putLong(K_AT, System.currentTimeMillis()).apply()
        lat = la; lon = lo; loaded = true
        return moved
    }

    /** e.g. "10.53° N, 76.21° E" */
    fun label(): String {
        val ns = if (lat >= 0) "N" else "S"
        val ew = if (lon >= 0) "E" else "W"
        return String.format(java.util.Locale.ENGLISH, "%.2f° %s, %.2f° %s", kotlin.math.abs(lat), ns, kotlin.math.abs(lon), ew)
    }
}
