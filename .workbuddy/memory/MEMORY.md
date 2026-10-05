# 梦欢 · 项目长期记忆

> 只保留**仍有约束力的结论**。逐版本细节见 `.workbuddy/memory/2026-*.md` 与 `docs/CHANGELOG.md`。
> 方案类文档在 `docs/PLAN-*.md` / `docs/RESEARCH-*.md`。

## 构建环境
- JDK17 `C:/Users/16621/.workbuddy/toolchains/jdk17`；SDK `.../toolchains/android-sdk`；
  Gradle 8.14.3 `C:/Users/16621/.gradle/wrapper/dists/gradle-8.14.3-all/<hash>/gradle-8.14.3/bin/gradle.bat`
- **不要用 gradlew**（无 wrapper）。日志重定向到 `logs/`。
- ⚠️ **`JAVA_HOME` 必须 `export`**（只赋值不export → gradle 报 "JAVA_HOME is not set"），
  且 `PATH` 里要有 `$JAVA_HOME/bin`。
- ⭐ **改完 Compose 别信静态自查**：括号平衡、导入"看起来齐"都查不出未解析引用 / 缺初值 /
  漏常量。**唯一可靠的是真跑 `:app:compileDebugKotlin`**（比 assemble 快得多），过了再谈出包。
  （v0.54.0 出包时静态自查全过，真编译一次报出 6 个错：`scrollBy` 缺 import、漏 import `Box`、
  `collectAsState()` 缺 initial、删路由时误删还在用的 `SettingsRoutes.PERMISSIONS`。）
- ⚠️ **删路由/常量前先确认它是不是真的没了**：v0.54.0 把「权限」入口挪组时，
  误以为权限页也被删了 → 把 `PERMISSIONS` 常量删掉 → 编译报未解析引用。
  **挪位置 ≠ 删页面。**
- ⚠️ `scrollBy` 是 `ScrollableState` 的扩展函数，用它必须
  `import androidx.compose.foundation.gestures.scrollBy`（别处能用是因为那边 import 过）。
- **中文路径坑**：JDK17 经 @argfile 按 GBK 解码 UTF-8 classpath → 中文路径下 JVM 单测必 CNFE。
  先 `subst X: "C:\Users\16621\Desktop\梦欢"` 再在 `X:\` 构建（重启失效需重跑）。
- **出包五步**：bump version → `subst` 后跑 `:app:assembleRelease`（release 用 debug 签名，
  可覆盖安装）→ 产物拷到项目根命名 `梦欢-v<版本>-release.apk` → 上一版移进 `archive/`
  → 补 CHANGELOG + 助手手册 + 测试断言。
- 勿删：`local.properties`、`gradle.properties` 的 `android.overridePathCheck=true`、
  `settings.gradle.kts` 阿里云镜像。

## 最高优先级约定
- ⭐ **新功能先去 GitHub 找成熟案例**再动手（可直接读/借鉴/复制，不用管许可证）。
  中英文关键词都搜，也搜 `awesome-android ...`。**闭门造车是本项目返工的主要来源。**
- ⭐ **不会就说不会，不许"随便搞"、不许试错式连着改**（v0.43.1 连着三轮返工，用户明确反感）。
  没把握先说明不确定性，或按上一条先找现成做法。
- ⭐ **动手前先问清方向**：需求有歧义或有多条实现路线时，列清"怎么做/什么代价/适合什么情况"
  让用户选，不许自作主张。**讲方案先画图，别先抛控件类型名** —— 用户脑子里的画面是**结果**，
  我脑子里的关键词是**实现**（"手风琴""折叠"这类词用户会直接否掉）。
- ⭐ **测试 / 编译 / 打包由用户决定**：改完代码就停手，只交付源码。
- ⭐ **一批需求 = 一个版本**，严禁按子功能拆版本。中间不 bump、不出包。
- ⭐ **应用助手手册（`AiPrompts.DEFAULT_ASSISTANT_PROMPT`）必须与每版同步**。
  它是这App 的使用手册，滞后 = 用户拿到错答案。改法见下节。
- 键盘 / 语音栈未经点名不得再动（见对应节）。
- 数据只存本机。人设可增删改；长按头像 = 切换**本会话**接口。
- 主题切换只在外观组；深色是 App 自带（跟随/浅色/深色），**Force Dark 必须关**。

## ⚠️ 助手手册 replace 链（最容易"看起来改了其实没改"）
- 机制 v0.51.1：**不用登记任何"历史版本全文"**。只要用户没亲手改过设定词，
  启动即刷最新（`refreshAssistantPromptIfNeeded` + `assistant_prompt_customized` 三态标记）。
- 新 replace **一律追加在链尾**；放在前面会因"目标文本还没生成"而**静默落空**。
- **目标串不带版本号**（被批量 sed 后立即失配）。
- 四个高频坑：① 目标串必须是**最终形态**（前序 replace 已改过就不对了）；
  ② **短目标串只命中多处中的一处**；③ **被包含的同款 replace 是死代码**（永远落空且看不出来）；
  ④ **每加一条必须在 `AiPromptsTest` 补双向断言**：新措辞 `contains` + 旧措辞 `assertFalse`。
- ⭐ **没跑构建时验证 replace 链 = 离线模拟器**：从 `AiPrompts.kt` 解析底稿 + 全部 replace，
  按 Kotlin 语义在 Python 重放。必须复刻：`trimIndent()` 去**首尾空白行**再按最小缩进左移
  （`len('.trimIndent()') == 13` 不是 14）、**只作用于紧邻字面量**、还原 `\` 转义、
  **去注释必须字符串感知**（否则 `https://…` 里的 `//` 被当注释，后半条链全歪）。
- ⚠️ Kotlin 中文文案**不要用半角引号包中文词**（提前结束字符串 → 编译报 `Expecting an element`），用「」。

## UI 设计系统（Figma 风）
- 黑白骨架 + 鲜亮品牌色**只作内容点缀**。令牌：`NeuBackground`/`NeuSurface`=#FFF、
  `NeuTrack`=#F2F3F5、`NeuHairline`=黑8%、`NeuAccent`=黑、`NeuDanger`=#F24E1E；
  `AccentInk` = 强调色一反相（强调色上的文字/图标必须跟着翻）。
  品牌色只用于 Hero 卡/热力/persona 环/Spark，**绝不用于骨架**。
- 核心组件 `ui/common/Neu.kt`：`neuSurface`、`figmaSelection`、`NeuButton`（999dp 药丸）、
  `NeuChip`、`NeuSwitch`、`NeuCircleButton`、`NeuSheetRow`、`NeuTextField`。图标走 `MIcons`（禁 Material）。
- 动效：弹簧优先；进出场**不用 fadeOut**（边缩边淡会让底部输入栏最后几帧透明 = "闪一下"）。
- 比例类尺寸基于 `LocalContentWidth`，不用 `LocalConfiguration.screenWidthDp`。
- ⚠️ **`neuSurface(intensity = 0f)` 是"整条全透明"**（color 按 intensity 乘 alpha），
  不是"透明底"。要凹陷灰槽用 `inset = true`。

## Compose / Android 硬规则（历次编译错都出在这几条）
- ⚠️ **颜色令牌只能在 composable 里读**；`drawBehind`/`Canvas`/`background(...)` 的 lambda 里
  **必须先取局部变量**。
- ⚠️ **自绘描边必须内缩 strokeWidth/2**（默认居中，外半圈伸出布局 = 分数像素上的"黑边"）。
- ⚠️ **独立 Composable 内不能用 `Modifier.weight`** → 子组件收 `Modifier` 参数、父级传 `weight(1f)`。
- ⚠️ `@file:OptIn` 必须在文件最前（注释之前）。
- ⚠️ 要做进出场动画的容器**不要直接挂 `imePadding`**（键盘收缩时 padding 瞬间归零会顶一下）。
- ⚠️ **UI 状态别用局部 remember 驱动跨层流程**（没人置位 = 死分支）；流程状态一律放 UiState。
- ⚠️ **NavHost deep link 参数是粘的**：按参数滚 tab 的 `LaunchedEffect` 从 push 层返回会**再跑一次**
  → 必须按 `entry.id` 记 `rememberSaveable` 消费标记。
- ⚠️ **小尺寸自绘图标的圆点会粘连** → 定好尺寸后**必须截图放大看一眼**。
- ⚠️ 过滤行用 `if (!show) return`，不要 alpha=0（透明节点会被无障碍与 UI 测试当成真实存在的行）。
- 列表滚动：首次 `scrollToItem` 无动画直跳；后续新消息**只在 nearBottom 时**跟随。
  LazyColumn 按"首项 + 偏移"锚定，**流式增量（末项变长）不会自动跟到底**，要按末项高度差 `scrollBy` 补滚。

## 键盘 / 面板 inset（`ui/chat/KeyboardInsets.kt`）
- 现行实现 = `rememberBottomBarInset(followKeyboard)`：navBar 走**基线**、面板/语音态不读 ime、
  ime 优先取 `WindowInsetsAnimationCompat` 逐帧插值。**改键盘行为先读那个文件的类注释**，
  别把旧三补丁加回来。
- 四条规律：① "取 max"修不了中间帧（时序错位时要把其中一个改成稳定基线）；
  ② 不要对系统动画做二次插值；③ 键盘避让不能两层各管一半、面板高度不能算进输入栏bottomInset；
  ④ 打补丁前先问"反方向会不会被误伤"。
- 形态切换入口收敛到一个函数，**互斥还要由键盘状态兜一层**（`ImeDismissesPanels`）。
  ⚠️ **别拿"焦点变化"当键盘显隐信号**。
- 没有 `BackHandler` 时返回键会被系统拿去收键盘 → 有弹出层的页面必须自己分层收起。
- 逐帧 inset 的读取必须封在独立小 composable 里。

## 语音栈（v0.50.0 整体重做，改前先读 `docs/PLAN-v0.50.0-*` + `docs/RESEARCH-v0.50.0-*`）
- **识别 = 一条链**：`data/SherpaAsr.kt` 是唯一一级（sherpa-onnx SenseVoice 离线非流式）。
  云端 Whisper 与系统识别器**实测永远走不通、已删**（含 `OpenAiClient.transcribe`），不要复活。
- **语音消息与通话共用同一个识别器实例**（228MB 权重建两个 session ≈ 500MB，中低端机 OOM）。
- **识别模型不随包**：`data/AsrModelManager.kt`（Range 断点 + 逐文件 sha256）+ `ui/settings/AsrModelScreen.kt`；
  落 `filesDir/asr_sensevoice/`，源顺序 ModelScope → hf-mirror。就绪 = `.verified` 标记 + 两文件字节数全等
  （**不是每次重算 228MB 哈希**）。`assets/asr/` 只剩 `silero_vad.onnx`。
- ⚠️ **`EXTRA_AUDIO_SOURCE`（API 31+ 文件输入）在国产ROM 上普遍不生效** —— 识别服务只监听实时麦克风。
  **换录音格式救不了。**
- **合成**：`data/TtsClient.kt` 三家可切（硅基 CosyVoice2 / 小米 MiMo / 自定义 OpenAI 兼容），
  **逐家一个实现**（MiMo 走 `/chat/completions` 且目标文本放 `assistant`；硅基预置音色拼
  `<model>:<alias>`；自定义端点只发四个最小字段）。`TtsTarget.CALL` = WAV + `sample_rate=24000`，
  `TtsTarget.NOTE` = MP3；24000 的唯一来源是 `TtsConfig.TTS_CALL_SAMPLE_RATE`
  （两边各写一个数就会变速变调）。
- **每家一套独立配置**：DataStore 键按 provider 分桶，`tts_provider` 只记当前生效家。
  写桶时**保存别家要先把老键实体化**（直接清会抹掉硅基填好的 Key）。
- **音色**：预置每家一男一女（名单与标签都在 `TtsConfig.TtsVoices.library()`，
  设置页与人设页**共用同一函数**）；三级回落集中在 `SettingsRepository.voiceForConversation()`。
  `data/VoiceCloner.kt` 克隆样本走 `realtime/MediaAudioDecoder.kt`（mp3/wav/m4a/**mp4** 都收）。
  ⚠️ 两条不许回退：重采样必须走 `WavPcm.resample`（降采样前低通，裸隔点取样会混叠）；
  样本必须 `trimToSpeech`（上限 10 秒，CosyVoice2 是零样本克隆）。
  ⚠️ 服务端 `customName` 只收字母/数字/`_`/`-`，中文名发出去 100% 400 → 用 `asciiVoiceName`。
- `data/WavPcm.kt` **位深不挑**（8/16/24/32 + float，EXTENSIBLE 从 GUID 取编码）——**别再收窄**。
- 采集 = `data/VoiceRecorder.kt`（AudioRecord 16kHz/MONO/PCM_16BIT + 手拼 WAV 头）。
  **不要用 MediaRecorder**。坑：构造不抛异常 ≠ 拿到麦克风（查 `state==STATE_INITIALIZED`）。
- 构建三处硬约束：`noCompress` **只剩 `onnx`**、`ndk.abiFilters` **只剩 `arm64-v8a`**、
  `proguard-rules.pro` 的 `-keep class com.k2fsa.sherpa.onnx.**` **不动**。任一被"顺手改回"语音就崩。
- ⚠️ 端侧识别准确率与首次加载耗时**只能真机验**（JVM 单测没 native 库）。

## 导航 / 数据就绪时序
- ⚠️ **"初值是空列表"会闪出错误内容** → 凡"异步数据 + 空状态带引导内容"的页面，
  必须显式分「未加载 / 确实为空 / 有数据」**三态**。
- ⚠️ **"是否已加载"必须从主数据流 `map` 派生，不能另发查询**（另发 Room 全表查询会与主 `combine`
  争抢共享 executor 堵死首值；`runTest` 还会判 `UncompletedCoroutinesError`）。
  与"Application.onCreate 禁止并发扫全表"是同一条规律。
- ⚠️ **`currentBackStackEntryAsState()` 在 `popBackStack()` 那一刻就切回上一层**，而退场动画还要跑几百毫秒。
  → 底栏显隐也走 `AnimatedVisibility`（进场延迟、退场立即），与 push 共用 `PUSH_TRANSITION_MS`。
- **周报补生成禁止放 `Application.onCreate`**（会抢走测试 MockWebServer 响应）。

## 数据层约定
- **Room 迁移三处同步**：实体/表变更 → `version+1` + `MIGRATION_N_N` + `MenghuanApp.addMigrations`；
  schema JSON 导出到 `app/schemas`。
- **存量字段为空靠 from* 推断，不做批量回填**。虚拟迁移的坑：老配置**必须先实体化**再动新集合，
  否则新键一变非空、虚拟读出就失效、老配置凭空消失。
- **记账本三铁律**：① 无分组无分类；② **现金余额不入库**，余额 = 全部流水有符号累加；
  ③ 金额存「分」Long。
- **AI 动作类标记的自我强化循环**：动作结果回历史时**一律改写成过去完成时事实**
  （如"（此前已生成过一张图片：…）"），否则模型会模仿继续输出标记。
- 送聊天记录给 LLM 的功能**必须带角色标注 + 背景声明**（AI 一方是虚拟角色扮演），
  否则人设会被当成用户画像收。
- 上下文预算**按字数**（`selectHistoryByCharBudget`，1 字 ≈ 1 token，封顶 20 万）。
  用户明确说按字数，**不要用 token 估算器**。
- 定时生成类功能模板：独立表 + 独立 prompt（硬编码 schema + 可自定义策略）+ 独立闹钟 + 独立 Receiver；
  同 day 覆盖、进程启动**只排闹钟不发请求**、Receiver 到点自排明天。
- ⚠️ **AI 动作标记、联网搜索**是全项目最容易造假信息的地方 → 搜不到必须在 prompt 里
  要求模型"如实说查不到、不许编"。

## 结构速查（v0.54.0）
- **四 tab**：home = HorizontalPager（消息/数据/任务/我的），beyondViewportPageCount=3；底栏在 MainActivity。
- 平板判源唯一：`calculateWindowSizeClass` → `LocalWindowWidthClass`（!= Compact 即 ≥600dp）。
- **设置链（v0.54.0 重构为「搜索框 + 4 个分组标签」，标签不可点不折叠，卡片点进去仍是全屏页）**：
  「我的」页 = 人设管理 / 基础档案 / AI 图库 / 设置四张卡（**本页 = 我是谁**）；
  设置页 = ① AI 与模型（对话模型 / AI 提示词）② 语音与通话（语音合成 / 音色 / 识别模型 ‖
  AI 主动打电话 / 语音通话铃声 / 定时主动消息）③ 提醒与通知（三开关**就地显示**）
  ④ 数据与系统（外观三胶囊**就地** / 权限 / 数据与隐私 / 关于）。
  **v0.54.0 删掉三个页面**（语音 / 外观 / 通知与提醒），最深 4 层 → 2 层；
  「接听延迟」并进 `CallRingtoneScreen`。共用组件在 `ui/settings/SettingsPage.kt`
  （`SettingsGroupLabel` / `SettingsRow` / `SettingsSwitchRow` / `SettingsSearchField` 等）。
- deep link：`menghuan://open/{page}`（chatList/tasks/data/me）、`menghuan://chat/{id}`、`menghuan://personas`。
  ⚠️ 域名是 **menghuan**（v0.36.0 起从 daoshi 改名），别写回。
- 数据页顺序（用户指定，勿动）：本周回顾条 → 今日建议 → 记账本 → AI 洞察/周报合并入口。
- **对话页三点菜单（九项）**：编辑人设 / 聊天记录 / 主动打电话 / 角色立绘 / Live 2D / 聊天背景 /
  联网 / 音色 / 模型。立绘·Live2D·背景**只存不显**。会话联网按会话存 `chat_web_search`。
- **AI 主动打电话**：总开关 `ai_call_enabled`（默认关）+ 每人设一条 `AiCallRule`。
  "独立"三个落点：规则按 `personaId` 存、闹钟 `requestCode = 2_000_100 + personaId % 10000`、
  判定取**该人设**会话最后一条消息时间。22:30–08:00 静默、每天最多一通。
  ⚠️ **用户点名要的那一通（`REQUESTED`）不受任何预设限制**（总开关/规则/静默/次数全不管）。
  ⚠️ 靠通知弹出 → 没 `POST_NOTIFICATIONS` 就一声不响，设置页有直达申请。
- **对话内手机操控**：`[操作手机:目标]`，用户在对话里发起时**不要确认卡**；
  围栏 = 白名单 + 悬浮停止 + 15 步上限。别再往回加确认环节。
- **AI 语音条**：`data/VoiceNoteMarker.kt` 解析 `【语音条】`，合成成功后**原地升级**刚落库那条
  （不另插一条）。播完一定复位、不能只靠 `onCompletion`（MP3 padding 时可能不来）。
- 相机：深色取景框里**白图标叠半透明白底 = 隐形** → 改白实心圆底 + 黑图标。
  修竖拍变横要**双重**：`targetRotation` + 按 EXIF 物理旋转像素**回写原文件**。
  拍完必须进确认态（重拍/发送）再发。

## 测试约定
- 下列**仅在用户要求跑测试时**适用，平时改完代码就停手。
- 要碰 Android 框架的测试必须 `@RunWith(RobolectricTestRunner::class)` + `@Config(sdk = [34])`
  + `ApplicationProvider.getApplicationContext()`（`RuntimeEnvironment.getApplication()`
  在无 runner 的类里返回 null → NPE）。
- ⚠️ **`android.util.Log` 在纯 JVM 单测里未 mock** → 生产代码日志用 `runCatching { Log.x(...) }` 包住。
- ⚠️ scrollable 容器里首屏之外的节点 `performClick()` **静默无效**，且来源页同名文案会骗过断言 →
  必须 `performScrollTo().performClick()` + 补一条**只存在于目标页**的文本断言。
- ⚠️ **Robolectric 绝对行数断言是雷**（Room 库文件型且跨测试类共享）→ 改「基线快照 + 增量」。
- ⚠️ 同容器多组 Lazy 数据 key 必须加前缀；同名文案的入口行要 `testTag`；
  底栏点击用 `bottom_tab_N`。
- ⚠️ **拍摄页无法用 JVM 截图测试守**（Robolectric 下 CameraX 初始化会挂）→ **必须真机验收**。

## 已废弃 / 别再往回加
- 打字机 `streamingText`（v0.41.4 推倒）；表情包功能（v0.35.1 用户取消）；
  语音识别文字在列表直接展示（v0.43.0 起改长按手动展开）。
- **API 配置列表的 ✓ 勾**（v0.53.4 去掉：选中态已有虚线框 + 副标题「默认 · 」）。
  ⚠️ 但**"点行 = 切换生效"必须保留**、点右箭头 = 进详情 —— 去的是**视觉元素**不是**行为**。
- 把音色列表改成 `NeuSheetRow` 卡片（v0.53.4 用户否掉）。
  「AI 语音条 / 语音通话」两个总开关**不要加回来**（默认开、用户不会去关）。
- **编辑人设是独立路由页**（`persona_editor/{personaId}`，0 = 新建），不是弹层/Dialog。
  设定词**不要「插入模板」**、label 不带英文；**音色已从这里移走**（改到对话页三点菜单）。
- 已删除文件别复活：`EdgeTtsClient.kt` / `ReadAloudPlayer.kt` / `realtime/Mp3Decoder.kt`
  —— 后者把**输入** MediaFormat 硬写成 24000Hz 单声道，真实流不符就吐 0 帧。
