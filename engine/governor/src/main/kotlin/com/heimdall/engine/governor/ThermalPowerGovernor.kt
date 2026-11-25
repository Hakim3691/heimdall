package com.heimdall.engine.governor

import com.heimdall.common.Clock
import com.heimdall.model.ThermalTier

/**
 * Thermal and power governor (FR-06, NFR-1).
 *
 * Implements a duty-cycle policy rather than a fixed cadence: the device tells us how hot it is and
 * the governor decides the minimum spacing between inference runs. Under SEVERE/CRITICAL/Emergency
 * status inference stops entirely until the device cools, which is the only safe behaviour for an
 * ambient service that would otherwise fight the platform thermal daemon.
 *
 * Pure and clock-injected so every transition can be tested without a device.
 */
class ThermalPowerGovernor(private val clock: Clock) {
    /** Currently active tier, driven by platform thermal callbacks. */
    var tier: ThermalTier = ThermalTier.NONE
        private set

    /** When the next inference run becomes eligible, in clock time. */
    private var nextEligibleAtMs: Long = Long.MIN_VALUE

    /** Minimum spacing between runs for the active tier. */
    val dutyIntervalMs: Long get() = tier.dutyIntervalMs

    /** True when the governor has suspended inference due to heat. */
    val isExecutionPaused: Boolean get() = tier.paused

    /** Applies the raw `PowerManager.THERMAL_STATUS_*` code. Unknown codes fall back to MODERATE. */
    fun onThermalStatus(status: Int) = applyTier(ThermalTier.fromPlatformStatus(status))

    /**
     * Applies a tier directly. Cooling down does not retroactively shorten the wait already owed for
     * the hotter tier, but it does lift a pause immediately.
     */
    fun applyTier(newTier: ThermalTier) {
        tier = newTier
        if (newTier.paused) {
            nextEligibleAtMs = Long.MAX_VALUE
        } else if (nextEligibleAtMs == Long.MAX_VALUE) {
            nextEligibleAtMs = clock.nowMs()
        }
    }

    /** True when inference is allowed right now. */
    fun canRunNow(): Boolean = !tier.paused && clock.nowMs() >= nextEligibleAtMs

    /** Remaining wait before the next eligible run; 0 when eligible or paused. */
    fun cooldownRemainingMs(): Long {
        if (tier.paused) return 0L
        val remaining = nextEligibleAtMs - clock.nowMs()
        return if (remaining > 0L) remaining else 0L
    }

    /**
     * Records that a run consumed the duty budget: the next run is pushed out by the active tier's
     * interval. Called after the inference completes (or is abandoned) so latency is not counted
     * against the budget twice.
     */
    fun onInferenceConsumed() {
        if (tier.paused) return
        nextEligibleAtMs = clock.nowMs() + tier.dutyIntervalMs
    }

    /** Resets to an unconstrained state; used when the capture service starts. */
    fun reset() {
        tier = ThermalTier.NONE
        nextEligibleAtMs = Long.MIN_VALUE
    }
}
