package com.athulsn10.glancekit

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.functions.FirebaseFunctionsException
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Error reports for People. Each failure is written to Firestore logs/ (no names, places or
 * codes), so problems on real phones can be read with the "Backend logs" workflow.
 * Reports made before sign-in are kept on the phone and sent later.
 */
object PeopleLog {
    private const val K_PENDING = "people_log_pending"

    fun e(c: Context, where: String, t: Throwable, detail: String? = null) {
        Log.w("GlanceKit", "People: $where failed", t)
        if (!PeopleRepo.configured) return
        val entry = JSONObject()
            .put("where", where.take(60))
            .put("type", t.javaClass.simpleName)
            .put("code", code(t) ?: "")
            .put("message", (t.message ?: "").take(600))
            .put("detail", (detail ?: t.stackTrace.take(6).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }).take(1500))
        // The same failure repeating (e.g. on every sync) is reported once an hour.
        val key = "people_log_last_" + (where + t.javaClass.simpleName + (t.message ?: "")).hashCode()
        val now = System.currentTimeMillis()
        if (now - Store.prefs(c).getLong(key, 0) < 3_600_000) return
        Store.prefs(c).edit().putLong(key, now).apply()
        val uid = PeopleRepo.uidNow(c)
        if (uid == null) { keep(c, entry); return }
        flush(c)
        write(uid, entry)
    }

    /** A note that something worked (e.g. a hug arrived), to follow a problem end to end. */
    fun info(c: Context, where: String, detail: String) {
        if (!PeopleRepo.configured) return
        val uid = PeopleRepo.uidNow(c) ?: return
        write(uid, JSONObject().put("where", where.take(60)).put("type", "info").put("code", "")
            .put("message", "ok").put("detail", detail.take(1500)))
    }

    /** Sends reports that were kept while signed out. */
    fun flush(c: Context) {
        val uid = PeopleRepo.uidNow(c) ?: return
        val p = Store.prefs(c)
        val raw = p.getString(K_PENDING, null) ?: return
        p.edit().remove(K_PENDING).apply()
        runCatching { val a = JSONArray(raw); for (i in 0 until a.length()) write(uid, a.getJSONObject(i)) }
    }

    private fun keep(c: Context, entry: JSONObject, now: Boolean = false) {
        val p = Store.prefs(c)
        val a = runCatching { JSONArray(p.getString(K_PENDING, "[]")) }.getOrDefault(JSONArray())
        if (a.length() >= 10) return
        a.put(entry)
        val e = p.edit().putString(K_PENDING, a.toString())
        if (now) e.commit() else e.apply()
    }

    private fun stack(t: Throwable): String =
        generateSequence(t) { it.cause }.take(3).joinToString(" | caused by ") { x ->
            "${x.javaClass.simpleName}: ${x.message ?: ""} @ " +
                x.stackTrace.take(8).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        }

    private const val K_EXIT_SEEN = "people_log_exit_seen"

    /**
     * Crashes kill the app before a report can be sent, so the crash is saved on the phone at
     * once and sent on the next start (see [flush]). No personal data: only where in the code.
     */
    fun installCrashHandler(c: Context) {
        val app = c.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                keep(app, JSONObject()
                    .put("where", "crash on ${thread.name}".take(60))
                    .put("type", e.javaClass.simpleName)
                    .put("code", "CRASH")
                    .put("message", (e.message ?: "").take(600))
                    .put("detail", stack(e).take(1500)), now = true)
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /**
     * Asks Android why the app last stopped. Picks up what the crash handler can't see: freezes
     * ("app isn't responding") and native crashes. Runs off the main thread.
     */
    fun reportExits(c: Context) {
        val app = c.applicationContext
        Thread {
            runCatching {
                val am = app.getSystemService(android.app.ActivityManager::class.java) ?: return@runCatching
                val p = Store.prefs(app)
                val seen = p.getLong(K_EXIT_SEEN, 0L)
                var newest = seen
                for (x in am.getHistoricalProcessExitReasons(app.packageName, 0, 5)) {
                    if (x.timestamp <= seen) continue
                    newest = maxOf(newest, x.timestamp)
                    val kind = when (x.reason) {
                        android.app.ApplicationExitInfo.REASON_ANR -> "ANR"
                        android.app.ApplicationExitInfo.REASON_CRASH -> "CRASH"
                        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
                        else -> null
                    } ?: continue
                    // A freeze comes with thread dumps: keep the main thread's part, where it hung.
                    val trace = if (kind != "CRASH") runCatching {
                        x.traceInputStream?.bufferedReader()?.use { r -> r.readText() }
                    }.getOrNull() else null
                    val main = trace?.let { t ->
                        val i = t.indexOf("\"main\"")
                        if (i >= 0) t.substring(i).lineSequence().take(25).map { it.trim() }.joinToString(" < ") else t.take(1500)
                    }
                    keep(app, JSONObject()
                        .put("where", "app exit (${if (x.importance <= 100) "on screen" else "in background"})")
                        .put("type", kind)
                        .put("code", kind)
                        .put("message", (x.description ?: kind).take(600))
                        .put("detail", (main ?: "").take(1500)))
                }
                if (newest > seen) p.edit().putLong(K_EXIT_SEEN, newest).apply()
                flush(app)
            }
        }.start()
    }

    private fun write(uid: String, e: JSONObject) {
        val data = hashMapOf<String, Any?>(
            "source" to "app", "uid" to uid,
            "where" to e.optString("where"), "type" to e.optString("type"), "code" to e.optString("code"),
            "message" to e.optString("message"), "detail" to e.optString("detail"),
            "app" to BuildConfig.VERSION_NAME, "android" to Build.VERSION.SDK_INT, "device" to Build.MODEL,
            "at" to FieldValue.serverTimestamp(),
        )
        // Firestore queues this while offline and sends it when the phone is back online.
        runCatching { FirebaseFirestore.getInstance().collection("logs").add(data) }
    }

    private fun code(t: Throwable): String? = when (t) {
        is FirebaseFunctionsException -> t.code.name
        is FirebaseFirestoreException -> t.code.name
        else -> null
    }

    /** A message for a toast that says what actually went wrong. */
    fun friendly(t: Throwable): String {
        val root = generateSequence(t) { it.cause }.last()
        return when {
            t is FirebaseFunctionsException -> when (t.code) {
                FirebaseFunctionsException.Code.UNAVAILABLE, FirebaseFunctionsException.Code.DEADLINE_EXCEEDED ->
                    "Couldn't reach the cloud (${t.code.name.lowercase()}). Check your internet and try again."
                FirebaseFunctionsException.Code.INTERNAL, FirebaseFunctionsException.Code.UNKNOWN ->
                    "Something went wrong on our side. It's been logged."
                else -> t.message ?: t.code.name
            }
            t is FirebaseNetworkException || root is IOException -> "No internet connection. Try again when you're online."
            t is kotlinx.coroutines.TimeoutCancellationException -> "That took too long. Check your internet and try again."
            else -> t.message?.takeIf { it.length < 140 } ?: "Something went wrong (${t.javaClass.simpleName})."
        }
    }
}
