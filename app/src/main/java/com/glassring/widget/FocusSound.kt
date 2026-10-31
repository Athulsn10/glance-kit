package com.glassring.widget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.lang.ref.WeakReference
import kotlin.random.Random

/**
 * Soothing sound loops for the focus scenes, with thunder for the rain.
 *
 * Plays while the focus screen is on show, and through a whole running session even with the
 * screen off (that's why it's a small media service with a notification). Stops by itself when
 * the session ends or is paused, and when you leave the focus screen with no session running.
 * The sounds are original, synthesised for this app (see tools/synth_sounds.py).
 */
class FocusSound : Service(), AudioManager.OnAudioFocusChangeListener {

    interface LightningListener { fun onLightning(power: Float) }

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var playing: Ambient.Kind? = null
    private var pool: SoundPool? = null
    private var thunderIds = IntArray(0)
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false
    private val rnd = Random(System.nanoTime())

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        active = true
        pool = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attrs).build().also { p ->
            thunderIds = intArrayOf(
                p.load(this, R.raw.thunder_1, 1),
                p.load(this, R.raw.thunder_2, 1),
                p.load(this, R.raw.thunder_3, 1),
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_OFF) {
            setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        val kind = Ambient.kind(this)
        startForeground(NOTIFICATION_ID, notification(kind), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        if (!wanted(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!hasFocus) requestFocus()
        if (kind != playing) play(kind)
        scheduleThunder()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        active = false
        main.removeCallbacksAndMessages(null)
        player?.run { runCatching { stop() }; release() }
        player = null
        pool?.release()
        pool = null
        focusRequest?.let { getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(it) }
        super.onDestroy()
    }

    // ---------------- Playback ----------------

    private fun rawFor(k: Ambient.Kind): Int? = when (k) {
        Ambient.Kind.RAIN -> R.raw.amb_rain
        Ambient.Kind.LEAVES -> R.raw.amb_leaves
        Ambient.Kind.SNOW -> R.raw.amb_snow
        Ambient.Kind.FIREFLIES -> R.raw.amb_fireflies
        Ambient.Kind.STARS -> R.raw.amb_stars
        Ambient.Kind.NONE -> null
    }

    /** Switch to the scene's loop with a short fade (Ogg loops play back without a gap). */
    private fun play(k: Ambient.Kind) {
        val old = player
        player = null
        playing = k
        old?.let { fade(it, from = VOLUME, to = 0f, ms = 600) { it.release() } }
        val res = rawFor(k) ?: return
        val mp = MediaPlayer.create(this, res, attrs, getSystemService(AudioManager::class.java)!!.generateAudioSessionId()) ?: return
        mp.isLooping = true
        mp.setVolume(0f, 0f)
        if (hasFocus) mp.start()
        player = mp
        fade(mp, from = 0f, to = VOLUME, ms = 1500)
    }

    private fun fade(mp: MediaPlayer, from: Float, to: Float, ms: Long, done: (() -> Unit)? = null) {
        val steps = 20
        for (i in 1..steps) {
            main.postDelayed({
                val v = from + (to - from) * i / steps
                runCatching { mp.setVolume(v, v) }
                if (i == steps) done?.invoke()
            }, ms * i / steps)
        }
    }

    // ---------------- Thunder ----------------

    private val thunderTick = Runnable { thunder() }

    private fun scheduleThunder() {
        main.removeCallbacks(thunderTick)
        if (playing == Ambient.Kind.RAIN) main.postDelayed(thunderTick, (12_000 + rnd.nextInt(30_000)).toLong())
    }

    /** Lightning first, then thunder a moment later - closer storms rumble sooner and louder. */
    private fun thunder() {
        if (playing != Ambient.Kind.RAIN) return
        val near = rnd.nextFloat()
        val power = 0.5f + near * 0.5f
        listeners.removeAll { it.get() == null }
        listeners.forEach { it.get()?.onLightning(power) }
        val delay = (300 + (1 - near) * 2600).toLong()
        main.postDelayed({
            val id = thunderIds[rnd.nextInt(thunderIds.size)]
            val vol = (0.45f + near * 0.5f) * VOLUME / 0.55f
            if (hasFocus) pool?.play(id, vol.coerceAtMost(1f), vol.coerceAtMost(1f), 1, 0, 0.9f + rnd.nextFloat() * 0.15f)
        }, delay)
        main.postDelayed(thunderTick, (25_000 + rnd.nextInt(45_000)).toLong())
    }

    // ---------------- Audio focus (pause for calls, other music, etc.) ----------------

    private fun requestFocus() {
        val am = getSystemService(AudioManager::class.java) ?: return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(this, main)
            .setWillPauseWhenDucked(false)
            .build()
        focusRequest = req
        hasFocus = am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (hasFocus) player?.start()
    }

    override fun onAudioFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> { hasFocus = true; player?.let { it.setVolume(VOLUME, VOLUME); it.start() } }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player?.setVolume(VOLUME * 0.3f, VOLUME * 0.3f)
            else -> { hasFocus = false; player?.pause() }
        }
    }

    // ---------------- Notification ----------------

    private fun notification(k: Ambient.Kind): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Focus sounds", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while focus sounds are playing"
                setShowBadge(false)
            })
        }
        val open = PendingIntent.getActivity(this, 160,
            Intent(this, MainActivity::class.java).putExtra("tab", "focus").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val off = PendingIntent.getService(this, 161, Intent(this, FocusSound::class.java).setAction(ACTION_OFF),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_w_focus)
            .setContentTitle("Focus sounds · ${k.label}")
            .setContentText("Plays during your focus session")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Sound off", off).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "focus_sound"
        private const val NOTIFICATION_ID = 8
        private const val ACTION_OFF = "com.glassring.widget.FOCUS_SOUND_OFF"
        private const val VOLUME = 0.55f

        /** True while the service is running (the rain scene then leaves lightning to it). */
        @Volatile var active = false
            private set

        /** Focus screens currently on show (set by each FocusView). */
        private val showing = HashSet<Int>()
        private val preview get() = synchronized(showing) { showing.isNotEmpty() }
        private val main = Handler(Looper.getMainLooper())
        private val listeners = ArrayList<WeakReference<LightningListener>>()

        fun listen(l: LightningListener) {
            main.post { listeners.removeAll { it.get() == null || it.get() === l }; listeners.add(WeakReference(l)) }
        }

        fun enabled(c: Context) = Store.prefs(c).getBoolean("focus_sound", true)
        fun setEnabled(c: Context, on: Boolean) {
            Store.prefs(c).edit().putBoolean("focus_sound", on).apply()
            update(c)
        }

        private fun wanted(c: Context): Boolean {
            if (!enabled(c) || Ambient.kind(c) == Ambient.Kind.NONE) return false
            return preview || Store.focusEndAt(c) > System.currentTimeMillis()
        }

        private var pendingStop: Runnable? = null

        fun setPreview(c: Context, owner: Any, on: Boolean) {
            val id = System.identityHashCode(owner)
            val changed = synchronized(showing) { if (on) showing.add(id) else showing.remove(id) }
            if (!changed) return
            val app = c.applicationContext
            pendingStop?.let { main.removeCallbacks(it) }
            if (preview) update(app) else {
                // A short grace period, so flicking between tabs doesn't cut the sound.
                pendingStop = Runnable { update(app) }.also { main.postDelayed(it, 1500) }
            }
        }

        /** Start, retune or stop the sound to match the settings, the session and the screen. */
        fun update(c: Context) {
            val app = c.applicationContext
            if (wanted(app)) {
                runCatching { app.startForegroundService(Intent(app, FocusSound::class.java)) }
            } else if (active) {
                app.stopService(Intent(app, FocusSound::class.java))
            }
        }
    }
}
