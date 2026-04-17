package org.fcitx.fcitx5.android.common.ipc;

/**
 * IPC interface for LLM-powered input suggestions.
 * The main app (FcitxInputMethodService) calls this on plugins to get
 * AI-completed candidate words for the current preedit/input context.
 */
interface IInputSuggestions {
    /**
     * Get LLM-completed candidate strings for the given pinyin preedit.
     * @param pinyin The current pinyin input (e.g. "nihao")
     * @param limit Max number of candidates to return
     * @return Array of candidate strings (e.g. ["你好", "你好啊", "你好吗"])
     */
    String[] getSuggestions(String pinyin, int limit);

    /**
     * Check if the suggestion engine is ready (model loaded).
     */
    boolean isReady();
}
