# 🔍 LLMFC 项目代码审核报告

**项目路径：** `/home/kokoro/workspace/llmfc`
**审核日期：** 2026-04-16
**审核者：** 萤萤·审查员

---

## 一、严重问题（必须修复）

### 🔴 Issue #1：`PluginData` 源码缺失 — 编译错误级

**位置：** `AIComposePlugin.kt` 第 5 行 + 第 58 行

```kotlin
import org.fcitx.fcitx5.android.plugin.aicompose.data.PluginData   // ❌ 引用不存在的包
...
override val data: PluginData = PluginData()                       // ❌ 类不存在
```

**事实：**
- `src/main/kotlin/.../data/` 目录不存在，无任何 `.kt` 文件
- 搜索整个 `fcitx5-android` 树，未找到 `PluginData` 类定义
- `build/intermediates/dex/debug/mergeExtDexDebug/classes.dex` 存在 → 说明构建产物有 DEX，但源码缺失

**分析：** DEX 产物存在有两种可能：① 之前某次编译侥幸通过但源码已丢失；② `build-logic` 代码生成。若是①则现在必然无法重编译；若是②则缺少生成的 `PluginData` 源码文件。

**修复方案：** 确认 `PluginData` 是否由 `fcitx-component` plugin 在编译时自动生成。需在 aicompose 源码树下补充 `data/PluginData.kt`，或检查 `fcitxComponent {}` DSL 是否有生成数据的配置。

---

### 🔴 Issue #2：`AndroidManifest.xml` 为空 — 运行时崩溃风险

**位置：** `plugin/aicompose/src/main/AndroidManifest.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
</manifest>
```

**问题：** 插件 AndroidManifest 空无一物。对比 `plugin/rime` 和 `plugin/pinyin`，应有：

```xml
<manifest ...>
    <uses-permission android:name="android.permission.INTERNET" />  <!-- 模型下载可能需要 -->
    <application>
        <activity android:name="...AIComposeSettingsActivity" />     <!-- 设置页 -->
    </application>
</manifest>
```

---

## 二、高优先级问题

### 🟠 Issue #3：JNI 函数签名不匹配 — 运行时 `UnsatisfiedLinkError`

**`LlamaEngine.kt` 第 102–105 行：**

```kotlin
private external fun _loadModelNative(modelPath: String, nCtx: Int, nThreads: Int): Boolean
private external fun _unloadModelNative()
private external fun _completeNative(prompt: String, maxTokens: Int): String
private external fun _completeStreamNative(prompt: String, maxTokens: Int, callback: (String) -> Unit)
```

**`LlamaEngine.cpp` JNI 函数名：**

```cpp
Java_org_fcitx_fcitx5_android_plugin_aicompose_LlamaEngine_loadModelNative
Java_org_fcitx_fcitx5_android_plugin_aicompose_LlamaEngine_unloadModelNative
Java_org_fcitx_fcitx5_android_plugin_aicompose_LlamaEngine_completeNative
Java_org_fcitx_fcitx5_android_plugin_aicompose_LlamaEngine_completeStreamNative
```

**包路径：** `org.fcitx.fcitx5.android.plugin.aicompose` ✅ 对齐
**Native 方法名：** ✅ 对齐

**但 `completeStreamNative` 的 Kotlin 签名有误：**

```kotlin
// 当前 — 传递 Kotlin lambda，JNI 无法直接使用
private external fun _completeStreamNative(
    prompt: String, maxTokens: Int, callback: (String) -> Unit
)
```

JNI 层尝试通过 `GetMethodID(callbackClass, "invoke", "(Ljava/lang/String;)V")` 调用，但 Kotlin `Function1` 的 SAM 转换在 JNI 侧并不稳定，**随时可能崩溃**。

**正确做法：** 使用 `jobject` + `JNIEnv->CallVoidMethod()`，或传 `jmethodID` 单独传来。建议参考 Android NDK `gamesdk` 的 callback 模式。

---

### 🟠 Issue #4：`LlamaEngine.cpp` 全局状态非线程安全

**位置：** `LlamaEngine.cpp` 第 18–22 行

```cpp
static struct llama_model * s_model = nullptr;
static struct llama_context * s_ctx = nullptr;
static struct llama_model_params s_mparams;
static int s_n_ctx = 2048;
static std::atomic<bool> s_model_loaded(false);
```

**问题：**

1. `s_mparams` 是值拷贝而非指针，后续 `nThreads` 参数修改无法生效
2. `loadModel` 和 `completeStream` 可被并发调用，但 `llama_decode` 共享同一个 `s_ctx` — **并发崩溃**
3. `unloadModel` 和 `completeStream` 并发调用时，use-after-free

**修复：加锁**

```cpp
static std::mutex s_mutex;
std::lock_guard<std::mutex> lock(s_mutex);
// 所有模型/上下文操作
```

---

### 🟠 Issue #5：`LlamaEngine.kt` `completeStream` 返回 `Job` 但无法取消

**位置：** `LlamaEngine.kt` 第 81–90 行

```kotlin
fun completeStream(prompt: String, maxTokens: Int = 32,
    callback: (token: String) -> Unit): Job {
    check(_isLoaded.value) { "Model not loaded" }
    return inferenceScope.launch {
        _completeStreamNative(prompt, maxTokens, callback)  // ⚠️ cancel 不会中断 JNI 调用
    }
}
```

**问题：** `Job.cancel()` 无法中断正在阻塞的 JNI C++ 循环（`while (generated < max_toks)` + `llama_decode`）。用户取消后 coroutine 会被标记为 cancelled，但 C++ 代码继续运行直至完成或崩溃。

**修复：**

1. C++ 层加入 `s_model_loaded` 原子检查 + `ggml_backend_buffer_cache` 能力
2. 或在 C++ 循环中加入 `checkPaused()` 轮询

---

### 🟠 Issue #6：Prompt 模板与中文分词 — LLM 输出质量隐患

**位置：** `LlamaEngine.cpp` 第 39–50 行

```cpp
static std::string generate_prompt(const char * input) {
    oss << "### Instruction:\n"
        << "Convert the following pinyin (without tones) to Chinese characters.\n"
        ...
        << "Pinyin: " << input << "\n\n"
        << "Chinese:";  // 冒号后无空格，tokenizer 可能产生粘连
}
```

**问题：**

1. 单拼 "wozhongguo" → LLM 需要自己分词，质量不稳定
2. 建议加入**分词提示**： `"Pinyin: wo zhong guo\nChinese: 我中国"` 或让用户输入带空格的拼音
3. 推理流程 `AIComposeEngine.normalizePinyin()` 将所有空格删掉，传给 LLM 的是连续拼音 → 效果差

---

## 三、中等问题

### 🟡 Issue #7：`AIComposeEngine.kt` 候选列表构建逻辑错误

**位置：** `AIComposeEngine.kt` 第 67–89 行

```kotlin
val candidates = mutableListOf<String>()
...
candidates.add(updated)   // 只有一个 candidate，永远被替换
...
callback(candidates.map { it.take(20) }.distinct().take(maxCandidates))
```

**问题：** 每次 token 到达，只有一个候选（累积的完整字符串），`distinct` 无意义。`maxCandidates` 永远只有 1 个候选可用。正确的流式推理应提供**多个不同长度的截断候选**：

```kotlin
// 方案：提供不同长度截断
candidates = listOf(
    output.take(4),      // 短候选
    output.take(8),     // 中候选
    output.take(12),    // 长候选
    output              // 完整
).distinct()
```

---

### 🟡 Issue #8：`nThreads` 参数双重标准

**位置：** `LlamaEngine.cpp` 第 91–98 行 vs 第 117 行

```cpp
// Model params — 全局 s_mparams 拷贝，无锁写入
s_mparams.n_threads = nThreads;

// Context params — 使用局部 cparams
cparams.n_threads = nThreads > 0 ? nThreads : std::thread::hardware_concurrency();
```

**问题：** `s_mparams.n_threads` 设置在模型层，`cparams.n_threads` 设置在上下文层，可能产生不一致。`s_mparams` 作为全局变量在 `loadModel` 前未被重置，若第二次加载不传 `nThreads`，会沿用上次的值。

---

### 🟡 Issue #9：Gradle 依赖缺失 NDK 版本声明

**位置：** `fcitx5-android/gradle.properties`

`build.gradle.kts` 中没有显式声明 `ndkVersion`，依赖 AGP 自动选择。llama.cpp 的 CMakeLists 对 NDK 版本敏感（r25c 在 AGP 8.1+ 有已知问题），建议显式锁定：

```kotlin
android {
    defaultConfig {
        ndk {
            version = "25c"  // 显式声明
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }
}
```

---

### 🟡 Issue #10：`GGML_NO_VULKAN=OFF` 与 `GGML_NATIVE=ON` 冲突

**位置：** `CMakeLists.txt` 第 10–21 行

```cmake
-DGGML_NATIVE=ON        # x86_64 主机优化
-DGGML_NO_VULKAN=OFF   # Android 上 Vulkan 层
```

在 Android NDK 交叉编译时 `GGML_NATIVE=ON` 指向 host 架构，而非 Android target，会在链接时产生警告或错误。

**修复：**

```cmake
# Android 交叉编译时 GGML_NATIVE 应为 OFF
set(LLAMA_CMAKE_ARGS
    -DGGML_NATIVE=OFF
    -DGGML_OPENBLAS=OFF
    -DGGML_ACCELERATE=OFF
    ...
)
```

---

## 四、低优先级 / 建议

| # | 问题 | 位置 | 建议 |
|---|------|------|------|
| 11 | `plugin.xml` `<depends>llama</depends>` — `llama` 在 Fcitx5 插件系统中未注册 | `plugin.xml:15` | 改为动态库依赖或移除 |
| 12 | `download_model.py` 使用 `snapshot_download` 下载**全部文件**而非指定 GGUF | `download_model.py:54` | 用户需指定 `--filename`，否则下载整个 repo |
| 13 | `AIComposeEngine.normalizePinyin` 只去数字，对 ü/v/nü 处理缺失 | `AIComposeEngine.kt:114` | 补充 `ü→u`, `v→u` 转换 |
| 14 | 无单元测试 | 全局 | `AIComposeEngine` 的 `normalizePinyin` 和 `requestCompletion` 逻辑应有测试 |
| 15 | `AGENT.md` 第 6 行 token 已明文写入 | `AGENT.md:6` | 应使用环境变量引用而非明文 |

---

## 📋 总结

| 等级 | 数量 | 代表问题 |
|------|------|---------|
| 🔴 严重 | 2 | `PluginData` 缺失、`AndroidManifest` 空 |
| 🟠 高优先 | 4 | JNI callback 线程安全、并发 use-after-free、Job 无法取消、Prompt 分词质量 |
| 🟡 中等 | 4 | 候选列表逻辑错误、nThreads 双重标准、NDK 版本未锁定、GGML_NATIVE 冲突 |
| 💡 建议 | 5 | 其他优化点 |

---

## ✅ 当前代码真正可用的部分

- ✅ `llama.cpp` submodule 集成路径正确
- ✅ JNI 包路径命名规范
- ✅ `LlamaEngine.cpp` `generate_prompt()` 模板设计合理
- ✅ `download_model.py` 脚本结构完整
- ✅ 流式推理核心循环逻辑（C++ 侧）基本正确

---

## 🚨 最紧急的三件事

1. **补全 `PluginData.kt` 源码** — 确认 build-logic 生成路径，或在 `data/PluginData.kt` 手动补充
2. **修复 JNI `completeStreamNative` 的 callback 模式** — 改用 `jmethodID` 传参而非直接传 Kotlin lambda
3. **给 `LlamaEngine.cpp` 全局操作加 `std::mutex`** — 防止并发 use-after-free

---

*审核报告版本：v1.0*
*审核人：萤萤·审查员*
