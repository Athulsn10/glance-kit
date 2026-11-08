package com.athulsn10.glancekit

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.RemoteViews
import android.widget.Toast
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/** Starts Firebase early (it's set up by hand, without the Google Services plugin). */
class GlanceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { PeopleRepo.init(this) }
    }
}

/** The People widget. One group per phone is shown; pick which one in the app. */
class PeopleWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        inBackground { PeopleViews.update(context) }
        PeopleSync.schedule(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        inBackground { PeopleViews.update(context) }
    }

    override fun onDisabled(context: Context) {
        if (PeopleRepo.cached(context).isEmpty()) PeopleSync.cancel(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_SEND) return
        val gid = intent.getStringExtra(EXTRA_GROUP) ?: return
        val action = intent.getStringExtra(EXTRA_ACTION) ?: return
        PeopleSync.buzz(context, longArrayOf(0, 30))
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val ok = withTimeoutOrNull(8_000) { runCatching { PeopleRepo.send(context, gid, action) }.isSuccess } ?: false
            if (!ok) {
                CoroutineScope(Dispatchers.Main).launch {
                    Toast.makeText(context, "Couldn't send. Check your internet.", Toast.LENGTH_SHORT).show()
                }
            }
            pending.finish()
        }
    }

    companion object {
        const val ACTION_SEND = "com.athulsn10.glancekit.PEOPLE_SEND"
        const val EXTRA_GROUP = "group"
        const val EXTRA_ACTION = "action"
    }
}

object PeopleViews {

    fun ids(c: Context): IntArray =
        AppWidgetManager.getInstance(c).getAppWidgetIds(ComponentName(c, PeopleWidget::class.java))

    fun update(c: Context) {
        val mgr = AppWidgetManager.getInstance(c)
        for (id in ids(c)) {
            val o = mgr.getAppWidgetOptions(id)
            val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 280).coerceAtLeast(180)
            val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 180).coerceAtLeast(140)
            runCatching { mgr.updateAppWidget(id, views(c, w.toFloat(), h.toFloat())) }
                .onFailure { android.util.Log.w("GlanceKit", "People widget update failed", it) }
        }
    }

    fun views(c: Context, wDp: Float, hDp: Float): RemoteViews {
        val rv = RemoteViews(c.packageName, R.layout.people_widget)
        val data = PeopleRepo.widgetData(c)
        val me = PeopleRepo.me(c)
        val g = data?.group ?: Group("none", Circle.COUPLE, emptyList(), null, null, 0)
        val others = data?.others ?: emptyList()
        val circle = g.circle
        val other = others.firstOrNull()
        val scene = if (data == null) Scene.WAITING else if (circle == Circle.FAMILY) Scene.NEAR else People.scene(g, me, other)

        rv.setTextViewText(R.id.people_title, if (data == null) "PEOPLE" else circle.title)
        val status = if (data == null) "" else if (circle == Circle.FAMILY) "${others.size + 1} MEMBERS" else People.status(circle, scene, g)
        rv.setTextViewText(R.id.people_status, status)
        rv.setTextColor(R.id.people_status, if (scene == Scene.TOGETHER || scene == Scene.RECEIVED) circle.accent else 0xFF969696.toInt())

        // Scene frames (the launcher flips them; nothing runs in the app).
        rv.removeAllViews(R.id.people_frames)
        val sceneH = (hDp - 104).coerceAtLeast(70f)
        val frames = PeopleArt.frames(c, wDp - 32, sceneH, g, me, others, if (scene == Scene.WAITING) 2 else 3)
        for (b in frames) {
            val f = RemoteViews(c.packageName, R.layout.people_frame)
            f.setImageViewBitmap(R.id.people_frame, b)
            rv.addView(R.id.people_frames, f)
        }

        // Words under the scene.
        val (big, small) = when {
            data == null && !PeopleRepo.configured -> "People" to "coming in a future update"
            data == null -> "Pair with someone" to "open Glance Kit · People"
            circle == Circle.FAMILY -> familyLines(g, me, others)
            else -> People.lines(circle, scene, g, me, other)
        }
        rv.setTextViewText(R.id.people_big, big)
        rv.setTextViewText(R.id.people_small, small)

        // Action button: the circle's first action (or Home safe for family).
        val open = Views.openApp(c, "people", 170)
        rv.setOnClickPendingIntent(R.id.people_frames, open)
        rv.setOnClickPendingIntent(R.id.people_text, open)
        if (data == null || (others.isEmpty())) {
            rv.setTextViewText(R.id.people_btn, if (data == null) "Open" else "Invite")
            rv.setInt(R.id.people_btn, "setBackgroundResource", R.drawable.pill_grey)
            rv.setOnClickPendingIntent(R.id.people_btn, open)
        } else {
            val act = circle.actions.first()
            val sym = when (circle) { Circle.FRIENDS -> "★"; else -> "♥" }
            rv.setTextViewText(R.id.people_btn, "$sym  ${act.label}")
            rv.setInt(R.id.people_btn, "setBackgroundResource",
                when (circle) { Circle.COUPLE -> R.drawable.pill_pink; Circle.FRIENDS -> R.drawable.pill_blue; Circle.FAMILY -> R.drawable.pill_amber })
            val i = Intent(c, PeopleWidget::class.java).setAction(PeopleWidget.ACTION_SEND)
                .putExtra(PeopleWidget.EXTRA_GROUP, g.id).putExtra(PeopleWidget.EXTRA_ACTION, act.key)
            rv.setOnClickPendingIntent(R.id.people_btn, PendingIntent.getBroadcast(c, 171, i,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        return rv
    }

    private fun familyLines(g: Group, me: Person, others: List<Person>): Pair<String, String> {
        val now = System.currentTimeMillis()
        if (g.lastFrom != null && g.lastFrom != me.uid && now - g.lastAt < People.RECEIVED_MS) {
            val who = others.firstOrNull { it.uid == g.lastFrom }?.name ?: "Someone"
            return "$who ${g.circle.action(g.lastAction)?.past ?: "said hi"}" to "${People.ago(g.lastAt)} · tap to send love"
        }
        if (others.isEmpty()) return "Waiting for family" to "share your invite code"
        val ds = others.mapNotNull { People.distance(me, it) }
        val home = others.count { it.atHome && !it.paused }
        val big = if (home == others.size) "Everyone's home" else "Everyone's okay"
        val small = if (ds.isEmpty()) "waiting for locations" else "nearest ${People.fmtDistance(ds.min())} · farthest ${People.fmtDistance(ds.max())}"
        return big to small
    }
}

/**
 * Background sync, at the interval chosen in the app (15, 30 or 60 minutes): take one location,
 * share it with your groups, fetch theirs and redraw the widget. Nothing runs in between.
 */
class PeopleSync(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        if (!PeopleRepo.init(c)) return Result.success()
        val groups = PeopleRepo.refresh(c)
        if (groups.isEmpty()) { PeopleViews.update(c); return Result.success() }
        val loc = if (PeopleRepo.paused(c)) null else PeopleRepo.locate(c)
        PeopleRepo.pushProfile(c, groups.map { it.group.id }, loc)
        PeopleViews.update(c)
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "people_sync"
        private const val ONCE = "people_sync_now"

        private fun net() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(c: Context, replace: Boolean = false) {
            if (!PeopleRepo.configured) return
            val req = PeriodicWorkRequestBuilder<PeopleSync>(PeopleRepo.interval(c).toLong(), TimeUnit.MINUTES).setConstraints(net()).build()
            WorkManager.getInstance(c).enqueueUniquePeriodicWork(PERIODIC,
                if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun now(c: Context) {
            if (!PeopleRepo.configured) return
            WorkManager.getInstance(c).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<PeopleSync>().setConstraints(net()).build())
        }

        fun cancel(c: Context) {
            val wm = WorkManager.getInstance(c)
            wm.cancelUniqueWork(PERIODIC); wm.cancelUniqueWork(ONCE)
        }

        fun buzz(c: Context, pattern: LongArray) {
            val v: Vibrator = c.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
            runCatching { v.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
        }
    }
}

/** Receives hugs, pokes and "home safe" pushes: buzz in the action's pattern, notify, redraw. */
class PeopleMessaging : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        runBlocking { withTimeoutOrNull(10_000) { PeopleRepo.pushToken(this@PeopleMessaging, token) } }
    }

    override fun onMessageReceived(msg: RemoteMessage) {
        val d = msg.data
        val gid = d["groupId"] ?: return
        val from = d["from"] ?: return
        val circle = Circle.of(d["circle"])
        val action = circle.action(d["action"]) ?: return
        val name = d["fromName"]?.ifBlank { null } ?: "Someone"
        PeopleRepo.noteReceived(this, gid, from, action.key)
        notify(circle, action, name)
        PeopleViews.update(this)
        PeopleSync.now(this) // fetch their latest place too
    }

    private fun notify(circle: Circle, action: PeopleAction, name: String) {
        val nm = getSystemService(NotificationManager::class.java)
        // One channel per action, so each has its own vibration pattern.
        val id = "people_${circle.key}_${action.key}"
        if (nm.getNotificationChannel(id) == null) {
            nm.createNotificationChannel(NotificationChannel(id, "${circle.label}: ${action.label}", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When someone sends you a ${action.label.lowercase()}"
                enableVibration(true)
                vibrationPattern = action.pattern
                setShowBadge(false)
            })
        }
        val open = PendingIntent.getActivity(this, 172,
            Intent(this, MainActivity::class.java).putExtra("tab", "people").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val sym = if (circle == Circle.FRIENDS) "★" else "♥"
        val n = android.app.Notification.Builder(this, id)
            .setSmallIcon(R.drawable.ic_w_people)
            .setContentTitle("$sym $name ${action.past}")
            .setContentText("Tap to send one back")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(id.hashCode(), n) }
    }
}
