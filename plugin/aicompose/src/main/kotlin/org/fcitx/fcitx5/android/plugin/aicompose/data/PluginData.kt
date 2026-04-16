package org.fcitx.fcitx5.android.plugin.aicompose.data

/**
 * PluginData — plugin-specific persistent data container.
 *
 * This class is required by the [org.fcitx.fcitx5.android.plugin.base.FcitxPlugin] base class.
 * It holds per-plugin configuration that survives across IME restarts.
 *
 * Currently empty — extend as needed when adding persistent settings
 * (e.g. selected model path, inference thread count, enabled state).
 */
class PluginData {
    // TODO: add persistent fields as settings are implemented
    // e.g. var selectedModelPath: String = ""
    // e.g. var inferenceThreads: Int = 4
    // e.g. var contextLength: Int = 2048
}
