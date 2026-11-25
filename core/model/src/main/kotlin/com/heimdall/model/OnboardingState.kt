package com.heimdall.model

/**
 * The set of privileges Heimdall needs before it can run ambiently.
 *
 * None of these can be granted silently — Android requires a user gesture for each — so onboarding
 * is a state machine over this object rather than a one-shot permission dialog. The consent for
 * `MediaProjection` is per capture session by platform rule and cannot be persisted.
 */
data class PermissionState(
    val overlayGranted: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val projectionConsentGranted: Boolean = false,
    val notificationsGranted: Boolean = false,
) {
    val allGranted: Boolean
        get() = overlayGranted && accessibilityEnabled && projectionConsentGranted && notificationsGranted

    /** Missing privileges in the order the onboarding UI should ask for them. */
    fun missing(): List<OnboardingStep> = buildList {
        if (!overlayGranted) add(OnboardingStep.OVERLAY)
        if (!accessibilityEnabled) add(OnboardingStep.ACCESSIBILITY)
        if (!notificationsGranted) add(OnboardingStep.NOTIFICATIONS)
        if (!projectionConsentGranted) add(OnboardingStep.PROJECTION)
    }
}

/** Onboarding steps, ordered by least to most invasive. */
enum class OnboardingStep {
    OVERLAY,
    ACCESSIBILITY,
    NOTIFICATIONS,
    PROJECTION,
    MODELS,

    /** Everything granted: the ambient service may start. */
    DONE,
}

/**
 * Readiness of the whole installation: privileges *and* model weights.
 *
 * Model availability is part of the same gate because starting capture without weights would burn
 * battery for nothing.
 */
data class OnboardingState(
    val permissions: PermissionState = PermissionState(),
    val modelsReady: Boolean = false,
) {
    /** The next thing the user must do; [OnboardingStep.DONE] when nothing is missing. */
    fun nextStep(): OnboardingStep {
        if (!permissions.overlayGranted) return OnboardingStep.OVERLAY
        if (!permissions.accessibilityEnabled) return OnboardingStep.ACCESSIBILITY
        if (!permissions.notificationsGranted) return OnboardingStep.NOTIFICATIONS
        if (!modelsReady) return OnboardingStep.MODELS
        // Projection consent is requested last, immediately before the service starts, because the
        // platform ties it to a live capture session.
        if (!permissions.projectionConsentGranted) return OnboardingStep.PROJECTION
        return OnboardingStep.DONE
    }

    val canStartCapture: Boolean get() = nextStep() == OnboardingStep.DONE
}
