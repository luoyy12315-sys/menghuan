# 梦欢 · 项目长期记忆

> 仅保留仍有约束力的结论；版本细节见 `docs/CHANGELOG.md` 与 `.workbuddy/memory/2026-*.md`。

## 最高优先级
- 新功能/疑难修复先搜 GitHub 成熟案例（中英文关键词、`android ... sample`、`awesome-android ...`），再适配；没把握先说明，不做试错式连改。
- 有歧义或多条路线先问清效果、场景、代价，让用户选；讲方案先描述/画出结果，不先抛实现术语。
- 测试、编译、打包由用户决定；默认改完源码即停。一批需求只算一个版本，不拆版本。
  ⚠️ **版本号用两段式**（`0.57`、`0.58`… 每次第二位 +1）——**不要再出 `0.56.17` 这种三段补丁号**
  （2026-10-07 用户拍板，原话"应该是0.57这样的啊。哪有0.56.17这么神奇的东西"；
  已出货的 0.56.17 / versionCode 138 保持不动，不重命名不重出）。
- 功能入口/行为变化必须同步 `AiPrompts.DEFAULT_ASSISTANT_PROMPT`；外部服务地址必须查证官方来源。
- 键盘与语音现行栈未经点名不动；数据当前只存本机。

## 构建
- JDK17：`C:/Users/16621/.workbuddy/toolchains/jdk17`；Android SDK 同级；Gradle 8.14.3 用本机分发，项目无 wrapper，勿用 `gradlew`。
- ⚠️ **`gradle` 不在 PATH**（直接敲会 `command not found`）：用
  `C:/Users/16621/.gradle/wrapper/dists/gradle-8.14.3-all/10utluxaxniiv4wxiphsi49nj/gradle-8.14.3/bin/gradle`。
- `JAVA_HOME` 必须 `export`，`PATH` 加 `$JAVA_HOME/bin`；日志写 `logs/`。
- 中文路径 JVM 构建先 `subst X: "C:\Users\16621\Desktop\梦欢"`，再从 `X:` 构建。
- Compose 静态检查不能替代 `:app:compileDebugKotlin`；`scrollBy` 需导入 `androidx.compose.foundation.gestures.scrollBy`。
- 本版本 Compose 的**导入路径坑**（都踩过）：`pointerInput` = `androidx.compose.ui.input.pointer.pointerInput`；
  `Modifier.blur` = `androidx.compose.ui.draw.blur`；`boundsInRoot()` 要 `import androidx.compose.ui.layout.boundsInRoot`；
  **没有 `positionInParent()`**；`androidx.compose.ui.unit.lerp` 只有 **Dp** 重载，
  Float 插值直接写 `a + (b-a)*f`，别去找 util.lerp。
- 勿删 `local.properties`、`android.overridePathCheck=true`、阿里云 Maven 镜像。
- 出包：升版本 → assembleRelease → 根目录 `梦欢-v<版本>-release.apk` → 上版入 `archive/` → CHANGELOG/助手手册/断言同步。
- 出包命令里 `java -version` 报 `java: command not found` 是**假警报**（该 subshell 的 PATH 没生效），
  Gradle 走 `JAVA_HOME` 照常跑通 —— 判据只看 gradle 的 `BUILD SUCCESSFUL`。
- `aapt` 读不了中文路径（`Illegal byte sequence`）：要验版本就 dump `app/build/outputs/apk/release/app-release.apk`，
  再用 md5 确认根目录那份与之逐字节一致。

## UI / Compose
- Figma 风：黑白骨架；品牌色只作内容点缀。令牌与组件统一走 `ui/common/Neu.kt`；图标只用 `MIcons`。
- 核心色：背景/表面白，轨道 `#F2F3F5`，发丝线黑 8%，主强调黑，危险 `#F24E1E`；强调色上文字/图标跟随 `AccentInk`。
- 动效弹簧优先；进出场避免边缩边 `fadeOut`；比例尺寸用 `LocalContentWidth`。
- `neuSurface(intensity=0f)` 会整条透明；凹陷灰槽用 `inset=true`。
- 颜色令牌在 composable 先取局部变量再进绘制 lambda；自绘描边内缩半个线宽。
- 独立 composable 不直接用 `Modifier.weight`；流程状态放 UiState；过滤节点直接不组合，不用 alpha=0。
- 动画容器不直接挂 `imePadding`；小尺寸自绘图标最终需截图放大验收。
- **底栏可横滑切页**（v0.56.9 定稿，用户点名"黑色块可以滑动切换屏幕"）：
  底栏加 `detectHorizontalDragGestures`，手指**每跨过一格**（`cellPx`）就
  `animateScrollToPage(startPage + 已跨格数)`；黑块跟手 = **整数页号的弹簧位置 + 拖动残余位移**
  （松手残余回 0）。⚠️ 两条别再踩：`dispatchRawDelta` 在这里**根本不生效**（只在 Pager 自己的
  滚动流程里有效）；黑块位置**不许**改成 `currentPage + currentPageOffsetFraction` 连续跟随
  —— 那会把弹簧过冲吃掉，用户点名要的"Q 弹感"就没了。
- **编辑人设的 emoji 头像**（v0.56.10 定稿）：`Int.MAX_VALUE` 格子的惰性列表做**循环滚动**
  （`index % size`；起点 `(Int.MAX_VALUE/2/size)*size` 保证对齐）；
  **放大镜 = 左边那个头像框**（不是那排 emoji！）：滑到的 emoji 以
  `NeuAvatar(emojiScaleX/Y)` 从放大态（1.42/1.16）**压回** 1.0，弹簧过冲 = "挤进去"；
  那排 emoji **只做大小/透明度渐变，绝不加模糊**（用户点名"不要模糊"）；
  **焦点锚点 = 头像框自己的中心**（NeuAvatar 量 `boundsInRoot().center.x`），emoji 排
  **从头像框底下穿过**（LazyRow 铺满、框画上层），滚到框位的那一个 = focus=1 = 被选中
  （`contentPadding(start=6dp)` 让静止时中心正好落框心：56/2−44/2）——
  "滚进框里 = 最大 = 选中"三合一，别再拆成两个锚点；
  头像名单 `avatarPresets` 已含外星人/机器人/幽灵等幻想形象（v0.53.4 那条"只用人的形象"已被放宽）。
- **改交互/动效前必须先问**（v0.56.9 教训）：我自己拍的 90ms 延迟、中间圆环镜框、
  把弹簧改成连续跟随 —— 三处都被否，用户质问"改之前为什么不问我"。
  涉及观感（延迟、模糊、弹性、动画幅度）一律先问再做，别拿默认参数硬上。
- 异步数据必须区分「未加载/空/有数据」；loaded 从主数据流派生，不另发 Room 全表查询。
- 页面 pop 时底栏显隐与 push 过渡同步，避免导航状态先切导致闪跳。
- **聊天页那条浅灰线：至今未定位，源码已回退干净（不要再猜着改）**。已量到的两条常驻横线
  （AI 气泡上一条的底边 y≈676、下一条的顶边 y≈883）用户都明确说"不是"；
  顶栏下沿那条全屏线用户明确**要保留**。它是**间歇**出现的（"有时候有有时候没有"），
  用户给的**裁剪图里没有那条线**（逐像素扫过：气泡左侧全白）→ 裁掉了。
  ⚠️ **下一步必须先拿到"线出现瞬间的整屏截图（不裁剪）"**，定位到具体绘制元素前不动任何渲染代码。
  已试过并已回退的两版：①AI 气泡改浅灰面+去描边 ②`neuSurface` 加 `stroke=false`。
  诊断手段记牢：**PIL 逐像素扫 + 灰度骨架图 + 每条线灰度统计**（看缩略图肉眼会看错）。
- **依赖"被组合"触发的动画在 Pager 里必然失效**：四 tab 同时组合，动画在屏外播完。
  金额滚动（`RollingAmountText.restartKey`）、本周回顾、整页错峰一律由外部可见信号驱动。
  ⚠️ `RollingAmountText` 的重播实现是**外层 `key(restartKey)` 重新挂载 + 内层 `LaunchedEffect(Unit)` 播种**，
  两条调用点（数据页卡片 / 记账本页）走**同一段代码** —— 不要再改回"改自身状态那套"（重组时机一变就被跳过）。
- 自绘组件带"可选装饰层"时，**装饰层不能决定容器尺寸**（否则有/无装饰时内容位移）；
  尺寸钉在容器上（如通话页头像画布 196dp）。
- 只在渲染层消费的动画值（如头像呼吸）用 `graphicsLayer` 延迟读，避免逐帧重组；不要为省事把它换成"按状态开关"，那是两态尺寸不一致的来源。

## 对话滚动 / 键盘
- 首次定位 `scrollToItem`；后续仅 nearBottom 时跟随。流式末项变长要按高度差 `scrollBy` 补滚，不能只靠 LazyColumn 锚定。
- `KeyboardInsets.kt` 是唯一键盘避让实现：导航条基线 + `WindowInsetsAnimationCompat` 逐帧 inset；不二次插值、不双层避让、不把面板高度混进 bottomInset。
- 面板/键盘互斥由统一入口及 `ImeDismissesPanels` 兜底；不拿焦点变化当键盘显隐信号。有弹层页面需分层处理返回键。

## 语音硬约束
- ASR 唯一实现 `SherpaAsr`（SenseVoice 离线非流式）；语音消息与通话共用实例。模型由 `AsrModelManager` 下载到 `filesDir/asr_sensevoice/`，就绪靠 `.verified` + 文件字节数。
- 采集仅 `VoiceRecorder`（AudioRecord 16k/mono/PCM16 + WAV），不使用 MediaRecorder。
- TTS 三家协议分别实现；CALL=WAV/24000，NOTE=MP3；采样率唯一常量 `TTS_CALL_SAMPLE_RATE`。
- 音色名单统一由 `TtsVoices.library()`；克隆音频走 `MediaAudioDecoder`，重采样必须 `WavPcm.resample`，样本先 `trimToSpeech`；服务端名称用 `asciiVoiceName`。
- `WavPcm` 保持支持 PCM 8/16/24/32、float、EXTENSIBLE；勿收窄。
- 构建保持：`noCompress` 仅 `onnx`、ABI 仅 `arm64-v8a`、sherpa JNI keep 规则不动。

## 结构与业务不变量
- 四 tab：消息/数据/任务/我的；平板判定唯一来自 `LocalWindowWidthClass`。
- 对话页三点菜单固定九项：编辑人设、聊天记录、主动打电话、角色立绘、Live 2D、聊天背景、联网、音色、模型。
- 数据页顺序固定：本周回顾 → 今日建议 → 记账本 → AI 洞察/周报。
- 「通话行为」页两张同级卡：**语音通话铃声** 与 **接听延迟**（路由 `call_answer_delay`）；
  接听延迟**不再挂在铃声页里**（v0.56.5 起）。铃声页只管铃声。接听延迟是**自定义秒数 0~600**（v0.56.6，非档位）。
- **电话 = 「主动电话」+ 「定时电话」**（v0.56.7 起更名/改结构）：
  电话弹层**两行都点标题才展开**、**页内不留说明小字**；
  「主动电话」= `AiCallRule.enabled` + 展开后的「几天没聊就主动打」（`idleDays` 1–30）与「每天几点检查」（`rule.time`，默认 21:00）
  —— 到点检查一次，够天数就主动打（静默时段 22:30–08:00 不打、每人设每天最多一次），规则闹钟在 `AiCall.rearm` 里排；
  「定时电话」= `AiCallRule.scheduledEnabled` + 一条条 `ScheduledMessage(kind = "call")`（与定时消息**共用存储/闹钟/编辑表单**），
  `ScheduledMessageReceiver` 按 kind 分流到 `ScheduledMessageSender` 或 `AiCall.ring`；
  `AiCall.ring(byExplicitSchedule = true)` 只看那两个开关，**不看静默时段、不占每天一次名额**。
- **数据页「记账本」卡片金额 = 滚法照搬记账本页 + 切页也重播**（v0.56.10 定稿，用户拍板：
  "切到数据页要跳，跳法就是**从 0 滚到余额**"）：
  `LedgerEntryCard(rollKey = ledgerRollKey)`，`ledgerRollKey` 由 `visible` 驱动、**可见即 +1
  不加延迟**（"延后 90ms"那版被否：盯着一串 0 停一下才滚，观感更差）。
  滚动时长 = **620ms**（v0.56.10 用户点名，与记账本页现金余额卡同一档；520ms 那版被嫌丑）；
  **`LedgerEntryCard` 不套 `staggerEnter`**（v0.56.10 用户点名"不需要错峰入场动画"，
  金额滚动就是这张卡的全部动效；错峰只留 0/1/3 三块）。
  ⚠️ 前几版各栽各的，别走回头路：v0.56.5 直接拿 visible 当 `restartKey` → **没生效**；
  v0.56.7 `key(restartKey)` 立刻重挂载 → 生效但**在切页那一帧开滚**，观感"卡着在那跳"；
  v0.56.8 不传 → 只在屏外播一次，**切页永不跳**。
- **弹层里不要"点击之后的灰色条"**（v0.56.8 用户强调"我都强调多少遍了"）：
  可点区域一律 `clickable(interactionSource = …, indication = null)`，不画 ripple；
  **`SettingsLineRow` 的按压灰条已删**（只剩箭头按压时轻微右移），不要再加回来。
  展开/收起要有**箭头提示**（收起朝右、展开朝下，带旋转过渡）。
- **人设主动关心 = 随机时刻 + 按角色一套 + 条数是上限**（v0.56.8 第二批）：
  **每个角色各一套**时间段与条数（`persona_proactive_window` / `persona_proactive_limit` 两张 id 表；
  全局那两个键已删），**每个角色一颗闹钟**（`PROACTIVE_PERSONA_BASE + id%10000`，extra 带人设 id，
  旧全局闹钟重排时 `cancelProactive` 取消）；`ProactiveChat.nextFireAt` 在它自己的窗口里随机取下一次
  （纯函数、支持跨零点），发完在剩余窗口继续随机，窗口过完或当天计划发完就排明天；
  **「每天最多几条」是上限不是定额**：什么数字都能填（上限 999），当天第一次要发时在 `1..上限` 里
  随机摇一个计划条数落盘、当天不再重摇；计数按角色存 `day:已发:计划`（老数据 `day` 按 1 条读）；
  提示词里带上当前时刻与"早上/中午/下午/晚上/深夜"，让它说贴合时间的话；
  **开关在会话设置的「人设主动关心」弹层里**（弹层还放这个角色的时间段与条数）；
  弹层容器 = `NeuSheetScaffold`（跟「模型选择」同一个**从底部升上来**的弹层，用户点名要的就是这个，
  **不是排版**——别再照「模型配置编辑弹窗」改字段布局）。
- **搜索只在设置页**（v0.56.8 第二批）：**消息页不做搜索** —— 顶栏放大镜也一并删了
  （`SearchOverlay`/`SearchResultUi`/`SearchResultRow`/`runSearch` 全删）；
  设置页保留顶栏**常驻**放大镜，点一下直接进搜索态并自动聚焦 + 弹键盘
  （`SettingsSearchField` 有 `focusRequester` 参数）。
- **「按住说话」档位按屏幕位置划区**（v0.56.7）：左半区 = 取消、右半区 = 转文字、
  正中间与蒙层缺口同宽的灰带 = 录音中；手指动过 `touchSlop` 才开始判档（否则一按就取消）。
  取录音条在屏幕上的左边距用 `boundsInRoot()`（本项目 Compose 版本里 **`positionInWindow()` 不可用**，编译会报 Unresolved）。
- **API 用量按模型分别记**（v0.56.7）：`usage_by_model` 表（`月键\t模型\ttokens`）+ `monthlyUsageByModel`；
  六个记账点（对话/提炼/周报/每日建议/Agent/识图）都必须传 `ApiConfig.model`。
- 音色弹层（`PersonaVoiceSheet`）是**纯展示**、**无说明小字**：名单由会话设置页**打开前预加载**（克隆音色读本机缓存）后传入。
- 会话设置「模型」弹层**没有「跟随全局」**（v0.56.6）；没单独选过时列表里勾的是全局默认那条。
- 加号面板的**相册 / 文件**：选中后落在输入框上方的 `PendingAttachment` 待发送条（可补文字一起发/直接发，✕ 丢掉，**条上不写说明文字**），
  **不再选完即发**；`sendFile` 带 `caption`（用户的话在前、抽出正文在后）并清空输入框。
- **会话联网的查询串取"最后一条用户消息"**，绝不读 `_uiState.value.input`
  —— `send()` 在建 prompt 之前就把输入框清空了，读 input 等于永远不搜（v0.56.5 修）。
- 联网搜索测试用 `WebSearchClient.searchOrThrow`（失败抛异常带服务端原文）；
  业务链路用 `search`（吞异常返回空表，绝不编内容）。
- 音色名单在服务商克隆音色上**先读本机缓存 `tts_cloned_cache`（按服务商各一份）再后台刷新**，
  否则克隆那几条永远晚一个网络往返才冒出来。
- deep link 域名是 `menghuan`；编辑人设是独立路由页。
- AI 语音条合成成功后原地升级原消息；播放结束必须主动复位。
- Room 变更同步实体、版本/迁移、注册迁移与 schema；金额以分存 Long，余额由流水累加。
- LLM 历史必须带角色与虚拟角色背景；上下文预算按字数，封顶 20 万。

## 助手手册 replace 链
- 新 `.replace()` 只追加链尾，目标使用前序处理后的最终文本且不带版本号。
- 每条 replace 在 `AiPromptsTest` 同时断言新文案存在、旧文案不存在；中文词用中文引号。
- 启动升级机制保持 `refreshAssistantPromptIfNeeded` + `assistant_prompt_customized` 三态，不恢复历史全文比对。
- **手册 replace 的目标子串必须"从最终文案里抄"，不许凭印象写**：v0.56.8 第二批就写出过 2 条
  **静默落空**的 replace（目标那两句早被更早的链改掉了，手册里根本没有）。
  核对办法（不用跑测试、30 秒）：编译后用 jshell 反射把最终文案 dump 出来逐句查——
  `jshell --class-path "app/build/tmp/kotlin-classes/debug;<kotlin-stdlib-2.2.20.jar>"` 里执行
  `AiPrompts.class.getMethod("getDEFAULT_ASSISTANT_PROMPT").invoke(AiPrompts.INSTANCE)`，
  用 `Files.write(..., getBytes("UTF-8"))` 写到 `logs/manual-vX.txt` 再 grep。
  注意 dump 出来的是**最终**文案，中间态的目标串要在链里按替换顺序推。
- **改措辞时必须回头修"被影响的历史断言"**：v0.56.5/v0.56.6 就撞到过 4 条老断言
  （v0536 的「卡片右边就是开关」、v0536b 的「聊天背景只存不显」、v0538 的「右边直接选…」、
  v0542 的「通话行为里是语音通话铃声」）——断言不跟着改，套件会一直红，后来人就不看了。
- **出包前值得跑的验尸官**：`gradle :app:testDebugUnitTest --tests "com.menghuan.app.AiPromptsTest"`
  （2~3 分钟；它直接编译真实 replace 链，能抓出"静默落空"）。只改 `src/test/**` 不需要重新出包。

## 禁止复活
- 打字机 `streamingText`、表情包、API 列表 ✓、语音两个总开关、设定词模板按钮。
- 已删：`EdgeTtsClient.kt`、`ReadAloudPlayer.kt`、`realtime/Mp3Decoder.kt`。
