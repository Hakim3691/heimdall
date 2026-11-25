package com.heimdall.acceptance

import com.google.common.truth.Truth.assertThat
import com.heimdall.model.BlockReason
import com.heimdall.model.GateResult
import com.heimdall.testing.SyntheticFrames
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When

/** FR-02: three-layer trigger cascade. */
class TriggerSteps(private val world: World) {
    @Given("a fresh Heimdall trigger cascade")
    fun freshCascade() {
        // World constructs a fresh cascade per scenario; nothing to reset.
        assertThat(world.cascade.hesitation.isArmed).isFalse()
    }

    @Given("the gyroscope reports a motionless device")
    fun motionlessDevice() {
        // Constant magnitude == zero variance == phone is lying still.
        repeat(SAMPLES) { world.cascade.onMagnitude(0.002f) }
        assertThat(world.cascade.motion.isHandheldActive).isFalse()
    }

    @Given("the gyroscope reports a handheld micro-tremor")
    fun handheldTremor() {
        // Alternating 0.10 / 0.50 rad/s => variance 0.04 (rad/s)^2, inside the 0.02..1.20 band.
        repeat(SAMPLES) { index -> world.cascade.onMagnitude(if (index % 2 == 0) 0.10f else 0.50f) }
        assertThat(world.cascade.motion.isHandheldActive).isTrue()
    }

    @Given("the gyroscope reports a device in motion")
    fun deviceInMotion() {
        // Alternating 0 / 5 rad/s => variance 6.25, far above the band.
        repeat(SAMPLES) { index -> world.cascade.onMagnitude(if (index % 2 == 0) 0f else 5f) }
        assertThat(world.cascade.motion.isHandheldActive).isFalse()
    }

    @Given("the user has not interacted for {int} seconds")
    fun noInteractionFor(seconds: Int) {
        world.cascade.onInteraction()
        world.clock.advance(seconds * 1_000L)
    }

    @Given("the user tapped {int} second ago")
    @Given("the user tapped {int} seconds ago")
    fun tappedAgo(seconds: Int) {
        world.cascade.onInteraction()
        world.clock.advance(seconds * 1_000L)
    }

    @When("the user taps now")
    fun tapsNow() {
        world.cascade.onInteraction()
    }

    @When("{int} more second passes")
    @When("{int} more seconds pass")
    fun moreTimePasses(seconds: Int) {
        world.clock.advance(seconds * 1_000L)
    }

    @Given("two identical frames have been captured")
    fun twoIdenticalFrames() {
        world.cascade.onFrame(SyntheticFrames.flat())
        world.observation = world.cascade.onFrame(SyntheticFrames.flat())
        assertThat(world.observation?.hammingDistance).isEqualTo(0)
    }

    @Given("no frame has been compared yet")
    fun noFrameCompared() {
        world.observation = world.cascade.onFrame(SyntheticFrames.flat())
        assertThat(world.observation?.hammingDistance).isNull()
    }

    @Given("two frames differ beyond the stability threshold")
    fun twoDifferentFrames() {
        world.cascade.onFrame(SyntheticFrames.flat())
        world.observation = world.cascade.onFrame(SyntheticFrames.ramp(invert = true))
        assertThat(world.observation?.stable).isFalse()
    }

    @When("a third identical frame is captured")
    fun thirdIdenticalFrame() {
        world.observation = world.cascade.onFrame(SyntheticFrames.ramp(invert = true))
        assertThat(world.observation?.stable).isTrue()
    }

    @When("the cascade is evaluated")
    fun evaluate() {
        world.gateResult = world.cascade.evaluate()
    }

    @When("the cascade passes and a tooltip is rendered")
    fun cascadePassesAndRenders() {
        val result = world.cascade.evaluate()
        assertThat(result).isInstanceOf(GateResult.Passed::class.java)
        world.cascade.onInterventionRendered()
    }

    @Then("the cascade passes")
    fun cascadePasses() {
        assertThat(world.cascade.evaluate()).isInstanceOf(GateResult.Passed::class.java)
    }

    @Then("the cascade blocks with reason {word}")
    fun cascadeBlocks(reason: String) {
        val result = world.cascade.evaluate()
        assertThat(result).isInstanceOf(GateResult.Blocked::class.java)
        val blocked = result as GateResult.Blocked
        assertThat(blocked.reason).isEqualTo(BlockReason.valueOf(reason))
    }

    private companion object {
        const val SAMPLES = 40
    }
}
