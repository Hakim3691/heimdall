package com.heimdall.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TextMetricsTest {
    @Test
    fun `counts plain words`() {
        assertEquals(4, TextMetrics.countWords("Tap Allow to continue"))
    }

    @Test
    fun `collapses irregular whitespace`() {
        assertEquals(3, TextMetrics.countWords("  one \n two\t\tthree  "))
    }

    @Test
    fun `does not count punctuation-only tokens`() {
        assertEquals(2, TextMetrics.countWords("Retry — now"))
    }

    @Test
    fun `empty text has no words`() {
        assertEquals(0, TextMetrics.countWords("   "))
    }

    @Test
    fun `truncation keeps short text untouched`() {
        assertEquals("Tap Allow", TextMetrics.truncateToWords("Tap Allow", 15))
    }

    @Test
    fun `truncation cuts on a word boundary`() {
        val text = (1..20).joinToString(" ") { "w$it" }
        val truncated = TextMetrics.truncateToWords(text, 15)
        assertEquals(15, TextMetrics.countWords(truncated))
        assertEquals(true, truncated.endsWith("w15"))
    }

    @Test
    fun `truncation drops a dangling separator`() {
        assertEquals("a, b", TextMetrics.truncateToWords("a, b, c", 2))
    }
}
