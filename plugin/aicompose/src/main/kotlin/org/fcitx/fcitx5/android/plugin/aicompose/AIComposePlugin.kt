package org.fcitx.fcitx5.android.plugin.aicompose

import android.util.Log
import org.fcitx.fcitx5.android.common.FcitxPluginService
import java.io.File

/**
 * AIComposePlugin — Fcitx5 plugin entry point.
 *
 * Registered as a <service> in AndroidManifest.xml, this class:
 * - Receives pinyin input from the IME via FcitxPluginService IPC
 * - Calls AIComposeEngine → LlamaEngine for LLM completion
 * - Returns candidate words to the Fcitx5 candidate view
 *
 * Architecture: extends FcitxPluginService (the standard base for all fcitx5
 * Android plugins). The service lifecycle (start/stop) manages the plugin's
 * connection to the fcitx5 IME core.
 */
class AIComposePlugin : FcitxPluginService() {

    private lateinit var llamaEngine: LlamaEngine
    private lateinit var engine: AIComposeEngine

    override fun onCreate() {
        super.onCreate()
        llamaEngine = LlamaEngine()
        engine = AIComposeEngine(llamaEngine)
        sInstance = this
        Log.i(TAG, "AIComposePlugin created")
    }

    override fun start() {
        Log.i(TAG, "AIComposePlugin started")
    }

    override fun stop() {
        engine.destroy()
        llamaEngine.destroy()
        sInstance = null
        Log.i(TAG, "AIComposePlugin stopped")
    }

    fun isModelLoaded(): Boolean = llamaEngine.isLoaded.value

    fun getAvailableModels(): List<ModelInfo> {
        val modelsDir = File(filesDir, "models")
        if (!modelsDir.exists()) return emptyList()
        return modelsDir.listFiles()
            ?.filter { it.extension == "gguf" }
            ?.map { ModelInfo(it.nameWithoutExtension, it.absolutePath, it.length() / 1024 / 1024) }
            ?: emptyList()
    }

    /** Called by SettingsActivity to unload model from the singleton instance. */
    fun unloadModelFromSettings() {
        engine.destroy()
        llamaEngine.destroy()
    }

    // ─── Singleton (for Settings UI access) ─────────────────────────────────

    companion object {
        private const val TAG = "AIComposePlugin"
        private var sInstance: AIComposePlugin? = null

        fun isLoaded(): Boolean = sInstance?.isModelLoaded() == true

        fun loadModelStatic(path: String, nCtx: Int, nThreads: Int): Boolean =
            sInstance?.loadModel(path, nCtx, nThreads) == true

        fun getAvailableModelsStatic(): List<ModelInfo> =
            sInstance?.getAvailableModels() ?: emptyList()

        fun unloadModelStatic() {
            sInstance?.unloadModelFromSettings()
        }
    }

    private fun loadModel(modelPath: String, nCtx: Int, nThreads: Int): Boolean {
        return llamaEngine.loadModel(modelPath, nCtx, nThreads)
    }
}

data class ModelInfo(
    val name: String,
    val path: String,
    val sizeMb: Long
)
