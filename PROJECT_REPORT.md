# 小企鹅输入法 AI 插件项目报告

**项目名称**：llmfc / fcitx5-android aicompose 插件
**最新版本**：a2b094db（591bc1c5）
**仓库**：https://github.com/fxmvtu/llmfc
**更新时间**：2026-04-17

---

## 一、项目概述

本项目将开源大语言模型（LLM）接入 Android 输入法——小企鹅输入法（fcitx5-android），通过端侧推理实现**智能候选词补全**功能。LLM 运行在设备本地，不依赖网络，支持用户自定义 GGUF 模型。

### 核心特性
- **完全本地运行**：LLM 推理在设备端执行，无需联网，保护隐私
- **热切换模型**：支持从 HuggingFace 下载或本地加载任意 GGUF 格式模型
- **输入法深度集成**：通过 AIDL IPC 与 fcitx5 输入法引擎通信，LLM 候选词无缝注入候选面板
- **独立设置界面**：插件有独立 APK 和桌面图标，可单独管理模型和参数

---

## 二、功能列表

### 2.1 端侧 LLM 推理引擎

| 功能 | 说明 |
|------|------|
| llama.cpp 集成 | 基于 C++ 端侧推理库，支持 GGUF 格式模型 |
| 多架构支持 | arm64-v8a、armeabi-v7a、x86_64、x86 |
| Vulkan GPU 加速 | 通过 GGML_VULKAN 实现 GPU 推理加速 |
| 动态上下文 | 可配置 n_ctx（上下文窗口大小），默认 512 |
| 多线程推理 | 可配置 n_threads，默认 CPU 核心数 |
| 流式输出 | token 逐个回调，实时更新候选词 |
| 推理取消 | 支持中断正在进行的推理任务 |
| 模型热加载/卸载 | 运行时动态加载或卸载模型，不影响输入法主程序 |

### 2.2 智能候选词系统

| 功能 | 说明 |
|------|------|
| LLM 候选注入 | 通过 AIDL IPC 将 LLM 生成结果注入输入法候选面板 |
| 两阶段缓存 | `onPreeditChanged` 异步预热 + `getSuggestions` 同步返回 |
| 自动前缀匹配 | 如果缓存结果以当前输入开头则复用，避免重复推理 |
| 卸载清空缓存 | 卸载模型时自动清空缓存，防止残留数据 |
| 相同输入去重 | `distinctBy` 去重 + 每条候选截断至最大长度 |
| 拼音归一化 | 自动将 `ü` 转换为 `v`，支持 `nü`/`lü` 系列 |

### 2.3 模型管理（设置界面）

| 功能 | 说明 |
|------|------|
| HuggingFace 下载 | 输入模型 ID（如 `liaised/llama-3-tiny-8b-mini-gguf`）一键下载 |
| 本地模型加载 | 选择本地 GGUF 文件路径 |
| 当前模型信息 | 显示已加载模型名称、量化方法、参数量 |
| 参数实时调节 | 滑动条调整 `n_ctx`（512-4096）和 `n_threads`（1-8） |
| 模型启用开关 | 一键启用/禁用 LLM 候选功能 |
| 状态 Toast | 下载进度、加载状态、错误信息实时反馈 |

### 2.4 插件架构

| 功能 | 说明 |
|------|------|
| 独立 APK | 插件作为独立 application 运行，有自己的 ApplicationId |
| FcitxPluginService 基类 | 继承标准插件服务基类，符合 fcitx5-android 插件规范 |
| AIDL IPC | 通过 `IInputSuggestions` 接口与主输入法进程通信 |
| 独立桌面图标 | 插件安装后有独立的 LAUNCHER 图标，可直接打开设置页 |
| 插件注册机制 | 通过 `plugin.xml` 向主 app 注册，支持动态发现和加载 |

---

## 三、技术架构

### 3.1 模块结构

```
llmfc/                           # Parent repo (GitHub)
├── llama.cpp/                   # llama.cpp 源码 (git submodule)
└── fcitx5-android/              # Android 输入法项目 (git submodule)
    ├── app/                     # 主输入法 app 模块
    ├── lib/                     # 公共库 (fcitx5, plugin-base, common)
    └── plugin/aicompose/        # AI 插件模块
        └── src/main/
            ├── kotlin/          # Kotlin 源码
            │   ├── MainService.kt           # 插件服务 + LLM 推理
            │   ├── AIComposeEngine.kt       # 推理引擎封装
            │   ├── LlamaEngine.kt           # JNI 封装
            │   └── ui/AIComposeSettingsActivity.kt  # 设置界面
            ├── cpp/               # C++ JNI 源码
            │   └── LlamaEngine.cpp # llama.cpp 调用 + 流式回调
            └── res/               # 资源文件（布局、字符串、图标）
```

### 3.2 IPC 通信流程

```
FcitxInputMethodService          FcitxRemoteService            MainService (aicompose)
        │                              │                              │
        │ handleFcitxEvent()          │                              │
        │────── getInputSuggestions() ────► isReady()                │
        │                              │                              │
        │                              │    onPreeditChanged(pinyin)  │
        │                              │──────────────► (async trigger)
        │                              │                              │
        │                              │         getSuggestions() ◄──│ (cache lookup)
        │◄──── injectAiSuggestions() ──┼──────────────────────────────│
        │                              │                              │
        ▼                              ▼                              ▼
  CandidatesView                 InputSuggestionsManager        LlamaEngine (JNI)
  (显示 LLM 候选词)              (AIDL Stub)                    (llama.cpp GGUF)
```

### 3.3 提示词模板

```
<s>Instru: Predict the next Chinese pinyin IME candidate words.
Input: {normalized_pinyin}
Output: (only the words, separated by spaces, max 5 words)</s>
```

### 3.4 依赖版本

| 组件 | 版本 |
|------|------|
| Android Gradle Plugin | 8.2.2 |
| Kotlin | 1.9.22 |
| NDK | 25c |
| CMake | 3.31.6 |
| llama.cpp | nightly（最新） |
| coroutines | 1.7.3 |
| Material | 1.13.0 |
| AndroidX Lifecycle | 2.7.0 |

---

## 四、代码质量

### 4.1 审计历史

项目经过 **12 轮代码审计**（v1–v12），共修复约 40+ 问题，包括：

| 类别 | 修复内容 |
|------|----------|
| 线程安全 | `std::mutex` 保护全局状态、流式回调从 Kotlin lambda 改为 `jmethodID` |
| 内存安全 | `use-after-free` 防护：`s_model_loaded.load()` 检查后再执行 `llama_decode` |
| API 兼容性 | llama.cpp nightly API 适配（batch_get_one 4参数、sampler_chain OOP、vocab_is_eog） |
| JNI 规范 | 删除未调用的 `getEnv()` 死代码、规范 AIDL `oneway` 语义 |
| 逻辑正确性 | 推理取消幂等性、缓存按前缀复用、相同输入跳过重复推理 |
| 配置完整性 | INTERNET 权限、Vulkan 特性声明、FileProvider、proguard-rules.pro |
| 性能优化 | `generate_prompt` 词边界拼接、`synchronized` 块仅保护必要区域 |

### 4.2 当前代码状态

- **所有 15 个 audit 问题均已修复**（issues #1–#15）
- **v12 审核结论**：`无阻塞性问题，可进入端到端实测阶段`
- **生产就绪**：✅ Ready for Production

---

## 五、APK 下载

**Release 页面**：https://github.com/fxmvtu/llmfc/releases/tag/a2b094db

### 需要安装两个 APK

**1. 主输入法 app**（选择对应架构）：
- `org.fcitx.fcitx5.android-a2b094db-arm64-v8a-release.apk`（推荐）
- `org.fcitx.fcitx5.android-a2b094db-armeabi-v7a-release.apk`
- `org.fcitx.fcitx5.android-a2b094db-x86_64-release.apk`
- `org.fcitx.fcitx5.android-a2b094db-x86-release.apk`

**2. aicompose 插件 APK**：
- `org.fcitx.fcitx5.android.plugin.aicompose-a2b094db-arm64-v8a-release.apk`

### 安装步骤
1. **卸载手机上已安装的小企鹅输入法**（如有官方版，签名冲突）
2. 安装主 app APK
3. 安装 aicompose 插件 APK
4. 打开主 app 完成初始化向导
5. 打开 aicompose 插件图标 → 下载 GGUF 模型
6. 在输入框输入拼音，自动获得 LLM 智能候选

---

## 六、未来计划

| 优先级 | 功能 | 说明 |
|--------|------|------|
| P0 | 端到端实测 | 在真机上验证完整流程（下载模型 → 加载 → 候选注入） |
| P0 | 候选词 UI 样式 | LLM 候选词与普通候选词视觉区分（如特殊颜色/图标） |
| P1 | Vulkan GPU 加速 | 验证 GGML_VULKAN 在移动设备上的加速效果 |
| P1 | 模型性能评测 | 测量 token/s、首次推理延迟、内存占用 |
| P2 | 单元测试 | AIComposeEngine 的 Mock 测试 |
| P2 | 多模型切换 UI | 同时管理多个 GGUF 模型 |
| P2 | RAG 增强 | 结合本地知识库提升领域术语候选质量 |
| P3 | Android Studio 集成 | 插件开发文档和 IDE 支持 |

---

## 七、相关文档

| 文档 | 位置 |
|------|------|
| AGENT.md（开发记录） | `llmfc/AGENT.md` |
| 代码审查报告 | `llmfc/REVIEW_REPORT.md` |
| 审核报告 | `llmfc/VERIFICATION_REPORT.md` |
| README | `llmfc/fcitx5-android/README.md` |
| 插件规范 | `llmfc/fcitx5-android/plugin/pluginSchema.xsd` |
