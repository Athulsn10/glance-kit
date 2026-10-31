package com.glassring.widget

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager
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
        refreshAll(applicationContext)
        return Result.success()
    }

    companion object {
        fun schedule(c: Context) {
            val req = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(c).enqueueUniquePeriodicWork("ring_refresh", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
