package com.heimdall.engine.vlm

import com.heimdall.common.Clock
import com.heimdall.common.HeimdallLog
import com.heimdall.model.VlmConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Production [VlmEngine] backed by the NDK llama.cpp runtime.
 *
 * Initialisation is lazy and serialised: the first frame pays the model load (mmap of several GB),
 * every later frame only pays decode. The engine is owned by the capture service, so [close]
 * releases native memory deterministically when the service stops.
 */
class NativeVlmEngine(
    private val modelPath: String,
    private val mmprojPath: String,
    private val config: VlmConfig = VlmConfig(),
    private val log: HeimdallLog = HeimdallLog.NONE,
    private val clock: Clock = Clock.MONOTONIC,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : VlmEngine {
    private val lock = Any()
    private var initialised = false
    private var initialisationFailure: String? = null

    override val isReady: Boolean
        get() = synchronized(lock) { initialised }

    override val backendDescription: String
        get() = if (NativeVlmBridge.ensureLoaded(log)) {
            runCatching { NativeVlmBridge.nativeBackend() }.getOrDefault("unknown")
        } else {
            "unavailable(${NativeVlmBridge.loadFailure ?: "no library"})"
        }

    /** Loads weights if needed. Returns false when the runtime cannot serve this device. */
    fun ensureInitialised(): Boolean = synchronized(lock) {
        if (initialised) return true
        if (initialisationFailure != null) return false
        if (!NativeVlmBridge.ensureLoaded(log)) {
            initialisationFailure = "native library unavailable"
            return false
        }
        val loaded = runCatching {
            NativeVlmBridge.nativeInit(
                modelPath = modelPath,
                mmprojPath = mmprojPath,
                contextSize = config.contextSize,
                threads = config.threads,
                gpuLayers = config.gpuLayers,
            )
        }.onFailure { log.error(TAG, "nativeInit threw", it) }
            .getOrDefault(false)
        if (!loaded) {
            initialisationFailure = "model rejected by llama.cpp"
            log.error(TAG, "nativeInit returned false for $modelPath")
            return false
        }
        initialised = true
        log.info(TAG, "VLM ready (${backendDescription}) ctx=${config.contextSize} threads=${config.threads}")
        true
    }

    override suspend fun evaluate(request: VlmRequest): RawVlmResponse = withContext(dispatcher) {
        if (!ensureInitialised()) {
            throw VlmException(initialisationFailure ?: "engine not initialised")
        }
        val startedAt = clock.nowMs()
        val text = runCatching {
            NativeVlmBridge.nativeEvaluate(
                argb = request.image.argb,
                width = request.image.width,
                height = request.image.height,
                prompt = request.prompt,
                maxTokens = config.maxOutputTokens,
                temperature = config.temperature,
            )
        }.getOrElse { error ->
            log.error(TAG, "inference failed", error)
            throw VlmException("inference failed: ${error.message}", error)
        }
        val elapsed = clock.nowMs() - startedAt
        if (elapsed > config.inferenceTimeoutMs) {
            log.warn(TAG, "inference exceeded budget: ${elapsed}ms")
        }
        RawVlmResponse(text = text, latencyMs = elapsed, backend = backendDescription)
    }

    override fun close() {
        synchronized(lock) {
            if (initialised && NativeVlmBridge.ensureLoaded(log)) {
                runCatching { NativeVlmBridge.nativeRelease() }
                    .onFailure { log.warn(TAG, "nativeRelease threw", it) }
            }
            initialised = false
            log.info(TAG, "VLM released")
        }
    }

    private companion object {
        const val TAG = "NativeVlmEngine"
    }
}
