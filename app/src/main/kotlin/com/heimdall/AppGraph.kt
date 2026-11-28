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
import com.heimdall.model.ThermalTier
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
            overlay = OverlayConfig(
                expandedHelpEnabled = settings.expandedHelpEnabled,
                opacity = settings.overlayOpacity,
            ),
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

    // ---- resident model ---------------------------------------------------------------------

    private val engineLock = Any()
    private var residentEngine: VlmEngine? = null
    private var residentKey: String? = null

    /**
     * The loaded model, kept for the life of the process.
     *
     * Mapping 11 GB of weights and importing a projector takes minutes on a phone, so it happens
     * once: the automatic check on the setup screen leaves its engine here and the capture service
     * reuses it instead of loading a second copy. Only [closeEngine] frees it.
     */
    fun acquireEngine(): VlmEngine = synchronized(engineLock) {
        val key = selectionKey()
        residentEngine?.let { existing ->
            if (residentKey == key) return existing
            log.info(TAG, "model selection changed; releasing the previous engine")
            runCatching { existing.close() }
        }
        val created = createEngine()
        residentEngine = created
        residentKey = key
        created
    }

    /** Frees the native runtime and the open language document. Safe when nothing is loaded. */
    fun closeEngine(reason: String) {
        synchronized(engineLock) {
            val engine = residentEngine ?: return
            log.info(TAG, "releasing the model ($reason)")
            runCatching { engine.close() }
            residentEngine = null
            residentKey = null
        }
    }

    /** True when the model for the current selection is already loaded and kept in memory. */
    val engineResident: Boolean
        get() = synchronized(engineLock) { residentEngine != null && residentKey == selectionKey() }

    // ---- thermal ----------------------------------------------------------------------------

    /** Last platform thermal status seen while a session ran (`PowerManager.THERMAL_STATUS_*`). */
    @Volatile
    var thermalStatus: Int = ThermalTier.PLATFORM_NONE
        private set

    /** Applies a platform thermal status to the governor and remembers it for the setup screen. */
    fun noteThermalStatus(status: Int) {
        thermalStatus = status
        governor.onThermalStatus(status)
    }

    /**
     * One line for the setup screen and the diagnostics: what heat is doing to the pipeline.
     *
     * A paused governor is the difference between "Heimdall is broken" and "Heimdall is waiting for
     * the phone to cool down", which is otherwise only visible in the notification.
     */
    fun thermalSummary(): String = if (governor.isExecutionPaused) {
        "PAUSED — the device is too warm, so no inference runs until it cools"
    } else {
        val seconds = governor.dutyIntervalMs / 1_000L
        "${governor.tier} — at most one inference every ${seconds}s"
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

    /** llama.cpp/ggml log tail of the last load, exported with the diagnostics report. */
    @Volatile
    private var probeNativeLog: String = ""

    /** The same tail, for the setup screen's failure text. */
    val probeLogTail: String get() = probeNativeLog

    private var probeKey: String? = null
    private var probeStartedAtMs = 0L
    private var probing = false

    /**
     * Loads the selected pair in the real runtime and reports whether it works on this device.
     *
     * This is the automatic check the setup screen runs on launch, and the reason there is no test
     * button: it builds the *production* engine, initialises it, and leaves it resident in
     * [acquireEngine]'s slot. A successful test therefore ends with the model ready to work rather
     * than freeing gigabytes that would have to be mapped again by the next session. Cached per
     * document pair so returning to the screen does not reload anything.
     */
    fun probeModels(onDone: (ProbeState.Finished) -> Unit = {}) {
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
        // The watchdog and the worker race to finish; only the first one may report.
        val delivered = AtomicBoolean(false)
        val deliver: (ProbeState.Finished) -> Unit = { state ->
            if (delivered.compareAndSet(false, true)) onDone(state)
        }
        // Opening the descriptor, importing the projector and mapping the weights are all disk
        // work, so they happen here rather than on whichever thread asked for the check.
        Thread({
            val state = runCatching { loadSelectedPair() }.getOrElse { error ->
                log.warn(TAG, "loading the model failed", error)
                ProbeState.Finished(
                    "the model could not be loaded: ${error.message ?: error.javaClass.simpleName}",
                    ready = false,
                )
            }
            synchronized(this) {
                publishProbe(state, key)
                probing = false
            }
            if (state.ready) {
                log.info(TAG, "model ready: ${state.message}")
            } else {
                log.warn(TAG, "model not ready: ${state.message}")
                state.nativeLog.lineSequence()
                    .filter { it.isNotBlank() }
                    .forEach { log.info(TAG, "llama.cpp: $it") }
            }
            deliver(state)
        }, "heimdall-model-load").start()

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

    /**
     * Builds the production engine for the current selection and initialises it, keeping it resident.
     *
     * A failed load is not cached: the engine is released so the next attempt (or the capture
     * service) starts from scratch, which is what a transient provider or memory error needs.
     */
    private fun loadSelectedPair(): ProbeState.Finished {
        val engine = acquireEngine() as? NativeVlmEngine
            ?: return ProbeState.Finished(
                "the scripted debug engine is in use, so there is no runtime to test",
                ready = false,
            )
        if (engine.ensureInitialised()) {
            return ProbeState.Finished(
                "llama.cpp loaded this pair on this device (${engine.backendDescription})",
                ready = true,
                nativeLog = engine.lastLogTail,
            )
        }
        val reason = engine.initialisationError ?: "llama.cpp refused the pair"
        val tail = engine.lastLogTail
        closeEngine("the model did not load")
        return ProbeState.Finished("llama.cpp refused the pair: $reason", ready = false, nativeLog = tail)
    }

    /**
     * Forgets the last verdict and frees the model so the next check starts from scratch, which is
     * what "Check again" means after a failure or a re-picked file.
     */
    fun invalidateProbe() {
        if (isProbing) return
        closeEngine("re-check requested")
        synchronized(this) {
            modelProbe = null
            probeReady = false
            probeNativeLog = ""
            probeKey = null
            probeState = ProbeState.Idle
        }
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

    /** Diagnostics bundle for the export flow: environment + recent log lines. */
    fun diagnosticsReport(): String = buildString {
        appendLine("# Heimdall diagnostics")
        appendLine("version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        // Metadata and the runtime verdict are separate lines on purpose: a "ready" metadata
        // verdict only means the files look compatible, not that llama.cpp loaded them.
        appendLine("metadata: ${models.describe()}")
        appendLine("runtime.pair: ${modelProbe ?: "not loaded yet (it loads automatically when both files are present)"}")
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
        appendLine("model: resident=${engineResident} thermal=${thermalSummary()} rawThermalStatus=$thermalStatus")
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
    }
}
