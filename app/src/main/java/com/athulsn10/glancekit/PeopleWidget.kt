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
        runCatching { PeopleMessaging.channels(this) }
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
        // No buzz here: only the people you send it to feel it.
        // Sent from a short background job: the cloud can take a while to wake, longer than a
        // widget tap is allowed to run for.
        PeopleSendWork.enqueue(context, gid, action)
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
                .onFailure { e ->
                    android.util.Log.w("GlanceKit", "People widget update failed", e)
                    // Never leave the widget blank or broken: show a plain version with the reason.
                    runCatching { mgr.updateAppWidget(id, fallback(c, e)) }
                }
        }
    }

    /** A picture-free widget, used if the full one can't be built. Shows why, to help fix it. */
    private fun fallback(c: Context, e: Throwable): RemoteViews {
        val rv = RemoteViews(c.packageName, R.layout.people_widget)
        val gd = PeopleRepo.widgetData(c)
        rv.setTextViewText(R.id.people_title, gd?.group?.circle?.title ?: "PEOPLE")
        rv.setTextViewText(R.id.people_big, gd?.others?.firstOrNull()?.name ?: "People")
        rv.setTextViewText(R.id.people_small, "Couldn't draw: ${e.javaClass.simpleName} ${e.message ?: ""}".take(90))
        rv.setTextViewText(R.id.people_btn, "Open")
        rv.setInt(R.id.people_btn, "setBackgroundResource", R.drawable.pill_grey)
        val open = Views.openApp(c, "people", 170)
        rv.setOnClickPendingIntent(R.id.people_btn, open)
        rv.setOnClickPendingIntent(R.id.people_text, open)
        return rv
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
        PeopleLog.flush(c)
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

        /** Vibrates as a message from someone, which Android allows even with the app in the background. */
        fun canVibrate(c: Context) = c.getSystemService(VibratorManager::class.java)?.defaultVibrator?.hasVibrator() == true

        fun ringer(c: Context) = when (c.getSystemService(android.media.AudioManager::class.java)?.ringerMode) {
            android.media.AudioManager.RINGER_MODE_SILENT -> "silent"
            android.media.AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            android.media.AudioManager.RINGER_MODE_NORMAL -> "normal"
            else -> "?"
        }

        fun buzz(c: Context, pattern: LongArray) {
            val v: Vibrator = c.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
            val attrs = android.os.VibrationAttributes.Builder()
                .setUsage(android.os.VibrationAttributes.USAGE_COMMUNICATION_REQUEST).build()
            runCatching { v.vibrate(VibrationEffect.createWaveform(pattern, -1), attrs) }
        }
    }
}

/** Sends a hug, poke etc. from the widget button, then says how it went. */
class PeopleSendWork(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        val gid = inputData.getString("group") ?: return Result.success()
        val action = inputData.getString("action") ?: return Result.success()
        val gd = PeopleRepo.cached(c).firstOrNull { it.group.id == gid }
        val label = gd?.group?.circle?.action(action)?.label ?: "Hi"
        val res = withTimeoutOrNull(45_000) { runCatching { PeopleRepo.send(c, gid, action) } }
        val msg = when {
            res == null -> {
                PeopleLog.e(c, "widget send $action", IllegalStateException("Timed out after 45 s"))
                "That took too long. Check your internet and try again."
            }
            res.isSuccess -> res.getOrThrow().message(label, gd?.others?.firstOrNull()?.name ?: "their")
            else -> { val e = res.exceptionOrNull()!!; PeopleLog.e(c, "widget send $action", e); PeopleLog.friendly(e) }
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post { Toast.makeText(c, msg, Toast.LENGTH_SHORT).show() }
        return Result.success()
    }

    companion object {
        fun enqueue(c: Context, gid: String, action: String) {
            val req = OneTimeWorkRequestBuilder<PeopleSendWork>()
                .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(androidx.work.workDataOf("group" to gid, "action" to action))
                .build()
            WorkManager.getInstance(c).enqueue(req)
        }
    }
}

/** Receives hugs, pokes and "home safe" pushes: buzz in the action's pattern, notify, redraw. */
class PeopleMessaging : FirebaseMessagingService() {

    companion object {
        const val CHANNEL = "pa3_couple_hug"

        fun channelId(circle: Circle, action: PeopleAction) = "pa3_${circle.key}_${action.key}"

        /**
         * One channel per action, each with its own vibration pattern. Made when the app starts, so
         * the cloud's notifications can use them even while the app is closed.
         */
        fun channels(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) != null) return
            // Tidy away channels from older builds.
            nm.notificationChannels.filter { it.id.startsWith("people_") && it.id != "people_updates" }
                .forEach { runCatching { nm.deleteNotificationChannel(it.id) } }
            for (circle in Circle.entries) for (a in circle.actions) {
                nm.createNotificationChannel(NotificationChannel(channelId(circle, a), "${circle.label}: ${a.label}", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "When someone sends you a ${a.label.lowercase()}. It buzzes in its own pattern."
                    enableVibration(true)
                    vibrationPattern = a.pattern
                    setShowBadge(false)
                })
            }
        }

        /** True when notifications from this app (and this channel, if it exists yet) can show and buzz. */
        fun canAlert(c: Context, channel: String? = null): Boolean {
            val nm = c.getSystemService(NotificationManager::class.java) ?: return false
            if (!nm.areNotificationsEnabled()) return false
            val ch = nm.getNotificationChannel(channel ?: CHANNEL) ?: return true
            return ch.importance != NotificationManager.IMPORTANCE_NONE
        }
    }

    override fun onNewToken(token: String) {
        runBlocking { withTimeoutOrNull(10_000) { PeopleRepo.pushToken(this@PeopleMessaging, token) } }
    }

    override fun onMessageReceived(msg: RemoteMessage) {
        val d = msg.data
        val gid = d["groupId"] ?: return
        PeopleRepo.noteAlertArrived(this)
        if (d["type"] == "sync") {
            // Someone joined or left: refetch now so the widget and app don't wait for the next sync.
            runBlocking { withTimeoutOrNull(8_000) { runCatching { PeopleRepo.refresh(this@PeopleMessaging) } } }
            PeopleViews.update(this)
            val who = d["fromName"]?.ifBlank { null } ?: "Someone"
            when (d["event"]) {
                "left" -> update("$who left your group", "You're no longer sharing with them.")
                "joined" -> update("$who joined", "You can see each other now.")
            }
            return
        }
        // "from" is reserved by Firebase, so the sender comes as "sender".
        val from = d["sender"] ?: d["from"] ?: return
        val circle = Circle.of(d["circle"])
        val action = circle.action(d["action"]) ?: return
        val name = d["fromName"]?.ifBlank { null } ?: "Someone"
        // Arrives here when the app is open (otherwise Android shows the notification itself, from
        // the cloud's notification part). Same channel either way, so it vibrates the same.
        val shown = canAlert(this, channelId(circle, action))
        if (shown) notify(circle, action, name, gid) else PeopleSync.buzz(this, action.pattern)
        PeopleLog.info(this, "received ${action.key}", "app open, priority ${msg.priority}/${msg.originalPriority}, notifications $shown, " +
            "vibrator ${PeopleSync.canVibrate(this)}, ringer ${PeopleSync.ringer(this)}, ${System.currentTimeMillis() - msg.sentTime} ms after sending")
        if (d["test"] == "1") return
        PeopleRepo.noteReceived(this, gid, from, action.key)
        PeopleViews.update(this)
        PeopleSync.now(this) // fetch their latest place too
    }

    /** A quiet note when someone joins or leaves. */
    private fun update(title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val id = "people_updates"
        if (nm.getNotificationChannel(id) == null) {
            nm.createNotificationChannel(NotificationChannel(id, "People: joins and leaves", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When someone joins or leaves one of your groups"
                setShowBadge(false)
            })
        }
        val open = PendingIntent.getActivity(this, 173,
            Intent(this, MainActivity::class.java).putExtra("tab", "people").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = android.app.Notification.Builder(this, id)
            .setSmallIcon(R.drawable.ic_w_people)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(id.hashCode(), n) }
    }

    private fun notify(circle: Circle, action: PeopleAction, name: String, gid: String) {
        val nm = getSystemService(NotificationManager::class.java)
        channels(this)
        val open = PendingIntent.getActivity(this, 172,
            Intent(this, MainActivity::class.java).putExtra("tab", "people").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val sym = if (circle == Circle.FRIENDS) "★" else "♥"
        val n = android.app.Notification.Builder(this, channelId(circle, action))
            .setSmallIcon(R.drawable.ic_w_people)
            .setContentTitle("$sym $name ${action.past}")
            .setContentText("Tap to send one back")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        // Same tag as the cloud's notification, so the two never stack up.
        runCatching { nm.notify("people_$gid", 0, n) }
    }
}
