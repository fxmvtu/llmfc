package org.fcitx.fcitx5.android.plugin.aicompose

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * LlamaEngine — JNI wrapper for llama.cpp inference on Android.
 *
 * Loads a GGUF model via JNI and provides both synchronous completion
 * and streaming completion with Kotlin Flow.
 */
class LlamaEngine {

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded

    private val _modelName = MutableStateFlow("")
    val modelName: StateFlow<String> = _modelName

    private var loadedModelPath: String = ""

    // Coroutine scope for background inference
    private val inferenceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Load a GGUF model from the given path.
     * This is a blocking call — run on a background thread.
     *
     * @param modelPath absolute path to the .gguf model file
     * @param nCtx context window size (tokens). 2048 is a safe default for mobile.
     * @param nThreads number of CPU threads for inference. Use 0 for auto.
     * @return true if loaded successfully
     */
    fun loadModel(modelPath: String, nCtx: Int = 2048, nThreads: Int = 0): Boolean {
        if (_isLoaded.value) {
            unloadModel()
        }
        val result = _loadModelNative(modelPath, nCtx, nThreads)
        _isLoaded.value = result
        if (result) {
            loadedModelPath = modelPath
            _modelName.value = modelPath.substringAfterLast("/").substringBefore(".gguf")
        }
        return result
    }

    /**
     * Unload the currently loaded model and free memory.
     * Also triggers cancellation of any in-progress streaming.
     */
    fun unloadModel() {
        if (_isLoaded.value) {
            _cancelNative()          // signal cancellation before unload
            _unloadModelNative()
            _isLoaded.value = false
            _modelName.value = ""
            loadedModelPath = ""
        }
    }

    /**
     * Synchronous completion — blocks until full generation is done.
     *
     * @param prompt input text (e.g. Chinese pinyin without tones: "nihao")
     * @param maxTokens maximum tokens to generate
     * @return generated text (汉字)
     */
    fun complete(prompt: String, maxTokens: Int = 32): String {
        check(_isLoaded.value) { "Model not loaded" }
        return _completeNative(prompt, maxTokens)
    }

    /**
     * Streaming completion — calls [callback] for each generated token.
     * Runs on [inferenceScope] (IO dispatcher).
     *
     * @param prompt input text
     * @param maxTokens max tokens to generate
     * @param callback called once per generated token on the callback thread
     * @return a [Job] that can be cancelled to stop inference mid-stream
     */
    fun completeStream(
        prompt: String,
        maxTokens: Int = 32,
        callback: (token: String) -> Unit
    ): Job {
        check(_isLoaded.value) { "Model not loaded" }
        return inferenceScope.launch {
            try {
                _completeStreamNative(prompt, maxTokens, callback)
            } finally {
                // Ensure cancellation flag is cleared when stream ends normally
                _cancelNative()
            }
        }.also { job ->
            // When the coroutine is cancelled (Job.cancel()), signal the
            // C++ side to stop early by setting the atomic cancellation flag.
            // The C++ loop checks s_cancelled every token, so generation stops promptly.
            job.invokeOnCompletion(cause = _) {
                _cancelNative()
            }
        }
    }

    /**
     * Release resources. Call when the plugin is destroyed.
     */
    fun destroy() {
        inferenceScope.cancel()
        unloadModel()
    }

    // ─── Native method declarations ──────────────────────────────────────────

    private external fun _loadModelNative(modelPath: String, nCtx: Int, nThreads: Int): Boolean
    private external fun _unloadModelNative()
    private external fun _completeNative(prompt: String, maxTokens: Int): String
    private external fun _completeStreamNative(prompt: String, maxTokens: Int, callback: (String) -> Unit)

    /**
     * Signal the C++ inference loop to stop at the next token boundary.
     * Used by both [unloadModel] and [completeStream] cancellation.
     */
    private external fun _cancelNative()

    companion object {
        init {
            System.loadLibrary("aicompose")
        }
    }
}
