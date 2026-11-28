package com.heimdall.model

/**
 * Tuning constants for the three-layer trigger cascade (TRD §6).
 *
 * Every threshold is data, not a literal buried in a detector, so field calibration only has to
 * change this object and the BDD table.
 */
data class TriggerConfig(
    /** Number of gyroscope magnitude samples in the variance window (TRD: 20). */
    val gyroSampleCount: Int = 20,
    /** Lower variance bound (rad/s)^2 — below this the device is resting / mounted. */
    val gyroVarianceMin: Float = 0.02f,
    /** Upper variance bound (rad/s)^2 — above this the device is being moved deliberately. */
    val gyroVarianceMax: Float = 1.20f,
    /**
     * A single sample (rad/s) large enough to prove a hand is holding the device.
     *
     * The variance band alone cannot see a phone that is held still: a steady grip reports a
     * variance around 0.001–0.003 (rad/s)^2, well under the 0.02 floor, while a phone lying on a
     * table reports both a low variance *and* a low peak. A hand always produces small corrections
     * of a few degrees per second, so a peak in the window above this floor is what distinguishes
     * "held" from "mounted or on a desk". Above [gyroVarianceMax] the device counts as carried or
     * deliberately moved, regardless of its peak.
     */
    val gyroMovementThreshold: Float = 0.08f,
    /** Uninterrupted UI inactivity that counts as hesitation (TRD: 1.5 s). */
    val hesitationThresholdMs: Long = 1_500L,
    /** Consecutive-frame dHash Hamming distance at or below which the screen counts as static. */
    val frameHashMaxHammingDistance: Int = 2,
    /** After rendering a tooltip, suppress further inference for this long. */
    val postInterventionCooldownMs: Long = 20_000L,
    /**
     * When the accessibility stream is unavailable, a frame that changes beyond the stability
     * threshold counts as a user interaction and restarts the hesitation window. Without this the
     * cascade could never leave the hesitation gate on a device where the service is blocked.
     */
    val frameChangeArmsHesitation: Boolean = true,
    /** Sensor sampling period hint in microseconds (SENSOR_DELAY_UI ≈ 60 ms). */
    val gyroSamplingPeriodUs: Int = 60_000,
) {
    init {
        require(gyroSampleCount > 1) { "gyro sample window must hold at least two samples" }
        require(gyroVarianceMin >= 0f) { "variance floor must be non-negative" }
        require(gyroVarianceMax > gyroVarianceMin) { "variance ceiling must exceed the floor" }
        require(gyroMovementThreshold >= 0f) { "movement threshold must be non-negative" }
        require(hesitationThresholdMs > 0L) { "hesitation threshold must be positive" }
        require(frameHashMaxHammingDistance in 0..64) { "dHash distance must fit a 64-bit hash" }
        require(postInterventionCooldownMs >= 0L) { "cooldown must be non-negative" }
    }
}

/** Overlay presentation policy (FR-01). */
data class OverlayConfig(
    /** Auto-dismiss delay (TRD: 4.5 s). */
    val autoDismissMs: Long = 4_500L,
    /** Vertical inset from the anchored screen edge, in dp (TRD: 120). */
    val verticalInsetDp: Int = 120,
    /** Maximum words in a tooltip (PRD: <= 15). */
    val maxWordCount: Int = 15,
    /** Corner radius of the glass pill, in dp. */
    val cornerRadiusDp: Float = 22f,
    /** If true, a separate opt-in touchable chip offers expanded help. Off by default. */
    val expandedHelpEnabled: Boolean = false,
    /**
     * Opacity of the glass scrim behind a hint, 0.2–1.0 (the user's slider).
     *
     * Only the background fades: the text and the accent bar stay fully opaque, because a hint that
     * is 40% opaque over a bright page would be unreadable rather than subtle.
     */
    val opacity: Float = 0.9f,
) {
    init {
        require(autoDismissMs > 0L) { "auto-dismiss must be positive" }
        require(maxWordCount > 0) { "word budget must be positive" }
        require(verticalInsetDp >= 0) { "inset must be non-negative" }
        require(opacity in 0.2f..1f) { "overlay opacity must be within 0.2..1" }
    }
}

/** Local VLM runtime configuration (FR-03, FR-05). */
data class VlmConfig(
    /** Interventions below this self-reported confidence are dropped (FR-05). */
    val confidenceThreshold: Float = 0.55f,
    /** llama.cpp context window. */
    val contextSize: Int = 2_048,
    /** Worker threads for decode; kept low to respect the power budget. */
    val threads: Int = 4,
    /** Layers offloaded to the Vulkan backend; 99 == all. */
    val gpuLayers: Int = 99,
    /** Square edge length the frame is rescaled to before vision encoding. */
    val imageSize: Int = 448,
    /** Hard cap on generated tokens; the response is a small JSON object. */
    val maxOutputTokens: Int = 96,
    /** Abandon inference that exceeds this wall-clock budget. */
    val inferenceTimeoutMs: Long = 12_000L,
    /** Deterministic decoding: same screen, same answer. */
    val temperature: Float = 0.2f,
) {
    init {
        require(confidenceThreshold in 0f..1f) { "confidence threshold must be within 0..1" }
        require(contextSize > 0) { "context size must be positive" }
        require(threads > 0) { "thread count must be positive" }
        require(imageSize > 0) { "image size must be positive" }
    }
}

/** Screen capture / pipeline configuration (FR-04). */
data class CaptureConfig(
    /** Longest edge of the captured frame before it is handed to the trigger engine. */
    val analysisMaxEdgePx: Int = 480,
    /** Target capture cadence while the cascade is armed. */
    val captureIntervalMs: Long = 500L,
    /** Android 14+ requires the projection foreground service notification to stay visible. */
    val notificationId: Int = 0x4845494D,
) {
    init {
        require(analysisMaxEdgePx >= 9) { "analysis frame must be at least the dHash grid" }
        require(captureIntervalMs > 0L) { "capture interval must be positive" }
    }
}

/**
 * Aggregate configuration object handed to the service graph.
 *
 * Defaults are the production values from the PRD/TRD; tests construct explicit instances.
 */
data class HeimdallConfig(
    val trigger: TriggerConfig = TriggerConfig(),
    val overlay: OverlayConfig = OverlayConfig(),
    val vlm: VlmConfig = VlmConfig(),
    val capture: CaptureConfig = CaptureConfig(),
)
