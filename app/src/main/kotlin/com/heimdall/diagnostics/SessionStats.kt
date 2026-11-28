package com.heimdall.diagnostics

import com.heimdall.model.BlockReason
import com.heimdall.model.GateResult
import java.util.concurrent.atomic.AtomicLong

/**
 * Counters for the current (or last) capture session.
 *
 * The exported diagnostics used to report only the model selection, which made it impossible to
 * tell "the cascade never passed" from "the engine was never initialised". These counters are the
 * minimum needed to answer that from a user-provided log: frames observed, gate passes, inference
 * attempts, interventions, the engine's own last-known state, and — most importantly — the reason
 * the cascade last refused a frame. A session where every frame is refused by the same stage is
 * otherwise completely silent on device.
 */
class SessionStats {
    private val frameCounter = AtomicLong()
    private val gatePassCounter = AtomicLong()
    private val inferenceCounter = AtomicLong()
    private val interventionCounter = AtomicLong()

    @Volatile
    var engineStatus: String = "not started"

    /** Last cascade verdict in words, including the gyro numbers behind a motion refusal. */
    @Volatile
    var lastGate: String = "no frame evaluated yet"
        private set

    /** Last model answer, or why it was dropped. */
    @Volatile
    var lastVerdict: String = "none"
        private set

    val frames: Long get() = frameCounter.get()
    val gatePasses: Long get() = gatePassCounter.get()
    val inferences: Long get() = inferenceCounter.get()
    val interventions: Long get() = interventionCounter.get()

    fun onFrameObserved() = frameCounter.incrementAndGet()

    /**
     * Records the cascade outcome. [motionDetail] explains a motion refusal (variance, peak, band),
     * which is the one number a user cannot otherwise report.
     */
    fun onGateEvaluated(result: GateResult, motionDetail: String = "") {
        lastGate = when (result) {
            is GateResult.Passed -> {
                gatePassCounter.incrementAndGet()
                "passed"
            }

            is GateResult.Blocked -> when (result.reason) {
                BlockReason.MOTION -> "blocked by motion ($motionDetail)"
                BlockReason.HESITATION -> "blocked by hesitation (no interaction long enough yet)"
                BlockReason.FRAME_STABILITY -> "blocked by frame stability (screen still changing)"
                BlockReason.COOLDOWN -> "blocked by cooldown (${result.cooldownRemainingMs} ms left)"
            }
        }
    }

    fun onInferenceStarted() = inferenceCounter.incrementAndGet()

    fun onInterventionRendered() = interventionCounter.incrementAndGet()

    /** Stores the last gate verdict: an intervention text, or the reason the answer was dropped. */
    fun onVerdict(verdict: String) {
        lastVerdict = verdict
    }

    fun reset() {
        frameCounter.set(0L)
        gatePassCounter.set(0L)
        inferenceCounter.set(0L)
        interventionCounter.set(0L)
        engineStatus = "not started"
        lastGate = "no frame evaluated yet"
        lastVerdict = "none"
    }

    fun describe(): String =
        "frames=$frames gatePasses=$gatePasses inferences=$inferences " +
            "interventions=$interventions engine=$engineStatus " +
            "lastGate=\"$lastGate\" lastVerdict=\"$lastVerdict\""
}
