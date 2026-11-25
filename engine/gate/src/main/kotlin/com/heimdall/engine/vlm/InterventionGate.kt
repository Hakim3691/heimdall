package com.heimdall.engine.vlm

import com.heimdall.model.Anchor
import com.heimdall.model.Intervention
import com.heimdall.model.OverlayConfig
import com.heimdall.model.RejectReason
import com.heimdall.model.TextMetrics
import com.heimdall.model.VlmConfig
import com.heimdall.model.VlmOutcome

/**
 * Output schema gate (FR-05).
 *
 * Every model answer passes through here before anything can reach the screen. The gate is
 * intentionally unforgiving: an answer that is not exactly the contracted JSON object, that says
 * `intervene: false`, that reports low confidence, or whose copy is blank is turned into
 * [VlmOutcome.Silent]. Silence is always a valid outcome; a wrong tooltip never is.
 */
class InterventionGate(
    private val vlmConfig: VlmConfig = VlmConfig(),
    private val overlayConfig: OverlayConfig = OverlayConfig(),
) {
    fun gate(raw: String): VlmOutcome {
        val parsed = MiniJson.parseFlatObject(raw)
            ?: return VlmOutcome.Silent(RejectReason.MALFORMED_OUTPUT, "not a flat JSON object")

        val intervene = parsed["intervene"] as? JsonValue.Bool
            ?: return VlmOutcome.Silent(RejectReason.MALFORMED_OUTPUT, "missing boolean 'intervene'")
        if (!intervene.value) {
            return VlmOutcome.Silent(RejectReason.NOT_INTERVENE)
        }

        val rawText = (parsed["text"] as? JsonValue.Str)?.value
            ?: return VlmOutcome.Silent(RejectReason.MALFORMED_OUTPUT, "missing string 'text'")
        val singleLine = TextMetrics.singleLine(rawText)
        if (singleLine.isEmpty() || TextMetrics.countWords(singleLine) == 0) {
            return VlmOutcome.Silent(RejectReason.EMPTY_TEXT)
        }

        val confidence = (parsed["confidence"] as? JsonValue.Num)?.value?.toFloat()
        if (confidence == null || !confidence.isFinite()) {
            return VlmOutcome.Silent(RejectReason.LOW_CONFIDENCE, "no numeric confidence reported")
        }
        if (confidence < vlmConfig.confidenceThreshold || confidence > 1f) {
            return VlmOutcome.Silent(
                RejectReason.LOW_CONFIDENCE,
                "confidence $confidence below ${vlmConfig.confidenceThreshold}",
            )
        }

        val budget = overlayConfig.maxWordCount
        val bounded = TextMetrics.truncateToWords(singleLine, budget)
        if (TextMetrics.countWords(bounded) > budget) {
            return VlmOutcome.Silent(RejectReason.WORD_LIMIT_EXCEEDED)
        }
        if (bounded.isBlank()) {
            return VlmOutcome.Silent(RejectReason.EMPTY_TEXT)
        }

        val anchor = when ((parsed["anchor"] as? JsonValue.Str)?.value?.lowercase()) {
            "top" -> Anchor.TOP
            else -> Anchor.BOTTOM
        }

        return VlmOutcome.Intervene(
            Intervention(
                text = bounded,
                confidence = confidence,
                anchor = anchor,
            ),
        )
    }
}
