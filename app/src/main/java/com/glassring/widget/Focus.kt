package com.glassring.widget

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Focus timer logic. The countdown itself is drawn by a system Chronometer, so it ticks for free. */
object Focus {
    private const val DONE_WORK = "focus_done"

    fun toggle(c: Context) {
        val now = System.currentTimeMillis()
        val end = Store.focusEndAt(c)
        val pausedLeft = Store.focusPausedLeft(c)
        if (end in 1..now) { // already ended, done-worker not run yet: count it instead of losing it
            cancelDone(c)
            Store.finish(c, completed = true)
            return
        }
        when {
            end > now -> { // running -> pause
                Store.setPaused(c, end - now)
                cancelDone(c)
            }
            pausedLeft > 0 -> { // paused -> resume
                Store.setRunning(c, now + pausedLeft, Store.focusLength(c))
                scheduleDone(c, pausedLeft)
            }
            else -> { // idle -> start
                val len = TimeUnit.MINUTES.toMillis(Config.FOCUS_MINUTES)
                Store.setRunning(c, now + len, len)
                scheduleDone(c, len)
            }
        }
    }

    fun addFiveMinutes(c: Context) {
        val five = TimeUnit.MINUTES.toMillis(5)
        Store.extend(c, five)
        val end = Store.focusEndAt(c)
        if (end > 0) scheduleDone(c, end - System.currentTimeMillis())
    }

    fun stop(c: Context) {
        cancelDone(c)
        Store.finish(c, completed = false)
    }

    private fun scheduleDone(c: Context, delayMs: Long) {
        val req = OneTimeWorkRequestBuilder<FocusDoneWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(c).enqueueUniqueWork(DONE_WORK, ExistingWorkPolicy.REPLACE, req)
    }

    private fun cancelDone(c: Context) {
        WorkManager.getInstance(c).cancelUniqueWork(DONE_WORK)
    }
}

/** Runs when a focus session ends: counts it, buzzes once, redraws. */
class FocusDoneWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        val end = Store.focusEndAt(c)
        // Ignore if the session was paused, stopped or extended since this was scheduled.
        if (end > 0 && end <= System.currentTimeMillis() + 5_000) {
            Store.finish(c, completed = true)
            try {
                c.getSystemService(VibratorManager::class.java)?.defaultVibrator
                    ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 120, 120, 120), -1))
            } catch (e: Exception) {
            }
        }
        refreshAll(c)
        return Result.success()
    }
}

/** Keeps the widget fresh every 15 minutes (the shortest Android allows for background work). */
class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        refreshAll(c)
        if (!Collect.battery(c).plugged) ChargeWorker.watch(c)
        return Result.success()
    }

    companion object {
        fun schedule(c: Context) {
            val req = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(c).enqueueUniquePeriodicWork("ring_refresh", ExistingPeriodicWorkPolicy.KEEP, req)
            ChargeWorker.watch(c)
        }
    }
}

/**
 * Live charging updates without a background service.
 * A job waits for the phone to be plugged in (the system wakes it, no polling). While plugged in,
 * it refreshes the widget once a minute. When unplugged, it refreshes once more and goes back to waiting.
 */
class ChargeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        refreshAll(c)
        if (Collect.battery(c).plugged) tick(c, next = if (tags.contains(TAG_A)) TICK_B else TICK_A) else watch(c)
        return Result.success()
    }

    companion object {
        private const val WAIT = "charge_wait"
        private const val TICK_A = "charge_tick_a"
        private const val TICK_B = "charge_tick_b"
        private const val TAG_A = "tick_a"

        /** Wait for the charger. Cheap: handled by the system's job scheduler. */
        fun watch(c: Context) {
            val req = OneTimeWorkRequestBuilder<ChargeWorker>()
                .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
                .build()
            WorkManager.getInstance(c).enqueueUniqueWork(WAIT, ExistingWorkPolicy.KEEP, req)
        }

        /**
         * Next refresh in one minute. Ticks alternate between two names so a running tick never
         * replaces itself, and there is never more than one loop.
         */
        private fun tick(c: Context, next: String) {
            val b = OneTimeWorkRequestBuilder<ChargeWorker>().setInitialDelay(1, TimeUnit.MINUTES)
            if (next == TICK_A) b.addTag(TAG_A)
            WorkManager.getInstance(c).enqueueUniqueWork(next, ExistingWorkPolicy.REPLACE, b.build())
        }
    }
}
