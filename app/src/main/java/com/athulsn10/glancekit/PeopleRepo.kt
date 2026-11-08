package com.athulsn10.glancekit

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.roundToLong

/**
 * Everything People talks to: Firebase (anonymous sign-in, Firestore, Cloud Functions, push) and a
 * small on-phone cache so the widget can draw without the network.
 *
 * Firestore layout (see firebase/firestore.rules):
 *   groups/{gid}                 circle, members[], lastFrom, lastAction, lastAt   (written by functions)
 *   groups/{gid}/members/{uid}   name, avatar, lat, lon, at, paused, tz, atHome, mood, moodUntil (each person writes their own)
 *   tokens/{uid}                 push token (write-only for the owner, read by functions)
 */
object PeopleRepo {

    private const val REGION = "asia-south1"
    private const val K_GROUPS = "people_groups"
    private const val K_NAME = "people_name"
    private const val K_AVATAR = "people_avatar"
    private const val K_PAUSED = "people_paused"
    private const val K_INTERVAL = "people_interval"
    private const val K_WIDGET_GROUP = "people_widget_group"
    private const val K_HOME_LAT = "people_home_lat"
    private const val K_HOME_LON = "people_home_lon"
    private const val K_MY_LAT = "people_my_lat"
    private const val K_MY_LON = "people_my_lon"
    private const val K_MY_AT = "people_my_at"
    private const val K_MOOD = "people_mood"
    private const val K_MOOD_UNTIL = "people_mood_until"

    /** True when this build has the Firebase settings (app/google-services.json at build time). */
    val configured get() = BuildConfig.FIREBASE_APP_ID.isNotEmpty()

    fun init(c: Context): Boolean {
        if (!configured) return false
        if (FirebaseApp.getApps(c).isEmpty()) {
            FirebaseApp.initializeApp(
                c.applicationContext,
                FirebaseOptions.Builder()
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                    .setApiKey(BuildConfig.FIREBASE_API_KEY)
                    .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                    .build(),
            )
        }
        return true
    }

    private fun db() = FirebaseFirestore.getInstance()
    private fun fn() = FirebaseFunctions.getInstance(REGION)

    /** Signs in anonymously if needed. Returns the user id, or null when offline / not set up. */
    suspend fun uid(c: Context): String? {
        if (!init(c)) return null
        val auth = FirebaseAuth.getInstance()
        auth.currentUser?.let { return it.uid }
        return runCatching { auth.signInAnonymously().await().user?.uid }.getOrNull()
    }

    fun uidNow(c: Context): String? = if (init(c)) FirebaseAuth.getInstance().currentUser?.uid else null

    // ---------------- My profile (kept on the phone, copied to each group) ----------------

    fun name(c: Context) = Store.prefs(c).getString(K_NAME, "") ?: ""
    fun setName(c: Context, n: String) = Store.prefs(c).edit().putString(K_NAME, n.trim().take(24)).apply()
    fun avatar(c: Context) = Avatar.fromJson(Store.prefs(c).getString(K_AVATAR, null)?.let { runCatching { JSONObject(it) }.getOrNull() })
    fun hasAvatar(c: Context) = Store.prefs(c).contains(K_AVATAR)
    fun setAvatar(c: Context, a: Avatar) = Store.prefs(c).edit().putString(K_AVATAR, a.toJson().toString()).apply()
    fun paused(c: Context) = Store.prefs(c).getBoolean(K_PAUSED, false)
    fun setPaused(c: Context, p: Boolean) = Store.prefs(c).edit().putBoolean(K_PAUSED, p).apply()
    /** Minutes between location updates: 15, 30 or 60. */
    fun interval(c: Context) = Store.prefs(c).getInt(K_INTERVAL, 15)
    fun setInterval(c: Context, m: Int) = Store.prefs(c).edit().putInt(K_INTERVAL, m).apply()
    fun mood(c: Context): Mood? = if (System.currentTimeMillis() < Store.prefs(c).getLong(K_MOOD_UNTIL, 0)) Mood.of(Store.prefs(c).getString(K_MOOD, null)) else null
    /** Set a mood until midnight (null clears it). */
    fun setMood(c: Context, m: Mood?) {
        val midnight = java.time.LocalDate.now().plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        Store.prefs(c).edit().putString(K_MOOD, m?.name).putLong(K_MOOD_UNTIL, if (m == null) 0 else midnight).apply()
    }
    fun widgetGroup(c: Context) = Store.prefs(c).getString(K_WIDGET_GROUP, null)
    fun setWidgetGroup(c: Context, id: String) = Store.prefs(c).edit().putString(K_WIDGET_GROUP, id).apply()

    /** "Home" stays on this phone; only a yes/no "at home" is shared (family). */
    fun setHomeHere(c: Context): Boolean {
        val p = Store.prefs(c)
        if (!p.contains(K_MY_LAT)) return false
        p.edit().putFloat(K_HOME_LAT, p.getFloat(K_MY_LAT, 0f)).putFloat(K_HOME_LON, p.getFloat(K_MY_LON, 0f)).apply()
        return true
    }
    fun hasHome(c: Context) = Store.prefs(c).contains(K_HOME_LAT)
    fun clearHome(c: Context) = Store.prefs(c).edit().remove(K_HOME_LAT).remove(K_HOME_LON).apply()

    /** My last known place as a Person (for distances), from the phone. */
    fun me(c: Context): Person {
        val p = Store.prefs(c)
        val lat = if (p.contains(K_MY_LAT)) p.getFloat(K_MY_LAT, 0f).toDouble() else null
        val lon = if (p.contains(K_MY_LON)) p.getFloat(K_MY_LON, 0f).toDouble() else null
        return Person(uidNow(c) ?: "me", name(c).ifEmpty { "You" }, avatar(c), lat, lon, p.getLong(K_MY_AT, 0),
            paused(c), TimeZone.getDefault().id, atHome(c, lat, lon), mood(c), p.getLong(K_MOOD_UNTIL, 0))
    }

    private fun atHome(c: Context, lat: Double?, lon: Double?): Boolean {
        val p = Store.prefs(c)
        if (lat == null || lon == null || !p.contains(K_HOME_LAT)) return false
        return People.distance(lat, lon, p.getFloat(K_HOME_LAT, 0f).toDouble(), p.getFloat(K_HOME_LON, 0f).toDouble()) < 150
    }

    // ---------------- Location ----------------

    fun hasFine(c: Context) = c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun hasBackground(c: Context) = c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private val locExec = Executors.newSingleThreadExecutor()

    /** One fresh location (fine if allowed, otherwise approximate), or null. */
    suspend fun locate(c: Context): Location? {
        if (!hasFine(c) && !Place.granted(c)) return null
        val lm = c.getSystemService(LocationManager::class.java) ?: return null
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) } ?: return null
        return withTimeoutOrNull(30_000) {
            suspendCancellableCoroutine { cont ->
                val cancel = CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                try {
                    lm.getCurrentLocation(provider, cancel, locExec) { loc -> if (cont.isActive) cont.resume(loc) }
                } catch (e: SecurityException) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    private fun round4(v: Double) = (v * 10_000).roundToLong() / 10_000.0

    // ---------------- Cloud ----------------

    /** Invite code for a new group (or to add someone to an existing family group). */
    suspend fun createInvite(c: Context, circle: Circle, groupId: String? = null): Pair<String, String> {
        uid(c) ?: error("Couldn't connect. Check your internet and try again.")
        val res = fn().getHttpsCallable("createInvite")
            .call(mapOf("circle" to circle.key, "groupId" to groupId)).await()
        @Suppress("UNCHECKED_CAST")
        val m = res.data as Map<String, Any?>
        val gid = m["groupId"] as String
        pushProfile(c, listOf(gid))
        return (m["code"] as String) to gid
    }

    /** Join someone's group with their code. Returns the group id. */
    suspend fun join(c: Context, code: String): String {
        uid(c) ?: error("Couldn't connect. Check your internet and try again.")
        val res = fn().getHttpsCallable("joinWithCode").call(mapOf("code" to code.uppercase().filter { it.isLetterOrDigit() })).await()
        @Suppress("UNCHECKED_CAST")
        val gid = (res.data as Map<String, Any?>)["groupId"] as String
        pushProfile(c, listOf(gid))
        return gid
    }

    suspend fun send(c: Context, groupId: String, action: String) {
        uid(c) ?: error("Couldn't connect.")
        fn().getHttpsCallable("sendAction").call(mapOf("groupId" to groupId, "action" to action)).await()
    }

    suspend fun leave(c: Context, groupId: String) {
        uid(c) ?: error("Couldn't connect.")
        fn().getHttpsCallable("leaveGroup").call(mapOf("groupId" to groupId)).await()
        saveGroups(c, cached(c).filter { it.group.id != groupId })
    }

    /** Write my profile (and, if given, a new location) to each of my groups. */
    suspend fun pushProfile(c: Context, groupIds: List<String>? = null, loc: Location? = null) {
        val uid = uid(c) ?: return
        val p = Store.prefs(c)
        if (loc != null) {
            p.edit().putFloat(K_MY_LAT, round4(loc.latitude).toFloat()).putFloat(K_MY_LON, round4(loc.longitude).toFloat())
                .putLong(K_MY_AT, loc.time.takeIf { it > 0 } ?: System.currentTimeMillis()).apply()
        }
        val me = me(c)
        val data = HashMap<String, Any?>()
        data["name"] = me.name
        data["avatar"] = me.avatar.toMap()
        data["paused"] = me.paused
        data["tz"] = me.tz
        data["atHome"] = me.atHome
        data["mood"] = me.mood?.name
        data["moodUntil"] = me.moodUntil
        if (!me.paused && me.lat != null && me.lon != null) {
            data["lat"] = me.lat; data["lon"] = me.lon; data["at"] = me.at
        }
        val ids = groupIds ?: cached(c).map { it.group.id }
        for (gid in ids) {
            runCatching { db().collection("groups").document(gid).collection("members").document(uid).set(data, SetOptions.merge()).await() }
        }
        pushToken(c)
    }

    suspend fun pushToken(c: Context, token: String? = null) {
        val uid = uid(c) ?: return
        val t = token ?: runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull() ?: return
        runCatching {
            db().collection("tokens").document(uid).set(mapOf("token" to t, "updatedAt" to FieldValue.serverTimestamp())).await()
        }
    }

    /** Download my groups and everyone in them, and keep a copy on the phone. */
    suspend fun refresh(c: Context): List<GroupData> {
        val uid = uid(c) ?: return cached(c)
        val snap = runCatching { db().collection("groups").whereArrayContains("members", uid).get().await() }.getOrNull() ?: return cached(c)
        val out = ArrayList<GroupData>()
        for (doc in snap.documents) {
            @Suppress("UNCHECKED_CAST")
            val members = (doc.get("members") as? List<String>) ?: emptyList()
            val g = Group(
                doc.id, Circle.of(doc.getString("circle")), members,
                doc.getString("lastFrom"), doc.getString("lastAction"),
                doc.getTimestamp("lastAt")?.toDate()?.time ?: 0L,
            )
            val ms = runCatching { doc.reference.collection("members").get().await() }.getOrNull()
            val people = ms?.documents?.filter { it.id != uid && it.id in members }?.map { d ->
                @Suppress("UNCHECKED_CAST")
                Person(
                    d.id, d.getString("name")?.ifBlank { null } ?: "Them",
                    Avatar.fromMap(d.get("avatar") as? Map<String, Any?>),
                    d.getDouble("lat"), d.getDouble("lon"), d.getLong("at") ?: 0L,
                    d.getBoolean("paused") ?: false, d.getString("tz"), d.getBoolean("atHome") ?: false,
                    Mood.of(d.getString("mood")), d.getLong("moodUntil") ?: 0L,
                )
            } ?: emptyList()
            out.add(GroupData(g, people))
        }
        saveGroups(c, out)
        return out
    }

    // ---------------- On-phone cache ----------------

    data class GroupData(val group: Group, val others: List<Person>)

    fun cached(c: Context): List<GroupData> {
        val raw = Store.prefs(c).getString(K_GROUPS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val ms = o.optJSONArray("members") ?: JSONArray()
                val g = Group(o.getString("id"), Circle.of(o.optString("circle")),
                    (0 until ms.length()).map { ms.getString(it) },
                    o.optString("lastFrom").ifEmpty { null }, o.optString("lastAction").ifEmpty { null }, o.optLong("lastAt"))
                val ps = o.optJSONArray("people") ?: JSONArray()
                GroupData(g, (0 until ps.length()).map { j ->
                    val q = ps.getJSONObject(j)
                    Person(q.getString("uid"), q.optString("name", "Them"), Avatar.fromJson(q.optJSONObject("avatar")),
                        if (q.has("lat")) q.getDouble("lat") else null, if (q.has("lon")) q.getDouble("lon") else null,
                        q.optLong("at"), q.optBoolean("paused"), q.optString("tz").ifEmpty { null }, q.optBoolean("atHome"),
                        Mood.of(q.optString("mood")), q.optLong("moodUntil"))
                })
            }
        }.getOrDefault(emptyList())
    }

    fun saveGroups(c: Context, list: List<GroupData>) {
        val arr = JSONArray()
        for (gd in list) {
            val g = gd.group
            val o = JSONObject().put("id", g.id).put("circle", g.circle.key).put("members", JSONArray(g.members))
                .put("lastFrom", g.lastFrom ?: "").put("lastAction", g.lastAction ?: "").put("lastAt", g.lastAt)
            val ps = JSONArray()
            for (p in gd.others) {
                val q = JSONObject().put("uid", p.uid).put("name", p.name).put("avatar", p.avatar.toJson())
                    .put("at", p.at).put("paused", p.paused).put("tz", p.tz ?: "").put("atHome", p.atHome)
                    .put("mood", p.mood?.name ?: "").put("moodUntil", p.moodUntil)
                if (p.lat != null && p.lon != null) q.put("lat", p.lat).put("lon", p.lon)
                ps.put(q)
            }
            o.put("people", ps)
            arr.put(o)
        }
        Store.prefs(c).edit().putString(K_GROUPS, arr.toString()).apply()
    }

    /** Note an action that just arrived (from a push), so the widget can show it right away. */
    fun noteReceived(c: Context, groupId: String, from: String, action: String) {
        val list = cached(c).map {
            if (it.group.id == groupId) it.copy(group = it.group.copy(lastFrom = from, lastAction = action, lastAt = System.currentTimeMillis())) else it
        }
        saveGroups(c, list)
    }

    /** The group the widget shows: the one picked in the app, or the first. */
    fun widgetData(c: Context): GroupData? {
        val all = cached(c)
        return all.firstOrNull { it.group.id == widgetGroup(c) } ?: all.firstOrNull()
    }
}
