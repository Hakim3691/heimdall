package com.heimdall.engine.trigger

import com.heimdall.model.TriggerConfig

/**
 * Stage 1 of the trigger cascade: handheld micro-tremor detection (TRD §6.1).
 *
 * Holds a ring buffer of gyroscope magnitude samples and classifies the device from their variance:
 *
 * - variance too low → the phone is resting on a desk, in a mount, or in a car dock
 * - variance too high → the user is walking, gesturing, or deliberately moving the device
 * - variance inside the band → a human is holding it still and looking at the screen
 *
 * The detector is pure: the platform adapter feeds magnitudes, so the band edges are unit-testable
 * exactly. Devices without a gyroscope call [assumeHandheld] so the later stages still gate.
 */
class GyroTremorDetector(private val config: TriggerConfig) {
    private val samples = FloatArray(config.gyroSampleCount)
    private var writeIndex = 0
    private var sampleCount = 0
    private var varianceActive = false

    /**
     * Set by the adapter when the hardware has no gyroscope. The cascade then relies on the
     * hesitation timer and the dHash check alone, which is strictly safer than disabling it.
     */
    var assumesHandheld: Boolean = false
        private set

    /** True when the last computed variance lies inside the micro-tremor band (or no sensor exists). */
    val isHandheldActive: Boolean get() = assumesHandheld || varianceActive

    /** Last computed population variance of the magnitude window, in (rad/s)^2. */
    var lastVariance: Float = 0f
        private set

    /** True once the window holds [TriggerConfig.gyroSampleCount] samples. */
    val hasFullWindow: Boolean get() = sampleCount == samples.size

    /**
     * Feeds one gyroscope sample. [magnitude] is `||w||` in rad/s, computed by the adapter from the
     * three raw axes so this class never sees a vector.
     */
    fun onMagnitude(magnitude: Float) {
        require(magnitude.isFinite() && magnitude >= 0f) { "gyro magnitude must be finite and >= 0" }
        samples[writeIndex] = magnitude
        writeIndex = (writeIndex + 1) % samples.size
        if (sampleCount < samples.size) sampleCount++
        if (hasFullWindow) recompute()
    }

    private fun recompute() {
        var sum = 0.0
        for (value in samples) sum += value
        val mean = sum / samples.size
        var squaredDeviations = 0.0
        for (value in samples) {
            val delta = value - mean
            squaredDeviations += delta * delta
        }
        lastVariance = (squaredDeviations / samples.size).toFloat()
        varianceActive = lastVariance >= config.gyroVarianceMin && lastVariance <= config.gyroVarianceMax
    }

    /** Declares that this device has no gyroscope; [isHandheldActive] becomes unconditionally true. */
    fun assumeHandheld(enabled: Boolean = true) {
        assumesHandheld = enabled
    }

    /** Drops the window, e.g. when the service stops or the screen turns off. */
    fun reset() {
        writeIndex = 0
        sampleCount = 0
        varianceActive = false
        assumesHandheld = false
        lastVariance = 0f
        samples.fill(0f)
    }
}
