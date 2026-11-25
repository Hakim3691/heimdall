package com.heimdall.common

/**
 * Monotonic millisecond clock.
 *
 * Every timing decision in Heimdall (hesitation, cooldown, duty cycle, auto-dismiss) reads time
 * through this seam so the BDD suite can advance time deterministically instead of sleeping.
 */
fun interface Clock {
    fun nowMs(): Long

    companion object {
        /**
         * Production clock. Uses `System.nanoTime()` so it is immune to wall-clock changes
         * (NTP jumps, user editing the date) — all Heimdall deadlines are durations, not dates.
         */
        val MONOTONIC: Clock = Clock { System.nanoTime() / 1_000_000L }
    }
}
