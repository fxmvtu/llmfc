# LLM Compose — 端侧大模型输入法插件
## 实现报告 v1.0 | 2026-04-17

---

## 一、项目概述

**目标**：将端侧大模型（llama.cpp）接入 fcitx5-android 小企鹅输入法，以 APK 形式发布，实现输入法内的 LLM 辅助补全功能。

**技术栈**：
- Android（Kotlin + JNI/C++）
- llama.cpp（端侧推理）
- AIDL（IPC 通信）
- Gradle（Android 构建）

**GitHub**：
- 父仓库：https://github.com/fxmvtu/llmfc
- 子模块：https://github.com/fxmvtu/llmfc （fcitx5-android 分支）
- 最新 Release：`a2b094db`

---

## 二、已实现功能

### 2.1 插件核心架构

| 模块 | 文件路径 | 职责 |
|---|---|---|
| `MainService` | `plugin/aicompose/src/main/kotlin/.../MainService.kt` | 插件主入口，继承 `FcitxPluginService`，生命周期管理 |
| `AIComposeEngine` | `plugin/aicompose/src/main/kotlin/.../AIComposeEngine.kt` | 推理引擎封装，缓存管理，协程调度 |
| `LlamaEngine.kt` | `plugin/aicompose/src/main/kotlin/.../LlamaEngine.kt` | Kotlin 侧 JNI 封装，暴露 loadModel / unloadModel / requestCompletion / cancel |
| `LlamaEngine.cpp` | `plugin/aicompose/src/main/cpp/LlamaEngine.cpp` | C++ JNI 实现，llama.cpp 调用，流式 callback，线程安全 |
| `IInputSuggestions.aidl` | `lib/common/src/main/aidl/.../IInputSuggestions.aidl` | 双向 AIDL 接口定义 |

#### MainService 职责（192 行）
- 继承 `FcitxPluginService`，实现 `onCreate()` / `start()` / `stop()` 生命周期
- 维护 `IInputSuggestions.Stub` 实现，提供 `isReady()` / `onPreeditChanged()` / `getSuggestions()`
- 缓存管理：`cachedSuggestions` + `lastPinyin`，volatile 读写
- 模型热加载/卸载：调用 `AIComposeEngine` 静态方法
- 单例模式：静态 `loadModelStatic()` / `unloadModelStatic()` 等供 Settings Activity 调用

#### AIComposeEngine 职责（137 行）
- `requestCompletion(prompt: String)` — 启动异步推理，CompletableJob 可取消
- `getSuggestions(pinyin: String?, limit: Int)` — 返回缓存结果，pinyin 前缀匹配 fallback
- `cancel()` — 取消当前推理
- `normalizePinyin()` — 拼音归一化：去声调、ü→v→u 转换
- 同步块仅保护 buffer 写入，结果计算在锁外执行

#### LlamaEngine.cpp（293 行）
- `load_model / unload_model` — 模型加载/卸载，std::mutex 全局锁
- `complete_stream` — 流式推理主循环，逐 token `llama_decode`，JNI callback 推送结果
- `_cancel_native` — 设置 s_cancelled 标志中断推理
- **线程安全**：所有全局状态（s_model_loaded / s_ctx / s_cancelled）被 mutex 保护
- **use-after-free 修复**：JNI callback 重新获取锁后立即检查 `s_model_loaded`，避免空指针 decode

---

### 2.2 LLM 推理能力

- **端侧推理**：llama.cpp 静态编译，不依赖网络
- **流式生成**：逐 token 返回，通过 JNI `CallVoidMethod` 回调 Kotlin
- **可配置参数**：
  - `nThreads`（推理线程数，1–8，默认 4）
  - `nCtx`（Context 长度，512–4096，默认 2048）
- **热加载/卸载**：运行时切换模型，无需重启输入法
- **推理取消**：取消正在进行的长生成
- **GGUF 模型支持**：HuggingFace 下载，支持 Qwen2 / Phi / LLaMA 等主流格式

---

### 2.3 输入法集成

| 功能 | 实现方式 |
|---|---|
| **候选词注入** | `CandidatesView.injectAiSuggestions()` 将 LLM 结果插入 PagedCandidateList，idx=-1（LLM 专属分类），⚡ 前缀标识 |
| **实时触发** | `FcitxInputMethodService` 监听 `InputPanelEvent`，preedit 变化时触发 `requestAiSuggestions()` |
| **两阶段缓存** | Phase 1: `onPreeditChanged(pinyin)` 异步触发推理并缓存；Phase 2: `getSuggestions()` 同步返回缓存结果 |
| **负载保护** | 相同拼音跳过重复推理；推理在 serviceScope 独立协程；模型卸载清空缓存 |
| **线程安全** | C++ 层 std::mutex + Kotlin 层 synchronized 块 |

---

### 2.4 Settings UI

- **LAUNCHER 入口**：作为独立 App 图标出现在手机桌面（`MAIN/LAUNCHER` intent-filter）
- 模型下载：从 HuggingFace 输入模型名并下载到 `/sdcard/Android/data/org.fcitx.fcitx5.android/files/models/`
- 模型加载/卸载按钮
- 推理线程数 Slider（1–8）
- Context 长度 Slider（512–4096）
- LLM 补全启用/禁用 Switch
- 下载进度条 + Toast 状态反馈

---

### 2.5 AIDL 通信接口

```aidl
// IInputSuggestions.aidl
oneway void onPreeditChanged(String pinyin);  // 异步预热缓存
String[] getSuggestions(String? pinyin, Int limit);  // 同步返回缓存
boolean isReady();  // 模型是否已加载
```

```aidl
// IFcitxRemoteService.aidl 新增方法
void registerInputSuggestions(IInputSuggestions suggestions);
void unregisterInputSuggestions(IInputSuggestions suggestions);
```

---

## 三、代码质量（Audit 修复记录）

共修复 12 轮审计问题（v1–v12），关键修复：

| 编号 | 问题 | 修复方案 |
|---|---|---|
| JNI 线程安全 | Kotlin lambda 在 JNI callback 中不稳定 | 改用 jmethodID + jobject 静态 Stub |
| use-after-free | 推理中途 unloadModel 导致 s_ctx=nullptr 被 decode | JNI callback 重新获取锁后立即检查 s_model_loaded |
| 线程安全 | 多个 llama API 调用无全局锁 | std::mutex 保护所有全局状态 |
| Prompt 注入 | 直接拼接 {{pinyin }} | 删除了有注入风险的完整 Prompt 模板 |
| 候选词去重 | 同长度截断后重复 | `distinctBy { it }` 去重 + 长度截断 |
| 冗余推理 | 相同拼音重复触发 | `if (pinyin == lastPinyin) return` 跳过 |
| 缓存清理 | 模型卸载后缓存残留 | unloadModel 时清空 cachedSuggestions + lastPinyin |
| C++ 死代码 | getEnv() 未被调用、completeNative 废弃 | 已删除 |
| AIDL 语义 | onPreeditChanged fire-and-forget 未注明 | 添加 Javadoc 说明 oneway 语义 |
| Settings 暴露 | Activity exported=false 无入口 | 添加 MAIN/LAUNCHER intent-filter |

---

## 四、APK 发布信息

| 项目 | 信息 |
|---|---|
| 最新 Release | `a2b094db` |
| Release 页面 | https://github.com/fxmvtu/llmfc/releases/tag/a2b094db |
| 包名 | `org.fcitx.fcitx5.android` |
| 支持架构 | arm64-v8a / armeabi-v7a / x86_64 / x86 |

**下载地址**：
- [arm64-v8a](https://github.com/fxmvtu/llmfc/releases/download/a2b094db/org.fcitx.fcitx5.android-a2b094db-arm64-v8a-release.apk)
- [armeabi-v7a](https://github.com/fxmvtu/llmfc/releases/download/a2b094db/org.fcitx.fcitx5.android-a2b094db-armeabi-v7a-release.apk)
- [x86_64](https://github.com/fxmvtu/llmfc/releases/download/a2b094db/org.fcitx.fcitx5.android-a2b094db-x86_64-release.apk)
- [x86](https://github.com/fxmvtu/llmfc/releases/download/a2b094db/org.fcitx.fcitx5.android-a2b094db-x86-release.apk)

**安装注意**：需先卸载手机上的官方 fcitx5（签名不同会冲突），再安装本 APK。

---

## 五、技术架构图

```
┌─────────────────────────────────────────────────────────┐
│  手机桌面 / 应用列表                                      │
│  ┌──────────────────┐    ┌──────────────────────────┐  │
│  │ 小企鹅输入法 (主)   │    │ LLM Compose (Settings)  │  │
│  └────────┬─────────┘    └──────────┬─────────────┘  │
└────────────┼──────────────────────────┼────────────────┘
             │                          │
             │bindService               │独立启动
             ▼                          ▼
┌────────────────────────────────────────────────────────┐
│  fcitx5-android 主应用 (org.fcitx.fcitx5.android)       │
│  ┌─────────────────────┐  ┌─────────────────────────┐  │
│  │ FcitxInputMethodService│  │ FcitxRemoteService     │  │
│  │  • handleFcitxEvent  │  │  • registerSuggestions │  │
│  │  • requestAiSuggestions│ │  • unregisterSuggestions│  │
│  └──────────┬────────────┘  └──────────┬────────────┘  │
│             │                             │              │
│             │         AIDL IPC            │              │
└────────────┼─────────────────────────────┼──────────────┘
              │                             │
              │                    ┌──────────▼──────────┐
              │                    │  aicompose 插件     │
              │                    │  (MainService)     │
              │                    │  • IInputSuggestions│
              │                    │  • AIComposeEngine  │
              │                    │  • LlamaEngine(JNI) │
              │                    └──────────┬──────────┘
              │                               │ JNI
              │                               ▼
              │                    ┌────────────────────┐
              │                    │ llama.cpp (C++)     │
              │                    │  • load_model      │
              │                    │  • complete_stream │
              │                    │  • _cancel_native  │
              │                    └────────────────────┘
              ▼
┌────────────────────────────────────────────────────────┐
│  候选词视图 CandidatesView                              │
│  ┌──────────────────────────────────────────────────┐  │
│  │  [候选1] [候选2] [候选3] ⚡AI补全1 ⚡AI补全2      │  │
│  └──────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────┘
```

---

## 六、待完成功能

| 优先级 | 功能 | 说明 |
|---|---|---|
| P0 | LLM 候选词视觉区分 | ⚡ 前缀已实现，但无背景色/图标区分 |
| P0 | Vulkan / GPU 加速 | llama.cpp Vulkan backend 在 Android 上的配置 |
| P1 | 性能测试 | token/s 吞吐量测试，验证不同模型效果 |
| P1 | RAG Pipeline | 检索增强生成，定制输入法领域知识 |
| P2 | 单元测试 | AIComposeEngine / LlamaEngine 边界条件测试 |
| P2 | 多模型管理 UI | 支持切换不同 GGUF 模型 |

---

## 七、项目结构

```
llmfc/                          # 父仓库（Git submodule superproject）
├── AGENT.md                    # 项目状态文档
├── REVIEW_REPORT.md           # 审计报告
├── llama.cpp/                 # 端侧推理引擎（子模块）
└── fcitx5-android/            # 主仓库（ submodule at commit a2b094db）
    ├── app/                    # fcitx5-android 主应用
    ├── lib/
    │   ├── common/             # 公共 IPC/AIDL 接口
    │   │   └── aidl/org/fcitx/fcitx5/android/common/ipc/
    │   │       ├── IInputSuggestions.aidl  ★ 新增
    │   │       └── IFcitxRemoteService.aidl  ★ 修改
    │   └── ...
    └── plugin/
        └── aicompose/         # LLM Compose 插件 ★ 核心开发
            └── src/main/
                ├── cpp/
                │   ├── LlamaEngine.cpp       # JNI C++ 实现
                │   └── CMakeLists.txt         # llama.cpp 链接配置
                ├── kotlin/org/fcitx/fcitx5/android/plugin/aicompose/
                │   ├── MainService.kt         # 插件主入口
                │   ├── AIComposeEngine.kt     # 推理引擎封装
                │   ├── LlamaEngine.kt          # JNI Kotlin 封装
                │   └── ui/
                │       └── AIComposeSettingsActivity.kt  ★ 新增
                ├── res/
                │   ├── layout/
                │   │   ├── activity_ai_compose_settings.xml  ★ 新增
                │   │   └── item_model.xml  ★ 新增
                │   ├── values/strings.xml   ★ 新增 strings
                │   └── xml/plugin.xml       # 插件描述文件
                └── AndroidManifest.xml      ★ 修改
```

---

*报告生成时间：2026-04-17*
*最后更新 commit：a2b094db*
