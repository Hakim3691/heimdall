package com.heimdall.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.heimdall.common.HeimdallLog
import com.heimdall.engine.trigger.TriggerCascade
import kotlin.math.sqrt

/**
 * Feeds gyroscope magnitudes into the trigger cascade (FR-02 stage 1).
 *
 * Registers at `SENSOR_DELAY_UI` (~60 ms) rather than a fixed rate: the platform batches sensor
 * deliveries to the display refresh cadence, which is exactly the resolution the variance window
 * needs and costs a fraction of a continuous high-rate stream.
 *
 * Devices without a gyroscope are supported by declaring a handheld assumption on the detector.
 * That is intentionally the permissive failure mode: stages 2 and 3 still gate every inference, so
 * a false positive here costs one extra frame hash, not a wrong tooltip.
 */
class GyroMonitor(
    context: Context,
    private val cascade: TriggerCascade,
    private val log: HeimdallLog,
) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val gyroscope: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var registered = false

    val hasGyroscope: Boolean get() = gyroscope != null

    fun start() {
        val manager = sensorManager
        val sensor = gyroscope
        if (manager == null || sensor == null) {
            log.warn(TAG, "no gyroscope on this device; assuming handheld (stages 2+3 still gate)")
            cascade.motion.assumeHandheld(true)
            return
        }
        if (registered) return
        registered = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI, 0)
        log.info(TAG, "gyroscope listener registered=$registered")
    }

    fun stop() {
        if (registered) {
            sensorManager?.unregisterListener(this)
            registered = false
        }
        cascade.motion.assumeHandheld(false)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GYROSCOPE) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        cascade.onMagnitude(sqrt(x * x + y * y + z * z))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val TAG = "GyroMonitor"
    }
}
