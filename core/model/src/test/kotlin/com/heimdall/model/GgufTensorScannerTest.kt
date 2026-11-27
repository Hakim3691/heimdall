package com.heimdall.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The tensor table is the only place that says whether a GGUF's packing is readable by the bundled
 * llama.cpp, so these tests build real GGUF v3 bytes and check the walk over them.
 */
class GgufTensorScannerTest {
    @Test
    fun `a PQ2_0 tensor is reported with its name and type`() {
        val file = gguf(
            keys = listOf(Key.string("general.architecture", "qwen35")),
            tensors = listOf(Tensor("blk.0.attn_q.weight", GgmlTensorTypes.PQ2_0)),
        )

        val scan = GgufTensorScanner.scan(ByteArrayInputStream(file))

        assertNotNull(scan)
        assertEquals("blk.0.attn_q.weight", scan!!.unsupported?.name)
        assertEquals(142, scan.unsupported?.typeId)
        assertTrue(scan.describe().contains("PQ2_0"))
    }

    @Test
    fun `Bonsai's Q2_0 packing is accepted`() {
        val file = gguf(
            keys = listOf(Key.string("general.architecture", "qwen35")),
            tensors = listOf(
                Tensor("token_embd.weight", GgmlTensorTypes.Q2_0),
                Tensor("blk.0.attn_q.weight", GgmlTensorTypes.Q2_0),
            ),
        )

        val scan = GgufTensorScanner.scan(ByteArrayInputStream(file))

        assertNull(scan?.unsupported)
        assertTrue(scan!!.complete)
        assertEquals(2, scan.scanned)
    }

    @Test
    fun `tokenizer arrays are skipped on the way to the tensor table`() {
        val file = gguf(
            keys = listOf(
                Key.string("general.architecture", "qwen2"),
                Key.intArray("tokenizer.ggml.token_type", IntArray(151_936) { 1 }),
                Key.stringArray("tokenizer.ggml.tokens", List(1_000) { "token-$it" }),
            ),
            tensors = listOf(Tensor("blk.7.ffn_down.weight", GgmlTensorTypes.PTQ1_0)),
        )

        val scan = GgufTensorScanner.scan(ByteArrayInputStream(file))

        assertEquals(143, scan?.unsupported?.typeId)
        assertTrue(scan?.describe().orEmpty().contains("PTQ1_0"))
    }

    @Test
    fun `a scan that runs out of budget is not a rejection`() {
        val file = gguf(
            keys = listOf(Key.stringArray("tokenizer.ggml.tokens", List(500) { "token-$it" })),
            tensors = listOf(Tensor("blk.0.attn_q.weight", GgmlTensorTypes.PQ2_0)),
        )

        val scan = GgufTensorScanner.scan(ByteArrayInputStream(file), maxBytes = 64)

        assertNotNull(scan)
        assertNull(scan?.unsupported)
        assertFalse(scan!!.complete)
        assertTrue(scan.describe().startsWith("unknown("))
    }

    @Test
    fun `a file that is not GGUF v3 yields no opinion`() {
        assertNull(GgufTensorScanner.scan(ByteArrayInputStream(byteArrayOf(0x50, 0x4B, 0x03, 0x04))))
    }

    // ---- GGUF v3 writer --------------------------------------------------------------------

    private sealed interface Key {
        val name: String

        class StringKey(override val name: String, val value: String) : Key

        class IntArrayKey(override val name: String, val values: IntArray) : Key

        class StringArrayKey(override val name: String, val values: List<String>) : Key

        companion object {
            fun string(name: String, value: String): Key = StringKey(name, value)

            fun intArray(name: String, values: IntArray): Key = IntArrayKey(name, values)

            fun stringArray(name: String, values: List<String>): Key = StringArrayKey(name, values)
        }
    }

    private data class Tensor(val name: String, val type: Int)

    private fun gguf(keys: List<Key>, tensors: List<Tensor>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("GGUF".toByteArray())
        writeInt(out, 3)
        writeLong(out, tensors.size.toLong())
        writeLong(out, keys.size.toLong())
        for (key in keys) {
            writeString(out, key.name)
            when (key) {
                is Key.StringKey -> {
                    writeInt(out, GGUF_TYPE_STRING)
                    writeString(out, key.value)
                }

                is Key.IntArrayKey -> {
                    writeInt(out, GGUF_TYPE_ARRAY)
                    writeInt(out, GGUF_TYPE_INT32)
                    writeLong(out, key.values.size.toLong())
                    for (value in key.values) writeInt(out, value)
                }

                is Key.StringArrayKey -> {
                    writeInt(out, GGUF_TYPE_ARRAY)
                    writeInt(out, GGUF_TYPE_STRING)
                    writeLong(out, key.values.size.toLong())
                    for (value in key.values) writeString(out, value)
                }
            }
        }
        for (tensor in tensors) {
            writeString(out, tensor.name)
            writeInt(out, 1)
            writeLong(out, 1L)
            writeInt(out, tensor.type)
            writeLong(out, 0L)
        }
        return out.toByteArray()
    }

    private fun writeString(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray()
        writeLong(out, bytes.size.toLong())
        out.write(bytes)
    }

    private fun writeInt(out: ByteArrayOutputStream, value: Int) {
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
    }

    private fun writeLong(out: ByteArrayOutputStream, value: Long) {
        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array())
    }

    private companion object {
        const val GGUF_TYPE_INT32 = 5
        const val GGUF_TYPE_STRING = 8
        const val GGUF_TYPE_ARRAY = 9
    }
}
