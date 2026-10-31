package com.glassring.widget

import android.content.Context
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Rich haptics for the instruments, using the Pixel's fine-grained vibration primitives
 * (tick, click, thud, rise) with a fallback to the standard effects on other phones.
 */
class Haptics(c: Context) {

    private val vib: Vibrator? = c.getSystemService(VibratorManager::class.java)?.defaultVibrator
    private val prefs = Store.prefs(c)
    private var lastAt = 0L

    var enabled: Boolean
        get() = prefs.getBoolean("haptics_on", true)
        set(v) { prefs.edit().putBoolean("haptics_on", v).apply() }

    private fun supports(p: Int) = vib?.areAllPrimitivesSupported(p) == true

    /** Plays a primitive at [scale] (0..1). [minGapMs] stops fast movement turning into a buzz. */
    private fun prim(p: Int, scale: Float, fallback: Int, minGapMs: Long = 25) {
        val v = vib ?: return
        if (!enabled || !v.hasVibrator()) return
        val now = SystemClock.uptimeMillis()
        if (now - lastAt < minGapMs) return
        lastAt = now
        val effect = if (supports(p)) {
            VibrationEffect.startComposition().addPrimitive(p, scale.coerceIn(0.05f, 1f)).compose()
        } else VibrationEffect.createPredefined(fallback)
        runCatching { v.vibrate(effect) }
    }

    private fun compose(vararg steps: Triple<Int, Float, Int>, fallback: Int) {
        val v = vib ?: return
        if (!enabled || !v.hasVibrator()) return
        lastAt = SystemClock.uptimeMillis()
        val ok = steps.all { supports(it.first) }
        val effect = if (ok) {
            VibrationEffect.startComposition().apply {
                steps.forEach { (p, s, delay) -> addPrimitive(p, s, delay) }
            }.compose()
        } else VibrationEffect.createPredefined(fallback)
        runCatching { v.vibrate(effect) }
    }

    // ----- Building blocks -----

    /** Faint detent, like a dial's notch. */
    fun detent(scale: Float = 0.25f) =
        prim(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, scale, VibrationEffect.EFFECT_TICK, 20)

    /** Firmer notch. */
    fun tick(scale: Float = 0.6f) =
        prim(VibrationEffect.Composition.PRIMITIVE_TICK, scale, VibrationEffect.EFFECT_TICK, 20)

    /** A clear click. */
    fun click(scale: Float = 0.8f) =
        prim(VibrationEffect.Composition.PRIMITIVE_CLICK, scale, VibrationEffect.EFFECT_CLICK, 30)

    /** North: a deep thud followed by a click. */
    fun north() = compose(
        Triple(VibrationEffect.Composition.PRIMITIVE_THUD, 0.9f, 0),
        Triple(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f, 40),
        fallback = VibrationEffect.EFFECT_HEAVY_CLICK,
    )

    /** Locked on: a quick swell that lands on a click. */
    fun lock() = compose(
        Triple(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.6f, 0),
        Triple(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 20),
        fallback = VibrationEffect.EFFECT_HEAVY_CLICK,
    )

    /** Slipped off: a soft fall. */
    fun release() = compose(
        Triple(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, 0.4f, 0),
        fallback = VibrationEffect.EFFECT_TICK,
    )

    /** Something set (a target, a zero point): two clicks. */
    fun set() = compose(
        Triple(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.8f, 0),
        Triple(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.8f, 70),
        fallback = VibrationEffect.EFFECT_DOUBLE_CLICK,
    )

    /** Cleared: a short spin down. */
    fun clear() = compose(
        Triple(VibrationEffect.Composition.PRIMITIVE_SPIN, 0.5f, 0),
        fallback = VibrationEffect.EFFECT_TICK,
    )

    /** Session complete: a swell, three rising clicks and a thud. */
    fun celebrate() = compose(
        Triple(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, 0.6f, 0),
        Triple(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.5f, 60),
        Triple(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.75f, 90),
        Triple(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 90),
        Triple(VibrationEffect.Composition.PRIMITIVE_THUD, 1f, 120),
        fallback = VibrationEffect.EFFECT_HEAVY_CLICK,
    )

    /** Calibration needed: a slow swell. */
    fun warn() = compose(
        Triple(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, 0.5f, 0),
        Triple(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, 0.5f, 0),
        fallback = VibrationEffect.EFFECT_DOUBLE_CLICK,
    )

    /**
     * Geiger-style pulse: call on every sensor update with how close you are (0 = far, 1 = on it).
     * Pulses get faster and stronger as you close in.
     */
    private var lastPulse = 0L
    fun proximity(closeness: Float) {
        if (closeness <= 0f) return
        val now = SystemClock.uptimeMillis()
        val gap = (450 - 370 * closeness).toLong()          // 450 ms far away, 80 ms right next to it
        if (now - lastPulse < gap) return
        lastPulse = now
        prim(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.15f + 0.6f * closeness, VibrationEffect.EFFECT_TICK, 20)
    }
}
