package com.heimdall.acceptance

import com.google.common.truth.Truth.assertThat
import com.heimdall.model.GgufTensorScan
import com.heimdall.model.GgufTensorScanner
import com.heimdall.model.ModelFile
import com.heimdall.model.ModelIssue
import com.heimdall.model.ModelValidation
import com.heimdall.model.ModelValidator
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** FR-07: model file validation. */
class ModelSteps(private val world: World) {
    private val ggufHeader = "GGUF".toByteArray()
    private val zipHeader = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val gigabyte = 1_000L * 1_000L * 1_000L
    private val megabyte = 1_000L * 1_000L

    private var file: ModelFile = ModelFile.LANGUAGE
    private var sizeBytes: Long = 0
    private var header: ByteArray = ggufHeader
    private var freeBytes: Long = 0

    /** Tensor table as the pick-time scan reports it; null means "packing not inspected". */
    private var tensors: GgufTensorScan? = null

    @Given("a language model of {int} GB with a GGUF header")
    fun languageModelGb(size: Int) {
        file = ModelFile.LANGUAGE
        sizeBytes = size * gigabyte
        header = ggufHeader
    }

    @Given("a language model of {int} MB with a GGUF header")
    fun languageModelMb(size: Int) {
        file = ModelFile.LANGUAGE
        sizeBytes = size * megabyte
        header = ggufHeader
    }

    @Given("a language model of {int} GB with a zip header")
    fun languageModelZip(size: Int) {
        file = ModelFile.LANGUAGE
        sizeBytes = size * gigabyte
        header = zipHeader
    }

    @Given("a language model with {int} bytes")
    fun languageModelBytes(size: Long) {
        file = ModelFile.LANGUAGE
        sizeBytes = size
        header = ByteArray(0)
    }

    @Given("a vision projector of {int} GB with a GGUF header")
    fun visionProjectorGb(size: Int) {
        file = ModelFile.VISION
        sizeBytes = size * gigabyte
        header = ggufHeader
    }

    @Given("a language model of {int} GB with GGUF architecture {string}")
    fun languageModelArchitecture(size: Int, architecture: String) {
        file = ModelFile.LANGUAGE
        sizeBytes = size * gigabyte
        header = ggufWithArchitecture(architecture)
    }

    /**
     * The packing question: a supported architecture says nothing about the tensor types inside,
     * so the pick-time scan is the only thing that can refuse `PQ2_0` before a huge load.
     */
    @Given("a language model of {int} GB with GGUF architecture {string} and tensor type {int}")
    fun languageModelTensorType(size: Int, architecture: String, typeId: Int) {
        file = ModelFile.LANGUAGE
        sizeBytes = size * gigabyte
        // A real GGUF v3 table, scanned exactly like the pick-time check scans a document.
        val bytes = ggufWithTensor(architecture, typeId)
        header = bytes
        tensors = GgufTensorScanner.scan(ByteArrayInputStream(bytes))
    }

    /** GGUF v3 with `general.architecture` and one tensor of [typeId]. */
    private fun ggufWithTensor(architecture: String, typeId: Int): ByteArray {
        val key = "general.architecture".toByteArray()
        val value = architecture.toByteArray()
        val name = "blk.0.attn_q.weight".toByteArray()
        val size = 4 + 4 + 8 + 8 +
            (8 + key.size + 4 + 8 + value.size) +
            (8 + name.size + 4 + 8 + 4 + 8)
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            .put("GGUF".toByteArray())
            .putInt(3)
            .putLong(1L)
            .putLong(1L)
            .putLong(key.size.toLong()).put(key)
            .putInt(8)
            .putLong(value.size.toLong()).put(value)
            .putLong(name.size.toLong()).put(name)
            .putInt(1)
            .putLong(1L)
            .putInt(typeId)
            .putLong(0L)
            .array()
    }

    @Given("a vision projector of {int} GB with GGUF architecture {string}")
    fun visionProjectorArchitecture(size: Int, architecture: String) {
        file = ModelFile.VISION
        sizeBytes = size * gigabyte
        header = ggufWithArchitecture(architecture)
    }

    @Given("a vision projector of {int} GB with GGUF architecture {string} and projector type {string}")
    fun visionProjectorWithType(size: Int, architecture: String, type: String) {
        file = ModelFile.VISION
        sizeBytes = size * gigabyte
        header = ggufWithMetadata(architecture, type)
    }

    /**
     * Pair scenarios: validate one file of each role and then ask the validator for the joint
     * verdict, which is what onboarding and the capture service actually gate on.
     */
    @Given("language weights {string} with projector type {string}")
    fun pair(languageArchitecture: String, projector: String) {
        val language = ModelValidator.validate(
            ModelFile.LANGUAGE,
            5 * gigabyte,
            ggufWithMetadata(languageArchitecture, null),
            20 * gigabyte,
        )
        val vision = ModelValidator.validate(
            ModelFile.VISION,
            gigabyte,
            ggufWithMetadata("clip", projector),
            20 * gigabyte,
        )
        world.validation = ModelValidator.validatePairOrReady(language, vision)
    }

    /**
     * A minimal GGUF v3 metadata prefix: magic, no tensors, one string key `general.architecture`.
     * This is the shape [ModelValidator] reads when deciding compatibility.
     */
    private fun ggufWithArchitecture(architecture: String): ByteArray =
        ggufWithMetadata(architecture, projectorType = null)

    /**
     * A GGUF v3 metadata prefix with `general.architecture` and, when [projectorType] is not null,
     * `clip.projector_type`. Both are the keys [ModelValidator] reads for a compatibility verdict.
     */
    private fun ggufWithMetadata(architecture: String, projectorType: String?): ByteArray {
        val entries = buildList {
            add("general.architecture" to architecture)
            projectorType?.let { add("clip.projector_type" to it) }
        }
        val size = 4 + 4 + 8 + 8 +
            entries.sumOf { (key, value) -> 8 + key.toByteArray().size + 4 + 8 + value.toByteArray().size }
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            .put("GGUF".toByteArray())
            .putInt(3)
            .putLong(0L)
            .putLong(entries.size.toLong())
        for ((key, value) in entries) {
            val keyBytes = key.toByteArray()
            val valueBytes = value.toByteArray()
            buffer.putLong(keyBytes.size.toLong()).put(keyBytes)
            buffer.putInt(8)
            buffer.putLong(valueBytes.size.toLong()).put(valueBytes)
        }
        return buffer.array()
    }

    @Given("{int} GB of free storage")
    fun freeStorageGb(size: Int) {
        freeBytes = size * gigabyte
        world.validation = ModelValidator.validate(file, sizeBytes, header, freeBytes, tensors)
    }

    @Given("{int} MB of free storage")
    fun freeStorageMb(size: Int) {
        freeBytes = size * megabyte
        world.validation = ModelValidator.validate(file, sizeBytes, header, freeBytes, tensors)
    }

    @Then("the model validation result is ready")
    fun validationReady() {
        assertThat(result()).isInstanceOf(ModelValidation.Ready::class.java)
    }

    @Then("the model validation reports {word}")
    fun validationReports(issue: String) {
        val validation = result()
        assertThat(validation).isInstanceOf(ModelValidation.Invalid::class.java)
        assertThat((validation as ModelValidation.Invalid).issue).isEqualTo(ModelIssue.valueOf(issue))
    }

    /** The rejection has to name the packing, because that is what the user can act on. */
    @Then("the model problem names {word}")
    fun problemNames(word: String) {
        val validation = result()
        assertThat(validation).isInstanceOf(ModelValidation.Invalid::class.java)
        assertThat((validation as ModelValidation.Invalid).detail.orEmpty()).contains(word)
    }

    /** Validates with the scenario's current file facts; the free-space step may have pre-computed it. */
    private fun result(): ModelValidation =
        world.validation ?: ModelValidator.validate(file, sizeBytes, header, freeBytes, tensors)
}
