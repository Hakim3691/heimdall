package com.heimdall.engine.vlm

import com.heimdall.common.HeimdallLog

/**
 * Public, read-only view of the native runtime for diagnostics.
 *
 * `NativeVlmBridge` is module-internal so the JNI surface cannot be called from the app layer; this
 * object exposes only the facts onboarding and the exported diagnostics need, plus the one action
 * that answers the only question metadata cannot: does this exact pair load on this device?
 */
object VlmRuntime {
    /**
     * Outcome of [probePair].
     *
     * [nativeLog] is the captured llama.cpp/ggml log tail for this attempt, so a refusal can be
     * explained here and reproduced from an exported diagnostics report without adb.
     */
    data class PairProbe(val ready: Boolean, val message: String, val nativeLog: String = "")

    /** True when `libheimdall.so` loaded for this process. Forces the (cached) load attempt. */
    val isLoaded: Boolean get() = NativeVlmBridge.ensureLoaded()

    /** Human-readable reason the library could not load, or null when it loaded. */
    val loadFailure: String? get() = NativeVlmBridge.loadFailure

    /** Backend reported by the runtime, e.g. "cpu", "vulkan" or "unloaded". */
    fun backend(): String =
        if (NativeVlmBridge.ensureLoaded()) {
            runCatching { NativeVlmBridge.nativeBackend() }.getOrDefault("unknown")
        } else {
            "unavailable"
        }

    /**
     * Loads [modelPath] + [mmprojPath] for real, runs one tiny frame, then releases the runtime.
     *
     * This is the authoritative compatibility check: the GGUF metadata rules predict, but only
     * llama.cpp knows whether a language architecture and a projector type actually work together.
     * Callers must ensure no capture session owns the runtime, because the probe frees it at the
     * end.
     */
    fun probePair(
        modelPath: String,
        mmprojPath: String,
        log: HeimdallLog = HeimdallLog.NONE,
    ): PairProbe {
        if (!NativeVlmBridge.ensureLoaded(log)) {
            return PairProbe(false, "native library unavailable: ${NativeVlmBridge.loadFailure ?: "no library"}")
        }
        return try {
            val loaded = NativeVlmBridge.nativeInit(
                modelPath = modelPath,
                mmprojPath = mmprojPath,
                contextSize = 512,
                threads = 2,
                gpuLayers = 0,
            )
            if (!loaded) {
                val logTail = NativeVlmBridge.logTail()
                val refusal = NativeLoadFailure.explain(
                    message = NativeVlmBridge.lastError() ?: "llama.cpp refused the pair",
                    nativeLog = logTail,
                )
                PairProbe(false, refusal, logTail)
            } else {
                // One real decode proves the multimodal marker, projector and vocabulary agree.
                val argb = IntArray(PROBE_EDGE * PROBE_EDGE) { 0xFF808080.toInt() }
                NativeVlmBridge.nativeEvaluate(
                    argb = argb,
                    width = PROBE_EDGE,
                    height = PROBE_EDGE,
                    prompt = "Answer with one word.",
                    maxTokens = 1,
                    temperature = 0f,
                )
                PairProbe(true, NativeVlmBridge.nativeBackend(), NativeVlmBridge.logTail())
            }
        } catch (failure: Throwable) {
            PairProbe(false, failure.message ?: failure.javaClass.simpleName)
        } finally {
            runCatching { NativeVlmBridge.nativeRelease() }
        }
    }

    /** One diagnostics line: library state, backend and the load failure if there is one. */
    fun describe(): String = buildString {
        append("library=")
        append(if (NativeVlmBridge.ensureLoaded()) "loaded" else "missing")
        append(" backend=")
        append(backend())
        append(" loadFailure=")
        append(NativeVlmBridge.loadFailure ?: "none")
    }

    private const val PROBE_EDGE = 64
}
