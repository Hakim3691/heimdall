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
    /**
     * Read-only descriptor of the picked language document; the native loader duplicates it and
     * reads the GGUF straight from it. [NO_LANGUAGE_DESCRIPTOR] means no weights are selected, so
     * the engine only reports why it cannot run.
     */
    private val languageDescriptor: Int,
    /** Path of the projector imported into app storage (`mtmd` takes a path, not a descriptor). */
    private val mmprojPath: String,
    private val config: VlmConfig = VlmConfig(),
    private val log: HeimdallLog = HeimdallLog.NONE,
    private val clock: Clock = Clock.MONOTONIC,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * The open document behind [languageDescriptor], or null when the weights are ordinary files.
     * Closed together with the engine so the descriptor never outlives the session that resolved it.
     */
    private val languageHandle: AutoCloseable? = null,
) : VlmEngine {
    private val lock = Any()
    private var initialised = false
    private var initialisationFailure: String? = null

    /**
     * Why the last load attempt failed, or null when it has not failed.
     *
     * The onboarding screen shows this instead of a bare "rejected": since the load is now the
     * automatic test, its failure reason is the answer to "is the model ready?".
     */
    val initialisationError: String?
        get() = synchronized(lock) { initialisationFailure }

    /** llama.cpp's own log tail for the last failed load; empty when there is nothing to quote. */
    @Volatile
    var lastLogTail: String = ""
        private set

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
                languageFd = languageDescriptor,
                mmprojPath = mmprojPath,
                contextSize = config.contextSize,
                threads = config.threads,
                gpuLayers = config.gpuLayers,
            )
        }.onFailure { log.error(TAG, "nativeInit threw", it) }
            .getOrDefault(false)
        if (!loaded) {
            lastLogTail = runCatching { NativeVlmBridge.logTail() }.getOrDefault("")
            initialisationFailure = runCatching { NativeVlmBridge.lastError() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: "model rejected by llama.cpp"
            log.error(TAG, "nativeInit returned false for descriptor $languageDescriptor")
            return false
        }
        initialised = true
        log.info(TAG, "VLM ready ($backendDescription) ctx=${config.contextSize} threads=${config.threads}")
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
            languageHandle?.let { handle ->
                runCatching { handle.close() }
                    .onFailure { log.warn(TAG, "closing the language descriptor threw", it) }
            }
            log.info(TAG, "VLM released")
        }
    }

    companion object {
        /** No weights selected: [NativeVlmBridge.nativeInit] refuses the call with a reason. */
        const val NO_LANGUAGE_DESCRIPTOR: Int = -1

        private const val TAG = "NativeVlmEngine"
    }
}
