package com.heimdall.engine.vlm

/** A value from a flat JSON object produced by the VLM. */
sealed interface JsonValue {
    data class Str(val value: String) : JsonValue
    data class Num(val value: Double) : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data object Null : JsonValue
}

/**
 * Strict, dependency-free parser for the flat JSON object Heimdall asks the VLM to produce.
 *
 * Why hand-rolled instead of `org.json`: the output gate is a security boundary (FR-05). We must
 * accept exactly one shape — a single flat object with scalar values — and reject everything else
 * deterministically on both the JVM (tests) and Android (runtime). `org.json` is a stub in local
 * unit tests and lenient in ways we do not want.
 *
 * Tolerated: leading/trailing prose, code fences, whitespace, escaped strings.
 * Rejected: nested objects/arrays, duplicate keys, trailing commas, unterminated strings.
 */
object MiniJson {
    private const val MAX_INPUT_CHARS = 8_192

    /** Parses the first flat object found in [raw], or returns null if it is not well-formed. */
    fun parseFlatObject(raw: String): Map<String, JsonValue>? {
        if (raw.length > MAX_INPUT_CHARS) return null
        val start = raw.indexOf('{')
        if (start < 0) return null
        val end = findClosingBrace(raw, start) ?: return null
        return parseObjectBody(raw.substring(start + 1, end))
    }

    private fun findClosingBrace(text: String, openIndex: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in openIndex until text.length) {
            val ch = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return null
    }

    private fun parseObjectBody(body: String): Map<String, JsonValue>? {
        val result = LinkedHashMap<String, JsonValue>()
        var index = 0
        while (true) {
            index = skipWhitespace(body, index)
            if (index >= body.length) break
            if (body[index] == ',') {
                index++
                continue
            }
            if (body[index] != '"') return null
            val keyParse = parseString(body, index) ?: return null
            index = skipWhitespace(body, keyParse.second)
            if (index >= body.length || body[index] != ':') return null
            index = skipWhitespace(body, index + 1)
            val valueParse = parseValue(body, index) ?: return null
            if (result.containsKey(keyParse.first)) return null
            result[keyParse.first] = valueParse.first
            index = skipWhitespace(body, valueParse.second)
            if (index < body.length && body[index] != ',') return null
        }
        return result
    }

    private fun parseValue(text: String, startIndex: Int): Pair<JsonValue, Int>? {
        if (startIndex >= text.length) return null
        return when (val ch = text[startIndex]) {
            '"' -> parseString(text, startIndex)?.let { (value, next) -> JsonValue.Str(value) to next }
            't' -> literal(text, startIndex, "true", JsonValue.Bool(true))
            'f' -> literal(text, startIndex, "false", JsonValue.Bool(false))
            'n' -> literal(text, startIndex, "null", JsonValue.Null)
            else -> if (ch == '-' || ch.isDigit()) parseNumber(text, startIndex) else null
        }
    }

    private fun literal(text: String, startIndex: Int, token: String, value: JsonValue): Pair<JsonValue, Int>? {
        if (!text.startsWith(token, startIndex)) return null
        return value to (startIndex + token.length)
    }

    private fun parseNumber(text: String, startIndex: Int): Pair<JsonValue, Int>? {
        var index = startIndex
        if (index < text.length && text[index] == '-') index++
        val digitsStart = index
        while (index < text.length && (text[index].isDigit() || text[index] == '.' || text[index] == 'e' || text[index] == 'E' || text[index] == '+' || text[index] == '-')) {
            index++
        }
        if (index == digitsStart) return null
        val parsed = text.substring(startIndex, index).toDoubleOrNull() ?: return null
        return JsonValue.Num(parsed) to index
    }

    private fun parseString(text: String, startIndex: Int): Pair<String, Int>? {
        val builder = StringBuilder()
        var index = startIndex + 1
        while (index < text.length) {
            when (val ch = text[index]) {
                '"' -> return builder.toString() to (index + 1)
                '\\' -> {
                    if (index + 1 >= text.length) return null
                    when (val escape = text[index + 1]) {
                        '"' -> builder.append('"')
                        '\\' -> builder.append('\\')
                        '/' -> builder.append('/')
                        'b' -> builder.append('\b')
                        'f' -> builder.append('\u000C')
                        'n' -> builder.append('\n')
                        'r' -> builder.append('\r')
                        't' -> builder.append('\t')
                        'u' -> {
                            if (index + 5 >= text.length) return null
                            val hex = text.substring(index + 2, index + 6)
                            val code = hex.toIntOrNull(16) ?: return null
                            builder.append(code.toChar())
                            index += 4
                        }
                        else -> return null
                    }
                    index += 2
                }
                else -> {
                    builder.append(ch)
                    index++
                }
            }
        }
        return null
    }

    private fun skipWhitespace(text: String, from: Int): Int {
        var index = from
        while (index < text.length && text[index].isWhitespace()) index++
        return index
    }
}
