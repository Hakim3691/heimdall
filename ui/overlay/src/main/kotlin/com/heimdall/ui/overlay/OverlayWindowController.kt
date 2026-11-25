package com.heimdall.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.heimdall.common.HeimdallLog
import com.heimdall.model.Anchor
import com.heimdall.model.OverlaySpec

/**
 * Renders Heimdall's micro-tooltip as a translucent, non-interactive overlay window (FR-01).
 *
 * Safety properties, in order of importance:
 * 1. The window is **never focusable and never touchable**. Taps always reach the app underneath.
 * 2. Only one pill exists at a time; showing a new tooltip replaces the previous one.
 * 3. Dismissal is idempotent and always removes pending callbacks, so the service can be killed
 *    without leaving a window behind or a timer that fires later.
 * 4. Losing the overlay permission (revoked mid-session) must not crash the capture service.
 */
class OverlayWindowController(
    private val context: Context,
    private val config: com.heimdall.model.OverlayConfig = com.heimdall.model.OverlayConfig(),
    private val log: HeimdallLog = HeimdallLog.NONE,
    private val windowManager: WindowManager? = null,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) {
    private val wm: WindowManager? by lazy {
        windowManager ?: (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
    }

    private var pillView: View? = null
    private var dismissRunnable: Runnable? = null

    /** Spec of the pill currently on screen, or null. */
    var currentSpec: OverlaySpec? = null
        private set

    val isShowing: Boolean get() = pillView != null

    /**
     * Shows [spec], replacing any visible pill. Returns false when the window could not be added
     * (permission revoked, service tearing down, no WindowManager).
     */
    fun show(spec: OverlaySpec): Boolean {
        val manager = wm
        if (manager == null) {
            log.error(TAG, "no WindowManager available; cannot render tooltip")
            return false
        }
        dismiss()
        val params = buildLayoutParams(spec)
        val view = TooltipPillView.create(context, spec)
        return try {
            manager.addView(view, params)
            pillView = view
            currentSpec = spec
            scheduleDismiss(spec.autoDismissMs)
            log.info(TAG, "tooltip shown (${spec.wordCount} words, anchor=${spec.anchor})")
            true
        } catch (error: WindowManager.BadTokenException) {
            log.error(TAG, "overlay window rejected (permission revoked?)", error)
            false
        } catch (error: IllegalStateException) {
            log.error(TAG, "overlay window added from an invalid state", error)
            false
        } catch (error: SecurityException) {
            log.error(TAG, "overlay permission missing", error)
            false
        }
    }

    /** Removes the pill and cancels its dismissal timer. Safe to call when nothing is showing. */
    fun dismiss() {
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        dismissRunnable = null
        val view = pillView ?: return
        pillView = null
        currentSpec = null
        runCatching {
            if (view.isAttachedToWindow) wm?.removeViewImmediate(view)
        }.onFailure { error -> log.warn(TAG, "failed to remove overlay view", error) }
    }

    private fun scheduleDismiss(afterMs: Long) {
        val runnable = Runnable {
            log.debug(TAG, "tooltip auto-dismissed")
            dismiss()
        }
        dismissRunnable = runnable
        mainHandler.postDelayed(runnable, afterMs)
    }

    /**
     * Builds the window parameters for [spec].
     *
     * Kept separate (and internal) so the pass-through contract can be asserted bit-for-bit by a
     * Robolectric test without rendering anything.
     */
    internal fun buildLayoutParams(spec: OverlaySpec): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            // FR-01: pass every tap through to the app underneath. The pill is display-only.
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = when (spec.anchor) {
                Anchor.TOP -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                Anchor.BOTTOM -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
            y = dpToPx(spec.verticalInsetDp)
            horizontalMargin = HORIZONTAL_MARGIN_DP / 100f
            if (spec.anchor == Anchor.TOP) {
                // Leave room for the status bar / display cutout.
                y += dpToPx(STATUS_BAR_ALLOWANCE_DP)
            }
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "OverlayWindowController"
        const val HORIZONTAL_MARGIN_DP = 16
        const val STATUS_BAR_ALLOWANCE_DP = 28
    }
}

/** Debug-only helper so a screenshot/testing build can log the resolved flags. */
internal fun WindowManager.LayoutParams.describeFlags(): String = buildList {
    if (flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0) add("NOT_TOUCHABLE")
    if (flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0) add("NOT_FOCUSABLE")
    if (flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0) add("NOT_TOUCH_MODAL")
    if (flags and WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED != 0) add("HARDWARE_ACCELERATED")
}.joinToString("|").ifEmpty { Log.w("Overlay", "no flags set").let { "NONE" } }
