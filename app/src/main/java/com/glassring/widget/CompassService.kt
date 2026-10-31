package com.glassring.widget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.widget.RemoteViews
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Keeps the Compass widget live.
 *
 * Android only lets a widget move if an app keeps sending it updates, so this runs as a small
 * foreground service (with a quiet notification). To save battery:
 *  - the sensor runs only while the screen is on; it stops the moment the screen turns off
 *  - each update only changes the dial's angle and the text, so it's tiny
 *  - updates are skipped unless the heading moved at least 2 degrees
 */
class CompassService : Service(), SensorEventListener {

    private lateinit var sm: SensorManager
    private var listening = false
    private var heading = 0f
    private var hasReading = false
    private var lastSent = -999f
    private var lastSentAt = 0L

    private val rot = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orient = FloatArray(3)

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> stopSensor()
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> startSensor()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        sm = getSystemService(SensorManager::class.java)
        goForeground()
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screen, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screen, f)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || ids(this).isEmpty()) {
            setLive(this, false)
            Singles.updateCompass(this)
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        setLive(this, true)
        if (getSystemService(PowerManager::class.java)?.isInteractive != false) startSensor()
        Singles.updateCompass(this)
        return START_STICKY
    }

    override fun onDestroy() {
        stopSensor()
        runCatching { unregisterReceiver(screen) }
        super.onDestroy()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Live compass", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while the compass widget is live"
                setShowBadge(false)
            },
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, CompassService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, InstrumentsActivity::class.java).putExtra("tab", "compass"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_w_compass)
            .setContentTitle("Compass widget is live")
            .setContentText("Updates only while the screen is on.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Turn off", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun startSensor() {
        if (listening) return
        val s = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return
        listening = sm.registerListener(this, s, SensorManager.SENSOR_DELAY_UI)
    }

    private fun stopSensor() {
        if (!listening) return
        sm.unregisterListener(this)
        listening = false
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        val m = if (abs(rot[8]) < 0.7f) {
            SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
            remapped
        } else rot
        SensorManager.getOrientation(m, orient)
        val target = ((Math.toDegrees(orient[0].toDouble()).toFloat() + 360f) % 360f)
        if (!hasReading) { heading = target; hasReading = true }
        val diff = ((target - heading + 540f) % 360f) - 180f
        heading = (heading + diff * 0.3f + 360f) % 360f

        val now = SystemClock.elapsedRealtime()
        val moved = abs(((heading - lastSent + 540f) % 360f) - 180f)
        if (moved >= 2f && now - lastSentAt >= 80) {
            lastSent = heading
            lastSentAt = now
            push(this, heading)
        }
    }

    companion object {
        private const val CHANNEL = "compass_live"
        private const val NOTIFICATION_ID = 7
        const val ACTION_STOP = "com.glassring.widget.COMPASS_STOP"
        private const val K_LIVE = "compass_live"
        private const val K_HEADING = "compass_heading"

        fun ids(c: Context): IntArray =
            AppWidgetManager.getInstance(c).getAppWidgetIds(ComponentName(c, CompassWidget::class.java))

        fun isLive(c: Context) = Store.prefs(c).getBoolean(K_LIVE, false)
        private fun setLive(c: Context, on: Boolean) = Store.prefs(c).edit().putBoolean(K_LIVE, on).apply()
        fun lastHeading(c: Context) = Store.prefs(c).getFloat(K_HEADING, 0f)

        /** Start from something the user did (a widget tap or the app being open). */
        fun start(c: Context) {
            if (ids(c).isEmpty()) return
            runCatching { c.startForegroundService(Intent(c, CompassService::class.java)) }
        }

        fun stop(c: Context) {
            setLive(c, false)
            c.stopService(Intent(c, CompassService::class.java))
            Singles.updateCompass(c)
        }

        fun startIntent(c: Context): PendingIntent = PendingIntent.getForegroundService(
            c, 140, Intent(c, CompassService::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** Fills the dial, heading and status for a full widget update. */
        fun bind(c: Context, rv: RemoteViews, heading: Float, live: Boolean) {
            rv.setInt(R.id.dial, "setImageLevel", level(heading))
            val deg = heading.roundToInt() % 360
            rv.setTextViewText(R.id.heading, "$deg°")
            rv.setTextViewText(R.id.direction, direction(deg))
            rv.setTextViewText(R.id.status, if (live) "LIVE" else "TAP TO GO LIVE")
        }

        /** Tiny update: only the dial angle and the text change. */
        private fun push(c: Context, heading: Float) {
            val ids = ids(c)
            if (ids.isEmpty()) return
            Store.prefs(c).edit().putFloat(K_HEADING, heading).apply()
            val rv = RemoteViews(c.packageName, R.layout.compass_widget)
            val deg = heading.roundToInt() % 360
            rv.setInt(R.id.dial, "setImageLevel", level(heading))
            rv.setTextViewText(R.id.heading, "$deg°")
            rv.setTextViewText(R.id.direction, direction(deg))
            AppWidgetManager.getInstance(c).partiallyUpdateAppWidget(ids, rv)
        }

        /** The dial turns the opposite way to the phone, so north stays north. */
        private fun level(heading: Float) = (((360f - heading) % 360f) / 360f * 10000f).roundToInt()

        fun direction(deg: Int): String {
            val names = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
            return names[(((deg + 11.25) % 360) / 22.5).toInt() % 16]
        }
    }
}
