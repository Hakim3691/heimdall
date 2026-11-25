package com.heimdall

import android.content.Context
import com.heimdall.common.Clock
import com.heimdall.common.HeimdallLog
import com.heimdall.common.RingBufferLog
import com.heimdall.diagnostics.RotatingFileLog
import com.heimdall.engine.governor.ThermalPowerGovernor
import com.heimdall.engine.trigger.TriggerCascade
import com.heimdall.engine.vlm.FakeVlmEngine
import com.heimdall.engine.vlm.NativeVlmEngine
import com.heimdall.engine.vlm.VlmEngine
import com.heimdall.model.Anchor
import com.heimdall.model.HeimdallConfig
import com.heimdall.model.ModelValidation
import com.heimdall.model.OverlayConfig
import com.heimdall.model.TextMetrics
import com.heimdall.model.VlmConfig
import com.heimdall.settings.SettingsStore
import com.heimdall.storage.ModelStore
import com.heimdall.ui.overlay.OverlayWindowController
import java.io.File

/**
 * Hand-wired object graph for the whole process. Constructed once from [HeimdallApplication].
 *
 * Everything here is process-scoped on purpose: the accessibility service and the capture service
 * must agree on one cascade (a pause detected by accessibility is what arms inference), and the
 * overlay controller must be a singleton or two tooltips could be on screen at once.
 */
class AppGraph(context: Context) {
    private val appContext: Context = context.applicationContext

    val clock: Clock = Clock.MONOTONIC

    private val ringLog = RingBufferLog(capacity = 2_000)

    /** Ring buffer + rotating file, exportable by the user; there is no network sink by design. */
    val log: HeimdallLog = RotatingFileLog(
        file = File(appContext.filesDir, "diagnostics/heimdall.log"),
        delegate = ringLog,
    )

    val settings: SettingsStore = SettingsStore(appContext)

    /** Model weights come from the documents the user picked in the system file picker. */
    val models: ModelStore = ModelStore(appContext, settings, log)

    /** Configuration snapshot; overlay options that the user can toggle are read from settings. */
    val config: HeimdallConfig
        get() = HeimdallConfig(
            overlay = OverlayConfig(expandedHelpEnabled = settings.expandedHelpEnabled),
            vlm = VlmConfig(),
        )

    val cascade: TriggerCascade by lazy { TriggerCascade(config.trigger, clock) }

    val governor: ThermalPowerGovernor by lazy { ThermalPowerGovernor(clock) }

    /** Single overlay window owner: two instances could draw two pills at once. */
    val overlay: OverlayWindowController by lazy {
        OverlayWindowController(appContext, config.overlay, log)
    }

    /**
     * Creates the engine for the current session.
     *
     * Release builds require the real native runtime. Debug builds fall back to a scripted engine
     * when weights are absent so the full pipeline is demonstrable on any device.
     */
    fun createEngine(): VlmEngine {
        val validation = models.validateAll()
        val ready = validation.values.all { it is ModelValidation.Ready }
        if (ready) {
            // Zero copy: the selected documents stay where they are and the mounted descriptors
            // keep their /proc/self/fd paths valid for the whole session.
            val mount = models.mount()
            if (mount != null) {
                return NativeVlmEngine(
                    modelPath = mount.languagePath,
                    mmprojPath = mount.visionPath,
                    config = config.vlm,
                    log = log,
                    clock = clock,
                    mountedFiles = mount,
                )
            }
            log.warn(TAG, "selected model documents could not be mounted for inference")
        }
        if (BuildConfig.DEBUG) {
            log.warn(TAG, "model files unavailable; using scripted engine (debug build only)")
            return FakeVlmEngine().apply {
                enqueue(FakeVlmEngine.intervention("Tap Allow in the dialog to continue"))
            }
        }
        // Release: still construct the native engine so it can report why it is unavailable.
        val reason = validation.entries.joinToString { (file, verdict) -> "${file.fileName}=$verdict" }
        log.warn(TAG, "model files unavailable; starting native engine without weights: $reason")
        return NativeVlmEngine("", "", config.vlm, log, clock)
    }

    fun nativeStatus(): String = runCatching {
        "nativeVlm=${models.allReady()}"
    }.getOrDefault("nativeVlm=unknown")

    /** Diagnostics bundle for the export flow: environment + recent log lines. */
    fun diagnosticsReport(): String = buildString {
        appendLine("# Heimdall diagnostics")
        appendLine("version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("models: ${models.describe()}")
        appendLine("overlay: ${config.overlay}")
        appendLine("trigger: ${config.trigger}")
        appendLine("runtime: sdk=${android.os.Build.VERSION.SDK_INT} device=${android.os.Build.MODEL}")
        appendLine("expandedHelp: ${settings.expandedHelpEnabled}")
        appendLine("anchorDefault: ${Anchor.BOTTOM}")
        appendLine("wordBudget: ${TextMetrics.countWords("a b c")}/${config.overlay.maxWordCount}")
        appendLine()
        appendLine("## log")
        append(ringLog.asText())
    }

    private companion object {
        const val TAG = "AppGraph"
    }
}
