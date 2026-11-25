package com.heimdall.acceptance

import com.google.common.truth.Truth.assertThat
import com.heimdall.model.Anchor
import com.heimdall.model.RejectReason
import com.heimdall.model.TextMetrics
import com.heimdall.model.VlmOutcome
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When

/** FR-05: output schema gate. */
class GatingSteps(private val world: World) {
    @Given("an intervention gate with default thresholds")
    fun defaultGate() {
        world.vlmConfig = com.heimdall.model.VlmConfig()
        world.overlayConfig = com.heimdall.model.OverlayConfig()
    }

    @When("the model answers:")
    fun modelAnswers(answer: String) {
        world.rawAnswer = answer
        world.outcome = world.gate().gate(answer)
    }

    @When("the model answers with {int} words")
    fun modelAnswersWithWords(words: Int) {
        val text = (1..words).joinToString(" ") { "word$it" }
        world.rawAnswer = """{"intervene": true, "confidence": 0.9, "text": "$text"}"""
        world.outcome = world.gate().gate(world.rawAnswer)
    }

    @Then("the gate returns an intervention")
    fun interventionReturned() {
        assertThat(world.outcome).isInstanceOf(VlmOutcome.Intervene::class.java)
    }

    @Then("the gate returns silence with reason {word}")
    fun silenceWithReason(reason: String) {
        val outcome = world.outcome
        assertThat(outcome).isInstanceOf(VlmOutcome.Silent::class.java)
        assertThat((outcome as VlmOutcome.Silent).reason).isEqualTo(RejectReason.valueOf(reason))
    }

    @Then("the intervention anchor is {word}")
    fun interventionAnchor(anchor: String) {
        val intervention = (world.outcome as VlmOutcome.Intervene).intervention
        assertThat(intervention.anchor).isEqualTo(Anchor.valueOf(anchor))
    }

    @Then("the intervention has {int} words")
    @Then("the intervention has at least {int} words")
    fun interventionHasWords(words: Int) {
        val intervention = (world.outcome as VlmOutcome.Intervene).intervention
        assertThat(TextMetrics.countWords(intervention.text)).isEqualTo(words)
    }

    @Then("the intervention text is:")
    fun interventionText(expected: String) {
        val intervention = (world.outcome as VlmOutcome.Intervene).intervention
        assertThat(intervention.text).isEqualTo(expected)
    }
}
