#include <jni.h>
#include <string>
#include <thread>
#include <atomic>
#include <vector>
#include <csignal>
#include <mutex>
#include <sstream>

// llama.cpp public headers
#include "llama.h"

// Android logging
#include <android/log.h>

// ─── Logging ─────────────────────────────────────────────────────────────────
#define LOG_TAG "LlamaEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ─── Global state (protected by s_mutex) ────────────────────────────────────
static struct llama_model * s_model = nullptr;
static struct llama_context * s_ctx = nullptr;
static int s_n_ctx = 2048;
static int s_n_threads = 0;
static std::atomic<bool> s_model_loaded(false);

// Cancellation flag — checked every token to support Job.cancel()
static std::atomic<bool> s_cancelled(false);

// Protects all model/context operations to prevent use-after-free
// and ensure thread-safe concurrent calls to load/unload/complete
static std::mutex s_mutex;

// ─── JNI Helpers ─────────────────────────────────────────────────────────────
static JNIEnv * getEnv(JavaVM *vm) {
    JNIEnv * env;
    if (vm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        return nullptr;
    }
    return env;
}

// ─── llama.cpp inference helpers ─────────────────────────────────────────────

static std::string generate_prompt(const char * input) {
    // Prompt template for Chinese pinyin-to-character completion.
    // Instructs the model to preserve spaces as word boundaries to help
    // with word segmentation (e.g. "ni hao" → "你好", not misaligned).
    std::ostringstream oss;
    oss << "### Instruction:\n"
        << "Convert the following pinyin (without tones) to Chinese characters.\n"
        << "Keep word boundaries as indicated by spaces.\n"
        << "Only output the Chinese characters, nothing else.\n\n"
        << "Pinyin: " << input << "\n\n"
        << "Chinese: ";
    return oss.str();
}

static void llama_log_callback(enum ggml_log_level level, const char * text, void * user_data) {
    (void)user_data;
    if (level >= GGML_LOG_LEVEL_ERROR) {
        __android_log_print(ANDROID_LOG_ERROR, "llama.cpp", "%s", text);
    }
}

// ─── Thread-safe model load ──────────────────────────────────────────────────

extern "C" {

JNIEXPORT jboolean JNICALL
Java_org_fcitx_fcitx5_android_plugin_aicompose_LlamaEngine_loadModelNative(
        JNIEnv *env, jobject thiz,
        jstring modelPath, jint nCtx, jint nThreads) {

    std::lock_guard<std::mutex> lock(s_mutex);

    // Free any previous session
    if (s_ctx) {
        llama_free(s_ctx);
        s_ctx = nullptr;
    }
    if (s_model) {
        llama_model_free(s_model);
        s_model = nullptr;
    }

    const char * path = env->GetStringUTFChars(modelPath, nullptr);
    if (!path) {
        LOGE("Failed to get model path");
        return JNI_FALSE;
    }

    LOGI("Loading model from: %s", path);
    LOGI("nCtx=%d nThreads=%d", nCtx, nThreads);

    s_n_ctx = nCtx > 0 ? nCtx : 2048;
    s_n_threads = nThreads > 0 ? nThreads : 0;

    // Set llama log callback
    llama_log_set(llama_log_callback, nullptr);

    // Configure model params
    struct llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 32;       // offload all layers to GPU (Vulkan via GGML)
    mparams.use_mmap = true;
    mparams.use_mlock = false;
    // n_threads is now only in context_params, not model_params

    // Load model (new API)
    s_model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!s_model) {
        LOGE("Failed to load model: %s", path);
        return JNI_FALSE;
    }

    char model_desc[256];
    llama_model_desc(s_model, model_desc, sizeof(model_desc));
    LOGI("Model loaded successfully: %s", model_desc);

    // Get vocab from model
    const struct llama_vocab * vocab = llama_model_get_vocab(s_model);

    // Create inference context (use new API)
    struct llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = s_n_ctx;
    cparams.n_threads = s_n_threads > 0 ? s_n_threads : (int32_t)std::thread::hardware_concurrency();
    cparams.n_threads_batch = cparams.n_threads;
    cparams.no_perf = true;

    s_ctx = llama_init_from_model(s_model, cparams);
    if (!s_ctx) {
        LOGE("Failed to create context");
        llama_model_free(s_model);
        s_model = nullptr;
        return JNI_FALSE;
    }

    (void)vocab; // vocab is stored in the model, accessed via llama_model_get_vocab()
    s_cancelled.store(false);
    LOGI("Context created. n_ctx=%d", llama_n_ctx(s_ctx));
    s_model_loaded.store(true);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_org_fcitx_fcitx5_android_plugin_aicompose_LlamaEngine_unloadModelNative(
        JNIEnv *env, jobject thiz) {

    std::lock_guard<std::mutex> lock(s_mutex);

    s_model_loaded.store(false);
    s_cancelled.store(true);

    if (s_ctx) {
        llama_free(s_ctx);
        s_ctx = nullptr;
    }
    if (s_model) {
        llama_model_free(s_model);
        s_model = nullptr;
    }

    LOGI("Model unloaded");
}

JNIEXPORT void JNICALL
Java_org_fcitx_fcitx5_android_plugin_aicompose_LlamaEngine_cancelNative(
        JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    s_cancelled.store(true);
    LOGI("Cancellation flag set");
}

JNIEXPORT void JNICALL
Java_org_fcitx_fcitx5_android_plugin_aicompose_LlamaEngine_completeStreamNative(
        JNIEnv *env, jobject thiz,
        jstring prompt, jint maxTokens, jobject callback) {

    // Check model loaded (outside lock — atomic read)
    if (!s_model_loaded.load()) {
        LOGE("Model not loaded");
        return;
    }

    // Create a global reference to the callback so it doesn't get GC'd
    // during the potentially long streaming generation
    jobject callbackRef = env->NewGlobalRef(callback);

    // Look up invoke method each time (safe — method ID is per-class, not per-instance)
    jclass callbackClass = env->GetObjectClass(callbackRef);
    jmethodID onTokenMethod = env->GetMethodID(callbackClass, "invoke", "(Ljava/lang/String;)V");
    if (!onTokenMethod) {
        LOGE("Callback method 'invoke(String)' not found");
        env->DeleteGlobalRef(callbackRef);
        return;
    }

    // ── Hold the mutex for the entire streaming operation ──────────────────
    // This prevents concurrent completeStream calls and guards against
    // unloadModel being called while streaming is in progress (use-after-free).
    std::unique_lock<std::mutex> lock(s_mutex);

    // Re-check after acquiring lock (model could have been unloaded)
    if (!s_model_loaded.load()) {
        LOGE("Model unloaded during stream setup");
        lock.unlock();
        env->DeleteGlobalRef(callbackRef);
        return;
    }

    // Reset cancellation flag for this generation
    s_cancelled.store(false);

    const char * prompt_str = env->GetStringUTFChars(prompt, nullptr);
    std::string prompt_template = generate_prompt(prompt_str);
    env->ReleaseStringUTFChars(prompt, prompt_str);

    // Get vocab from model
    const struct llama_vocab * vocab = llama_model_get_vocab(s_model);

    // Tokenize the prompt (signature: vocab, text, text_len, tokens, n_max, add_special, parse_special)
    int n_tokens = llama_tokenize(vocab, prompt_template.c_str(), (int32_t)prompt_template.size(), nullptr, 0, true, true);
    if (n_tokens <= 0 || n_tokens >= s_n_ctx - 4) {
        lock.unlock();
        env->DeleteGlobalRef(callbackRef);
        return;
    }
    std::vector<llama_token> tokens(n_tokens);
    n_tokens = llama_tokenize(vocab, prompt_template.c_str(), (int32_t)prompt_template.size(), tokens.data(), (int32_t)n_tokens, true, true);
    if (n_tokens <= 0) {
        lock.unlock();
        env->DeleteGlobalRef(callbackRef);
        return;
    }

    if (!llama_decode(s_ctx, llama_batch_get_one(tokens.data(), tokens.size()))) {
        lock.unlock();
        env->DeleteGlobalRef(callbackRef);
        return;
    }

    struct llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    struct llama_sampler * chain = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(chain, llama_sampler_init_greedy());

    int generated = 0;
    int max_toks = maxTokens > 0 ? maxTokens : 32;

    while (generated < max_toks) {
        // Check cancellation flag — allows Job.cancel() to stop mid-generation
        if (s_cancelled.load()) {
            LOGI("Stream cancelled at token %d", generated);
            break;
        }

        llama_token next_token = llama_sampler_sample(chain, s_ctx, -1);

        if (llama_vocab_is_eog(vocab, next_token)) {
            break;
        }

        char token_buf[256];
        int n_written = llama_token_to_piece(vocab, next_token, token_buf, sizeof(token_buf), 0, true);
        if (n_written > 0) {
            // Build Java String while still holding the lock for model/ctx safety,
            // but release before the JNI callback to avoid potential deadlock when
            // the Kotlin side tries to re-acquire s_mutex (e.g. in synchronized blocks).
            jstring token_jstr = env->NewStringUTF(std::string(token_buf, n_written).c_str());
            if (token_jstr) {
                lock.unlock();          // release lock before JNI callback
                env->CallVoidMethod(callbackRef, onTokenMethod, token_jstr);
                lock.lock();            // re-acquire for next iteration
                env->DeleteLocalRef(token_jstr);
            }
            generated++;
        }
        // else: blank token (e.g. whitespace or punctuation) — skip without breaking
        // the loop. This prevents early termination when the model outputs
        // spaces or punctuation mid-sentence.

        if (!llama_decode(s_ctx, llama_batch_get_one(&next_token, 1))) {
            break;
        }
    }

    llama_sampler_free(chain);
    lock.unlock();
    env->DeleteGlobalRef(callbackRef);
}

} // extern "C"
