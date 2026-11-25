package com.heimdall.model

/**
 * Thermal duty-cycle tiers.
 *
 * Mirrors `PowerManager.THERMAL_STATUS_*` so the governor's policy is a pure function that can be
 * unit-tested without an Android device. Intervals come from TRD §9.
 */
enum class ThermalTier(
    /** Minimum spacing between inference runs while this tier is active. */
    val dutyIntervalMs: Long,
    /** When true, inference is suspended entirely until the device cools. */
    val paused: Boolean,
) {
    NONE(dutyIntervalMs = 10_000L, paused = false),
    LIGHT(dutyIntervalMs = 15_000L, paused = false),
    MODERATE(dutyIntervalMs = 30_000L, paused = false),
    SEVERE(dutyIntervalMs = Long.MAX_VALUE, paused = true),
    CRITICAL(dutyIntervalMs = Long.MAX_VALUE, paused = true),
    SHUTDOWN(dutyIntervalMs = Long.MAX_VALUE, paused = true),
    ;

    companion object {
        // Values are the stable platform constants (API 29+) rather than android.os references so
        // this module stays a pure JVM library.
        const val PLATFORM_NONE = 0
        const val PLATFORM_LIGHT = 1
        const val PLATFORM_MODERATE = 2
        const val PLATFORM_SEVERE = 3
        const val PLATFORM_CRITICAL = 4
        const val PLATFORM_EMERGENCY = 5
        const val PLATFORM_SHUTDOWN = 6

        /** Unknown status codes are treated as MODERATE: keep working, but slow down. */
        fun fromPlatformStatus(status: Int): ThermalTier = when (status) {
            PLATFORM_NONE -> NONE
            PLATFORM_LIGHT -> LIGHT
            PLATFORM_MODERATE -> MODERATE
            PLATFORM_SEVERE -> SEVERE
            PLATFORM_CRITICAL -> CRITICAL
            PLATFORM_EMERGENCY, PLATFORM_SHUTDOWN -> SHUTDOWN
            else -> MODERATE
        }
    }
}
