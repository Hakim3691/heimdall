package com.heimdall.model

/**
 * Word accounting for tooltip copy.
 *
 * The PRD caps guidance at 15 words (FR-02 / NFR copy discipline). Whitespace-heavy VLM output is
 * normalised before counting so the gate cannot be bypassed with double spaces or newlines.
 */
object TextMetrics {
    private val WHITESPACE = Regex("\\s+")
    private val TRAILING_SEPARATORS = charArrayOf(',', ';', ':', '-', '\u2013')

    /** Counts whitespace-separated words; punctuation-only tokens are not words. */
    fun countWords(text: String): Int {
        val normalised = text.trim()
        if (normalised.isEmpty()) return 0
        return normalised.split(WHITESPACE).count { token -> token.any(Char::isLetterOrDigit) }
    }

    /**
     * Truncates [text] to at most [maxWords] words on a word boundary.
     *
     * Trailing list separators are dropped so the result never ends on a dangling comma.
     */
    fun truncateToWords(text: String, maxWords: Int): String {
        require(maxWords > 0) { "maxWords must be positive" }
        val normalised = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (normalised.size <= maxWords) return text.trim()
        return normalised.take(maxWords).joinToString(" ").trimEnd(*TRAILING_SEPARATORS)
    }

    /** Single-line display form: collapses newlines/tabs the VLM may emit. */
    fun singleLine(text: String): String = text.trim().replace(WHITESPACE, " ")
}
