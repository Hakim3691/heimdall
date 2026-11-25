package com.heimdall.settings

import android.content.Context
import android.content.SharedPreferences
import com.heimdall.model.ModelRole

/**
 * User-facing preferences.
 *
 * Kept intentionally tiny: Heimdall is silent-by-default, so there is almost nothing to configure.
 * The opt-in is the expanded-help affordance; the rest is setup state the onboarding screen reads
 * back (which model document was picked, whether the notification prompt already ran).
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

    /** True once the notification permission dialog has been shown; avoids nagging every launch. */
    var notificationPromptRequested: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_PROMPTED, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATION_PROMPTED, value).apply()

    /** Persisted Storage Access Framework document for a model role, or null when unset. */
    fun modelUri(role: ModelRole): String? = prefs.getString(KEY_MODEL_URI + role.name, null)

    fun setModelUri(role: ModelRole, uri: String?) {
        prefs.edit().apply {
            if (uri == null) remove(KEY_MODEL_URI + role.name) else putString(KEY_MODEL_URI + role.name, uri)
        }.apply()
    }

    private companion object {
        const val FILE = "heimdall_settings"
        const val KEY_EXPANDED_HELP = "expanded_help_enabled"
        const val KEY_STARTED = "has_started_capture"
        const val KEY_NOTIFICATION_PROMPTED = "notification_prompt_requested"
        const val KEY_MODEL_URI = "model_uri_"
    }
}
