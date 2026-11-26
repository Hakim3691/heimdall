package com.heimdall.engine.vlm

/**
 * Public, read-only view of the native runtime for diagnostics.
 *
 * `NativeVlmBridge` is module-internal so the JNI surface cannot be called from the app layer; this
 * object exposes only the facts onboarding and the exported diagnostics need, without allowing a
 * caller to initialise or evaluate anything.
 */
object VlmRuntime {
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

    /** One diagnostics line: library state, backend and the load failure if there is one. */
    fun describe(): String = buildString {
        append("library=")
        append(if (NativeVlmBridge.ensureLoaded()) "loaded" else "missing")
        append(" backend=")
        append(backend())
        append(" loadFailure=")
        append(NativeVlmBridge.loadFailure ?: "none")
    }
}
