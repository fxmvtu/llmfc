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
     * Pre-warm the suggestion cache with the current pinyin preedit.
     * This is a fire-and-forget async trigger (oneway) — it schedules
     * LLM inference in the background so subsequent getSuggestions() calls
     * can return immediately from cache without blocking the caller.
     *
     * This separates the trigger path (keystroke → onPreeditChanged → async inference)
     * from the query path (getSuggestions → synchronous cache read).
     *
     * NOTE: Because this is oneway, rapid consecutive calls will replace any
     * pending inference for the previous pinyin — old results are discarded.
     * This is the intended behaviour: only the most recent pinyin matters.
     */
    oneway void onPreeditChanged(String pinyin);

    /**
     * Check if the suggestion engine is ready (model loaded).
     */
    boolean isReady();
}
