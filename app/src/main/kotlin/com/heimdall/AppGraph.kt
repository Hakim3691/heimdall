package com.heimdall

import android.content.Context
import android.os.Build
import com.heimdall.common.Clock
import com.heimdall.common.HeimdallLog
import com.heimdall.common.RingBufferLog
import com.heimdall.diagnostics.RotatingFileLog
import com.heimdall.diagnostics.SessionStats
import com.heimdall.engine.governor.ThermalPowerGovernor
import com.heimdall.engine.trigger.TriggerCascade
import com.heimdall.engine.vlm.FakeVlmEngine
import com.heimdall.engine.vlm.NativeVlmEngine
import com.heimdall.engine.vlm.VlmEngine
import com.heimdall.engine.vlm.VlmRuntime
import com.heimdall.model.Anchor
import com.heimdall.model.HeimdallConfig
import com.heimdall.model.ModelFile
import com.heimdall.model.ModelValidation
import com.heimdall.model.OverlayConfig
import com.heimdall.model.TextMetrics
import com.heimdall.model.VlmConfig
import com.heimdall.service.HeimdallCaptureService
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

    /** Counters for the current or last capture session, exported with the diagnostics report. */
    val session: SessionStats = SessionStats()

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
        val pairIssue = models.pairIssue(validation)
        val ready = validation.values.all { it is ModelValidation.Ready } && pairIssue == null
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
        } else if (pairIssue != null) {
            log.warn(TAG, "model pair rejected: ${pairIssue.detail}")
        }
        if (BuildConfig.DEBUG) {
            log.warn(TAG, "model files unavailable; using scripted engine (debug build only)")
            return FakeVlmEngine().apply {
                enqueue(FakeVlmEngine.intervention("Tap Allow in the dialog to continue"))
            }
        }
        // Release: still construct the native engine so it can report why it is unavailable.
        val reason = validation.entries.joinToString { (file, verdict) -> "${file.role}=$verdict" } +
            (pairIssue?.let { ", pair=${it.detail}" } ?: "")
        log.warn(TAG, "model files unavailable; starting native engine without weights: $reason")
        return NativeVlmEngine("", "", config.vlm, log, clock)
    }

    /**
     * Real native-runtime state for diagnostics.
     *
     * This used to report `models.allReady()`, which said "nativeVlm=true" purely because two files
     * had been picked — even when `libheimdall.so` was missing from the APK. The library load result,
     * its failure reason and the device ABI are the facts that actually explain an unloaded backend.
     */
    fun nativeStatus(): String = runCatching {
        "nativeVlm=${VlmRuntime.isLoaded} backend=${VlmRuntime.backend()} " +
            "abi=${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"} " +
            "loadFailure=${VlmRuntime.loadFailure ?: "none"}"
    }.getOrDefault("nativeVlm=unknown")

    /**
     * Result of the last [probeModels] run, or null when it has not run for this selection.
     *
     * Metadata rejects what it can prove is wrong; this holds the runtime's own verdict on the
     * exact pair, which is the only way to know a Qwen3.5-VL/Bonsai pair really loads.
     */
    @Volatile
    var modelProbe: String? = null
        private set

    private var probeKey: String? = null
    private var probing = false

    /**
     * Loads the selected pair in the native runtime on a background thread and reports the result.
     *
     * Skipped while capture owns the runtime (the probe frees it), and cached per document pair so
     * focus changes do not reload gigabytes.
     */
    fun probeModels(onDone: (String) -> Unit = {}) {
        if (HeimdallCaptureService.isRunning) {
            onDone("not tested: stop the capture session first")
            return
        }
        val key = ModelFile.entries.joinToString("|") { models.uriFor(it)?.toString().orEmpty() }
        synchronized(this) {
            if (probing) return
            val cached = modelProbe?.takeIf { probeKey == key }
            if (cached != null) {
                onDone(cached)
                return
            }
            val issue = models.pairIssue()
            if (issue != null) {
                val message = "pair rejected: ${issue.detail}"
                modelProbe = message
                probeKey = key
                onDone(message)
                return
            }
            val mount = models.mount() ?: run {
                val message = "not tested: the selected documents cannot be mounted"
                modelProbe = message
                onDone(message)
                return
            }
            probing = true
            Thread({
                val probe = try {
                    VlmRuntime.probePair(mount.languagePath, mount.visionPath, log)
                } finally {
                    runCatching { mount.close() }
                }
                val message = if (probe.ready) {
                    "pair loads on this device (${probe.message})"
                } else {
                    "pair rejected by llama.cpp: ${probe.message}"
                }
                synchronized(this) {
                    modelProbe = message
                    probeKey = key
                    probing = false
                }
                log.info(TAG, "model pair probe: $message")
                onDone(message)
            }, "heimdall-model-probe").start()
        }
    }

    /** Diagnostics bundle for the export flow: environment + recent log lines. */
    fun diagnosticsReport(): String = buildString {
        appendLine("# Heimdall diagnostics")
        appendLine("version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("models: ${models.describe()}")
        appendLine("modelProbe: ${modelProbe ?: "not run"}")
        appendLine("native: ${nativeStatus()}")
        appendLine("capture: running=${HeimdallCaptureService.isRunning} ${session.describe()}")
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
