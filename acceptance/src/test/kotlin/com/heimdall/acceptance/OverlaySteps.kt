package com.heimdall.acceptance

import com.google.common.truth.Truth.assertThat
import com.heimdall.model.Anchor
import com.heimdall.model.Intervention
import com.heimdall.model.OverlayConfig
import com.heimdall.model.OverlayPolicy
import com.heimdall.model.TextMetrics
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then

/** FR-01: tooltip presentation policy (the pure half of the overlay contract). */
class OverlaySteps(private val world: World) {
    @Given("the default overlay configuration")
    fun defaultConfig() {
        world.overlayConfig = OverlayConfig()
    }

    @Given("overlay configuration with expanded help enabled")
    fun expandedHelpConfig() {
        world.overlayConfig = OverlayConfig(expandedHelpEnabled = true)
    }

    @Given("overlay configuration with {int}% opacity")
    fun opacityConfig(percent: Int) {
        world.overlayConfig = OverlayConfig(opacity = percent / 100f)
    }

    @Given("an intervention anchored {word}")
    fun interventionAnchored(anchor: String) {
        val intervention = Intervention(
            text = "Tap Allow to continue",
            confidence = 0.9f,
            anchor = Anchor.valueOf(anchor),
        )
        world.spec = OverlayPolicy.specFor(intervention, world.overlayConfig)
    }

    @Given("an intervention with line breaks in its text")
    fun interventionWithLineBreaks() {
        val intervention = Intervention(
            text = "Tap Allow\nto grant access",
            confidence = 0.9f,
        )
        world.spec = OverlayPolicy.specFor(intervention, world.overlayConfig)
    }

    @Given("an intervention with {int} words")
    fun interventionWithWords(words: Int) {
        val text = (1..words).joinToString(" ") { "hint$it" }
        val intervention = Intervention(text = text, confidence = 0.9f)
        world.spec = OverlayPolicy.specFor(intervention, world.overlayConfig)
    }

    @Then("the overlay spec is not touchable")
    fun notTouchable() {
        assertThat(requireNotNull(world.spec).touchable).isFalse()
    }

    @Then("the overlay spec dismisses after {long} milliseconds")
    fun dismissesAfter(millis: Long) {
        assertThat(requireNotNull(world.spec).autoDismissMs).isEqualTo(millis)
    }

    @Then("the overlay spec uses a {int} dp inset")
    fun insetDp(inset: Int) {
        assertThat(requireNotNull(world.spec).verticalInsetDp).isEqualTo(inset)
    }

    @Then("the overlay spec is anchored {word}")
    fun anchored(anchor: String) {
        assertThat(requireNotNull(world.spec).anchor).isEqualTo(Anchor.valueOf(anchor))
    }

    @Then("expanded help is unavailable")
    fun expandedHelpUnavailable() {
        assertThat(requireNotNull(world.spec).expandedHelpAvailable).isFalse()
    }

    @Then("expanded help is available")
    fun expandedHelpAvailable() {
        assertThat(requireNotNull(world.spec).expandedHelpAvailable).isTrue()
    }

    @Then("the overlay spec is {int}% opaque")
    fun opacity(percent: Int) {
        assertThat(requireNotNull(world.spec).opacity).isEqualTo(percent / 100f)
    }

    @Then("the overlay spec is still not touchable")
    fun stillNotTouchable() {
        assertThat(requireNotNull(world.spec).touchable).isFalse()
    }

    @Then("the overlay text contains no line breaks")
    fun noLineBreaks() {
        assertThat(requireNotNull(world.spec).text).doesNotContain("\n")
    }

    @Then("the overlay text has at most {int} words")
    fun atMostWords(max: Int) {
        val spec = requireNotNull(world.spec)
        assertThat(TextMetrics.countWords(spec.text)).isAtMost(max)
        assertThat(spec.wordCount).isAtMost(OverlayPolicy.MAX_WORDS)
    }
}
