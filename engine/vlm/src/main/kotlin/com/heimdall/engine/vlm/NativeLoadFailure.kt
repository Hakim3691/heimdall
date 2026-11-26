package com.heimdall.engine.vlm

/**
 * Turns a native load refusal into a sentence that names the actual cause.
 *
 * llama.cpp returns a null model pointer and explains itself only through the log, which
 * [NativeVlmBridge.logTail] now captures. The two refusals users actually hit are not bugs in the
 * pair but in the *file packing*: PrismML publishes Bonsai weights in private tensor types
 * (`PQ2_0`/`PTQ1_0`, ggml types 142/143) and with an `-MTP-` drafter head, neither of which exists
 * in upstream llama.cpp. Saying "refused the language weights" hides that; naming the packing and
 * the file to download instead does not.
 */
object NativeLoadFailure {
    /** Private PrismML packing: ggml type 142 (`PQ2_0`) or 143 (`PTQ1_0`). */
    private val PRIVATE_TYPE = Regex("""invalid ggml type (142|143)\b""")

    /** The same files often name their quantisation in the tensor or metadata lines. */
    private val PRIVATE_QUANT = Regex("""\b(pq2_0|ptq1_0)\b""", RegexOption.IGNORE_CASE)

    /** Any other out-of-range tensor type: still a packing the bundled build cannot read. */
    private val UNKNOWN_TYPE = Regex("""invalid ggml type (\d+)""")

    /** A drafter/MTP head appended to the weights (`...-MTP-...`, `blk.mtp.`). */
    private val MTP_HEAD = Regex("""(^|[^a-z])mtp([^a-z]|$)""", RegexOption.IGNORE_CASE)

    private const val PRIVATE_PACKING =
        "these weights use PrismML's private packing (PQ2_0/PTQ1_0, ggml tensor type 142/143), " +
            "which the bundled upstream llama.cpp cannot read. Download the Q2_0 rebuild of the " +
            "same weights, or a standard Q4/Q8 GGUF, and test again"

    private const val MTP =
        "these weights carry a multi-token-prediction (MTP) drafter head that the bundled " +
            "llama.cpp does not implement. Use the non-MTP weights and test again"

    /**
     * @param message the generic refusal from [NativeVlmBridge.lastError], used when the log has
     *   nothing more specific to say.
     * @param nativeLog the captured llama.cpp/ggml log tail for the failed load.
     * @return a single actionable line; never blank.
     */
    fun explain(message: String, nativeLog: String): String {
        val combined = message + "\n" + nativeLog
        val known = when {
            PRIVATE_TYPE.containsMatchIn(combined) || PRIVATE_QUANT.containsMatchIn(combined) -> PRIVATE_PACKING
            MTP_HEAD.containsMatchIn(combined) -> MTP
            UNKNOWN_TYPE.containsMatchIn(combined) -> {
                val type = UNKNOWN_TYPE.find(combined)?.groupValues?.get(1)
                "these weights use a tensor packing this llama.cpp build does not know" +
                    (type?.let { " (ggml type $it)" } ?: "") +
                    ". Download a standard quantisation of the same model and test again"
            }
            else -> null
        }
        val evidence = firstProblemLine(nativeLog)
        return buildString {
            append(known ?: message.ifBlank { "llama.cpp refused the pair" })
            if (evidence != null) {
                append(" (llama.cpp: ")
                append(evidence)
                append(')')
            }
        }
    }

    /** First `error:` line, else the last `warn:` line, trimmed for inline display. */
    private fun firstProblemLine(log: String): String? {
        val lines = log.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val problem = lines.firstOrNull { it.startsWith("error:") }
            ?: lines.lastOrNull { it.startsWith("warn:") }
            ?: return null
        return problem.substringAfter(':').trim().take(240)
    }
}
