package com.heimdall.engine.vlm

import com.heimdall.common.HeimdallLog

/**
 * JNI surface of the llama.cpp/ggml runtime (TRD §7).
 *
 * The library is loaded lazily and defensively: a missing or ABI-mismatched `libheimdall.so` must
 * degrade to "no model" instead of crashing the service, because Heimdall runs inside the user's
 * foreground experience.
 */
internal object NativeVlmBridge {
    private const val TAG = "NativeVlmBridge"
    private const val LIBRARY = "heimdall"

    /** Null when the native library loaded; otherwise the reason it did not. */
    var loadFailure: String? = null
        private set

    val isLoaded: Boolean get() = loadFailure == null

    fun ensureLoaded(log: HeimdallLog = HeimdallLog.NONE): Boolean {
        if (isLoaded && loadFailure == null && loaded) return true
        if (loaded) return true
        return synchronized(this) {
            if (!loaded) {
                loadFailure = runCatching { System.loadLibrary(LIBRARY) }
                    .exceptionOrNull()
                    ?.let { error ->
                        log.warn(TAG, "native library '$LIBRARY' unavailable", error)
                        "${error.javaClass.simpleName}: ${error.message}"
                    }
                loaded = loadFailure == null
            }
            loaded
        }
    }

    private var loaded = false

    /** Loads language weights + vision projector. Returns false when the runtime refuses them. */
    external fun nativeInit(
        modelPath: String,
        mmprojPath: String,
        contextSize: Int,
        threads: Int,
        gpuLayers: Int,
    ): Boolean

    /** Runs one frame (ARGB ints) and returns the raw model text. */
    external fun nativeEvaluate(
        argb: IntArray,
        width: Int,
        height: Int,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
    ): String

    /** e.g. "vulkan", "cpu", or "unloaded". */
    external fun nativeBackend(): String

    /** Why the last [nativeInit] was refused; empty when it succeeded. */
    external fun nativeLastError(): String

    /** Kotlin-side view of [nativeLastError], null when there is no message. */
    fun lastError(): String? = runCatching { nativeLastError() }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Tail of what llama.cpp/ggml logged during the last load, one message per line.
     *
     * This is where the real refusal lives ("tensor '...' has invalid ggml type 142"); the
     * boolean returned by [nativeInit] says only that the load failed.
     */
    external fun nativeLogTail(): String

    /** [nativeLogTail] that degrades to an empty string when the runtime is unavailable. */
    fun logTail(): String = runCatching { nativeLogTail() }.getOrDefault("")

    /** Frees model/context memory. Safe to call repeatedly. */
    external fun nativeRelease()
}
