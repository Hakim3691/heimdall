package com.heimdall.testing

import com.heimdall.common.Clock

/**
 * Deterministic clock for tests and BDD scenarios.
 *
 * Time only moves when a test calls [advance]; nothing in Heimdall reads the real clock during a
 * scenario, which is what makes "1.5 s of hesitation" and "4.5 s auto-dismiss" verifiable.
 */
class FakeClock(startMs: Long = 1_000_000L) : Clock {
    var currentMs: Long = startMs
        private set

    override fun nowMs(): Long = currentMs

    fun advance(deltaMs: Long): Long {
        require(deltaMs >= 0) { "cannot move time backwards" }
        currentMs += deltaMs
        return currentMs
    }

    fun advanceTo(targetMs: Long) {
        require(targetMs >= currentMs) { "cannot move time backwards" }
        currentMs = targetMs
    }
}
