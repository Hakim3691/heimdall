package com.heimdall.model

/**
 * The set of privileges Heimdall needs before it can run ambiently.
 *
 * None of these can be granted silently — Android requires a user gesture for each — so onboarding
 * is a state machine over this object rather than a one-shot permission dialog. The consent for
 * `MediaProjection` is per capture session by platform rule and cannot be persisted.
 *
 * Accessibility is **optional**: Android 13+ blocks the toggle for sideloaded apps until restricted
 * settings are allowed, and that is not something an app can do for itself. Heimdall therefore must
 * still work without the service, using frame changes as its interaction signal (see
 * `TriggerCascade`). The field stays so onboarding can recommend enabling it.
 */
data class PermissionState(
    val overlayGranted: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val projectionConsentGranted: Boolean = false,
    val notificationsGranted: Boolean = false,
) {
    /** Privileges that block capture. Accessibility is deliberately not one of them. */
    val requiredGranted: Boolean
        get() = overlayGranted && projectionConsentGranted && notificationsGranted

    /** True when the optional accessibility enhancement is still missing. */
    val accessibilityRecommended: Boolean
        get() = !accessibilityEnabled

    /** Missing required privileges in the order the onboarding UI should ask for them. */
    fun missing(): List<OnboardingStep> = buildList {
        if (!overlayGranted) add(OnboardingStep.OVERLAY)
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
 * Readiness of the whole installation: required privileges *and* model weights.
 *
 * Model availability is part of the same gate because starting capture without weights would burn
 * battery for nothing. Accessibility is recommended but never blocks: the capture service falls
 * back to frame-change activity when the accessibility stream is unavailable.
 */
data class OnboardingState(
    val permissions: PermissionState = PermissionState(),
    val modelsReady: Boolean = false,
) {
    /** The next required thing to do; [OnboardingStep.DONE] when nothing required is missing. */
    fun nextStep(): OnboardingStep {
        if (!permissions.overlayGranted) return OnboardingStep.OVERLAY
        if (!permissions.notificationsGranted) return OnboardingStep.NOTIFICATIONS
        if (!modelsReady) return OnboardingStep.MODELS
        // Projection consent is requested last, immediately before the service starts, because the
        // platform ties it to a live capture session.
        if (!permissions.projectionConsentGranted) return OnboardingStep.PROJECTION
        return OnboardingStep.DONE
    }

    val canStartCapture: Boolean get() = nextStep() == OnboardingStep.DONE

    /** Whether onboarding should keep recommending the optional accessibility service. */
    val accessibilityRecommended: Boolean get() = permissions.accessibilityRecommended
}
