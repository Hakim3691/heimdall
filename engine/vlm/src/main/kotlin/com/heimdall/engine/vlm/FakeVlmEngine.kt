package com.heimdall.engine.vlm

import com.heimdall.model.RgbImage

/**
 * Scripted [VlmEngine] used by instrumented tests, the debug build and benchmark warm-ups.
 *
 * It exists in `main` (not test sources) because the debug app ships with it so that the whole
 * capture → cascade → overlay path can be exercised on a real device without multi-GB weights.
 */
class FakeVlmEngine(
    private val responses: MutableList<String> = mutableListOf(),
    private val backend: String = "fake",
) : VlmEngine {
    val requests: MutableList<VlmRequest> = mutableListOf()

    /** Response returned for every frame; ignored once [responses] has entries. */
    var defaultResponse: String = NOTHING_TO_DO

    var ready: Boolean = true

    private var cursor = 0

    override val isReady: Boolean get() = ready

    override val backendDescription: String get() = backend

    fun enqueue(vararg json: String) {
        responses += json
    }

    override suspend fun evaluate(request: VlmRequest): RawVlmResponse {
        requests += request
        if (!ready) throw VlmException("fake engine not ready")
        val text = when {
            responses.isEmpty() -> defaultResponse
            else -> {
                val index = cursor.coerceAtMost(responses.lastIndex)
                cursor++
                responses[index]
            }
        }
        return RawVlmResponse(text = text, latencyMs = 1L, backend = backend)
    }

    override fun close() {
        ready = false
    }

    fun imageSizes(): List<Pair<Int, Int>> = requests.map { it.image.width to it.image.height }

    companion object {
        const val NOTHING_TO_DO: String = """{"intervene": false}"""

        fun intervention(text: String, confidence: Double = 0.9, anchor: String = "bottom"): String =
            """{"intervene": true, "confidence": $confidence, "text": "$text", "anchor": "$anchor"}"""

        fun uniformImage(width: Int = 448, height: Int = 448): RgbImage = RgbImage.uniform(width, height)
    }
}
