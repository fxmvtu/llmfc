# LLMFC — 端侧大模型输入法案

**项目代号**：LLMFC（LLM + Fcitx5）
**基础框架**：[fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) ⭐ 5,091
**LLM 引擎**：llama.cpp（NDK/JNI，GGUF 格式）
**开发语言**：Kotlin + C++ (JNI)
**协议**：LGPL v2.1（继承 fcitx5）+ Apache-2.0（llama.cpp）

---

## 1. 项目目标

将 fcitx5-android 打造成**首个支持端侧 LLM 实时推理补全**的开源 Android 输入法：

1. 用户敲拼音 → LLM 流式生成候选语句 → 候选词上屏
2. 100% 离线运行，无需网络
3. 模型可替换（Qwen2.5 / Phi-3 / MiniCPM / Gemma 等 GGUF 格式）
4. 最终打包为单一 APK（模型内置或从 HuggingFace 下载）

---

## 2. 系统架构

```
┌──────────────────────────────────────────────────────────┐
│                    Android Framework                       │
│  ┌────────────────────────────────────────────────────┐  │
│  │           FcitxInputMethodService (Kotlin)          │  │
│  │  ┌──────────────────┐  ┌─────────────────────────┐  │  │
│  │  │  PinyinEngine    │  │   AIComposePlugin       │  │  │
│  │  │  (内置拼音引擎)   │  │   [新增插件模块]         │  │  │
│  │  └────────┬─────────┘  └──────────┬──────────────┘  │  │
│  │           │                        │                 │  │
│  │           │             ┌───────────▼───────────────┐│  │
│  │           │             │   LlamaEngine JNI Bridge  ││  │
│  │           │             │   LlamaEngine.kt (Kotlin) ││  │
│  │           │             │        +                 ││  │
│  │           │             │   LlamaEngine.cpp (JNI)   ││  │
│  │           │             └───────────┬───────────────┘│  │
│  │           │                          │                 │  │
│  └───────────┼──────────────────────────┼─────────────────┘  │
│              │                          │                    │
│              │               ┌───────────▼───────────────┐  │
│              │               │   llama.cpp (C++)         │  │
│              │               │   libllama.so (NDK)        │  │
│              │               └───────────┬───────────────┘  │
│              │                           │                   │
│  ┌───────────▼───────────────────────────┼─────────────────┐  │
│  │           GGUF Model File              │                 │  │
│  │   (Qwen2.5-1.5B-Instruct-Q4_K_M.gguf)  │                 │  │
│  │   存储于 app_internal / 用户下载目录    │                 │  │
│  └────────────────────────────────────────┴─────────────────┘  │
└──────────────────────────────────────────────────────────┘
```

### 核心组件职责

| 组件 | 语言 | 职责 |
|------|------|------|
| `FcitxInputMethodService` | Kotlin | 输入法生命周期、IME 协议、UI 渲染 |
| `AIComposePlugin` | Kotlin | 输入事件拦截、拼音→LLM 路由、候选列表管理 |
| `LlamaEngine` | Kotlin | JNI 调用封装、模型加载、推理参数配置 |
| `LlamaEngine.cpp` | C++ | JNI 导出、llama.cpp 推理调用、流式输出回调 |
| `llama.cpp` | C++ | GGUF 模型加载、Tensor 推理、KV Cache |
| `fcitx5-core` | C++ | 输入法核心逻辑（词库、候选排序） |

---

## 3. 项目目录结构

```
llmfc/                                    # GitHub repo 根目录
├── SPEC.md                               # 本文档
├── README.md                             # 项目说明
│
├── fcitx5-android/                       # fork 自 fcitx5-android/fcitx5-android
│   ├── app/                              # 主 APK 模块
│   │   └── src/main/
│   │       ├── java/org/fcitx/fcitx5/android/
│   │       │   ├── FcitxApplication.kt
│   │       │   ├── FcitxInputMethodService.kt
│   │       │   └── input/                 # 输入法 UI（键盘、候选栏等）
│   │       └── res/
│   │
│   ├── lib/                              # 核心库模块
│   │   ├── fcitx5/                       # fcitx5 C++ 核心（submodule）
│   │   ├── fcitx5-chinese-addons/        # 中文输入引擎（submodule）
│   │   ├── libime/                       # libime 词库库
│   │   └── plugin-base/                  # 插件基础设施
│   │
│   ├── plugin/                           # 输入法插件
│   │   ├── rime/                         # [参考] Rime 引擎插件
│   │   ├── pinyin/                       # [参考] 内置拼音插件
│   │   └── aicompose/                    # ★ 新增：LLM AI 补全插件
│   │       ├── build.gradle.kts
│   │       ├── src/main/
│   │       │   ├── AndroidManifest.xml
│   │       │   ├── kotlin/org/fcitx/fcitx5/android/plugin/aicompose/
│   │       │   │   ├── AIComposePlugin.kt        # 插件入口
│   │       │   │   ├── AIComposeEngine.kt        # 补全逻辑
│   │       │   │   └── LlamaEngine.kt             # JNI 封装
│   │       │   ├── cpp/
│   │       │   │   └── LlamaEngine.cpp            # JNI 实现
│   │       │   ├── assets/
│   │       │   │   └── models/                    # 内置模型目录（可选）
│   │       │   └── res/
│   │
│   ├── llama.cpp/                        # ★ 新增：llama.cpp（submodule 或 gradle 依赖）
│   │
│   └── build.gradle.kts                  # 根构建配置
│
├── models/                               # GGUF 模型文件（不 commit，用 git-lfs 或 downloader）
│   └── README.md                         # 说明：需下载哪些模型
│
└── scripts/                              # 辅助脚本
    ├── download_model.py                 # HuggingFace 模型下载工具
    └── build_apk.sh                      # APK 构建脚本
```

---

## 4. 模块接口设计

### 4.1 AIComposePlugin（插件入口）

```kotlin
// plugin/aicompose/src/main/kotlin/.../AIComposePlugin.kt

class AIComposePlugin : AddonInterface {
    // 由 fcitx5 插件系统调用，传入原始输入字符串（如拼音）
    override suspend fun processInput(
        pin yin: String,
        context: InputContext
    ): List<Candidate>

    // 启用/禁用 LLM 补全
    fun setEnabled(enabled: Boolean)

    // 更新当前使用的模型
    fun setModel(modelPath: String)

    // 获取模型列表（内置 + 用户下载）
    fun getAvailableModels(): List<ModelInfo>
}
```

### 4.2 LlamaEngine（JNI 封装）

```kotlin
// plugin/aicompose/src/main/kotlin/.../LlamaEngine.kt

class LlamaEngine {
    // 加载 GGUF 模型（同步，耗时长，应在后台线程）
    fun loadModel(modelPath: String, params: ModelParams): Boolean

    // 卸载模型，释放内存
    fun unloadModel()

    // 同步推理：给定前缀，返回完整补全
    fun complete(prompt: String, maxTokens: Int): String

    // 流式推理：注册回调，逐 token 产出
    fun completeStream(
        prompt: String,
        maxTokens: Int,
        callback: (token: String) -> Unit
    )

    // 检查模型是否已加载
    fun isModelLoaded(): Boolean

    // 获取当前模型名称
    fun getModelName(): String
}
```

### 4.3 LlamaEngine.cpp（JNI 实现）

```cpp
// plugin/aicompose/src/main/cpp/LlamaEngine.cpp

extern "C" {
    JNIEXPORT jboolean JNICALL
    Java_org_fcitx_fcitx5_android_plugin_aicompose_LlamaEngine_loadModel(
        JNIEnv *env, jobject thiz, jstring modelPath, jint nCtx, jint nThreads);

    JNIEXPORT void JNICALL
    Java_org_fcitx_fcitx5_android_plugin_aicompose_LlamaEngine_unloadModel(
        JNIEnv *env, jobject thiz);

    JNIEXPORT jstring JNICALL
    Java_org_fcitx_fcitx5_android_plugin_aicompose_LlamaEngine_complete(
        JNIEnv *env, jobject thiz, jstring prompt, jint maxTokens);

    JNIEXPORT void JNICALL
    Java_org_fcitx_fcitx5_android_plugin_aicompose_LlamaEngine_completeStream(
        JNIEnv *env, jobject thiz, jstring prompt, jint maxTokens, jobject callback);
}
```

---

## 5. 输入流程与 UI 交互

### 5.1 用户输入流程

```
用户输入拼音 "ni hao"
        │
        ▼
FcitxInputMethodService.onKeyDown()
        │
        ├──► PinyinEngine.processInput("ni hao")
        │            └──► 传统候选词（你 好 / 你好 / ...）
        │
        └──► AIComposePlugin.processInput("nihao")   ← 连续拼音，无空格
                    │
                    └──► LlamaEngine.completeStream("nihao", 32, onToken)
                            │
                            ├── Token: "你"
                            ├── Token: "好"
                            ├── Token: "呀"
                            └── Token: EOS
                    │
                    └──► 候选栏展示：你好 | 你好呀 | 你好呀呀 ...
                              [点击选中] → 上屏
```

### 5.2 候选视图布局

```
┌─────────────────────────────────────────────────┐
│  你 好 啊  ↆ                               [LLM] │  ← 候选栏（fancy 候选词）
├─────────────────────────────────────────────────┤
│  q   w   e   r   t   y   u   i   o   p         │
│   a   s   d   f   g   h   j   k   l             │  ← 虚拟键盘
│  ⇧   z   x   c   v   b   n   m   ⌫             │
│ 123  🌐  [空格____空格]  。  ⏎                   │
└─────────────────────────────────────────────────┘
```

- **LLM 按钮**：点击切换 LLM 补全开/关
- **候选词**：第一行优先显示 LLM 补全结果，后接传统拼音候选
- 流式输出时，已输出的字实时上屏（类似打字的动态效果）

### 5.3 模型切换 UI

在输入法设置页新增：

```
LLM 设置
├── 当前模型：Qwen2.5-1.5B-Instruct  [切换]
├── 模型来源：内置 / 从 HuggingFace 下载
├── 下载模型 → 跳转 HuggingFace 选择页
├── 推理线程数：4 [slider 1-8]
├── Context 长度：2048 [slider 512-4096]
└── LLM 补全开关：[ON/OFF]
```

---

## 6. 构建配置

### 6.1 Gradle 依赖

在 `plugin/aicompose/build.gradle.kts`：

```kotlin
plugins {
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "org.fcitx.fcitx5.android.plugin.aicompose"

    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DGGML_NATIVE=ON",
                    "-DGGML_OPENBLAS=OFF",
                    "-DGGML_ACCELERATE=OFF",
                    "-DGGML_STATIC=ON",
                    "-DCMAKE_POSITION_INDEPENDENT_CODE=ON"
                )
            }
        }
    }

    buildFeatures {
        prefab = true
    }
}

dependencies {
    implementation(project(":lib:plugin-base"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.annotation:annotation:1.7.1")

    // llama.cpp AAR（由 CMake 预编译后发布，或从 MavenCentral 获取）
    // 初期用源码 submodule + CMake 构建
}
```

### 6.2 CMake 集成（llama.cpp）

```cmake
# plugin/aicompose/src/main/cpp/CMakeLists.txt

cmake_minimum_required(VERSION 3.18)
project(llama_engine)

set(LLAMACPP_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../llama.cpp)
add_subdirectory(${LLAMACPP_ROOT} llama-build EXCLUDE_FROM_ALL)

add_library(llama_engine SHARED LlamaEngine.cpp)
target_link_libraries(llama_engine fcitx5 llama PRIVATE)
target_include_directories(llama_engine PRIVATE ${LLAMACPP_ROOT})
```

### 6.3 NDK 版本

- **NDK r25c**（LTS，稳定）
- **Android Gradle Plugin** 8.1+
- **compileSdk** 34，**minSdk** 24

---

## 7. 模型管理

### 7.1 内置模型策略

APK 包体过大（44MB 基础 + 1-3GB 模型），**不**将 GGUF 打入 APK。
采用运行时下载机制：

| 方案 | 实现 |
|------|------|
| 首次引导下载 | APK 安装后弹窗，引导用户下载轻量模型（Qwen2.5-0.5B，约 400MB） |
| HuggingFace 下载 | 内置 HuggingFace Hub API，支持搜索/下载 GGUF 模型 |
| 模型存放路径 | `context.filesDir/models/`（应用私有目录） |
| 模型切换 | 设置页选择，加载到 LlamaEngine |

### 7.2 推荐模型列表

| 模型 | 参数量 | GGUF 文件 | 推荐场景 |
|------|--------|-----------|---------|
| Qwen2.5-0.5B | 0.5B | `Qwen2.5-0.5B-Instruct-Q4_K_M.gguf` | 低端机，默认内置 |
| Qwen2.5-1.5B | 1.5B | `Qwen2.5-1.5B-Instruct-Q4_K_M.gguf` | 主流机，主推 |
| Phi-3-mini | 3.8B | `Phi-3-mini-4k-instruct-q4.gguf` | 旗舰机 |
| MiniCPM-2B | 2B | `MiniPM-2B-dits-Q4_K_M.gguf` | 中文优化 |

### 7.3 下载器脚本

```python
# scripts/download_model.py
"""
从 HuggingFace 下载 GGUF 模型到指定目录。
用法：python download_model.py Qwen/Qwen2.5-0.5B-Instruct output/
"""
import sys
import os
from huggingface_hub import hf_hub_download

model_id = sys.argv[1]
out_dir = sys.argv[2] if len(sys.argv) > 2 else "./models"
os.makedirs(out_dir, exist_ok=True)

# 列出该 repo 下所有 .gguf 文件
from huggingface_hub import list_repo_files
files = list_repo_files(model_id, repo_type="model")
gguf_files = [f for f in files if f.endswith(".gguf")]

for gguf in gguf_files:
    print(f"Downloading {gguf} ...")
    path = hf_hub_download(model_id, gguf, local_dir=out_dir)
    print(f"Saved to {path}")
```

---

## 8. 执行计划

### 阶段 1：基础设施搭建（Week 1）

| Day | 任务 | 交付物 |
|-----|------|--------|
| 1 | Fork fcitx5-android，导入 IDE，编译通过 | APK（不含 LLM） |
| 2 | 确认 fcitx5 plugin 机制，复读 Rime 插件结构 | 笔记：plugin 生命周期 |
| 3 | 引入 llama.cpp submodule，CMake 编译 `libllama.so` | NDK 构建产物 |
| 4 | 编写 `LlamaEngine.kt` + `LlamaEngine.cpp` JNI 封装 | Java JNI 桩 |
| 5 | 用 JNI Test Activity 验证 llama.cpp 推理 | 独立可运行的 Test APK |

### 阶段 2：LLM 推理集成（Week 2）

| Day | 任务 | 交付物 |
|-----|------|--------|
| 6 | `AIComposePlugin.kt` 骨架：接收拼音，调用 LlamaEngine | 插件骨架 |
| 7 | 流式推理：llama.cpp streaming API → JNI callback → Kotlin Flow | 流式管道 |
| 8 | Fcitx5 候选栏集成：补全结果→`CandidateWord`→显示 | 候选栏渲染 |
| 9 | 模型下载器：HuggingFace Hub API + 进度 UI | 下载管理页 |
| 10 | 端到端测试：输入拼音 "wo ai ni" → LLM 生成候选 | 集成测试 APK |

### 阶段 3：产品化（Week 3）

| Day | 任务 | 交付物 |
|-----|------|--------|
| 11 | 设置页：模型切换 / 线程数 / Context 长度 | 设置 UI |
| 12 | 内存管理：模型卸载 / 低内存降级 | 稳定性 |
| 13 | 多 ABI 打包（arm64-v8a + armeabi-v7a） | 多架构 APK |
| 14 | 最终测试 + Debug APK 输出 | 可测试的 Debug APK |

---

## 9. 技术风险与对策

| 风险 | 等级 | 应对 |
|------|------|------|
| llama.cpp NDK 编译失败 | 中 | 使用官方 `examples/llama.android` CMake 脚手架做基准 |
| 流式推理阻塞主线程 | 高 | 推理强制在独立 Coroutine Dispatcher（IO/Default），主线程只管 UI |
| 输入延迟（LLM 推理慢） | 高 | ① 选小模型（0.5B/1.5B）；② 限制 maxTokens；③ 异步返回，显示 "AI 思考中..." |
| fcitx5 plugin 调试困难 | 中 | 先写纯 Kotlin/JNI TestActivity，不走插件机制，验证通过后再封装为 Plugin |
| 手机存储不足（模型 1-3GB） | 低 | 提示用户清理空间，支持将模型移到 SD 卡（Android 11+ scoped storage 限制） |

---

## 10. 验收标准

1. ✅ Debug APK 在真机（arm64）上成功安装并运行
2. ✅ 打开输入法，能正常拼音输入并上屏汉字
3. ✅ 加载 GGUF 模型（Qwen2.5-0.5B）后，输入 "nihao" 能在 3 秒内返回补全候选
4. ✅ 候选栏正确显示 LLM 生成的补全词，点击后上屏
5. ✅ 设置页能切换不同模型
6. ✅ 从 HuggingFace 下载模型功能正常
7. ✅ APK 不超过 100MB（基础包），模型独立下载

---

*文档版本：v0.1（首版）*
*最后更新：2026-04-16*
