package com.heimdall.acceptance

import com.google.common.truth.Truth.assertThat
import com.heimdall.engine.trigger.FrameDiffEngine
import com.heimdall.model.RgbImage
import com.heimdall.testing.SyntheticFrames
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When

/** FR-02 stage 3: dHash computations. */
class FrameStabilitySteps(private val world: World) {
    private val maxDistance = world.triggerConfig.frameHashMaxHammingDistance

    @Given("a uniform grey frame")
    fun uniformFrame() {
        world.image = SyntheticFrames.flat()
    }

    @Given("an inverted horizontal brightness ramp")
    fun invertedRamp() {
        world.secondaryImage = SyntheticFrames.ramp(invert = true)
        world.secondarySignature = FrameDiffEngine.signature(world.secondaryImage!!)
        if (world.image == null) {
            world.image = world.secondaryImage
            world.signature = world.secondarySignature
        }
    }

    @Given("a horizontal brightness ramp")
    fun ramp() {
        world.image = SyntheticFrames.ramp()
        world.signature = FrameDiffEngine.signature(world.image!!)
    }

    @Given("a horizontal brightness ramp rendered at {int} pixels wide")
    fun rampAtWidth(width: Int) {
        val image = SyntheticFrames.ramp(width = width, height = 32)
        if (world.image == null) {
            world.image = image
            world.signature = FrameDiffEngine.signature(image)
        } else {
            world.secondaryImage = image
            world.secondarySignature = FrameDiffEngine.signature(image)
        }
    }

    @Given("the same frame with a small bright patch")
    fun patchedFrame() {
        world.secondaryImage = SyntheticFrames.flatWithPatch()
    }

    @When("the frame is hashed twice")
    fun hashTwice() {
        val image: RgbImage = world.image ?: SyntheticFrames.flat()
        world.signature = FrameDiffEngine.signature(image)
        world.secondarySignature = FrameDiffEngine.signature(image)
    }

    @Then("both signatures are identical")
    fun signaturesIdentical() {
        assertThat(world.secondarySignature).isEqualTo(world.signature)
    }

    @Then("the Hamming distance is {int}")
    fun hammingDistanceIs(distance: Int) {
        val first = requireNotNull(world.signature)
        val second = requireNotNull(world.secondarySignature)
        assertThat(first.hammingTo(second)).isEqualTo(distance)
    }

    @Then("the frame signature is zero")
    fun signatureIsZero() {
        val signature = world.signature ?: FrameDiffEngine.signature(requireNotNull(world.image))
        assertThat(signature.bits).isEqualTo(0L)
    }

    @Then("the frame signature has all 64 bits set")
    fun signatureAllBits() {
        val signature = world.secondarySignature ?: FrameDiffEngine.signature(requireNotNull(world.secondaryImage))
        assertThat(signature.bits).isEqualTo(-1L)
    }

    @Then("the two signatures differ in every bit")
    fun differInEveryBit() {
        val first = requireNotNull(world.signature)
        val second = requireNotNull(world.secondarySignature)
        assertThat(first.hammingTo(second)).isEqualTo(64)
    }

    @Then("the two frames are considered static")
    fun framesStatic() {
        val first = FrameDiffEngine.signature(requireNotNull(world.image))
        val second = FrameDiffEngine.signature(requireNotNull(world.secondaryImage))
        assertThat(FrameDiffEngine.isStatic(first, second, maxDistance)).isTrue()
    }

    @Then("the two frames are not considered static")
    fun framesNotStatic() {
        val first = FrameDiffEngine.signature(requireNotNull(world.image))
        val second = FrameDiffEngine.signature(requireNotNull(world.secondaryImage))
        assertThat(FrameDiffEngine.isStatic(first, second, maxDistance)).isFalse()
    }
}
