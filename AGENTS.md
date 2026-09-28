# AGENTS.md — 导师 · 项目协作指导

> 本文件面向在此项目工作的 AI 助手与人类开发者。动手前必读，读完再改代码。

## 一、项目是什么

**「导师」**：Android 平台的个人全能 AI 导师应用（vibe coding 开发中）。
用户与 AI 人设（persona）单聊，获得生活/学习/工作指导；App 收集用户数据形成洞察，
并通过任务清单 + 定时提醒督促执行。用户**自行配置 LLM API**，数据**只存本机**。

- 定位：单机版 AI mentor，不做账号体系、不做云端
- 交互核心：聊天为主界面，人设 = 聊天对象（类似联系人）
- 当前阶段：**核心闭环可用（真实 LLM / 任务提醒 / 档案注入），二期做 AI 提炼**

## 二、技术栈

| 层 | 选型 |
|---|---|
| 语言 / UI | Kotlin + Jetpack Compose（Material 3） |
| 架构 | MVVM + Repository，手动依赖容器（`MentorApp`），无 Hilt |
| 本地库 | Room（personas / messages / tasks / profile_facts）+ DataStore（规划） |
| 网络 | OkHttp + SSE（OpenAI 兼容协议，`llm/OpenAiClient.kt`） |
| 构建 | Gradle Kotlin DSL，AGP 8.7.3，Kotlin 2.0.21，minSdk 26，targetSdk 35 |

## 三、目录结构

```
导师·/
├── AGENTS.md                    ← 本文件
├── preview/all.html             ← 高保真交互预览（设计基准，见 §六）
├── app/src/main/java/com/daoshi/app/
│   ├── MainActivity.kt          # NavHost：chatList → chat/{personaId}，personas，data，tasks
│   ├── MentorApp.kt             # Application + 服务容器（db / persona / profile / settings 仓库）
│   ├── data/
│   │   ├── model/Models.kt      # Persona(含 tone/pinned) / ToneStyle / ChatMessage / TaskItem / ProfileFact / AiInsight
│   │   ├── db/AppDatabase.kt    # Room v5 + 正式 Migration（5 DAO，messages 有联合索引；schema 导出到 app/schemas，禁破坏性重建）
│   │   ├── ExtractionEngine.kt  # AI 提炼流水线（水位线增量 → LLM → facts/insights 落库）
│   │   ├── ProfileExtractor.kt  # 提炼 prompt + 宽容 JSON 解析（纯函数，JVM 单测）
│   │   ├── PersonaRepository.kt # 人设仓库（3 个种子均 isBuiltIn；updateTone/setPinned）
│   │   ├── ProfileRepository.kt # 用户档案仓库（ProfileFact 种子 + 增删改）
│   │   └── SettingsRepository.kt# DataStore（API Key 经 ApiKeyCipher/AndroidKeyStore 加密；DataStore 可注入供测试）
│   ├── llm/OpenAiClient.kt      # OpenAI 兼容 SSE 流式客户端
│   ├── notify/                  # AlarmScheduler + TaskReminderReceiver（含稍后提醒）+ TaskSnoozeReceiver + BootReceiver
│   └── ui/
│       ├── theme/Theme.kt       # 新拟态设计令牌：NeuBackground/NeuSurface/NeuAccent/AccentBrush…
│       ├── theme/Icons.kt       # MIcons 纯线条图标（path 照搬 preview/all.html 的 SVG）
│       ├── common/Neu.kt        # 新拟态核心：neuSurface 双向渐变阴影 / NeuButton / NeuTextField / NeuCheckCircle / NeuSheet*
│       ├── chat/                # ChatListScreen + ChatScreen（MarkdownText 渲染/按天分组/快捷打卡/失败重试）+ ChatViewModel + ChatDrafts
│       ├── persona/             # PersonaManageScreen + PersonaEditorSheet（新增/编辑/删除人设）
│       ├── data/DataScreen.kt   # 我的数据页（真实完成度圆环/打卡热力/档案 AI 徽标/洞察 + 编辑弹层）
│       ├── tasks/TasksScreen.kt # 任务页（分组卡片/勾选打卡/长按删除 + TasksViewModel）
│       ├── common/MarkdownText.kt # AI 气泡轻量 Markdown（粗体/行内码/列表/标题/代码栅栏）
│       └── settings/SettingsSheet.kt  # 共享设置面板 + ApiConfigSheet + MoreSheets.kt（通知/隐私/关于）
```

## 四、设计系统（Neumorphism 新拟态，不可擅自更改）

定调：**柔和灰蓝纸面 + 双向软阴影 + 单一靛蓝点睛**（2026-09-27 用户拍板，取代此前 ins 风）。浅色锁定，不做深色。

| 令牌 | 值 | 用途 |
|---|---|---|
| `NeuBackground` | `#E4E9F3` | 页面基底 / 凹陷面填充 |
| `NeuSurface` | `#EDF1F9` | 凸起面（卡片 / 按钮 / 底栏） |
| `NeuShadowDark` / `NeuShadowLight` | `#BFC9DC` / `#FFFFFF` | 右下投影 / 左上反光 |
| `TextPrimary` | `#3D4557` | 主文字 |
| `NeuAccent` / `NeuAccentDeep` | `#6C7DF0` / `#5766DA` | 唯一强调色：发送键 / 勾选 / 未读点 / 进度环 |
| 气泡 | AI = 凹陷纸面；用户 = AccentBrush 竖渐变 | 聊天气泡（无尾巴、圆角 22dp、maxWidth = 版心 76%） |
| 图标 | 21–23px **纯线条符号**（stroke 1.6–1.8），**无底色圆** | `ui/theme/Icons.kt` MIcons，与预览 SVG 1:1 |
| 字体 | 系统字体栈（刻意选择） | 全局 |

**新拟态核心实现（`ui/common/Neu.kt`，勿绕过）：**
- `Modifier.neuSurface(cornerRadius, color, elevation, intensity, inset)`：drawBehind 里先画两张膨胀圆角矩形的**方向线性渐变**（暗色贴右下、白色贴左上），再画面体填充；`inset=true` 时改画内缘双渐变描边（裁进面体）。**禁止 BlurMaskFilter（硬件加速下不生效）与 Modifier.blur（API 31+）**——minSdk 26 真机流畅全靠这个方案。
- 按压反馈统一 `rememberNeuPressState()`：阴影收矮变淡 + scale 0.97（110ms）。
- 动效令牌：`EaseOutExpo`（cubic-bezier(.16,1,.3,1)）；按压 110ms / 状态 220ms / 页面 340ms / 进场 460ms / 列表级联 45ms。

**交互语言（两条隐藏/可见入口规则，勿破坏）：**
- 顶栏可见图标按钮 = 常规功能（＋ 新增人设｜滑杆 设置）
- **长按头像（500ms）= 隐藏入口**：单聊页长按顶部头像 → 对话风格面板（温和陪伴/严格教练/苏格拉底反问）
- 设置入口**只在会话列表页**右上角，数据页/任务页不放

**布局自适应（真机关键，勿破坏）：**
- 系统栏 insets **只由 `ui/common/AdaptiveScreen.kt` 统一处理**（它负责 `statusBarsPadding` + `navigationBarsPadding`）。
  页面内部**不要**再写 `statusBarsPadding()`；`Scaffold` 用 `contentWindowInsets = WindowInsets(0,0,0,0)`。
  这是"真机比设计稿挤/矮"的根因，已修一次，别再改回去。
- **底栏只在消息/数据/任务三个顶级 tab 显示**：单聊页/人设管理页是 push 层级，
  隐藏底栏让输入栏直达屏幕底部（否则输入栏与底栏叠在一起，v0.3.0 修的错乱）。
  隐藏底栏页面的手势条避让由 AdaptiveScreen 的 navigationBarsPadding 补足。
- 底部导航**不要**用 Material `NavigationBar`（默认 80dp 高 + 胶囊指示器，与设计稿差一截），
  用 `MainActivity` 里的 `NeuBottomBar`（浮起胶囊 + 凹陷选中圆井 + 22dp 图标 + 9.5sp 标签）。
- 版心规则：手机（< 600dp）铺满；平板/折叠屏（>= 600dp）内容居中收窄到 **600dp**，两侧浅灰边槽。
- 气泡等比例元素用**屏幕比例**（如气泡最大宽 = 版心 × 76%），不要写死 dp。
- `preview/all.html` 在窄视口会自动缩放手机框，方便在任意窗口对比。

**系统栏与深色模式（真机关键，勿破坏）：**
- 浅色锁定 ≠ 高枕无忧：**必须退出系统 Force Dark**，`res/values-v29/themes.xml` 里
  `android:forceDarkAllowed=false` + `android:enforceNavigationBarContrast=false`。
  不关的话，系统深色模式下 Android 10+ 会对整棵 View 树强制反色，新拟态的灰阶双向阴影
  全被搅成糊色块（真机「全是乱码/UI 错乱」的根因，v0.2.1 修复）。
- 系统栏图标用 `SystemBarStyle.light(...)` **锁定深色图标**，不要用 `auto`——auto 跟随系统
  深色模式会把图标翻成白色，浅色底上直接看不见。
- 冷启动底色：`values/colors.xml` 的 `neu_background`(#E4E9F3) 挂在主题
  `android:windowBackground` 上，消除启动白屏闪烁；改 NeuBackground 时两处要同步。
- 启动图标 = 自适应图标（`mipmap-anydpi-v26`，前景 `drawable/ic_launcher_foreground.xml`
  = 靛蓝气泡+三点），minSdk 26 全覆盖，无需 legacy PNG；monochrome 在 API<33 自动忽略。

**页面结构（标准 IM 习惯）：**
- 聊天 tab 主页 = **会话列表**（搜索框整行、会话行=头像+名字+预览+时间+未读点，无身份标签）
- 点会话 → push 滑入**单聊页**（左上 ← 返回退出）；**每个 persona 的会话消息完全独立**
- 底部 tab：消息 / 数据 / 任务，切换带方向感知滑动

## 五、数据模型核心约定

1. **人设（Persona）不写死**：用户可增、删、改，不限于"导师"。只预置 3 个种子
   （id 1 阿布/人生导师 🖋️，id 2 严格教练 🎯，id 3 苏格拉底 💭）。`conversationId = personaId`，
   一设一会话，消息互不混淆。
2. **对话风格（ToneStyle）**是叠加在人设上的语气维度：GENTLE / STRICT / SOCRATIC，
   实现 = 换 `promptSnippet`，成本为零。风格切换**不是换人设**。
3. system prompt 组装顺序（接 LLM 时）：`persona.systemPrompt` + `tone.promptSnippet` + 用户档案（ProfileFact）。
4. API 密钥走 Android Keystore / EncryptedSharedPreferences，**不允许明文落盘**。

## 六、工作流约定（重要）

1. **预览即设计基准**：`preview/all.html` 是单文件高保真交互预览（单手机三页 + 会话进出）。
   任何 UI 变更**先改预览 → 用户确认 → 再同步 Compose 代码**，两边保持一致。
2. **改动 Compose 后自查**：Kotlin 文件注意未使用的 import 要清理；ViewModel 用 `viewModelFactory`
   + `APPLICATION_KEY` 模式获取仓库（暂无 Hilt）。
3. **种子数据**集中在：`PersonaRepository.seedIfEmpty()`（人设）与 `ChatViewModel.seedMessages()`（按 personaId 分会话）。
4. 项目记忆在 `.workbuddy/memory/`（日志按天追加，长期约定在 MEMORY.md），跨会话先读它。

## 七、路线图（按优先级）

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
- [x] AI 提炼流水线（v0.4.0：`data/ExtractionEngine.kt`——聊天回复成功落库后检查水位线，攒够 8 条增量消息自动调 LLM（复用 OpenAiClient）；产出 ProfileFact（按 key 覆盖，manual 来源不被篡改）+ AiInsight（标题去重、留 20 条）；解析器 `ProfileExtractor` 纯函数 + JVM 单测；数据页洞察区有手动「提炼」入口（API 未配置时隐藏））
- [x] 数据页习惯/洞察接真实数据（v0.4.0：ai_insights 表（Room v4 + MIGRATION_3_4）驱动洞察卡，空时回退示例并标注「示例数据」；习惯卡改为「近 7 天打卡」每日完成热力（真数据）；档案条目 source=ai 带小徽标）

## 八、本地构建（已验证可用）

本机已具备完整工具链，**不要下载 Android Studio，也不要用 `./gradlew`**（项目无 wrapper，gradlew 会联网拉 190MB 发行包）。

| 组件 | 路径 |
|---|---|
| JDK 17 | `C:/Users/16621/.workbuddy//toolchains//jdk17` |
| Android SDK | `C:/Users/16621/.workbuddy//toolchains//android-sdk`（platform-34/35，build-tools 34.0.0/35.0.0，cmdline-tools/latest） |
| Gradle 8.14.3 | `C:/Users/16621/.gradle/wrapper/dists/gradle-8.14.3-all/<hash>/gradle-8.14.3/bin/gradle.bat` |

```bash
cd "C:/Users/16621/Desktop/导师·"
export JAVA_HOME="C:/Users/16621/.workbuddy/toolchains/jdk17"   # 正斜杠的 Windows 路径实测可用；MSYS 的 /c/... 形式会让 gradle.bat 报 invalid directory
export ANDROID_HOME="C:/Users/16621/.workbuddy/toolchains/android-sdk"
"<gradle.bat 路径>" assembleDebug --no-daemon --console=plain > build.log 2>&1
```

已有配置（勿删）：`local.properties`（sdk.dir）、`gradle.properties` 的 `android.overridePathCheck=true`（项目路径含中文与「·」）、`settings.gradle.kts` 里的阿里云镜像（必须排在 google() 之前）。

四个已知坑与解法见技能 `capacitor-android-offline-build`：AGP 拉不到（镜像）、中文路径（overridePathCheck）、缺 cmdline-tools 导致静默挂起（看日志是否刷 "waiting for package manifests"）、JAVA_HOME 路径格式。

产物校验：
```bash
SDK="C:/Users/16621/.workbuddy/toolchains/android-sdk"
"$SDK/build-tools/35.0.0/aapt2.exe" dump badging app/build/outputs/apk/debug/app-debug.apk | grep -E "^package|application-label|sdkVersion"
"$SDK/build-tools/35.0.0/apksigner.bat" verify --print-certs app/build/outputs/apk/debug/app-debug.apk
```

**分发装真机用 release 包**（2026-09-27 已验证）：`assembleRelease` 已开 R8 混淆压缩 + 资源收缩 + debug 签名（与 debug 包同证书，可直接覆盖升级），体积 ≈1.5MB（debug 18MB）。规则在 `app/proguard-rules.pro`——Compose/Room/OkHttp/coroutines/DataStore 全自带 consumer rules，**不要手写多余 keep**；本 App 无反射取类，新增依赖若带反射序列化（Gson/Retrofit 等）需补规则。dex 抽查法：解出 classes.dex 后 `grep -aoE "Lcom/daoshi/app/[A-Za-z0-9/$]+;"`，Manifest 组件应原名保留。

**JVM 截图测试（无真机时"亲眼"验证 UI，2026-09-28 新增）**：`app/src/test/java/com/daoshi/app/UiRenderTest.kt` 启动真实 MainActivity、按用户路径点击导航各页，Roborazzi 用真机同款 Skia 管线出 PNG。用法：

```bash
# 中文路径坑之五：Gradle 测试 worker 经 java @argfile 传 classpath，JDK17 启动器按系统 GBK
# 解码 UTF-8 字节，中文路径条目失效（ClassNotFoundException）。必须先映射 ASCII 盘符：
subst X: "C:\Users\16621\Desktop\导师·"
cd X:/
gradle recordRoborazziDebug   # 产物 app/build/screenshots/*.png；镜像已在 gradle 配好（android-all ~150MB 首跑下载）
```

## 九、不要做

- 不要引入 Hilt/Dagger 等重 DI（保持手动容器，vibe coding 友好）
- 不要改回 ins / iMessage / 微信风 UI（Neumorphism 新拟态是用户 2026-09-27 拍板方向）
- 不要在单聊页/数据页/任务页加设置入口（只在会话列表）
- 不要把人设写死成代码常量（走 Room 种子）
- 不要上传用户数据到任何云端
