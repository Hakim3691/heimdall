package com.heimdall.model

/**
 * Vertical anchoring of a rendered tooltip.
 *
 * The pill never sits over the navigation bar or the status bar: [TOP] anchors below the status
 * bar and [BOTTOM] above the gesture/navigation area, both offset by [OverlayConfig.verticalInsetDp].
 */
enum class Anchor { TOP, BOTTOM }

/**
 * A validated, renderable guidance tooltip.
 *
 * Instances only exist once [VlmOutcome.Intervene] has passed the output schema gate, so every
 * consumer may assume: non-blank text, a confidence inside 0..1, and a word count within the
 * configured budget (see [TextMetrics]).
 */
data class Intervention(
    val text: String,
    val confidence: Float,
    val anchor: Anchor = Anchor.BOTTOM,
    /** Optional accessibility node label the tooltip refers to, used only for diagnostics. */
    val component: String? = null,
) {
    init {
        require(text.isNotBlank()) { "intervention text must not be blank" }
        require(confidence.isFinite() && confidence in 0f..1f) { "confidence must be within 0..1" }
    }

    val wordCount: Int get() = TextMetrics.countWords(text)
}
