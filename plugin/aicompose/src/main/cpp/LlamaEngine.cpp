#include <jni.h>
#include <string>
#include <thread>
#include <atomic>
#include <vector>
#include <csignal>

// llama.cpp public headers
#include "common/common.h"
#include "llama.h"

// ─── Logging ─────────────────────────────────────────────────────────────────
#define LOG_TAG "LlamaEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ─── Global state ─────────────────────────────────────────────────────────────
static struct llama_model * s_model = nullptr;
static struct llama_context * s_ctx = nullptr;
static struct llama_model_params s_mparams;
static int s_n_ctx = 2048;
static std::atomic<bool> s_model_loaded(false);

// ─── JNI Helpers ─────────────────────────────────────────────────────────────
static JNIEnv * getEnv(JavaVM *vm) {
    JNIEnv * env;
    if (vm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        return nullptr;
    }
    return env;
}

static jclass findClass(JNIEnv *env, const char *name) {
    return env->FindClass(name);
}

// ─── llama.cpp inference helpers ─────────────────────────────────────────────

static std::string generate_prompt(const char * input) {
    // Simple prompt template for Chinese pinyin-to-character completion.
    // The model receives a pinyin string and is asked to convert to Chinese.
    // We use a minimal chat-templated prompt compatible with most instruct models.
    std::ostringstream oss;
    oss << "### Instruction:\n"
        << "Convert the following pinyin (without tones) to Chinese characters.\n"
        << "Only output the Chinese characters, nothing else.\n\n"
        << "Pinyin: " << input << "\n\n"
        << "Chinese:";
    return oss.str();
}

static void llama_log_callback(enum ggml_log_level level, const char * text, void * user_data) {
    (void)user_data;
    if (level >= GGML_LOG_LEVEL_ERROR) {
        __android_log_print(ANDROID_LOG_ERROR, "llama.cpp", "%s", text);
    }
}

// ─── JNI Implementations ─────────────────────────────────────────────────────

extern "C" {

JNIEXPORT jboolean JNICALL
Java_org_fcitx_fcitx5_android_plugin_aicompose_LlamaEngine_loadModelNative(
        JNIEnv *env, jobject thiz,
        jstring modelPath, jint nCtx, jint nThreads) {

    // Free any previous session
    if (s_ctx) {
        llama_free(s_ctx);
        s_ctx = nullptr;
    }
    if (s_model) {
        llama_free_model(s_model);
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

    // Configure model params
    s_mparams = llama_model_default_params();
    s_mparams.n_gpu_layers = 32;          // offload all layers to GPU (Vulkan via GGML)
    s_mparams.use_mmap = true;
    s_mparams.use_mlock = false;

    // Override n_threads if user specified them
    if (nThreads > 0) {
        s_mparams.n_threads = nThreads;
    }

    // Set llama log callback
    llama_log_set_callback(llama_log_callback, nullptr);

    // Load model
    s_model = llama_load_model_from_file(path, s_mparams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!s_model) {
        LOGE("Failed to load model: %s", path);
        return JNI_FALSE;
    }

    LOGI("Model loaded successfully: %s", llama_model_desc(s_model));

    // Create inference context
    struct llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = s_n_ctx;
    cparams.n_threads = nThreads > 0 ? nThreads : std::thread::hardware_concurrency();
    cparams.no_perf = true;

    s_ctx = llama_new_context_with_model(s_model, cparams);
    if (!s_ctx) {
        LOGE("Failed to create context");
        llama_free_model(s_model);
        s_model = nullptr;
        return JNI_FALSE;
    }

    LOGI("Context created. n_ctx=%d", llama_n_ctx(s_ctx));
    s_model_loaded.store(true);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_org_fcitx_fcitx5_android_plugin_aicompose_LlamaEngine_unloadModelNative(
        JNIEnv *env, jobject thiz) {

    s_model_loaded.store(false);

    if (s_ctx) {
        llama_free(s_ctx);
        s_ctx = nullptr;
    }
    if (s_model) {
        llama_free_model(s_model);
        s_model = nullptr;
    }

    LOGI("Model unloaded");
}

JNIEXPORT jstring JNICALL
Java_org_fcitx_fcitx5_android_plugin_aicompose_LlamaEngine_completeNative(
        JNIEnv *env, jobject thiz,
        jstring prompt, jint maxTokens) {

    if (!s_model_loaded.load()) {
        LOGE("Model not loaded");
        return env->NewStringUTF("");
    }

    const char * prompt_str = env->GetStringUTFChars(prompt, nullptr);
    std::string prompt_template = generate_prompt(prompt_str);
    env->ReleaseStringUTFChars(prompt, prompt_str);

    // Tokenize the prompt
    std::vector<llama_token> tokens = ::llama_tokenize(s_model, prompt_template, true);
    if (tokens.empty()) {
        LOGE("Failed to tokenize prompt");
        return env->NewStringUTF("");
    }

    // Check context size
    if ((int)tokens.size() >= s_n_ctx - 4) {
        LOGE("Prompt too long for context");
        return env->NewStringUTF("");
    }

    // Reset context for new generation
    llama_reset_timings(s_ctx);

    // Evaluate prompt
    if (!llama_decode(s_ctx, llama_batch_get_one(tokens.data(), tokens.size()))) {
        LOGE("Failed to evaluate prompt");
        return env->NewStringUTF("");
    }

    // Sampling params — deterministic for consistency
    struct llama_sampler_chain * chain = llama_sampler_chain_new();
    llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    llama_sampler_chain_add(chain, llama_sampler_init_grammar("", ""));

    std::ostringstream output;
    int generated = 0;
    int max_toks = maxTokens > 0 ? maxTokens : 32;

    while (generated < max_toks) {
        llama_token next_token = llama_sampler_sample(chain, s_ctx, -1);

        // Check for EOS
        if (llama_token_is_eog(s_model, next_token)) {
            break;
        }

        const char * token_str = llama_token_to_piece(s_model, next_token, true);
        if (token_str && token_str[0] != '\0') {
            output << token_str;
            generated++;
        } else {
            break;
        }

        // Prepare next decode
        if (!llama_decode(s_ctx, llama_batch_get_one(&next_token, 1))) {
            LOGE("Failed to decode token %d", generated);
            break;
        }
    }

    llama_sampler_free(chain);

    std::string result = output.str();
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_org_fcitx_fcitx5_android_plugin_aicompose_LlamaEngine_completeStreamNative(
        JNIEnv *env, jobject thiz,
        jstring prompt, jint maxTokens, jobject callback) {

    if (!s_model_loaded.load()) {
        LOGE("Model not loaded");
        return;
    }

    // Get callback method
    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onTokenMethod = env->GetMethodID(callbackClass, "accept", "(Ljava/lang/String;)V");
    if (!onTokenMethod) {
        LOGE("Callback method 'accept(String)' not found");
        return;
    }

    const char * prompt_str = env->GetStringUTFChars(prompt, nullptr);
    std::string prompt_template = generate_prompt(prompt_str);
    env->ReleaseStringUTFChars(prompt, prompt_str);

    std::vector<llama_token> tokens = ::llama_tokenize(s_model, prompt_template, true);
    if (tokens.empty() || (int)tokens.size() >= s_n_ctx - 4) {
        return;
    }

    llama_reset_timings(s_ctx);
    if (!llama_decode(s_ctx, llama_batch_get_one(tokens.data(), tokens.size()))) {
        return;
    }

    struct llama_sampler_chain * chain = llama_sampler_chain_new();
    llama_sampler_chain_add(chain, llama_sampler_init_greedy());

    int generated = 0;
    int max_toks = maxTokens > 0 ? maxTokens : 32;

    while (generated < max_toks) {
        llama_token next_token = llama_sampler_sample(chain, s_ctx, -1);

        if (llama_token_is_eog(s_model, next_token)) {
            break;
        }

        const char * token_str = llama_token_to_piece(s_model, next_token, true);
        if (token_str && token_str[0] != '\0') {
            // Convert token to Java String and call callback
            jstring token_jstr = env->NewStringUTF(token_str);
            env->CallVoidMethod(callback, onTokenMethod, token_jstr);
            env->DeleteLocalRef(token_jstr);
            generated++;
        } else {
            break;
        }

        if (!llama_decode(s_ctx, llama_batch_get_one(&next_token, 1))) {
            break;
        }
    }

    llama_sampler_free(chain);
}

} // extern "C"
