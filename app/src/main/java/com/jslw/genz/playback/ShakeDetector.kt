package com.jslw.genz.playback

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/** Shake-to-skip. Only registered while music is playing, to save battery. */
class ShakeDetector(ctx: Context, private val onShake: () -> Unit) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var registered = false
    private var last = 0L
    private var hits = 0
    private var firstHit = 0L
    var strength = 3

    fun setActive(active: Boolean) {
        if (active == registered || accel == null) return
        if (active) sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI) else sm.unregisterListener(this)
        registered = active
    }

    override fun onSensorChanged(e: SensorEvent) {
        val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        val threshold = 1.6f + strength * 0.35f
        val now = System.currentTimeMillis()
        if (g > threshold) {
            if (now - firstHit > 800) { hits = 0; firstHit = now }
            hits++
            if (hits >= 2 && now - last > 1500) {
                last = now
                hits = 0
                onShake()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
