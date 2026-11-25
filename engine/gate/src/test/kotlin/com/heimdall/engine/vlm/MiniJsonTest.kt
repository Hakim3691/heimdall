package com.heimdall.engine.vlm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MiniJsonTest {
    @Test
    fun `parses a flat object with scalars`() {
        val parsed = MiniJson.parseFlatObject(
            """{"intervene": true, "confidence": 0.9, "text": "Tap Allow", "nothing": null}""",
        )
        assertEquals(JsonValue.Bool(true), parsed?.get("intervene"))
        assertEquals(JsonValue.Num(0.9), parsed?.get("confidence"))
        assertEquals(JsonValue.Str("Tap Allow"), parsed?.get("text"))
        assertEquals(JsonValue.Null, parsed?.get("nothing"))
    }

    @Test
    fun `finds the object inside surrounding prose and fences`() {
        val raw = """
            Here you go:
            ```json
            {"intervene": false}
            ```
        """.trimIndent()
        assertEquals(JsonValue.Bool(false), MiniJson.parseFlatObject(raw)?.get("intervene"))
    }

    @Test
    fun `decodes escapes`() {
        val parsed = MiniJson.parseFlatObject("""{"text": "Tap \"Retry\"\nthen wait"}""")
        assertEquals(JsonValue.Str("Tap \"Retry\"\nthen wait"), parsed?.get("text"))
    }

    @Test
    fun `rejects nested objects`() {
        assertNull(MiniJson.parseFlatObject("""{"text": {"hint": "tap"}}"""))
    }

    @Test
    fun `rejects duplicate keys`() {
        assertNull(MiniJson.parseFlatObject("""{"a": 1, "a": 2}"""))
    }

    @Test
    fun `rejects unterminated strings`() {
        assertNull(MiniJson.parseFlatObject("""{"text": "unterminated}"""))
    }

    @Test
    fun `rejects an unclosed object`() {
        assertNull(MiniJson.parseFlatObject("""{"intervene": true"""))
    }

    @Test
    fun `rejects input without an object`() {
        assertNull(MiniJson.parseFlatObject("just prose"))
    }

    @Test
    fun `rejects trailing garbage between pairs`() {
        assertNull(MiniJson.parseFlatObject("""{"a": 1 2}"""))
    }

    @Test
    fun `accepts negative and exponent numbers`() {
        val parsed = MiniJson.parseFlatObject("""{"a": -1.5e3}""")
        assertTrue(parsed?.get("a") is JsonValue.Num)
        assertEquals(JsonValue.Num(-1500.0), parsed?.get("a"))
    }

    @Test
    fun `rejects overly long input`() {
        assertNull(MiniJson.parseFlatObject("{" + " ".repeat(9_000) + "}"))
    }
}
