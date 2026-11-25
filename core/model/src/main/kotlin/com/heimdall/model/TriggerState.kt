package com.heimdall.model

/** Which stage of the three-layer cascade refused the frame. */
enum class BlockReason {
    /** Stage 1: device is at rest, mounted, or moving too aggressively to be a handheld user. */
    MOTION,

    /** Stage 2: the user interacted with the UI more recently than the hesitation threshold. */
    HESITATION,

    /** Stage 3: two consecutive frames differ by more than the permitted dHash distance. */
    FRAME_STABILITY,

    /** A tooltip was rendered recently; the cascade backs off so it cannot nag. */
    COOLDOWN,
}

/** Outcome of one cascade evaluation. */
sealed interface GateResult {
    data object Passed : GateResult

    data class Blocked(
        val reason: BlockReason,
        val cooldownRemainingMs: Long = 0L,
    ) : GateResult
}

/** Immutable snapshot of the three gate signals, useful for diagnostics and BDD assertions. */
data class TriggerState(
    val handheldActive: Boolean,
    val hesitating: Boolean,
    val frameStable: Boolean,
) {
    val allGatesOpen: Boolean get() = handheldActive && hesitating && frameStable
}
