# AGENT.md — LLM Compose for fcitx5-android

## 项目状态：✅ Production Ready（审查通过）

**最新 submodule commit**：`a2b094db`（Settings LAUNCHER）
**父仓库 main**：`b263155`（IMPLEMENTATION_REPORT.md + AGENT.md）
**最新 Release**：https://github.com/fxmvtu/llmfc/releases/tag/a2b094db

---

## 审查结论（2026-04-17 萤萤·审查员）

> 核心功能完整，端到端链路已实现且达到 production-ready 标准。
> P0 项为体验优化，不阻塞发布。RAG Pipeline 暂不需要。

详见：`VERIFICATION_REPORT.md`（审查员提供）

---

## 架构概览

```
手机桌面
├── 小企鹅输入法（主 APK）—— FcitxInputMethodService
│   └── InputPanelEvent → requestAiSuggestions()
│       ├── suggestions.onPreeditChanged(pinyin) [oneway, 异步缓存]
│       └── suggestions.getSuggestions(pinyin, 5) [同步返回]
│           └── candidatesView.injectSuggestions()
└── LLM Compose Settings（独立图标）
    └── HuggingFace 下载 / 模型管理 / 参数配置

插件进程（MainService）
├── IInputSuggestions.Stub（isReady / onPreeditChanged / getSuggestions）
├── AIComposeEngine（缓存 + 协程调度）
├── LlamaEngine.kt（JNILoadModel / requestCompletion / cancel）
└── LlamaEngine.cpp（llama.cpp 流式推理，mutex 线程安全）
```

---

## 核心文件

| 文件 | 说明 |
|------|------|
| `plugin/aicompose/src/main/kotlin/.../MainService.kt` | 插件主入口，IInputSuggestions Stub，生命周期 |
| `plugin/aicompose/src/main/kotlin/.../AIComposeEngine.kt` | 推理引擎封装，缓存管理，协程调度 |
| `plugin/aicompose/src/main/kotlin/.../LlamaEngine.kt` | Kotlin JNI 封装 |
| `plugin/aicompose/src/main/cpp/LlamaEngine.cpp` | C++ JNI 实现，llama.cpp 调用 |
| `plugin/aicompose/src/main/kotlin/.../ui/AIComposeSettingsActivity.kt` | Settings UI |
| `lib/common/.../aidl/.../IInputSuggestions.aidl` | AIDL 接口 |
| `lib/common/.../aidl/.../IFcitxRemoteService.aidl` | 新增 register/unregisterInputSuggestions |

---

## 构建 & 发布

```bash
# 插件构建
cd /home/kokoro/workspace/llmfc/fcitx5-android
JAVA_HOME=/home/kokoro/java/jdk-21.0.6+7 ANDROID_HOME=/home/kokoro/android-sdk \
  ./gradlew :plugin:aicompose:assembleDebug --no-daemon

# 全量 APK 构建
JAVA_HOME=/home/kokoro/java/jdk-21.0.6+7 ANDROID_HOME=/home/kokoro/android-sdk \
  ./gradlew :app:assembleRelease --no-daemon

# 签名（4 个架构）
KEYSTORE=~/.android/debug.keystore
APKSIGNER=/home/kokoro/android-sdk/build-tools/34.0.0/lib/apksigner.jar
for arch in arm64-v8a armeabi-v7a x86_64 x86; do
  unsigned=".../aicompose-$arch-release-unsigned.apk"
  signed=".../aicompose-$arch-release.apk"
  "$JAVA_HOME/bin/java" -jar $APKSIGNER sign --ks $KEYSTORE \
    --ks-pass pass:android --ks-key-alias androiddebugkey \
    --key-pass pass:android --out $signed $unsigned
done

# Git push
git add fcitx5-android && git commit -m "..." && git push origin main
```

---

## 待完成功能

| 优先级 | 功能 | 说明 |
|--------|------|------|
| **P0** | LLM 候选词视觉区分 | 添加背景色或图标，与普通候选词区分 |
| **P0** | Vulkan GPU 加速验证 | 真机测试 token/s 对比 CPU baseline |
| P1 | token/s 吞吐量测试 | 在 completeStream 入口/出口加时间戳日志 |
| P2 | 单元测试 | AIComposeEngine / LlamaEngine 边界条件 |
| P2 | 多模型管理 UI | 切换不同 GGUF 模型 |

---

## 技术笔记

- NDK 28 的 `libdl.a` 会损坏 → 用 `sdkmanager` 重装即可
- CMake 3.31.6 必须装到 `/home/kokoro/android-sdk/cmake/3.31.6/`
- llama.cpp 路径：plugin/aicompose/src/main/cpp/ → llmfc/llama.cpp 需 6 层 `../../../../../..`
- `LlamaEngine.cpp` JNI callback 改用 jmethodID 而非 Kotlin lambda
- `std::mutex` 保护所有 llama 全局状态（s_model_loaded / s_ctx / s_cancelled）
- APK 安装前需卸载手机官方 fcitx5（签名不同）

## GitHub Token
`ghp_XXXXX_REDACTED_XXXXX`（PAT classic，repo scope）
