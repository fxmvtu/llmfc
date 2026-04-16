package org.fcitx.fcitx5.android.plugin.aicompose

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * AIComposeEngine — orchestrates pinyin → LLM → candidate words.
 *
 * Integrates with the Fcitx5 input pipeline:
 *   1. Receives raw pinyin input (e.g. "nihao")
 *   2. Calls LlamaEngine for streaming completion
 *   3. Emits partial/complete Chinese strings as candidates
 */
class AIComposeEngine(
    private val llamaEngine: LlamaEngine
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating

    private var currentJob: Job? = null

    /**
     * Toggle LLM completion on/off.
     */
    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        if (!enabled) {
            cancelCurrent()
        }
    }

    /**
     * Given the current preedit (pinyin without tones), return AI completion candidates.
     * This is called by AIComposePlugin when the user pauses typing.
     *
     * @param pinyinRaw raw pinyin string, e.g. "wozhongguo" or "nihao"
     * @param maxCandidates max number of candidate phrases to return
     * @param callback called with each completed phrase
     * @return a [Job] that can be cancelled if the user keeps typing
     */
    fun requestCompletion(
        pinyinRaw: String,
        maxCandidates: Int = 5,
        callback: (candidates: List<String>) -> Unit
    ): Job? {
        if (!_enabled.value || !llamaEngine.isLoaded.value) {
            return null
        }

        if (pinyinRaw.isBlank()) {
            callback(emptyList())
            return null
        }

        cancelCurrent()

        return scope.launch {
            _isGenerating.value = true
            try {
                // Accumulate partial completions
                val candidates = mutableListOf<String>()
                val lock = Object()
                var streamJob: Job? = null

                streamJob = llamaEngine.completeStream(
                    prompt = normalizePinyin(pinyinRaw),
                    maxTokens = 32
                ) { token ->
                    // token arrives on IO thread; switch to main for candidate list
                    scope.launch(Dispatchers.Main) {
                        synchronized(lock) {
                            // Build incremental completion string
                            val current = candidates.lastOrNull() ?: ""
                            val updated = current + token
                            if (candidates.isEmpty()) {
                                candidates.add(updated)
                            } else {
                                candidates[candidates.lastIndex] = updated
                            }
                            // Show top candidate as user types
                            callback(candidates.map { it.take(20) }.distinct().take(maxCandidates))
                        }
                    }
                }

                streamJob?.join()
            } finally {
                _isGenerating.value = false
            }
        }
    }

    /**
     * Cancel any in-flight generation (called when user keeps typing).
     */
    fun cancelCurrent() {
        currentJob?.cancel()
        currentJob = null
    }

    /**
     * Strip tones and spaces from pinyin for LLM input.
     * e.g. "ni3 hao3" → "nihao"
     */
    private fun normalizePinyin(raw: String): String {
        return raw
            .replace(" ", "")
            .replace(Regex("[1234]"), "")  // strip tone numbers
            .lowercase()
    }

    fun destroy() {
        cancelCurrent()
        scope.cancel()
    }
}
