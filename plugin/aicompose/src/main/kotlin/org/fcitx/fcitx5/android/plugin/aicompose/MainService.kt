package org.fcitx.fcitx5.android.plugin.aicompose

import android.util.Log
import kotlinx.coroutines.*
import org.fcitx.fcitx5.android.common.FcitxPluginService
import org.fcitx.fcitx5.android.common.ipc.FcitxRemoteConnection
import org.fcitx.fcitx5.android.common.ipc.IInputSuggestions
import org.fcitx.fcitx5.android.common.ipc.bindFcitxRemoteService
import java.io.File
import kotlin.concurrent.withLock

/**
 * Model metadata for a GGUF model file in the plugin's models directory.
 */
data class ModelInfo(
    val name: String,
    val path: String,
    val sizeMb: Long
)

/**
 * FcitxPluginService implementation for the AI Compose plugin.
 *
 * This service:
 * 1. Extends FcitxPluginService so the main app binds to it at startup
 * 2. Implements IInputSuggestions so the app can query LLM candidates
 * 3. Registers itself with the app's FcitxRemoteService on start()
 * 4. Unregisters on stop()
 *
 * The actual LLM inference is delegated to AIComposeEngine (LlamaEngine).
 */
class MainService : FcitxPluginService() {

    private lateinit var connection: FcitxRemoteConnection
    private lateinit var llamaEngine: LlamaEngine
    private lateinit var engine: AIComposeEngine
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Cached suggestions for synchronous AIDL calls
    // Access guarded by suggestionLock
    private val suggestionLock = java.util.concurrent.locks.ReentrantLock()
    private var cachedSuggestions: Array<String> = emptyArray()
    private var lastPinyin: String = ""

    private val suggestionsBinder = object : IInputSuggestions.Stub() {
        // onPreeditChanged: fire-and-forget async trigger, pre-warms the cache.
        // Runs on the binder thread pool — dispatch to serviceScope to avoid blocking.
        override fun onPreeditChanged(pinyin: String?) {
            if (pinyin.isNullOrBlank()) return
            serviceScope.launch {
                triggerSuggestion(pinyin)
            }
        }

        // Called from binder thread — must not block the main app's binder thread pool
        // Returns cached results from the last inference run, with partial-match fallback.
        override fun getSuggestions(pinyin: String?, limit: Int): Array<String> {
            if (pinyin.isNullOrBlank()) return emptyArray()
            suggestionLock.withLock {
                // Exact match — return immediately
                if (pinyin == lastPinyin && cachedSuggestions.isNotEmpty()) {
                    @Suppress("UNCHECKED_CAST")
                    return (cachedSuggestions.copyOf(minOf(limit, cachedSuggestions.size)) as Array<String>)
                }
                // Partial-match fallback: if current pinyin starts with last pinyin,
                // the cached suggestions may still be relevant (user added more chars).
                // Return whatever we have, capped to limit.
                if (lastPinyin.isNotEmpty() && pinyin.startsWith(lastPinyin) && cachedSuggestions.isNotEmpty()) {
                    log("Partial match fallback for '$pinyin' (cached from '$lastPinyin')")
                    @Suppress("UNCHECKED_CAST")
                    return (cachedSuggestions.copyOf(minOf(limit, cachedSuggestions.size)) as Array<String>)
                }
            }
            // No useful cache — return empty; next keystroke will trigger a new inference
            return emptyArray()
        }

        override fun isReady(): Boolean {
            return sInstance?.isModelLoadedInternal() == true
        }
    }

    override fun onCreate() {
        super.onCreate()
        llamaEngine = LlamaEngine()
        engine = AIComposeEngine(llamaEngine)
        sInstance = this
        Log.i(TAG, "AIComposePlugin created")
    }

    override fun start() {
        Log.i(TAG, "AIComposePlugin started")
        connection = bindFcitxRemoteService(BuildConfig.MAIN_APPLICATION_ID) {
            log("Bind to fcitx remote")
            it.registerInputSuggestions(suggestionsBinder)
        }
    }

    override fun stop() {
        runCatching {
            connection.remoteService?.unregisterInputSuggestions(suggestionsBinder)
        }
        unbindService(connection)
        // Cancel scope first so any in-flight triggerSuggestion coroutines are cancelled,
        // then perform a single consolidated destroy of the engine and native state.
        serviceScope.cancel()
        engine.destroy()
        llamaEngine.destroy()
        sInstance = null
        log("Unbind from fcitx remote")
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
    }

    // ─── Internal engine access ──────────────────────────────────────────────

    private fun isModelLoadedInternal(): Boolean = llamaEngine.isLoaded.value

    /**
     * Trigger async LLM inference for the given pinyin.
     * Results are cached and returned by getSuggestions().
     * Safe to call from any thread.
     */
    private fun triggerSuggestion(pinyin: String) {
        if (!llamaEngine.isLoaded.value || pinyin.isBlank()) return

        // Cancel any in-progress generation for previous pinyin
        engine.cancelCurrent()

        engine.requestCompletion(
            pinyinRaw = pinyin,
            maxCandidates = 5
        ) { candidates ->
            suggestionLock.withLock {
                cachedSuggestions = candidates.toTypedArray()
                lastPinyin = pinyin
            }
            log("Cached ${candidates.size} suggestions for '$pinyin'")
        }
    }

    // ─── Public API (for Settings UI) ─────────────────────────────────────────

    fun isModelLoaded(): Boolean = llamaEngine.isLoaded.value

    fun getAvailableModels(): List<ModelInfo> {
        val modelsDir = File(filesDir, "models")
        if (!modelsDir.exists()) return emptyList()
        return modelsDir.listFiles()
            ?.filter { it.extension == "gguf" }
            ?.map { ModelInfo(it.nameWithoutExtension, it.absolutePath, it.length() / 1024 / 1024) }
            ?: emptyList()
    }

    private fun loadModel(modelPath: String, nCtx: Int, nThreads: Int): Boolean {
        return llamaEngine.loadModel(modelPath, nCtx, nThreads)
    }

    fun unloadModelFromSettings() {
        engine.destroy()
        llamaEngine.destroy()
    }

    // ─── Singleton (for Settings UI access) ─────────────────────────────────

    companion object {
        private const val TAG = "AIComposePlugin"
        private var sInstance: MainService? = null

        fun isLoaded(): Boolean = sInstance?.isModelLoaded() == true

        fun loadModelStatic(path: String, nCtx: Int, nThreads: Int): Boolean =
            sInstance?.loadModel(path, nCtx, nThreads) == true

        fun getAvailableModelsStatic(): List<ModelInfo> =
            sInstance?.getAvailableModels() ?: emptyList()

        fun unloadModelStatic() {
            sInstance?.unloadModelFromSettings()
        }
    }
}
