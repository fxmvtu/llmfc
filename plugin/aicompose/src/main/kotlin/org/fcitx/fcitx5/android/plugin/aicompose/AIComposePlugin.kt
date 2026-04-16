package org.fcitx.fcitx5.android.plugin.aicompose

import android.content.Context
import android.util.Log
import org.fcitx.fcitx5.android.plugin.aicompose.data.PluginData
import org.fcitx.fcitx5.android.plugin.base.FcitxPlugin
import org.fcitx.fcitx5.android.plugin.base.ui.AddonUI
import java.io.File

/**
 * AIComposePlugin — Fcitx5 plugin entry point.
 *
 * Registered via FcitxPlugin manifest entry, this class:
 * - Receives pinyin input from the IME
 * - Calls AIComposeEngine → LlamaEngine for LLM completion
 * - Returns candidate words to the Fcitx5 candidate view
 */
class AIComposePlugin private constructor(
    private val context: Context
) : FcitxPlugin() {

    private lateinit var llamaEngine: LlamaEngine
    private lateinit var engine: AIComposeEngine

    override fun onCreate() {
        super.onCreate()
        llamaEngine = LlamaEngine()
        engine = AIComposeEngine(llamaEngine)
        Log.i(TAG, "AIComposePlugin created")
    }

    override fun onDestroy() {
        engine.destroy()
        llamaEngine.destroy()
        super.onDestroy()
    }

    /**
     * Called by AIAddonUI (settings UI) when the user selects a model to load.
     */
    fun loadModel(modelPath: String, nCtx: Int = 2048, nThreads: Int = 4): Boolean {
        return llamaEngine.loadModel(modelPath, nCtx, nThreads)
    }

    fun isModelLoaded(): Boolean = llamaEngine.isLoaded.value

    fun getAvailableModels(): List<ModelInfo> {
        val modelsDir = File(context.filesDir, "models")
        if (!modelsDir.exists()) return emptyList()
        return modelsDir.listFiles()
            ?.filter { it.extension == "gguf" }
            ?.map { ModelInfo(it.nameWithoutExtension, it.absolutePath, it.length() / 1024 / 1024) }
            ?: emptyList()
    }

    // ─── Data ────────────────────────────────────────────────────────────────

    override val data: PluginData = PluginData()

    // ─── Companion ───────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "AIComposePlugin"

        @Volatile
        private var instance: AIComposePlugin? = null

        fun getInstance(context: Context): AIComposePlugin {
            return instance ?: synchronized(this) {
                instance ?: AIComposePlugin(context.applicationContext).also {
                    instance = it
                    it.onCreate()
                }
            }
        }
    }
}

data class ModelInfo(
    val name: String,
    val path: String,
    val sizeMb: Long
)
