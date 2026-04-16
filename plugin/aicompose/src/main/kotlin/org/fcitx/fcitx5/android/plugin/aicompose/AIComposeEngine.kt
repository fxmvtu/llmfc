package org.fcitx.fcitx5.android.plugin.aicompose

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * AIComposeEngine — orchestrates pinyin → LLM → candidate words.
 *
 * Integrates with the Fcitx5 input pipeline:
 *   1. Receives raw pinyin input (e.g. "ni hao")
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
     * @param pinyinRaw raw pinyin string, e.g. "wozhongguo" or "ni hao"
     * @param maxCandidates max number of candidate phrases to return
     * @param callback called with each updated candidate list
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
                // Accumulate output character by character
                val buffer = StringBuilder()
                val lock = Object()

                llamaEngine.completeStream(
                    prompt = normalizePinyin(pinyinRaw),
                    maxTokens = 32
                ) { token ->
                    // token arrives on IO thread; switch to main for UI updates
                    scope.launch(Dispatchers.Main) {
                        synchronized(lock) {
                            buffer.append(token)

                            // Build multiple candidates at different lengths so the user
                            // sees progressive options even before generation finishes.
                            // Using fixed breakpoints avoids showing many duplicates.
                            val output = buffer.toString()
                            val breakpoints = listOf(4, 8, 12, 16, 20, 24, 28)
                                .filter { it <= output.length }
                                .take(maxCandidates - 1)

                            val candidates = if (breakpoints.isEmpty()) {
                                listOf(output.take(maxCandidates))
                            } else {
                                (breakpoints.map { output.take(it) } + output)
                                    .distinct()
                                    .take(maxCandidates)
                            }

                            callback(candidates)
                        }
                    }
                }
            } finally {
                _isGenerating.value = false
            }
        }.also { job -> currentJob = job }
    }

    /**
     * Cancel any in-flight generation (called when user keeps typing).
     */
    fun cancelCurrent() {
        currentJob?.cancel()
        currentJob = null
    }

    /**
     * Normalize pinyin for LLM input:
     * 1. Strip tone numbers (e.g. "ni3 hao3" → "ni hao")
     * 2. Convert "v" to "u" (ü used in nü, lü, etc.)
     * 3. Strip spaces — the LLM prompt handles segmentation via explicit instruction
     *
     * e.g. "nv" → "nu", "lv" → "lu", "ni3 hao3" → "nihao"
     *
     * @PublishedApi so test code in the same module can access it without exposing
     * a public API surface.
     */
    @PublishedApi
    internal fun normalizePinyin(raw: String): String {
        return raw
            .replace(" ", "")
            .replace(Regex("[1234]"), "")   // strip tone numbers
            .replace("v", "u")              // nü/lü → nu/lu (LLM input)
            .lowercase()
    }

    fun destroy() {
        cancelCurrent()
        scope.cancel()
    }
}
