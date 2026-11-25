package com.heimdall.acceptance

import com.heimdall.engine.governor.ThermalPowerGovernor
import com.heimdall.engine.trigger.FrameObservation
import com.heimdall.engine.trigger.FrameSignature
import com.heimdall.engine.trigger.TriggerCascade
import com.heimdall.engine.vlm.InterventionGate
import com.heimdall.model.GateResult
import com.heimdall.model.ModelValidation
import com.heimdall.model.OnboardingState
import com.heimdall.model.OverlayConfig
import com.heimdall.model.OverlaySpec
import com.heimdall.model.RgbImage
import com.heimdall.model.TriggerConfig
import com.heimdall.model.VlmConfig
import com.heimdall.model.VlmOutcome
import com.heimdall.testing.FakeClock

/**
 * Shared state for one Cucumber scenario.
 *
 * Cucumber's picocontainer creates one instance per scenario and injects it into every step class,
 * so steps can be split by feature area without leaking state between scenarios.
 */
class World {
    val clock = FakeClock(startMs = 1_000_000L)

    // --- trigger cascade -----------------------------------------------------------------
    val triggerConfig = TriggerConfig()
    val cascade = TriggerCascade(triggerConfig, clock)
    var gateResult: GateResult? = null
    var observation: FrameObservation? = null
    var image: RgbImage? = null
    var secondaryImage: RgbImage? = null
    var signature: FrameSignature? = null
    var secondarySignature: FrameSignature? = null

    // --- governor ------------------------------------------------------------------------
    val governor = ThermalPowerGovernor(clock)
    var remainingWaitMs: Long = -1L

    // --- output gate ---------------------------------------------------------------------
    var vlmConfig = VlmConfig()
    var overlayConfig = OverlayConfig()
    var rawAnswer: String = ""
    var outcome: VlmOutcome? = null

    // --- overlay -------------------------------------------------------------------------
    var spec: OverlaySpec? = null

    // --- storage -------------------------------------------------------------------------
    var validation: ModelValidation? = null

    // --- onboarding ----------------------------------------------------------------------
    var onboardingState = OnboardingState()

    /** Builds the gate from the current (possibly scenario-tuned) configuration. */
    fun gate(): InterventionGate = InterventionGate(vlmConfig, overlayConfig)
}
