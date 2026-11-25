package com.heimdall.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.heimdall.AppGraph
import com.heimdall.HeimdallApplication

/**
 * Stage 2 of the trigger cascade: turns interaction events into a hesitation signal (TRD §6.2).
 *
 * The service is deliberately blind by configuration (see `accessibility_service_config.xml`):
 * `canRetrieveWindowContent=false` and no gesture capability. It only needs to know *when* the user
 * last did something — it never reads, stores or transmits screen text, which is also why it cannot
 * leak anything even if the process were compromised.
 */
class HeimdallAccessibilityService : AccessibilityService() {
    private lateinit var graph: AppGraph

    override fun onServiceConnected() {
        super.onServiceConnected()
        graph = (application as HeimdallApplication).graph
        connected = true
        graph.log.info(TAG, "accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            -> graph.cascade.onInteraction()

            else -> Unit
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        connected = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "HeimdallA11y"

        /** Set while the service is bound; used by onboarding to show live status. */
        @Volatile
        var connected: Boolean = false
            private set
    }
}
