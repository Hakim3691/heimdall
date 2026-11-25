package com.heimdall.engine.trigger

import com.heimdall.common.Clock
import com.heimdall.model.BlockReason
import com.heimdall.model.GateResult
import com.heimdall.model.RgbImage
import com.heimdall.model.TriggerConfig
import com.heimdall.model.TriggerState

/** Observation produced by feeding one captured frame into the cascade. */
data class FrameObservation(
    val signature: FrameSignature,
    /** Hamming distance to the previous frame; null for the first frame after a reset. */
    val hammingDistance: Int?,
    val stable: Boolean,
)

/**
 * The three-layer trigger cascade (FR-02).
 *
 * Gates are evaluated cheapest-first and short-circuit, which is the whole power story of the
 * product: a frame only ever reaches the VLM when the gyroscope, the accessibility stream and the
 * dHash check all agree. No inference happens without a [GateResult.Passed].
 */
class TriggerCascade(
    private val config: TriggerConfig,
    private val clock: Clock,
) {
    val motion: GyroTremorDetector = GyroTremorDetector(config)
    val hesitation: HesitationTracker = HesitationTracker(config)

    private var previousSignature: FrameSignature? = null
    private var frameStable: Boolean = false
    private var cooldownUntilMs: Long = Long.MIN_VALUE

    /** Last observation, or null before the first frame. */
    var lastObservation: FrameObservation? = null
        private set

    fun onMagnitude(magnitude: Float) = motion.onMagnitude(magnitude)

    fun onInteraction() = hesitation.onInteraction(clock.nowMs())

    /**
     * Feeds one captured frame (already downscaled by the platform adapter) and records whether it
     * is static relative to the previous one.
     */
    fun onFrame(image: RgbImage): FrameObservation {
        val signature = FrameDiffEngine.signature(image)
        val previous = previousSignature
        val distance = previous?.hammingTo(signature)
        val stable = distance != null && distance <= config.frameHashMaxHammingDistance
        previousSignature = signature
        frameStable = stable
        return FrameObservation(signature, distance, stable).also { lastObservation = it }
    }

    /** Feeds a precomputed signature (used by the service to avoid hashing twice). */
    fun onSignature(signature: FrameSignature): FrameObservation {
        val previous = previousSignature
        val distance = previous?.hammingTo(signature)
        val stable = distance != null && distance <= config.frameHashMaxHammingDistance
        previousSignature = signature
        frameStable = stable
        return FrameObservation(signature, distance, stable).also { lastObservation = it }
    }

    /** Immutable snapshot of all three gate signals. */
    fun state(): TriggerState = TriggerState(
        handheldActive = motion.isHandheldActive,
        hesitating = hesitation.isHesitating(clock.nowMs()),
        frameStable = frameStable,
    )

    /**
     * Evaluates the cascade. Order matters: the cooldown is checked before the sensors so a nagging
     * tooltip costs nothing, and the motion gate is checked before the timer so a phone on a desk
     * never arms the expensive stages.
     */
    fun evaluate(): GateResult {
        val now = clock.nowMs()
        if (now < cooldownUntilMs) {
            return GateResult.Blocked(BlockReason.COOLDOWN, cooldownRemainingMs = cooldownUntilMs - now)
        }
        if (!motion.isHandheldActive) {
            return GateResult.Blocked(BlockReason.MOTION)
        }
        if (!hesitation.isHesitating(now)) {
            return GateResult.Blocked(BlockReason.HESITATION)
        }
        if (!frameStable) {
            return GateResult.Blocked(BlockReason.FRAME_STABILITY)
        }
        return GateResult.Passed
    }

    /**
     * Called after a tooltip is rendered: starts the anti-nag cooldown, restarts the hesitation
     * window and keeps the current frame baseline so the same screen cannot trigger twice.
     */
    fun onInterventionRendered() {
        val now = clock.nowMs()
        cooldownUntilMs = now + config.postInterventionCooldownMs
        hesitation.restart(now)
    }

    /** Remaining anti-nag cooldown in milliseconds, 0 when the cascade may fire. */
    fun cooldownRemainingMs(): Long {
        val remaining = cooldownUntilMs - clock.nowMs()
        return if (remaining > 0L) remaining else 0L
    }

    /** Clears sensor/UI/frame state, e.g. on screen-off or service restart. */
    fun reset() {
        motion.reset()
        hesitation.reset()
        previousSignature = null
        frameStable = false
        lastObservation = null
        cooldownUntilMs = Long.MIN_VALUE
    }
}
