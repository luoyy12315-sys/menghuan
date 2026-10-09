# 梦欢 · 路线图

> 从 `AGENTS.md` §七 拆出来独立成文（2026-10-04）：AGENTS.md 要保持「动手前必读」的体量，
> 路线图是**做完一项勾一项的历史台账**，越滚越长，不适合每次都塞进上下文。
> 勾选状态以本文件为准；`AGENTS.md` 只留一行指路。


- [x] **接真实 LLM**：设置 · API 配置 = 真表单（base_url/api_key/model + 测试连接 + 保存到 DataStore）；聊天经 `OpenAiClient` SSE 流式回复，逐 token 渲染；未配置时降级为模拟回复
- [x] 消息持久化（聊天）：发送与回复写入 Room（MessageDao），重开会话恢复记录
- [x] 会话列表预览取真实最后一条消息（`MessageDao.observeLastMessages`，未读点=导师有新回复）
- [x] 任务闭环（除聊天打卡回执）：任务页 Room 化（TasksViewModel）+ 新建弹层扩展（备注/日期/提醒）+ AlarmManager 精确闹钟到点通知 + BootReceiver 重启重排 + 长按删除；聊天里打卡回执未做
- [x] 我的数据页（手动部分）：档案可编辑（ProfileEditSheet 写 Room）+ 编辑结果注入 system prompt；AI 定期提炼 ProfileFact 未做（二期）
- [x] 新增人设 / 人设管理页：设置 · 人设管理 → PersonaManageScreen；会话列表 ＋ 直接新增；预置人设可编辑不可删
- [x] 对话风格按 persona 持久化（Persona.tone 列，重进会话保留）；聊天请求失败不落库 + 错误条重试
- [x] API Key 迁移 Keystore 加密（v0.2.3：AndroidKeyStore AES-256-GCM，DataStore 落密文 `api_key_enc`；旧明文自动迁移删除，Keystore 不可用回退读明文；`data/ApiKeyCipher.kt`）
- [x] 聊天打卡闭环（v0.3.0：任务页勾选完成 → 主导师会话写「✅ 已完成打卡」用户消息进 LLM 历史；聊天页输入栏左键 = 快捷打卡面板，选任务即以消息发进当前会话；ChatViewModel 持续观察 Room，外部写入实时上屏）
- [x] 会话列表真实搜索（v0.3.0：人设名/身份 + 消息内容 LIKE，点击直达会话；搜索框点击进入搜索态。不做 delay 防抖——本地查询毫秒级且 Robolectric PAUSED 时钟下 delay 永不恢复，靠 cancel 旧任务保序）
- [x] 未读点闭环（v0.3.0：DataStore `last_read_<personaId>`，打开会话即已读；未读点 = AI 新回复且晚于已读时刻，带光晕）
- [x] 设置面板五项全部可用（v0.3.0：通知与提醒 = 总开关 + 系统设置入口，TaskReminderReceiver 到点先查开关；数据与隐私 = 四类数据统计 + 清除聊天记录（二次确认）；关于 = 版本信息）
- [x] 数据页完成度接真实任务数据（本周周一起到期任务完成率；习惯/洞察仍为示例并已标注「示例数据」）
- [x] 任务通知点击直达任务页（v0.3.0：deep link `daoshi://tasks`；personas/data/chat 亦注册 deep link）
- [x] 单聊页底栏遮挡输入栏修复（v0.3.0：push 层级页隐藏底栏 + AdaptiveScreen 统一 navigationBarsPadding）
- [x] 种子数据并发竞态修复（v0.3.0：多收集器同时首订导致种子重复插入、列表出双行；改 Mutex + 一次性 + COUNT 判空）
- [x] AI 气泡轻量 Markdown 渲染（v0.5.0：`ui/common/MarkdownText.kt` 粗体/行内码/标题/列表/代码栅栏，无新依赖；行分类与栅栏切分为纯函数 + 单测）
- [x] 通知「10 分钟后再提醒」action（v0.5.0：TaskSnoozeReceiver 取消通知并重排闹钟）
- [x] 数据导出 JSON（v0.5.0：数据与隐私面板 → 全量导出走系统分享，密钥不含）
- [x] 数据导入 JSON 合并（v0.8.0：GetContent 选文件 → 人设/档案/洞察同名跳过、消息按人设名映射追加、任务追加，绝不删除现有数据；行内结果反馈）
- [x] 手动提炼统计反馈（v0.8.0：ExtractionEngine.ExtractSummary，数据页内联显示"新洞察 X 条 · 新档案 Y 项"，4 秒自动消失）
- [x] 气泡长按复制 + 会话草稿保留（v0.5.0：ChatDrafts 进程内存级，切走再回输入还在）+ 数据页完成度附上周对比
- [x] 会话置顶（v0.6.0：Room v5 personas.pinned + 长按会话行切换 + 📌 标识；编辑人设透传 pinned）
- [x] 测试基建（v0.5.1：OpenAiClientTest 用 MockWebServer 验证 SSE 契约；ExtractionEngineTest 集成测试——提炼落库/去重/水位线；共 30 个测试）
- [x] 任务编辑（v0.7.0：长按任务行打开预填弹层，改标题/备注/日期/提醒并重排闹钟；删除入口在编辑弹层内，仍走二次确认）
- [x] 消息长按菜单（v0.9.0：复制全文/删除单条；删除同步移出 LLM 历史；种子消息仅复制，流式期间不可删；MIcons 新增 Copy/Delete）
- [x] AI 提炼流水线（v0.4.0：`data/ExtractionEngine.kt`——聊天回复成功落库后检查水位线，攒够 8 条增量消息自动调 LLM（复用 OpenAiClient）；产出 ProfileFact（按 key 覆盖，manual 来源不被篡改）+ AiInsight（标题去重、留 20 条）；解析器 `ProfileExtractor` 纯函数 + JVM 单测；数据页洞察区有手动「提炼」入口（API 未配置时隐藏））
- [x] 数据页习惯/洞察接真实数据（v0.4.0：ai_insights 表（Room v4 + MIGRATION_3_4）驱动洞察卡，空时回退示例并标注「示例数据」；习惯卡改为「近 7 天打卡」每日完成热力（真数据）；档案条目 source=ai 带小徽标）
- [x] 启动图标 + 底栏修正 + 数据页极简化 + 微信式会话删除（v0.10.0–v0.11.2：黑底白气泡启动图标；底栏 clickable 必须 indication=null；会话长按菜单=置顶/删除会话，删除=清空消息+personas.hidden（Room v6 + MIGRATION_5_6），重开/新消息即恢复；数据页移除全部解释性文字与示例数据，编辑入口只留基础档案栏）
- [x] 数据页区块头统一 + 全局清理解释性文字（v0.11.3：SectionHeader(title, actionText, onAction) 私有组件，基础档案/近 7 天打卡/AI 洞察三处同一规格（标题 13sp SemiBold 黑、动作 13sp Medium、基线对齐）；移除聊天/任务/人设/设置面板的解释与引导文案，空状态只留事实短语）
- [x] 任务双板块：每日任务 / 基础任务（v0.12.0：Room v7 repeatDaily/completionDate + task_completions 表 + MIGRATION_6_7；新建弹层「每日重复」开关；每日重复闹钟 + BootReceiver 跨天重置；周完成度只算基础任务、打卡热力只算每日任务）
- [x] AI 自动建议任务（v0.12.0：提炼 prompt 产出 taskSuggestions → TaskSuggestionHub 分发 → 聊天页 AI 气泡下方建议卡「添加任务」一键落库；同 title 当日去重 + 同名任务过滤；解析容错/去重/集成测试 + 建议卡截图）
- [x] 提炼接入任务数据 + 洞察转任务（v0.12.0：prompt 注入近 7 天任务完成情况；洞察卡长按「转为任务」）
- [x] 人设可删除（v0.12.0：取消预置不可删限制；物理删除级联清理消息/未读键/水位线；管理页卡片长按与编辑弹层删除均走二次确认；删光后会话列表虚线框「＋ 新建人设」空状态；设置面板「恢复预置人设」= 空表重播种子）
- [x] 人设头像渐变环统一（v0.12.0：PersonaEditorSheet 预览改 personaBrush(id) 环，与会话列表/聊天顶栏一致；AboutSheet 为 App 徽标保留 Hero 环）
- [x] 任务逾期状态（v0.13.0：基础任务过期未完成 = 卡片红色左边线 +「已逾期」红字标签；逾期分组沉底）
- [x] 每日总结通知（v0.14.0：每晚 21:30「今日完成 x/y」，点击直达任务页；通知面板 NeuSwitch 可关；App 启动 + BootReceiver + 到点自排明天三重自愈；summarizeToday 纯函数单测）
- [x] 快捷打卡 7 天圆点（v0.15.0：面板任务行右侧 7 颗小圆点，左→右 = 6 天前→今天，黑=完成；数据源 task_completions）
- [x] 数据页 Sparkline（v0.16.0：完成度卡下 7 天每日任务完成率纯 Canvas 折线（黑 1.6dp 圆帽 + 末端圆点），无每日任务不渲染）
- [x] 单聊消息搜索（v0.17.0：顶栏搜索图标 → 覆盖层搜索态，会话内 LIKE 时间倒序；ChatScreen 外层包 Box 修覆盖层 0 高问题）
- [x] 导出 Markdown 周报（v0.18.0：数据与隐私面板 → 本周基础任务 x/y + 每日打卡分列 + 档案更新（AI 标注）+ 洞察 20 条，系统分享）
- [x] 今日任务小组件（v0.19.0：Glance 黑底白字最多 3 条，点击直达 daoshi://tasks；刷新 = 系统 30min + App 启动 + 任务增删改勾选；glance-appwidget 1.1.1）
- [x] 动效打磨（v0.19.1：任务行弹簧重排 placementSpec；热力点 40ms 级联浮现；洞察卡 EaseOutExpo 340ms 进场）
- [x] 测试加固（E1/E2：RetryOnFailure 整测重试兜底 IO×虚拟时间卡死；DailySummaryTest / TasksDailyResetTest / ChatListViewModelTest（hidden 过滤 + 物理删除级联）等，总数只增不减）
- [x] 手机 Agent MVP（v0.20.0：`agent/` 六件套——AgentAccessibilityService（感知+手势+悬浮停止）、ScreenState（控件编号清单，纯函数单测）、AgentPrompts（四段式 prompt + 宽容 JSON 动作解析）、AgentLoop（观察→LLM→执行→等界面稳定，maxSteps=15）、ActionExecutor（tap/swipe/setText/back/home）、AgentConfig（白名单围栏：空=不限制，本应用永远放行）；安全围栏 = 白名单 + 敏感词确认（支付/删除/发送）+ 悬浮停止 + 步数上限；入口 = 任务页顶栏 ▶，执行摘要可写入主导师会话；`res/xml/agent_accessibility_config.xml` + Manifest BIND_ACCESSIBILITY_SERVICE）
- [x] 修复 API 对话「null 乱码 + 不符人设」（v0.20.1：根因 = OpenAiClient 用 optString 读 delta.content，推理模型（deepseek-reasoner/GLM-4.5/qwen-thinking）思考阶段发 `"content": null`，org.json 读成字面量 "null" 拼进回复且落库污染 LLM 历史；修复 = 只接受 String 类型 content + ChatViewModel init 一次性清洗历史乱码（纯乱码 AI 消息删除、前缀剥掉，用户消息永不改）+ OpenAiClientTest 回归测试；顺带：无人设（systemPrompt 空）= 普通 AI，不叠 tone，用中性助手提示词；提炼/洞察/任务建议本就独立于 persona（ProfileExtractor.SYSTEM_PROMPT），无改动）
- [x] 「我的」页 + 旧入口清理（v0.21.0：第 4 个 tab `ui/profile/MeScreen.kt`（人设管理/基础档案/设置三行卡，类微信「我」）；设置链（API/通知/隐私/关于）与档案编辑弹层从会话列表页迁入（MeViewModel 承接）；ChatListScreen 顶栏删掉 ＋ 新增人设与滑杆 设置两颗按钮，只留标题与搜索；设置面板删「恢复预置人设」行；deep link `daoshi://me`）
- [x] 顶级四 tab 横滑切页（v0.21.0：`HorizontalPager` 承载消息/数据/任务/我的，`beyondViewportPageCount=3` 保 4 页存活不丢滚动位置；底栏 `HomeBottomBar` 胶囊与 Pager 双向联动（点按 animateScrollToPage 弹簧、拖动跨半页滑移）；NavHost 收敛为 home + chat/{personaId} + personas，push 页过渡不变；通知/小组件 deep link 改 `daoshi://open/{page}`（tasks/data/me 直达对应 tab））
- [x] 平板自适应真适配（v0.21.0：`material3-window-size-class` 的 `calculateWindowSizeClass` → `LocalWindowWidthClass` 唯一判源（>=600dp）；消息页 `AdaptiveSplitScreen` 双栏——左列表 360dp + 发丝线 + 右单聊内嵌（ChatScreen showBack=false，点行切会话不 push，选中行灰底，未选中显示「未选择会话」占位），底栏保持显示；数据/任务/我的版心放大到 840dp 且两列网格——数据=档案|打卡并排+洞察两列、任务=LazyVerticalGrid 分组头 GridItemSpan 跨列、我的=行卡两列；手机 <600dp 一个像素没动，UiRenderTabletTest（w900dp）四张截图验证）
- [x] 数据页信息极简（v0.21.0：删「基础档案」「近 7 天打卡」区块标题与热力「6 天前 → 今天」轴标签，档案卡编辑入口整体移除（→我的页），延续 v0.11.3 只留事实短语原则；字体规格统一走 SectionHeader 既有规格）
- [x] AI 周报（v0.21.0：Room v8 `ai_weekly_reports`（weekStart 唯一索引 + MIGRATION_7_8）+ `data/WeeklyReportGenerator.kt`——WeeklyReportMath 纯函数（周区间/统计聚合/prompt/宽容解析/聊天摘要截断，JVM 单测）+ OpenAiClient 生成 200–400 字中性周报，同周覆盖；每周日 21:30 `WeeklyReportReceiver`（AlarmScheduler.scheduleWeeklyReport 对齐下周日 + 自排）+ App 启动（MainActivity）/ BootReceiver 查上周缺报静默补生成；旧「导出周报（Markdown）」入口删除；数据页新增「AI 周报」板块（最新一期卡 + 手动「生成」/历史切换弹层，无周报时整个隐藏）；测试 59→63 个）
- [x] 修复：启动周报补生成与提炼集成测试抢 MockWebServer 响应（v0.21.0 过程中：MentorApp 启动协程读到测试保存的 API 配置后会抢先消费 mock 响应，提炼引擎等满 120s 读超时；修复 = 补生成从 Application.onCreate 移到 MainActivity.onCreate（无界面阶段不发 LLM 请求））
- [x] 数据自主权 + 周报完善（v0.22.0：导出/导入 JSON 抽成 `data/DataBackup.kt` 纯函数编解码器（DataBackupTest 往返/容错单测），并补上 `aiWeeklyReports` 段（导入按 weekStart 去重追加）；周报卡长按系统分享（WeeklyReportMath.shareText）；「通知与提醒」新增「AI 周报生成提醒」开关（默认关，SettingsRepository.weeklyReportNotifyEnabled），WeeklyReportReceiver 生成成功后按开关发通知点击直达数据页；今日任务小组件标题行右侧加「已完成 x/y」进度（口径复用 DailySummaryReceiver.summarizeToday）；测试 63→67 个）
- [x] 修复：UiRenderTest 种子竞态（v0.22.0 过程中：seedIfEmpty 异步播种，render5/render7 在种子就绪前 setPinned(2L) 落空表被吞、断言偶发失败（v0.21 Pager 让启动变重后暴露率约 50%）；修复 = 测试体先 awaitText("阿布") 等种子上屏再写 DB，awaitText 换 waitForIdle 泵轮询）
- [x] 任务筛选 + emoji 快选 + 底栏未读点（v0.23.0：任务页「隐藏已完成」NeuChip 筛选（视图状态不落库，每日/今日/明天/以后/逾期五组联动过滤，全空显示「全部完成」）；人设编辑弹层 emoji 横滑快选行（12 预设 NeuChip + 自定义输入保留）；底栏「消息」tab 未读黑点——`data/UnreadCenter.kt` 应用级计数器（UnreadLogic 纯函数口径 = 会话列表 showDot：hidden 无消息不计、最后一条来自导师且晚于已读时刻，UnreadLogicTest 4 测）；测试 67→71 个）
- [x] 周报生成体验 + 小组件直接打卡（v0.24.0：`data/TaskActions.kt` 抽取 toggle 全套语义（翻转/完成记录/闹钟/打卡回执/刷小组件），TasksViewModel 委托；小组件任务行圆圈 = Glance actionRunCallback 直接打卡（ActionParameters.Key 注意不能用裸 String）；周报板块在「无周报但 API 已配置」时也显示（暂无周报空态 + 生成入口），否则第一份周报没有入口；DataStats 加周报期数；TaskActionsToggleTest；测试 71→73 个）
- [x] 自助配置减负 + 分享补全（v0.25.0：API 配置页「常用服务商」预设 chips（DeepSeek/智谱/通义/Moonshot/OpenAI，点击填 baseUrl，model 仅留空时回填）；人设编辑弹层对话风格 NeuChip 选择（温和陪伴/严格教练/苏格拉底反问，与长按头像同一开关）；周报 prompt 注入上期周报要点（前 200 字，连续数周有承接感）；洞察卡长按菜单加「分享」；测试 73→74 个）
- [x] 会话导出 + 权限补全（v0.26.0：长按会话菜单「导出记录」= 单会话 Markdown 系统分享（`data/ConversationExport.kt` 纯函数 + 单测，逐行引用块排版）；「AI 周报生成提醒」开关打开时申请 POST_NOTIFICATIONS 权限（Android 13+，拒绝则开关落回）；Room 迁移测试在 Robolectric 不可行（MigrationTestHelper 的 assets 走 instrumentation context，单测不合并）已验证并放弃；测试 74→76 个）
- [x] 打磨轮（v0.27.0：周报历史行支持长按分享该期（NeuSheetRow 增加可选 onLongClick，combinedClickable）；会话长按菜单加「标记已读」（仅未读行显示，settingsRepository.markChatRead）；手动生成周报成功后自动选中新报告展示；测试 76 个）
- [x] 聊天草稿落盘（v0.28.0：SettingsRepository.chatDraft/setChatDraft（draft_<personaId>）+ ChatViewModel 600ms 去抖写 + init 恢复（仅内存缓存为空时应用）；周报卡右侧改「M月d日生成」；洞察区空态「暂无洞察」白卡）
- [x] 打卡连续天数（v0.29.0：`habitStreak` 纯函数（宽恕规则：今天没打卡从昨天起数，HabitStreakTest 5 测），打卡卡备注行显示「连续 N 天」）
- [x] 导出文件化（v0.30.0：`data/ShareFiles.kt` + FileProvider（manifest provider + res/xml/file_paths.xml，cache/exports）——会话记录 .md / 全量数据 .json / AI 周报 .md 均以真实文件系统分享，不再是大段文本；周报空态/生成入口回归测试（render12，settingsStore 单例测后清理））
- [x] 用户反馈九连改（v0.31.0：① 会话长按菜单锚定行内右上角（DropdownMenu align TopEnd）；② 取消洞察卡分享与周报历史行长按分享（保留周报卡长按分享与会话导出记录）；③ **取消全部示例种子**——新用户人设/档案/消息全空，仅预置内置「应用助手」🤖（使用指南会话，PersonaRepository 单种子 + data/AiPrompts.kt 默认提示词 + ChatViewModel 种子消息删除）；④ 数据页删「本周目标完成度」进度环卡与 Sparkline（WeekProgress/weekProgress/weekRates 全删）；⑤ 任务页「隐藏已完成」从筛选行改为右上角眼睛按钮（MIcons.EyeOff，选中黑底白图标）；⑥ 任务行长按灰色涟漪消除（combinedClickable 补 indication=null）；⑦ 档案注入措辞改「仅供背景参考、禁止主动复述」（AiPrompts.DEFAULT_PROFILE_PREAMBLE）；⑧ 设置新增「AI 提示词」面板（PromptSettingsSheet：应用助手/基础/档案注入三段可编辑 + 恢复默认；base_prompt/profile_preamble 存 DataStore，应用助手提示词写回人设行）；⑨ NeuSheetScaffold 弹层 skipPartiallyExpanded + 内容 verticalScroll——全部上拉抽屉一次展开到位、超长内容可滚；测试夹具重构（UiRenderTest/TabletTest @Before 显式建人设与档案、ChatListViewModelTest 先插后订）；测试 81 个）
- [x] 用户反馈七连改（v0.32.0：① 长按菜单真正锚到条目右下角——DropdownMenu 的 Popup 锚定其**直接父节点**，align/offset 加在菜单上无效，须在右下角放小锚点 Box 包住菜单（会话行 + 洞察卡两处）；② 恢复数据页「本周目标完成度」圆环卡（weekProgress/WeekProgress 回归），长条折线 Sparkline 保持删除；③ 任务页「＋ 新建」被顶栏默认槽宽裁掉——NeuTopBar 增 actionsWidth 参数，三按钮页传 150dp；④ 从人设管理返回不再跳回消息页——home 的初始 tab 对齐只在 deep link（daoshi://open/{page}）时执行；⑤ **对话风格（温和陪伴/严格教练/苏格拉底反问）整体删除**：长按头像面板、编辑器 chips、ChatViewModel tone 状态与 prompt 注入全删，单聊副标题只显示身份，personas.tone 列保留不用；⑥ 设置 · 数据与隐私新增「一键重置（清空全部数据与设置）」：逐任务取消闹钟 → clearAllTables → DataStore 全清 → 重新播应用助手 → 刷小组件（二次确认对话框，建议先导出备份）；⑦ 应用助手提示词全面重写为详细使用手册（概览/四 tab/设置项/自动化/数据隐私/回答要求六节），启动时 ensureAssistant 缺失补建 + refreshAssistantPromptIfNeeded 旧版默认文案自动升级（用户改过的不动）；ChatListViewModelTest/ExtractionEngineTest 按名字定位人设以消除补建竞态；测试 82 个）
- [x] 聊天日期去条 + 头像预设扩充 + 设定词规范（v0.33.0：① 单聊日期头去掉灰底胶囊改纯文字（原胶囊在文字下露出灰条，被当成"线"，`neuSurface` 移出）；② 头像 emoji 快选 12→28 个（覆盖教师/书籍/健身/自然等常见人设类型）；③ 人设编辑器加「查看写作规范」（应用内规范弹层：设定词是什么/五要素/写作要点/两个完整示例）与「插入模板」（模板骨架写入，非空时二次确认覆盖）；④ 修两处 Compose 坑：ModalBottomSheet 打开时 `onRoot()` 会撞多 root（snap 取最后一个 root）、NeuSheetScaffold 内容区已可滚时弹层内不能再套 verticalScroll（无限高度崩溃）；测试 83 个）
- [x] 数据纯净度 + 聊天手感（v0.34.0：① 发送消息后自动收起键盘（LocalSoftwareKeyboardController.hide，不清焦点）；② **提炼宁缺毋滥**——ProfileExtractor prompt 规则重写（facts 只收长期画像、insights 最多 1 条且需明确重复模式/数据支撑、taskSuggestions 最多 1 条且需用户明确行动意愿），parse 硬上限同步收紧 take(1)；③ 内置「应用助手」排除出提炼（extractAll 过滤 + runExtract 早退，手动提炼也不碰）与周报转录（buildTranscript 增 excludeConversationIds 参数，生成器传助手会话 id），"怎么用 App"的对话不再污染档案/洞察/任务建议/周报；测试 83→87 个）
- [x] 打卡连续天数（v0.29.0：`habitStreak` 纯函数（宽恕规则：今天没打卡从昨天起数，HabitStreakTest 5 测），打卡卡备注行显示「连续 N 天」；洞察区空态白卡「暂无洞察」）
- [x] 导出文件化（v0.30.0：`data/ShareFiles.kt` + FileProvider（manifest provider + res/xml/file_paths.xml，cache/exports）——会话记录 .md / 全量数据 .json / AI 周报 .md 均以真实文件系统分享（可保存/发送到文件管理器），不再是大段文本；周报卡右侧改「M月d日生成」（替换历史期数计数）；聊天草稿落盘（v0.28.0：draft_<personaId> 键 + 600ms 去抖 + 进程重启恢复）；周报空态/生成入口回归测试（render12，settingsStore 单例测后清理）；测试 76→81 个）
- [x] **暗色模式 + 多接口 + 生图 + 表情包 + 数据页合并（v0.35.0，versionCode 53，一次做完 15 项需求）**：
  ① **暗色模式**：颜色令牌改 `NeuPalette` + `LocalNeuPalette`，浅/深两套色板严格反相；同名的 `@ReadOnlyComposable` getter 保持调用点不变；新增 `AccentInk`（强调色上的文字/图标必须跟着强调色反相）；全 App 40 处裸色值换令牌；`res/values-night` + `values-night-v29`（**注意不是 `values-v29-night`，AGP 报非法目录名**）；`MenghuanThemedRoot` 解析「模式+系统深色」并同步 `SystemBarStyle`；设置 · 外观三选一（`themeMode` 键）
  ② **自动滚底 + 去闪烁**：首帧 `scrollToItem`（原 `animateScrollToItem` 在长会话里是肉眼可见的横冲直撞）；会话列表同样处理
  ③ **搜索框滚动联动**：顶部时顶栏下方凹陷搜索行，上滑离开后换成顶栏放大镜（无圆圈）
  ④ **人设渐变环随机化**：`personaPaletteOf(id)` 稳定散列 → 12 组配色 × 2 走向（重启不变）
  ⑤ **数据页合并**：`WeekCard` = 打卡热力 + 完成度环置顶一张卡；洞察单列；档案移出数据页（只在「我的」页）
  ⑥ **洞察/周报可折叠**：`SectionHeader` 加 trailing 箭头；**取消「AI 洞察转任务」**（菜单与 `convertInsightToTask` 全删）
  ⑦ **多接口**：`ApiProfiles.kt`（Codec 纯函数）；老单接口读时虚拟成「默认接口」零操作升级；长按头像切换；API 配置面板改为列表 + 内联编辑
  ⑧ **生图**：`[生图:…]` 标记协议（不用 function calling，各家兼容端点支持不一致）+ `ImageGenerationClient` 落 `filesDir/images`；`messages.imagePath`（Room v9）；`ImageProbe` 探活
  ⑨ **表情包**：`EmojiPack` 系统 emoji 面板（分组横滑，点一下直接发）；`isPureEmoji` 为真整条渲染 44sp
  ⑩ **写作规范补充**：「写进设定词：AI 能替你做的事」（发任务/打卡督促/操作手机/生图/周报洞察/记住你）+ 授权写法示例
  ⑪ **提示词可见化**：设置 · AI 提示词面板新增只读 `PromptViewer`（洞察提炼提示词 / 周报提示词 / 生图触发协议，各带复制）+ `insightLogicText`/`reportLogicText` 生成逻辑说明
  ⑫ 应用助手提示词升级到 v0.35.0 并登记 v0.34 旧文案进 `LEGACY_ASSISTANT_PROMPTS` 自动升版
  ⑬ 新增 `V035LogicTest` 纯逻辑测；测试 87→106（表达式包那组在 v0.35.1 随功能取消删掉）
- [x] **v0.35.1 收尾修正（versionCode 54，纯交互/UI 调整，patch 版）**：
  ① 人设编辑器：删「自定义」按钮，改为**直接点头像**触发选图；渐变环由 `personaBrush(id)` 恒定绘制，换图不掉环；删「头像 emoji」输入框与右侧提示文字（`NeuAvatar` 加了 `modifier` 参数供调用方加点击热区，调用方需自行 `clip(CircleShape)`）
  ② 设置：主题三选一从面板底部收进**「外观」子弹层**（新增 `AppearanceSheet`，`SettingsSheet` 的 `onAppearance` 由 MeScreen 接线）；面板底部那排快捷 chips 删除
  ③ AI 周报改为**卡片内折叠**（`WeeklyReportCard` 收 `expanded`/`onToggleExpand`）：收起留 2 行预览（`COLLAPSED_PREVIEW_LINES`）+「展开全文」，板块头不再带折叠箭头，默认收起
  ④ **取消表情包**（需求作废）：删 `data/EmojiPack.kt`、`ChatComponents.EmojiPanel`、`MIcons.Smile`、输入栏笑脸按钮与 3 个 emoji 测试；`MessageBubble` 恢复只认 `"❤️"` 的分支
- [x] **自定义 API 网络兼容修复（v0.35.2，versionCode 55，纯修复）**：
  ① 用户填 `http://` 端点报 `CLEARTEXT communication to xxx not permitted by network security policy`；
     填 `https://` 遇到证书 SAN 与访问域名不匹配的服务商报 `Hostname xxx not verified`
  ② **新增 `res/xml/network_security_config.xml`** 并在 Manifest 挂 `android:networkSecurityConfig`：
     `base-config cleartextTrafficPermitted="true"` 全局放开明文；`trust-anchors` 同时信任 `system` 与 `user` CA
     （用户可自装 CA 兼容自签端点，比代码里 trust-all 干净）
  ③ **新增 `llm/NetworkStack.kt`**：`baseBuilder()` 统一注入 `PermissiveHostnameVerifier`
     （只跳过"域名 vs 证书 CN/SAN"匹配，**证书链仍走系统 TrustManager**，MITM/假证书仍被拒）
  ④ **三处 OkHttpClient 全部改走 `NetworkStack.baseBuilder()`**：`OpenAiClient`（对话/SSE）、
     `ImageGenerationClient`（生图 + 图片下载）、`ImageProbe`（生图探活）——避免"聊天能连通、测试连接失败"这种配置漂移
  ⑤ **明确不做 trust-all TrustManager**：那会让任何公共 WiFi 中间人都能截走 `Authorization: Bearer <apiKey>`
     明文头，破坏 App"API Key 走 Keystore 加密"的安全承诺；`network_security_config.xml` 里
     `certificates src="user"` 是遇到自签端点时的正确解法（装 CA 到设备，不动代码）
- [x] **修复「测试生图接口」稳定报"网络错误"（v0.35.3，versionCode 56，纯修复）**：
  ① **根因**：`ApiConfigSheet` 用 `rememberCoroutineScope()`（Main 线程）调 `ImageProbe.probe()`，
     而 `probe()` 内部是阻塞式 `http.newCall(...).execute()` **没有 `withContext(Dispatchers.IO)`**——
     Android 抛 `NetworkOnMainThreadException`，该异常 `message` 为 null，被 `?: "网络错误"` 兜底吞掉真错误。
     同项目里 `OpenAiClient.testConnection` 与 `ImageGenerationClient.generate` 都做了 IO 切换，唯独 `ImageProbe` 漏了
  ② **修复**：`ImageProbe.probe()` 加 `withContext(Dispatchers.IO)`（suspend 函数内部切主线程外的调度器是硬约束）
  ③ **顺带诊断力升级**：`NetworkStack.describeNetworkFailure(t)` 统一异常翻译——始终带 `t.javaClass.simpleName`，
     message 非空时前置。将来再遇到 message 为 null 的异常不会被糊成"网络错误"四个字，
     用户和作者都能一眼看出是超时/DNS/SSL/线程错配。`OpenAiClient.testConnection` 与 `ImageProbe.probe` 共用
- [x] **应用改名「梦欢」+ AI 陪伴化 + 提示词分层可编 + AI 优化人设（v0.36.0，versionCode 60，minor）**：
  ① **全层重命名**：Android `applicationId` `com.daoshi.app` → `com.menghuan.app`；Kotlin 包路径同步（56 个源文件 + 19 个测试文件的 `package`/`import`）；`MentorApp` → `MenghuanApp`；`MentorThemedRoot` → `MenghuanThemedRoot`；`Theme.Mentor` → `Theme.Menghuan`；deep link scheme `daoshi://` → `menghuan://`（`MainActivity` / `TodayWidget` / `TaskReminderReceiver` 三处）；Room DB 文件名 `daoshi.db` → `menghuan.db`；Keystore 别名 `daoshi_api_key` → `menghuan_api_key`；导出文件名 `daoshi-export-*` → `menghuan-export-*`；`strings.xml app_name` 与无障碍服务 label 改「梦欢」；APK 归档命名 `梦欢-vX.Y.Z-release.apk`。**老用户视为新 App**（Android 沙箱按包名隔离），需 JSON 导出/导入迁移，API Key 必须重填（Keystore 与包名绑定）
  ② **应用定位改 AI 陪伴**：`AiPrompts.DEFAULT_ASSISTANT_PROMPT` 全文重写——应用概览从「AI 导师应用」改为「AI 陪伴应用」，措辞从"督促指导"改"陪伴支持"，风格由用户创建的人设决定；`DEFAULT_BASE_PROMPT` 从"乐于助人的 AI 助手"改"AI 陪伴伙伴，风格由具体人设决定"；`ASSISTANT_PERSONA_TITLE` 从「使用指南 · 答疑」改「应用向导 · 人设助手」；关于面板 (`MoreSheets.AboutSheet`) 标题与副标题重写。**任务标签**：AI 建议落库的任务从「导师安排」改「AI 建议」（跟"AI 洞察"术语一致）。**对话角色标签**：提炼/周报转录里 AI 一方从「导师」改「AI」（`ExtractionEngine` / `WeeklyReportMath.buildTranscript`）
  ③ **人设适配协议（应用助手新增能力）**：`DEFAULT_ASSISTANT_PROMPT` 新增章节——用户丢一段自由描述（例："温柔的姐姐，会关心我的作息"），助手输出结构化【名字】【身份】【设定词】三件套。老 v0.35.x 用户的内置助手 systemPrompt 通过 `LEGACY_ASSISTANT_PROMPT_V035`（新增）自动升级到 v0.36.0 版本，v0.34/v0.31 的历史检测链保留
  ④ **AI 洞察数量松绑**：`ProfileExtractor` prompt 去掉「最多 1 条」措辞，改为「根据信息价值自主决定条数（宁少勿滥，可以是 0 条）」；代码 `insights.take(1)` → `take(MAX_INSIGHTS=10)`、`suggestions.take(1)` → `take(MAX_SUGGESTIONS=5)` 作**软防灾上限**（正常输出 0~3 条不受影响，仅防模型失控吐几十条撑爆 UI）
  ⑤ **提示词分层可编（框架硬编码 + 策略可编）**：`ProfileExtractor` / `WeeklyReportMath` / `ChatViewModel.IMAGE_HINT` 三处 prompt 拆为 `FRAMEWORK`（硬编码不可改：JSON schema、输出格式约束、`[生图:...]` 标记协议——改这些会让 parse/findPromptIn 直接崩）+ `DEFAULT_STRATEGY`（用户可编：宁缺毋滥尺度、字数语气、触发时机）；`systemPrompt(strategy)` / `imageHint(strategy)` 拼接两者。`SettingsRepository` 加三个 DataStore 键（`extraction_strategy` / `weekly_report_strategy` / `image_hint_strategy`）+ Flow + setter，与默认一致时不落盘。`ExtractionEngine` / `WeeklyReportGenerator` / `ChatViewModel` 三处调用点读 strategy 后传给对应函数
  ⑥ **`PromptSettingsSheet` UI 重构**：从"3 段可编 + 3 段只读+复制按钮"扩展为**六段全部可编**——前三段完整可编（应用助手 / 基础 / 档案注入），后三段用新 `LayeredPromptSection` 组件（上=框架只读凹陷面展示，下=策略可编 TextField + 「本段恢复默认」按钮）；全局「全部恢复默认」按钮保留；**删除所有「复制提示词」按钮**（`NeuTextButton("复制提示词", ...)` 及其 `ClipboardManager` / `ClipData` / `Context` imports），策略既然可编，输入框自身就支持复制粘贴
  ⑦ **人设编辑页「AI 优化设定词」按钮**：新建 `data/PersonaRewriter.kt`（硬编码 JSON schema 改写 prompt + 宽容 parse：截取首个 `{` 到末个 `}`）+ `ui/persona/AiPersonaOptimizeSheet.kt`（子面板：输入描述 → 调当前 active profile 的对话接口 → 三件套结果预览 → 「使用这个」一键填入 name/title/prompt，emoji 有建议就一并覆盖）。`PersonaEditorSheet` 在「查看写作规范 / 插入模板」按钮行加第三颗「AI 优化设定词」，`showAiOptimize` 状态控制子面板显隐。**为什么不复用应用助手**：应用助手输出三行文本用户得手动复制，这里要求 LLM 输出严格 JSON 代码 parse 后直接填表单，体验更顺；两个入口指向同一能力
  ⑧ **人设身份示例拓宽**：`PersonaEditorSheet` 身份输入框 placeholder 从「例：人生导师 / 健身教练」改「例：朋友 / 陪伴者 / 教练 / 心理咨询师」，反映应用不再预设导师定位
  ⑨ **测试**：从 105 → **107**——`ProfileExtractorParseTest` 新增 `insights 超过软上限 10 条被裁到 10 条` 与 `taskSuggestions 超过软上限 5 条被裁到 5 条`；旧的"最多 1 条"断言改为"软上限内全部保留"；`ConversationExportTest` / `WeeklyReportGeneratorTest` 里 3 处「导师」字符串断言跟随运行时改名同步（`由「梦欢」App 导出` / `阿布（AI）` / `【梦欢 · AI 周报】`）
- [x] **任务提醒简化 + API 配置 UI 统一（v0.36.1，versionCode 61，patch）**：
  ① **任务编辑弹层提醒行**：去掉「不提醒」文本显示，未设时右侧只留一个细箭头 `›` 作可点提示（不点就是不提醒，语义自明）；整行去掉 Material 默认 ripple（那个方形灰色框），改用 `clickable(interactionSource, indication = null)` 保持面板整体的凹陷拟物观感；已设时依旧显示 `HH:mm` + `×` 清除按钮，× 也去掉 ripple
  ② **API 配置 UI 统一**：对话接口和生图接口共用同一套「摘要行 + 点开才展开表单」交互。生图接口未配置时只显示「＋ 配置生图接口」按钮，已配置时显示 `NeuSheetRow(ImageIcon, "生图接口", "模型 · baseUrl")` + 编辑/清除；点开后才是完整表单（预设 chips + 三个输入 + 测试/保存/收起）。对话接口原本就是这个模式，无需改
  ③ **两组预设 chips 尾部加「自定义」**：新建 `PresetChipsRow` 共用组件，`isCustom = baseUrl 非空且不匹配任何预设`；点击时若当前是预设态则清空 baseUrl+model 让用户从头填，已是自定义态则不动（避免误清用户辛苦填的自定义 URL）。对话接口和生图接口共用此组件，保证两处 UI 一致
  ④ **删除信息噪音**：section header 从「对话接口（可配置多条）」/「生图接口（AI 可生成图片）」简化为「对话接口」/「生图接口」；生图区底部「AI 觉得需要配图时会在回复里发起生图请求，图片只存本机」的小字说明删除——用户既然进了这个面板就已经知道要干什么
  ⑤ **顺带修复**：生图接口测试连接的异常翻译也走 `NetworkStack.describeNetworkFailure`（原来是 `it.message ?: "网络错误"`），与 v0.35.3 对话接口 testConnection 保持一致
- [x] **修复生图接口「测试连接」稳定 HTTP 400（v0.36.2，versionCode 62，纯修复）**：
  ① **根因**：`ImageProbe.probe()` 发的请求体 `{"prompt":"a dot","n":1,"size":"256x256"}` 三处都不合法——(a) 没带 `model` 字段，SiliconFlow / 智谱 CogView / 豆包 Seedream / gpt-image-1 全部强制要求 model，缺了直接 400；(b) `size = "256x256"` 是老 DALL-E 2 尺寸，主流服务商基本不支持（gpt-image-1 只吃 1024x1024/1536x1024/1024x1536/auto；cogview-3 只吃 1024x1024 等几种）；(c) HTTP 400 时 `else -> "HTTP 400"` 直接扔掉响应体，服务商明明说了具体原因（"model is required" / "size not supported"）全被吞成三个字
  ② **修复**：`ImageProbe` 加 `model: String` 构造参数；请求体改为 `{model, prompt:"a dot", n:1, size:"1024x1024"}`（1024x1024 是业内支持最广的尺寸）；`ApiConfigSheet` 调用点传 `imgModel`，未填模型时前端拦截并给可读提示；HTTP 4xx 一律尝试解析 body 里的 `error.message`（兼容 OpenAI 风格 `{"error":{"message":...}}` / 扁平 `{"message":...}` / 纯文本三种形态），拼进返回值。现在报错形如 `连接失败 · HTTP 400：size 256x256 is not supported, please use one of [1024x1024, ...]`，用户能直接看出哪个字段不对
  ③ **注意**：新 probe 请求结构合法，理论上鉴权通过的服务商可能真跑一次生图消耗额度；但绝大多数服务商在鉴权前会先做参数校验，实际不会真跑，且只在用户主动点「测试连接」时触发
- [x] **修复生图接口 SSLHandshakeException: connection closed（v0.36.3，versionCode 63，纯修复）**：
  ① **诊断结论**（桌面 openssl/curl 实测）：zhiqiapi.com（new-api 中转）证书链完整有效（LE YR2 → Root YR → ISRG Root X1，verify 0）、SAN 正规通配符、TLS1.2/1.3 都通、ALPN h2/http1.1 都通、curl 带 Key 请求 `/v1/models` 直接 HTTP 200——**桌面全绿，仅 Android Conscrypt 握手被服务端掐断**。`connection closed` 措辞 = 对端主动关连接（服务端不接受 Android ClientHello 的 TLS1.3/扩展组合），**不是证书信任问题**，所以 trust-all 修不好它，v0.36.2 的 HTTP 层修复也够不着它
  ② **修复**：`NetworkStack` 新增 `COMPAT_CONNECTION_SPEC`（只允许 TLS 1.2）+ `compatClient()`（叠加降级规格 + 强制 http/1.1 去掉 h2 ALPN）+ 顶层扩展 `OkHttpClient.callWithTlsFallback(request, handler)`：默认规格握手抛 `SSLHandshakeException` 时自动用降级 client 重试一次。**只调握手协议参数，不放松证书信任/域名校验**；握手失败意味着请求字节没发出去，重试不会重复消耗生图额度
  ③ **接入点**：`ImageProbe.probe()`、`ImageGenerationClient.generate()`、`ImageGenerationClient.download()`（图片可能落在服务商 CDN，同样可能握手不兼容）三处改走 `callWithTlsFallback`。对话接口 `OpenAiClient` 暂不接（用户聊天未报此错，避免扩大影响面）
- [x] **按会话独立选择 AI 接口（v0.36.4，versionCode 64，新功能）**：
  ① **需求**：原来长按头像切接口是**全局**的（改 `api_active_profile`，所有会话下一条消息都跟着变）。用户要求每个会话能独立选不同模型/接口。
  ② **存储**：DataStore 加前缀键 `persona_api_<personaId>` = 该会话的接口覆盖 id（缺失=跟随全局）。**不用 Room 列**，避免数据库迁移；与现有 read mark / watermark / draft 的前缀键风格一致。`PersonaRepository.delete` 级联清理该键。
  ③ **解析**：`SettingsRepository.effectiveProfileId(personaId)` = 会话覆盖优先（且该 id 必须仍存在於 `api_profiles`，被删则回落全局）→ 否则全局 `activeProfileId`；`apiConfigFor(personaId)` 由此映射出 `ApiConfig`。全局 `apiConfig` 保留给周报/Agent/人设改写等无会话上下文的消费方。
  ④ **消费点改按会话**：`ChatViewModel.reply()` 与 `ExtractionEngine.runExtract()` 改读 `apiConfigFor(personaId)`；周报/Agent/PersonaRewriter 仍用全局（它们没有单一会话上下文）。
  ⑤ **UI**：`ChatViewModel` 暴露 `globalProfileId` / `activeProfileId`(=本会话生效) / `apiOverrideId`(可空)；`switchApiProfile` 改为只写本会话覆盖，新增 `followGlobalApi()` 清覆盖。`ApiSwitchSheet` 改名「本会话的 AI 接口」，顶部加「跟随全局（当前：X）」行用于清除覆盖，其余行选中态=本会话生效 id。
  ⑥ **语义澄清**：切换只影响"之后"的消息；历史消息不记录当时模型；生图接口仍独立单条不受此影响。
- [x] **清除全部数据 + 洞察/周报单条删除（v0.36.5，versionCode 65，功能+交互）**：
  ① **需求**：设置里的「清除全部聊天记录」升级为「清除全部数据（保留人设与接口）」——除人设与 API 之外全删；
     AI 洞察与 AI 周报历史某一期都可单条删除（长按弹菜单，点「删除」才删）。填的是"只清聊天"与"一键重置（恢复出厂）"之间的空档。
  ② **删除范围**：messages / ai_insights / ai_weekly_reports / profile_facts / tasks / task_completions 六表全清；
     **保留**：personas、`api_profiles`+全局/生图接口配置、提示词覆盖、主题、通知开关、会话级接口覆盖 `persona_api_*`。
  ③ **级联清理（易漏项，已全做）**：任务闹钟必须在清库**之前**逐个 cancel（清完就枚举不到任务了，会留孤儿闹钟）；
     `SettingsRepository.clearConversationScopedKeys()` 清 `last_read_*` / `extract_upto_*` / `draft_*`
     三类会话级派生键（水位线不清的话下次提炼从已删消息 id 之后起算，会漏提）；
     `TaskSuggestionHub.clear()` 清进程内待展示建议与"当日已建议"状态；最后刷今日任务小组件。
  ④ **实现落点**：`MeViewModel.clearAllData(context)`（与 `resetAll` 并列，后者是恢复出厂）；
     `DataPrivacySheet` 的确认对话框把"将删除"与"保留"两部分都列出来（任务里有用户自建 to-do，必须写明）。
  ⑤ **单条删除**：`DataViewModel.deleteInsight(id)` / `deleteWeeklyReport(id)`（DAO 各加 `deleteById`）；
     `InsightCard` 与 `WeeklyReportHistorySheet` 行都走**长按 → 右下角 DropdownMenu「删除」→ 点菜单项才删**
     （用户明确要求：破坏性操作长按不能直接执行，菜单那一下就是确认；不再另弹 AlertDialog）。
     菜单锚点仍按 §四 的规则包在 `Box(Modifier.align(Alignment.BottomEnd))` 里——洞察卡外层要再套一个
     `Box(Modifier.fillMaxWidth())`，因为 `NeuCard` 的 content 是 `ColumnScope`，`align(BottomEnd)` 在那儿不成立。
     删掉正在展示的周报会自动回落最新一期（`displayed` 的 `firstOrNull{...} ?: firstOrNull()` 已覆盖）。
     **`NeuSheetRow` 因此新增可选 `onLongClick` 参数**（走 `combinedClickable`，indication=null 保持凹陷观感）。
  ⑥ **应用助手手册同步**：数据页长按说明 + 数据与隐私一节改写；v0.36.0 全文登记为
     `AiPrompts.DEFAULT_ASSISTANT_PROMPT_V036` 并挂进 `LEGACY_ASSISTANT_PROMPTS`，未改过的老助手自动升版。
  ⑦ **测试**：`SettingsRepositoryTest.clearConversationScopedKeysKeepsConfig`（派生键清空、配置键存活）；
     顺带修 `UiRenderTest.render15AppearanceSheet` 偶发失败——点击只把 `scope.launch` 排进主线程，
     DataStore 的写落在 IO 线程，`waitForIdle` 泵不到它，读完立刻断言会拿到旧值；改用 `awaitState` 泵轮询。
     **注意 `settingsStore` 是进程级单例，测试写完必须 `edit { it.clear() }`**，否则串到同类其它用例
     （`apiConfig` 优先读 `api_profiles`，残留接口会让 `legacyPlaintextFallback` 读到错的 Key）；
     `UiRenderTest` 新增 render17（清除全部数据端到端：确认框文案 + 六表清空 + 人设/接口存活）与
     render18（洞察 / 周报历史：长按 → 菜单「删除」→ 点菜单项，`performTouchInput { longClick() }` +
     `awaitText("删除")` + `clickText("删除")`；DropdownMenu 是独立 root，`onNodeWithText` 照样能定位）。测试 107→110。
- [x] **对话内 AI 操控手机（v0.37.0，versionCode 66，功能）**：
  ① **触发协议**：与 `[生图:…]` 同构的单行标记 `[操作手机:目标]`——`AgentPrompts.findGoalIn/stripMarker`
     （纯函数；标记独占一行时把换行一并剥掉，不留空行）。能力提示 `AGENT_CHAT_HINT_FRAMEWORK`
     （标记格式硬编码）+ `AGENT_CHAT_DEFAULT_STRATEGY`（策略可编）分层，`agentChatHint()` 拼接，
     与生图 v0.36.0 机制同款。
  ② **能力注入门槛**：`reply()` 里 `agentChatEnabled && AgentAccessibilityService.isRunning()` 才注入
     ——服务不在线不给模型这个能力，提了标记也执行不了的事不做。
  ③ **完全权限（用户拍板）**：无确认卡、`autoConfirm=true` 让执行中敏感动作不打断；
     留守围栏 = 白名单（沿用 `AgentConfig`，默认不限制）+ 悬浮停止 + 15 步上限。
  ④ **接口跟随本会话**：`maybeRunAgent` 传 `settingsRepository.apiConfigFor(personaId)` 进
     `AgentLoop.run(api=…)`；任务页 ▶ 传 null 走全局，行为不变。
  ⑤ **作用域**：Agent 挂 `MenghuanApp.applicationScope`（Agent 要切走前台，聊天页退出不掐断；
     ChatViewModel 新增可空 `appScope` 构造参数，JVM 测试不传不受影响）；
     摘要落库走 `onFinish` 回调（应用级），VM 销毁不丢结果。
  ⑥ **执行卡**：`ChatUiState.agentRun: AgentRunUiState?` + `AgentRunCard`（目标 + 日志自动滚底 +
     停止/收起，⛔/⚠️ 标红）；`AgentRunner.State.Running` 携带 goal、新增 `runningPersonaId`
     ——重进会话若执行仍在跑自动重挂卡片；Idle 即收卡（onFinish 落库摘要后 `reset()`）。
     摘要消息 = 用户侧消息「🤖 自动执行「目标」完成/未完成（N 步）：…」（打卡回执同款语义，LLM 可感知）。
  ⑦ **总开关 + 策略段**：`SettingsRepository` 新增 `agent_chat_enabled`（默认开不落盘）/
     `agent_chat_strategy` 两键；`PromptSettingsSheet` 六段 → **七段**，⑦ 手机操控 = 总开关 + 分层段
     （`LayeredPromptSection` 的 index/title 改可空，该段标题由外部渲染）。
  ⑧ 应用助手手册升版 v0.37.0（单聊说明 / 七段 / 自动化两入口），v0.36.5 全文登记
     `DEFAULT_ASSISTANT_PROMPT_V0365` 挂进 `LEGACY_ASSISTANT_PROMPTS`。
  ⑨ 测试 110 → 124：`V037AgentChatTest` 14 测（解析 / 剥标记 / 分层拼接）。
- [x] **设置链信息显示优化（v0.37.1，versionCode 67，纯 UI）**：
  ① **设置主面板副标题改实时状态**：人设管理=人设数、API 配置=「当前：接口名 · 生图已/未配置」、
     AI 提示词=「七段可编 · 手机操控开/关」、外观=当前模式、通知=「已开启 N/3 项」、
     数据与隐私=动作摘要。全部从 `MenghuanApp` 容器现取 flow（`collectAsState`）。
  ② **ApiConfigSheet 反馈可见性**：切换/删除/保存接口、保存/清除生图的提示原先塞进 `testResult`
     （只在编辑表单里渲染，表单没开 = 无可见反馈）；改走独立 `listMsg` 常显在列表区。
     接口地址展示统一 `displayUrl()` 去协议头；生图摘要行标题改模型名（板块头已写"生图接口"）。
  ③ **通知与提醒**：三个开关加一句说明小字（私有 `SwitchRow`）。
  ④ **数据与隐私**：分「本机数据 / 备份与恢复 / 危险操作」三组（私有 `SectionLabel`，与 ApiConfigSheet 同规格）。
  ⑤ **AI 提示词**：`SectionLabel` / `LayeredPromptSection` 加 `customized` 参数，段值 ≠ 默认值时
     标题右侧挂「已自定义」徽标，实时随输入更新（恢复默认即消失）。
- [x] **周报直出全文 + AI 提示词手风琴（v0.37.2，versionCode 68，纯 UI）**：
  ① **周报卡删除折叠**（用户要求）：`WeeklyReportCard` 去掉 expanded/onToggleExpand/箭头/
     「展开全文」按钮与 COLLAPSED_PREVIEW_* 常量，正文直出；`reportExpanded` 状态删除。
     render16 同步：断言全文直接可见 + `assertDoesNotExist("展开全文")`。
  ② **PromptSettingsSheet 手风琴重构**（用户反馈"太繁琐"）：默认 8 行索引全收起，
     `openSection` 单开（点一段展开、再点收起）；`SectionRow`（标题 + 已自定义徽标 + 尾部控件 +
     ChevronUp/Down）；展开态 = `LayeredEditor`（框架只读框 + 策略输入框）；
     **「本段恢复默认」只在 customized 时渲染**；⑦ 总开关放行内（trailing）不用展开；
     生成逻辑说明两段长文收进折叠行。分段语义（框架硬编码 / 策略可编）不变。
  ③ 应用助手手册升版 v0.37.2，v0.37.0 全文登记 `DEFAULT_ASSISTANT_PROMPT_V0370`。
- [x] **八件套大版本（v0.38.0，versionCode 69，功能）**——2026-10-02 用户"全做"拍板：
  ① **对话触发 Agent 前置守卫**：`ChatViewModel.AgentChatGate.decide()`（纯函数）+ `ChatUiState.errorKind`
     ——错误条分级：retry（网络）/ accessibility（→ 系统无障碍设置）/ api（→ 就地叠开 ApiConfigSheet）/
     info（无按钮）。**为什么必须分级**：重试会重新生成回复，模型可能再发一次 [操作手机:…] 标记
     把手机操作重复执行一遍；配置/权限类错误一律不给"重试"。
  ② **Agent 白名单改应用勾选**：`AgentSheet` 手填包名 → 启用开关 + 已安装应用（LAUNCHER intent）勾选列表；
     Manifest `<queries>` 声明包可见性；开关开但零勾选 = 不限制（避免全锁死）。
  ③ **Agent 进度通知**：`AgentNotifier` 低重要度常驻通知（每步更新、setOnlyAlertOnce），
     MenghuanApp 应用级 combine(AgentRunner.state, agentNotifyEnabled) 驱动；点通知
     PendingIntent.getActivities([home, chat]) 直达发起会话（新深链 menghuan://chat/{personaId}）；
     开关在通知设置（默认开）。
  ④ **发图给 AI 看（vision 输入）**：输入栏新增图片按钮（GetContent）→ `VisionImage.copyToChatImages`
     拷私有目录 chat_images/ → 消息 imagePath 复用现有列 → `OpenAiClient.buildMessagesJson`
     把带图用户消息转多段 content（image_url data URL 在前、caption 在后；`VisionImage.encodeDataUrl`
     现场 decode bounds → inSampleSize 降采样 → JPEG78 → base64，落盘始终原图）；
     助手消息的 imagePath（AI 生成图）不进请求；模型不支持 vision 时 400 → 错误文案追加提示。
  ⑤ **人设主动关心**：`ProactiveChat.maybeGreet`（每晚 20:30 `ProactiveReceiver`；
     proactive_enabled 默认关 + proactive_last_day 每日去重；目标 = 最近聊过的非助手人设；
     prompt = 人设 + 档案 + 近 7 天未完成任务，60 字内）；生成落库 + 通知直达会话；
     App 启动 / BootReceiver / 到点三重自排。
  ⑥ **日期型记忆**：`TaskSuggestion.dueDate`（ISO）+ `ProfileExtractor.normalizeDueDate`
     （宽容解析 2026-10-05 / 2026/10/5 / 10月5日补年，过去超 30 天视为明年）；建议卡显示「到期」，
     addSuggestedTask 落到 TaskItem.dueAt（当天 20:00）。
  ⑦ **Token 用量**：`OpenAiClient.streamEvents`（Delta/Usage 事件流）——chat 走 mapNotNull，
     ChatViewModel 一边渲染一边收 usage；AgentLoop/提炼/周报改 `collectReply`；`UsageTracker.add`
     按自然月累计（usage_month/usage_tokens，跨月重置），ApiConfigSheet 底部显示
     「本月 API 用量：约 N 万 tokens」（没拿到过 usage 就不显示）。
  ⑧ 清数据（clearAllData/resetAll）顺带删 chat_images/ 与 images/ 目录（孤儿文件）。
  ⑨ 测试 124 → 141：`V038LogicTest`（Gate 分级 / normalizeDueDate / monthKey / formatTokens /
     vision 请求体组装）+ `AgentRunnerTest`（start→Finished→onFinish→reset 链）。
  ⑩ 手册升版 v0.38.0，v0.37.2 全文登记 `DEFAULT_ASSISTANT_PROMPT_V0372`（LEGACY 链现七段）。
- [x] **聊天手感四连修（v0.38.1，versionCode 70，纯修复+打磨）**：
  ① **退出对话跳任务页根因修复**：home 的 deep link 参数永远留在 entry.arguments，
     从 push 层返回重新组合时 `LaunchedEffect(initialTab)` 再执行一次把 Pager 拽回深链 tab。
     修法 = `appliedDeepLinkEntry`（rememberSaveable，按 entry.id 记消费标记），deep link 只生效一次。
  ② **气泡黑线**：`neuSurface` 居中描边外半圈伸出布局边界 0.5dp，分数像素上呈黑边；
     改为描边内缩半宽（topLeft/size 各收 0.5dp），全局生效。
  ③ **生图可视化**：`generatingImage` 局部状态从未置位、`IMAGE_PENDING` 分支是死代码（已删）；
     改为 `uiState.isGeneratingImage/imagePrompt` 驱动的 `ImageGeneratingCard`
     （小方卡 + `shimmerFill` 对角光带 + 提示词，挂消息列表底部），完成由真图接管；
     失败把「（正在为你生成图片）」占位改写为「（图片生成失败）」。
  ④ **打字机节奏**：reply() 重构为 collectJob（缓冲 sb）+ 45ms 追赶循环
     （`shown += max(1, (target-shown)/5 + 1)`，对齐后退出），错误分支语义保持；
     STREAM_UI_THROTTLE_MS 删除。TypingDots 升级为等点错落弹跳。
- [x] **提炼角色污染修复（v0.38.2，versionCode 71，纯修复）**——用户实测：AI 角色的人设/称呼
     被提炼进用户档案，真人画像反而没收。三层修：
  ① **转录标注角色名**：提炼输入的「AI：」改为「AI角色「名字」：」+ 开头背景声明
     （虚拟扮演、非用户本人）——`runExtract` 现在拿 personaName（null/应用助手照旧跳过）。
  ② **ProfileExtractor.FRAMEWORK 加铁律**（硬编码层）：AI 角色一方的性格/设定/称呼/台词/情节
     不得进 facts/insights/taskSuggestions；用户与角色的关系、互动方式、态度也不是用户画像。
  ③ **DEFAULT_STRATEGY 收紧**：facts/insights 明确排除清单（扮演情节、一次性事件、称呼昵称等）。
     用户自定义过策略的需「本段恢复默认」拿新规则。
  ④ **周报同源修复**：WeeklyReportMath.FRAMEWORK 加「标注（AI）的一方是虚拟角色扮演，
     周报只写用户本人的真实生活」。存量污染数据不自动删（手动编辑/长按删）。
- [x] **生图自发放滥修复（v0.38.3，versionCode 72，纯修复）**：根因 = **自我强化循环**——
     已生成的图片以 assistant 消息留在 LLM 历史（content=英文提示词），模型照葫芦画瓢
     继续自发输出 [生图:…]（用户实测：要求一次后频繁自发生成）。修法：
     ① 历史映射时把带 imagePath 的 assistant 消息改写成过去完成时
        「（此前已生成过一张图片：提示词前 60 字）」，不再以可模仿的输出形态出现；
     ② IMAGE_HINT_DEFAULT_STRATEGY 收紧：被动能力 / 只看用户最近一条消息 / 同话题最多一次 /
        无人要求主动发图 = 严重错误。**教训：任何"AI 动作类标记"的结果回历史时，
        必须标注成已完成的事实，不能留下可模仿的输出样式。**

- [x] **(0,200000) 上下文 + 图片查看/图库/粒子（v0.39.0，versionCode 73，功能）**——
     用户拿着服务商计费页拍板：请求长度 < 20 万 token 是半价档、超过翻倍。
  ① **历史字数预算**：`HISTORY_CHAR_BUDGET = 200_000`（1 字 ≈ 1 token 保守折算）+
     `selectHistoryByCharBudget`（纯函数：从最新往最旧收、最新一条永远保留、超预算停）；
     预算 = 20 万 − system prompt 字数 − 回复余量 8,000。替代旧的 takeLast(20)。
  ② **图片全屏查看**：`ImageViewerOverlay`（Dialog 全屏黑底 + detectTransformGestures 捏合 1–5x +
     双击还原、单击关）；`rememberViewerBitmap` ~1080px 高清解码（列表缩略图仍走
     rememberLocalBitmap 320px）；ImageBubble 加可选 onClick（气泡可点，聊天页 viewerPath 驱动）。
  ③ **我的页「AI 图库」**：第 4 张行卡 → `GallerySheet`：filesDir/images/ 下的消息图
     （按路径前缀区分用户发图的 chat_images/）时间倒序、手动 chunked(3) 网格
     （弹层内容自带纵向滚动，不能嵌同向 Lazy——v0.33.0 教训），点开走同一 viewer。
  ④ **生图粒子特效**：`ParticleField` 26 颗墨色粒子公转+呼吸+明灭，公转圈数/明灭次数
     取整数保证 0→1 循环首尾无缝；ImageGeneratingCard 删"正在生成图片…"文字与 shimmerFill。
- [x] **生成记录 + 波点粒子（v0.40.0，versionCode 74，功能）**：
  ① **Room v10**：`image_generations` 表（timestamp/personaId/personaName/model/prompt/
     success/imagePath）+ MIGRATION_9_10 + `ImageGenerationDao`；生图成功失败都落一条
     （`maybeGenerateImage` 内写入，model 取生图接口配置、personaName 冗余存防人设删除）。
  ② **GallerySheet 改记录列表**：每条 = 缩略图（失败=灰面「失败」标红 / 成功但文件丢了=
     「已丢失」）+ 提示词（2 行）+ 时间 + 模型·会话 + 成败标签；成功且文件在才可点进 viewer。
  ③ **波点浮动粒子**（用户指定样式）：`DotSeed` 深浅两层（近层大且实 40%、远层小且淡），
     上下浮沉 + 轻微横漂 + 呼吸缩放，整数次循环无缝；按 depth 排序绘制出层次。
- [x] **聊天流畅度优化（v0.40.1，versionCode 75，性能）**：
  ① **进页闪烁**：ChatScreen 首次消息加载完成后的定位从 animateScrollToItem 改
     **scrollToItem 无动画直跳**（initialScrollSettled 标记只跳一次）；有新消息时
     **只在贴底（nearBottom）才跟随滚动**——此前每次新消息都强行拽底，翻历史被抢。
  ② **流式尾巴脱离列表**：ChatUiState 新增 `streamingText: String?`，打字机每跳只
     `copy(streamingText=…)`（O(1)），ChatScreen 以独立 item(key="streamingTail") 渲染 AiBubble；
     结束走 `finishStream()`（回读 DB 消息列表 + 清尾巴）。替代此前每 45ms 拷贝全列表 +
     重建 ChatMessage 的写法（长会话 GC 抖动掉帧根因）。
  ③ **图库回填改懒执行**：启动期并发回填（v0.40.0）会与其它启动协程争抢 Room 查询线程池，
     Robolectric 下把会话列表 rows 流的首值堵死（100% 复现，禁用回填即绿）；
     改为 GallerySheet 打开时懒回填。**教训：Application.onCreate 里不要并发发多个
     各自扫全表的协程——Room 的 query executor 共享，启动期争抢会拖死别的数据流。**
- [x] **回复一次性上屏 + 思考链 + 退场动画（v0.41.4，versionCode 80）**：
  ① **打字机整体废弃**（用户明确要求"后台生成、一次性发送"）：`ChatUiState.streamingText`
     字段**已删除**（别再加回来），新增 `generating: Boolean`。reply() 里的 45ms 追赶循环
     改成 `collectJob.join()` —— 等 SSE 全部收完，`persistReply` 一次性落库，
     `finishStream` 整段上屏。生成期间 UI 挂 `item(key="typingTail")` 的空 AiBubble（三点动效）。
     **注意 streamingText 是 v0.38.1→v0.40.1 两轮优化的产物，这次是推倒，别被旧注释误导。**
  ② **思考链落库 + 长按查看**：`StreamEvent.Reasoning` 解析 `delta.reasoning_content`
     与 `delta.reasoning`（两家字段名不同，都要认）；Room **v11** `messages.reasoning` 列
     （MIGRATION_10_11，三处同步：实体 / AppDatabase / MenghuanApp.addMigrations）。
     `buildMessagesJson` **绝不回送 reasoning**（白费 token + 旧思路带偏本轮），
     OpenAiClientTest 有一条断言专门守这个。长按菜单仅在 `reasoning.isNotBlank()` 时
     多出「查看思考过程」一行（MIcons.Brain）。
  ③ **输入栏退场闪烁的根因 = imePadding 挂在整行上**：返回时键盘收起 → bottom padding
     从键盘高度**瞬间**掉到 0，输入栏在滑出动画中途被"顶"一下。改成
     `animateDpAsState(WindowInsets.ime.getBottom(...))` 做 220ms 平滑收拢。
     **教训：任何时候把 imePadding 直接挂在一个会在退场期间重组的容器上，都要想到键盘
     收缩会造成 layout 跳变。**
  ④ **退场时长 280→420ms + scaleOut(0.96f) 收束，且去掉 fadeOut**：纯平移的快速滑出
     读起来"唰一下没了"太仓促；边缩边淡会让输入栏最后几帧透明化（"闪一下"的另一来源）。
     chat 与 personas 两个 push 路由同步改（都用 EaseOutQuart）。

- [x] **数据页大改版：记账本 + 每日建议 + 洞察周报合并 + 本周回顾（v0.42.0，versionCode 81）**：
  ① **本周回顾条置顶**（`DataScreen.WeekReviewCard`）：把原「打卡热力」与「完成度环」
     两套**分母不同**的口径合并成一张卡——用户看不懂"一边 5/7 一边 2/3"。
     **统一口径 = 今天该做的事「每日任务 + 当天到期的基础任务」**（与任务页 / 每日总结通知一致）。
     纯函数 `buildWeekReview(tasks, completions, today, zone)` 在 `DataViewModel.kt`，
     可单测。**注意"每日任务当天之后创建的当天不算"**（`createdAt < dayEnd`）——
     补看历史时不能把未来的任务算进过去。**点格子展开当天明细**（`expandedIndex` 单值）。
  ② **今日建议走独立生成链路（B 方案）**：新表 `daily_suggestions` + 独立 prompt
     （`DailySuggestionMath`，FRAMEWORK 硬编码 schema + 可自定义 STRATEGY）+ 每天 07:30
     闹钟（`DAILY_SUGGESTION_REQUEST_CODE`）+ `DailySuggestionReceiver`（通知 id 2_000_000_007）。
     **套路照搬周报**：同 day 覆盖不追加、进程启动**只排闹钟不发请求**、Receiver 里到点自排明天。
     长按可「转为任务」（写 task `createdBy="ai"` + `setConverted(day,true)`）。
  ③ **记账本**：收入/支出双向流水，**无分组无分类**（用户明确说不需要）；
     **现金余额不入库**——余额 = 全部流水有符号累加（`LedgerMath.balanceCents`），
     流水是唯一事实来源，删记录不会算错。金额存「分」Long，规避浮点误差。
     三入口：手动 / 语音（系统 `SpeechRecognizer`，零第三方依赖，**点语音才申请
     RECORD_AUDIO**，不在进页面时突袭）/ 识图（复用 `OpenAiClient` vision 通道）。
     月度统计按月切换。入口在**数据页底部**（用户指定），点击进独立全屏页。
  ④ **AI 洞察 + 周报合并成一个入口**：数据页只留一张卡（`ai_entry_card`），
     点击进**全屏详情页** `AiDetailScreen`（洞察全文 + 周报全文，最新一期默认展开）。
  ⑤ **Room v12**：`ledger_entries`（索引 `occurredAt`）+ `daily_suggestions`
     （`day` 唯一索引），配 `MIGRATION_11_12`，三处同步别漏。
  ⑥ **踩坑一：LazyColumn 跨列表 key 冲突**。`AiDetailScreen` 里洞察与周报同在
     一个 LazyColumn，两边都用 `key = { it.id }` → id 都是 1 时抛
     `Key "1" was already used`。**同一 Lazy 容器里的多组数据 key 必须加前缀**
     （`"insight-$id"` / `"report-$id"`）。
  ⑦ **踩坑二（重要）：scrollable 容器里首屏之外的节点，`performClick()` 静默无效。**
     `render19` 曾"通过"却截到了数据页——因为数据页自己也有「现金余额 / 636.20」，
     断言被自家文案骗过。**必须 `performScrollTo().performClick()`，并补一条只存在于
     目标页的文本断言**（如记账页的「月度统计」、详情页的「AI 洞察 · N 条」）确认真的跳走了。
     **以后任何"点击 → 跳转 → 断言"的截图测试都要按这个双保险写。**
  ⑧ **踩坑三：文件型 Room 库跨测试类共享**。`ChatViewModelTest` 有一条
     `assertEquals(0, messageDao().getAll().size)` 的绝对断言，被 `UiRenderTest`
     写入的消息搞红（单独跑却绿）。**教训：Robolectric 测试里凡断言"表里有多少行"，
     一律改成「基线快照 + 增量」，不要假定空表。**
  ⑨ 数据页两处入口行加 `testTag`：卡片内标题文本与区块头「记账本 / AI 洞察 / 周报」
     完全同名，`onNodeWithText` 会撞车（"expected exactly 1 node but found 2"）。

- [x] **做减法一轮：全应用去阴影 + 删说明小字 + 记账本入口合一 + 周度统计（v0.42.1，versionCode 82）**：
  ① **全局零阴影**：`neuSurface` 删掉 `PaperShadow` 绘制分支，`elevation` 参数保留但失效
     （`@Suppress("UNUSED_PARAMETER")`，为不动几十个调用点）；`NeuPressState` 去掉 `elevation` 字段。
     三类 Material 容器必须**逐个显式归零**，且参数名不同（见 §四「Material 容器的阴影陷阱」）。
     **`ModalBottomSheet` 与 `AlertDialog` 都没有 `shadowElevation`，写上去直接编译不过。**
  ② **删说明性小字**：「本周任务全部清完」「点格子看当天明细」「这天没有任务」
     「还没有产出，聊聊就有」「每天早上自动生成 / 配置 AI 接口后即可生成」全删。
     空态从「图标 + 双行文案」简化成单行 / 直接不显示（如 `summary: String?` 为 null 就不渲染）。
     **数据标签一律保留**，别误删余额、收支结余、日期。
  ③ **记账本入口合一**：数据页 `RecentLedgerCard` + `LedgerEntryRow` 两块合成单卡
     `LedgerEntryCard`（钱包图标 + 「记账本」+ `¥余额` + 右箭头），**整卡 clickable**，
     `testTag("ledger_entry_row")`；流水只在本页看。
  ④ **周度统计**：`LedgerMath` 新增 `weekStartOf`（`with(DayOfWeek.MONDAY)`）/ `shiftWeek` /
     `weekTotals` / `weeklyNetCents`（key = `dayOfWeek.value` 1..7，周区间 `[start, start+7)`）。
     `DataViewModel` 新增 `enum LedgerStatsScope { MONTH, WEEK }` + `periodStats`
     （`combine(ledgerEntries, month, weekStart, scope)`），**取代原 `monthStats`**；
     `setLedgerScope` 切档时锚点归位今天。UI 侧统计卡标题即切换按钮
     （`testTag("ledger_scope_toggle")` + 28dp 强调色下划线暗示可点），细柱月档 N 根、周档固定 7 根。
     **周起点必须是周一**，与数据页「本周回顾条」同口径，否则又是两套分母。
  ⑤ **测试夹具要点**：验证月/周切换生效，夹具要用「**本月但不在本周**」的流水
     （放上个月会导致默认月档看不到，测试超时）；断言 `Long` 字段写 `60_00L`，
     别写 `60_00`（Kotlin 会推成 Int，`assertEquals(Long, Int)` 直接红）。

- [x] **数据页减法 + 交互收敛（v0.42.2，versionCode 83）**：
  ① **再删两处文案**：本周回顾去掉「本周完成度 xx%」与「🔥 连续 N 天全清」徽标
     （`StreakBadge` 函数已删）。卡片只剩 `done / total` 主指标 + 7 天格子 + 进度条。
  ② **区块头动作全撤**：`SectionHeader` 的 `actionText` / `onAction` 参数**已删除**，
     「今日建议」不再有「重新生成」、「AI 洞察 / 周报」不再有「提炼」——**别再往区块头加动作按钮**，
     这两个动作容易误触洗掉已有内容；生成走弹窗，提炼由水位线自动跑。
  ③ **今日建议 = 点击弹窗**（`DailySuggestionSheet`）：点建议卡 / 空态卡弹 `NeuSheetScaffold`，
     面板内是完整建议 + 「生成今日建议 / 重新生成」按钮（未配置接口置灰）；
     长按仍是「转为任务」。空态卡与卡片热区的 `onClick` 都改成开弹窗。
  ④ **AI 周报默认折叠**（`AiDetailScreen`）：删掉进页面自动展开最新一期的 `LaunchedEffect`，
     `expandedReportId` 初值 `null`。折叠态只显示期标签 + 生成日期 + 箭头。
  ⑤ **踩坑一（动画时机）：Pager 屏外页只组合、不放置。** 数据页在
     `HorizontalPager(beyondViewportPageCount = 3)` 里**后台就组合完并播完入场动画**，
     所以 `LaunchedEffect(Unit) { revealed = true }` 在真正切过来时早已跑完——表现为
     「动画只有从 push 页返回时才看得到」。
     ⚠️ **v0.42.1 曾用 `Modifier.onPlaced` 修，结论是错的（v0.42.3 推翻）**：
     `onPlaced` 只在节点**首次进入布局**时回调，而 Pager 会**预布局相邻页**，
     屏外页照样触发，等于没修。**看到本文旧版本说"用 onPlaced 区分组合/显示"请忽略。**
     ✅ 正解 = **外部喂可见性信号**：`DataScreen(visible = …)` 由
     `pagerState.settledPage == 1` 驱动，可见性一变动画就重播。**跨页可见性一律用这个路子。**
  ⑥ **踩坑二（致命）：`onPlaced` 里改 State 必须只放行一次。** `onPlaced` 每次 layout
     后都回调，无条件 `replay += 1` 会「layout → 改 State → 再 layout」死循环，
     实测整套截图测试全挂 `AppNotIdleException`。正确写法 `if (!armed) armed = true`。
     **以后凡是"placement 回调里改状态"，一律先想清楚会不会自激。**
  ⑦ **踩坑三：铺满卡片的交互热区用 `onClick = {}` 会吞掉底下的点击。** 周报卡原来
     Column 上挂 `clickable(onToggle)`，外面又盖一层 `matchParentSize` 的
     `combinedClickable(onClick = {})` 只接长按 → 点击永远到不了底层，"点周报打不开"。
     **热区层必须自己处理点击**（把 onClick 指到真正的动作上），不要指望穿透。

- [x] **五处手感/定位修正（v0.42.3，versionCode 84）**：
  ① **今日建议 = 屏幕居中 Dialog**（用户明确"从中间弹出"）。用 `androidx.compose.ui.window.Dialog`
     + `neuSurface` 卡片 + 暗色遮罩；**弹窗必须渲染在可滚动容器之外**（之前嵌在
     `verticalScroll` 的 Column 里，`ModalBottomSheet` 定位就会异常）。
     开关状态 `suggestionSheetOpen` 提升到 `DataScreen`，照 `LedgerRoute` 的
     "屏幕内容之外挂弹层"写法。
  ② **本周回顾条删掉顶部 `done / total` 大数字**（用户认为无用）。
  ③ **入场动画可见性信号**（见上 ⑤）：`DataScreen(visible = pagerState.settledPage == 1)`。
     参数默认 `true`，单页预览/测试不用传。
  ④ **退出对话输入框上弹**：`ime padding` 改为**只跟涨、不跟跌**——
     `if (imePx > frozenImePx) frozenImePx = imePx`，键盘开始收起就冻住。
     退场时输入栏零自身位移，由页面下滑带走。返回键先 `keyboardController.hide()` 再 `onBack()`。
     **别再改回 `animateDpAsState(imePx)`：方向是错的，慢速动画同样会弹。**
     > ⚠️ **本结论已被 v0.42.4 推翻**：真因是 ime 与 navigationBars 两套 inset 时序错位，
     > 冻结 ime padding 管不到外层 AdaptiveScreen 加的那 48dp。正确做法见 v0.42.4 条目。
  ⑤ **用户发的图片靠右**：`ImageBubble(…, fromUser = msg.isFromUser)`，
     内部 `horizontalAlignment` 按它切换（用户 End / AI Start），图注自动跟随。
     > ⚠️ **本修复不完整，v0.42.4 已返工**：漏了外层 `Box` 的默认 `contentAlignment = TopStart`。

- [x] **v0.42.3 两条修复返工（v0.42.4，versionCode 85）** —— 两次都栽在"没看到真正生效的那一层"：
  ① **图片靠边（返工）**：`ImageBubble` 内部对齐**无效**，因为它的外层
     `Box(Modifier.fillMaxWidth())` 用默认 `contentAlignment = TopStart`，整块无条件贴左上。
     **凡是"内部对齐了但视觉没变"的情况，先往上找一层容器的 `contentAlignment` /
     `horizontalArrangement`**——Compose 里父容器把子节点摆哪儿，子节点自己说了不算。
     已给 `render23ImageBubbleSides` 加**几何断言**（图注中心 x vs 版心中线），防止再次静默跑偏。
  ② **输入框上弹（返工）**：真因 = **`ime` 与 `navigationBars` 的更新时序错位**。
     `adjustResize` + edgeToEdge 下键盘收起：`ime` 先降 0（输入栏掉到底），
     紧接着 `navigationBars` 从 0 恢复到 48dp（键盘可见时被 ime 吸收）又把它顶上去。
     那 48dp 来自外层 `AdaptiveScreen.navigationBarsPadding()`，冻 ime 完全管不到。
     **正解 = 合并成单一量**：`bottomInset = max(ime.getBottom(), navigationBars.getBottom())`，
     键盘可见 = 键盘高度、收起 = 导航条高度，**单次连续无跳变**，再配 220ms EaseOutExpo 下落。
     配套：`AdaptiveScreen` / `AdaptiveSplitScreen` 新增 `applyNavigationBarsPadding`（默认 true），
     单聊路由传 `false` 把底部避让让给输入栏；**全项目只有 chat 与双栏右栏传 false**。
     **教训：键盘避让绝不能由两层各管一半——两个 inset 各自变化就会打架。**
- [x] **微信风输入栏 + 语音/表情/加号/拍摄/文件（v0.43.0，versionCode 86）**：
  输入栏换成「左语音圈 / 中白底圆角输入框 / 右表情圈 + 加号圈」，有内容时右侧换黑色发送键。
  - **语音**：按住说话（上滑 80dp 取消）→ `SpeechToText` 转文字 → 落库为语音消息。
    消息列表**只显示语音条**（`VoiceBubble`，宽度随 duration 0.30~0.56 版心比例），
    识别文字只喂给模型不在 UI 展示。识别两路兜底：API 31+ 用
    `RecognizerIntent.EXTRA_AUDIO_SOURCE` 喂文件；低版本录制期并行做实时识别；都失败不阻断发送。
  - **表情面板**：两页 tab（emoji / 我的表情），**必须由子页签收 `Modifier` 参数、
    父级在 Column 作用域里传 `weight(1f)`**——独立 Composable 内部直接用 `weight` 会
    `Unresolved reference`（weight 是 ColumnScope/RowScope 的成员扩展，不在作用域里拿不到）。
  - **加号面板**：相册 / 拍摄 / 文件。拍摄走 **CameraX 自绘界面**（`CameraCaptureScreen`），
    理由 = 用户要求风格统一，系统相机 UI 不可控；底栏三等分 weight 保证快门精确居中。
  - **文件喂法 = 本地分类型抽取**（`data/FileTextExtractor.kt`，零依赖）：
    OOXML 解 zip 剥标签；PDF 只抽未压缩文本层（`BT…ET` + `Tj/TJ`，字面量要按
    `\ddd` 八进制 → 字节 → UTF-8 解码，直接按 ISO-8859-1 逐字符拼中文会乱码）；
    抽不出就退化成"只给文件名"。上限 20000 字。
  - **Room v13**：`messages` 加 6 列（`mediaKind` / `audioPath` / `audioDurationMs` /
    `attachmentPath` / `attachmentName` / `attachmentSize`）+ 新表 `stickers`。
    存量 `mediaKind` 为空靠 `MediaKind.fromLegacy` 推断，**不做批量回填**。
  - ⚠️ **输入框跳动第三次修（前两版结论都不够）**：v0.42.4 的
    `max(ime, navigationBars)` 只能修"最终值"，修不了中间那一帧（ime 先降 0 掉底、
    navBars 后恢复 48dp 顶回）。v0.43.0 改成**单调收敛**：
    记住本会话见过的最大底部高度作下界，**升高即时生效、降低要等 inset 稳定 120ms 后才放开**
    （`INSET_SETTLE_MS`），输入栏因此只降不升、平滑落回。
    **凡是"两个系统量先后变化导致中间帧抖动"的问题，取 max/取最新都不够，
    要用单调收敛（或等稳定）把中间帧吞掉。**
  - ⚠️ **面板高度绝不能算进输入栏的 bottomInset**：面板是输入栏的**下一个兄弟节点**、
    自己占着 `PanelHeight`。首版把它也加进 inset，输入栏直接被顶到屏幕中间
    （截图一眼可见）。**输入栏 inset 只管它自己那一行；面板自己负责 `navigationBarsPadding`。**
  - ⚠️ **emoji 格子别用 `aspectRatio(1f)`**：面板只高 268dp，正方形格子（屏宽/8 ≈ 48dp）
    一屏只剩两行半。固定 40dp 行高能在同样面板里铺五行。

- [x] **v0.43.0 真机返工九条（v0.43.1，versionCode 87）**：
  - ⚠️ **语音链路两个死结都在 v0.43.1 才挖出来**（v0.43.0 以为能用，实际上 AI 永远收不到转写）：
    1. **`EXTRA_AUDIO_SOURCE` 只吃未压缩 PCM**。录 `.m4a`(AAC) 喂进去在多数 ROM 上直接
       `onError`，转写永远是空。现在录 **WAV/16bit/16kHz 单声道**。
       ⚠️ `MediaRecorder.OutputFormat.WAV` 在 compileSdk 35 里是 **hidden 常量**，
       直接引用会 `Unresolved reference`，必须写数值 **6**。
    2. **并行识别与 MediaRecorder 抢麦克风**：`LiveSession`（低版本实时识别）和
       录音同时开麦，两边都拿不到音频。**此类功能必须串行：录完再识别。**
    经验：凡“录音 + 识别”叠在一起的设计，先问“麦克风归谁”。
  - **语音态是开关不是按住**：点语音键 → 中间变「按住 说话」且语音键翻成
    **键盘图标**，再点一下退出语音态（用户明确指定的退出方式）。
  - **语音条长按转文字 = 开关**：状态必须放在**列表层**（`mutableStateListOf<Long>` 记 id），
    放气泡内部会随滚动回收而丢。识别失败（`content` 为空）时不给该入口。
  - ⚠️ **键盘避让不能做二次插值**：输入栏自己走 220ms 缓动、键盘是**系统**在动，
    两条曲线不同必然“慢半拍”（用户反馈“移动不同步”）。**直接跟随键盘真实高度**，
    只对回落给 90ms 抹帧。同理：面板一展开就必须**直接忽略 `ime`**
    （`rawInsetPx = if (panel != 0) navPx else maxOf(imePx, navPx)`），否则键盘收起动画那几百毫秒里
    “ime 还占着高度 + 面板也占位” = 中间一大片空白。
  - ⚠️ **没有 BackHandler 时，返回键会被系统拿去收键盘并消费掉**，
    第二次才到 NavHost 直接退页。凡页面有“弹出层”（键盘/面板/语音态/搜索），
    必须自己接 `BackHandler` 做分流，逐层收起。
  - **相机页白图标在浅色取景内容下等于隐形**：改成**白实心圆底 + 黑图标**，
    任意取景内容上都对比成立。拍完必须进**确认态**（重拍/发送）再发。
  - **竖拍变横的修法是双重的**：① `ImageCapture.targetRotation` 跟随预览方向；
    ② 按 EXIF **物理旋转像素后回写原文件** —— 回写才能让下游
    （vision 编码 / 气泡显示 / 不读 EXIF 的解码路径）全都拿到正向图。
  - **小尺寸图标的圆点会粘连**：键盘图标初版 4 列键帽，缩到 21dp 糊成黑疙瘩；
    改 3 列×2 行 + 加大半径才分得清。自绘图标定尺寸后**必须截图放大看一眼**。
  - ⚠️ **拍摄页无法用 JVM 截图测试守**：Robolectric 下 CameraX 初始化会挂，
    不要为它写 UiRenderTest（实测超时）。拍摄相关改动**必须真机验收**。

- [x] **v0.43.1 真机第二轮返工四条（versionCode 87，同版本内完成）**：
  - ⚠️⚠️ **"列表初值是空列表"会闪出错误内容**：会话列表原来只有「空 / 非空」两态，
    `rows` 的初值就是 `emptyList()`，Room 首个结果没回来时先按"没有人设"渲染出
    居中的「新建人设」，数据到了才换 —— 用户看到"先显示新建人设，然后对话列表才出现"。
    **凡"异步数据 + 空状态有引导内容"的页面，必须显式区分「未加载」与「确实为空」**。
    这里用 `ChatListState(rows, loaded)` 三态（骨架屏 / 新建入口 / 列表）。
  - ⚠️⚠️ **"是否已加载"必须从主数据流派生，不能另发一条查询**：
    写成 `viewModelScope.launch { repo.observePersonas().first(); loaded = true }` 时，
    ① 会和主 `combine` 争抢 Room 共享 query executor，把列表首值堵死（Robolectric 100% 复现，
    两个既有单测 60s 超时，与 MEMORY 里 Application.onCreate 的教训同源）；
    ② `runTest` 会把未完成的子协程判为"测试未结束"，直接 `UncompletedCoroutinesError`。
    正解 = `rows.map { ChatListState(it, loaded = true) }`，额外查询 0 次。
  - ⚠️⚠️ **MediaRecorder 的参数组合在真机 ROM 上可能非法，必须带回退链**：
    `setOutputFormat(6)`(WAV) + `AudioEncoder.DEFAULT` 在小米上 `prepare()` 直接抛异常。
    现在按「WAV → MPEG_4/AAC」依次尝试，第一组成功即采用。
    ⚠️ 更要命的是**异常被 `catch (e: Exception) { false }` 静默吞掉**，真机上只有
    "启动失败"四个字、无从定位。**凡 `start()` 类失败必须 `Log.e` + 把原因回传给 UI**。
  - ⚠️⚠️ **"键盘 ↔ 面板"的过渡期只看 `panel != 0` 是不够的**：
    点表情那一瞬间 `panel` 还是 0、`ime` 还是键盘高度 → 输入栏被残留高度顶着、
    面板又在下面展开 = 中间一大片空白（用户截图）。
    **正解 = 把「切换动作本身」当信号**：引入 `OUTPUT_SWITCH_QUIET_MS = 280ms` 静默期，
    并让**所有**输出形态入口（语音键/表情键/加号键/输入框聚焦/返回键）收敛到
    同一个 `switchOutputMode(nextPanel, nextVoice, showKeyboard)`。
    以前四条入口各写一份 hide/show，漏一处就留下"键盘还开着 + 面板也开着"的窗口期。
  - ⚠️ **单调收敛的下界在"用户主动切换形态"时要立刻放开**，不能等 120ms 稳定计时 ——
    那 120ms 里输入栏仍被旧键盘高度顶着。在收敛逻辑里比对"切换时刻"是否变化即可。
  - ⚠️⚠️ **`currentBackStackEntryAsState()` 在 `popBackStack()` 那一刻就切回上一层**，
    而退场动画要跑 420ms。若底栏显隐直接绑路由，这 420ms 里底栏已渲染、
    Scaffold 的 `innerPadding` 随之加大，正在滑出的页面会被往上挤（用户看到"输入框往上跳、
    底栏先冒出来"）。**正解 = 底栏显隐也走 `AnimatedVisibility`，
    进场延迟到页面滑走之后、退场立刻开始，并与 push 路由共用同一时长常量。**
- [x] **v0.43.1 真机第三轮返工两条（versionCode 87，同版本内完成）**：
  上一轮的两处"修过头"，本轮从机制上收口。
  - ⚠️⚠️ **"输出形态切换静默期"只能对「收键盘」生效，绝不能对「弹键盘」生效**：
    静默期的语义是"这一轮不要键盘了，别再按键盘高度顶输入栏"。套在弹键盘场景上就是自相矛盾 ——
    点表情后再点输入框时，底部避让被压成导航条高度，而键盘正往上弹、已经盖住输入栏
    （用户反馈"键盘把输入框挡住"）。
    **正解 = `switchOutputMode` 里判方向**：`keyboardWanted = showKeyboard && nextPanel == 0 && !nextVoice`；
    `outputSwitchAt = if (keyboardWanted) 0L else System.currentTimeMillis()`
    —— 要弹键盘时静默期**置 0（等价于没有）**，立刻跟随真实 `ime` 高度。
    **凡"给某个过渡打的补丁"，必须先问一句"这个过渡的反方向会不会被误伤"。**
  - ⚠️⚠️ **`SpeechRecognizer.EXTRA_AUDIO_SOURCE`（API 31+）在国产 ROM 上普遍不生效**：
    小米等定制系统的识别服务**只监听实时麦克风**，喂它音频文件路径等于没喂 ——
    `onResults` 不回调、`onError` 也可能不给，最终 `withTimeoutOrNull` 走满 20s 返回 null。
    现象就是"AI 知道是语音但不知道内容"。**换录音格式（改 WAV/PCM）救不了这条路，
    因为对方压根不读文件。**
    **正解 = 语音转写走"云端优先"三级级联**：
    ① 复用用户已配好的 AI 接口走 Whisper 协议（`POST {base}/audio/transcriptions`，
    multipart `file` + `model=whisper-1` + `language`）—— 主力；
    ② 系统识别器喂文件 —— 兜底；
    ③ 都失败 → null（语音照发、content 空，历史里写明"发了段语音但没转成文字"）。
    关键设计：**`cloudTranscribe` 由调用方以 `suspend (File) -> String?` 注入** ——
    `SpeechToText` 是纯逻辑对象，不该知道 API 配置从哪来（可测试性也靠它）。
  - ⚠️ **Android 11+ 包可见性（`<queries>`）同样管 `SpeechRecognizer`**：
    不声明 `<intent android:name="android.speech.RecognitionService"/>` 可能导致
    `isRecognitionAvailable()` 恒为 false —— 识别器连"可用"都不认。已补进 Manifest。
  - ⚠️ **`android.util.Log` 在纯 JVM 单测里未 mock**（非 Robolectric 类中调用会抛
    `Method w in android.util.Log not mocked`）。新增的 `OpenAiClient.transcribe` 里的日志
    必须用 `runCatching { Log.w(...) }` 包住。
  - ⚠️ **Robolectric 测试要用 `@RunWith(RobolectricTestRunner::class)` + `@Config(sdk = [34])`
    + `ApplicationProvider.getApplicationContext()`**；`RuntimeEnvironment.getApplication()`
    在没有 runner 的"纯 JVM"类里返回 null → NPE（`SpeechToTextTest` 首版就踩了这个）。
- [x] **输入区 / 拍摄页 /「我的」页一轮细节打磨（v0.45.0，versionCode 90）**：
  - 语音态键盘图标重绘（外框 + 一排三键帽点 + 空格横线，线宽 1.6）+ 颜色压到 `TextSecondary`；
  - 表情面板顶部留白 8→2dp；「我的表情」改名「收藏」，添加入口收成网格末尾一个加号格，
    空态说明文字全删；加号面板 132→104dp；
  - 输入栏图标键 / 面板格子 / 加号面板三格统一改 `rememberNeuPressState` 缩放（去 Material ripple）；
  - 新增 `MediaKind.STICKER` → `StickerBubble`（120dp、不可点开），与图片气泡区分；
    老数据按 `filesDir/stickers/` 识别回来；会话预览显示 `[表情]`；
  - 拍摄页：进入相机的过渡 `AnimatedVisibility` 淡入（+ 轻缩放）+ `BackHandler` 接住侧滑返回
    （此前侧滑直接 pop 到桌面）；翻转动效加在**翻转按钮**上（绕 Y 轴转半圈），预览画面不动；
  - 「我的」页设置面板删「人设管理」（与一级行卡重复）、「AI 提示词」上提为一级行卡。
  - ⚠️ **拍摄页改动仍无法用 JVM 截图测试守**（CameraX 在 Robolectric 下会挂），必须真机验收。
- [x] **修：唤键盘后消息列表不跟随（v0.45.1，versionCode 91）**：键盘弹起输入栏被顶高 → 列表视口
  变矮，`LazyColumn` 不自动保持贴底 → 最新一条被挤出屏幕外（用户真机反馈，每次都出现）。
  `ChatScreen` 监听视口高度，只在**视口收缩且收缩前本来贴底**时按收缩量 `scrollBy` 跟随到底
  （用户选定"贴底才跟随"，微信式；翻历史不打断）；用收缩前一帧的贴底状态判断，瞬时弹键盘也准。
- [x] **实时语音通话一期（v0.46.0，versionCode 92）**：用户拍板"DeepSeek 管道"（非 WebRTC 真双工，
  引擎做成可替换接口 `VoiceCallEngine` 将来可插 GLM-Realtime）。免按住持续对话 + VAD 分段 +
  barge-in 打断 + Edge TTS 免费音色（晓晓）；复用内置 sherpa 流式模型出 partial 字幕；
  新增 assets `silero_vad.onnx`（1.8MB，ModelScope 下载）；Room v14 加 call_sessions/call_turns
  （挂断一次性落库）；零新增 gradle 依赖。单聊顶栏电话图标 → `call/{personaId}`。
  已知限制：首声延迟 1.5–3s；Edge TTS 非官方接口有失效风险（降级纯字幕）；通话记录无查看 UI；
  无前台服务（App 被杀即断）。**必须真机验收**（Robolectric 无法跑 AudioRecord/WS 合成）。
- [x] **语音栈推倒重来（v0.50.0，versionCode 98）**：用户判定"语音方案越搞越乱、想推倒重来"，
  要求先参考成熟实现（拆解爱语 2.9.5 的 dex + assets + 音色样本）再动手。
  收敛成 **一条识别链 + 一条合成链 + 一个标记协议 + 两个开关**；识别模型**零内置、装后下载**。
  - **识别**：端侧 sherpa-onnx `OfflineRecognizer` + **SenseVoice 离线非流式**（int8 228MB，
    中/英/日/韩/粤，顺带出情绪标签）。替掉 `streaming-zipformer-zh-14M`——14M 参数官方定性
    "准确率不佳"，且梦欢是"录完再识别"，用流式模型属于拿错工具。
    三级级联砍成单级（云端 Whisper 端点不支持、系统识别器喂文件在国产 ROM 无效，两级都是死路）。
    **语音消息与通话共用同一个识别器实例**（删 `CallAsr.kt`；228MB 建两个 session ≈500MB 会 OOM）。
  - **模型不随包**：`AsrModelManager`（Range 断点续传 + 逐文件 sha256 + SAF 手动导入 + 删除释放空间）
    + `AsrModelSheet`。源顺序 **ModelScope → hf-mirror → 手动导入**（2026-10-04 实测：GitHub Releases
    25s 超时不可达、hf-mirror 证书 SNI 异常，只有 ModelScope 秒通且接口给官方 sha256、支持 Range）。
    没模型时点语音键**不进语音态**、直接拉起下载面板；通话页进页即检查 + 提示条一键下载。
  - **合成**：统一 `TtsClient`，三家可切（硅基流动 CosyVoice2 / 小米 MiMo / 自定义 OpenAI 兼容）。
    三家协议不统一，逐家一个实现（查证记录 `docs/RESEARCH-v0.50.0-TTS协议查证.md`，
    每条标证据等级）。**音色克隆**走 App 内全自动：样本裁 15 秒 → **端侧 ASR 自动出逐字稿**
    （硅基上传接口 `text` 必填）→ multipart 上传换 `speech:...` uri。音色按人设存，通话与语音条共用。
  - **AI 语音条**：`【语音条】` 标记（与 `[生图:…]`/`[操作手机:…]` 同族），AI 自主决定这句话用说的；
    合成成功后把刚落库那条**原地升级**成语音条（不另插一条），失败降级为纯文字并提示原因一次；
    `VoiceBubble(isUser=…)` AI 侧改面色细线。提示词第⑧段进「我的 · AI 提示词」。
  - **通话**：Edge TTS → `TtsClient`；字幕改**按 VAD 段出**（离线非流式没有逐字 partial）；barge-in 不变；
    新增 `TtsAudioDecoder`（容器嗅探分流 + 下混单声道 + 重采样到 OUT_RATE，否则换三家后会变速变调）；
    TTS 未配置时不出声、走字幕兜底并明确提示。
  - **删除**：Edge TTS、`ReadAloudPlayer`、单聊右上角喇叭、`chat_read_aloud` 键（朗读功能用户明确作废）。
  - **两个总开关**（设置 → 语音）：AI 语音条 / 语音通话（关掉后加号面板不再出现「通话」格）。
  - **体积**：去内置模型（−25MB）+ 去 `armeabi-v7a`（−22MB so）→ **APK 80.85 MB → 35.3 MB**。
  - 测试：新增 `AsrModelManagerTest` / `TtsConfigTest` / `TtsClientTest`（MockWebServer 钉死三家
    请求体与响应形状）/ `VoiceNoteMarkerTest`；`SpeechToTextTest` 按单级重写；
    `UiRenderTest` 语音态用例改为断言"无模型时拉起下载面板" + 新增 4 张截图。全量 303 项通过。
  - ⚠️ **三家 TTS 用户当下都没有 Key，真机出声验收做不了**——协议正确性只由单测与文档保证。
    必须真机验收：识别准确率对比、模型下载全流程、通话整通、语音条播放与转文字、三家各合成一句、音色克隆上传。
- [x] **「我的」这条线全部全屏页 + 应用助手手册立为硬约定（v0.51.0，versionCode 99）**：
  13 个设置链弹层（基础档案 / AI 图库 / AI 提示词 / 设置 / API 配置 / 外观 / 通知与提醒 /
  数据与隐私 / 关于 / 语音 / 语音合成 / 识别模型）改成 NavHost 全屏页，共用 `SettingsPage` 骨架，
  路由名集中在 `SettingsRoutes`。动机是设置链已有三层，弹层叠弹层盖屏、返回语义不清，
  而识别模型下载要几分钟、必须能退出去干别的再回来。人设编辑/消息菜单/打卡/记一笔等表单菜单类**保持弹层**。
  应用助手手册补「语音」整章：先分清「听」（识别模型，App 内下载）与「说」（语音合成，须去官网注册 Key），
  给出 cloud.siliconflow.cn → /account/ak 拿 Key → App 粘贴 → 试听 的逐步教程，
  含音色克隆的实名认证要求与 MiMo Token Plan 专属地址注意。
  ⚠️ 顺带修掉一个**静默失效**：手册是在 v0.38 全文上做定点 `replace`，锚点经 `trimIndent()` 后段首无缩进，
  搜索串多写空格就白改且不报错——v0.50.0 有两条中招，本次改正。
  并立为 AGENTS.md 零.5/零.6 硬约定：**每次改功能必须同步手册，且每加一条 replace 必须在
  `AiPromptsTest` 补一对"新的在 + 旧的不在"断言**；手册里的服务商网址只准用查证过的官方地址。
  识别模型**去掉手动导入**（普通人不知道该下哪个文件），只留 ModelScope / hf-mirror 两个自动源，
  失败时把官方仓库主页以可选中形式给出。

- [x] **修「应用助手手册永远升不上去」的机制缺陷（v0.51.1，versionCode 100）**：真机反馈助手仍答不上
  "语音怎么配"，根因不是文案而是启动闸门——旧实现要"库里这段**正好等于**某个历史默认文案"才刷新，
  而历史表只登到 v0.38，每版都在 `DEFAULT_ASSISTANT_PROMPT` 上原地追加 `replace`、中间态从未登记，
  于是 v0.41~v0.50 升过级的用户手册永久卡死。改为只看"用户有没有亲手改过设定词"
  （DataStore 三态标记 + `isBuiltinAssistantManual` 形状识别兜底老装机），
  **以后改手册不必再登记任何历史全文**；删掉六份已成死码的历史手册全文（约 340 行）。
  顺带按真机实况重写手册里的单聊输入栏一段（发图/拍摄/文件在「＋」面板、表情面板、左侧语音键、
  有字时右侧是发送键）。回归 `AssistantManualUpgradeTest` 6 条 + `AiPromptsTest` 两组。

- [x] **语音配置整改（v0.52.0，versionCode 101）**：用户一次报六条，逐条对应记在 `docs/PLAN-v0.52.0-语音配置整改.md`。
  ① 语音合成**每家一套独立配置**（DataStore 按 provider 分桶，老键虚拟归属当时生效那家；
  只有配齐的家才成为"当前生效"）；② #2「MP3 克隆说格式不对」与 #4「通话音频解码失败」是**同一个根因**——
  旧 `Mp3Decoder` 把输入 `MediaFormat` 硬写 24kHz/单声道，真实流不符就吐 0 帧。新增
  `MediaAudioDecoder`（`MediaExtractor` 自读真实采样率/声道，mp3/wav/m4a/**mp4** 通吃）并删掉旧解码器；
  通话改为直接向服务商要 **WAV@24000**（`TtsTarget.CALL`），链路里根本不解 MP3；
  ③ 新增**「音色」板块**（设置 → 语音）：预置每家只留一男一女、右上角就是「克隆音色」、
  克隆列表进页面自动拉取（删掉看不懂的"刷新我的克隆音色"）、手填 ID 收进板块；
  人设编辑只从板块名单里挑，试听与添加撤到板块里；音色三级回落（人设 → 板块默认 → 该家第一个预置）
  由 `voiceForConversation()` 统一，聊天与通话共用；④ 语音条播完一定复位
  （不再只依赖 `onCompletion`，加 position/duration 看门狗 + 绝对超时）；
  ⑤ **所有 API 配置一律手风琴**（对话/生图/语音合成默认收起，收起态只给摘要，防误触）。
  协议按 `api-docs.siliconflow.cn` 逐字复核：克隆音色确实走 `voice` 字段
  （`references` 是"不预上传、请求内联"的另一条路，没动）。
  测试：`TtsProviderConfigTest` 6 条（写测试时抓到"保存别家会抹掉硅基 Key"的实现 bug）、
  `TtsConfigTest` 重写预置/名单/目标格式用例、`UiRenderTest` 更新语音两页并新增 API 手风琴用例。

- [x] **接口配置改「卡片 + 点箭头看详情」+ 通话 WAV 解析 + 克隆中文名（v0.53.0，versionCode 102）**：
  用户纠正 v0.52.0 的手风琴理解——要的是**每条接口**收在卡片里：点卡片本体=切换当前生效，
  点卡片右侧箭头=`SettingsCardDialog` 中间弹窗看/改/删（卡片外那排「编辑」「删除」按钮删掉），
  箭头加大加深（`NeuSheetRow(onChevronClick=…)`）；语音合成页同一套原理，
  只列**已配齐的模型卡片** + 底部「＋ 添加接入」选服务商，不再常驻三家分段表单；
  分桶"写过没写过"改判 `contains`，否则清空字段会被老键复活。
  两个真机 bug 定位到具体字节层：① 克隆 HTTP 400 是 `customName` 只收 ASCII，
  中文名改为只本机显示（`cloneVoiceNames` 表）；② 通话"WAV 解不开"是 `WavPcm` 只认 PCM16
  而硅基回 float32，解析器改成按表头解释（PCM 8/16/24/32 + float 32/64 + extensible + 流式长度）。
  手册同步 + `AiPromptsTest` 双向断言；AGENTS.md §四 与 零.4 各立一条"别再改回去"。

- [x] **通话闪退定位与克隆音质修复（v0.53.1 → v0.53.3，versionCode 103~105）**：
  ① v0.53.1 给通话两条协程加异常兜底 + 新增 `data/CrashLog.kt`（崩溃前把堆栈写进
  `filesDir/crash.log`，「设置 → 关于」出现「上次崩溃记录」可看可复制）——真机没有 logcat，
  这是唯一能拿到栈的办法；② 用户截图里那行 `StringIndexOutOfBoundsException regionStart=-82`
  直接把根因钉死：是我 v0.53.0 修 WAV 时把 chunk 游标推进写漏了（流式 WAV 的 data 长度为 -1 时
  游标倒退变负）→ `WavPcm` 改为 data 读完即停 + 游标必须严格前进，补 3 条回归；
  ③ "克隆音色发抖变调"（预置正常、语音条也怪 → 范围锁死在参考音频）：
  降采样改走带低通的 `WavPcm.resample`（48k→24k 裸线性插值的混叠会烘进参考），
  样本裁剪改成 `VoiceCloner.trimToSpeech`（跳前导静音、在能量谷下刀、上限 15→10 秒），
  弹层写明样本要 8~10 秒干净单人声。`VoiceClonerSampleTest` 5 条钉住。

- [x] **视频通话（v0.58.0）**：语音通话之外多一个独立入口，实时声音仍来自同一套通话引擎，
  视觉层可插拔（`CallVisualEngine`：NONE / 伪视频 / Live2D）；伪视频用 Media3 + TextureView
  按通话状态切短素材（缺状态回退待机、全程静音、切歌不闪黑），Live2D 走官方 Cubism Java SDK
  （`:cubism-framework` 源码 + `Live2DCubismCore.aar`）+ 按播放头对齐的 RMS 口型驱动；
  形象上传沿用会话设置页两行（zip 模型包 / 多选 mp4 逐状态指认），Room v15 加
  `personas.videoVisualType`（旧数据 NONE）；**视觉失败只降级成头像，电话照打**。
  纯函数回归见 `CallVisualTest`。⚠️ 待真机验收（见 CHANGELOG v0.58 末段清单）。

- [x] **v0.58.1**：会话设置按用户给的**五组平铺**（无小标题、无折叠，组间浅灰缝 `GroupSeam`）；
  定时消息条目收进弹层；Live 2D + 伪视频 合成一行**「视频通话形象」**；
  **角色立绘**回到编辑人设页（`personas.artImagePath`，Room v16，`filesDir/portraits/`），
  AI 生图时当参考图发过去（图生图；不认参考图的家自动退回普通生图并明说"这张没按立绘生成"）。
  顺手修掉 `PersonaEditorSheet` 漏传 `hidden` / `videoVisualType` 会把形象绑定重置的存量 bug。

- [x] **v0.59**：**朗读**回来（长按任意一条念 / 停；「自动朗读」开关**按角色各开各的**，
  在该角色会话设置第一组最后那行，默认全关，
  AI 已发语音条的回复跳过）；**语音识别双引擎**（离线 SenseVoice ↔ 通用 OpenAI 兼容在线接口，
  在「识别模型」页手动切，**绝不自动回退**，失败把服务端原话报出来；通话始终走端侧）；
  **联网搜索第三家「自定义」**（URL + 独立一份 Key，通用请求形状 + 多路兜底解析 + 测试搜索）。
  ⚠️ 三条红线改写在 AGENTS §零.4：不复活"右上角喇叭"，不做在线→离线自动回落。

- [x] **v0.60**：视频通话真机反馈五条一次做完 ——
  ① **画面改全屏铺满**（cover：一致就铺满、不一致就放大居中裁边，不黑边不拉伸；
  有画面时页底不再铺白底，上下补暗色渐变托底，名字/时长/字幕走白色系）——
  以前画面是按比例排的卡片，竖屏素材会把字幕与静音/挂断挤出屏幕（"视频通话没法用"的根因）；
  ② 通话记录卡按类型显示「视频通话」+ 摄像机图标（列表预览 📹/📞）；
  ③ 删除消息**先本地摘掉再落库**，失败插回原位并报错（删除类操作当场见效）；
  ④ 「视频通话形象」两张卡片空 8dp；
  ⑤ 进视频通话的先决条件补"以文件为准"兜底（列 NONE 时读一次盘上的 metadata）。
  ⚠️ 待真机验收（见 CHANGELOG v0.60 末段清单）。

- [x] **v0.61 / v0.61.1 / v0.62 / v0.63**：视频通话画面改用 **TextureView** 承载（GLSurfaceView 的
  "挖洞 / 盖住 UI"两条路在 Compose 里都不通）；画面在**首帧真的画出来之后**才切布局；
  备忘本（v0.62 建、v0.63 收敛成"一句话 + 自动时间、全程不弹窗"）；菜单改紧凑（自绘 NeuMenu，
  M3 的 DropdownMenuItem 最小行高 48dp 压不动）；记账本流水长按可编辑。

- [x] **v0.64.0**：**Live2D 动作系统上线**，同时按用户点名把视频通话页重做。
  - **通话页**：上下分区（角色区 + 对话台），字幕与按钮**结构上不压角色**（引擎新增底部可视余量
    `setViewportBottomInset`，角色被投影收进上方那块）→ 不再需要任何蒙版；
    **右滑**把对话台整块横移出场进"纯净态"（角色铺满、顶栏保留）、**左滑**唤回；字幕收敛成 3 行窗口。
    画面层尺寸恒定（只改投影），避免滑动时 GL 表面反复重设导致的抖动。
  - **新模块 `com.menghuan.app.live2d`**（与通话解耦）：通用通道层（`GENERIC_*` → 该模型真有的参数）、
    **舞台通道**（整体位移/旋转/缩放走渲染矩阵、零参数依赖 → 翻跟斗任何模型都演得出来）、
    动作播放器（关键帧 + 淡入淡出 + 循环 + 强度/速度，写在物理与口型之前所以**口型仍归实时语音**）、
    能力探测与降级（缺参数不崩、跳过缺的、幅度自动收）。
  - **内置 8 条**：四态默认 + 摆手 / 摇头 / 劈叉 / 翻跟斗。全是程序化轨道，**换模型不失效**。
  - **两个新入口**（都在会话设置里）：「通话动作」（四态各绑一个动作，只绑定不编辑）、
    「角色空间」（换背景 / 拖动 / 点击互动 / 动作条预览 / 动作库收藏与长按菜单）。
  - **自己做**（选基础动作 + 强度速度时长 + 四个部位开关 + 表情，**全程无参数名**）与
    **AI 生成**（一句话 → LLM 结合模型能力 → 本地预览 → 确认保存；**只有生成那一步联网**）。
  - Room **v19**（两张新表，升级零风险）；纯函数回归 `Live2dActionTest`；
    助手手册补「角色与动作（v0.64.0）」整章 + `AiPromptsTest` 逐小节断言。
  - ⚠️ 待真机验收（见 CHANGELOG v0.64 末段清单）。

- [x] **v0.66.1**：Live2D 通话这条线做一次体检（**纯优化，无新功能**）。
  - **确定错的三条**：① `onSurfaceCreated` 原来"先 `getInstance()` 编译 → 再 `clear()` → 再 `deleteInstance()`"，
    等于每次数序变化留一批没人认领的 GPU program 还白编译一遍；② `AndroidView` 的 factory 重跑时
    两块视图的两个上下文会**同时驱动同一个模型**（新增 `viewGeneration` 判代，旧视图回调不碰模型）；
    ③ `load()` 抛出物被上层裸 `runCatching` 吞掉 → 角色空间永远停在"正在准备角色…"，
    现在引擎内部 swallow + 当场写 `Degraded` 原因，页面也真的把原因显示出来。
  - **省电与主线程**：暂停改成 `LockSupport.parkNanos`（原来停画期间每 20ms 还发一次 `eglMakeCurrent`；
    Kotlin 里别用 `synchronized+wait`，`Any` 看不见 `wait`，第一版就是这么编译失败的）、
    暂停状态交给重建后的新线程（否则"后台 + 转屏"会在后台跑渲染）；背景图解码真的挪进 `Dispatchers.IO`
    （注释早这么写但实现没做）；语音通话不再订阅通话背景表与 stat 磁盘；字幕从"每个 token 重铺整表"
    改成 80ms 节拍 + 先比签名（没人说话时一次都不铺）。
  - **帧循环**：几何兜底的包围盒 60Hz → 15Hz；舞台矩阵数组复用；`ActionPlayer` 删掉每帧的死变量分配、
    逐通道 `forEach{}` 换 `for`。
  - ⚠️ 待真机验收（见 CHANGELOG v0.66.1 末段五条）。**没做**：把 `character.destroy()` 挪到 GL 线程
    退出前执行（那才是删 GL 对象的正解，但动的是一条已被真机验证过的不变量，要连真机一起）。

- [x] **v0.67.0**：**视频通话与 Live2D 全面优化**（用户点名"全做 + 明显档 + 点角色与动作胶囊"）。
  - **动作承接层**（新增 `live2d/ActionBlender.kt`，纯函数 + 9 条 JVM 用例）：换动作 / 停动作 /
    循环绕回起点，全部**从上一帧那个姿势接着过渡**，不再"先弹回静止位再淡进来"。
    淡完就 `owns()=false` 把参数**还给模型自己的 Idle**（一直写 0 等于把它按在静止值上，
    表现是"播过一次摆手，角色的待机动作从此没了"）。旋转起点折到最近的那一整圈。
  - **舞台旋转改绕角色中心**（`StagePose.fillNdcMatrix`）：以前绕 NDC 原点 = 屏幕正中，
    而通话页把角色抬到上半屏 → "翻跟斗"看着像贴着大圆弧绕屏幕转。
  - **帧节拍封顶 60fps**（`GlTextureView.loop`）：原来只靠 swap 背压限速，120Hz 机器上满速跑一百多帧，
    GPU 白做两倍、发热降频 → 就是"打久了越来越卡"。**回前台先归零动画时钟**，`dt` 上限 100ms → 1/30s。
  - **四态默认动作提亮**：轨道峰值 0.03~0.17 → **0.16~0.42**（±10° 的头会动 3°~8°）。
    同时把内置动作里的 `GENERIC_EYE` 全删——框架的眨眼是 `setParameterValue` **覆盖**，
    免费模型（没有 motion 文件）每帧走覆盖那一路，写了静默失效、换张模型又突然生效。
  - **通话页新增互动入口**：点角色按头/身体/空白分区演对应动作（与角色空间同一套判定与绑定，
    落在对话台上不算）+ 角色脚边一排动作胶囊（收藏优先，**上限 4、刻意不做成可横滚**——
    横滚容器会跟"右滑进纯净态"抢同一根手指；只有 Live2D 出现）。
    一次性动作演完由 `finishedCount` 读数**接回当前状态那条**，不做跨线程回调。
  - **右滑手势重写**：点按与横滑合进一个 `awaitEachGesture`，拖动期间只写普通浮点，
    松手才交给 `Animatable` 跑弹簧（原来每个事件 `scope.launch{snapTo}`，一次拖动排 60~120 条协程）。
  - **「动作幅度」四档**（`live2d/ActionScale` + 会话设置「通话动作」页顶一行，DataStore `call_action_scale`）：
    小 / 标准 / 大 / 特大，按角色记。只做四档不做滑块——每条动作已经有一个"强度"滑块了。
  - **动作设计系统收紧**：删掉四处说明性小字；「通话动作」选择器不再列出四态默认；
    编辑器强度/速度上限改取数据层常数（原来写死 1.8，与 `MAX_AMPLITUDE=2.0` 两套口径并存）。
  - 手册同步 4 条定点 replace + `AiPromptsTest` 一整条新用例（并把 v0.64 里被改写的"只做绑定"断言跟上）。
  - ⚠️ **本轮全部改动没上真机**：动作用户看不看得出来、翻跟斗是不是真在原地翻、滑动跟不抖、
    发热降没降，都要装机定论。测试形象用**小柚铃**。

- [x] **v0.68.0**：**按全面验收报告修「AI 提示词」模块**（用户圈"全修 P1+P2+P3"）。
  - **P1 补第九段**：「每日建议策略」的 DataStore 键与生成链路读取从 v0.42.0 就在、注释还承诺"可在设置里覆盖"，
    唯独页面没这一格 —— 接进 `PromptSettingsScreen`（框架只读 + 策略可编），全链路"八段"改"九段"。
  - **P2 修助手改名断链**：新增 `PersonaRepository.findAssistant()`（先按名字，再认"内置手册形状"），
    提示词页读/保存、启动升级、`ensureAssistant` 四处全改走它；人设编辑器对内置助手的**名字锁死**。
    原来改个名会同时：提示词页显示出厂手册、保存多建一条助手、**手册永久停在改名那一刻**（不报错不崩）。
  - **P2 手册补四块**（dump 实测出来的缺口）：数据页真实五块顺序 + **今日建议整块**（07:30 自动出、
    长按转任务、中央弹窗看全文、与「AI 建议」是两回事）、会话设置行名与"立绘 / Live 2D 只存不显"作废、
    提示词库九段、**平板 / 折叠屏双栏**（≥600dp 自动、无开关）。
  - **AGENTS.md 自己也滞后三处**已改：§四 消息页搜索（v0.56.8 已整条删）、§三 八段、§零.5「全部恢复默认」按钮。
  - 验收方法记进 CHANGELOG：临时测试把合成后的手册全文 dump 出来 + 脚本扫 182 条 `replace` 锚点，
    改完再 dump 一次确认新句在了、旧句没了（`.replace` 静默落空不报错）。
  - ⚠️ 两条判错的结论也在报告里更正了：「会话找回」「在线识别」手册其实**有写**，
    是我按单个关键词 grep 没命中 —— 判"有没有"要用实文核，不能只按一个词。

- [x] **v0.73.0**：**动态（推特式时间线）+ 备忘本整块下线 + 任务页从底栏搬进数据页**
  （用户：不需要备忘录功能。也不需要移数据过去，重新构建类似推特的帖子UI设计、
  你直接做完所有工作，我最后拿个包就行了）。
  - **一张表两种行**：`moments`（Room **v21**，`MIGRATION_20_21` = 建表 + `DROP TABLE memos`，**不迁旧数据**）；
    口径全在 `data/MomentMath`（帖子倒序 / 回复正序 / 孤儿回复丢弃 / 点赞串幂等翻转 / 相对时间五档），8 项单测。
  - **UI** `ui/moment/MomentsScreen.kt`：时间线（抬头 + 正文 + 回复数 / 心）+ 右下悬浮发帖钮 + 详情页（`moment/{id}`，
    回复平铺 + 底部回复栏）+ 长按震一下弹菜单删除。空态三态照会话列表 v0.43.1（`loaded` 从同一条 combine 派生）。
  - **AI 发帖 / 回帖**：标记协议 `[动态:…]` / `[动态#id:…]`（`data/MomentMarker` + 9 项单测），
    与 `.add` 同族；回帖**先验 id**（编出来的编号丢弃 + 日志）、一轮最多 2 条、AI 只有 insert。
    可回清单走**易变上下文**（`momentFeedBlock`），协议说明走 system prompt —— §零.9 那条缓存纪律。
  - **结构换位置**：底栏第 3 格 = 动态（`MIcons.Globe`/`Reply` 新增），任务页 = 全屏路由 `tasks`
    （入口在数据页 `tasks_entry_row` 那张卡）；`menghuan://open/tasks` 的 deep link 跟着搬到任务页身上，
    小组件与三处任务提醒通知点进来仍然落到任务页。
  - **手册同步**（§零.5）：3 条定点 replace（锚点全部从**成品手册全文**逐字截、跑了一次 dump 核对每个只命中 1 次）；
    新增 `v0_73_0 手册写明动态与备忘下线` 双向断言，并把 `v0620 备忘本` / `v0650 备忘本入口卡` 的备忘断言
    **整批翻成 assertFalse**（模块没了，手册留着旧说法就是让助手教不存在的功能）。
  - **验证**：全量 `testDebugUnitTest` **559 项 / 5 项红**。新增 `MomentMathTest` 8 + `MomentMarkerTest` 9 +
    `render41Moments`（空态→发帖→上屏）全绿；5 项红里 `UiRenderTest.render19LedgerPage` /
    `render28TtsConfigScreen` **已用 HEAD 独立 worktree 复跑确认本来就在红**，另三项是既有红。
  - ⚠️ **本批全部改动没上真机**：动态页的手感、AI 发帖的频率是否讨嫌、Room v20→v21 升级是否干净，都要装机定论。
  - **未做（记录在案，别当成已交付）**：P2 历史摘要（OptMem 式写入即合并 + `In-chat @ Depth`）、
    端侧向量检索、`<search:>` 拉取式补强。
- [x] **v0.72.0**：**触碰会说给她听 + 五轴心情引擎**（用户："你直接做完所有工作，我最后拿个包就行了"）。
  - 点角色除了播动作，还把"用户刚刚摸了摸你的头"送进**下一轮的易变上下文**：不进 `turns`、不进字幕、
    一次请求 consume 一次、最多攒 3 条；`OTHER`（空白处）**不许谎称被碰到**。
    只有三区是刻意的：免费模型 HitArea 基本只画 Head/Body，硬凑 11 个部位会让模型说没发生过的事。
    语音通话 / 伪视频走接口默认空实现（没有形象就没有"被摸"这回事）。
  - `data/CompanionState.kt`：五轴连续漂移 + 互相制衡（连接需求堆高会**侵蚀骄傲**，嘴硬有保质期）+ 确定性；
    `promptContext()` 把数值翻成人话（模型不需要知道 `connection=0.42`，只需要知道"想找他又拉不下脸"），
    **空状态返回空串**。13 项单测钉单调性与边界。
  - 主动关心动机化：低于 `CONSIDER` 这一轮不发且**不消耗配额**；用户设的时间窗与条数仍是硬上限；
    跳过时 `Log.i` 带档位。用户回消息 → 连接清零 + 骄傲软化，且放在**消息落库成功之后**。
  - **两个真 bug 是这次的新测试抓出来的**（不是断言写错）：空状态也返回一句白占 token；
    加速项 `pow(1+connection,1.5)` 在 connection=0 时恒为 1、第一次 tick 根本不加速而注释写着"越沉默越快"
    → 补一个随沉默长度上升的因子，幂次保留（它是"越想越继续想"的复利），注释改成说真话。
  - 手册同步 1 段（【她为什么会主动找我】），`AiPromptsTest` 新用例 5 条断言含计数断言。
  - **出包**：`梦欢-v0.72.0-release.apk`（36.3MB / versionCode 161，debug 证书与历次同一张，可覆盖升级）；
    根目录老包 v0.68.0 已 mv 进 `archive/`（现 87 个，未删任何一个）。全量单测 **509 项 / 3 失败**（三个历史红）。
  - ⬜ **本轮仍未做**（用户说"做完所有工作"，但下面这些我没塞进这个包，因为再动就要拆页面结构、风险盖过收益）：
    **朋友圈/动态 + 任务页与备忘本的位置对调**（用户那条结构指令，XL，未动）、
    **P2 历史摘要**（OptMem 式搭车写入 + `In-chat @ Depth` 注入位）、端侧向量召回、`<search:>` 拉取式。
  - ⚠️ **真机零验证**：触碰措辞自不自然、心情会不会让她变得太主动/太冷淡，全都要装机调。截图测试没跑。

- [x] **v0.71.0**：**「TA 的记忆」页 + 待入库审批**（用户："继续，不要让我选"）。
  - 新页 `ui/memory/MemoryScreen.kt`、路由 `memory/{personaId}`、入口在 **单聊页右上角三点 → 会话设置 →「TA 的记忆」**；
    有未确认条目时那一行写「**N 条待确认**」（新计数查询 `observeStagingCount`，不为数一个数把整表拉进组合层）。
    **只显示当前这个角色的记忆** —— 与 v0.70.0 的分人设边界一致。
  - 四段：待入库 / 记住的 / 永久 / 已忽略；空段整段不占位。动作：记住·不要记 / 永久·停用 / 取消永久 /
    停用可恢复、拒过的只能「彻底删除」。
    ⚠️ **`rejected` 行不跟着角色删、不给一键清空**："拒过某句话"本身就是数据，删了它 AI 下次会再记一遍。
  - **`<add:内容>` 从此落 `staging`**：v0.70.0 先落 `active` 是因为当时没有审批入口 ——
    **次序不能反**（没有入口的暂存区 = 用户看不见的死功能）。已在库的不降级、拒过的永不写回。
    这条已写进 AGENTS.md §零.9 当"不许回退"。
  - 手册同步 1 段（入口 + 四段名字），锚点**复用前面某条 replace 产出的句子**并先 `grep -c` 确认它唯一
    —— 这是 v0.69.0 锚点静默落空之后定下的选锚办法。`AiPromptsTest` 新用例含计数断言防应用两遍。
  - **验证**：`compileDebugKotlin` 通过；全量 `testDebugUnitTest` **494 项 / 3 失败**
    （仍是已证明在 v0.67.0 就挂着的 AiCall 钟点顺延 / TaskActions 勾选回退 / TtsConfig 自定义音色）。
  - ⚠️ **新页没上真机、也没跑截图测试**：长句子在四段里的换行、一行三颗钮的挤压，装机才看得出来。没出包。

- [x] **v0.70.0**：**记忆按人设彻底分开（P1 数据地基）**（用户："记忆按人设彻底分开，但用户可以设置自己的个人档案全部 AI 可读"）。
  - Room **v19 → v20**：`profile_facts` 三条 `ADD COLUMN`（`personaId` / `category` / `lifecycle`）带默认值，
    **不重建表、不动一行数据**；既有行落 `personaId = 0`，那正是它们原本的语义（对所有角色可见），
    所以不需要用户逐条挑归属（用户："现在都没有老用户"）。
  - 新增 `data/MemoryScope.kt`（全纯函数）：`visible()` 是**安全边界**——只给"本角色 + 允许共享的基础档案"，
    `rejected` / `archived` 一律不进 prompt；`core` 置顶、其余按 id（按 updatedAt 排会让注入内容每次编辑后
    整体跳动，前缀一抖缓存白丢，§零.9）。六个调用点全改走它：聊天 / 实时通话 / AI 主动来电 / 定时消息 /
    主动关心；**每日建议只看基础档案**（它不属于任何角色，喂进各角色私事等于绕过分开的边界）。
  - **同名判据也要分人设**：提炼合并从全表 `getByKey(key)` 改成 `findScoped(key, personaId)`，
    否则"分开"只是表面 —— A 角色聊出的"口味=辣"仍会覆盖 B 角色那条。
  - 删角色级联清掉它自己的记忆（SQL 写死 `personaId != 0`：**用户本人的基础档案永远不跟着角色删**）。
  - 新开关「**全部 AI 可读**」入口在 我的 → 基础档案 页最上一行，**默认开**（默认关会让用户升级完
    发现每个角色都不认识自己，那是静默倒退，不是"分开"的本意）。基础档案页现在只列 `personaId = 0`
    那一档；以前各角色被提炼的记忆会混进这张"我自己的资料"页。
  - 词法检索层先跑起来（CJK 相邻双字 bigram + 命中占比打分 + 阈值 60，与爱语同口径），
    `MemoryScopeTest` 10 项含两条硬边界：**被拒过的记忆永不回归**、**先筛范围再算分**。
  - 手册同步 1 段 + `AiPromptsTest` 成对断言（含两处计数断言防重复插入）。
    ⚠️ 这段文案改错两次都是**当场编译或当场断言抓住的**：一次把上一版的 `## v0.69.0` 标题吞了、
    一次把 `{N}` 占位在脚本传参时吃掉一层反斜杠（写出来是 `"\\n"` 而非换行）。
  - **`<add:内容>` 记忆写入标记**（`data/MemoryMarker.kt`）：认全角冒号；**标记独占一行时连换行一起吃**
    （不在剥完后统一压空行 —— AI 正文本来就有分段空行，压它会连段落一起毁，`VoiceNoteMarker` 同族口径）；
    剥净才落库显示；整条只有标记时给 `💭 记下了` 占位（**不能留空白消息**，否则用户看到"没反应"，
    实际发生了一件他不知道的事）；标签取句首 24 字 → 相近句子合并成更新而非越堆越多；`rejected` 过的永不写回。
    ⚠️ **这一版直接落 `active`、不走 `staging`**：`visible` 不注入 staging，没有记忆页可以点头之前
    写成 staging 等于做一个**用户看不见的死功能**。暂存区必须与审批入口同批落地。
  - **记忆回灌 = 宿主主动推，不等模型发 `<search:>`**：拉取式要"答一半→喂结果→再问一次"，
    每轮多一次请求多一笔钱，而且模型不一定记得发标记（不发时它凭印象编，用户看不出区别）。
    推取式零额外请求；阈值 60、top 3、**只在本角色可见范围内搜**，结果走易变上下文（§零.9），
    并写明"不要一条条念给用户听"。`MemoryScope.search` 留着，以后拉取式做成补充不是替换。
  - 手册再加一段（AI 会自己判断什么值得记、下次会主动想起；用户问「你怎么知道」要答得上来）。
  - **验证**：全量 **493 项 / 3 失败**（仍是那三个 v0.67.0 就挂着的）；新增 `MemoryMarkerTest` 9 项。
    这轮两条失败**是我的断言写错了**（把正常段落空行当 bug、`keyOf` 用了不足 24 字的句子验截断），
    同时暴露一个真问题（标记独占一行留空行）→ **改了实现也改了断言**，不是放宽测试求绿。
  - ⬜ **P1 剩下的（下一轮）**：用户可见的**记忆页** + `staging` 待入库与审批（入库/拒绝）、
    `<search:>` 拉取式补充、端侧向量召回（装完再提示下载）。
  - ⚠️ **没上真机**：三条 ALTER 在旧库上的效果 JVM 测不出来；记忆写入的实际质量（会不会记一堆废话）
    只有装机用几天才知道。
  - ⚠️ **没上真机**：三条 ALTER 跑在旧库上的效果 JVM 测不出来；分角色之后用户的直观感受要装机才知道。

- [x] **v0.69.0**：**案例对标后的 P0 第一批 —— 把已经在手上但被扔掉的信号捡回来**（用户："全收进一个版本"）。
  - **前缀缓存**：`usage` 里新增 `prompt_tokens` 与 `cached_tokens`（认三种方言，全 0 返回 null 不编数），
    设置 → 模型配置 顶部显示命中率；⚠️ 没读数时整行不显示（画成 0% 会骗用户去改对的东西）。
    **会话联网的搜索结果从 system prompt 搬到最后一条用户消息**——它每轮都变，留在前缀里等于每次联网
    都把「人设+档案+能力说明」作废一次；这是本批唯一直接省钱的那一条。
  - **当前时间/星期**：新建 `data/RuntimeContext.kt`（纯函数），聊天与通话共用一份措辞。以前根本没告诉过模型。
  - **打断收口**：通话引擎在下一次请求里给最后一条 AI 轮补"……（被用户打断了）"；
    ⚠️ 不写进 `turns`（那批整条进通话记录，写了等于伪造用户话）。
  - **语气读数**：SenseVoice 的 `emotion/event` 接回来（javap 对过 aar），**共用同一条解码路径**，
    不另开第二个 session（228MB×2 ≈500MB 会 OOM）。表外标签不提、NEUTRAL 不写、只进请求不进字幕。
  - **Live2D 自换表情**：`[表情:名]` 标记族，候选来自 `onCatalogReady` 内存快照（⚠️ 不读库，
    库里 `native:expr:` 行是异步写的，读库=第一次通话清单空）；清单空则整段不注入；
    TTS 之前剥离，字幕另走 `stripForDisplay` 防"半个标记闪一下"。
  - 手册同步 5 条定点 replace，每条配 `AiPromptsTest` 成对断言（新增两处**计数断言**防重复插入）。
    ⚠️ 其中「表情段」锚点第一次**静默落空**（原挂的排障行早被前面的 replace 改写），是配对断言当场抓住的——
    这条实测过程就是 §零.5 立规矩的理由。
  - **验证**：全量 `testDebugUnitTest` 473 项 / 4 失败；其中 3 项（AiCall 钟点顺延、TaskActions 勾选回退、
    TtsConfig 自定义音色名单）**已用 HEAD 独立 worktree 复跑，在 v0.67.0 上一样红**——仓库长期挂着的测试，
    非本批引入，修不修等用户发话。截图测试与出包都没做。
  - ⚠️ **本批全部改动没上真机**：命中率是否真的上涨、打断语气与表情是否自然，都要装机定论。

- [x] **下一版候选**（v0.74.0 已完成前三条，用户拍板"不用角色拖动位置持久化"）：
  - [x] 「点击头部 / 身体 / 空白 → 播哪个动作」的**配置界面**（v0.74.0：新增 `interaction_bindings` 表 +
    「点击互动」弹层，点头部/点身体可指定任意动作，点其他固定"随机趣味动作"；未指定走内置默认）。
  - [x] 动作编辑器的**高级模式**（参数级微调）（v0.74.0：逐通道幅度倍率 0.2×–2.0×，
    `ActionComposer.scaleTracks` 纯函数 + 编辑器"高级"折叠区；**刻意不做逐关键帧编辑**）。
  - [x] 角色空间背景支持**视频**（v0.74.0：`StageBackground` 三档 Preset/Image/Video，
    `StageVideoBackgroundEngine`（ExoPlayer + 裸 TextureView，静音循环）；
    通话背景与角色空间共用同一层；导入有 80MB / 30 秒 / 可解码三道校验）。
  - [ ] 角色的拖动位置持久化 —— **用户明确不做**（v0.74.0 拍板），此条关闭。
