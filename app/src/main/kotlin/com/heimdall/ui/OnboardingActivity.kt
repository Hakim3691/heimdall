package com.heimdall.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.heimdall.AppGraph
import com.heimdall.HeimdallApplication
import com.heimdall.R
import com.heimdall.model.ModelFile
import com.heimdall.model.OnboardingState
import com.heimdall.model.OnboardingStep
import com.heimdall.model.PermissionState
import com.heimdall.service.HeimdallAccessibilityService
import com.heimdall.service.HeimdallCaptureService

/**
 * Permission onboarding and the only screen in the app.
 *
 * Heimdall cannot ask for any of its privileges silently — overlay, accessibility, notifications and
 * screen capture all require an explicit platform dialog or a trip to Settings. This screen exists to
 * make that unavoidable sequence as short and honest as possible, and to show *why* each one is
 * needed. The state machine itself ([OnboardingState]) is pure and covered by acceptance tests.
 */
class OnboardingActivity : Activity() {
    private lateinit var graph: AppGraph
    private lateinit var stepsContainer: LinearLayout
    private lateinit var primaryButton: Button
    private lateinit var statusLine: TextView

    private var pendingExport = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph = (application as HeimdallApplication).graph
        setContentView(buildContent())
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    // ---- UI ----------------------------------------------------------------------------------

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(24), dp(28), dp(24), dp(24))
        }

        root.addView(
            TextView(this).apply {
                text = getString(R.string.onboarding_title)
                setTextColor(TEXT)
                textSize = 26f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
        )
        root.addView(
            TextView(this).apply {
                text = getString(R.string.onboarding_subtitle)
                setTextColor(TEXT_MUTED)
                textSize = 14f
                setPadding(0, dp(8), 0, dp(20))
            },
        )

        stepsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(stepsContainer)

        statusLine = TextView(this).apply {
            setTextColor(TEXT_MUTED)
            textSize = 13f
            setPadding(0, dp(12), 0, dp(12))
        }
        root.addView(statusLine)

        root.addView(
            Switch(this).apply {
                text = "Offer a tappable “more” chip (opt-in)"
                setTextColor(TEXT)
                isChecked = graph.settings.expandedHelpEnabled
                setOnCheckedChangeListener { _, checked ->
                    graph.settings.expandedHelpEnabled = checked
                    graph.log.info(TAG, "expandedHelp=$checked")
                }
            },
        )

        primaryButton = Button(this).apply {
            text = getString(R.string.onboarding_start)
            setBackgroundColor(ACCENT)
            setTextColor(Color.BLACK)
            setOnClickListener { onPrimaryAction() }
        }
        root.addView(primaryButton, LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(16) })

        val stopButton = Button(this).apply {
            text = getString(R.string.onboarding_stop)
            setOnClickListener {
                startService(
                    Intent(this@OnboardingActivity, HeimdallCaptureService::class.java)
                        .setAction(HeimdallCaptureService.ACTION_STOP),
                )
                refreshUi()
            }
        }
        root.addView(stopButton, LinearLayout.LayoutParams(MATCH, dp(48)).apply { topMargin = dp(8) })

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        footer.addView(
            Button(this).apply {
                text = getString(R.string.onboarding_refresh)
                setOnClickListener { refreshUi() }
            },
            LinearLayout.LayoutParams(0, WRAP, 1f),
        )
        footer.addView(
            Button(this).apply {
                text = getString(R.string.onboarding_export_logs)
                setOnClickListener { exportDiagnostics() }
            },
            LinearLayout.LayoutParams(0, WRAP, 1f),
        )
        root.addView(footer)

        root.addView(
            TextView(this).apply {
                text = getString(R.string.onboarding_privacy_note)
                setTextColor(TEXT_FAINT)
                textSize = 12f
                setPadding(0, dp(20), 0, 0)
            },
        )

        return ScrollView(this).apply { addView(root) }
    }

    private fun refreshUi() {
        stepsContainer.removeAllViews()
        val state = currentOnboardingState()
        val labels = mapOf(
            OnboardingStep.OVERLAY to R.string.onboarding_step_overlay,
            OnboardingStep.ACCESSIBILITY to R.string.onboarding_step_accessibility,
            OnboardingStep.NOTIFICATIONS to R.string.onboarding_step_notifications,
            OnboardingStep.MODELS to R.string.onboarding_step_models,
            OnboardingStep.PROJECTION to R.string.onboarding_step_projection,
        )
        val granted = setOf(
            OnboardingStep.OVERLAY to state.permissions.overlayGranted,
            OnboardingStep.ACCESSIBILITY to state.permissions.accessibilityEnabled,
            OnboardingStep.NOTIFICATIONS to state.permissions.notificationsGranted,
            OnboardingStep.MODELS to state.modelsReady,
        ).filter { it.second }.map { it.first }

        for (step in OnboardingStep.entries) {
            if (step == OnboardingStep.DONE) continue
            val label = labels[step] ?: continue
            stepsContainer.addView(buildStepRow(step, getString(label), step in granted))
        }

        val next = state.nextStep()
        primaryButton.isEnabled = next == OnboardingStep.DONE
        primaryButton.text = when {
            state.canStartCapture && HeimdallCaptureService.isRunning -> getString(R.string.onboarding_stop)
            state.canStartCapture -> getString(R.string.onboarding_start)
            else -> "Complete: ${getString(labels[next] ?: R.string.onboarding_step_overlay)}"
        }
        statusLine.text = buildString {
            append(if (state.canStartCapture) "Ready. " else "Next: ${next.name.lowercase()}. ")
            val missingModels = graph.models.absent()
            if (missingModels.isNotEmpty()) {
                append("Missing model files: ")
                append(missingModels.joinToString { ModelFile.entries.first { f -> f.role == it.role }.fileName })
                append(". ")
            }
            append(getString(R.string.onboarding_models_hint))
        }
    }

    private fun buildStepRow(step: OnboardingStep, label: String, granted: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(SURFACE)
                setStroke(dp(1), if (granted) ACCENT_DIM else STROKE)
            }
        }
        val text = TextView(this).apply {
            this.text = label
            setTextColor(if (granted) TEXT else TEXT_MUTED)
            textSize = 15f
        }
        row.addView(text, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(
            TextView(this).apply {
                this.text = if (granted) {
                    getString(R.string.onboarding_status_granted)
                } else {
                    getString(R.string.onboarding_status_missing)
                }
                setTextColor(if (granted) ACCENT else WARN)
                textSize = 12f
                setPadding(0, 0, dp(12), 0)
            },
        )
        row.addView(
            Button(this).apply {
                this.text = "Open"
                isEnabled = !granted
                setOnClickListener { onStepAction(step) }
            },
        )
        return row.apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) }
        }
    }

    // ---- actions -----------------------------------------------------------------------------

    private fun onStepAction(step: OnboardingStep) {
        when (step) {
            OnboardingStep.OVERLAY -> openOverlaySettings()
            OnboardingStep.ACCESSIBILITY ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            OnboardingStep.NOTIFICATIONS ->
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
            OnboardingStep.MODELS -> {
                refreshUi()
                Toast.makeText(this, getString(R.string.onboarding_models_hint), Toast.LENGTH_LONG).show()
            }
            OnboardingStep.PROJECTION -> requestProjection()
            OnboardingStep.DONE -> Unit
        }
    }

    @Suppress("DEPRECATION")
    private fun openOverlaySettings() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private fun onPrimaryAction() {
        if (HeimdallCaptureService.isRunning) {
            startService(
                Intent(this, HeimdallCaptureService::class.java)
                    .setAction(HeimdallCaptureService.ACTION_STOP),
            )
            refreshUi()
            return
        }
        val next = currentOnboardingState().nextStep()
        if (next != OnboardingStep.DONE) {
            onStepAction(next)
            return
        }
        requestProjection()
    }

    private fun requestProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            Toast.makeText(this, "Screen capture is not available on this device", Toast.LENGTH_LONG).show()
            return
        }
        val models = graph.models.validateAll()
        val notReady = models.filterValues { it !is com.heimdall.model.ModelValidation.Ready }.keys
        if (notReady.isNotEmpty() && !com.heimdall.BuildConfig.DEBUG) {
            graph.log.warn(TAG, "refusing to start capture: models not ready $notReady")
            Toast.makeText(this, getString(R.string.onboarding_models_hint), Toast.LENGTH_LONG).show()
            return
        }
        startActivityForResult(manager.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    private fun exportDiagnostics() {
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "heimdall-diagnostics.txt")
            },
            REQ_EXPORT,
        )
    }

    @Deprecated("Activity result API needs a ComponentActivity; this screen has no other lifecycle needs")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_PROJECTION -> {
                if (resultCode == RESULT_OK && data != null) {
                    ContextCompat.startForegroundService(
                        this,
                        HeimdallCaptureService.startIntent(this, resultCode, data),
                    )
                } else {
                    graph.log.warn(TAG, "projection consent declined")
                }
                refreshUi()
            }

            REQ_EXPORT -> {
                if (resultCode == RESULT_OK && data?.data != null) {
                    runCatching {
                        contentResolver.openOutputStream(data.data!!)?.use { stream ->
                            stream.write(graph.diagnosticsReport().toByteArray())
                        }
                    }.onFailure { graph.log.error(TAG, "diagnostics export failed", it) }
                }
            }

            else -> Unit
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATIONS) refreshUi()
    }

    // ---- state -------------------------------------------------------------------------------

    private fun currentOnboardingState(): OnboardingState = OnboardingState(
        permissions = PermissionState(
            overlayGranted = Settings.canDrawOverlays(this),
            accessibilityEnabled = isAccessibilityEnabled(),
            notificationsGranted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
            // Projection consent is per-session and cannot be persisted; a live service counts.
            projectionConsentGranted = HeimdallCaptureService.isRunning,
        ),
        modelsReady = graph.models.allReady(),
    )

    private fun isAccessibilityEnabled(): Boolean {
        if (HeimdallAccessibilityService.connected) return true
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { component -> component.contains(packageName) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "Onboarding"
        const val REQ_PROJECTION = 42
        const val REQ_NOTIFICATIONS = 43
        const val REQ_EXPORT = 44

        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

        val BG = Color.parseColor("#121014")
        val SURFACE = Color.parseColor("#1E1C22")
        val STROKE = Color.parseColor("#3A3742")
        val TEXT = Color.parseColor("#F4F1F7")
        val TEXT_MUTED = Color.parseColor("#B9B3C4")
        val TEXT_FAINT = Color.parseColor("#8A8494")
        val ACCENT = Color.parseColor("#7CC4FF")
        val ACCENT_DIM = Color.parseColor("#33567A")
        val WARN = Color.parseColor("#FFB59A")
    }
}
