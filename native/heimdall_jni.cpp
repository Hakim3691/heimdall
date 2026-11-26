// ---------------------------------------------------------------------------------------------
// Heimdall native VLM runtime — JNI wrapper over llama.cpp + mtmd.
//
// Design notes
//  * The model is loaded once (mmap, several GB) and kept for the process lifetime; the decode
//    context is created per evaluation and freed immediately. That trades a few MB of allocation
//    for a guaranteed-clean KV cache on every frame, which matters because a stale image token
//    from the previous screen would silently poison the next answer.
//  * Greedy sampling: the response is a small JSON object, determinism is worth more than variety,
//    and a shorter sampler chain means fewer tokens of work per frame.
//  * All entry points are serialised by one mutex. The Kotlin side already runs inference on a
//    single background dispatcher; this makes the native layer safe regardless.
//  * Vulkan is a compile-time choice (HEIMDALL_BUILD_VULKAN). When ggml is built with the Vulkan
//    backend, layer offload is requested via n_gpu_layers; otherwise llama.cpp falls back to CPU.
// ---------------------------------------------------------------------------------------------

#include <jni.h>

#include <android/log.h>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define LOG_TAG "HeimdallNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifdef HEIMDALL_HAS_VULKAN
static constexpr const char * kBackend = "llama.cpp/vulkan";
#else
static constexpr const char * kBackend = "llama.cpp/cpu";
#endif

namespace {

struct RuntimeState {
    llama_model * model = nullptr;
    mtmd_context * vision = nullptr;
    int contextSize = 2048;
    int threads = 4;
    int gpuLayers = 0;
    bool backendInitialised = false;
};

RuntimeState g_state;
std::mutex g_mutex;
// Last load/init failure, surfaced to Kotlin so onboarding can explain a refused pair instead of
// only logging it. Cleared at the start of every init.
std::string g_last_error;

std::string tokenToText(const llama_vocab * vocab, llama_token token) {
    char buffer[256];
    const int32_t written = llama_token_to_piece(vocab, token, buffer, sizeof(buffer), 0, true);
    if (written <= 0) {
        return {};
    }
    return std::string(buffer, static_cast<size_t>(written));
}

/** True once the accumulated text holds one complete, balanced JSON object. */
bool hasCompleteJsonObject(const std::string & text) {
    int depth = 0;
    bool inString = false;
    bool escaped = false;
    bool opened = false;
    for (char ch : text) {
        if (inString) {
            if (escaped) {
                escaped = false;
            } else if (ch == '\\') {
                escaped = true;
            } else if (ch == '"') {
                inString = false;
            }
            continue;
        }
        switch (ch) {
            case '"':
                inString = true;
                break;
            case '{':
                depth++;
                opened = true;
                break;
            case '}':
                depth--;
                if (opened && depth == 0) {
                    return true;
                }
                break;
            default:
                break;
        }
    }
    return false;
}

void releaseLocked() {
    if (g_state.vision != nullptr) {
        mtmd_free(g_state.vision);
        g_state.vision = nullptr;
    }
    if (g_state.model != nullptr) {
        llama_model_free(g_state.model);
        g_state.model = nullptr;
    }
    if (g_state.backendInitialised) {
        llama_backend_free();
        g_state.backendInitialised = false;
    }
}

/** Converts an ARGB int buffer into the packed RGB byte layout mtmd expects. */
std::vector<unsigned char> argbToRgb(const int32_t * argb, int width, int height) {
    std::vector<unsigned char> rgb(static_cast<size_t>(width) * height * 3);
    size_t out = 0;
    const size_t total = static_cast<size_t>(width) * height;
    for (size_t i = 0; i < total; ++i) {
        const int32_t pixel = argb[i];
        rgb[out++] = static_cast<unsigned char>((pixel >> 16) & 0xFF);
        rgb[out++] = static_cast<unsigned char>((pixel >> 8) & 0xFF);
        rgb[out++] = static_cast<unsigned char>(pixel & 0xFF);
    }
    return rgb;
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_heimdall_engine_vlm_NativeVlmBridge_nativeInit(
        JNIEnv * env, jobject /* thiz */,
        jstring modelPath, jstring mmprojPath,
        jint contextSize, jint threads, jint gpuLayers) {
    std::lock_guard<std::mutex> guard(g_mutex);
    g_last_error.clear();
    if (g_state.model != nullptr && g_state.vision != nullptr) {
        return JNI_TRUE;
    }

    const char * modelPathChars = env->GetStringUTFChars(modelPath, nullptr);
    const char * mmprojPathChars = env->GetStringUTFChars(mmprojPath, nullptr);
    if (modelPathChars == nullptr || mmprojPathChars == nullptr) {
        LOGE("init: could not read model paths");
        if (modelPathChars) env->ReleaseStringUTFChars(modelPath, modelPathChars);
        if (mmprojPathChars) env->ReleaseStringUTFChars(mmprojPath, mmprojPathChars);
        return JNI_FALSE;
    }
    const std::string modelPathStr(modelPathChars);
    const std::string mmprojPathStr(mmprojPathChars);
    env->ReleaseStringUTFChars(modelPath, modelPathChars);
    env->ReleaseStringUTFChars(mmprojPath, mmprojPathChars);

    llama_backend_init();
    g_state.backendInitialised = true;

    llama_model_params modelParams = llama_model_default_params();
#ifdef HEIMDALL_HAS_VULKAN
    modelParams.n_gpu_layers = gpuLayers;
#else
    modelParams.n_gpu_layers = 0;
#endif
    modelParams.load_mode = LLAMA_LOAD_MODE_MMAP;
    g_state.model = llama_model_load_from_file(modelPathStr.c_str(), modelParams);
    if (g_state.model == nullptr) {
        LOGE("init: failed to load language model %s", modelPathStr.c_str());
        g_last_error = "llama.cpp refused the language weights";
        releaseLocked();
        return JNI_FALSE;
    }

    mtmd_context_params visionParams = mtmd_context_params_default();
#ifdef HEIMDALL_HAS_VULKAN
    visionParams.use_gpu = gpuLayers > 0;
#else
    visionParams.use_gpu = false;
#endif
    visionParams.print_timings = false;
    visionParams.n_threads = threads;
    visionParams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;

    g_state.vision = mtmd_init_from_file(mmprojPathStr.c_str(), g_state.model, visionParams);
    if (g_state.vision == nullptr) {
        LOGE("init: failed to load vision projector %s", mmprojPathStr.c_str());
        g_last_error = "llama.cpp refused the vision projector (wrong projector type for these weights?)";
        releaseLocked();
        return JNI_FALSE;
    }
    if (!mtmd_support_vision(g_state.vision)) {
        LOGE("init: projector does not report vision support");
        g_last_error = "the projector does not report vision support";
        releaseLocked();
        return JNI_FALSE;
    }

    g_state.contextSize = contextSize > 0 ? contextSize : 2048;
    g_state.threads = threads > 0 ? threads : 4;
    g_state.gpuLayers = gpuLayers;

    LOGI("init: ready backend=%s ctx=%d threads=%d gpu_layers=%d", kBackend, g_state.contextSize,
         g_state.threads, g_state.gpuLayers);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_heimdall_engine_vlm_NativeVlmBridge_nativeEvaluate(
        JNIEnv * env, jobject /* thiz */,
        jintArray argb, jint width, jint height,
        jstring prompt, jint maxTokens, jfloat temperature) {
    std::lock_guard<std::mutex> guard(g_mutex);

    const std::string silent = R"({"intervene": false})";
    if (g_state.model == nullptr || g_state.vision == nullptr) {
        return env->NewStringUTF(silent.c_str());
    }
    if (width <= 0 || height <= 0 || maxTokens <= 0) {
        return env->NewStringUTF(silent.c_str());
    }

    jsize pixelCount = env->GetArrayLength(argb);
    if (pixelCount < width * height) {
        LOGE("evaluate: pixel buffer shorter than %dx%d", width, height);
        return env->NewStringUTF(silent.c_str());
    }
    std::vector<int32_t> pixels(static_cast<size_t>(pixelCount));
    env->GetIntArrayRegion(argb, 0, pixelCount, pixels.data());

    const char * promptChars = env->GetStringUTFChars(prompt, nullptr);
    if (promptChars == nullptr) {
        return env->NewStringUTF(silent.c_str());
    }
    const std::string promptStr(promptChars);
    env->ReleaseStringUTFChars(prompt, promptChars);

    // --- fresh decode context per frame -------------------------------------------------------
    llama_context_params ctxParams = llama_context_default_params();
    ctxParams.n_ctx = static_cast<uint32_t>(g_state.contextSize);
    ctxParams.n_batch = 512;
    ctxParams.n_ubatch = 512;
    ctxParams.n_threads = g_state.threads;
    ctxParams.n_threads_batch = g_state.threads;
    ctxParams.no_perf = true;

    llama_context * ctx = llama_init_from_model(g_state.model, ctxParams);
    if (ctx == nullptr) {
        LOGE("evaluate: llama_init_from_model failed");
        return env->NewStringUTF(silent.c_str());
    }
    const llama_vocab * vocab = llama_model_get_vocab(g_state.model);

    std::vector<unsigned char> rgb = argbToRgb(pixels.data(), width, height);
    mtmd_bitmap * bitmap = mtmd_bitmap_init(static_cast<uint32_t>(width),
                                            static_cast<uint32_t>(height), rgb.data());
    if (bitmap == nullptr) {
        LOGE("evaluate: mtmd_bitmap_init failed");
        llama_free(ctx);
        return env->NewStringUTF(silent.c_str());
    }

    // The prompt must contain exactly one media marker for the single image we pass.
    std::string fullPrompt = std::string(mtmd_default_marker()) + "\n" + promptStr;

    mtmd_input_text inputText{};
    inputText.text = fullPrompt.c_str();
    inputText.text_len = fullPrompt.size();
    inputText.add_special = true;
    inputText.parse_special = true;

    const mtmd_bitmap * bitmaps[] = {bitmap};
    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    int32_t tokenizeResult = mtmd_tokenize(g_state.vision, chunks, &inputText, bitmaps, 1);
    if (tokenizeResult != 0) {
        LOGE("evaluate: mtmd_tokenize failed (%d)", tokenizeResult);
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(bitmap);
        llama_free(ctx);
        return env->NewStringUTF(silent.c_str());
    }

    llama_pos nPast = 0;
    int32_t evalResult = mtmd_helper_eval_chunks(
            g_state.vision, ctx, chunks, 0, 0, ctxParams.n_batch, true, &nPast);
    if (evalResult != 0) {
        LOGE("evaluate: mtmd_helper_eval_chunks failed (%d)", evalResult);
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(bitmap);
        llama_free(ctx);
        return env->NewStringUTF(silent.c_str());
    }

    mtmd_input_chunks_free(chunks);
    mtmd_bitmap_free(bitmap);

    // --- decode loop --------------------------------------------------------------------------
    llama_sampler_chain_params samplerParams = llama_sampler_chain_default_params();
    samplerParams.no_perf = true;
    llama_sampler * sampler = llama_sampler_chain_init(samplerParams);
    if (temperature > 0.05f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    }

    llama_batch_ext * batch = llama_batch_ext_init(ctx);
    std::string output;
    output.reserve(512);

    for (int generated = 0; generated < maxTokens; ++generated) {
        const llama_token token = llama_sampler_sample(sampler, ctx, -1);
        llama_sampler_accept(sampler, token);
        if (llama_vocab_is_eog(vocab, token)) {
            break;
        }
        output += tokenToText(vocab, token);
        // Stop as soon as one complete JSON object exists: every extra token is battery.
        if (hasCompleteJsonObject(output)) {
            break;
        }
        llama_batch_ext_clear(batch);
        llama_batch_ext_add_token(batch, 0, token);
        const int32_t decodeResult = llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch);
        if (decodeResult != 0) {
            LOGW("evaluate: decode returned %d", decodeResult);
            break;
        }
    }

    llama_batch_ext_free(batch);
    llama_sampler_free(sampler);
    llama_free(ctx);

    if (output.empty()) {
        return env->NewStringUTF(silent.c_str());
    }
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_heimdall_engine_vlm_NativeVlmBridge_nativeBackend(
        JNIEnv * env, jobject /* thiz */) {
    std::lock_guard<std::mutex> guard(g_mutex);
    if (g_state.model == nullptr) {
        return env->NewStringUTF("unloaded");
    }
    return env->NewStringUTF(kBackend);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_heimdall_engine_vlm_NativeVlmBridge_nativeLastError(
        JNIEnv * env, jobject /* thiz */) {
    std::lock_guard<std::mutex> guard(g_mutex);
    return env->NewStringUTF(g_last_error.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_heimdall_engine_vlm_NativeVlmBridge_nativeRelease(
        JNIEnv * /* env */, jobject /* thiz */) {
    std::lock_guard<std::mutex> guard(g_mutex);
    releaseLocked();
    LOGI("release: model freed");
}
