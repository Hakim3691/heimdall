package com.heimdall.ui.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.heimdall.model.OverlaySpec

/**
 * Material 3-flavoured "glass" pill, built programmatically.
 *
 * Built in code instead of XML/Compose on purpose: this view lives in a `TYPE_APPLICATION_OVERLAY`
 * window with no Activity, no theme and no lifecycle, so the fewer framework dependencies the better.
 */
internal object TooltipPillView {
    private const val BACKGROUND_ARGB = 0xE61C1B1F.toInt()
    private const val STROKE_ARGB = 0x33FFFFFF
    private const val ACCENT_ARGB = 0xFF7CC4FF.toInt()
    private const val TEXT_ARGB = 0xFFF4F1F7.toInt()

    fun create(context: Context, spec: OverlaySpec): View {
        val density = context.resources.displayMetrics.density
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16, density), dp(12, density), dp(18, density), dp(12, density))
            elevation = dp(10, density).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = spec.let { 22f * density }
                setColor(scrimColor(spec.opacity))
                setStroke(dp(1, density).coerceAtLeast(1), STROKE_ARGB)
            }
            // The window is not touchable, so the view must not pretend to be clickable either.
            isClickable = false
            isFocusable = false
        }

        val accent = View(context).apply {
            setBackgroundColor(ACCENT_ARGB)
            layoutParams = LinearLayout.LayoutParams(dp(3, density).coerceAtLeast(2), dp(20, density)).apply {
                marginEnd = dp(12, density)
            }
        }

        val label = TextView(context).apply {
            text = spec.text
            setTextColor(TEXT_ARGB)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        pill.addView(accent)
        pill.addView(label)
        pill.contentDescription = "Heimdall hint: ${spec.text}"
        return pill
    }

    /**
     * Fades only the glass behind the text. The text and accent bar keep their own alpha, so a low
     * setting makes the scrim subtle instead of making the hint unreadable.
     */
    private fun scrimColor(opacity: Float): Int {
        val alpha = (Color.alpha(BACKGROUND_ARGB) * opacity.coerceIn(0f, 1f)).toInt()
        return Color.argb(alpha, Color.red(BACKGROUND_ARGB), Color.green(BACKGROUND_ARGB), Color.blue(BACKGROUND_ARGB))
    }

    private fun dp(value: Int, density: Float): Int = (value * density).toInt()

    @Suppress("unused")
    private val transparent = Color.TRANSPARENT
}
