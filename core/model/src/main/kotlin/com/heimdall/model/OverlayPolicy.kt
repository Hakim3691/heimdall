package com.heimdall.model

/**
 * Presentation decisions for a tooltip, derived without touching the Android window APIs.
 *
 * Keeping this pure is deliberate: the FR-01 "touch-through by default" contract is the single most
 * important safety property of the overlay, so it is decided here and asserted by acceptance tests,
 * while [com.heimdall.ui.overlay.OverlayWindowController] only translates the spec into
 * `WindowManager.LayoutParams`.
 */
data class OverlaySpec(
    val anchor: Anchor,
    val verticalInsetDp: Int,
    val autoDismissMs: Long,
    /** False == the pill lets taps fall through to the app underneath (FR-01 default). */
    val touchable: Boolean,
    /** Only ever true when the user opted into the expanded-help chip. */
    val expandedHelpAvailable: Boolean,
    val text: String,
    val wordCount: Int,
) {
    init {
        require(wordCount <= OverlayPolicy.MAX_WORDS) { "tooltip exceeds the word budget" }
    }
}

/** Rules that turn a validated [Intervention] into a renderable [OverlaySpec]. */
object OverlayPolicy {
    /** Hard PRD cap: tooltips are at most 15 words. */
    const val MAX_WORDS: Int = 15

    /**
     * Builds the overlay spec for [intervention].
     *
     * The pill is always non-touchable; expanded help is a separate opt-in surface, so it can never
     * steal a tap meant for the app underneath.
     */
    fun specFor(intervention: Intervention, config: OverlayConfig): OverlaySpec {
        val normalised = TextMetrics.singleLine(intervention.text)
        val words = TextMetrics.countWords(normalised)
        val bounded = if (words > config.maxWordCount) {
            TextMetrics.truncateToWords(normalised, config.maxWordCount)
        } else {
            normalised
        }
        return OverlaySpec(
            anchor = intervention.anchor,
            verticalInsetDp = config.verticalInsetDp,
            autoDismissMs = config.autoDismissMs,
            touchable = false,
            expandedHelpAvailable = config.expandedHelpEnabled,
            text = bounded,
            wordCount = TextMetrics.countWords(bounded),
        )
    }
}
