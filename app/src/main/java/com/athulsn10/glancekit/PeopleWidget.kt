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
import android.util.TypedValue
import android.view.View
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

/** The People widget. Shows one group; tap its title for the next one, or pick and order them in the app. */
class PeopleWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // The system asks after a restart, an app update or a new widget: draw it all.
        inBackground { PeopleViews.update(context, force = true) }
        PeopleSync.schedule(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        inBackground { PeopleViews.update(context, force = true) }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        PeopleViews.forget(context, ids)
    }

    override fun onDisabled(context: Context) {
        if (PeopleRepo.cached(context).isEmpty()) PeopleSync.cancel(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_NEXT) {
            // The title was tapped: show the next group.
            inBackground { PeopleRepo.nextWidgetGroup(context); PeopleViews.update(context) }
            return
        }
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
        const val ACTION_NEXT = "com.athulsn10.glancekit.PEOPLE_NEXT"
        const val EXTRA_GROUP = "group"
        const val EXTRA_ACTION = "action"
    }
}

object PeopleViews {

    fun ids(c: Context): IntArray =
        AppWidgetManager.getInstance(c).getAppWidgetIds(ComponentName(c, PeopleWidget::class.java))

    private const val FULL_EVERY_MS = 3 * 60 * 60 * 1000L
    private fun sigKey(id: Int) = "people_sig_$id"
    private fun sigAtKey(id: Int) = "people_sig_at_$id"

    /** Forget what was last drawn for widgets that were removed. */
    fun forget(c: Context, ids: IntArray) {
        val e = Store.prefs(c).edit()
        for (id in ids) e.remove(sigKey(id)).remove(sigAtKey(id))
        e.apply()
    }

    /**
     * Redraw every People widget. This is called a lot (any widget refresh, each minute while
     * charging...), and a full redraw restarts the launcher's animation, so unless [force] is
     * set or the picture really changed, only the words are updated.
     */
    @Synchronized
    fun update(c: Context, force: Boolean = false) {
        val mgr = AppWidgetManager.getInstance(c)
        val p = Store.prefs(c)
        for (id in ids(c)) {
            val o = mgr.getAppWidgetOptions(id)
            val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 280).coerceAtLeast(180).toFloat()
            val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 180).coerceAtLeast(140).toFloat()
            runCatching {
                val s = State(c)
                val sig = signature(c, s, w, h)
                val now = System.currentTimeMillis()
                val same = !force && p.getString(sigKey(id), null) == sig && now - p.getLong(sigAtKey(id), 0) in 0 until FULL_EVERY_MS
                if (same) {
                    // Words only: text and taps, never bitmaps, so the pictures keep animating.
                    val rv = RemoteViews(c.packageName, R.layout.people_widget)
                    words(c, rv, s)
                    mgr.partiallyUpdateAppWidget(id, rv)
                } else {
                    mgr.updateAppWidget(id, views(c, w, h))
                    p.edit().putString(sigKey(id), sig).putLong(sigAtKey(id), now).apply()
                }
            }.onFailure { e ->
                android.util.Log.w("GlanceKit", "People widget update failed", e)
                p.edit().remove(sigKey(id)).apply()
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

    /** What the widget shows right now. */
    private class State(c: Context) {
        val data = PeopleRepo.widgetData(c)
        val me = PeopleRepo.me(c)
        val g = data?.group ?: Group("none", Circle.COUPLE, emptyList(), null, null, 0)
        val others = data?.others ?: emptyList()
        val circle = g.circle
        val other = others.firstOrNull()
        val scene = if (data == null) Scene.WAITING else if (circle == Circle.FAMILY) Scene.NEAR else People.scene(g, me, other)
        val groups = PeopleRepo.cached(c).size
    }

    fun views(c: Context, wDp: Float, hDp: Float): RemoteViews {
        val rv = RemoteViews(c.packageName, R.layout.people_widget)
        val s = State(c)
        words(c, rv, s)
        pictures(c, rv, s, wDp, hDp)
        return rv
    }

    private fun sceneHeight(hDp: Float) = (hDp - 104).coerceAtLeast(70f)

    /**
     * A fingerprint of the picture part of the widget. While it stays the same, only the words
     * are updated, so the launcher's animation carries on instead of starting again (which made
     * the heart jump back or sit still every time any widget refreshed).
     */
    private fun signature(c: Context, s: State, wDp: Float, hDp: Float): String {
        val first = PeopleArt.frames(c, wDp - 32, sceneHeight(hDp), s.g, s.me, s.others, 1).first()
        val buf = java.nio.ByteBuffer.allocate(first.byteCount)
        first.copyPixelsToBuffer(buf)
        return "${s.g.id}/${s.scene}/${wDp.toInt()}x${hDp.toInt()}/${java.util.Arrays.hashCode(buf.array())}"
    }

    /** The scene: avatar frames and the travelling heart / plane. */
    private fun pictures(c: Context, rv: RemoteViews, s: State, wDp: Float, hDp: Float) {
        val g = s.g
        // Scene frames (the launcher cross-fades them; nothing runs in the app).
        rv.removeAllViews(R.id.people_frames)
        val sceneH = sceneHeight(hDp)
        val frames = PeopleArt.frames(c, wDp - 32, sceneH, g, s.me, s.others)
        for (b in frames) {
            val f = RemoteViews(c.packageName, R.layout.people_frame)
            f.setImageViewBitmap(R.id.people_frame, b)
            rv.addView(R.id.people_frames, f)
        }

        // The heart / plane between two people who are apart: its own layer, moved by the launcher.
        rv.removeAllViews(R.id.people_fly_go)
        rv.removeAllViews(R.id.people_fly_sway)
        val fly = if (s.data == null) null else PeopleArt.fly(c, wDp - 32, sceneH, g, s.me, s.others)
        rv.setViewVisibility(R.id.people_fly_go, if (fly != null && !fly.sway) View.VISIBLE else View.GONE)
        rv.setViewVisibility(R.id.people_fly_sway, if (fly != null && fly.sway) View.VISIBLE else View.GONE)
        if (fly != null) {
            val id = if (fly.sway) R.id.people_fly_sway else R.id.people_fly_go
            val dip = TypedValue.COMPLEX_UNIT_DIP
            // The flipper is as wide as the trip and sits in the middle of the scene; the margins
            // nudge it so the symbol's centre starts on the track, at the right height.
            rv.setViewLayoutWidth(id, fly.trackDp.coerceAtLeast(1f), dip)
            rv.setViewLayoutHeight(id, fly.sizeDp, dip)
            rv.setViewLayoutMargin(id, RemoteViews.MARGIN_END, fly.sizeDp / 2, dip)
            rv.setViewLayoutMargin(id, RemoteViews.MARGIN_BOTTOM, fly.aboveCentreDp.coerceAtLeast(0f), dip)
            // An empty one first, then two copies of the symbol. A flipper shows its first child
            // without animating it whenever the widget is redrawn, which left a heart sitting
            // still at the start of the line; now that moment is blank, and it reads as a short
            // pause between trips. The flipper restarts its animation on each flip, hence two.
            for (k in 0 until 3) {
                val item = RemoteViews(c.packageName, R.layout.people_fly_item)
                if (k > 0) item.setImageViewBitmap(R.id.people_fly_item, fly.bitmap)
                item.setViewLayoutWidth(R.id.people_fly_item, fly.sizeDp, dip)
                item.setViewLayoutHeight(R.id.people_fly_item, fly.sizeDp, dip)
                rv.addView(id, item)
            }
        }
    }

    /** Everything that isn't a picture: titles, the two lines, the button and what taps do. */
    private fun words(c: Context, rv: RemoteViews, s: State) {
        val data = s.data
        val g = s.g
        val circle = s.circle
        val scene = s.scene
        val others = s.others
        // With more than one group, the title is also a "next" button.
        rv.setTextViewText(R.id.people_title, if (data == null) "PEOPLE" else if (s.groups > 1) circle.title + "  ›" else circle.title)
        var status = if (data == null) "" else if (circle == Circle.FAMILY) "${others.size + 1} MEMBERS" else People.status(circle, scene, g)
        // They answered what you sent with the same thing: say so.
        val last = circle.action(g.lastAction)
        if (scene == Scene.RECEIVED && last != null && g.lastFrom != null && PeopleHistory.returned(c, g.id, g.lastFrom, last.key, g.lastAt)) {
            status = last.label.uppercase() + " RETURNED"
        }
        rv.setTextViewText(R.id.people_status, status)
        rv.setTextColor(R.id.people_status, if (scene == Scene.TOGETHER || scene == Scene.RECEIVED) circle.accent else 0xFF969696.toInt())

        // Words under the scene.
        val (big, small) = when {
            data == null && !PeopleRepo.configured -> "People" to "coming in a future update"
            data == null -> "Pair with someone" to "open Glance Kit · People"
            circle == Circle.FAMILY -> familyLines(g, s.me, others)
            else -> People.lines(circle, scene, g, s.me, s.other)
        }
        rv.setTextViewText(R.id.people_big, big)
        rv.setTextViewText(R.id.people_small, small)

        // Action button: the circle's first action (or Home safe for family).
        val open = Views.openApp(c, "people", 170)
        rv.setOnClickPendingIntent(R.id.people_scene, open)
        rv.setOnClickPendingIntent(R.id.people_text, open)
        rv.setOnClickPendingIntent(R.id.people_head, if (s.groups > 1) PendingIntent.getBroadcast(c, 174,
            Intent(c, PeopleWidget::class.java).setAction(PeopleWidget.ACTION_NEXT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) else open)
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

        /** False when the phone is on silent or Do Not Disturb, so we don't buzz through it. */
        fun mayBuzz(c: Context): Boolean {
            val silent = c.getSystemService(android.media.AudioManager::class.java)?.ringerMode == android.media.AudioManager.RINGER_MODE_SILENT
            val dnd = c.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
                ?.let { it != NotificationManager.INTERRUPTION_FILTER_ALL } ?: false
            return !silent && !dnd
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
            res.isSuccess -> {
                PeopleRepo.uidNow(c)?.let { PeopleHistory.note(c, gid, it, action) } // so a reply can count as "returned"
                res.getOrThrow().message(label, gd?.others?.firstOrNull()?.name ?: "their")
            }
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
            if (nm.getNotificationChannel(CHANNEL) == null) {
                // First run of this channel set: tidy away channels from older builds.
                nm.notificationChannels.filter { it.id.startsWith("people_") && it.id != "people_updates" }
                    .forEach { runCatching { nm.deleteNotificationChannel(it.id) } }
            }
            // Make whichever are missing, so actions added in an update get their buzz too.
            for (circle in Circle.entries) for (a in circle.actions) {
                if (nm.getNotificationChannel(channelId(circle, a)) != null) continue
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
        if (shown) notify(circle, action, name, gid)
        // Buzz it ourselves as well. The notification's channel should vibrate, but that depends
        // on phone settings we can't see (it was reported not buzzing at all), and here the app
        // is running, so we can be sure. Stays quiet when the phone is on silent or Do Not Disturb.
        if (!shown || PeopleSync.mayBuzz(this)) PeopleSync.buzz(this, action.pattern)
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
