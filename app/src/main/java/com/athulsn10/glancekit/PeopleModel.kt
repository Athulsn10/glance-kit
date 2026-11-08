package com.athulsn10.glancekit

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Something you can send: it buzzes the other phone with its own vibration pattern. */
data class PeopleAction(val key: String, val label: String, val pattern: LongArray, val past: String)

/** Who a group is for: its look, symbol, actions and size. */
enum class Circle(
    val key: String, val label: String, val title: String,
    val accent: Int, val soft: Int, val symbol: String, val max: Int,
    val actions: List<PeopleAction>,
) {
    COUPLE("couple", "Partner", "US", 0xFFFF7A96.toInt(), 0xFFFF7A96.toInt(), "heart", 2, listOf(
        PeopleAction("hug", "Hug", longArrayOf(0, 80, 90, 80, 420, 80, 90, 80), "sent a hug"),
        PeopleAction("miss", "Miss you", longArrayOf(0, 650, 220, 650), "misses you"),
        PeopleAction("kiss", "Kiss", longArrayOf(0, 60, 140, 60), "sent a kiss"),
    )),
    FRIENDS("friends", "Friend", "BESTIES", 0xFF5EC4FF.toInt(), 0xFFFFCE54.toInt(), "star", 2, listOf(
        PeopleAction("highfive", "High five", longArrayOf(0, 45, 70, 45), "sent a high five"),
        PeopleAction("poke", "Poke", longArrayOf(0, 90), "poked you"),
        PeopleAction("hangout", "Hang out?", longArrayOf(0, 100, 80, 100, 80, 100), "wants to hang out"),
    )),
    FAMILY("family", "Family", "FAMILY", 0xFFFFB84C.toInt(), 0xFF82D696.toInt(), "home", 5, listOf(
        PeopleAction("home", "Home safe", longArrayOf(0, 70, 140, 70), "got home safe"),
        PeopleAction("call", "Call me", longArrayOf(0, 200, 150, 200, 150, 200), "asked you to call"),
        PeopleAction("love", "Love you", longArrayOf(0, 80, 90, 80, 420, 80, 90, 80), "sends love"),
    ));

    fun action(key: String?) = actions.firstOrNull { it.key == key }

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: COUPLE
    }
}

/** One person in a group, as stored in the cloud (only what the widget needs). */
data class Person(
    val uid: String,
    val name: String,
    val avatar: Avatar,
    val lat: Double?,
    val lon: Double?,
    val at: Long,            // when the location was taken (ms)
    val paused: Boolean,
    val tz: String?,
    val atHome: Boolean,
    val mood: Mood?,         // a mood they set for today, if any
    val moodUntil: Long,
) {
    val hasPlace get() = lat != null && lon != null && !paused
    fun moodNow(now: Long = System.currentTimeMillis()) = if (mood != null && now < moodUntil) mood else null
}

data class Group(
    val id: String,
    val circle: Circle,
    val members: List<String>,
    val lastFrom: String?,
    val lastAction: String?,
    val lastAt: Long,
)

enum class Scene { WAITING, TOGETHER, NEAR, CITY, FAR, RECEIVED, PAUSED }

object People {
    const val TOGETHER_M = 100.0
    const val NEAR_M = 5_000.0
    const val CITY_M = 50_000.0
    const val STALE_MS = 3 * 60 * 60 * 1000L
    const val RECEIVED_MS = 10 * 60 * 1000L

    /** Great-circle distance in metres. */
    fun distance(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(aLat); val p2 = Math.toRadians(bLat)
        val dp = p2 - p1; val dl = Math.toRadians(bLon - aLon)
        val h = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }

    fun distance(a: Person?, b: Person?): Double? {
        if (a?.lat == null || a.lon == null || b?.lat == null || b.lon == null) return null
        return distance(a.lat, a.lon, b.lat, b.lon)
    }

    fun fmtDistance(m: Double): String = when {
        m < 1000 -> "${(m / 10).toInt() * 10} m"
        m < 10_000 -> String.format(Locale.ENGLISH, "%.1f km", m / 1000)
        else -> String.format(Locale.ENGLISH, "%,d km", (m / 1000).toInt())
    }

    fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
        val m = ((now - ms) / 60_000).coerceAtLeast(0)
        return when {
            m < 1 -> "just now"
            m < 60 -> "$m min ago"
            m < 48 * 60 -> "${m / 60} h ago"
            else -> "${m / 1440} days ago"
        }
    }

    /** The scene for a two-person group. */
    fun scene(g: Group, me: Person?, other: Person?, now: Long = System.currentTimeMillis()): Scene {
        if (g.lastFrom != null && g.lastFrom != me?.uid && now - g.lastAt < RECEIVED_MS) return Scene.RECEIVED
        if (other == null) return Scene.WAITING
        if (other.paused || other.lat == null || now - other.at > STALE_MS) return Scene.PAUSED
        val d = distance(me, other) ?: return Scene.WAITING
        return when {
            d < TOGETHER_M -> Scene.TOGETHER
            d < NEAR_M -> Scene.NEAR
            d < CITY_M -> Scene.CITY
            else -> Scene.FAR
        }
    }

    /** Moods for (me, them) in a scene. A mood someone set for today wins. */
    fun moods(circle: Circle, scene: Scene, me: Person?, other: Person?): Pair<Mood, Mood> {
        val couple = circle == Circle.COUPLE
        val base = when (scene) {
            Scene.TOGETHER -> if (couple) Mood.LOVE to Mood.KISS else Mood.LAUGH to Mood.EXCITED
            Scene.NEAR -> Mood.EXCITED to Mood.HAPPY
            Scene.CITY -> Mood.HAPPY to Mood.HAPPY
            Scene.FAR -> if (couple) Mood.MISS to Mood.MISS else Mood.HAPPY to Mood.WINK
            Scene.RECEIVED -> if (couple) Mood.SHY to Mood.KISS else Mood.WOW to Mood.WINK
            Scene.PAUSED -> Mood.POUT to Mood.SLEEP
            Scene.WAITING -> Mood.HAPPY to Mood.SLEEP
        }
        return (me?.moodNow() ?: base.first) to (other?.moodNow() ?: base.second)
    }

    fun status(circle: Circle, scene: Scene, g: Group): String = when (scene) {
        Scene.TOGETHER -> if (circle == Circle.COUPLE) "TOGETHER" else "HANGING OUT"
        Scene.NEAR -> "NEARBY"
        Scene.CITY -> "SAME CITY"
        Scene.FAR -> "MILES APART"
        Scene.RECEIVED -> (circle.action(g.lastAction)?.label ?: "Hi").uppercase() + " RECEIVED"
        Scene.PAUSED -> "PAUSED"
        Scene.WAITING -> "WAITING"
    }

    /** Big line and small line under the scene. */
    fun lines(circle: Circle, scene: Scene, g: Group, me: Person?, other: Person?): Pair<String, String> {
        val name = other?.name ?: "Them"
        val d = distance(me, other)
        return when (scene) {
            Scene.TOGETHER -> (if (circle == Circle.COUPLE) "Together" else "Hanging out") to "within 100 m of each other"
            Scene.NEAR, Scene.CITY -> fmtDistance(d ?: 0.0) to "apart · updated ${ago(other?.at ?: 0)}"
            Scene.FAR -> {
                val their = other?.tz?.let { runCatching { ZoneId.of(it) }.getOrNull() }
                val local = if (their != null && their != ZoneId.systemDefault()) {
                    " · their time " + Instant.now().atZone(their).format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
                } else ""
                fmtDistance(d ?: 0.0) to "apart$local"
            }
            Scene.RECEIVED -> {
                val who = if (g.lastFrom == other?.uid) name else "Someone"
                "$who ${circle.action(g.lastAction)?.past ?: "said hi"}" to "${ago(g.lastAt)} · tap to send one back"
            }
            Scene.PAUSED -> if (other?.paused == true) "$name paused sharing" to ("last seen ${ago(other.at)}" + (d?.let { " · ${fmtDistance(it)}" } ?: ""))
            else "$name hasn't updated" to "last seen ${ago(other?.at ?: 0)}"
            Scene.WAITING -> if (other == null) "Waiting for them to join" to "share your invite code"
            else "Finding you both" to "turn on location sharing in the app"
        }
    }
}
