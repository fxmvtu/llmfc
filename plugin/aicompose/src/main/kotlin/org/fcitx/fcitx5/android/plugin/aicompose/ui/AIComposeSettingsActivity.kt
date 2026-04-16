package org.fcitx.fcitx5.android.plugin.aicompose.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.fcitx.fcitx5.android.plugin.aicompose.R

/**
 * Settings Activity for LLM Compose plugin.
 *
 * Allows the user to:
 * - Select and load/unload a GGUF model
 * - Configure inference parameters (nThreads, nCtx)
 * - Enable/disable LLM completion
 *
 * Currently a minimal stub — full UI implementation follows.
 */
class AIComposeSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Full settings UI will be implemented here.
        // For now, show a toast indicating the settings are not yet implemented.
        Toast.makeText(this, R.string.settings_title, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        // Clean up any resources (e.g. coroutine scopes, model handles)
        // when the Activity is destroyed. Full implementation will extend this.
        super.onDestroy()
    }
}
