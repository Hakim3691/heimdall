package com.heimdall.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.heimdall.BuildConfig
import com.heimdall.HeimdallApplication
import com.heimdall.R
import com.heimdall.model.Anchor
import com.heimdall.model.Intervention
import com.heimdall.model.ModelFile
import com.heimdall.model.ModelIssue
import com.heimdall.model.ModelValidation
import com.heimdall.model.OnboardingState
import com.heimdall.model.OnboardingStep
import com.heimdall.model.OverlayPolicy
import com.heimdall.model.PermissionState
import com.heimdall.service.HeimdallAccessibilityService
import com.heimdall.service.HeimdallCaptureService

/**
 * Permission onboarding and the only screen in the app.
 *
 * Android grants none of these privileges to an installer: overlay, accessibility and screen
 * capture each need a Settings trip or a per-session system dialog, and a sideloaded app must first
 * be allowed to use restricted settings. So the screen does the next best thing — it drives the
 * whole sequence itself on first launch (notification prompt → overlay → accessibility → model
 * picker → cast prompt) and explains every platform obstacle inline. The state machine itself
 * ([OnboardingState]) is pure and covered by the acceptance tests.
 */
class OnboardingActivity : Activity() {
    private lateinit var graph: AppGraph
    private lateinit var stepsContainer: LinearLayout
    private lateinit var modelsContainer: LinearLayout
    private lateinit var primaryButton: Button
    private lateinit var statusLine: TextView

    /** The step this screen already launched for the user, so returning does not relaunch it. */
    private var autoStep: OnboardingStep? = null

    private var notificationDialogInFlight = false

    /** Live pipeline line and probe row, refreshed between rebuilds by [updateLiveStatus]. */
    private var pipelineLine: TextView? = null
    private var probeStatusView: TextView? = null

    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            updateLiveStatus()
            if (HeimdallCaptureService.isRunning || graph.isProbing) {
                pollHandler.postDelayed(this, LIVE_STATUS_INTERVAL_MS)
            }
        }
    }

    private fun startPolling() {
        pollHandler.removeCallbacks(pollRunnable)
        pollHandler.post(pollRunnable)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph = (application as HeimdallApplication).graph
        // A configuration change must not replay the platform sequence the user is in the middle of.
        autoStep = savedInstanceState?.getString(KEY_AUTO_STEP)?.let { name ->
            runCatching { OnboardingStep.valueOf(name) }.getOrNull()
        }
        setContentView(buildContent())
        requestNotificationsOnce()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_AUTO_STEP, autoStep?.name)
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        autoChain()
        // The cascade refuses frames for reasons the screen would otherwise never show.
        startPolling()
    }

    override fun onPause() {
        super.onPause()
        pollHandler.removeCallbacks(pollRunnable)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Returning from Settings or the picker can change every row; refresh immediately.
        if (hasFocus) refreshUi()
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

        modelsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = cardBackground(ACCENT_DIM)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
        }
        root.addView(modelsContainer)

        statusLine = TextView(this).apply {
            setTextColor(TEXT_MUTED)
            textSize = 13f
            setPadding(0, dp(12), 0, dp(12))
        }
        root.addView(statusLine)

        pipelineLine = TextView(this).apply {
            setTextColor(TEXT_MUTED)
            textSize = 12f
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(pipelineLine)

        root.addView(
            Button(this).apply {
                text = getString(R.string.onboarding_sample_hint)
                setOnClickListener { showSampleHint() }
            },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = dp(8) },
        )

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

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        footer.addView(
            Button(this).apply {
                text = getString(R.string.onboarding_refresh)
                setOnClickListener {
                    // Re-read both documents instead of trusting the cached verdict.
                    graph.models.invalidate()
                    refreshUi()
                }
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
        val labels = stepLabels()
        val granted = grantedSteps(state)

        // Explicit order: display order and the order [OnboardingState.nextStep] asks for must agree.
        for (step in STEP_ORDER) {
            val optional = step == OnboardingStep.ACCESSIBILITY
            stepsContainer.addView(
                buildStepRow(step, getString(labels.getValue(step)), step in granted, optional = optional),
            )
            if (optional && step !in granted) {
                stepsContainer.addView(buildAccessibilityHelp())
            }
        }
        buildModelRows()

        val next = state.nextStep()
        primaryButton.isEnabled = true
        primaryButton.text = when {
            HeimdallCaptureService.isRunning -> getString(R.string.onboarding_stop)
            next == OnboardingStep.PROJECTION -> getString(R.string.onboarding_start)
            next == OnboardingStep.DONE -> getString(R.string.onboarding_start)
            else -> getString(R.string.onboarding_next_step, getString(labels.getValue(next)))
        }
        primaryButton.alpha = 1f

        statusLine.text = buildString {
            append(
                if (state.canStartCapture) {
                    getString(R.string.onboarding_ready)
                } else {
                    getString(R.string.onboarding_next_status, getString(labels.getValue(next)))
                },
            )
            val validations = graph.models.validateAll()
            val problems = validations.entries
                .filter { (_, validation) -> validation !is ModelValidation.Ready }
                .map { (file, validation) -> problemText(file, validation as ModelValidation.Invalid) }
                .toMutableList()
            graph.models.pairIssue(validations)?.let { issue ->
                problems += getString(R.string.onboarding_pair_warning, issue.detail.orEmpty())
            }
            if (problems.isNotEmpty()) {
                append(' ')
                append(getString(R.string.onboarding_missing_models, problems.joinToString("; ")))
            }
        }
        updateLiveStatus()
    }

    /**
     * One status-line entry for a rejected file: what was actually picked, plus the real reason.
     * The old line printed the canonical slot name (`minicpm-v-2_6-Q4_K_M.gguf`) even when a Bonsai
     * file occupied the slot, which read as if the recommended download were missing.
     */
    private fun problemText(file: ModelFile, invalid: ModelValidation.Invalid): String {
        val name = graph.models.displayName(file) ?: file.description
        val reason = invalid.detail?.takeIf { it.isNotBlank() } ?: issueLabel(invalid)
        return getString(R.string.onboarding_model_problem, name, reason)
    }

    private fun buildStepRow(
        step: OnboardingStep,
        label: String,
        granted: Boolean,
        optional: Boolean = false,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = cardBackground(if (granted) ACCENT_DIM else STROKE)
        }
        val text = TextView(this).apply {
            this.text = label
            setTextColor(if (granted) TEXT else TEXT_MUTED)
            textSize = 15f
        }
        row.addView(text, LinearLayout.LayoutParams(0, WRAP, 1f))
        // Projection consent is per session by platform rule, so an inactive session is a normal
        // state, not a warning: it is requested when capture starts.
        val inactive = step == OnboardingStep.PROJECTION && !granted
        row.addView(
            TextView(this).apply {
                this.text = when {
                    granted -> getString(R.string.onboarding_status_granted)
                    inactive -> getString(R.string.onboarding_status_inactive)
                    optional -> getString(R.string.onboarding_status_optional)
                    else -> getString(R.string.onboarding_status_missing)
                }
                setTextColor(
                    when {
                        granted -> ACCENT
                        inactive -> TEXT_FAINT
                        optional -> TEXT_FAINT
                        else -> WARN
                    },
                )
                textSize = 12f
                setPadding(0, 0, dp(12), 0)
            },
        )
        row.addView(
            Button(this).apply {
                this.text = getString(R.string.onboarding_open)
                setOnClickListener { onStepAction(step) }
            },
        )
        return row.apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) }
        }
    }

    /** Sideloaded builds cannot enable accessibility until restricted settings are allowed. */
    private fun buildAccessibilityHelp(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = cardBackground(WARN)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) }
        }
        card.addView(
            TextView(this).apply {
                text = getString(R.string.onboarding_accessibility_help)
                setTextColor(TEXT_MUTED)
                textSize = 13f
            },
        )
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        actions.addView(
            Button(this).apply {
                text = getString(R.string.onboarding_open_app_info)
                setOnClickListener { openAppInfo() }
            },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(0, 0, dp(8), 0) },
        )
        actions.addView(
            Button(this).apply {
                text = getString(R.string.onboarding_copy_adb)
                setOnClickListener { copyAdbCommand() }
            },
        )
        card.addView(actions)
        return card
    }

    private fun buildModelRows() {
        modelsContainer.removeAllViews()
        modelsContainer.addView(
            TextView(this).apply {
                text = getString(R.string.onboarding_models_title)
                setTextColor(TEXT)
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
        )
        val validations = graph.models.validateAll()
        for (file in ModelFile.entries) {
            modelsContainer.addView(buildModelRow(file, validations[file] ?: ModelValidation.Invalid(file, ModelIssue.MISSING)))
        }
        modelsContainer.addView(
            TextView(this).apply {
                text = getString(R.string.onboarding_models_hint)
                setTextColor(TEXT_FAINT)
                textSize = 12f
                setPadding(0, dp(8), 0, 0)
            },
        )
        modelsContainer.addView(
            TextView(this).apply {
                text = getString(R.string.onboarding_models_recommended_note)
                setTextColor(TEXT_FAINT)
                textSize = 12f
                setPadding(0, dp(8), 0, 0)
            },
        )
        val modelActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        modelActions.addView(
            Button(this).apply {
                text = getString(R.string.onboarding_get_recommended)
                setOnClickListener { openRecommendedModel() }
            },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(0, 0, dp(8), 0) },
        )
        modelActions.addView(
            Button(this).apply {
                text = getString(R.string.onboarding_copy_names)
                setOnClickListener { copyRecommendedFileNames() }
            },
        )
        modelsContainer.addView(modelActions)
        modelsContainer.addView(
            Button(this).apply {
                text = getString(R.string.onboarding_models_help)
                setOnClickListener { showModelHelp() }
            },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) },
        )
        // The runtime probe is the only authoritative verdict: metadata can only reject what it can
        // prove, while llama.cpp decides whether this exact pair loads.
        probeStatusView = TextView(this).apply {
            text = probeText()
            setTextColor(probeColor())
            textSize = 12f
            setPadding(0, dp(8), 0, 0)
        }
        modelsContainer.addView(probeStatusView)
        modelsContainer.addView(
            Button(this).apply {
                text = probeButtonText()
                isEnabled = !graph.isProbing && bothModelsPicked() && !HeimdallCaptureService.isRunning
                setOnClickListener { startProbe() }
            },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) },
        )
    }

    private fun bothModelsPicked(): Boolean =
        graph.models.uriFor(ModelFile.LANGUAGE) != null && graph.models.uriFor(ModelFile.VISION) != null

    private fun probeButtonText(): String = if (graph.isProbing) {
        getString(R.string.onboarding_probe_running_button, graph.probeElapsedSeconds)
    } else {
        getString(R.string.onboarding_test_pair)
    }

    private fun probeText(): String = when {
        !bothModelsPicked() -> getString(R.string.onboarding_probe_none)
        graph.isProbing -> getString(R.string.onboarding_probe_running_now, graph.probeElapsedSeconds)
        graph.probeMatchesSelection && graph.modelProbe != null -> getString(
            if (graph.probeReady) R.string.onboarding_probe_ready else R.string.onboarding_probe_failed,
            graph.modelProbe.orEmpty(),
        )
        else -> getString(R.string.onboarding_probe_none)
    }

    private fun probeColor(): Int = when {
        graph.isProbing -> ACCENT
        graph.probeMatchesSelection && graph.probeReady -> ACCENT
        graph.probeMatchesSelection && graph.modelProbe != null -> WARN
        else -> TEXT_FAINT
    }

    private fun startProbe() {
        // Rebuild immediately so the row shows the running state instead of a toast that vanishes.
        refreshUi()
        startPolling()
        graph.probeModels { state ->
            runOnUiThread {
                refreshUi()
                showProbeResult(state)
            }
        }
    }

    /**
     * A rejection used to be a faint 12sp line, so a probe that ran for minutes looked like nothing
     * had happened. The verdict is a dialog, with the runtime's own log tail that explains it.
     */
    private fun showProbeResult(state: AppGraph.ProbeState.Finished) {
        val tail = state.nativeLog.lineSequence()
            .filter { it.isNotBlank() }
            .toList()
            .takeLast(PROBE_LOG_LINES)
            .joinToString("\n")
        val body = if (tail.isBlank()) state.message else state.message + "\n\nllama.cpp:\n" + tail
        AlertDialog.Builder(this)
            .setTitle(
                if (state.ready) R.string.onboarding_probe_ok_title else R.string.onboarding_probe_failed_title,
            )
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.onboarding_probe_copy) { _, _ ->
                copyToClipboard("Heimdall runtime probe", body)
                Toast.makeText(this, R.string.onboarding_probe_copied, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun updateLiveStatus() {
        pipelineLine?.text = pipelineText()
        probeStatusView?.let { view ->
            view.text = probeText()
            view.setTextColor(probeColor())
        }
    }

    /**
     * The single line that answers "why is nothing appearing?": the cascade's last verdict, the
     * engine state and what the model last said (or why its answer was dropped).
     */
    private fun pipelineText(): String {
        val session = graph.session
        if (!HeimdallCaptureService.isRunning) {
            return getString(R.string.onboarding_pipeline_idle)
        }
        return getString(
            R.string.onboarding_pipeline_state,
            session.frames,
            session.gatePasses,
            session.inferences,
            session.interventions,
        ) + "\n" + getString(R.string.onboarding_pipeline_gate, session.lastGate) +
            "\nverdict: " + session.lastVerdict + "\nengine: " + session.engineStatus
    }

    /**
     * Renders a real pill through the production overlay path, so "the toggle is on but nothing is
     * ever drawn" can be told apart from "the trigger cascade never opened".
     */
    private fun showSampleHint() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.onboarding_sample_hint_needs_overlay, Toast.LENGTH_LONG).show()
            openOverlaySettings()
            return
        }
        val spec = OverlayPolicy.specFor(
            Intervention(getString(R.string.onboarding_sample_text), confidence = 1f, anchor = Anchor.BOTTOM),
            graph.config.overlay,
        )
        val shown = graph.overlay.show(spec)
        Toast.makeText(
            this,
            if (shown) R.string.onboarding_sample_hint_shown else R.string.onboarding_sample_hint_failed,
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun buildModelRow(file: ModelFile, validation: ModelValidation): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(2))
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(
            TextView(this).apply {
                text = file.description
                setTextColor(TEXT)
                textSize = 14f
            },
        )
        val ready = validation is ModelValidation.Ready
        val selected = graph.models.summary(file) ?: getString(R.string.onboarding_model_missing)
        column.addView(
            TextView(this).apply {
                text = if (ready) {
                    getString(R.string.onboarding_model_ready_name, selected)
                } else {
                    getString(R.string.onboarding_model_not_ready, selected, issueLabel(validation))
                }
                setTextColor(if (ready) ACCENT else WARN)
                textSize = 12f
            },
        )
        row.addView(column, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(
            Button(this).apply {
                text = if (graph.models.displayName(file) == null) {
                    getString(R.string.onboarding_choose)
                } else {
                    getString(R.string.onboarding_replace)
                }
                setOnClickListener { pickModel(file) }
            },
        )
        return row
    }

    private fun issueLabel(validation: ModelValidation): String = when (validation) {
        is ModelValidation.Ready -> getString(R.string.onboarding_model_ready)
        is ModelValidation.Invalid -> when (validation.issue) {
            ModelIssue.MISSING -> getString(R.string.onboarding_issue_missing)
            ModelIssue.EMPTY -> getString(R.string.onboarding_issue_empty)
            ModelIssue.NOT_A_GGUF -> getString(R.string.onboarding_issue_not_gguf)
            ModelIssue.TRUNCATED -> getString(R.string.onboarding_issue_truncated)
            ModelIssue.INSUFFICIENT_STORAGE -> getString(R.string.onboarding_issue_storage)
            ModelIssue.INCOMPATIBLE_MODEL -> getString(R.string.onboarding_issue_incompatible)
            ModelIssue.INCOMPATIBLE_PAIR -> getString(R.string.onboarding_issue_pair)
        }
    }

    private fun showModelHelp() {
        AlertDialog.Builder(this)
            .setTitle(R.string.onboarding_models_help)
            .setMessage(R.string.onboarding_models_help_body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ---- actions -----------------------------------------------------------------------------

    private fun onStepAction(step: OnboardingStep) {
        when (step) {
            OnboardingStep.OVERLAY -> openOverlaySettings()
            OnboardingStep.ACCESSIBILITY -> openAccessibilitySettings()
            OnboardingStep.NOTIFICATIONS -> requestNotifications()
            OnboardingStep.MODELS -> pickModel(graph.models.absent().firstOrNull() ?: ModelFile.LANGUAGE)
            OnboardingStep.PROJECTION -> requestProjection()
            OnboardingStep.DONE -> Unit
        }
    }

    private fun onPrimaryAction() {
        if (HeimdallCaptureService.isRunning) {
            stopCapture()
            return
        }
        val next = currentOnboardingState().nextStep()
        if (next == OnboardingStep.DONE) {
            requestProjection()
            return
        }
        onStepAction(next)
    }

    /** Drives the platform sequence on first launch: notifications → overlay → accessibility → models → cast. */
    private fun autoChain() {
        if (notificationDialogInFlight) return
        val state = currentOnboardingState()
        val next = autoTarget(state)
        if (next == OnboardingStep.DONE || next == autoStep) return
        when (next) {
            OnboardingStep.OVERLAY -> {
                autoStep = next
                openOverlaySettings()
            }

            OnboardingStep.ACCESSIBILITY -> {
                autoStep = next
                openAccessibilitySettings()
            }

            OnboardingStep.MODELS -> {
                autoStep = next
                graph.models.absent().firstOrNull()?.let { pickModel(it) }
            }

            OnboardingStep.PROJECTION -> {
                autoStep = next
                // The cast dialog is offered automatically on the first run only. Afterwards the
                // consent is per session by platform rule, but re-prompting on every launch would
                // be a nag the user cannot dismiss permanently.
                if (!graph.settings.projectionPrompted) requestProjection()
            }

            OnboardingStep.NOTIFICATIONS, OnboardingStep.DONE -> Unit
        }
    }

    /**
     * A permanently denied notification permission must not block the rest of the setup, so once the
     * prompt has been shown the chain continues and only the row keeps asking for it.
     */
    private fun autoTarget(state: OnboardingState): OnboardingStep {
        val next = state.nextStep()
        if (next != OnboardingStep.NOTIFICATIONS || !graph.settings.notificationPromptRequested) return next
        return OnboardingState(
            permissions = state.permissions.copy(notificationsGranted = true),
            modelsReady = state.modelsReady,
        ).nextStep()
    }

    private fun requestNotificationsOnce() {
        if (notificationsGranted()) return
        if (graph.settings.notificationPromptRequested) return
        graph.settings.notificationPromptRequested = true
        requestNotifications()
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            openNotificationSettings()
            return
        }
        notificationDialogInFlight = true
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
    }

    private fun openNotificationSettings() {
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
        )
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

    /**
     * The public accessibility list is the only stable entry point: the per-service details screen
     * (`ACTION_ACCESSIBILITY_DETAILS_SETTINGS`) is a system API third-party apps cannot target.
     */
    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openAppInfo() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    /**
     * The exact commands that lift the restricted-settings flag and enable the service.
     *
     * Package and component are resolved from the running build, so the same card works for the
     * release (`com.heimdall`) and debug (`com.heimdall.debug`) packages.
     */
    private fun adbCommands(): String = buildString {
        appendLine("adb shell appops set $packageName ACCESS_RESTRICTED_SETTINGS allow")
        appendLine(
            "adb shell settings put secure enabled_accessibility_services " +
                "$packageName/com.heimdall.service.HeimdallAccessibilityService",
        )
        append("adb shell settings put secure accessibility_enabled 1")
    }

    private fun copyAdbCommand() {
        copyToClipboard("Heimdall accessibility", adbCommands())
        Toast.makeText(this, R.string.onboarding_adb_copied, Toast.LENGTH_LONG).show()
    }

    /** Opens the Hugging Face page in the system browser; Heimdall itself has no network access. */
    private fun openRecommendedModel() {
        val opened = runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RECOMMENDED_MODEL_URL)))
        }.isSuccess
        if (!opened) {
            Toast.makeText(this, RECOMMENDED_MODEL_URL, Toast.LENGTH_LONG).show()
        }
    }

    private fun copyRecommendedFileNames() {
        copyToClipboard("Heimdall model files", RECOMMENDED_FILE_NAMES)
        Toast.makeText(this, R.string.onboarding_model_names_copied, Toast.LENGTH_SHORT).show()
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun pickModel(file: ModelFile) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        val title = if (file == ModelFile.LANGUAGE) {
            R.string.onboarding_pick_language
        } else {
            R.string.onboarding_pick_vision
        }
        startActivityForResult(Intent.createChooser(intent, getString(title)), requestCodeFor(file))
    }

    private fun stopCapture() {
        if (!HeimdallCaptureService.isRunning) {
            refreshUi()
            return
        }
        startService(
            Intent(this, HeimdallCaptureService::class.java)
                .setAction(HeimdallCaptureService.ACTION_STOP),
        )
        // Stopping drops projection consent; do not immediately re-prompt for it on resume.
        autoStep = OnboardingStep.PROJECTION
        refreshUi()
    }

    private fun requestProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            Toast.makeText(this, "Screen capture is not available on this device", Toast.LENGTH_LONG).show()
            return
        }
        val models = graph.models.validateAll()
        val notReady = models.filterValues { it !is ModelValidation.Ready }.keys
        if (notReady.isNotEmpty() && !BuildConfig.DEBUG) {
            graph.log.warn(TAG, "refusing to start capture: models not ready $notReady")
            Toast.makeText(this, getString(R.string.onboarding_models_hint), Toast.LENGTH_LONG).show()
            return
        }
        graph.settings.projectionPrompted = true
        // Android 14+ lets the app pin the dialog to the whole display, which removes the
        // "single app" option that would otherwise give Heimdall a crop of one window.
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            @Suppress("DEPRECATION")
            manager.createScreenCaptureIntent()
        }
        startActivityForResult(intent, REQ_PROJECTION)
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

            REQ_PICK_LANGUAGE, REQ_PICK_VISION -> {
                val file = if (requestCode == REQ_PICK_LANGUAGE) ModelFile.LANGUAGE else ModelFile.VISION
                handlePickedModel(file, resultCode, data)
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

    private fun handlePickedModel(file: ModelFile, resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (resultCode == RESULT_OK && uri != null) {
            graph.models.remember(file, uri)
            val validation = graph.models.validateAll()[file]
            val invalid = validation as? ModelValidation.Invalid
            val pairIssue = graph.models.pairIssue()
            when {
                invalid != null -> Toast.makeText(
                    this,
                    pickFailureMessage(invalid),
                    Toast.LENGTH_LONG,
                ).show()

                pairIssue != null -> Toast.makeText(
                    this,
                    getString(
                        R.string.onboarding_model_pick_mismatch,
                        pairIssue.detail.orEmpty(),
                    ),
                    Toast.LENGTH_LONG,
                ).show()

                else -> {
                    // Continue straight to the other file so setup stays a single sequence.
                    val other = ModelFile.entries.firstOrNull {
                        it != file && graph.models.uriFor(it) == null
                    }
                    if (other != null) {
                        pickModel(other)
                    } else {
                        // Both files are plausible: ask the runtime to prove the pair loads.
                        graph.probeModels { state ->
                            runOnUiThread {
                                refreshUi()
                                showProbeResult(state)
                            }
                        }
                    }
                }
            }
        }
        refreshUi()
    }

    /** Per-issue wording: an architecture rejection is not a "pick a local copy" problem. */
    private fun pickFailureMessage(validation: ModelValidation.Invalid): String =
        when (validation.issue) {
            ModelIssue.INCOMPATIBLE_PAIR -> getString(
                R.string.onboarding_model_pick_mismatch,
                validation.detail.orEmpty(),
            )

            ModelIssue.INCOMPATIBLE_MODEL -> getString(
                R.string.onboarding_model_pick_incompatible,
                validation.detail ?: issueLabel(validation),
            )

            else -> getString(
                R.string.onboarding_model_pick_failed,
                validation.detail ?: issueLabel(validation),
            )
        }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATIONS) {
            notificationDialogInFlight = false
            refreshUi()
            autoChain()
        }
    }

    // ---- state -------------------------------------------------------------------------------

    private fun currentOnboardingState(): OnboardingState = OnboardingState(
        permissions = PermissionState(
            overlayGranted = Settings.canDrawOverlays(this),
            accessibilityEnabled = isAccessibilityEnabled(),
            notificationsGranted = notificationsGranted(),
            // Projection consent is per-session and cannot be persisted; a live service counts.
            projectionConsentGranted = HeimdallCaptureService.isRunning,
        ),
        modelsReady = graph.models.allReady(),
    )

    private fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun isAccessibilityEnabled(): Boolean {
        if (HeimdallAccessibilityService.connected) return true
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { component -> component.contains(packageName) }
    }

    private fun grantedSteps(state: OnboardingState): Set<OnboardingStep> = buildSet {
        if (state.permissions.overlayGranted) add(OnboardingStep.OVERLAY)
        if (state.permissions.accessibilityEnabled) add(OnboardingStep.ACCESSIBILITY)
        if (state.permissions.notificationsGranted) add(OnboardingStep.NOTIFICATIONS)
        if (state.modelsReady) add(OnboardingStep.MODELS)
        if (state.permissions.projectionConsentGranted) add(OnboardingStep.PROJECTION)
    }

    private fun stepLabels(): Map<OnboardingStep, Int> = mapOf(
        OnboardingStep.OVERLAY to R.string.onboarding_step_overlay,
        OnboardingStep.ACCESSIBILITY to R.string.onboarding_step_accessibility,
        OnboardingStep.NOTIFICATIONS to R.string.onboarding_step_notifications,
        OnboardingStep.MODELS to R.string.onboarding_step_models,
        OnboardingStep.PROJECTION to R.string.onboarding_step_projection,
    )

    private fun requestCodeFor(file: ModelFile): Int =
        if (file == ModelFile.LANGUAGE) REQ_PICK_LANGUAGE else REQ_PICK_VISION

    private fun cardBackground(stroke: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setColor(SURFACE)
        setStroke(dp(1), stroke)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "Onboarding"
        const val REQ_PROJECTION = 42
        const val REQ_NOTIFICATIONS = 43
        const val REQ_EXPORT = 44
        const val REQ_PICK_LANGUAGE = 45
        const val REQ_PICK_VISION = 46
        const val KEY_AUTO_STEP = "com.heimdall.extra.AUTO_STEP"
        const val LIVE_STATUS_INTERVAL_MS = 1_000L
        const val PROBE_LOG_LINES = 12
        const val RECOMMENDED_MODEL_URL = "https://huggingface.co/openbmb/MiniCPM-V-2_6-gguf"
        const val RECOMMENDED_FILE_NAMES = "ggml-model-Q4_K_M.gguf\nmmproj-model-f16.gguf"

        val STEP_ORDER = listOf(
            OnboardingStep.OVERLAY,
            OnboardingStep.ACCESSIBILITY,
            OnboardingStep.NOTIFICATIONS,
            OnboardingStep.MODELS,
            OnboardingStep.PROJECTION,
        )

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
