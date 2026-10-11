# 梦欢 · 项目长期记忆

> 只留仍有约束力的结论。版本见 `docs/CHANGELOG.md`；流水见 `.workbuddy/memory/2026-*.md`；命令行与脚本细节见 `PLAYBOOK.md`。

## 铁律
- **用户意图不一定正确，按最优方案答，不迎合。** 不用 A/B/C 选项回避判断；用户直觉可推翻我的错误方案，但不顺着说；前提有误先纠前提。只有**真等价**（口味/风格/成本）才给选项。
- **看到常量/参数先问「它凭什么」**，改前先 grep 有没有被读。
- 「源页有、目标页没有」的上下文先问（按角色的功能搬到全局页 → 进谁的角色？）。
- 新功能/疑难修复先搜 GitHub 成熟案例；没把握先说明，不试错式连改。
- 测试/编译/打包由用户决定，**一批需求=一个版本**。**UI 改动编译即停；要跑测试先问一句**；版本号/CHANGELOG/出包一律等点名。例外：动到 `manualPatch` 链/迁移/断言这类**静默失效**处，锚点核对要做，但**只跑那一个类**。
- 版本号**两段式**（0.57、0.58），绝不三段补丁号。
- 功能入口/行为变化必须同步 `AiPrompts.DEFAULT_ASSISTANT_PROMPT` + `AiPromptsTest`。
- 键盘与语音现行栈未经点名不动；数据只存本机；**改交互/动效前必须先问**。
- 禁止 `git checkout -- <文件>`（工作区长期大量未提交改动，曾冲掉 replace 链）。
- 「只能/只在…」先问清是"要限制"还是"描述现状"。
- **删声明要连上方紧邻 KDoc + 注解行一起删**（`@Query`/`@Composable` 在上方，漏删 → KSP 报 `Not sure how to convert a Cursor…`）。

## 构建与测试
- **命令细节（JDK/Gradle 路径、subst、出包顺序、抓栈）见 `PLAYBOOK.md`**；勿删 `local.properties`、`android.overridePathCheck=true`、阿里云镜像；日志写 `logs/`。
- APK 字节数不能当重建判据（zip 对齐凑长度），认 md5；证改动编进去比 `classes.dex` md5。
- **dex 搜中文必须用 Python 数 UTF-8 字节**：`strings -e l` / `utf-16-le` 全 0 是**假阴性**，`grep` 对中文失效；短串防误命中，以长串锚点为准。
- ⚠️ **手册类旧措辞在 dex 里恒有命中** —— 那是 `manualPatch` 的 **target 参数**（补丁要拿它做匹配），不代表手册最终文案里还有。**判手册文案只认 jshell dump**；dex 只能证明"新串编进去了"。
- **测试基线 v0.88.0：719 项 / 64 类 / 0 红**；唯一 flaky `UiRenderTest.render25VoiceModeBlockedByMissingModel`（单跑必绿）。
- 测试红了先分"实现 bug / 断言过期"（看注释里是否写着"用户点名不要 XXX"）。
- 重写大文件前先列顶层声明清单，搬完与 `git show HEAD:<文件>` 对账。

## Room / 迁移
- **实体 `indices` 必须与迁移建的索引逐项一致**，否则 onValidateSchema 抛错 → 打开库即崩。
- 改实体形状后必须重跑 KSP 重导 schema（identityHash 变）并同步迁移测试。
- `DbMigrationTest.kt` 从 `app/schemas/<v>.json` 建旧库；Robolectric 下必须显式 `db.execSQL("PRAGMA user_version = $v")`；新迁移必须反向验证。
- **删功能的迁移**：建表那条历史迁移**原样保留**，只加「已废弃」注释（老库要一路重放，少一环报"找不到迁移路径"）；DROP 用 `IF EXISTS`。
- 金额以分存 Long，余额由流水累加。库版本：v26=记账分类列、v27=删周报表、v28=洞察 period/day+索引。

## UI / Compose
- Figma 风黑白骨架；令牌/组件走 `ui/common/Neu.kt`；图标只用 MIcons；轨道 `#F2F3F5`，危险 `#F24E1E`。
- 动效弹簧优先；渲染层动画值用 `graphicsLayer` 延迟读；独立 composable 不用 `Modifier.weight`；过滤节点直接不组合；异步数据区分未加载/空/有数据。
- 弹层可点区域一律 `clickable(indication=null)`；界面不写说明性小字。
- 复用件：`NeuAvatar(emoji, size, ringBrush=personaBrush(id), imagePath, modifier)`；底部弹层 `NeuSheetScaffold(title, onDismiss){ColumnScope}`。
- 已定稿（别走回头路）：底栏横滑=整数页号弹簧+拖动残余；emoji 头像排只做大小/透明度渐变绝不模糊；Pager 内动画由外部可见信号驱动；记账金额静态不滚动；「按住说话」按屏幕位置划档；聊天页浅灰线未定位不要猜着改。
- ⚠️ **`LaunchedEffect(Unit)` 在 Pager 里会在屏外跑**（四 tab 预组合）：刷新/入场一律由 `visible` 信号驱动。
- `Modifier.onGloballyPositioned` 报的是**整个 LayoutNode** 尺寸，`padding` 前后一样 —— 量"内容自己"必须挂更内层节点（长按菜单锚点栽过）。
- 导入坑：`pointerInput`→`ui.input.pointer`；`blur`→`ui.draw`；`boundsInRoot`→`ui.layout`；无 `positionInParent/Window`；`ui.unit.lerp` 只有 Dp 重载；`TextOverflow.MiddleEllipsis` 在 bom 2024.12.01 **不存在**。

## 对话滚动 / 键盘
- 首次 `scrollToItem`，后续仅 nearBottom 跟随；流式末项变长按高度差 `scrollBy` 补滚。
- `KeyboardInsets.kt` 是唯一键盘避让实现（导航条基线+逐帧 inset），不二次插值、不双层避让；面板/键盘互斥 + `ImeDismissesPanels` 兜底。

## 语音硬约束
- ASR：`SherpaAsr`（离线 SenseVoice）与 `OnlineAsr`（OpenAI 兼容）**两条并列、手动切换、绝不自动回退**；通话逐段喂 PCM **始终走端侧**；采集仅 `VoiceRecorder`。
- TTS 三家协议分别实现；CALL=WAV/24000、NOTE=MP3，采样率唯一常量 `TTS_CALL_SAMPLE_RATE`；音色名单 `TtsVoices.library()`；重采样必须 `WavPcm.resample`；`customName` 只收 ASCII，中文名存本机显示。
- `WavPcm` 保持 PCM 8/16/24/32、float、EXTENSIBLE；构建 `noCompress` 仅 onnx、ABI 仅 arm64-v8a、sherpa JNI keep 不动。
- 朗读：`ChatViewModel.readAloud()` 复用语音条链；同一时刻只念一条；复位不能只靠 `onCompletion`（按 `probeDurationMs` 兜底）；每次打断 `readToken++`；AI 已发语音条的回复跳过自动朗读。

## 业务不变量
### 全局
- 四 tab：消息/数据/任务/我的；对话页三点菜单固定九项；接听延迟独立路由 0~600 秒。
- **数据页顺序**：本周回顾→今日建议→记账本→任务→**角色空间**→AI 洞察；角色空间入口在「我的数据」页，点开先弹选人层（没导入 Live2D 模型包的仍列出但不可点）。
- **四条 AI 产出链路**：记忆←对话转录（手动/每 N 轮）；洞察←**记忆+任务+记账**（`InsightEngine`，不读转录，允许 0 条，MAX 3，日/周/月）；每日建议←**昨天的洞察**（只静默落库、**不发通知**）；任务建议←聊天当下（`TaskSuggestionMarker`，**只在聊天页出卡**）。周报已删。
- **AI 引用任务口径**：唯一出料口 `data/TaskContext.kt`，按归属日分桶 —— 今天该做 ≤8 / 明天到期 ≤4 / 今天已完成(给名字) / 逾期只给件数 / 3 天以后不进；判定一律 `isEffectivelyDone/Failed`，绝不裸用 `isDone`。**任务块只留聊天链**（`ChatViewModel.taskStatusBlock`），后台三链全砍、不加开关。**根因教训：模型提到不该提的东西，多半是宿主喂的料里带着它 —— 改提示词不如改出料。**
- **记账分类**：`ledger_entries.category` 只存**名字**不存 emoji（`LedgerCategory.PRESETS`）；**第一项必须是「未分类」**；只在非空时显示标签；分类**不进统计口径**；选择器是**一行点选 chips**；流水按天分段。
- API 用量按模型分别记（`usage_by_model`，六个记账点都必须传 `ApiConfig.model`）。联网查询串取"最后一条用户消息"，绝不读 `_uiState.value.input`；业务链路用 `search`（吞异常返空表），测试用 `searchOrThrow`。
- **动态只存于主动关心链**（用户拍板）：聊天链不看名单、不回帖/赞/不发帖、**连剥标记都不做**（`MomentMarker` 在 `ChatViewModel` 已零引用）。落库唯一实现 `data/MomentActions.apply`（`Limits.PROACTIVE` 1/1/1；点赞幂等只加不取消）；**feed 里已表态的帖子不再列**（`MomentMath.engagedBy`）。
- 人设写作规范单一来源 `data/PersonaPromptSpec.TEXT`；AI 看动态只剩一个时机（角色主动来找时喂未回未赞 ≤5 条，**不列自己发的帖**）。
- 加号面板相册/文件=落 `PendingAttachment` 待发送条；`sendFile` 带 caption 并清空输入框。
- 音色弹层纯展示；会话「模型」弹层无「跟随全局」；deep link 域名 `menghuan`；编辑人设独立路由页。
- **滚动摘要**：`conversation_summaries` 水位线 + `SummaryEngine`（掉出窗口超 3 万字触发、单次 ≤4 万字、助手会话跳过）；只拼 system prompt 尾部；默认开；**摘要与历史必须读同一来源**。
- **上下文窗口与提炼节奏（按角色，都在会话设置）**：窗口默认 **12_000**（`history_budget_<id>`，2000..200000，入口在**「对话摘要」弹层**）；提炼间隔默认 **6 轮**（`extract_every_<id>`，1..50，**只数用户侧**，入口在**「TA 的记忆」小层**）；都**只在改过时才落盘**；删角色走 `removeSessionTuning`。口径：窗口是「防 400 的硬上限」不是目标用量；无状态 API **历史每轮全量重发、每轮全价**才是真正花钱处。
- **启动图标**：logo=黑气泡 `#0C0C0C`+白手写「mh」；`ic_launcher_background` 浅色 `#FBFBFA`；单色图标另存 `ic_launcher_monochrome`；冷启动（Activity 静态标记判）播逐笔绘制约 1.5s；`ui/common/MhLogoPaths.kt` 是**生成文件**（`tools/logo/` 脚本），别手改坐标串。

### 电话 / 主动关心
- **主动电话并入主动关心**（随机时刻、电话优先一次只做一件事、每天最多几通按角色 1–9、守静默 22:30–08:00；「几天没聊」整套已删）；**定时消息+定时电话合并为「定时」**（同存储以 kind 区分，只看条目 enabled，无总开关）。老 `ai_call_rules` 只剩：enabled 推断成新「主动打电话」开关 + instruction 存量；兼容推断读的 `keyAiCallTime`/`keyAiCallIdleDays`/`keyAiCallPersonaId` **只读保留**。
- 定时消息**跟着角色走**：目标人设没了就不发，删角色时一并摘掉它的定时项+取消闹钟。
- **主动关心提示词已接上并开放编辑**：原 `SYSTEM_INSTRUCTION`（死代码、零引用）拆成 `HINT_FRAMEWORK`（不可编）+ `DEFAULT_STRATEGY`（可编）+ `systemPrompt(persona, strategy)`，由 `settingsRepository.proactiveStrategy` 覆盖（键 `proactive_strategy`）→ 那段里的「≤60 字」「别出戏」第一次真的生效。「AI 提示词」页现为**十二段**（第十一段「主动关心策略」）。
- ⚠️ 三条后台链（主动关心/定时消息/来电开场白）的 `【用户档案】` 都套 `profilePreamble`（原来那句"别复述档案"只有聊天/通话认）。

### 角色的「心情」（五轴）
- `data/CompanionMarker.kt` 的 `[心情:情绪词]` 标记是 **valence/arousal 的唯一输入源**（词表→写死增量，单轮最多 ±0.25；**用词不用数字**）。落轴唯一实现 `ChatViewModel.maybeUpdateMood`（落库后）；**主动关心/定时消息/通话只剥标记、不解析**；说明段**只注入聊天 system prompt**；剥离正则**不设词长上限**（否则超长词漏给用户；剥离必须比识别宽松）。
- **心情两个出口**：① `ProactiveChat`（她主动找你那次）把 `promptContext()` 拼进用户上下文；② 普通聊天走**易变上下文** —— `ChatViewModel.runtimeContextNow()` 尾部的 `companionMoodBlock()`（**绝不进 system prompt 前缀**，否则每轮作废缓存）。valence 在 `tick` 里当 `moodFactor`（<−0.2 时连接需求增速 ×0.4）。
- **两处刻意设计**：`applyDelta` 的 `connection` 参数**无调用方**（情绪词不该改写"想念度"，否则"主动来找你"变成可被提示词操纵的开关）；`tick` 里 `connection ≥ 0.6` 锁 valence 由"完全冻结"改为**回落速度 ×0.25**。

### 记忆
- **档案注入两条线**：`MemoryScope.split(all, personaId, shared)` → `base`（基础档案 `personaId=0`，**全带、不设上限**，同人设设定词性质）+ `owned`（角色自己的记忆，上限由调用方给：聊天 `MEMORY_INJECT_LIMIT=30`、后台四链 `PROFILE_FACTS_MAX=10`）。**绝不能合成一条再 `take`** —— 用户手填档案一多就把角色记忆挤出常驻。同 `key` 去重＝**留 id 更大者**；`personaId=0` 时 base 恒空；`visible()` **保留不动**（要全量）。
- `ProfileFact.injectable` 是**白名单**（`active`/`core`）—— 黑名单会把 `staging` 放进去，审批制就不存在。`INJECT_ORDER` = `core` 置顶 + **id 倒序**（用 id 不用 updatedAt，否则编辑一次就整体洗牌）。
- **记忆两条入口**：① `MemoryMarker` 即时标记 `<add:…>`，**每轮都可能**、不看门槛（`maybeStageMemories`）；② `ExtractionEngine` 批量提炼，攒够 `extract_every_<id>` 条**用户发言**（默认 6 = **12 段对话**）。
- ⚠️ **水位线推进时机**：`setExtractionWatermark` 必须在 LLM 调用**之后**、提前返回在**之前** → 顺序反了自动提炼**永远不触发**。
- **提炼不是配额**：`ProfileExtractor.DEFAULT_STRATEGY` 明写「0 条是正常的，不是失败」。已开放编辑（第十二段「记忆记录策略」，键 `memory_marker_strategy`；⚠️ 与「记忆提炼策略」`extraction_strategy` 是两条入口）。
- **记忆语义召回（默认关）**：`memory_embeddings` 按 `baseUrl|model` 分桶；90 天未召回 active→archived（core 豁免），archived 高分（≥80）召回当场升回（双门槛/懒索引细节见 `PLAYBOOK.md`）。
- `MEMORY_INJECT_LIMIT=30` / `PROFILE_FACTS_MAX=10` **没有来历依据**（别凭感觉调）。

## 可编提示词段（三件套）
`HINT_FRAMEWORK`（硬编码只读，改了解析会崩）+ `DEFAULT_STRATEGY`（用户可覆盖）+ `promptSection/systemPrompt(strategy)`；配套 `SettingsRepository` 一个 `stringPreferencesKey` + Flow + setter（空值或等于默认则 remove 键）；页 `PromptSettingsScreen` 用 `LayeredEditor`。**形状统一 → 新增段照抄三件套。**

## 手册 replace 链
- 只追加链尾；target 必须**从 dump 出的最终文案里抄**；只许 `.manualPatch()`（落空记进 `assistantManualPatchMisses`），**不许裸 `.replace()`**。每加一条同时在 `AiPromptsTest` 补一对断言（新在 + 旧不在，只写后者是假绿）。
- ⚠️ **离线模拟补丁链不可信**（v0.92.0：249 条模拟出 22 条假未命中、第 87 条起级联失真）。**只有真编译 + jshell dump 才可信**（手法见 `PLAYBOOK.md`）；核锚点用 Python `count()`（grep 对中文失效）。
- **补信息不能插进句子中间**（中文会断）→ 整句保留 + 分号后补一句；**改一句要搬全它承载的所有事实**，别丢 `**` 强调。⚠️ **断言别钉版本相关数字/计数**（钉「八段」→ 改十段当场红），也别钉排序等易变行为。

## 禁止复活
- 打字机 `streamingText`、表情包、API 列表 ✓、语音两个总开关、设定词模板按钮、备忘本。
- 已删：`EdgeTtsClient.kt`、`ReadAloudPlayer.kt`、`realtime/Mp3Decoder.kt`、`WeeklyReportGenerator`。

## 死代码审计（工具留在 `tools/`，每版可重跑）
- **病根**：声明/注释承诺了、实现没接上（死代码 / 写了没人调 / 常量没人读 / **只在测试里活**）。**6 个脚本与已知局限见 `PLAYBOOK.md`。** 已清 v0.98 收尾的 30 文件 / 47 处 + 5 处补漏（老「几天没聊」残留、设计令牌、UI/VM/DAO 死件、`DailySuggestionReceiver` 通知段 = 行为不变清理；`PersonaRepository.remove` 补上漏调的 `removeCompanionState(id)`）。
- **已处置（用户拍板「本来就没有的东西不要补回」）**：数据页「提炼」按钮 v0.42.1 就被收敛，手册 v0.86/v0.88 又把它写回去 → 假承诺。已删整链（`refreshInsights` + `extracting`/`extractMessage` + `AiEntryCard` 三个无用参数 + `DataViewModel` 的 `extractionEngine` 字段）+ 链尾两条 `manualPatch` 更正手册 + 翻掉钉它的断言。**教训：测试断言会替假承诺站岗 —— 改行为前先看有没有断言在钉旧说法。**
- 遗留未定：**省钱旋钮**（历史窗口默认 12_000 每轮全量重发）、「每隔几轮提炼一次」UI 文案歧义。

## 手机 Agent（`com.menghuan.app.agent`，7 文件 ≈800 行）
- 路线：无障碍读屏 + LLM 决策（每步一次非流式请求）+ 无障碍手势。两个入口：对话内 `[操作手机:目标]`（autoConfirm）/ 任务页 ▶（`AgentSheet`，走确认）。
- **保活 = 默认开**（`agent_keep_alive`）：`AgentKeepAliveService`（specialUse 前台服务 + 静默常驻通知），**只在 `AgentLoop.run()` 前置检查通过后挂** —— 用过才挂，**不在 App onCreate 恢复**（那等于人人常驻，且后台起 FGS 会被系统拒）；开关在 `AgentSheet`。
- **自愈**：连续 3 次读不到屏幕 → 中止并让用户重开无障碍（别空转烧完步数）；服务 `onUnbind` 必须清 `instance`。⚠️ 代码**无法**自动重开无障碍（系统限制），只能深链设置页 + 电池白名单引导。
- `waitWindowStable` **两阶段**：等首次 tick 变化 ≤600ms（无变化直接返回）+ 变化后静默 400ms，上限 2.5s。**别再改回"从入口起算静默 900ms"**（那是 ≥13.5s 纯等待的根因）。
- 解析失败重试**必须带提示**（`AgentLoop.RETRY_HINT` → `AgentPrompts.userPrompt(retryHint)`）；原样重发 = 花两次钱买同一个错。
- 方案文档 `docs/PLAN-2026-10-11-手机Agent优化方案.md`（P0 六条 / P1 脚本缓存 / P2 Shizuku+视觉），**按批交付、每批做完即停**。P2 闸门 = 用户 API 是否支持多模态。
- **元素清单**（`ScreenState`）：准入只留 `clickable||editable||scrollable`，**纯文本仅整屏无交互元素时兜底**；折叠（type+真实文案，留 3 标 `×N`）**必须先于截断**；**无真实文案的节点绝不折叠**（一排图标按钮会点错）；标签截断 20 字**只影响清单文本**，`UiElement.text/desc` 保持完整。
- **定位**（`ActionExecutor.resolve`）：resourceId 优先（同 id 取最近心）→ 坐标兜底；**"界面动过"只认窗口 tick**（比自己的快照序号恒等 = 没校验），动过则坐标必须过标签校验，认不出就**拒绝执行**报"界面已变化"。
- **卡住判定**按 `AgentPrompts.actionSignature`（`tap[3]`/`text[4]`/`swipe[up]`），**失败也计入**，连续 2 次同类即中止；`text` 的输入内容不进签名。
- **敏感围栏**：元素文案 + **要输入的内容**（`isSensitiveValue`）两处都查；`execute()` 返回 `StepOutcome`（Done/Failed + 可读原因），失败原因进日志与历史。
- **动作脚本缓存**（`ActionScriptStore` + `AgentLoop.replayScript`，存 `filesDir/agent_scripts.json`）：命中 = 目标规范化 + 起始包名 + **起始界面指纹**全等；**每步重读屏按指纹（resourceId 优先，无 id 用类型+标签）找元素，同屏多命中即作废，绝不盲重放**；只有 done 成功且 ≥2 步才落盘，上限 50；起点指纹必须在任何部分重放**之后**再取。入口在 `AgentSheet`（条数 + 清除）。
- **Agent 专用模型**：键 `agent_model`（空 = 跟随接口），`baseApi.copy(model=…)` **只换模型名**，地址/Key 仍用接口的；记账传生效模型。
- **观测层混合输入（截图+树）**：`ScreenCapture`（`takeScreenshot`，**API 30+ 才生效**，低版本静默退回纯树）落 `filesDir/agent_screenshot.jpg`（固定名每步覆盖）→ `ChatMessage.imagePath`。⚠️ **`collectReply` 的 `imageDataUrl` 默认 `{ null }`，不显式传 lambda 图片静默不发** —— 走多模态必须 imagePath + lambda 两件都给。开关 `agent_vision`（默认开）；SYSTEM 规则 6 钉死「动作只能按编号、截图只辅助」。
- **Shizuku 后端已判定不做**（P2-K）：`screencap`/`input` 的独特价值被 M4-L 吃掉，代价（新依赖 + 装 App + 重启重激活 + 双后端）没变。触发条件 = 主力机停在 Android 10 且要视觉。

## 音色克隆（易踩的两条）
- **服务端 `customName` 必须唯一**：`VoiceCloner.asciiVoiceName` **一律拼 4 位随机后缀**。旧实现只把显示名里的 ASCII 抠出来（`小美a`/`小明a` → 都成 `a`）→ 服务端同名 → **名单里永远只有一条，且上传成功、无任何报错**（用户报"只能克隆一个"的真因）。改完记得：算一次就复用（存缓存的名字必须与上传的一致）。
- **样本文件名也必须唯一**（`音色名_时间戳.wav`）：MiMo 的音色名单**就是 `filesDir/voices/` 里的 .wav**，同名即覆盖 → 按设计只能有一个。
- 逐字稿是端侧 ASR 自动填的，而 ASR 开着 **ITN**（口语数字→阿拉伯数字），服务商要求 prompt 文本与音频逐字一致 → 对不上会跑调。**别用"再开一个 ITN=false 的识别器"解决**（两份 228MB≈500MB，`SherpaAsr` 头注释警告过的 OOM 场景）→ 走「含数字时 UI 提示核对 + 重听样本」。
- 官方样本硬要求：**单一说话人 / 无 BGM 与回声 / 音量情绪平稳 / 8~10 秒**；只能靠 UI 明示，代码救不了。
- 「删除克隆音色」= **写隐藏表**（`hiddenVoiceIds`，服务商账号里的实体不动），**必须有恢复入口**（`unhideVoice`）；克隆成功要顺手写 `cachedClonedVoices`，否则列表接口一失败名单里就查无此音。

## Live2D
- 子系统在 `com.menghuan.app.live2d`，与通话解耦。**动作引擎 4 条铁律、通话页布局定稿、缩放与背景约束见 `PLAYBOOK.md`** —— 动 Live2D 前先读那节。

