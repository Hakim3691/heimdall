package com.heimdall.acceptance

import com.google.common.truth.Truth.assertThat
import com.heimdall.model.OnboardingState
import com.heimdall.model.OnboardingStep
import com.heimdall.model.PermissionState
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When

/** FR-01 / FR-04: onboarding readiness state machine. */
class OnboardingSteps(private val world: World) {
    @Given("a fresh install with no models")
    fun freshInstall() {
        world.onboardingState = OnboardingState()
    }

    @Given("overlay granted but nothing else")
    fun overlayOnly() {
        world.onboardingState = OnboardingState(
            permissions = PermissionState(overlayGranted = true),
            modelsReady = false,
        )
    }

    @Given("overlay and accessibility granted")
    fun overlayAndAccessibility() {
        world.onboardingState = OnboardingState(
            permissions = PermissionState(overlayGranted = true, accessibilityEnabled = true),
            modelsReady = false,
        )
    }

    @Given("every permission except projection")
    fun everyPermissionExceptProjection() {
        world.onboardingState = OnboardingState(
            permissions = PermissionState(
                overlayGranted = true,
                accessibilityEnabled = true,
                notificationsGranted = true,
                projectionConsentGranted = false,
            ),
            modelsReady = false,
        )
    }

    @Given("every permission and both models ready except projection")
    fun allButProjection() {
        world.onboardingState = OnboardingState(
            permissions = PermissionState(
                overlayGranted = true,
                accessibilityEnabled = true,
                notificationsGranted = true,
                projectionConsentGranted = false,
            ),
            modelsReady = true,
        )
    }

    @Given("permissions granted with models ready but no projection consent")
    fun readyWithoutProjection() {
        allButProjection()
    }

    @When("projection consent is granted")
    fun projectionGranted() {
        world.onboardingState = world.onboardingState.copy(
            permissions = world.onboardingState.permissions.copy(projectionConsentGranted = true),
        )
    }

    @Then("the next onboarding step is {word}")
    fun nextStepIs(step: String) {
        assertThat(world.onboardingState.nextStep()).isEqualTo(OnboardingStep.valueOf(step))
    }

    @Then("capture cannot start")
    fun captureCannotStart() {
        assertThat(world.onboardingState.canStartCapture).isFalse()
    }

    @Then("capture can start")
    fun captureCanStart() {
        assertThat(world.onboardingState.canStartCapture).isTrue()
    }
}
