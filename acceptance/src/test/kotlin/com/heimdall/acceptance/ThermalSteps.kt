package com.heimdall.acceptance

import com.google.common.truth.Truth.assertThat
import com.heimdall.model.ThermalTier
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When

/** FR-06 / NFR-1: thermal duty-cycle governance. */
class ThermalSteps(private val world: World) {
    @Given("a fresh thermal governor")
    fun freshGovernor() {
        world.governor.reset()
    }

    @When("the platform reports thermal status {int}")
    fun platformStatus(status: Int) {
        world.governor.onThermalStatus(status)
    }

    @Then("the governor tier is {word}")
    fun tierIs(tier: String) {
        assertThat(world.governor.tier).isEqualTo(ThermalTier.valueOf(tier))
    }

    @Then("the duty interval is {word} milliseconds")
    fun dutyIntervalIs(interval: String) {
        val expected = if (interval == "paused") Long.MAX_VALUE else interval.toLong()
        assertThat(world.governor.dutyIntervalMs).isEqualTo(expected)
    }

    @Then("inference is running")
    fun inferenceRunning() {
        assertThat(world.governor.isExecutionPaused).isFalse()
        assertThat(world.governor.canRunNow()).isTrue()
    }

    @Then("inference is paused")
    fun inferencePaused() {
        assertThat(world.governor.isExecutionPaused).isTrue()
        assertThat(world.governor.canRunNow()).isFalse()
    }

    @Given("the governor allows a run now")
    fun allowsRunNow() {
        world.governor.reset()
        assertThat(world.governor.canRunNow()).isTrue()
    }

    @When("an inference run is consumed")
    fun inferenceConsumed() {
        world.governor.onInferenceConsumed()
    }

    @Then("the governor blocks the next run with {long} milliseconds remaining")
    fun blocksWithRemaining(expected: Long) {
        world.remainingWaitMs = world.governor.cooldownRemainingMs()
        assertThat(world.governor.canRunNow()).isFalse()
        assertThat(world.remainingWaitMs).isEqualTo(expected)
    }

    @When("{int} seconds pass")
    fun secondsPass(seconds: Int) {
        world.clock.advance(seconds * 1_000L)
    }

    @Then("the governor permits a run")
    fun governorAllowsRunNow() {
        assertThat(world.governor.canRunNow()).isTrue()
    }
}
