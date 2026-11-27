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

    /** A valid GGUF whose architecture or projector type is not supported. */
    INCOMPATIBLE_MODEL,

    /** Both files are individually usable, but they belong to different model families. */
    INCOMPATIBLE_PAIR,
}

/** Result of validating one model file, or the pair. */
sealed interface ModelValidation {
    /**
     * A usable file. [architecture] and [projectorType] are null when the metadata prefix could not
     * be parsed (old GGUF versions or a short read); the pair rule then has no opinion and the
     * runtime self-test decides.
     */
    data class Ready(
        val architecture: String? = null,
        val projectorType: String? = null,
    ) : ModelValidation

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
 * Compatibility is a **pair** property, not a per-file one. The bundled llama.cpp + mtmd front-end
 * supports several vision families; which vision projector can be paired with which language
 * weights is fixed by `tools/mtmd/mtmd-impl.h` (its `PROJECTOR_TYPE_NAMES` table), and a mismatch
 * fails only after a multi-gigabyte load. Reading `general.architecture` and `clip.projector_type`
 * at pick time turns that into an immediate, specific verdict.
 *
 * The GGUF metadata check is advisory in one direction only: when the prefix cannot be parsed there
 * is no verdict from metadata, so the size and magic rules decide and the runtime probe remains the
 * final authority.
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
     * Language architecture → the projector types mtmd can run with it.
     *
     * Values are the `clip.projector_type` strings from llama.cpp's `PROJECTOR_TYPE_NAMES`:
     * * `resampler` — MiniCPM-V 2.x (the official MiniCPM-V 2.6 mmproj declares exactly this)
     * * `qwen3vl_merger` — Qwen3-VL, including Qwen3.5-VL-based builds such as Ternary-Bonsai
     * * `minicpmv4_6`/`minicpmv4_7` — MiniCPM-V 4.x, whose language weights are Qwen3.5-based
     *
     * Anything outside this table is refused: a `clip` projector is not automatically a match.
     */
    val SUPPORTED_PAIRS: Map<String, Set<String>> = mapOf(
        "qwen2" to setOf("resampler"),
        "minicpmv" to setOf("resampler"),
        "qwen35" to setOf("qwen3vl_merger", "minicpmv4_6", "minicpmv4_7"),
        "qwen35moe" to setOf("qwen3vl_merger", "minicpmv4_6", "minicpmv4_7"),
        "qwen3vl" to setOf("qwen3vl_merger"),
        "qwen3vlmoe" to setOf("qwen3vl_merger"),
    )

    private val SUPPORTED_PROJECTOR_TYPES: Set<String> = SUPPORTED_PAIRS.values.flatten().toSet()

    /** ggml's name for the multimodal projector graph. */
    private const val VISION_ARCHITECTURE = "clip"

    /**
     * @param tensors tensor types from [GgufTensorScanner], when the adapter managed to read them.
     *   Null (or an incomplete scan) means packing is unverified, never that it is unsupported.
     */
    fun validate(
        file: ModelFile,
        sizeBytes: Long,
        header: ByteArray,
        freeBytes: Long,
        tensors: GgufTensorScan? = null,
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
        val info = GgufMetadata.parse(header)
        return incompatible(file, info)
            ?: packingProblem(file, tensors)
            ?: ModelValidation.Ready(
                architecture = info?.architecture,
                projectorType = info?.projectorType,
            )
    }

    /**
     * Checks that two individually valid files belong to the same model family.
     *
     * Returns null when the pair is acceptable — including when either file's metadata could not be
     * parsed, because this rule only rejects what it can prove is wrong.
     */
    fun validatePair(
        language: ModelValidation,
        vision: ModelValidation,
    ): ModelValidation.Invalid? {
        val languageReady = language as? ModelValidation.Ready ?: return null
        val visionReady = vision as? ModelValidation.Ready ?: return null
        val architecture = languageReady.architecture ?: return null
        val projectorType = visionReady.projectorType ?: return null
        val allowed = SUPPORTED_PAIRS[architecture]
            ?: return ModelValidation.Invalid(
                ModelFile.VISION,
                ModelIssue.INCOMPATIBLE_PAIR,
                "no known vision projector for language architecture '$architecture'",
            )
        if (projectorType in allowed) return null
        return ModelValidation.Invalid(
            ModelFile.VISION,
            ModelIssue.INCOMPATIBLE_PAIR,
            "projector '$projectorType' does not match language architecture '$architecture' " +
                "(expected ${allowed.joinToString()})",
        )
    }

    /** [validatePair] as a verdict, for callers that only need pass/fail. */
    fun validatePairOrReady(
        language: ModelValidation,
        vision: ModelValidation,
    ): ModelValidation = validatePair(language, vision) ?: ModelValidation.Ready(
        architecture = (language as? ModelValidation.Ready)?.architecture,
        projectorType = (vision as? ModelValidation.Ready)?.projectorType,
    )

    private fun incompatible(file: ModelFile, info: GgufInfo?): ModelValidation.Invalid? {
        val architecture = info?.architecture ?: return null
        return when (file.role) {
            ModelRole.LANGUAGE -> {
                if (architecture in SUPPORTED_PAIRS) {
                    null
                } else {
                    ModelValidation.Invalid(
                        file,
                        ModelIssue.INCOMPATIBLE_MODEL,
                        "architecture '$architecture' is not a supported language model " +
                            "(supported: ${SUPPORTED_PAIRS.keys.joinToString()})",
                    )
                }
            }

            ModelRole.VISION_PROJECTOR -> projectorProblem(file, architecture, info)
        }
    }

    /**
     * A vision projector must be a `clip` GGUF **and** declare a projector type mtmd implements.
     * The old rule (`clip || any *vision* key`) accepted foreign projectors such as a Qwen3.5-VL
     * mmproj next to MiniCPM-V weights, which only fails inside the native load.
     */
    private fun projectorProblem(
        file: ModelFile,
        architecture: String,
        info: GgufInfo,
    ): ModelValidation.Invalid? {
        if (architecture != VISION_ARCHITECTURE) {
            return ModelValidation.Invalid(
                file,
                ModelIssue.INCOMPATIBLE_MODEL,
                "architecture '$architecture' is not a vision projector",
            )
        }
        val projectorType = info.projectorType
            ?: return ModelValidation.Invalid(
                file,
                ModelIssue.INCOMPATIBLE_MODEL,
                "vision projector declares no clip.projector_type",
            )
        if (projectorType !in SUPPORTED_PROJECTOR_TYPES) {
            return ModelValidation.Invalid(
                file,
                ModelIssue.INCOMPATIBLE_MODEL,
                "projector type '$projectorType' is not supported " +
                    "(supported: ${SUPPORTED_PROJECTOR_TYPES.joinToString()})",
            )
        }
        return null
    }

    /**
     * A file can pass every metadata rule and still be undecodable because one tensor uses a ggml
     * type this llama.cpp build does not implement. Reading the tensor-info table at pick time
     * settles that here, instead of after a multi-gigabyte load ends in `invalid ggml type 142`.
     */
    private fun packingProblem(file: ModelFile, tensors: GgufTensorScan?): ModelValidation.Invalid? {
        val unsupported = tensors?.unsupported ?: return null
        val hint = when (unsupported.typeId) {
            GgmlTensorTypes.PQ2_0, GgmlTensorTypes.PTQ1_0 ->
                "these weights use PrismML's private packing, which the bundled upstream llama.cpp " +
                    "cannot read. Download the Q2_0 rebuild of the same weights, or a standard Q4/Q8 GGUF"

            else ->
                "this llama.cpp build does not implement that tensor type. Download a standard " +
                    "quantisation of the same model"
        }
        return ModelValidation.Invalid(
            file,
            ModelIssue.INCOMPATIBLE_MODEL,
            "tensor '${unsupported.name}' is ${unsupported.label}: $hint",
        )
    }

    private fun ByteArray.startsWithGgufMagic(): Boolean {
        if (size < GGUF_MAGIC.size) return false
        return GGUF_MAGIC.indices.all { index -> this[index] == GGUF_MAGIC[index] }
    }
}
