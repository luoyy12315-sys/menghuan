# 梦欢 · 项目长期记忆

> 2026-10-03 整理：v0.21–v0.38 的细节已并入「历史沉淀」区，只保留**仍有约束力的结论**。
> 逐版本原始记录见 `.workbuddy/memory/2026-*.md` 与 `CHANGELOG.md`。

## 构建环境（验证可用）
- JDK17 `C:/Users/16621/.workbuddy/toolchains/jdk17`；Android SDK `.../toolchains/android-sdk`；
  Gradle 8.14.3 `C:/Users/16621/.gradle/wrapper/dists/gradle-8.14.3-all/<hash>/gradle-8.14.3/bin/gradle.bat`
- 构建：`assembleDebug|testDebugUnitTest|recordRoborazziDebug --no-daemon --console=plain`；
  **不要用 gradlew**（无 wrapper）。日志重定向到 `logs/`（覆盖同名，禁止写项目根）。
- 勿删配置：`local.properties`、`gradle.properties` 的 `android.overridePathCheck=true`、`settings.gradle.kts` 阿里云镜像。
- **中文路径坑（单测）**：JDK17 经 @argfile 按 GBK 解码 UTF-8 classpath → 中文路径下 JVM 单测必 CNFE。
  先 `subst X: "C:\Users\16621\Desktop\梦欢"` 再在 `X:\` 下构建（重启失效需重跑）。
- **UI 回归门禁**：改 UI 必跑 `recordRoborazziDebug`（Robolectric+Roborazzi 启 MainActivity 出 PNG
  到 `app/build/screenshots/`），目视比对；用法见 AGENTS §八。

## 最高优先级约定
- **⭐ 新功能先去 GitHub 找优秀案例再动手（2026-10-04 拍板）**：GitHub 上同类实现大多免费公开，
  **可直接读、借鉴、复制适配，不用管许可证**。顺序 = 搜 star 高/活跃的实现（中英文关键词都搜，
  也搜 `android ... sample` / `awesome-android ...`）→ 读它的做法 → 照着落到本项目。
  **闭门造车是本项目返工的主要来源。**
- **⭐ 不会就直说，不许"随便搞"、不许试错式连着改（2026-10-04 拍板）**：没把握就先说明不确定性，
  或先去 GitHub 找现成做法。v0.43.1 连着三轮返工仍未解决，用户明确反感"先改一版试试"。
- **⭐ 键盘 / 语音冻结已于 v0.44.0 解除（2026-10-04 用户点名"换成熟方案"）**：
  该次已按 GitHub 成熟案例整体替换（键盘 = 官方 WindowInsetsAnimationCompat + navBar 基线，
  新文件 `ui/chat/KeyboardInsets.kt`；语音采集 = AudioRecord 直采 PCM+WAV 头）。
  **此后未经点名仍不得再动**；`SpeechToText` / `cloudTranscribe` / `OpenAiClient.transcribe`
  未动过。本地离线 ASR（vosk/sherpa-onnx，需下载 40-80MB 模型）用户未拍板，**不要擅自上**。
- **版本节奏（2026-10-01 拍板）**：用户说一次需求 = 一次版本更新。一批需求（哪怕十几条子项、
  横跨 UI/数据/AI）必须在**同一个 versionName** 内全部做完，**严禁按子功能拆成一串小版本**。
  中间不 bump version、不出包；全做完 + 测试截图过了才出一个包。
- **数据只存本机**，不上云。
- 人设（Persona）用户可增删改；长按头像 = 切换**本会话** AI 接口（v0.35.0）。
- 主题切换只在「设置 → 外观」；深色主题是 App 自带（跟随系统/浅色/深色），**Force Dark 必须关**。
- 业务逻辑层（各 ViewModel / Repository / Room / OpenAiClient / notify）历轮 UI 重建均保留。

## UI 设计系统（Figma 风，2026-09-28 拍板）
- 黑白骨架 + 鲜亮品牌色**只作内容点缀**。参考 `DESIGN.md`。**预览工作流已废弃：直接改 Compose。**
- 令牌：`NeuBackground`/`NeuSurface`=#FFF，`NeuTrack`=#F2F3F5 凹陷灰，`NeuHairline`=黑8%，
  `NeuAccent`=纯黑，`NeuDanger`=#F24E1E；`AccentInk` = 强调色一反相
  （**强调色上的文字/图标必须跟着翻**，否则深色下白底白字）。
  品牌色 Brand Green/Yellow/Purple/Blue/Salmon 只用于 Hero 卡/热力/persona 环/Spark，**绝不用于骨架**。
- 核心组件 `ui/common/Neu.kt`：`neuSurface`、`figmaSelection`、NeuButton（999dp 药丸）、
  NeuChip、NeuSwitch、NeuCircleButton。图标走 `ui/theme/Icons.kt` 的 **MIcons**（纯线条 21–23dp，禁用 Material 图标）。
- 动效：弹簧优先；底栏指示器弹簧滑移；EaseOutExpo 管进出场；进出场**不用 fadeOut**
  （边缩边淡会让底部输入栏最后几帧透明化 = 用户看到的"闪一下"）。
- 比例类尺寸一律基于 `LocalContentWidth`，不用 `LocalConfiguration.screenWidthDp`。

## 踩实的 Compose / Android 硬规则
- ⚠️ **颜色令牌只能在 composable 里读**（`@ReadOnlyComposable get()` 读 `LocalNeuPalette`）。
  `drawBehind`/`Canvas`/`Modifier.background(...)` 的 lambda 里**必须先取局部变量** —— 这是历次改造编译错的唯一来源。
- ⚠️ **自绘描边必须内缩 strokeWidth/2**：`drawRoundRect + Stroke(1dp)` 默认居中，外半圈伸出布局边界，
  分数像素上呈"黑边"。任何自绘描边都要内缩。
- ⚠️ **独立 Composable 内部不能直接用 `Modifier.weight`**（ColumnScope/RowScope 成员扩展）
  → 子组件收 `Modifier` 参数、父级在作用域里传 `weight(1f)`。
- ⚠️ **`@file:OptIn` 必须在文件最前**（注释之前），否则被忽略。
- ⚠️ **要做进出场动画的容器不要直接挂 `imePadding`**：键盘收缩时 padding 瞬间归零会把元素顶一下。
- ⚠️ **NavHost deep link 参数是粘的**：`entry.arguments` 每次重组都能读到，
  任何"按参数滚 tab"的 LaunchedEffect 从 push 层返回时会**再执行一次**。
  必须按 `entry.id` 记消费标记（rememberSaveable），只生效一次。
- ⚠️ **UI 状态别用局部 remember 驱动跨层流程**：局部状态没人置位 = 死分支（v0.35.0 生图占位踩过）。
  流程状态一律放 UiState。
- ⚠️ **小尺寸自绘图标的圆点会粘连**：定好尺寸后**必须截图放大看一眼**（键盘图标 4 列键帽糊成黑疙瘩）。

## 键盘 / 面板 inset（v0.44.0 已换官方方案，实现在 `ui/chat/KeyboardInsets.kt`）
- **现行实现 = `rememberBottomBarInset(followKeyboard)`**：navBar 走**基线**（只在 ime==0 时刷新）、
  面板/语音态不读 ime、ime 优先取 `WindowInsetsAnimationCompat` 逐帧插值（`ime==0` 强制归零自愈）。
  改键盘行为先读那个文件的类注释，别再把旧三补丁（单调收敛/静默期/方向性动画）加回来。
- **"取 max 修不了中间帧"**：两个系统量时序错位（ime 与 navBar 先后变）时，
  把其中一个改成"稳定基线"才是正解；时间窗/单调收敛类补丁只会互相咬合。
- **不要对系统动画做二次插值**：输入栏自己走缓动、键盘是**系统**在动，两条曲线不同必然"慢半拍"。
- **键盘避让不能由两层各管一半**；**面板高度不能算进输入栏 bottomInset**（面板是兄弟节点，自己占高）。
- **所有"形态切换"入口必须收敛到一个函数**，各写一份 hide/show 必漏窗口期。
- ⚠️⚠️ **"入口收敛到一个函数"还不够 —— 互斥必须再由键盘状态兜一层**（v0.44.1 真机 bug）：
  点表情收键盘**不清焦点** → 再点输入框 `onFocus` 不触发（焦点没变），但系统会对
  **已聚焦**的输入框自动弹键盘 → 面板态还挂着 → 输入栏被盖。
  正解 = `ImeDismissesPanels`：**ime==0 布防，布防后 ime>0 且有面板/语音态就收掉**
  （PanelSwitchHelper 思想），点击入口只当正常路径。无时间窗：收起动画降到 0 才布防，不会误收。
- ⚠️ **别拿"焦点变化"当键盘显隐的信号**：不清焦点 / 重复点击已聚焦控件都是常态。
- ⚠️ **给某个过渡打补丁时，必须反问"反方向会不会被误伤"**
  （本项目的静默期补丁误伤了"弹键盘"，正是栽在这里）。
- **没有 `BackHandler` 时返回键会被系统拿去收键盘并消费掉**，第二次才到 NavHost 直接退页。
  凡页面有"弹出层"，必须自己接 `BackHandler` 分层收起。
- ⚠️ 逐帧 inset 的读取必须封在独立小 composable 里，不能提到页面顶层
  （否则键盘动画期间整页每帧重组）。

## 语音采集（v0.44.0 已换 AudioRecord 直采，实现在 `data/VoiceRecorder.kt`）
- **现行实现 = `AudioRecord` 16kHz/MONO/PCM_16BIT 直采 + 手拼 44 字节 WAV 头**
  （vosk/sherpa-onnx/whisper.cpp 三家 Android 采集层的统一做法）。
  **不要再回去用 MediaRecorder**：format/encoder 组合表是 ROM 兼容性重灾区。
- 坑：`AudioRecord` 构造不抛异常 ≠ 拿到麦克风，必须查 `state==STATE_INITIALIZED`；
  采集必须独立线程（read 是阻塞的）；stop 后要 `join` 抽水线程再回填 WAV 头。

## 导航 / 数据就绪时序（v0.43.1）
- ⚠️⚠️ **"数据流初值是空列表"会闪出错误内容**：会话列表原来只有「空 / 非空」两态，
  冷启动时先渲染居中的「新建人设」，数据到了才换（用户实测"先显示新建人设，然后列表才出现"）。
  **凡"异步数据 + 空状态带引导内容"的页面，必须显式分「未加载 / 确实为空 / 有数据」三态**。
- ⚠️⚠️ **"是否已加载"必须从主数据流 `map` 派生，不能另发查询**：另发一条 Room 全表查询会与
  主 `combine` 争抢共享 query executor 堵死首值（Robolectric 100% 复现，单测 60s 超时），
  且 `runTest` 会把未完成子协程判为 `UncompletedCoroutinesError`。与
  "Application.onCreate 禁止并发扫全表"是同一条规律。
- ⚠️⚠️ **`currentBackStackEntryAsState()` 在 `popBackStack()` 那一刻就切回上一层**，
  而退场动画还要跑几百毫秒。底栏显隐若直接绑路由，这段里底栏已渲染、Scaffold `innerPadding`
  加大，正在滑出的页面被往上挤（用户反馈"底栏先冒出来、输入框往上跳"）。
  正解 = **底栏显隐也走 `AnimatedVisibility`**（进场延迟、退场立即），
  并与 push 路由共用同一时长常量 `PUSH_TRANSITION_MS`。

## 数据层约定
- **Room 迁移三处同步**：实体/表变更 → `version+1` + `MIGRATION_N_N` + `MenghuanApp.addMigrations`；
  schema JSON 导出到 `app/schemas`（迁移出错 diff 它）。
- **存量字段为空一律靠 from* 推断，不做批量回填**（`MediaKind.fromLegacy` 同思路）。
- **记账本三铁律**：① 无分组无分类；② **现金余额不入库**，余额 = 全部流水有符号累加
  （流水是唯一事实来源）；③ 金额存「分」Long。
- **AI 动作类标记的自我强化循环**：生成过的结果留在历史里会被模型模仿继续输出标记。
  动作结果回历史时**一律改写成过去完成时事实**（如「（此前已生成过一张图片：…）」）。
- **送聊天记录给 LLM 的功能必须带角色标注 + 背景声明**（AI 一方是虚拟角色扮演），
  否则角色人设会被当成用户画像收（v0.38.2 实测踩坑）。
- **上下文预算按字数**：`selectHistoryByCharBudget`，1 字 ≈ 1 token 保守折算，封顶 20 万
  （扣 system prompt + 回复余量）。**用户明确说按字数，不要用 token 估算器**。
- **定时生成类功能模板**（每日建议/周报同构）：独立表 + 独立 prompt（硬编码 schema + 可自定义策略）
  + 独立闹钟 + 独立 Receiver；同 day 覆盖、进程启动**只排闹钟不发 LLM 请求**、Receiver 到点自排明天。
- ⚠️ **Application.onCreate 禁止并发多个"扫全表"协程**：Room query executor 共享，
  启动期争抢线程池会堵死别的数据流（Robolectric 100% 复现）。一次性维护挪到"用到时懒执行"。
- ⚠️ **周报补生成禁止放 Application.onCreate**（会抢走测试 MockWebServer 响应）。
  入口 = MainActivity.onCreate / BootReceiver / WeeklyReportReceiver。

## 语音链路（v0.44.1：**端侧离线成主力**）
- 采集：`VoiceRecorder` = AudioRecord 直采 16kHz/单声道/16bit PCM + 手写 44 字节 WAV 头
  （vosk/sherpa/whisper.cpp 三家 Android 采集层的一致做法；不碰 MediaRecorder 的参数组合表）。
- 转写三级级联（用户拍板"端侧离线 + 模型打进 APK"）：
  ① **端侧 `SherpaAsr`（sherpa-onnx 1.13.8 AAR，主力，全离线）** → ② 云端 Whisper →
  ③ 系统识别器 → 都失败返回 null（语音照发、不阻断）。
- ⚠️⚠️⚠️ **关键事实：`EXTRA_AUDIO_SOURCE`（API 31+ 文件输入）在国产 ROM 上普遍不生效** ——
  识别服务只监听实时麦克风，喂文件路径等于没喂。**换录音格式救不了，因为对方不读文件。**
  云端 Whisper 同理依赖用户那个接口额外支持 `/audio/transcriptions`（实测不支持）→
  这两条都不能当主力，才上的端侧。
- **端侧三件套配置缺一即崩**：`androidResources.noCompress += onnx/txt`（NDK AAssetManager
  读不了压缩 asset）、`ndk.abiFilters = armeabi-v7a + arm64-v8a`（AAR 4 套 ABI 共 127MB so）、
  `-keep class com.k2fsa.sherpa.onnx.** { *; }`（JNI 按名字访问数据类字段）。
  模型 = `assets/asr/` zh-14M 全套 int8 四件套 25MB。APK 因此 3.4MB → 83MB。
- 喂数据按 0.1s 分片**边喂边解码** + 末尾补 0.3s 静音再 `inputFinished()`（流式 zipformer
  不补会丢最后几个字）。全程 `runCatching` —— 识别失败绝不炸发送链。
- 其他踩实点：麦克风独占，识别与录音必须串行（先录完再识别）；
  `start()` 失败必须 `Log.e` + 原因回传 UI，不能静默吞。
- ⚠️ 端侧识别**准确率与首次加载耗时只能真机验**（JVM 单测没 native 库，跑不到真实识别）。

## 相机 / 拍摄（v0.43.1）
- 深色取景框里**白图标叠半透明白底 = 隐形**（浅色取景内容下）。改**白实心圆底 + 黑图标**。
- 底栏：左「相册」（系统 `PickVisualMedia`）/ 中「快门」/ 右「翻转」。
- **拍完必须进确认态（重拍/发送）再发**，避免误拍一张废图就发给 AI。
- **修竖拍变横要双重**：① `ImageCapture.targetRotation` 跟随预览方向；
  ② 按 EXIF **物理旋转像素后回写原文件** —— 回写才能让下游（vision 编码/气泡显示/不读 EXIF 的路径）
  全部拿到正向图。

## 测试约定
- ⚠️ **要碰 Android 框架的测试必须 `@RunWith(RobolectricTestRunner::class)` +
  `@Config(sdk = [34])` + `ApplicationProvider.getApplicationContext()`**；
  `RuntimeEnvironment.getApplication()` 在**没有 runner 的"纯 JVM"类**里返回 null → NPE
  （`SpeechToTextTest` 首版 7 条全 NPE 就是踩这个）。
- ⚠️ **`android.util.Log` 在纯 JVM 单测里未 mock**，调用抛
  `Method w in android.util.Log not mocked` → 生产代码里的日志用 `runCatching { Log.x(...) }` 包住。
- ⚠️ **scrollable 容器里首屏之外的节点直接 `performClick()` 静默无效**，且来源页同名文案会
  "骗过"断言。必须 `performScrollTo().performClick()`，并补一条**只存在于目标页**的文本断言。
- ⚠️ **Robolectric 绝对行数断言是雷**（Room 库文件型且跨测试类共享）→ 改「基线快照 + 增量」。
- ⚠️ **同容器多组 Lazy 数据的 key 必须加前缀**（`"insight-$id"` / `"report-$id"`）。
- ⚠️ **同名文案的入口行要 testTag**（否则 `onNodeWithText` found 2 nodes）。
- 底栏点击一律用 testTag `bottom_tab_N`（4 页常驻，屏外页语义树可见）。
- ⚠️ **拍摄页无法用 JVM 截图测试守**：Robolectric 下 CameraX 初始化会挂（实测超时）。
  拍摄相关改动**必须真机验收**。

## 结构速查
- **四 tab**：home = HorizontalPager（消息/数据/任务/我的），beyondViewportPageCount=3；
  底栏 = MainActivity 的 HomeBottomBar。
- **平板判源唯一**：`calculateWindowSizeClass` → `LocalWindowWidthClass`（!= Compact 即 ≥600dp）；
  消息页双栏 `AdaptiveSplitScreen`（左 360dp 列表 + 右内嵌 ChatScreen showBack=false），
  数据/任务/我的版心 840dp 两列网格。
- **deep link**：通知/小组件 `daoshi://open/{page}`（tasks/data/me）；`daoshi://personas` 单页 push。
- **数据页顺序（用户指定，勿动）**：本周回顾条 → 今日建议 → 记账本 → AI 洞察/周报合并入口。
- **本周回顾口径**：今天该做的事 = 每日任务 + 当天到期的基础任务；
  每日任务判断带 `createdAt < dayEnd`。
- **AI 周报不折叠**（用户明确删过「展开全文」）；**长设置面板 = 手风琴**（默认全收起、单开、
  恢复默认只在 customized 时出现）。
- **错误条必须分级**（`ErrorBar.KIND_*`）：配置/权限类**绝不给"重试"**
  （重试会让模型重复发 `[操作手机:…]`）。
- **对话内手机操控**：`[操作手机:目标]`，用户在对话里发起时**不要确认卡**（autoConfirm=true）；
  围栏 = 白名单（默认不限制）+ 悬浮停止 + 15 步上限。别再往回加确认环节。
- **列表滚动策略**：首次加载 → `scrollToItem` 无动画直跳（只做一次）；
  后续新消息只在 nearBottom 时 `animateScrollToItem` 跟随。

## 已废弃 / 别再往回加
- 打字机 `streamingText`（v0.41.4 推倒，改"后台生成、一次性发送"）。
- 表情包功能（v0.35.1 用户取消）；情绪/手势 emoji 分区标题与面板中间灰线（v0.43.1 删）。
- 语音键 / 表情键的**外圈**（v0.43.1 删，只要符号本身、颜色淡）。
- 语音识别文字在列表直接展示（v0.43.0 用户要求不展示；v0.43.1 改为长按手动展开）。
