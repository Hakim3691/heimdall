package com.heimdall.model

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The few GGUF header fields Heimdall needs to refuse a model that could never load.
 *
 * A file can be a perfectly valid GGUF and still be useless here: the ternary/Bonsai weights the
 * first build accepted are GGUF, but their architecture is not MiniCPM-V and llama.cpp rejects them
 * only after the user waits through a multi-gigabyte load. Reading the metadata prefix at pick time
 * turns that into an immediate "this file is not supported".
 */
data class GgufInfo(
    val version: Int,
    val architecture: String?,
    val keyCount: Long,
    /** True when clip/mmproj keys are present (a vision projector, not language weights). */
    val hasVisionKeys: Boolean,
)

/**
 * Minimal, bounded GGUF metadata reader (little endian, version 3).
 *
 * Only the first keys are read — `general.architecture` is emitted first by every converter — and
 * every length is validated against the supplied buffer, so a truncated or hostile file cannot make
 * this allocate or loop unboundedly. Version 1/2 files use a different string layout and return
 * [parse] == null, which callers treat as "no metadata opinion" rather than "incompatible".
 */
object GgufMetadata {
    private val MAGIC =
        byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())
    private const val SUPPORTED_VERSION = 3
    private const val MAX_KEYS = 512L
    private const val MAX_STRING_BYTES = 64L * 1024L
    private const val MAX_ARRAY_ELEMENTS = 4_096L
    private const val MAX_DEPTH = 2

    private const val TYPE_UINT8 = 0
    private const val TYPE_INT8 = 1
    private const val TYPE_UINT16 = 2
    private const val TYPE_INT16 = 3
    private const val TYPE_UINT32 = 4
    private const val TYPE_INT32 = 5
    private const val TYPE_FLOAT32 = 6
    private const val TYPE_BOOL = 7
    private const val TYPE_STRING = 8
    private const val TYPE_ARRAY = 9
    private const val TYPE_UINT64 = 10
    private const val TYPE_INT64 = 11
    private const val TYPE_FLOAT64 = 12

    private const val KEY_ARCHITECTURE = "general.architecture"

    /** Parses what fits in [bytes]; null when the buffer is too short or not GGUF v3. */
    fun parse(bytes: ByteArray): GgufInfo? {
        if (bytes.size < 24) return null
        for (index in MAGIC.indices) {
            if (bytes[index] != MAGIC[index]) return null
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(MAGIC.size)
        val version = buffer.int
        if (version != SUPPORTED_VERSION) return null
        buffer.long // tensor count: not needed for the compatibility verdict
        val keyCount = buffer.long

        var architecture: String? = null
        var hasVisionKeys = false
        var parsedKeys = 0L
        val target = minOf(keyCount, MAX_KEYS)
        while (parsedKeys < target && buffer.remaining() >= 12) {
            val key = readString(buffer) ?: break
            val type = buffer.int
            if (key == KEY_ARCHITECTURE && type == TYPE_STRING) {
                architecture = readString(buffer) ?: break
            } else if (!skipValue(buffer, type, depth = 0)) {
                break
            }
            if (key.startsWith("clip.") || key.startsWith("mmproj.") || key.contains("vision")) {
                hasVisionKeys = true
            }
            parsedKeys++
        }
        return GgufInfo(
            version = version,
            architecture = architecture,
            keyCount = keyCount,
            hasVisionKeys = hasVisionKeys,
        )
    }

    private fun readString(buffer: ByteBuffer): String? {
        if (buffer.remaining() < 8) return null
        val length = buffer.long
        if (length < 0L || length > MAX_STRING_BYTES || length > buffer.remaining()) return null
        val value = ByteArray(length.toInt())
        buffer.get(value)
        return String(value, Charsets.UTF_8)
    }

    private fun skipValue(buffer: ByteBuffer, type: Int, depth: Int): Boolean {
        if (depth > MAX_DEPTH) return false
        val fixedSize = when (type) {
            TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> 1
            TYPE_UINT16, TYPE_INT16 -> 2
            TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> 4
            TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> 8
            TYPE_STRING -> return readString(buffer) != null
            TYPE_ARRAY -> return skipArray(buffer, depth)
            else -> return false
        }
        if (buffer.remaining() < fixedSize) return false
        buffer.position(buffer.position() + fixedSize)
        return true
    }

    private fun skipArray(buffer: ByteBuffer, depth: Int): Boolean {
        if (buffer.remaining() < 12) return false
        val elementType = buffer.int
        val count = buffer.long
        if (count < 0L || count > MAX_ARRAY_ELEMENTS) return false
        var index = 0L
        while (index < count) {
            if (!skipValue(buffer, elementType, depth + 1)) return false
            index++
        }
        return true
    }
}
