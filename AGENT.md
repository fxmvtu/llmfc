# AGENT.md — LLMFC 项目执行指南

> 项目：LLMFC — 端侧大模型接入小企鹅输入法（fcitx5-android + llama.cpp）
> 仓库：`github.com/fxmvtu/llmfc`
> 分支：main
> Token：`${GITHUB_TOKEN}`（通过环境变量传入，勿硬编码）

---

## 1. 项目现状总览

### 1.1 已完成的部分

| 组件 | 状态 | 说明 |
|------|------|------|
| `aicompose` 插件骨架 | ✅ 完成 | Kotlin + C++ JNI 源码完整 |
| `LlamaEngine.kt` | ✅ 完成 | JNI 封装、模型加载/推理接口 |
| `AIComposeEngine.kt` | ✅ 完成 | 拼音→LLM 路由、流式推理编排 |
| `AIComposePlugin.kt` | ✅ 完成 | 插件入口、生命周期管理 |
| `LlamaEngine.cpp` | ✅ 完成 | llama.cpp JNI 实现（load/unload/complete/stream） |
| `CMakeLists.txt` | ✅ 完成 | llama.cpp 静态编译为 `libaicompose.so` |
| `build.gradle.kts` | ✅ 完成 | NDK 构建配置、多 ABI 支持 |
| `plugin.xml` | ✅ 完成 | 插件注册（入口：AIComposePlugin） |
| `strings.xml` | ✅ 完成 | 中英文 UI 字符串 |
| `download_model.py` | ✅ 完成 | HuggingFace GGUF 模型下载脚本 |
| `SPEC.md` | ✅ 完成 | 技术规格文档（v0.1） |
| `README.md` | ✅ 完成 | 用户级说明文档 |
| aicompose Debug APK 构建产物 | ✅ 存在 | `plugin/aicompose/build/intermediates/dex/.../classes.dex` |

### 1.2 未完成的部分（TODO）

| 任务 | 优先级 | 说明 |
|------|--------|------|
| **端到端集成** — 插件与输入法主 app 连接 | P0 | AIComposePlugin 尚未真正接入 FcitxInputMethodService |
| **候选栏 UI** — LLM 补全结果上屏 | P0 | 只有数据流，缺 UI 渲染 |
| **模型下载 UI** — 设置页内下载管理 | P1 | `download_model.py` 有，但无 Android 侧 UI |
| **PluginData.kt** | P1 | `AIComposePlugin.kt` 引用了 `data.PluginData`，build 产物有 DEX 但源码缺失 |
| **主 APK 构建** | P0 | 从未构建过完整 `:app:assembleDebug` |
| **真机测试** | P0 | 从未在真机上验证 |
| **Vulkan/GPU 加速** | P2 | `GGML_NO_VULKAN=OFF` 配置了但 Android 上 Vulkan 支持需要额外处理 |
| **多模型管理 UI** | P2 | 模型切换界面 |

---

## 2. 目录结构

```
llmfc/                              # Git 根目录
├── AGENT.md                        # 本文件
├── SPEC.md                         # 技术规格（设计文档）
├── README.md                       # 用户文档
├── .gitmodules                    # submodule 声明
│
├── fcitx5-android/                 # fork 自 fcitx5-android/fcitx5-android
│   ├── app/                        # 主输入法 APK 模块
│   ├── lib/                        # 核心库（fcitx5, plugin-base, common 等）
│   ├── plugin/
│   │   └── aicompose/              # ★ LLM 补全插件（核心工作区）
│   │       ├── build.gradle.kts
│   │       └── src/main/
│   │           ├── AndroidManifest.xml
│   │           ├── kotlin/org/fcitx/fcitx5/android/plugin/aicompose/
│   │           │   ├── AIComposePlugin.kt    # 插件入口（单例）
│   │           │   ├── AIComposeEngine.kt    # 推理编排
│   │           │   └── LlamaEngine.kt         # JNI 封装
│   │           ├── cpp/
│   │           │   ├── LlamaEngine.cpp       # JNI 实现
│   │           │   └── CMakeLists.txt
│   │           ├── res/
│   │           │   ├── values/strings.xml
│   │           │   └── xml/plugin.xml
│   │           └── assets/                  # 可选：内置模型
│   │
│   └── modules/                    # 输入法引擎模块（rime, pinyin 等参考）
│
├── llama.cpp/                      # git submodule — llama.cpp 官方仓库
│
└── scripts/
    └── download_model.py           # HuggingFace GGUF 模型下载
```

---

## 3. 构建命令速查

```bash
# 初始化 submodules（已配置，需确认存在）
git submodule update --init --recursive

# 单独构建 aicompose 插件
cd /home/kokoro/workspace/llmfc/fcitx5-android
./gradlew :plugin:aicompose:assembleDebug

# 构建完整输入法 APK（含 aicompose 插件）
./gradlew :app:assembleDebug

# 清理
./gradlew clean

# 查看所有可用任务
./gradlew tasks --all | grep -E "(assemble|build|plugin|aicompose)"
```

**构建产物位置：**
- 插件 AAR：`fcitx5-android/plugin/aicompose/build/outputs/aar/`
- 插件 DEX：`fcitx5-android/plugin/aicompose/build/intermediates/dex/`
- 主 APK：`fcitx5-android/app/build/outputs/apk/debug/`

---

## 4. 核心代码解析

### 4.1 LlamaEngine.cpp — JNI 函数签名（必须一一对应）

```
Java_org_fcitx_fcitx5_android_plugin_aicompose_LlamaEngine_loadModelNative
Java_org_fcitx_fcitx5_android_plugin_aicompose_LlamaEngine_unloadModelNative
Java_org_fcitx_fcitx5_android_plugin_aicompose_LlamaEngine_completeNative
Java_org_fcitx_fcitx5_android_plugin_aicompose_LlamaEngine_completeStreamNative
```

**包路径**：`org.fcitx.fcitx5.android.plugin.aicompose`
**So 库名**：`libaicompose.so`（System.loadLibrary("aicompose")）

### 4.2 llama.cpp 版本

 submodule 指向 `ggerganov/llama.cpp`，`LlamaEngine.cpp` 使用的是当前 llama.cpp API（`llama_sampler_chain_*` 系列，v0.1+）。

### 4.3 推理流程

```
用户输入拼音 "nihao"
  → AIComposeEngine.requestCompletion("nihao")
    → normalizePinyin("nihao") → "nihao"
    → LlamaEngine.completeStream("### Instruction:\nConvert pinyin to Chinese...\nPinyin: nihao\n\nChinese:", 32, callback)
      → LlamaEngine.cpp: llama_decode() + llama_sampler_sample() 循环
        → 逐 token 通过 JNI callback 回调
      → AIComposeEngine 收集 token 拼接为完整中文
    → callback(candidates) → 候选列表
```

### 4.4 prompt 模板

`LlamaEngine.cpp` 中 `generate_prompt()` 生成格式：
```
### Instruction:
Convert the following pinyin (without tones) to Chinese characters.
Only output the Chinese characters, nothing else.

Pinyin: {input}

Chinese:
```
**这意味着需要 instruct-tuned 模型（如 Qwen2.5-Instruct）才能正常工作。**

---

## 5. 已知问题与卡点

### 5.1 PluginData 源码缺失
`AIComposePlugin.kt` 第 5 行引用 `import ...data.PluginData`，但 `src/main/kotlin/.../data/` 目录下没有 `PluginData.kt` 文件。
- **可能原因**：该类由 build-logic 在编译时生成，或由 `plugin-base` 库提供。
- **验证方式**：查看 `lib/plugin-base` 的源码，看是否有 `PluginData` 类导出。
- **如果 build 已成功**：说明 runtime 没问题，可暂时忽略。

### 5.2 插件与主 app 集成
`AIComposePlugin` 尚未被 `FcitxInputMethodService` 调用。需要在输入法主 service 中：
1. 加载 `aicompose` 插件
2. 获取 `AIComposePlugin` 单例
3. 在用户拼音输入时调用 `engine.requestCompletion()`

### 5.3 模型下载 UI 缺失
`download_model.py` 可独立运行，但 Android 端无 HuggingFace 下载 UI。用户需自行下载 GGUF 文件放入 `filesDir/models/`。

### 5.4 Vulkan 支持
`GGML_NO_VULKAN=OFF` 意味着尝试使用 Vulkan GPU 加速。在 Android 上这需要：
- `android:hardwareAccelerated="true"`
- Vulkan driver 支持（大部分现代 Android 设备支持）
- 如遇崩溃可改为 `GGML_NO_VULKAN=ON` 降级为 CPU 推理

---

## 6. 下一步行动清单

### Phase A：验证构建（立即可做）
1. 运行 `./gradlew :plugin:aicompose:assembleDebug` — 确认插件编译通过
2. 运行 `./gradlew :app:assembleDebug` — 构建完整输入法 APK
3. 用 `adb install` 安装到真机

### Phase B：端到端集成（核心）
1. 找到 `FcitxInputMethodService.kt`，分析如何加载/调用插件
2. 在拼音输入流程中插入 `AIComposeEngine.requestCompletion()`
3. 将 LLM 候选词渲染到候选栏

### Phase C：模型下载 UI（可选）
1. 参考 `plugin-base` 的设置页写法，给 aicompose 添加设置 Activity
2. 集成 HuggingFace Hub SDK 或调用 `download_model.py`

### Phase D：测试验证
1. 下载 Qwen2.5-0.5B GGUF 模型放入 `filesDir/models/`
2. 真机输入 "nihao" → 验证 3 秒内返回补全

---

## 7. Git 工作流

```bash
# 确认 token 权限
gh auth status

# 查看当前分支
git -C /home/kokoro/workspace/llmfc branch

# 提交当前进度
git -C /home/kokoro/workspace/llmfc add .
git -C /home/kokoro/workspace/llmfc commit -m "docs: add AGENT.md"

# 推送到 GitHub（token 从环境变量 GITHUB_TOKEN 读取）
git -C /home/kokoro/workspace/llmfc push https://${GITHUB_TOKEN}@github.com/fxmvtu/llmfc.git

# 设置 remote 自动认证（可选）
git -C /home/kokoro/workspace/llmfc remote set-url origin https://${GITHUB_TOKEN}@github.com/fxmvtu/llmfc.git
```

---

*本文档版本：v1.0*
*最后更新：2026-04-16*
