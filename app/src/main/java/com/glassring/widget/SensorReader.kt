package com.glassring.widget

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Takes a single reading from a sensor: switches it on, waits for the first value, switches it
 * off. Used by the widget so sensors are only ever on for a moment.
 */
object SensorReader {

    private val thread: HandlerThread by lazy { HandlerThread("sensors").apply { start() } }

    fun has(c: Context, type: Int): Boolean =
        c.getSystemService(SensorManager::class.java)?.getDefaultSensor(type) != null

    fun readOnce(c: Context, type: Int, timeoutMs: Long = 2000): FloatArray? {
        val sm = c.getSystemService(SensorManager::class.java) ?: return null
        val sensor = sm.getDefaultSensor(type) ?: return null
        val latch = CountDownLatch(1)
        var value: FloatArray? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (value == null) {
                    value = event.values.copyOf()
                    latch.countDown()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        return try {
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, Handler(thread.looper))
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            value
        } catch (e: Exception) {
            null
        } finally {
            sm.unregisterListener(listener)
        }
    }
}
