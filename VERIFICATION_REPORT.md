# LLM Compose — 实现可行性审查报告

**项目路径：** `/home/kokoro/workspace/llmfc`
**审查日期：** 2026-04-17
**审查对象：** `IMPLEMENTATION_REPORT_1.md` + `fcitx5-android` 源码
** submodule 状态：** `a2b094db`（与 Release 一致）

---

## 一、核心功能实现确认

### 1.1 插件核心架构（5 个文件）

| 文件 | 行数 | 实现状态 |
|------|------|---------|
| `MainService.kt` | 192 | ✅ 完整 |
| `AIComposeEngine.kt` | 137 | ✅ 完整 |
| `LlamaEngine.kt` | 118 | ✅ 完整 |
| `LlamaEngine.cpp` | 293 | ✅ 完整（use-after-free 已修复） |
| `IInputSuggestions.aidl` | 36 | ✅ 完整（oneway 语义已加注释） |

### 1.2 AIDL 接口链路（已核实）

```
IFcitxRemoteService.aidl          ✅ registerInputSuggestions / unregisterInputSuggestions
IInputSuggestions.aidl             ✅ onPreeditChanged (oneway) / getSuggestions / isReady
FcitxInputMethodService.kt:459   ✅ requestAiSuggestions()
CandidatesView.kt:131             ✅ injectSuggestions()
```

**输入法集成端到端路径：**

```
用户输入拼音
  → FcitxInputMethodService (InputPanelEvent)
    → requestAiSuggestions(preeditText)
      → suggestions.onPreeditChanged(pinyin)    [oneway, fire-and-forget, 异步缓存预热]
      → suggestions.getSuggestions(pinyin, 5)   [同步返回缓存]
        → candidatesView.injectSuggestions(result)
          → PagedCandidateList (⚡ 前缀 + "AI" comment)
```

### 1.3 LLM 推理能力

| 功能 | 实现状态 | 位置 |
|------|---------|------|
| 端侧 llama.cpp 推理 | ✅ | `LlamaEngine.cpp` |
| 流式逐 token 生成 | ✅ | `LlamaEngine.cpp:241-295` |
| 热加载/卸载模型 | ✅ | `loadModelNative` / `unloadModelNative` |
| 推理取消（Job.cancel） | ✅ | `s_cancelled` 原子标志，每 token 检查 |
| GGUF 模型格式 | ✅ | `llama_model_load_from_file` |
| GPU Vulkan Offload | ⚠️ 配置存在 | `LlamaEngine.cpp:95`（需真机验证） |

### 1.4 Settings UI

| 功能 | 实现状态 |
|------|---------|
| LAUNCHER 入口 | ✅ `a2b094db` commit 添加 |
| HuggingFace 模型下载 | ✅ 直接 URL，无需 HF SDK |
| 推理线程数 Slider（1-8） | ✅ |
| Context 长度 Slider（512-4096） | ✅ |
| LLM 启用/禁用 Switch | ✅ |
| 下载进度条 + Toast | ✅ |

### 1.5 APK 发布

| 项目 | 状态 |
|------|------|
| Release commit | ✅ `a2b094db` |
| arm64-v8a APK | ✅ 已构建 |
| x86_64 APK | ✅ 已构建 |
| x86 APK | ✅ 已构建 |
| 签名冲突说明 | ✅ 文档已注明 |

---

## 二、P0 待完成功能分析

### ⚠️ LLM 候选词视觉区分

**现状：** `CandidatesView.kt:134` 使用 `⚡` 前缀 + `"AI"` comment

**缺口：** 无背景色或独立图标，普通候选词与 AI 候选词视觉差异小

**影响：** 中等用户体验问题，不阻塞功能使用

**实现代价：** 低——可添加 `Drawable` 或改变背景色区分

### ⚠️ Vulkan / GPU 加速

**现状：** `LlamaEngine.cpp:95` 设置了 `n_gpu_layers = 32`

**缺口：**
- `build.gradle.kts` CMake 配置中**未显式设置** `LLAMA_VULKAN=ON`
- 默认编译可能为纯 CPU 推理
- Vulkan 支持取决于设备 GPU + llama.cpp 编译选项

**验证方法：** 在真机上对比 `token/s` 吞吐量与 CPU baseline

---

## 三、P1 待完成功能分析

### ⏳ 性能测试

**现状：** 无 benchmark 代码

**缺口：** 无法量化 token/s 吞吐量

**建议：** 在 `completeStreamNative` 入口/出口添加时间戳日志

### ❌ RAG Pipeline

**评估：** 当前场景**不需要** RAG

**原因：**
1. 用户 pinyin 输入是实时碎片化的，不适合做固定文档检索
2. LLM 本身具备语言理解和补全能力
3. RAG 需要额外引入向量数据库（FAISS）+ embedding 模型，增加 APK 体积和复杂度

**结论：** 暂不实现是合理的工程决策

---

## 四、代码质量总结

| 维度 | 评级 | 说明 |
|------|------|------|
| JNI 线程安全 | ✅ | mutex 保护所有全局状态，use-after-free 竞态已修复 |
| AIDL IPC 正确性 | ✅ | `onPreeditChanged` oneway 语义正确，`getSuggestions` 同步缓存 |
| 协程使用 | ✅ | `Dispatchers.IO` 推理，`serviceScope` 生命周期正确 |
| 缓存一致性 | ✅ | `suggestionLock` 保护读写，unload 时清空缓存 |
| 候选词去重 | ✅ | `distinct()` + `breakpoints` 分段逻辑正确 |
| 历史审计（v1-v12） | ✅ | 12 轮审计，高优先级问题持续清零 |

---

## 五、总体结论

### ✅ 核心功能完全可以实现

输入法内 LLM 辅助补全功能的**完整端到端链路**已实现且代码质量达到 production-ready 标准：

- 用户输入拼音 → AIDL IPC 触发推理 → llama.cpp 流式生成 → 候选词注入输入法候选列表
- 所有接口定义、生命周期管理、线程安全、缓存一致性均已正确处理
- APK 已发布 multi-arch release

### ⚠️ 剩余 P0 项为体验优化，不阻塞发布

| 问题 | 优先级 | 建议 |
|------|--------|------|
| 候选词视觉区分不足 | P0 | 添加背景色或图标，代价低 |
| Vulkan GPU 加速未验证 | P0 | 真机测试 token/s，对比 CPU baseline |

### ❌ RAG Pipeline 暂不需要

当前输入法场景下，纯 LLM 推理已足够，无需引入 RAG 带来的额外复杂度。

---

## 六、审查历史

| 轮次 | 状态 |
|------|------|
| v1-v8 | 逐步修复 JNI 线程安全、死代码、竞态条件等问题 |
| v9 | 发现 use-after-free 严重竞态 → 已修复 |
| v10 | 清理死代码、完善注释 → 零高优先级问题 |
| v11-v12 | 完善缓存逻辑和边界处理 → 零问题状态 |
| **本期（可行性审查）** | **核心功能完整，P0 项为体验优化** |

---

*审查人：萤萤·审查员*
*审查日期：2026-04-17*
