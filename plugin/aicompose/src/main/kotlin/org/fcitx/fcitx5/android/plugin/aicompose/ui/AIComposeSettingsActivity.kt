package org.fcitx.fcitx5.android.plugin.aicompose.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.plugin.aicompose.AIComposePlugin
import org.fcitx.fcitx5.android.plugin.aicompose.ModelInfo
import org.fcitx.fcitx5.android.plugin.aicompose.R
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Settings Activity for LLM Compose plugin.
 *
 * Manages:
 * - Model selection and loading / unloading (via AIComposePlugin singleton)
 * - Inference parameters: nThreads, nCtx
 * - LLM completion enable/disable
 * - HuggingFace model download
 *
 * Settings are persisted via SharedPreferences (plugin's private storage).
 * The plugin service reads these preferences on start().
 */
class AIComposeSettingsActivity : AppCompatActivity() {

    // ─── Preferences key constants ─────────────────────────────────────────

    private companion object {
        const val PREFS_NAME = "aicompose_settings"
        const val PREF_ENABLED = "llm_enabled"
        const val PREF_THREADS = "n_threads"
        const val PREF_NCTX = "n_ctx"
        const val PREF_LAST_MODEL = "last_model_path"
        const val PREF_ENABLED_DEFAULT = true
        const val PREF_THREADS_DEFAULT = 4
        const val PREF_NCTX_DEFAULT = 2048
        const val HF_DOWNLOAD_DIR = "models"
        const val HF_BASE = "https://huggingface.co"
    }

    // ─── Views ──────────────────────────────────────────────────────────────

    private lateinit var tvModelStatus: MaterialTextView
    private lateinit var progressLoading: LinearProgressIndicator
    private lateinit var btnLoadModel: MaterialButton
    private lateinit var rvModels: RecyclerView
    private lateinit var tvNoModels: MaterialTextView
    private lateinit var btnDownloadHf: MaterialButton
    private lateinit var layoutDownloadProgress: View
    private lateinit var progressDownload: LinearProgressIndicator
    private lateinit var tvDownloadStatus: MaterialTextView
    private lateinit var sliderThreads: Slider
    private lateinit var tvThreadsValue: MaterialTextView
    private lateinit var sliderCtx: Slider
    private lateinit var tvCtxValue: MaterialTextView
    private lateinit var switchEnable: SwitchMaterial

    private lateinit var prefs: android.content.SharedPreferences

    // ─── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_compose_settings)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        bindViews()
        loadSettings()
        setupModelList()
        setupListeners()
        updateModelStatus()
    }

    // ─── View binding (manual) ──────────────────────────────────────────────

    private fun bindViews() {
        tvModelStatus = findViewById(R.id.tvModelStatus)
        progressLoading = findViewById(R.id.progressLoading)
        btnLoadModel = findViewById(R.id.btnLoadModel)
        rvModels = findViewById(R.id.rvModels)
        tvNoModels = findViewById(R.id.tvNoModels)
        btnDownloadHf = findViewById(R.id.btnDownloadHf)
        layoutDownloadProgress = findViewById(R.id.layoutDownloadProgress)
        progressDownload = findViewById(R.id.progressDownload)
        tvDownloadStatus = findViewById(R.id.tvDownloadStatus)
        sliderThreads = findViewById(R.id.sliderThreads)
        tvThreadsValue = findViewById(R.id.tvThreadsValue)
        sliderCtx = findViewById(R.id.sliderCtx)
        tvCtxValue = findViewById(R.id.tvCtxValue)
        switchEnable = findViewById(R.id.switchEnable)
    }

    // ─── Load persisted settings into UI ───────────────────────────────────

    private fun loadSettings() {
        switchEnable.isChecked = prefs.getBoolean(PREF_ENABLED, PREF_ENABLED_DEFAULT)
        sliderThreads.value = prefs.getInt(PREF_THREADS, PREF_THREADS_DEFAULT).toFloat()
        sliderCtx.value = prefs.getInt(PREF_NCTX, PREF_NCTX_DEFAULT).toFloat()
        updateThreadsLabel(sliderThreads.value.toInt())
        updateCtxLabel(sliderCtx.value.toInt())
    }

    // ─── Setup listeners ────────────────────────────────────────────────────

    private fun setupListeners() {
        // Enable / disable
        switchEnable.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean(PREF_ENABLED, isChecked) }
        }

        // nThreads slider
        sliderThreads.addOnChangeListener { _, value, _ ->
            val threads = value.toInt()
            updateThreadsLabel(threads)
            prefs.edit { putInt(PREF_THREADS, threads) }
        }

        // nCtx slider
        sliderCtx.addOnChangeListener { _, value, _ ->
            val ctx = value.toInt()
            updateCtxLabel(ctx)
            prefs.edit { putInt(PREF_NCTX, ctx) }
        }

        // Load / Unload button
        btnLoadModel.setOnClickListener {
            if (AIComposePlugin.isLoaded()) {
                unloadModel()
            } else {
                val path = prefs.getString(PREF_LAST_MODEL, null)
                if (path != null) {
                    loadModel(path)
                } else {
                    Toast.makeText(this, "请先选择一个模型", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Download from HuggingFace — open URL input dialog
        btnDownloadHf.setOnClickListener {
            showHfDownloadDialog()
        }
    }

    // ─── Model list (built-in GGUF files) ─────────────────────────────────

    private fun setupModelList() {
        rvModels.layoutManager = LinearLayoutManager(this)
        val models = AIComposePlugin.getAvailableModelsStatic()

        if (models.isEmpty()) {
            rvModels.visibility = View.GONE
            tvNoModels.visibility = View.VISIBLE
        } else {
            rvModels.visibility = View.VISIBLE
            tvNoModels.visibility = View.GONE
            rvModels.adapter = ModelAdapter(models) { model ->
                prefs.edit { putString(PREF_LAST_MODEL, model.path) }
                updateModelStatus()
                // Auto-load when selected
                if (!AIComposePlugin.isLoaded()) {
                    loadModel(model.path)
                }
            }
        }
    }

    // ─── Model load / unload ────────────────────────────────────────────────

    private fun loadModel(path: String) {
        val nThreads = prefs.getInt(PREF_THREADS, PREF_THREADS_DEFAULT)
        val nCtx = prefs.getInt(PREF_NCTX, PREF_NCTX_DEFAULT)

        progressLoading.visibility = View.VISIBLE
        btnLoadModel.isEnabled = false

        // Run model loading on IO dispatcher — JNI call blocks
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = AIComposePlugin.loadModelStatic(path, nCtx, nThreads)
            withContext(Dispatchers.Main) {
                progressLoading.visibility = View.GONE
                btnLoadModel.isEnabled = true
                if (ok) {
                    Toast.makeText(this@AIComposeSettingsActivity, R.string.model_loaded, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@AIComposeSettingsActivity, R.string.download_failed, Toast.LENGTH_SHORT).show()
                }
                updateModelStatus()
            }
        }
    }

    private fun unloadModel() {
        AIComposePlugin.unloadModelStatic()
        Toast.makeText(this, R.string.model_unloaded, Toast.LENGTH_SHORT).show()
        updateModelStatus()
    }

    private fun updateModelStatus() {
        val loaded = AIComposePlugin.isLoaded()
        if (loaded) {
            val name = File(prefs.getString(PREF_LAST_MODEL, "") ?: "").nameWithoutExtension
            tvModelStatus.text = name.ifEmpty { getString(R.string.model_loaded) }
            btnLoadModel.text = getString(R.string.unload_model)
        } else {
            tvModelStatus.text = getString(R.string.no_model_loaded)
            btnLoadModel.text = getString(R.string.load_model)
        }
    }

    // ─── HuggingFace download ────────────────────────────────────────────────

    private fun showHfDownloadDialog() {
        val editText = android.widget.EditText(this).apply {
            hint = "e.g. TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/tinyllama-1.1b-chat-v1.0-q4_k_m.gguf"
            setText("TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/tinyllama-1.1b-chat-v1.0-q4_k_m.gguf")
        }

        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.download_from_hf)
            .setView(editText)
            .setPositiveButton("下载") { _, _ ->
                val selector = editText.text.toString().trim()
                if (selector.isNotEmpty()) downloadFromHf(selector)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun downloadFromHf(selector: String) {
        // Map selector "owner/repo/filename.gguf" → direct file URL
        val parts = selector.split("/")
        if (parts.size < 3) {
            Toast.makeText(this, "格式: owner/repo/filename.gguf", Toast.LENGTH_SHORT).show()
            return
        }
        val repo = parts.dropLast(1).joinToString("/")
        val filename = parts.last()
        val fileUrl = "$HF_BASE/$repo/resolve/main/$filename"
        val outFile = File(filesDir, "$HF_DOWNLOAD_DIR/$filename")

        layoutDownloadProgress.visibility = View.VISIBLE
        btnDownloadHf.isEnabled = false
        progressDownload.progress = 0

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    tvDownloadStatus.text = getString(R.string.downloading)
                }

                val url = URL(fileUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.connect()

                val total = conn.contentLength.toLong()
                outFile.parentFile?.mkdirs()

                conn.inputStream.use { input ->
                    outFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var downloaded = 0L
                        var bytes: Int
                        while (input.read(buffer).also { bytes = it } != -1) {
                            output.write(buffer, 0, bytes)
                            downloaded += bytes
                            if (total > 0) {
                                val pct = ((downloaded * 100) / total).toInt()
                                withContext(Dispatchers.Main) {
                                    progressDownload.progress = pct
                                    tvDownloadStatus.text = "$pct% — $filename"
                                }
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    layoutDownloadProgress.visibility = View.GONE
                    btnDownloadHf.isEnabled = true
                    Toast.makeText(this@AIComposeSettingsActivity, R.string.download_complete, Toast.LENGTH_SHORT).show()
                    // Reload model list
                    setupModelList()
                    // Auto-select downloaded model
                    prefs.edit { putString(PREF_LAST_MODEL, outFile.absolutePath) }
                    updateModelStatus()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    layoutDownloadProgress.visibility = View.GONE
                    btnDownloadHf.isEnabled = true
                    Toast.makeText(this@AIComposeSettingsActivity, "${getString(R.string.download_failed)}: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ─── Slider label helpers ────────────────────────────────────────────────

    private fun updateThreadsLabel(threads: Int) {
        tvThreadsValue.text = "$threads ${getString(R.string.inference_threads)}"
    }

    private fun updateCtxLabel(ctx: Int) {
        tvCtxValue.text = "$ctx ${getString(R.string.context_length)}"
    }

    // ─── RecyclerView Adapter for model list ───────────────────────────────

    private class ModelAdapter(
        private val models: List<ModelInfo>,
        private val onClick: (ModelInfo) -> Unit
    ) : RecyclerView.Adapter<ModelAdapter.VH>() {

        class VH(val tvName: MaterialTextView, val tvSize: MaterialTextView) :
            RecyclerView.ViewHolder(tvName.rootView)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val tvName = MaterialTextView(parent.context)
            val tvSize = MaterialTextView(parent.context)
            tvName.layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            tvName.setPadding(24, 16, 24, 4)
            tvName.textSize = 16f
            tvSize.setPadding(24, 0, 24, 16)
            tvSize.textSize = 12f
            tvSize.setTextColor(0xFF888888.toInt())

            val container = android.widget.LinearLayout(parent.context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                addView(tvName)
                addView(tvSize)
            }
            val lp = android.view.ViewGroup.MarginLayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            parent.addView(container, lp)
            return VH(tvName, tvSize)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val m = models[position]
            holder.tvName.text = m.name
            holder.tvSize.text = "${m.sizeMb} MB"
            holder.itemView.setOnClickListener { onClick(m) }
        }

        override fun getItemCount() = models.size
    }
}
