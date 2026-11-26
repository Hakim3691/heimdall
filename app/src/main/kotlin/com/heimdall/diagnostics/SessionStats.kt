package com.heimdall.diagnostics

import java.util.concurrent.atomic.AtomicLong

/**
 * Counters for the current (or last) capture session.
 *
 * The exported diagnostics used to report only the model selection, which made it impossible to
 * tell "the cascade never passed" from "the engine was never initialised". These counters are the
 * minimum needed to answer that from a user-provided log: frames observed, gate passes, inference
 * attempts, interventions, and the engine's own last-known state.
 */
class SessionStats {
    private val frameCounter = AtomicLong()
    private val gatePassCounter = AtomicLong()
    private val inferenceCounter = AtomicLong()
    private val interventionCounter = AtomicLong()

    @Volatile
    var engineStatus: String = "not started"

    val frames: Long get() = frameCounter.get()
    val gatePasses: Long get() = gatePassCounter.get()
    val inferences: Long get() = inferenceCounter.get()
    val interventions: Long get() = interventionCounter.get()

    fun onFrameObserved() = frameCounter.incrementAndGet()

    fun onGatePassed() = gatePassCounter.incrementAndGet()

    fun onInferenceStarted() = inferenceCounter.incrementAndGet()

    fun onInterventionRendered() = interventionCounter.incrementAndGet()

    fun reset() {
        frameCounter.set(0L)
        gatePassCounter.set(0L)
        inferenceCounter.set(0L)
        interventionCounter.set(0L)
        engineStatus = "not started"
    }

    fun describe(): String =
        "frames=$frames gatePasses=$gatePasses inferences=$inferences " +
            "interventions=$interventions engine=$engineStatus"
}
