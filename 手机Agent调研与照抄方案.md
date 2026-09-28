# 手机 Agent 调研与照抄方案（自用版，不上架）

> 目标：让导师 App 能「看见屏幕 → 问 AI → 替我点手机」。只自用，不需要上架审核。
> 本文档 = GitHub 调研结论 + 可照抄的模块拆解 + 给 AI 工具的实施提示词。

---

## 一、GitHub 上值得照抄的项目（按推荐度排序）

### ⭐ 第一梯队：纯 Kotlin 端侧实现（和导师 App 同构，直接抄代码）

**1. Operit（AAswordman/Operit）** —— 最推荐
- Android 原生 Kotlin App，完全独立运行，40+ 内置工具
- 含「自动点击 Agent」：无障碍服务感知 + LLM 决策 + 手势执行
- 支持自定义 API（OpenAI 兼容）→ 和导师的 OpenAiClient 同款协议
- 亮点：无障碍封装、Shizuku 提权、工具调用架构、记忆库
- 抄什么：`自动点击 Agent` 的感知/决策/执行三件套；工具注册架构

**2. Aries-AI（ZG0704666/Aries-AI）** —— 架构参考价值最高
- Kotlin + Compose，文件结构一目了然：
  - `PhoneAgentAccessibilityService.kt`（无障碍感知）
  - `ActionExecutor.kt`（手势/输入执行）
  - `UiAutomationAgent.kt`（agent 循环）
  - `PromptTemplates.kt`（决策 prompt 模板）
  - `ScreenshotManager.kt`（截屏）
  - `FloatingChatService.kt`（悬浮窗对话）
  - `ShizukuBridge.kt` / 虚拟屏方案（后台执行不干扰前台）
- 抄什么：整个目录结构就是我们要新增模块的蓝图

**3. DroidRun（droidrun/mobilerun-portal）**
- Android 无障碍服务 App：实时高亮屏幕上可点击/可勾选/可编辑元素并编号
- 「给控件编号 + 覆盖层标注」是最实用的感知方案，LLM 只需返回编号
- 注意：AGPL-3.0 协议（自用无所谓）

### ⭐ 第二梯队：PC 端 ADB 控制流（不用改 App，先跑通体验）

**4. Open-AutoGLM（zai-org/Open-AutoGLM）**
- 智谱官方开源的 AutoGLM 手机 Agent：PC 上跑 Python，经 ADB 控制安卓真机
- 依赖 GLM-4V 多模态模型读截屏
- 适合**先体验**：半小时就能在电脑上让 AI 替你点手机，验证这个能力值不值得做进 App

**5. AppAgent（TencentQQGYLab/AppAgent）/ Mobile-Agent（X-PLUG/MobileAgent）**
- 学术框架（CHI 2025 论文），Python + ADB
- 核心贡献是**决策循环设计**：探索阶段（自学习每个 App 怎么用）+ 执行阶段
- 抄什么：prompt 设计（观察→思考→行动→总结的四段式）和自我探索记忆机制

### 对照表

| 项目 | 语言/形态 | 感知 | 决策模型 | 执行 | 适合抄什么 |
|---|---|---|---|---|---|
| Operit | Kotlin App | 无障碍 | OpenAI 兼容 API | 无障碍手势 | 工具架构+Agent 主体 |
| Aries-AI | Kotlin App | 无障碍+截屏 | OpenAI 兼容 API | 手势+Shizuku | 全套模块蓝图 |
| DroidRun Portal | Kotlin App | 控件编号+覆盖层 | 远程 | 无障碍 | 控件编号方案 |
| Open-AutoGLM | Python PC | 截屏 | GLM-4V | ADB | 快速体验 |
| AppAgent | Python PC | 截屏+XML | GPT-4V 等 | ADB | Prompt 设计 |

---

## 二、照抄到导师 App 的技术方案

### 能力分层（MVP 三件套）

```
┌─ 感知层 ── AgentAccessibilityService
│    onAccessibilityEvent 抓当前窗口控件树 → 压缩成编号清单：
│    [3] Button "发送" bounds=(900,1800) clickable
│    [4] EditText "输入内容" text=""
├─ 决策层 ── AgentLoop（复用 OpenAiClient）
│    prompt = 任务目标 + 屏幕清单 + 历史动作
│    → LLM 返回 JSON：{"action":"tap","element":3} 或 {"action":"done"}
└─ 执行层 ── ActionExecutor
     tap/swipe = dispatchGesture（无障碍手势）
     输入文字 = ACTION_SET_TEXT / 剪贴板粘贴
     返回/主页 = performGlobalAction
```

### 需要新增的模块（对应导师目录结构）

```
app/src/main/java/com/daoshi/app/agent/
├── AgentAccessibilityService.kt   # 无障碍服务：感知 + 手势执行
├── ScreenState.kt                 # 控件树 → 编号清单（纯函数，可 JVM 单测）
├── AgentLoop.kt                   # 决策循环：观察→LLM→执行→验证，最多 N 步
├── AgentPrompts.kt                # prompt 模板（抄 AppAgent 的四段式）
├── ActionExecutor.kt              # tap/swipe/setText/back/home
└── AgentConfig.kt                 # 白名单 App、单步确认开关、步数上限
res/xml/agent_accessibility_config.xml
AndroidManifest.xml                # <service> 声明 + BIND_ACCESSIBILITY_SERVICE
```

### 关键实现要点（其他 AI 容易踩的坑都写在这里）

1. **无障碍服务声明**：`android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged"`、`android:canRetrieveWindowContent="true"`、`android:canPerformGestures="true"`（API 24+，minSdk 26 满足）
2. **截屏走 MediaProjection**（用户每次授权一次）或先纯控件树方案（零隐私弹窗，MVP 就用它）；多模态视觉模型后置到二期
3. **模型要求**：纯文本决策只需要普通 chat 模型（现有 API 配置直接用）；视觉决策需要 VL 模型（qwen-vl / GLM-4V / GPT-4o）
4. **安全围栏（必做）**：
   - App 白名单：只允许操作用户勾选的应用
   - 敏感动作（支付确认、发送消息、删除）执行前暂停请求确认
   - 悬浮窗「停止」按钮常驻，随时掐断循环
   - 步数上限（默认 15 步）防死循环烧 token
5. **与导师现有架构的整合点**：
   - LLM 调用复用 `OpenAiClient`（AgentLoop 只用非流式 `complete()` 即可）
   - 依赖注入走 `MentorApp` 容器
   - 执行日志可作为消息写入对应 persona 会话（「我帮你打开了微信并回复了消息」）
   - 长期方向：配合任务系统 → 「到点自动执行某任务」

### 建议的落地顺序

1. **第一步（不改 App）**：PC 装 Open-AutoGLM 跑通 ADB 控制，确认体验值得做 → 约半小时
2. **第二步（MVP）**：抄 Aries-AI/DroidRun 的无障碍三件套，纯控件树决策，只支持 tap/back/setText → 一次大版本（0.13.0）
3. **第三步**：加截屏 + VL 模型、悬浮窗、App 白名单设置页
4. **第四步**：与任务系统打通（定时自动执行）

---

## 三、给其他 AI 工具的实施提示词（可直接粘贴）

````
你是资深 Android 工程师，在「导师」App（Kotlin + Jetpack Compose，MVVM，手动依赖
容器 MentorApp，无 Hilt，minSdk 26）中新增「手机 Agent」能力：让 AI 通过无障碍服务
替用户操作手机。仅自用，不上架。动手前先读 AGENTS.md。

【参照项目】照抄以下开源项目的实现思路（可联网查看源码）：
- Aries-AI: https://github.com/ZG0704666/Aries-AI（模块蓝图）
- Operit: https://github.com/AAswordman/Operit（无障碍封装 + 工具架构）
- DroidRun Portal: https://github.com/droidrun/mobilerun-portal（控件编号+覆盖层）
- AppAgent: https://github.com/TencentQQGYLab/AppAgent（决策 prompt 设计）

【新模块】app/src/main/java/com/daoshi/app/agent/ 下：
1. AgentAccessibilityService.kt
   - 声明为无障碍服务（canRetrieveWindowContent + canPerformGestures）
   - 暴露单例引用供其他模块访问当前窗口根节点
   - dispatchGesture 实现 tap(x,y)、swipe；performGlobalAction 实现 back/home
2. ScreenState.kt（纯函数）
   - AccessibilityNodeInfo 树 → List<UiElement(index, type, text, contentDesc,
     bounds, clickable, editable, scrollable)>
   - 只保留 visible、text/contentDesc 非空或 clickable 的节点，上限 40 个
   - 输出编号清单文本，供 LLM 阅读；配套 JVM 单测
3. AgentLoop.kt
   - run(goal: String)：循环 { 取ScreenState → 组prompt → OpenAiClient.complete()
     → 解析 JSON 动作 → 执行 → 等待界面稳定(1.5s) }，直到 action=done 或达步数上限
   - 动作协议：{"action":"tap","element":N} | {"action":"text","element":N,"value":"..."}
     | {"action":"swipe","dir":"up|down"} | {"action":"back"} | {"action":"done","summary":"..."}
   - 解析失败重试 1 次；LLM 调用复用现有 OpenAiClient（非流式）
4. AgentPrompts.kt：系统 prompt 抄 AppAgent 四段式（观察/思考/行动/总结），要求
   LLM 只输出 JSON，不输出其他文字
5. AgentConfig.kt（DataStore）：步数上限（默认15）、App 白名单包名集合、
   敏感动作需确认开关
6. 安全围栏（硬性要求）：
   - 白名单外的包直接拒绝执行
   - 动作命中支付/删除/发送类关键词时暂停，等用户在 UI 确认
   - Agent 运行期间悬浮窗显示「停止」按钮
7. 入口 UI：聊天页（或任务页）新增「自动执行」入口，输入目标后启动 AgentLoop，
   每步动作实时显示；执行摘要写进当前 persona 会话消息

【约束】
- Compose 改动遵循 Figma 风设计系统（neuSurface/NeuButton 等，见 AGENTS.md §四）
- insets 只由 AdaptiveScreen 处理；不做 statusBarsPadding
- 构建命令与环境见 AGENTS.md §八；不许用 ./gradlew
- 完成后：assembleRelease 出包，按 APK 命名硬性规定处理；新增纯函数要有 JVM 单测
````

---

## 四、结论

照抄路径已经明确：**决策循环与 prompt 抄 AppAgent，无障碍感知与控件编号抄 DroidRun，模块组织抄 Aries-AI，工程整合抄 Operit**。这四个项目的代码都在 GitHub 上开放，MVP（纯控件树 + tap/text/back + 步数上限 + 白名单）是几百行 Kotlin 的量级，导师 App 现有的 OpenAiClient、依赖容器、会话体系全部可以直接复用，不需要引入任何新依赖。
