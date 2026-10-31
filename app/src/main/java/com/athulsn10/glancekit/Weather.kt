package com.athulsn10.glancekit

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Weather from MET Norway's free forecast service (api.met.no, CC BY 4.0).
 * Fetched about every 30 minutes, only with a connection, and kept on the phone between fetches.
 * Only your approximate location (rounded to about 1 km, see Place) is sent.
 */
object Weather {

    enum class Sky { CLEAR, PARTLY, CLOUDY, RAIN, STORM, SNOW, FOG }

    data class Now(
        val tempC: Int,
        val sky: Sky,
        val text: String,
        val high: Int?,
        val low: Int?,
        val humidity: Int?,
        val rainChance: Int?,
        val fetchedAt: Long,
    ) {
        /** e.g. "28°  Light rain · H 31° L 24°" */
        val line: String get() {
            val extra = when {
                high != null && low != null -> "H $high° L $low°"
                humidity != null -> "humidity $humidity%"
                else -> null
            }
            return "$tempC°  $text" + (extra?.let { " · $it" } ?: "")
        }
    }

    private const val K = "wx_json"
    private const val KA = "aq_json"

    /** US AQI with its category name and colour. */
    data class Air(val aqi: Int, val pm25: Double?, val fetchedAt: Long) {
        val level: String get() = when {
            aqi <= 50 -> "Good"
            aqi <= 100 -> "Moderate"
            aqi <= 150 -> "Unhealthy for sensitive groups"
            aqi <= 200 -> "Unhealthy"
            aqi <= 300 -> "Very unhealthy"
            else -> "Hazardous"
        }
        val color: Int get() = when {
            aqi <= 50 -> 0xFF6BCB77.toInt()
            aqi <= 100 -> 0xFFE8C547.toInt()
            aqi <= 150 -> 0xFFFF8C42.toInt()
            aqi <= 200 -> 0xFFE8483A.toInt()
            aqi <= 300 -> 0xFFB06AE8.toInt()
            else -> 0xFFC0392B.toInt()
        }
        /** e.g. "AQI 72 · Moderate · PM2.5 22" */
        val line: String get() {
            val short = if (aqi in 101..150) "Unhealthy (sensitive)" else level
            return "AQI $aqi · $short" + (pm25?.let { " · PM2.5 ${it.roundToInt()}" } ?: "")
        }
    }

    fun air(c: Context): Air? {
        val raw = Store.prefs(c).getString(KA, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            val at = o.getLong("at")
            if (System.currentTimeMillis() - at > MAX_AGE) return null
            Air(o.getInt("aqi"), o.optDouble("pm25", Double.NaN).takeIf { !it.isNaN() }, at)
        }.getOrNull()
    }

    /** Air quality from Open-Meteo (CAMS global model, CC BY 4.0). Same rounded location, no key. */
    fun fetchAir(c: Context): Boolean {
        Place.ensureLoaded(c)
        val lat = String.format(Locale.US, "%.4f", Place.latitude)
        val lon = String.format(Locale.US, "%.4f", Place.longitude)
        val url = URL("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=$lat&longitude=$lon&current=us_aqi,pm2_5")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "GlanceKit/1 github.com/Athulsn10/glance-kit")
        }
        return try {
            if (conn.responseCode != 200) return false
            val cur = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getJSONObject("current")
            if (cur.isNull("us_aqi")) return false
            val o = JSONObject().apply {
                put("at", System.currentTimeMillis())
                put("aqi", cur.getDouble("us_aqi").roundToInt())
                if (!cur.isNull("pm2_5")) put("pm25", cur.getDouble("pm2_5"))
            }
            Store.prefs(c).edit().putString(KA, o.toString()).apply()
            true
        } catch (e: Exception) {
            android.util.Log.w("GlanceKit", "Air quality fetch failed", e)
            false
        } finally {
            conn.disconnect()
        }
    }
    private const val MAX_AGE = 3 * 60 * 60 * 1000L // older than this: don't show it

    /** Latest saved weather, or null if there's none recent enough. */
    fun now(c: Context): Now? {
        val raw = Store.prefs(c).getString(K, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            val at = o.getLong("at")
            if (System.currentTimeMillis() - at > MAX_AGE) return null
            Now(
                tempC = o.getInt("t"), sky = Sky.valueOf(o.getString("sky")), text = o.getString("text"),
                high = o.optInt("hi", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
                low = o.optInt("lo", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
                humidity = o.optInt("rh", -1).takeIf { it >= 0 },
                rainChance = o.optInt("pop", -1).takeIf { it >= 0 },
                fetchedAt = at,
            )
        }.getOrNull()
    }

    /** Is anything on the home screen showing weather? */
    fun wanted(c: Context) = ClockViews.ids(c).isNotEmpty() ||
        (Store.PAGE_CLOCK in Store.pageOrder(c) && widgetIds(c).isNotEmpty())

    /** Download and save the current forecast. Returns true on success. Runs off the main thread. */
    fun fetch(c: Context): Boolean {
        Place.ensureLoaded(c)
        val lat = String.format(Locale.US, "%.4f", Place.latitude)
        val lon = String.format(Locale.US, "%.4f", Place.longitude)
        val url = URL("https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=$lat&lon=$lon")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            // MET Norway asks every app to say who it is.
            setRequestProperty("User-Agent", "GlanceKit/1 github.com/Athulsn10/glance-kit")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (conn.responseCode != 200) return false
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val saved = parse(body) ?: return false
            Store.prefs(c).edit().putString(K, saved.toString()).apply()
            true
        } catch (e: Exception) {
            android.util.Log.w("GlanceKit", "Weather fetch failed", e)
            false
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(body: String): JSONObject? {
        val series = JSONObject(body).getJSONObject("properties").getJSONArray("timeseries")
        if (series.length() == 0) return null
        val now = System.currentTimeMillis()
        // The entry for the current hour (the first one that isn't in the past).
        var idx = 0
        for (i in 0 until series.length()) {
            val t = Instant.parse(series.getJSONObject(i).getString("time")).toEpochMilli()
            if (t > now - 30 * 60_000) { idx = i; break }
        }
        val cur = series.getJSONObject(idx).getJSONObject("data")
        val details = cur.getJSONObject("instant").getJSONObject("details")
        val next = cur.optJSONObject("next_1_hours") ?: cur.optJSONObject("next_6_hours")
        val symbol = next?.optJSONObject("summary")?.optString("symbol_code") ?: "cloudy"

        // Today's high and low from the hourly temperatures (local day).
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        var hi = Double.NEGATIVE_INFINITY
        var lo = Double.POSITIVE_INFINITY
        for (i in 0 until series.length()) {
            val e = series.getJSONObject(i)
            val day = Instant.parse(e.getString("time")).atZone(zone).toLocalDate()
            if (day != today) continue
            val t = e.getJSONObject("data").getJSONObject("instant").getJSONObject("details").optDouble("air_temperature", Double.NaN)
            if (t.isNaN()) continue
            if (t > hi) hi = t
            if (t < lo) lo = t
        }
        return JSONObject().apply {
            put("at", now)
            put("t", details.getDouble("air_temperature").roundToInt())
            put("sky", sky(symbol).name)
            put("text", describe(symbol))
            if (hi.isFinite() && lo.isFinite() && hi > lo) { put("hi", hi.roundToInt()); put("lo", lo.roundToInt()) }
            if (details.has("relative_humidity")) put("rh", details.getDouble("relative_humidity").roundToInt())
        }
    }

    /** MET symbol codes (e.g. "lightrainshowers_day") to the icon groups we draw. */
    fun sky(symbol: String): Sky {
        val s = symbol.substringBefore('_')
        return when {
            "thunder" in s -> Sky.STORM
            "snow" in s || "sleet" in s -> Sky.SNOW
            "rain" in s -> Sky.RAIN
            s == "fog" -> Sky.FOG
            s == "cloudy" -> Sky.CLOUDY
            s == "partlycloudy" || s == "fair" -> Sky.PARTLY
            else -> Sky.CLEAR
        }
    }

    /** Readable name for a MET symbol code. */
    fun describe(symbol: String): String {
        val s = symbol.substringBefore('_')
        val words = linkedMapOf(
            "clearsky" to "Clear", "fair" to "Mostly clear", "partlycloudy" to "Partly cloudy", "cloudy" to "Cloudy",
            "fog" to "Fog", "heavyrainandthunder" to "Heavy rain and thunder", "rainandthunder" to "Rain and thunder",
            "lightrainandthunder" to "Light rain and thunder", "heavyrainshowersandthunder" to "Thundery showers",
            "rainshowersandthunder" to "Thundery showers", "lightrainshowersandthunder" to "Thundery showers",
            "heavyrainshowers" to "Heavy showers", "rainshowers" to "Rain showers", "lightrainshowers" to "Light showers",
            "heavyrain" to "Heavy rain", "rain" to "Rain", "lightrain" to "Light rain",
            "heavysnow" to "Heavy snow", "snow" to "Snow", "lightsnow" to "Light snow",
            "snowshowers" to "Snow showers", "lightsnowshowers" to "Light snow showers", "heavysnowshowers" to "Heavy snow showers",
            "sleet" to "Sleet", "lightsleet" to "Light sleet", "heavysleet" to "Heavy sleet",
            "sleetshowers" to "Sleet showers", "lightsleetshowers" to "Light sleet showers",
        )
        return words[s] ?: when (sky(symbol)) {
            Sky.STORM -> "Thunderstorm"; Sky.SNOW -> "Snow"; Sky.RAIN -> "Rain"; Sky.FOG -> "Fog"
            Sky.CLOUDY -> "Cloudy"; Sky.PARTLY -> "Partly cloudy"; Sky.CLEAR -> "Clear"
        }
    }

    // ---------------- Scheduling ----------------

    private const val PERIODIC = "weather_periodic"
    private const val ONCE = "weather_once"

    fun schedule(c: Context) {
        val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val req = PeriodicWorkRequestBuilder<WeatherWorker>(30, TimeUnit.MINUTES).setConstraints(net).build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Fetch soon (e.g. just after the widget is added), if the saved weather is older than 20 minutes. */
    fun refreshSoon(c: Context) {
        fun savedAt(key: String) = runCatching { JSONObject(Store.prefs(c).getString(key, "{}")!!).optLong("at", 0) }.getOrDefault(0L)
        val at = minOf(savedAt(K), savedAt(KA)) // the older of weather and air quality
        if (System.currentTimeMillis() - at < 20 * 60_000) return
        val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val req = OneTimeWorkRequestBuilder<WeatherWorker>().setConstraints(net).build()
        WorkManager.getInstance(c).enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, req)
    }

    /** Fetch right away (e.g. after the location changed). */
    fun refreshNow(c: Context) {
        if (!wanted(c)) return
        val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val req = OneTimeWorkRequestBuilder<WeatherWorker>().setConstraints(net).build()
        WorkManager.getInstance(c).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, req)
    }

    fun cancel(c: Context) {
        val wm = WorkManager.getInstance(c)
        wm.cancelUniqueWork(PERIODIC)
        wm.cancelUniqueWork(ONCE)
    }
}

class WeatherWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        if (!Weather.wanted(c)) { Weather.cancel(c); return Result.success() }
        val ok = Weather.fetch(c)
        runCatching { Weather.fetchAir(c) }
        refreshAll(c)
        return if (ok || runAttemptCount >= 2) Result.success() else Result.retry()
    }
}
