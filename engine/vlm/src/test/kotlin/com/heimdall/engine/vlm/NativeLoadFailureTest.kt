package com.heimdall.engine.vlm

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** The refusal sentence has to name the packing, because that is what the user can act on. */
class NativeLoadFailureTest {
    private val generic = "llama.cpp refused the language weights"

    @Test
    fun `private PQ2_0 tensor type is reported as PrismML packing`() {
        val log = "error: gguf_init_from_file: tensor 'blk.0.attn_q.weight' has invalid ggml type 142. " +
            "should be in [0, 44)"

        val message = NativeLoadFailure.explain(generic, log)

        assertThat(message).contains("Q2_0")
        assertThat(message).contains("142")
        assertThat(message).contains("llama.cpp: gguf_init_from_file: tensor 'blk.0.attn_q.weight'")
        assertThat(message).doesNotContain(generic)
    }

    @Test
    fun `private PTQ1_0 tensor type is reported the same way`() {
        val log = "error: tensor 'token_embd.weight' has invalid ggml type 143. should be in [0, 44)"

        assertThat(NativeLoadFailure.explain(generic, log)).contains("Q2_0")
    }

    @Test
    fun `a drafter MTP tensor is reported as an unsupported head`() {
        val log = "error: gguf_init_from_file: tensor 'blk.3.-MTP-fc.weight' has an unknown name"

        val message = NativeLoadFailure.explain(generic, log)

        assertThat(message).contains("MTP")
        assertThat(message).contains("non-MTP")
    }

    @Test
    fun `an unknown quantisation type still names the type and the log line`() {
        val log = "error: tensor 'blk.0.ffn_down.weight' has invalid ggml type 99. should be in [0, 44)"

        val message = NativeLoadFailure.explain(generic, log)

        assertThat(message).contains("ggml type 99")
        assertThat(message).contains("standard quantisation")
    }

    @Test
    fun `a refusal without a captured log keeps the generic message`() {
        assertThat(NativeLoadFailure.explain(generic, "")).isEqualTo(generic)
    }

    @Test
    fun `a blank message never produces a blank verdict`() {
        assertThat(NativeLoadFailure.explain("", "")).isNotEmpty()
    }

    @Test
    fun `projector failures keep llama's own words`() {
        val log = "error: clip_model_load: expected 'clip.projector_type' but got 'resampler'"

        val message = NativeLoadFailure.explain("llama.cpp refused the vision projector", log)

        assertThat(message).contains("clip_model_load")
        assertThat(message).contains("projector")
    }
}
