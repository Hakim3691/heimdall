package com.heimdall.engine.trigger

import com.heimdall.model.TriggerConfig

/**
 * Stage 2 of the trigger cascade: UI hesitation tracking (TRD §6.2).
 *
 * A user who has not tapped, scrolled, typed or caused a window change for
 * [TriggerConfig.hesitationThresholdMs] is hesitating. The tracker is unarmed until the first
 * interaction arrives so a freshly started service cannot fire on a screen nobody has touched yet.
 */
class HesitationTracker(private val config: TriggerConfig) {
    private var lastInteractionMs: Long = 0L

    /** True after at least one qualifying accessibility interaction has been observed. */
    var isArmed: Boolean = false
        private set

    /** Timestamp of the most recent interaction, or null before the first one. */
    val lastInteractionAtMs: Long? get() = if (isArmed) lastInteractionMs else null

    /** Records a user interaction (tap, scroll, text edit, window change). */
    fun onInteraction(nowMs: Long) {
        lastInteractionMs = nowMs
        isArmed = true
    }

    /** True when the interaction that started this hesitation window arrived at least the threshold ago. */
    fun isHesitating(nowMs: Long): Boolean {
        if (!isArmed) return false
        val elapsed = nowMs - lastInteractionMs
        return elapsed >= config.hesitationThresholdMs
    }

    /** Remaining time until the current hesitation window opens, 0 when already hesitating. */
    fun timeUntilHesitationMs(nowMs: Long): Long {
        if (!isArmed) return config.hesitationThresholdMs
        return (config.hesitationThresholdMs - (nowMs - lastInteractionMs)).coerceAtLeast(0L)
    }

    /** Resets the window without disarming, used after rendering a tooltip. */
    fun restart(nowMs: Long) {
        if (isArmed) lastInteractionMs = nowMs
    }

    fun reset() {
        isArmed = false
        lastInteractionMs = 0L
    }
}
