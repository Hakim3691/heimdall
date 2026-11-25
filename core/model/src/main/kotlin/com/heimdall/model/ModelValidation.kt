package com.heimdall.model

/** One of the two GGUF files Heimdall needs at runtime. */
enum class ModelRole { LANGUAGE, VISION_PROJECTOR }

/** A required model file, its canonical file name and the minimum plausible size on disk. */
enum class ModelFile(
    val role: ModelRole,
    val fileName: String,
    val minBytes: Long,
    val description: String,
) {
    LANGUAGE(
        role = ModelRole.LANGUAGE,
        fileName = "minicpm-v-2_6-Q4_K_M.gguf",
        // A real Q4_K_M build is ~5.5 GB; anything under 1 GB is a truncated download, not a model.
        minBytes = 1_000_000_000L,
        description = "MiniCPM-V 2.6 Q4_K_M language weights",
    ),
    VISION(
        role = ModelRole.VISION_PROJECTOR,
        fileName = "mmproj-f16.gguf",
        minBytes = 100_000_000L,
        description = "MiniCPM-V 2.6 vision projector (mmproj, f16)",
    ),
}

/** Why a model file cannot be used. */
enum class ModelIssue {
    MISSING,
    EMPTY,
    NOT_A_GGUF,
    TRUNCATED,
    INSUFFICIENT_STORAGE,
}

/** Result of validating one model file. */
sealed interface ModelValidation {
    data object Ready : ModelValidation

    data class Invalid(
        val file: ModelFile,
        val issue: ModelIssue,
        val detail: String? = null,
    ) : ModelValidation
}

/**
 * Pure validation of a model file's observable properties (FR-07).
 *
 * The Android adapter reads the file size, the first four bytes and the free space, then asks this
 * object for a verdict — which keeps the storage manager's rules testable without a filesystem and
 * impossible to accidentally loosen.
 */
object ModelValidator {
    /** GGUF files start with the ASCII magic "GGUF" followed by a little-endian version. */
    private val GGUF_MAGIC = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())

    /** Extra room the runtime needs beyond the weights themselves (KV cache, mmap overhead). */
    const val RUNTIME_HEADROOM_BYTES: Long = 512L * 1024L * 1024L

    fun validate(
        file: ModelFile,
        sizeBytes: Long,
        header: ByteArray,
        freeBytes: Long,
    ): ModelValidation {
        if (sizeBytes <= 0L) {
            return ModelValidation.Invalid(file, ModelIssue.MISSING, "file is absent or empty")
        }
        if (!header.startsWithGgufMagic()) {
            return ModelValidation.Invalid(file, ModelIssue.NOT_A_GGUF, "magic header is not GGUF")
        }
        if (sizeBytes < file.minBytes) {
            return ModelValidation.Invalid(
                file,
                ModelIssue.TRUNCATED,
                "size $sizeBytes is below the ${file.minBytes} byte floor",
            )
        }
        if (freeBytes in 0 until RUNTIME_HEADROOM_BYTES) {
            return ModelValidation.Invalid(
                file,
                ModelIssue.INSUFFICIENT_STORAGE,
                "less than ${RUNTIME_HEADROOM_BYTES} bytes free",
            )
        }
        return ModelValidation.Ready
    }

    private fun ByteArray.startsWithGgufMagic(): Boolean {
        if (size < GGUF_MAGIC.size) return false
        return GGUF_MAGIC.indices.all { index -> this[index] == GGUF_MAGIC[index] }
    }
}
