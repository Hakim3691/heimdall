package com.heimdall.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.heimdall.common.HeimdallLog
import com.heimdall.model.Anchor
import com.heimdall.model.OverlayConfig
import com.heimdall.model.OverlaySpec
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The single most important safety property of the overlay: it must never intercept a tap.
 * These are Robolectric assertions on the real `WindowManager.LayoutParams` the controller builds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverlayWindowControllerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val controller = OverlayWindowController(
        context = context,
        config = OverlayConfig(),
        log = HeimdallLog.NONE,
    )

    private fun spec(anchor: Anchor = Anchor.BOTTOM) = OverlaySpec(
        anchor = anchor,
        verticalInsetDp = 120,
        autoDismissMs = 4_500,
        touchable = false,
        expandedHelpAvailable = false,
        text = "Tap Allow to continue",
        wordCount = 4,
    )

    @Test
    fun `window is an application overlay with translucent pixels`() {
        val params = controller.buildLayoutParams(spec())
        assertThat(params.type).isEqualTo(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        assertThat(params.format).isEqualTo(PixelFormat.TRANSLUCENT)
    }

    @Test
    fun `window is not touchable and not focusable`() {
        val params = controller.buildLayoutParams(spec())
        assertThat(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).isNotEqualTo(0)
        assertThat(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).isNotEqualTo(0)
        assertThat(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL).isNotEqualTo(0)
    }

    @Test
    fun `bottom anchor is inset above the navigation area`() {
        val params = controller.buildLayoutParams(spec(Anchor.BOTTOM))
        assertThat(params.gravity).isEqualTo(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        assertThat(params.y).isGreaterThan(0)
    }

    @Test
    fun `top anchor clears the status bar`() {
        val params = controller.buildLayoutParams(spec(Anchor.TOP))
        assertThat(params.gravity).isEqualTo(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        assertThat(params.y).isGreaterThan(0)
    }

    @Test
    fun `dismiss is idempotent before anything is shown`() {
        controller.dismiss()
        controller.dismiss()
        assertThat(controller.isShowing).isFalse()
        assertThat(controller.currentSpec).isNull()
    }
}
