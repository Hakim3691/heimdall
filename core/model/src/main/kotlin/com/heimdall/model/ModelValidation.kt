package com.heimdall.model

/** One of the two GGUF files Heimdall needs at runtime. */
enum class ModelRole { LANGUAGE, VISION_PROJECTOR }

/**
 * A required model file: its role, the canonical MiniCPM-V file name and the minimum plausible
 * size on disk.
 *
 * [description] is a neutral role label — the UI must show what was actually picked, not assume the
 * recommended model, because the user can select any local GGUF and an incompatible one has to be
 * reported as such instead of being labelled "MiniCPM-V 2.6".
 */
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
        description = "Language weights (main GGUF)",
    ),
    VISION(
        role = ModelRole.VISION_PROJECTOR,
        fileName = "mmproj-f16.gguf",
        minBytes = 100_000_000L,
        description = "Vision projector (mmproj GGUF)",
    ),
}

/** Why a model file cannot be used. */
enum class ModelIssue {
    MISSING,
    EMPTY,
    NOT_A_GGUF,
    TRUNCATED,
    INSUFFICIENT_STORAGE,

    /** A valid GGUF whose architecture is not the supported MiniCPM-V pair. */
    INCOMPATIBLE_MODEL,
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
 * The Android adapter reads the file size, a metadata prefix and the free space, then asks this
 * object for a verdict — which keeps the storage manager's rules testable without a filesystem and
 * impossible to accidentally loosen.
 *
 * The GGUF metadata check is advisory in one direction only: when the prefix cannot be parsed
 * (version 1/2, short read) there is no verdict from metadata, so the size and magic rules decide.
 * When an architecture *is* readable and is not supported, the file is rejected before capture
 * starts instead of failing inside llama.cpp after a multi-gigabyte load.
 */
object ModelValidator {
    /** GGUF files start with the ASCII magic "GGUF" followed by a little-endian version. */
    private val GGUF_MAGIC =
        byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())

    /** Extra room the runtime needs beyond the weights themselves (KV cache, mmap overhead). */
    const val RUNTIME_HEADROOM_BYTES: Long = 512L * 1024L * 1024L

    /** How much of the file the platform adapter should hand over for metadata parsing. */
    const val METADATA_PREFIX_BYTES: Int = 64 * 1024

    /**
     * GGUF architectures accepted as Heimdall's language weights.
     *
     * MiniCPM-V 2.6 is Qwen2-based: the official `ggml-model-Q4_K_M.gguf` reports
     * `general.architecture = qwen2` (the multimodal wiring lives in the separate mmproj), while some
     * conversions use llama.cpp's `minicpmv` name. Both are accepted; anything else (e.g. a ternary /
     * Bonsai build) is rejected before the load.
     */
    private val SUPPORTED_LANGUAGE_ARCHITECTURES = setOf("qwen2", "minicpmv")

    /** ggml's name for the multimodal projector graph. */
    private const val VISION_ARCHITECTURE = "clip"

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
        return incompatible(file, GgufMetadata.parse(header))
            ?: ModelValidation.Ready
    }

    private fun incompatible(file: ModelFile, info: GgufInfo?): ModelValidation.Invalid? {
        val architecture = info?.architecture ?: return null
        return when (file.role) {
            ModelRole.LANGUAGE -> {
                if (architecture in SUPPORTED_LANGUAGE_ARCHITECTURES) {
                    null
                } else {
                    ModelValidation.Invalid(
                        file,
                        ModelIssue.INCOMPATIBLE_MODEL,
                        "architecture '$architecture' is not MiniCPM-V",
                    )
                }
            }

            ModelRole.VISION_PROJECTOR -> {
                if (architecture == VISION_ARCHITECTURE || info.hasVisionKeys) {
                    null
                } else {
                    ModelValidation.Invalid(
                        file,
                        ModelIssue.INCOMPATIBLE_MODEL,
                        "architecture '$architecture' is not a vision projector",
                    )
                }
            }
        }
    }

    private fun ByteArray.startsWithGgufMagic(): Boolean {
        if (size < GGUF_MAGIC.size) return false
        return GGUF_MAGIC.indices.all { index -> this[index] == GGUF_MAGIC[index] }
    }
}
