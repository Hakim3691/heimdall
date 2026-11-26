package com.heimdall.acceptance

import com.google.common.truth.Truth.assertThat
import com.heimdall.model.ModelFile
import com.heimdall.model.ModelIssue
import com.heimdall.model.ModelValidation
import com.heimdall.model.ModelValidator
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
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

    @Given("a vision projector of {int} GB with GGUF architecture {string}")
    fun visionProjectorArchitecture(size: Int, architecture: String) {
        file = ModelFile.VISION
        sizeBytes = size * gigabyte
        header = ggufWithArchitecture(architecture)
    }

    /**
     * A minimal GGUF v3 metadata prefix: magic, no tensors, one string key `general.architecture`.
     * This is the shape [ModelValidator] reads when deciding compatibility.
     */
    private fun ggufWithArchitecture(architecture: String): ByteArray {
        val key = "general.architecture".toByteArray()
        val value = architecture.toByteArray()
        return ByteBuffer.allocate(4 + 4 + 8 + 8 + 8 + key.size + 4 + 8 + value.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("GGUF".toByteArray())
            .putInt(3)
            .putLong(0L)
            .putLong(1L)
            .putLong(key.size.toLong())
            .put(key)
            .putInt(8)
            .putLong(value.size.toLong())
            .put(value)
            .array()
    }

    @Given("{int} GB of free storage")
    fun freeStorageGb(size: Int) {
        freeBytes = size * gigabyte
        world.validation = ModelValidator.validate(file, sizeBytes, header, freeBytes)
    }

    @Given("{int} MB of free storage")
    fun freeStorageMb(size: Int) {
        freeBytes = size * megabyte
        world.validation = ModelValidator.validate(file, sizeBytes, header, freeBytes)
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

    /** Validates with the scenario's current file facts; the free-space step may have pre-computed it. */
    private fun result(): ModelValidation =
        world.validation ?: ModelValidator.validate(file, sizeBytes, header, freeBytes)
}
