# LLMFC — 端侧大模型输入法

基于 [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) + [llama.cpp](https://github.com/ggerganov/llama.cpp) 构建的 Android 输入法，支持端侧 GGUF 大模型实时补全。

**特性**
- 100% 离线运行，无需网络
- 端侧 LLM 推理（Qwen2.5 / Phi-3 / MiniCPM / Gemma 等 GGUF 模型）
- 流式输出，实时显示补全候选
- 支持 HuggingFace 模型下载

## 项目结构

```
llmfc/
├── SPEC.md                    # 技术规格文档
├── README.md                  # 本文件
│
├── fcitx5-android/            # Forked fcitx5-android
│   ├── app/                   # 主输入法 APK
│   ├── lib/                   # 核心库
│   ├── plugin/
│   │   └── aicompose/         # ★ LLM AI 补全插件
│   │       ├── src/main/kotlin/    # Kotlin 代码
│   │       │   ├── AIComposePlugin.kt
│   │   │       ├── AIComposeEngine.kt
│   │   │       └── LlamaEngine.kt
│   │       └── src/main/cpp/       # JNI + llama.cpp
│   │           ├── LlamaEngine.cpp
│   │           └── CMakeLists.txt
│   └── ...                    # (submodules)
│
├── llama.cpp/                 # llama.cpp submodule
│
└── scripts/
    └── download_model.py      # HuggingFace 模型下载工具
```

## 快速开始

### 前置要求

- Android Studio Hedgehog (2023.1) 或更高
- NDK r25c
- Android SDK 34，minSdk 24

### 编译

```bash
# 初始化 submodules（已包含 fcitx5 核心库 + llama.cpp）
git submodule update --init --recursive

# 在 Android Studio 中打开 fcitx5-android 目录
# 或使用 Gradle 命令行构建：
cd fcitx5-android
./gradlew :plugin:aicompose:assemble

# 完整 APK 构建（主输入法 + 插件）：
./gradlew :app:assembleRelease
```

### 下载模型

APK 本身不内置模型，需要单独下载：

```bash
# 使用 Python 脚本下载 HuggingFace GGUF 模型
pip install huggingface-hub
python scripts/download_model.py Qwen/Qwen2.5-0.5B-Instruct-GGUF ./models/

# 推荐模型（手机端）：
# - Qwen/Qwen2.5-0.5B-Instruct-GGUF  (~400MB)
# - Qwen/Qwen2.5-1.5B-Instruct-GGUF  (~1GB)
# -microsoft/Phi-3-mini-4k-instruct-GGUF  (~2.3GB)
```

### 安装与使用

1. 安装 APK：`adb install app/build/outputs/apk/release/...`
2. 打开系统设置 → 语言与输入法 → 键盘 → 启用 **LLM Compose**
3. 选择一个 GGUF 模型文件
4. 在任意输入框切换到 LLM Compose 键盘，开始输入拼音

## 开发说明

### 插件架构

`plugin/aicompose` 是一个标准 fcitx5-android 插件：

- `AIComposePlugin.kt` — 插件入口，Fcitx5 生命周期管理
- `AIComposeEngine.kt` — 输入路由 + 候选列表管理
- `LlamaEngine.kt` — llama.cpp JNI 封装（Kotlin 侧）
- `LlamaEngine.cpp` — JNI 实现（ llama.cpp C++ 调用）

### JNI 签名

| Kotlin 方法 | JNI C++ 函数 |
|------------|-------------|
| `_loadModelNative(path, nCtx, nThreads)` | `Java_org_fcitx_fcitx5_android_plugin_aicompose_LlamaEngine_loadModelNative` |
| `_completeNative(prompt, maxTokens)` | `Java_org_fcitx_fcitx5_android_plugin_aicompose_LlamaEngine_completeNative` |
| `_completeStreamNative(prompt, maxTokens, callback)` | `Java_org_fcitx_fcitx5_android_plugin_aicompose_LlamaEngine_completeStreamNative` |

### 模型推理流程

```
用户输入拼音 "nihao"
        ↓
AIComposeEngine.requestCompletion("nihao")
        ↓
LlamaEngine.completeStream("请问：nihao 的中文是？", 32, onToken)
        ↓
LlamaEngine.cpp: llama.cpp streaming decode
        ↓
逐 token 回调 → Kotlin callback → 候选栏更新
```

## License

- fcitx5-android 部分：LGPL v2.1
- llama.cpp 部分：MIT
- 本项目新增代码：Apache-2.0
