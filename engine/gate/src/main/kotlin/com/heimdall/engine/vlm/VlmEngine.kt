package com.heimdall.engine.vlm

import com.heimdall.model.RgbImage

/** One inference request: a frame plus the prompt to run against it. */
data class VlmRequest(
    val image: RgbImage,
    val prompt: String,
)

/** Raw model output plus timing, before the schema gate. */
data class RawVlmResponse(
    val text: String,
    val latencyMs: Long,
    /** Backend that produced the answer, e.g. "vulkan" or "cpu"; diagnostics only. */
    val backend: String = "unknown",
)

/** Raised when the engine cannot evaluate a frame at all (distinct from a silent model answer). */
class VlmException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The local vision-language runtime.
 *
 * Implementations must be safe to call from a background dispatcher, must never touch the network
 * (the app has no INTERNET permission — this interface is the only model boundary), and must be
 * cheap to keep alive between frames.
 */
interface VlmEngine : AutoCloseable {
    /** True when weights are loaded and a request can be served. */
    val isReady: Boolean

    /** Human-readable backend description for diagnostics, e.g. "llama.cpp/vulkan". */
    val backendDescription: String

    /** Runs one frame through the model. Throws [VlmException] on unrecoverable engine failure. */
    suspend fun evaluate(request: VlmRequest): RawVlmResponse

    override fun close()
}
