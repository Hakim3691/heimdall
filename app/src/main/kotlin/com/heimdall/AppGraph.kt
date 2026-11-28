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
import java.util.concurrent.atomic.AtomicBoolean

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

    /** True when [modelProbe] is a runtime that actually loaded the selection. */
    @Volatile
    var probeReady: Boolean = false
        private set

    /** UI-facing state of the runtime probe; only [ProbeState.Running] is non-terminal. */
    @Volatile
    var probeState: ProbeState = ProbeState.Idle
        private set

    /** True while llama.cpp is loading the selection for [probeModels]. */
    val isProbing: Boolean get() = probeState is ProbeState.Running

    /** True when [modelProbe] describes the documents selected right now. */
    val probeMatchesSelection: Boolean
        get() = modelProbe != null && probeKey == selectionKey()

    /** Seconds the running probe has been busy, 0 when it is not running. */
    val probeElapsedSeconds: Long
        get() = if (isProbing) ((clock.nowMs() - probeStartedAtMs) / 1_000L).coerceAtLeast(0L) else 0L

    /**
     * Result of a runtime probe, from the screen's point of view.
     *
     * The probe used to report only through a callback that showed a toast while loading, so a
     * rejection that took minutes was indistinguishable from nothing happening. The screen needs a
     * running state (with elapsed time) and a terminal verdict that carries the log tail.
     */
    sealed interface ProbeState {
        /** Nothing has been probed for the current selection yet. */
        data object Idle : ProbeState

        /** llama.cpp is loading the pair. */
        data object Running : ProbeState

        /** Terminal verdict, with the runtime log tail that explains a rejection. */
        data class Finished(
            val message: String,
            val ready: Boolean,
            val nativeLog: String = "",
        ) : ProbeState
    }

    /** llama.cpp/ggml log tail of the last probe, exported with the diagnostics report. */
    @Volatile
    private var probeNativeLog: String = ""

    private var probeKey: String? = null
    private var probeStartedAtMs = 0L
    private var probing = false

    /**
     * Loads the selected pair in the native runtime on a background thread and reports the result.
     *
     * Skipped while capture owns the runtime (the probe frees it), and cached per document pair so
     * focus changes do not reload gigabytes.
     */
    fun probeModels(onDone: (ProbeState.Finished) -> Unit = {}) {
        if (HeimdallCaptureService.isRunning) {
            onDone(ProbeState.Finished(PROBE_NEEDS_STOP, ready = false))
            return
        }
        val key = selectionKey()
        synchronized(this) {
            if (probing) return
            val cached = modelProbe?.takeIf { probeKey == key }
            if (cached != null) {
                onDone(ProbeState.Finished(cached, probeReady, probeNativeLog))
                return
            }
            val issue = models.pairIssue()
            if (issue != null) {
                val state = ProbeState.Finished(
                    "runtime not tested: the GGUF metadata already rejects the pair (${issue.detail})",
                    ready = false,
                )
                publishProbe(state, key)
                onDone(state)
                return
            }
            probing = true
            probeKey = key
            probeStartedAtMs = clock.nowMs()
            probeState = ProbeState.Running
        }
        // The watchdog and the worker race to finish; only the first one may open a dialog.
        val delivered = AtomicBoolean(false)
        val deliver: (ProbeState.Finished) -> Unit = { state ->
            if (delivered.compareAndSet(false, true)) onDone(state)
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
            val state = ProbeState.Finished(
                message = message,
                ready = probe?.probe?.ready == true,
                nativeLog = probe?.probe?.nativeLog.orEmpty(),
            )
            synchronized(this) {
                publishProbe(state, key)
                probing = false
            }
            if (state.ready) {
                log.info(TAG, "model pair probe: $message")
            } else {
                log.warn(TAG, "model pair probe: $message")
                state.nativeLog.lineSequence()
                    .filter { it.isNotBlank() }
                    .forEach { log.info(TAG, "llama.cpp: $it") }
            }
            deliver(state)
        }, "heimdall-model-probe").start()

        // The native load cannot be interrupted, so this only frees the screen: it publishes a
        // timeout verdict and leaves `probing` latched, which refuses a second load on top of a
        // runtime that is still opening gigabytes.
        Thread({
            runCatching { Thread.sleep(PROBE_TIMEOUT_MS) }
            val timedOut = synchronized(this) {
                if (probeState is ProbeState.Running) {
                    publishProbe(ProbeState.Finished(PROBE_TIMEOUT, ready = false), key)
                    true
                } else {
                    false
                }
            }
            if (timedOut) {
                log.warn(TAG, "model pair probe timed out after ${PROBE_TIMEOUT_MS / 1_000}s")
                deliver(ProbeState.Finished(PROBE_TIMEOUT, ready = false))
            }
        }, "heimdall-model-probe-watchdog").start()
    }

    private fun selectionKey(): String =
        ModelFile.entries.joinToString("|") { models.uriFor(it)?.toString().orEmpty() }

    private fun publishProbe(state: ProbeState.Finished, key: String) = synchronized(this) {
        modelProbe = state.message
        probeReady = state.ready
        probeNativeLog = state.nativeLog
        probeKey = key
        probeState = state
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
        appendLine(
            "probe: " + when {
                isProbing -> "running for ${probeElapsedSeconds}s"
                modelProbe != null && probeMatchesSelection -> if (probeReady) "ready" else "rejected"
                else -> "not tested for the current selection"
            },
        )
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

        /** Generous: an 11 GB content-provider read plus one decode, on a phone. */
        const val PROBE_TIMEOUT_MS = 180_000L
        const val PROBE_TIMEOUT =
            "runtime probe timed out after 3 minutes — the pair is too large for this device, or " +
                "the file provider stalled. The llama.cpp log in the diagnostics export has the detail."
        const val PROBE_NEEDS_STOP = "runtime not tested: stop the capture session first"
    }
}
