# 梦欢 v0.67.0 全面验收报告

日期：2026-10-08 · 验收对象：versionCode 156 / `梦欢-v0.67.0-release.apk`（38,067,067 字节）
方式：**源码 + 可执行核对**（编译、JVM 单测、把手册合成结果 dump 出来逐条比对）。
⚠️ 不含真机验收 —— 真机部分单列在第四节，别把这份报告当"装机过了"。

---

## 结论

| 板块 | 判定 | 关键证据 |
|---|---|---|
| AI 提示词模块（八段） | **结构正确、读写与升级机制可靠**，但**少一段**、**手册落后于已发布功能** | `PromptSettingsScreen.kt:44-55`、`SettingsRepository.kt` 的 9 个策略键、手册 dump 逐词命中表 |
| 手册自动升级机制 | ✅ 正常，测试守住 | `PersonaRepository.kt:25-34`/`:134-147` + `AssistantManualUpgradeTest` 6 条全绿 |
| 视频通话 / Live2D（v0.67.0 改动） | 源码级 ✅，**三批连投没上真机**（v0.64 / v0.66.1 / v0.67.0） | `docs/CHANGELOG.md` v0.67.0 节 |
| 构建 / 数据 / 权限 / 密钥 | ✅ 全部合规，未见回退 | 见第三节 |
| 台账与仓库卫生 | ⚠️ 28 项未提交；zip 未跟踪 | `git status` |

一句话：**提示词模块本身没坏，坏在"跟不上版本"—— 有一段的存储和读取都在、唯独没有编辑入口；
手册则漏掉了四个已经发布的功能。** 这类缺口的后果不是崩，而是**用户问助手"这个功能怎么用"，
它答不上来或答旧的**（AGENTS §零.5 明说这比没有助手更糟）。

---

## 一、AI 提示词模块（重点）

### 1.1 结构与代码事实核对

UI 里八段（`ui/settings/PromptSettingsScreen.kt`）：

| 段 | 落库位置 | 框架只读 | 结论 |
|---|---|---|---|
| 应用助手提示词 | 内置人设 `应用助手.systemPrompt`（**不是** DataStore） | — | ✅ |
| 基础提示词 | `base_prompt` | — | ✅ |
| 档案注入说明 | `profile_preamble` | — | ✅ |
| AI 洞察提炼策略 | `extraction_strategy` | ✅ `ProfileExtractor.FRAMEWORK` | ✅ |
| AI 周报撰写策略 | `weekly_report_strategy` | ✅ `WeeklyReportMath.FRAMEWORK` | ✅ |
| 生图触发策略 | `image_hint_strategy` | ✅ `IMAGE_HINT_FRAMEWORK` | ✅ |
| 手机操控（带总开关） | `agent_chat_strategy` + `agent_chat_enabled` | ✅ | ✅ |
| AI 语音条 | `voice_note_strategy` | ✅ | ✅ |
| **每日建议策略** | **`daily_suggestion_strategy` 键存在、`DailySuggestionGenerator.kt:140` 真的在读** | 有 FRAMEWORK | **❌ UI 里没有这一段** |

另外三处逻辑核过是对的，不用担心：

- **改动判定**：`draft != persisted` 才出现「恢复 / 保存」，与 v0.54.4 拍板的形状一致。
- **手册纳管标记**：保存助手段一定走 `personaRepository.save()`，那里按
  "存的文本 == 当前默认？" 写 `assistant_prompt_customized`（true 才落盘，改回默认就 remove）
  → **改回默认等于重新交还给 App 维护**，与 AGENTS §零.5 描述一致 ✅。
- **升级机制**：启动时 `refreshAssistantPromptIfNeeded()` 三态判定（null 才按"像不像内置手册形状"判一次）✅。

### 1.2 问题清单（按严重度）

**P1 · 「每日建议策略」有后端没前端。**
`DailySuggestionGenerator.kt:42` 的注释白纸黑字写着"用户可在「设置 · AI 提示词 · 每日建议策略」里覆盖"，
DataStore 键 `daily_suggestion_strategy` 存在、生成链路真的读它 —— 但提示词页只有八段，没这一项。
后果：这句话现在是**假承诺**；用户想调"今日建议"的口径只能改代码。
（v0.42.0 加每日建议链路时漏接的，属于典型"新功能没回头补提示词模块"。）

**P2 · 助手人设被改名后，手册彻底脱离升级，还会多出一个"应用助手"。**
`PromptSettingsScreen.kt:91-93` 用 `getByName("应用助手")` 取手册；改名后取不到 →
页面显示的是**默认手册**（不是用户那份），此时点保存 → `PersonaRepository.save` 走
`?: Persona(name = ASSISTANT_PERSONA_NAME, …)` **新建一条内置助手**，库里出现两个；
而原来那份改名手册也不再被 `refreshAssistantPromptIfNeeded()` 命中（它同样按名字找）。
人设编辑器对内置人设**只打了个 `BuiltinTag`、没锁名字**（`PersonaEditorSheet.kt:515`）。
后果：用户手一抖改个名，"手册必须跟着版本走"这条机制就静默失效。

**P2 · 手册漏掉四个已经发布的功能**（我把合成后的手册全文 dump 出来逐词查的，不是印象）：

| 功能 | 版本 | 手册命中 | 用户会怎么问 |
|---|---|---|---|
| **今日建议 / 每日建议**（数据页那块，每天 07:30 生成、长按转任务） | v0.42.0 | **0 次** | "今天建议哪来的 / 怎么改它的口径" |
| **在线语音识别**（`AsrModelScreen.kt:80` 的离线/在线二选一） | v0.59.0 | **0 次** | "识别不准能不能换在线的" —— 手册只会讲离线 SenseVoice |
| **隐藏会话的找回路**（人设管理卡片右端那颗 `ChatBubble`） | v0.61.0 | **0 次** | "删掉的会话怎么找回" —— 这是用户真报过的坑 |
| **平板 / 折叠屏双栏与版心** | v0.35 起 | **0 次** | "平板上为什么右边也有一份" |

（`TextureView` 这类纯实现细节不算缺口，本来就不该进手册。）

**P3 · 手册里有两处对同一件事的重复措辞**：语音合成"支持三家：…"（旧）与
"三家可选（…）"（新）并存 —— 是一条 replace 锚点没命中留下的**静默残留**。
不矛盾，但会让助手在同一个答案里换两种说法。

**P3 · AGENTS §零.5 写的"点「全部恢复默认」则重新纳管"已经没有这个按钮**（v0.54.4 改成**分段**「恢复」）。
代码行为是对的，是**文档措辞滞后** —— 下一个人照 AGENTS 找那颗按钮会找不到。

**P3 · 手册体积**：合成后 **19,366 字符**。历史预算是 `HISTORY_CHAR_BUDGET(200,000) − systemPrompt.length − 8,000`，
所以它**吃掉的是自己的历史额度**，不会溢出；但代价是：应用助手这一路每次请求都固定带 ~1.9 万字符，
**在 8k 上下文的小模型上这一路会直接超限答不了**。真机上如有用户报"助手回一句错"，先查他选的模型窗口。

---

## 二、视频通话 / Live2D（v0.67.0 这批）

源码级已验收 ✅：

- 承接层 `live2d/ActionBlender.kt`（纯函数、每帧零分配）：换动作 / 停动作 / 循环接缝 / 淡完交还控制权，四条都有单测钉住。
- 舞台旋转绕角色中心（`StagePose.fillNdcMatrix`），且有"pivot=0 与旧实现逐元素相同"的回归保护。
- 帧节拍封顶 60fps（`GlTextureView.pace`）、回前台归零动画时钟、`dt` 上限 1/30s。
- 通话页点角色 + 动作胶囊（只在 Live2D 出现；落在对话台上的点不算）；演完接回当前状态那条。
- 语音通话隔离：`videoMode=false` 时连视觉引擎都不创建（AGENTS §零.7）。
- 手册同步 4 条 replace + `AiPromptsTest` 一整条新用例（54 条全绿）。

⚠️ 遗留风险（本轮刻意没动，写在账上）：

1. **模型自带 HitArea 那一路命中检测仍在 UI 线程读 drawable**（v0.64 起就有，不是本轮引入）。
   后果最多是判错一档，不是崩；彻底修要给 GL 线程加事件队列，属另一个量级。
2. v0.66.1 记的"**`character.destroy()` 应挪到 GL 线程内执行**"仍未做（要连真机一起）。
3. `CubismShaderAndroid` 是进程单例，**同进程两块 GL 视图**（角色空间 + 通话）互踩的老问题
   靠"视图代数 + 一次一路通话"规避，没有结构性解决。

---

## 三、全局体检（构建 / 数据 / 权限 / 安全）

| 项 | 事实 | 判定 |
|---|---|---|
| 三处构建硬约束 | `noCompress` 只剩 `onnx`；`abiFilters` 只剩 `arm64-v8a`；`proguard-rules.pro` 有 `-keep class com.k2fsa.sherpa.onnx.**` | ✅ 未被回退 |
| Live2D Core keep | `-keep class com.live2d.sdk.cubism.core.** { *; }` | ✅ 在 |
| Media3 版本 | 钉在 1.9.0（1.10+ 会因 `minCompileSdk=36` 挂） | ✅ 在 |
| Room | `version = 19`、`exportSchema = true`、`schemas/…/19.json` 存在、**无** `fallbackToDestructiveMigration` | ✅ |
| 清单权限 | INTERNET / POST_NOTIFICATIONS / USE_FULL_SCREEN_INTENT / SCHEDULE_EXACT_ALARM / RECEIVE_BOOT_COMPLETED / RECORD_AUDIO / CAMERA | ✅ 与实际功能对得上；`ACCESS_NOTIFICATION_POLICY` 没申请 = §零.8 用户拍板不做，正确 |
| 通知震动 | 走 `Notification.Builder.setVibrationEffect`，不需要 VIBRATE 权限 | ✅ |
| 硬编码密钥 | `git grep` 全仓 `sk-…` / `api_key="…"` 零命中 | ✅ |
| 产物 | release 出包成功，签名与历次同一张 debug 证书（可覆盖安装） | ✅ |
| 本轮相关测试 | `Live2dActionTest`(53) + `AiPromptsTest`(54) + `CallVisualTest`(20) + `AssistantManualUpgradeTest`(6) 全绿 | ✅ |
| 全量测试 | 没跑满：Robolectric 那批历史有 7 项环境性红（v0.65 那轮记过），与本轮无关 | ⚠️ 记在账上 |
| ROADMAP | 未勾项 1 条（"下一版候选"清单） | ✅ 台账干净 |
| git | **28 项未提交**（含新增 `ActionBlender.kt` / `ActionScale.kt`）；测试用 `免费模型—奶蛙.zip` 仍未跟踪 | ⚠️ 见第四节 |

---

## 四、无法在本机验收（必须装机的清单）

**这条线从 v0.58 起，已经累计三批改动没上过真机**（v0.64 动作系统 / v0.66.1 体检 / v0.67.0 本轮）。
装机要逐条给结论，别只说"看着还行"：

1. 通话里四态来回切，**脖子还弹不弹**（承接层的核心疗效）。
2. 翻跟斗是**原地翻**还是绕着屏幕转。
3. 循环动作（默认待机/说话）跑起来**有没有每圈一卡的接缝感**。
4. 点角色：点头部→摇头、点身体→摆手、**点字幕没反应**。
5. 动作胶囊一排：点了演、演完自动接回当前状态。
6. 幅度档位「小/标准/大/特大」四档是否**看得出差别**；四态默认在"标准"档是否已经"明显"。
7. 打 10 分钟后是否还卡（帧节拍疗效）；机身温度对比旧版。
8. 回前台那一帧**抽不抽**。
9. 伪视频那一路：确认通话页**没有**出现点角色与胶囊（只有 Live2D 该有）。
10. 语音通话回归：主视觉头像、通话完全不受本轮改动影响。

---

## 五、建议的修复批次（v0.68.0 候选，按性价比排序）

1. **补「每日建议策略」这一段**（八段 → 九段）：`LayeredEditor` 现成，
   `DailySuggestionMath.FRAMEWORK` + `dailySuggestionStrategy` 都齐了，只是没接进页面；
   同时把 AGENTS/手册里的段数措辞同步。
2. **手册补四块**：今日建议、在线识别（二选一、不自动回退）、删掉会话的找回路、平板双栏。
   每块按 §零.5 配一对断言。
3. **助手人设锁名**：编辑内置"应用助手"时名字只读（或让 `PromptSettingsScreen` /
   升级机制按 `isBuiltIn + id` 找而不是按名字找），杜绝"改个名手册就不升级了"。
4. 清掉手册里语音合成的重复措辞。
5. AGENTS §零.5 那句"全部恢复默认"改成"该段下方那颗「恢复」"。
6. **安排一次真机验收**，把第四节十条走掉；`免费模型—奶蛙.zip` 要不要进 `.gitignore` 也一并定。

---

## 附：这次是怎么核的（可复跑）

- **手册不是靠眼睛读的**：写了个临时 JVM 测试把 `AiPrompts.DEFAULT_ASSISTANT_PROMPT`
  的**合成结果**写到 `logs/manual-dump.txt`（临时测试已删，没留在仓库里），
  再用 `logs/audit-manual.js` 做两件事：① 扫全部 182 条 `replace` 的锚点，
  在成品文本里找"旧句还在、新句不在"= 静默落空；② 按功能关键词表逐项查手册覆盖。
- 为什么要这么做：`replace` 的子串对不上时**不报错、不崩、什么都不写**，
  而 v0.50.0 那次整章语音教程就是这么在真机上"助手答不上来"才被发现的。
