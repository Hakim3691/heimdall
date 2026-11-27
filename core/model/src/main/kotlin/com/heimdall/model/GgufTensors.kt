package com.heimdall.model

import java.io.InputStream

/**
 * ggml tensor types the bundled llama.cpp build can decode.
 *
 * The ids are the `ggml_type` values of the pinned upstream commit (`ggml/include/ggml.h`), not the
 * ones PrismML's fork adds: a file can be a perfectly valid GGUF and still be unreadable because a
 * single tensor uses a type this binary does not implement (Bonsai's `PQ2_0` = 142, `PTQ1_0` = 143).
 * llama.cpp only says so after the metadata is parsed, which is why the tensor-info table is read at
 * pick time instead.
 */
object GgmlTensorTypes {
    /** Bonsai's Hadamard quants are upstream: `Q2_0` is a supported type in this build. */
    const val Q2_0 = 42

    /** PrismML-only types: no upstream implementation exists. */
    const val PQ2_0 = 142
    const val PTQ1_0 = 143

    /**
     * Every id defined by the pinned build. The gaps are types upstream removed (4, 5, 31-33,
     * 36-38): a file that still uses one is refused by ggml exactly like an unknown type.
     */
    val SUPPORTED: Set<Int> = setOf(
        0, 1, 2, 3, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23,
        24, 25, 26, 27, 28, 29, 30, 34, 35, 39, 40, 41, 42, 43,
    )

    private val NAMES = mapOf(
        0 to "F32",
        1 to "F16",
        2 to "Q4_0",
        3 to "Q4_1",
        6 to "Q5_0",
        7 to "Q5_1",
        8 to "Q8_0",
        9 to "Q8_1",
        10 to "Q2_K",
        11 to "Q3_K",
        12 to "Q4_K",
        13 to "Q5_K",
        14 to "Q6_K",
        15 to "Q8_K",
        16 to "IQ2_XXS",
        17 to "IQ2_XS",
        18 to "IQ3_XXS",
        19 to "IQ1_S",
        20 to "IQ4_NL",
        21 to "IQ3_S",
        22 to "IQ2_S",
        23 to "IQ4_XS",
        24 to "I8",
        25 to "I16",
        26 to "I32",
        27 to "I64",
        28 to "F64",
        29 to "IQ1_M",
        30 to "BF16",
        34 to "TQ1_0",
        35 to "TQ2_0",
        39 to "MXFP4",
        40 to "NVFP4",
        41 to "Q1_0",
        42 to "Q2_0",
        43 to "F8_E4M3",
        PQ2_0 to "PQ2_0",
        PTQ1_0 to "PTQ1_0",
    )

    /** Human name for [id]; the id itself when this build has never heard of the type. */
    fun name(id: Int): String = NAMES[id] ?: "unknown type"
}

/** One tensor-info entry whose type the bundled llama.cpp cannot decode. */
data class UnsupportedTensor(val name: String, val typeId: Int) {
    /** e.g. `PQ2_0 (ggml type 142)`. */
    val label: String get() = "${GgmlTensorTypes.name(typeId)} (ggml type $typeId)"
}

/**
 * What the tensor-info table of a GGUF says about its packing.
 *
 * [complete] is false when the table did not fit inside the scan budget: the packing is then simply
 * unknown, which must never be reported as a rejection.
 */
data class GgufTensorScan(
    val tensorCount: Long,
    val scanned: Int,
    val unsupported: UnsupportedTensor? = null,
    val complete: Boolean = true,
) {
    /** Short diagnostics form: the packing, or why there is nothing to say. */
    fun describe(): String = when {
        unsupported != null -> "unsupported(tensor '${unsupported.name}' is ${unsupported.label})"
        complete -> "supported($scanned tensors)"
        else -> "unknown($scanned of $tensorCount tensors scanned)"
    }
}

/**
 * Reads a GGUF's tensor-info table — names, dimensions and ggml types — without loading any weights.
 *
 * A GGUF header holds the tensor table *after* the key/value metadata, so the reader walks (and
 * skips) the metadata first. Values are skipped by their declared size, so the walk costs a few
 * megabytes of reads rather than the multi-gigabyte file, and every length is validated against
 * [scan]'s byte budget so a hostile or truncated file cannot make this loop or allocate unboundedly.
 */
object GgufTensorScanner {
    /** Metadata (tokenizer tables dominate) is a few MB; the table itself is a few tens of KB. */
    const val MAX_SCAN_BYTES: Long = 16L * 1024L * 1024L

    private val MAGIC =
        byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())
    private const val SUPPORTED_VERSION = 3
    private const val MAX_TENSORS = 100_000L
    private const val MAX_STRING_BYTES = 1L * 1024L * 1024L
    private const val MAX_ARRAY_ELEMENTS = 100_000_000L
    private const val MAX_DIMS = 8

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

    /**
     * Scans [stream] (positioned at the first byte of the file).
     *
     * Returns null when the bytes are not a GGUF v3 file — the metadata reader already has an
     * opinion in that case — and a (possibly incomplete) [GgufTensorScan] otherwise.
     */
    fun scan(stream: InputStream, maxBytes: Long = MAX_SCAN_BYTES): GgufTensorScan? {
        val cursor = Cursor(stream, maxBytes)
        val magic = ByteArray(MAGIC.size)
        if (!cursor.readFully(magic, MAGIC.size)) return null
        if (!magic.contentEquals(MAGIC)) return null
        val version = cursor.uint32() ?: return null
        if (version != SUPPORTED_VERSION) return null
        val tensorCount = cursor.uint64() ?: return null
        val keyCount = cursor.uint64() ?: return null

        var keyIndex = 0L
        while (keyIndex < keyCount) {
            if (!skipKeyValue(cursor)) return incomplete(tensorCount, 0)
            keyIndex++
        }

        var scanned = 0L
        val target = minOf(tensorCount, MAX_TENSORS)
        while (scanned < target) {
            val name = cursor.string(MAX_STRING_BYTES) ?: return incomplete(tensorCount, scanned.toInt())
            val dims = cursor.uint32() ?: return incomplete(tensorCount, scanned.toInt())
            if (dims > MAX_DIMS) return incomplete(tensorCount, scanned.toInt())
            if (!cursor.skip(dims.toLong() * 8L)) return incomplete(tensorCount, scanned.toInt())
            val type = cursor.uint32() ?: return incomplete(tensorCount, scanned.toInt())
            if (!cursor.skip(8L)) return incomplete(tensorCount, scanned.toInt())
            scanned++
            if (type !in GgmlTensorTypes.SUPPORTED) {
                // One bad tensor is enough: llama.cpp refuses the whole file.
                return GgufTensorScan(
                    tensorCount = tensorCount,
                    scanned = scanned.toInt(),
                    unsupported = UnsupportedTensor(name, type),
                    complete = true,
                )
            }
        }
        return GgufTensorScan(tensorCount = tensorCount, scanned = scanned.toInt(), complete = true)
    }

    private fun incomplete(tensorCount: Long, scanned: Int) =
        GgufTensorScan(tensorCount = tensorCount, scanned = scanned, complete = false)

    private fun skipKeyValue(cursor: Cursor): Boolean {
        if (cursor.string(MAX_STRING_BYTES) == null) return false
        val type = cursor.uint32() ?: return false
        return skipValue(cursor, type)
    }

    private fun skipValue(cursor: Cursor, type: Int): Boolean {
        val fixedSize = when (type) {
            TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> 1L
            TYPE_UINT16, TYPE_INT16 -> 2L
            TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> 4L
            TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> 8L
            TYPE_STRING -> return cursor.string(MAX_STRING_BYTES) != null
            TYPE_ARRAY -> return skipArray(cursor)
            else -> return false
        }
        return cursor.skip(fixedSize)
    }

    private fun skipArray(cursor: Cursor): Boolean {
        val elementType = cursor.uint32() ?: return false
        val count = cursor.uint64() ?: return false
        if (count < 0L || count > MAX_ARRAY_ELEMENTS) return false
        val fixedSize = when (elementType) {
            TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> 1L
            TYPE_UINT16, TYPE_INT16 -> 2L
            TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> 4L
            TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> 8L
            TYPE_STRING -> 0L
            TYPE_ARRAY -> return skipNestedArrays(cursor, count)
            else -> return false
        }
        if (fixedSize > 0L) {
            if (count != 0L && Long.MAX_VALUE / count < fixedSize) return false
            return cursor.skip(count * fixedSize)
        }
        var index = 0L
        while (index < count) {
            if (cursor.string(MAX_STRING_BYTES) == null) return false
            index++
        }
        return true
    }

    /** Arrays of arrays are legal in GGUF but no known model uses them; skip defensively. */
    private fun skipNestedArrays(cursor: Cursor, count: Long): Boolean {
        var index = 0L
        while (index < count) {
            if (!skipArray(cursor)) return false
            index++
        }
        return true
    }

    /** Little-endian reader that refuses to read past a byte budget. */
    private class Cursor(private val input: InputStream, private var remaining: Long) {
        fun readFully(target: ByteArray, length: Int): Boolean {
            if (length > remaining) return false
            var read = 0
            while (read < length) {
                val count = input.read(target, read, length - read)
                if (count < 0) return false
                read += count
                remaining -= count
            }
            return true
        }

        fun skip(count: Long): Boolean {
            if (count < 0L) return false
            if (count > remaining) return false
            var left = count
            val scratch = ByteArray(8 * 1024)
            while (left > 0L) {
                val step = minOf(left, scratch.size.toLong()).toInt()
                if (!readFully(scratch, step)) return false
                left -= step
            }
            return true
        }

        fun uint32(): Int? {
            val bytes = ByteArray(4)
            if (!readFully(bytes, 4)) return null
            return (bytes[0].toInt() and 0xFF) or
                ((bytes[1].toInt() and 0xFF) shl 8) or
                ((bytes[2].toInt() and 0xFF) shl 16) or
                ((bytes[3].toInt() and 0xFF) shl 24)
        }

        fun uint64(): Long? {
            val bytes = ByteArray(8)
            if (!readFully(bytes, 8)) return null
            var value = 0L
            for (index in 7 downTo 0) {
                value = (value shl 8) or (bytes[index].toLong() and 0xFF)
            }
            return value
        }

        fun string(maxBytes: Long): String? {
            val length = uint64() ?: return null
            if (length < 0L || length > maxBytes || length > remaining) return null
            val bytes = ByteArray(length.toInt())
            if (!readFully(bytes, bytes.size)) return null
            return String(bytes, Charsets.UTF_8)
        }
    }
}
