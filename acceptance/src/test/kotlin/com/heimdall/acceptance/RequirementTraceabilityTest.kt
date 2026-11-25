package com.heimdall.acceptance

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * Traceability gate: a P0 requirement without an executable scenario fails the build.
 *
 * This is what keeps the PRD honest over time. Adding FR-08 to the specification means adding a
 * feature tagged `@FR-08` in the same change, or CI refuses the release.
 */
class RequirementTraceabilityTest {
    private val requiredTags = setOf(
        "FR-01", "FR-02", "FR-03", "FR-04", "FR-05", "FR-06", "FR-07", "NFR-1",
    )

    @Test
    fun `every P0 requirement has at least one scenario`() {
        val tags = featureTags()
        assertThat(tags).containsAtLeastElementsIn(requiredTags)
    }

    @Test
    fun `no scenario is tagged with an unknown requirement`() {
        val unknown = featureTags() - requiredTags
        assertThat(unknown).isEmpty()
    }

    @Test
    fun `every feature file declares at least one scenario`() {
        featureFiles().forEach { file ->
            val text = file.readText()
            assertThat(text).contains("Scenario")
        }
    }

    private fun featureTags(): Set<String> = featureFiles()
        .joinToString("\n") { it.readText() }
        .let { text ->
            Regex("@((?:FR|NFR)-\\d+(?:-\\d+)?)").findAll(text).map { it.groupValues[1] }.toSet()
        }

    private fun featureFiles(): List<File> {
        val location = requireNotNull(javaClass.classLoader?.getResource("features")) {
            "features directory is missing from the test classpath"
        }
        return File(location.toURI()).listFiles { file -> file.extension == "feature" }
            ?.sorted()
            .orEmpty()
            .also { assertThat(it).isNotEmpty() }
    }
}
