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
import com.heimdall.model.ModelFile
import com.heimdall.model.OverlayConfig
import com.heimdall.model.TextMetrics
import com.heimdall.model.VlmConfig
import com.heimdall.settings.SettingsStore
import com.heimdall.storage.ModelRepository
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

    val models: ModelRepository = ModelRepository(
        modelsDir = File("/sdcard/Heimdall/models"),
        log = log,
    )

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
        val languagePath = models.pathFor(ModelFile.LANGUAGE)
        val visionPath = models.pathFor(ModelFile.VISION)
        val ready = validation.values.all { it is com.heimdall.model.ModelValidation.Ready }
        return if (ready) {
            NativeVlmEngine(
                modelPath = languagePath,
                mmprojPath = visionPath,
                config = config.vlm,
                log = log,
                clock = clock,
            )
        } else if (BuildConfig.DEBUG) {
            log.warn(TAG, "model files unavailable; using scripted engine (debug build only)")
            FakeVlmEngine().apply {
                enqueue(FakeVlmEngine.intervention("Tap Allow in the dialog to continue"))
            }
        } else {
            // Release: still construct the native engine so it can report why it is unavailable.
            NativeVlmEngine(languagePath, visionPath, config.vlm, log, clock)
        }
    }

    fun nativeStatus(): String = runCatching {
        "nativeVlm=" + (models.takeIf { it.exists(ModelFile.LANGUAGE) } != null)
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
