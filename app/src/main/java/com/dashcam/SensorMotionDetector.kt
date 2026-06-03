package com.dashcam

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

class SensorMotionDetector(
    context: Context,
    private val onMotion: () -> Unit
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val sensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val usingLinear = sensor?.type == Sensor.TYPE_LINEAR_ACCELERATION
    private val gravity = FloatArray(3)

    var threshold = 1.3f

    fun start() {
        sensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x: Float
        val y: Float
        val z: Float
        if (usingLinear) {
            x = event.values[0]
            y = event.values[1]
            z = event.values[2]
        } else {
            val alpha = 0.8f
            for (i in 0..2) gravity[i] = alpha * gravity[i] + (1 - alpha) * event.values[i]
            x = event.values[0] - gravity[0]
            y = event.values[1] - gravity[1]
            z = event.values[2] - gravity[2]
        }
        val magnitude = sqrt(x * x + y * y + z * z)
        if (magnitude > threshold) {
            onMotion()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
