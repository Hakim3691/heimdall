package com.heimdall.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * User-facing preferences.
 *
 * Kept intentionally tiny: Heimdall is silent-by-default, so there is almost nothing to configure.
 * The one opt-in is the expanded-help affordance, which is off by default because it is the only
 * touchable surface the app ever creates.
 */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Whether the opt-in, touchable "more" chip is offered alongside a tooltip. Default false. */
    var expandedHelpEnabled: Boolean
        get() = prefs.getBoolean(KEY_EXPANDED_HELP, false)
        set(value) = prefs.edit().putBoolean(KEY_EXPANDED_HELP, value).apply()

    /** True once the user has completed onboarding and started capture at least once. */
    var hasStartedCapture: Boolean
        get() = prefs.getBoolean(KEY_STARTED, false)
        set(value) = prefs.edit().putBoolean(KEY_STARTED, value).apply()

    private companion object {
        const val FILE = "heimdall_settings"
        const val KEY_EXPANDED_HELP = "expanded_help_enabled"
        const val KEY_STARTED = "has_started_capture"
    }
}
