package com.heimdall.model

/** Why the output gate suppressed a VLM response. Never shown to the user; diagnostics only. */
enum class RejectReason {
    /** Model answered `intervene: false`. */
    NOT_INTERVENE,

    /** Model asked to intervene but self-reported confidence below the configured floor. */
    LOW_CONFIDENCE,

    /** Output was not the required JSON object (truncated stream, prose, wrong schema). */
    MALFORMED_OUTPUT,

    /** `text` was present but empty/whitespace once normalised. */
    EMPTY_TEXT,

    /** Even after truncation the copy still exceeded the word budget. */
    WORD_LIMIT_EXCEEDED,

    /** The engine could not run (model not loaded, native library missing, out of memory). */
    ENGINE_UNAVAILABLE,

    /** Inference exceeded its deadline and was abandoned. */
    TIMEOUT,
}

/** Result of evaluating one screen frame against the local VLM plus the output schema gate. */
sealed interface VlmOutcome {
    data class Intervene(val intervention: Intervention) : VlmOutcome

    data class Silent(
        val reason: RejectReason,
        val detail: String? = null,
    ) : VlmOutcome
}
