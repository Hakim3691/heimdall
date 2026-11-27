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
            // The language weights are read from the picked document's own descriptor, so the
            // multi-gigabyte file is never copied and never reopened through /proc/self/fd. The
            // projector has to be a real file for mtmd, and is imported into app storage once.
            val prepared = models.prepare()
            if (prepared != null) {
                return NativeVlmEngine(
                    languageDescriptor = prepared.languageFd,
                    mmprojPath = prepared.projectorPath,
                    config = config.vlm,
                    log = log,
                    clock = clock,
                    languageHandle = prepared,
                )
            }
            log.warn(TAG, "selected model documents could not be prepared for inference")
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
        return NativeVlmEngine(NativeVlmEngine.NO_LANGUAGE_DESCRIPTOR, "", config.vlm, log, clock)
    }

    /**
     * Real native-runtime state for diagnostics.
     *
     * This used to report `models.allReady()`, which said "nativeVlm=true" purely because two files
     * had been picked — even when `libheimdall.so` was missing from the APK. The library load result,
     * its failure reason and the device ABI are the facts that actually explain an unloaded backend.
     */
    fun nativeStatus(): String = runCatching {
        "nativeLibrary=${VlmRuntime.isLoaded} backend=${VlmRuntime.backend()} " +
            "abi=${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"} " +
            "loadFailure=${VlmRuntime.loadFailure ?: "none"}"
    }.getOrDefault("nativeLibrary=unknown")

    /**
     * Result of the last [probeModels] run, or null when it has not run for this selection.
     *
     * Metadata rejects what it can prove is wrong; this holds llama.cpp's own verdict on the exact
     * pair, which is the only way to know a Qwen3.5-VL/Bonsai pair really loads. It is never mixed
     * with the metadata verdict: `models.describe()` reports what the files claim, this reports
     * what the runtime did.
     */
    @Volatile
    var modelProbe: String? = null
        private set

    /** llama.cpp/ggml log tail of the last probe, exported with the diagnostics report. */
    @Volatile
    private var probeNativeLog: String = ""

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
            onDone("runtime not tested: stop the capture session first")
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
                val message = "runtime not tested: the GGUF metadata already rejects the pair (${issue.detail})"
                modelProbe = message
                probeKey = key
                onDone(message)
                return
            }
            probing = true
        }
        // Opening the descriptor and importing the projector are both disk work, so they happen on
        // the probe thread rather than on whichever thread tapped the button.
        Thread({
            val probe = runCatching<ProbeOutcome?> {
                val prepared = models.prepare()
                if (prepared == null) {
                    null
                } else {
                    try {
                        ProbeOutcome(VlmRuntime.probePair(prepared.languageFd, prepared.projectorPath, log))
                    } finally {
                        prepared.close()
                    }
                }
            }.getOrElse { error ->
                log.warn(TAG, "model probe failed", error)
                ProbeOutcome(
                    VlmRuntime.PairProbe(false, error.message ?: error.javaClass.simpleName),
                )
            }
            val message = when {
                probe == null -> "runtime not tested: the selected documents cannot be opened"
                probe.probe.ready -> "runtime loads this pair on this device (${probe.probe.message})"
                else -> "runtime rejected the pair: ${probe.probe.message}"
            }
            synchronized(this) {
                modelProbe = message
                probeNativeLog = probe?.probe?.nativeLog.orEmpty()
                probeKey = key
                probing = false
            }
            if (probe?.probe?.ready == true) {
                log.info(TAG, "model pair probe: $message")
            } else {
                log.warn(TAG, "model pair probe: $message")
                probe?.probe?.nativeLog.orEmpty().lineSequence()
                    .filter { it.isNotBlank() }
                    .forEach { log.info(TAG, "llama.cpp: $it") }
            }
            onDone(message)
        }, "heimdall-model-probe").start()
    }

    /** Wrapper so `null` can mean "the documents could not even be opened". */
    private class ProbeOutcome(val probe: VlmRuntime.PairProbe)

    /** Diagnostics bundle for the export flow: environment + recent log lines. */
    fun diagnosticsReport(): String = buildString {
        appendLine("# Heimdall diagnostics")
        appendLine("version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        // Metadata and the runtime verdict are separate lines on purpose: a "ready" metadata
        // verdict only means the files look compatible, not that llama.cpp loaded them.
        appendLine("metadata: ${models.describe()}")
        appendLine("runtime.pair: ${modelProbe ?: "not tested (metadata only; tap 'Test pair')"}")
        appendLine("native: ${nativeStatus()}")
        if (probeNativeLog.isNotBlank()) {
            appendLine()
            appendLine("## llama.cpp log (last probe)")
            probeNativeLog.lineSequence()
                .filter { it.isNotBlank() }
                .forEach { appendLine(it) }
            appendLine()
        }
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
