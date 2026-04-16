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
        Log.i(TAG, "AIComposePlugin created")
    }

    override fun start() {
        // Called when the service is bound and the plugin should start providing
        // functionality. The plugin is now ready to receive input via IPC.
        Log.i(TAG, "AIComposePlugin started")
    }

    override fun stop() {
        // Called when the service is unbound. Clean up in-flight inference.
        engine.destroy()
        llamaEngine.destroy()
        Log.i(TAG, "AIComposePlugin stopped")
    }

    /**
     * Called by the settings UI (AIComposeSettingsActivity) when the user
     * selects a model to load.
     */
    fun loadModel(modelPath: String, nCtx: Int = 2048, nThreads: Int = 4): Boolean {
        return llamaEngine.loadModel(modelPath, nCtx, nThreads)
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

    // ─── Companion ───────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "AIComposePlugin"
    }
}

data class ModelInfo(
    val name: String,
    val path: String,
    val sizeMb: Long
)
