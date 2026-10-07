# AGENTS.md — 梦欢 · 项目协作指导

> 本文件面向在此项目工作的 AI 助手与人类开发者。动手前必读，读完再改代码。
>
> 配套文档（别把内容往本文件里堆，本文件要一直保持"能一次读完"）：
> `docs/DESIGN.md` = 设计系统细节｜`docs/ROADMAP.md` = 功能台账（做完勾一项）｜`docs/CHANGELOG.md` = 逐版本变更。

## 零、最高优先级约定（2026-10-04 用户拍板，违反即返工）

1. **新功能先去 GitHub 找优秀案例，别自己从零拍脑袋写。**  
   GitHub 上有大量免费、公开、成熟的同类实现，**可以直接读、可以直接借鉴、可以直接复制适配**。  
   动手顺序固定为：  
   ① 先用搜索找 star 高 / 维护活跃的同类实现（中文 + 英文关键词都要搜，也直接搜  
   `android ... sample`、`awesome-android ...`）；  
   ② 读它的实现思路与关键代码；  
   ③ 再照着成熟做法落到本项目，而不是自己发明一套。
   （不用管许可证，直接用。）
   **自己闭门造车是这个项目返工的主要来源**（键盘 inset、语音转写都栽在这上面）：
   那些坑前人早就踩过并写在开源实现里了。
2. **不会就说不会，不要"随便搞"、更不要试错式反复提交。**  
   对没把握的改动，先向用户说明不确定性（或按第 1 条先找现成案例），  
   **不要抱着"先改一版试试"的心态连着返工** —— v0.43.1 连着三轮返工都没解决问题，  
   用户明确表达了反感。一次改不对比快速迭代十次更可接受。
3. **动手之前先问清楚，拿准确信息、给方向让用户选（2026-10-04 拍板）。**  
   开始任何新功能 / 修复之前，若需求存在歧义或有多种实现路线，**必须先停下来问**，  
   不许自作主张替用户做决定：  
   ① **先问用户拿准确信息**：实际想达到的效果、使用场景、复现步骤、设备情况 ——  
      信息不全就动手 = 大概率返工；  
   ② **把可选方向列出来供用户选**：每个方向说清楚"怎么做、什么代价（体积/流量/依赖）、  
      适合什么情况"，让用户按自己的意图挑；  
   ③ **用户选定后再动手**，做完交付时要能对得上用户当初选的那个方向。  
   （方向唯一、没有歧义的小修可以直接做；但凡"这么做也行、那么做也行"，一律先问。）
4. **键盘 / 语音模块现行实现（键盘 v0.44.0 整体替换；语音 v0.50.0 整体重做），未经点名不得再动。**  
   - 键盘避让：`ui/chat/KeyboardInsets.kt` —— 官方 `WindowInsetsAnimationCompat` + 导航条基线；  
     `ImeDismissesPanels` 让"键盘真弹起"反过来收掉面板/语音态（面板与键盘互斥由**键盘状态**驱动，
     不靠点击入口各自 hide/show —— PanelSwitchHelper 的核心思路）。  
   - 语音采集：`data/VoiceRecorder.kt` —— AudioRecord 直采 PCM + 手写 WAV 头。  
   - **语音栈 v0.50.0 整体重做**（施工依据 `docs/PLAN-v0.50.0-语音执行方案.md`，协议依据
     `docs/RESEARCH-v0.50.0-TTS协议查证.md`）：一条识别链 + 一条合成链 + 一个标记协议 + 两个开关。
     - 识别：**两条并列的路，手动切换、绝不自动回退**（v0.59.0 用户拍板把在线识别加了回来）。
       - `data/SherpaAsr.kt` = 离线一级（sherpa-onnx `OfflineRecognizer` + **SenseVoice 离线非流式**），默认走它；
       - `data/OnlineAsr.kt` = 在线一级（通用 OpenAI 兼容 `POST {地址}/audio/transcriptions`，multipart
         `file` + `model`），地址/Key/模型用户自己填，**App 不猜任何服务商域名**（§零.6）；
       - `data/SpeechToText.kt` 按 `SettingsRepository.asrEngine`（`offline` / `online`）二选一。
       ⚠️ **绝不做"在线不行就回落离线"**：当年云端 Whisper 与系统识别器就是坐在自动回退的第二级上，
       用户配的端点实测不支持 → 每次白等一轮超时、"识别为空"哪一级出的事谁也说不清，三个版本后整条删掉
       （连 `OpenAiClient.transcribe` 一起）。现在它是一条**明示的选择**：选在线就只走在线，
       失败把 `OnlineAsr.lastFailure` 原样报给用户（401 是没配好 Key、404 是这家压根没这个接口、
       超时是网络，三种原因的处理动作完全不同，**不许糊成"没听清"**）。
       ⚠️ **通话例外**：通话逐段喂 PCM、**始终走端侧**（在线要"一个文件一次请求"，每句多一次网络往返
       会把对话拖死），与这个开关无关。
     - **语音消息与通话共用同一个识别器实例**（`CallAsr.kt` 已删，通话改调
       `SherpaAsr.transcribeSamples`）：228MB 权重建两个 session ≈ 500MB，中低端机直接 OOM。
     - **识别模型不随包**：`data/AsrModelManager.kt`（Range 断点续传 + 逐文件 sha256 + 删除）
       + `ui/settings/AsrModelScreen.kt`；模型落 `filesDir/asr_sensevoice/`，
       源顺序 **ModelScope → hf-mirror**（v0.51.0 去掉"手动导入"：普通人不知道该下哪个文件。
       实测 GitHub Releases 不可达、hf-mirror 证书异常）。
       就绪判定 = `.verified` 标记 + 两文件字节数全等，**不是每次重算 228MB 哈希**（点语音键就要调）。
       `assets/asr/` 只剩 `silero_vad.onnx`（1.8MB 继续内置）。
     - 合成：`data/TtsClient.kt` 三家可切（硅基流动 CosyVoice2 / 小米 MiMo / 自定义 OpenAI 兼容），
       **三家协议不统一、逐家一个实现**（MiMo 走 `/chat/completions` 且目标文本必须放 `assistant`；
       硅基预置音色要拼 `<model>:<alias>`、回裸二进制；自定义端点只发四个最小字段）。
       `synthesize(text, voice, target)` 的 **`target` 决定要什么格式**（v0.52.0）：
       `TtsTarget.CALL` = **WAV + `sample_rate=24000`**（正好等于播放轨道采样率，通话链路
       **根本不解 MP3**），`TtsTarget.NOTE` = MP3（语音条落盘给 MediaPlayer 播）；
       MiMo 两个目标都要 wav（它那个 mp3 枚举没人用过，不赌）。24000 的唯一来源是
       `data/TtsConfig.kt` 的顶层常量 `TTS_CALL_SAMPLE_RATE`，`CallAudioManager.OUT_RATE` 引用它——
       两边写成两个数就会每句白做一次重采样（变速变调）。
     - **每家一套独立配置（v0.52.0）**：DataStore 键按 provider 分桶 `tts_<家>_api_key_enc/plain`、
       `_base_url`、`_model`、`_default_voice`、`_manual_voice_id`，`tts_provider` 只记"当前生效哪家"。
       老单桶键虚拟归属到**当时生效的那一家**（`ttsConfigOf(provider)` 读），写桶时
       `migrateLegacyTtsLocked` 先搬再清老键——**保存别家时把老键直接清掉会抹掉硅基填好的 Key**
       （这条是 `TtsProviderConfigTest` 抓的）。只有配齐的家才能成为"当前生效"。
     - **音色**：预置音色每家只留**一男一女**（硅基 `alex`/`anna`，小米 `白桦`/`冰糖`，
       自定义无预置）。名单与标签都在 `TtsConfig.kt` 的 `TtsVoices.library()` / `labelOf()`（纯函数），
       「设置 → 语音 → 音色」板块和「编辑人设」的候选**共用同一个函数**，两边不会各说各话。
       板块 = 预置 + 我克隆的 + 手填 ID，右上角「克隆音色」，点一行 = 设为默认，行尾「试听」；
       人设页只挑、不试听不添加。音色三级回落集中在 `SettingsRepository.voiceForConversation()`
       （人设专属 → 板块默认 → 该家第一个预置），聊天与通话都走它。
     - `data/VoiceCloner.kt`（硅基 App 内克隆：裁样本 → **端侧 ASR 自动出逐字稿** → multipart 上传换 uri）。
       **样本格式不再限死 mp3/wav**：v0.52.0 起统一用 `realtime/MediaAudioDecoder.kt`
       （`MediaExtractor` 自己读真实采样率/声道），mp3 / wav / m4a / **mp4** 都收——
       用户上传 mp4 时直接抽里面的音轨，不需要写 MP3 编码器。
       ⚠️ **v0.53.3 两条不许回退**：① 重采样必须走 `WavPcm.resample`（降采样前做低通），
       不能用裸 `resampleLinear`——48k→24k 隔点取样的混叠会烘进参考音频，
       克隆出来发抖变调（预置音色正常、只有克隆怪，就是这个）；
       ② 样本必须 `trimToSpeech`（跳前导静音 + 在能量谷下刀，上限 10 秒）——
       CosyVoice2 是零样本克隆，切在半句话上参考与逐字稿就对不上。
       样本还必须是**干净单人声**：带背景音乐代码救不了，弹层里已写明。
     - 通话播放 `realtime/TtsAudioDecoder.kt`：按容器嗅探分流 + **下混单声道 + 重采样到 OUT_RATE**。
       以前 Edge TTS 恒定 24kHz 单声道 MP3 刚好等于 `OUT_RATE` 所以没这层；换三家后不归一化会变速变调。
       MP3 分支落临时文件交给 `MediaAudioDecoder`（网关不守 `response_format` 时的兜底）。
     - **`data/WavPcm.kt` 位深不挑（v0.53.0）**：PCM 8/16/24/32 与 IEEE float 32/64 都解，
       `WAVE_FORMAT_EXTENSIBLE` 从 SubFormat GUID 取真编码，流式 WAV 的 `data` 长度写成 0 / 0xFFFFFFFF
       时按"到文件尾"处理。v0.52.0 只认 PCM16，硅基回的 float32 WAV 被判"解不开" →
       通话整场没声（真机反馈"服务商回的 WAV 解不开"）。**别再把它收窄回去。**
     - **克隆音色的名字**：服务端 `customName` 只收字母/数字/`_`/`-`（HTTP 400 原文），
       所以上传发 `VoiceCloner.asciiVoiceName(中文名)`，用户起的中文名存本机
       （`SettingsRepository.cloneVoiceNames()` 那张 `uri → 名字` 表）只用于显示。
       直接把中文当 customName 发出去 = 100% 400（v0.52.0 真机踩过）。
     - AI 语音条：`data/VoiceNoteMarker.kt` 解析 `【语音条】` 标记，合成成功后把刚落库那条
       **原地升级**成语音条（不另插一条，否则文字与语音说同一件事）；`VoiceBubble(isUser=…)`
       AI 侧走面色细线、用户侧保持黑底药丸。播完一定复位不再只靠 `MediaPlayer.onCompletion`
       （MP3 尾部 padding 时那个回调可能整场不来）——`ChatScreen` 有按 position/duration 的看门狗。
     - **已删除、不要复活**：Edge TTS（`EdgeTtsClient.kt`）、**`realtime/Mp3Decoder.kt`**（v0.52.0）——
       它把**输入** `MediaFormat` 硬写成 `24000Hz/单声道`，真实流不符就吐 0 帧，
       同时是"通话：声音合成失败/音频解码失败"与"克隆：文件格式不对"两条真机反馈的同一个根因。
     - **朗读（v0.59.0 按用户要求加回来，形状与当年不同）**：长按消息气泡菜单「朗读」念任意一条 +
       **按角色各开各的**「自动朗读」开关（在**该角色的会话设置第一组最后那行**，与「联网搜索」并列），
       **默认全关**。当年被删的是"右上角喇叭自动念"那一套
       （`ReadAloudPlayer.kt` + 全局 `chat_read_aloud` 键），**不要把它复活回来**：现在的实现是
       `ChatViewModel.readAloud()`，合成**复用语音条那条链**（`TtsClients` + `voiceForConversation`
       三级回落音色），播放 = 临时文件 + `MediaPlayer`；`UiState.readingId` 让气泡知道自己在被念。
       开关存法与「会话联网」同一套 `id\t1|0` 表（`chatReadAloud`）。
       ⚠️ 三条纪律：① 同一时刻只念一条，再点同一条 = 停；② 复位**不能只靠 `onCompletion`**
       （MP3 尾部 padding 时那个回调可能整场不来）——按 `probeDurationMs` 的时长挂兜底；
       ③ 每次打断都 `readToken++`，让在飞的合成协程回来发现自己过期就放弃，
       不许它把新一轮的状态覆盖掉。AI 已经发了语音条的回复**跳过自动朗读**（同一句话别念两遍）。
     - 构建配置三处硬约束：`noCompress` **只剩 `onnx`**（`txt` 随内置 tokens 一起移除）、
       `ndk.abiFilters` **只剩 `arm64-v8a`**（去 v7a 省 22MB，用户已确认放弃 32 位老机）、
       `proguard-rules.pro` 的 `-keep class com.k2fsa.sherpa.onnx.**` **保持不变**（JNI 字段被混淆会崩）。
       ⚠️ 这三处任一被"顺手改回"都会让语音崩或让包涨回 80MB。
   ⚠️ 修键盘这块**先按第 3 条问清方向**。语音栈刚在 v0.50.0 整体重做完，改前先看上面两份文档，
   别凭记忆改。

5. **内置「应用助手」的使用手册必须跟着每一次版本更新走，不许滞后（2026-10-05 用户拍板）。**  
   内置人设「应用助手」的设定词（`data/AiPrompts.kt` 的 `DEFAULT_ASSISTANT_PROMPT`）就是这款 App
   的使用手册，用户会直接问它"这个功能怎么用 / 语音怎么配"。它一旦滞后，用户得到的就是**错答案**
   ——比没有助手更糟。所以：
   - **改了功能入口、加了功能、删了功能，同一个版本里必须同步改手册**；
   - 手册是在 `DEFAULT_ASSISTANT_PROMPT_V038`（**底稿，不要再改它的措辞**）全文上做**定点 `.replace()`**，
     不把整份文案复制第二遍。⚠️ `replace` 的子串对不上时**静默落空**、不报错不崩，
     而底稿经 `trimIndent()` 后**段首是没有缩进的**——搜索串多写一个空格就整段白改
     （v0.50.0 真实踩过）；
   - 因此**每加一条 replace，必须同时在 `app/src/test/java/com/menghuan/app/AiPromptsTest.kt`
     补一对断言**：新措辞 `contains` + 旧措辞 `assertFalse(contains)`。只写一边守不住静默失效；
   - **启动升级机制（v0.51.1 重写，别再改回去）：改手册不需要登记任何"历史版本全文"。**
     只要用户没亲手改过助手的设定词，启动时一律刷成最新 `DEFAULT_ASSISTANT_PROMPT`
     （`PersonaRepository.refreshAssistantPromptIfNeeded` + DataStore 三态标记
     `assistant_prompt_customized`；用户在提示词页/人设编辑里存过自定义内容 = 改过 = 从此不覆盖，
     点「全部恢复默认」则重新纳管）。旧做法是"库里这段是否**精确等于**某个历史默认文案"，
     而每版都是在原地追加 replace、中间态从未登记，于是 v0.41~v0.50 之间升过级的用户再也匹配不上，
     手册永久卡死——v0.50.0 写了整章语音配置教程，真机上助手依然答不上"语音怎么配"，
     就是这个机制漏的（回归测试：`AssistantManualUpgradeTest`）。
   - 涉及**外部服务配置**的功能（语音合成、生图、LLM 接口），手册必须写清"去哪家官网、拿什么、
     填到哪一步"，给出可点击的官方地址，不能只说"去设置里看看"。用户问"语音怎么配置"时
     要能直接引导到 `cloud.siliconflow.cn` 注册拿 Key 的完整步骤。
6. **不替用户猜外部服务的地址与 Key。** 手册/提示词里出现的服务商网址必须是**查证过的官方地址**，
   不确定的写"请查看服务商官网"，不要编一个看起来合理的域名。
   （反例：参考包里的 `cloud.siliconflow.cn/i/owSKCHzC` 是**第三方作者的邀请链接**，带邀请码，
   绝不能抄进本 App 的文案。）
7. **视频通话（v0.58.0）：通话能力与视觉形象彻底解耦，以下五条不许回退。**
   施工依据外部方案文档 `~/tabbit/梦欢_实时通话_视频化通话_开发方案与总Prompt.md`（本期不做 3D）。
   - **只有两个入口共用一套实时通话**：`call/{personaId}`（语音）与 `video_call/{personaId}`（视频）
     都跑 `PipelineVoiceCallEngine`，**没有第二条网络链路、第二套音频链路**。视觉层挂在
     `realtime/visual/`（`CallVisualEngine` + 三个实现 None/Video/Live2d + `CallVisualState` 六态），
     由 VM 订阅引擎状态后驱动。
   - **语音通话必须完全不碰视觉层**（方案 §二.一）：不加载 Live2D、不建播放器、不扫素材目录。
     `videoMode=false` 时 VM 连 `CallVisualEngine` 都不创建。
   - **视觉失败 ≠ 通话失败**（方案 §二十四）：任何视觉异常只把 `VisualRuntime.Degraded(原因)` 交给
     页面回落到头像，**禁止把异常冒泡进通话链路**（`suspend fun load` 内部 swallow，帧循环包 runCatching）。
   - **口型电平按播放头报，不按写入速度报**：唯一来源是 `CallAudioManager.playPcm` 里那个
     `pendingLevels/pendingEnds` 队列 —— 非阻塞写会先把 AudioTrack 缓冲灌满，按写入速度算电平
     就是"AI 还没出声嘴先动半秒"。`stopPlayback()` 必归零（打断后嘴要收）。
     RMS→门限→开平方在 `CallAudioLevel`（纯函数 + 单测），平滑在 `Live2dCharacter.MouthProvider`
     （一阶低通，与帧率无关）。⚠️ 官方 Sample 的 `LAppWavFileHandler.getParameter()` 是**空壳**
     （直接 return 0.0f），别当参考实现抄；正规入口是 `CubismLipSyncUpdater(ids, IParameterProvider)`。
   - **`cubism/` 是 vendor 的官方 Cubism SDK for Java 5-r.5，不要"顺手升级/替换/自研解析 .moc3"**：
     Framework 105 个 java 文件原样放在 `cubism/framework/src/main/java`，**通过 app 的
     `sourceSets.main.java.srcDirs` 编进 app 模块，不建成独立 library 模块** —— AGP 明确禁止
     library 模块带本地 `.aar` 出 AAR（实测 `:cubism-framework:bundleReleaseLocalLintAar` 直接
     报 "Direct local .aar file dependencies are not supported when building an AAR"）。
     闭源 Core 是 `cubism/libs/Live2DCubismCore.aar`，由 app 用
     `implementation(files(rootProject.file("cubism/libs/Live2DCubismCore.aar")))` 引
     （和 `app/libs/sherpa-onnx-1.13.8.aar` 同一种接法；官方 `Core/RedistributableFiles.txt`
     允许随包分发，arm64 so 实测 110KB）。它**没有任何 Maven 坐标**（Maven Central / Aliyun 全 404），
     只能从 live2d.com 下载页拿（⚠️ 本机 `curl` 对 www.live2d.com 报 000 是**证书假死**，
     `--insecure` 就 200 —— 浏览器能正常打开，别说"官网不可达"）。
     JNI keep 规则写在 **`app/proguard-rules.pro`** 里：
     `-keep class com.live2d.sdk.cubism.core.** { *; }` **保持不动**（Core 字段被 JNI 按名字访问，
     混淆即崩，同 sherpa-onnx 那条教训）。官方 Framework 自带的 consumer-rules.pro 是**空文件**，
     指望它自动带进规则是错的。
   - **Media3 钉死在 1.9.0，别升到 1.10/1.11**：1.10 起 AAR metadata 要求 `minCompileSdk=36`，
     本工程 compileSdk 35 + AGP 8.7.3 会在 `:app:checkReleaseAarMetadata` 直接失败。
   - 伪视频**只用 `androidx.media3:media3-exoplayer`，不引 media3-ui**：PlayerView 的 `exo_shutter`
     就是黑屏来源，自己挂 TextureView 反而"新帧出来前一直显示上一帧"、切状态不闪黑，还省 330KB。
     素材一律静音（AI 的声音只有 AudioTrack 那一条链路）。
   - ⚠️ **v0.60 起视频通话页 = 全屏铺满，别再改回"按比例排的卡片"**（用户点名要 9:16 竖屏全屏铺满、
     不黑边不拉伸）：画面是**垫在最底层的背景层**（`CallVisualSlot` 只负责把整屏交给引擎），
     顶栏/字幕/静音挂断叠在它上面；以前画面夹在顶栏与字幕之间、按素材比例撑高，
     竖屏素材直接把字幕与挂断挤出屏幕（"视频通话没法用"的真根因）。
     铺满口径 = **cover**，由引擎在 View 内部做（伪视频 `VideoVisualEngine.applyCoverTransform`
     给 TextureView 设变换矩阵；Live2D 走 `fitProjection`）：留边违背"铺满"、拉伸会变形，二者都不要。
     有画面时页底**不能再铺 `NeuBackground`**（改透明），否则白底从画面边缘透出来描白边；
     可读性靠上下两层暗色渐变托底 + 时长/字幕走白色系。
     另：有画面时**不要**给叠在画面上的控件加 `staggerEnter`/`graphicsLayer` 这类 post-layout 变换
     （官方 SurfaceView 文档：合成按布局位置算，变换过的兄弟视图可能与曲面合不上）。
   - **通话页一律不显示名字**（v0.60.1 用户两次点名：视频顶栏、语音主视觉都是"头像就是身份"）：
     圆头像 + `personaBrush` 渐变环已经足够认出是谁，那行 20sp 的名字不要复活。
   - **语音通话的主视觉头像 = 屏宽 59%、中心落在屏高约 38.7%**（v0.60.1 用户照参考图点名）：
     `RealtimeCallScreen` 外层是 `BoxWithConstraints`，边长与顶部留白都按 `maxWidth`/`maxHeight`
     比例算（**不许写死 dp**，AGENTS 布局规矩），画布边长 = 头像边长 × `CallAvatarCanvasRatio`（196:124，
     脉冲环画在画布内、画布尺寸必须恒定，否则两态头像位置会跳）。
     ⚠️ 视频通话**有画面**那一支不受影响（走上面的居中顶栏，42dp 小头像）。
   - **视频通话有画面时 = 顶栏「头像 + 状态」一行、时间一行**（v0.60.1 用户点名，别再改回名字）：
     名字换成 42dp 圆头像（`personaBrush` 渐变环，同会话列表那颗），状态胶囊挂头像右边，
     时间在下面一行，整组**居中贴顶**。⚠️ 只在 `videoVisualOn` 时走这套；
     **没有形象（回落到居中大头像）时按上面那条大头像的规矩**，同一屏不会出现两颗头像。
   - ⚠️ **Live2D "加载成功却什么都不画" 的真根因：框架的 GLSL 是运行时读文件的，两处都不能缺**
     （v0.60.4 才查到底；v0.58~v0.60.3 三版都归错因，白绕）。Cubism Java 5-r.5 的
     `CubismShaderAndroid` 会按 `com/live2d/sdk/cubism/framework/shaders/standardES/*.vert|.frag`
     调 `ICubismLoadFileFunction` 读着色器源码，**读不到只 `cubismLogError` 后 `return 0`，不抛异常**
     → 所有 shader program 句柄为 0 → `drawModel()` 画不出任何东西，而 moc/贴图/物理是我们自己
     `File.readBytes()` 读的，所以 `ready=true`、`fail=null`、零报错。两处缺一不可：
     1. `app/build.gradle.kts` 的 `sourceSets.main` 必须挂
        `assets.srcDir(rootProject.file("cubism/framework/src/main/assets"))`。
        官方 Framework 是 library 模块、着色器在它的 `src/main/assets` 里会自动进 AAR；
        我们改成 `java.srcDirs` 搬源码后**assets 整个漏掉**了。
        ⚠️ 用**单数 `srcDir`（追加）**，别用 `srcDirs` —— 那会把模块自己的
        `app/src/main/assets/asr/silero_vad.onnx`（离线识别）挤掉，语音当场废。
     2. `CubismFramework.Option` 必须设 **`loadFileFunction`**（官方 Sample 在
        `LAppMinimumDelegate` 私有构造里有这句 `cubismOption.loadFileFunction = new LAppMinimumPal.LoadFileFunction()`，
        照抄时**漏了这一行就是全场静默失败**）。本工程实现在 `Live2dBootstrap` / `AssetLoadFileFunction`。
     3. **这类静默失败必须当场判死**：`Live2dVisualEngine` 首帧探针先查
        `Live2dBootstrap.shaderLoadFailure`，非空就 `Degraded("Live2D 着色器资源没打进包…")`
        回落到头像并把原因写在页面上。**别再靠"看着是白屏"猜**。
   - **下面两条也是真 bug（v0.60.1 修的），但都不是"看不见"的那一个**，别把它们当根因写回去：
     1. **模型加载必须在 `onSurfaceChanged`，不许挪回 `onSurfaceCreated`**。`GLSurfaceView` 保证
        created 先于 changed，**created 时窗口尺寸还是 0**，而 `CubismRendererAndroid.create(w, h)`
        拿这两个数去建**离屏渲染目标**——0×0 的缓冲不报错，只会把每帧画进零尺寸目标。
        官方 Sample 躲这一刀靠的是 `onSurfaceChanged → setRenderTargetSize(w, h)`，
        视图重新量过（旋转/分屏/回前台）时这一句也不能省。
     2. **v0.61.0 起 Live2D 的视图是 `GlTextureView`（TextureView + 自建 EGL 上下文与渲染线程），
        不要改回 `GLSurfaceView`**。`SurfaceView` 只有两种摆法且都不行：排在窗口下面要在窗口上挖洞，
        而 Compose 的 `AndroidView` 里**整棵 Compose 绘制树比子 View 先画**，洞会把顶栏/字幕/挂断
        一起擦掉；`setZOrderOnTop(true)` 排到窗口上面，角色又**盖住它覆盖范围内的 UI**
        （v0.60.2~v0.60.4 真机反馈"挡住挂断键"）。TextureView 走普通 View 绘制：透明处透出页面、
        UI 永远画在它上面。代价是 EGL 上下文、渲染线程、surface 生命周期都要自己管，
        全收在 `realtime/visual/live2d/GlTextureView.kt` 一个文件里。
        ⚠️ 官方 Sample 不涉及这一层（它的曲面是不透明整屏、上面没有 UI），**别拿它当依据**。
   - `publish()` 里 `live2d publish size=… ready=… fail=…` 与首帧 `live2d probe corner rgba=…`
     是分辨"没加载 / 加载失败 / 画不出来 / 缓冲没 alpha"的唯一手段，**别删**。
     （`rgba=0,0,0,0` = 缓冲透明、内容没画 → 直指着色器；这条就是破案的钥匙。）
   - 上传入口是会话设置页的**一行「视频通话形象」**（v0.58.1 用户拍板：原来 Live 2D / 伪视频 两行合成一行，
     点开先选类型再进各自的上传弹层；**不叠两层底部弹层**，是"关掉自己再打开对面"）。
     「Live2D 模型包」= zip 模型包 / 「伪视频素材」= 多选 mp4 + 逐状态指认；不在人设编辑页重复一份。
     旧的「角色立绘」行与 `persona_art` / `persona_live2d` 两个 DataStore 键**已废弃并清理**（单文件渲染不出来）。
     素材落 `filesDir/call_visuals/<personaId>/`，库里只有 `personas.videoVisualType`（Room v15）；
     两边不一致**以文件为准**。
   - **角色立绘（v0.58.1）= 另一条线，别和通话形象混**：入口在**编辑人设页**（`personas.artImagePath`，Room **v16**，
     文件落 `filesDir/portraits/`），用途是 **AI 生图时当参考图发给生图服务**（图生图），让 TA 画"自己"长相一致。
     v0.60.1 用户点名改形状：它是**「身份」下面一个和名字/身份同款的 `NeuTextField`**
     （`onClick` 参数 = 只读 + 整块可点、不弹键盘），框里显示图片文件名、点它就是选图，
     **原来的「上传立绘」按钮已删除**；已上传时下面一颗「删除立绘」。
     落盘**沿用用户那张图的原名**（`portraitTargetFile` 收非法字符 + 重名加序号），
     不再编 `art_<时间戳>` 那种用户没见过的名字。
     它和 `avatarImagePath`（圆裁头像）是两列，**不许互相顶替**：头像多半只有脸，当参考图等于把服装形象丢了。
     ⚠️ 各家网关的参考图字段名不统一（`image`/`images`/`init_image`/`ref_image`…），本项目的口径是
     **不猜参数名**：只发国内最通用的 `"image": [dataURL]`，被服务端判错就**自动去掉参考图重试一次**，
     成功则在 `ImageGenResult.note` 里明说"这张没按立绘生成"——结果对但条件没生效的事不许闷声。


## 一、项目是什么

**「梦欢」**：Android 平台的 AI 陪伴应用（v0.36.0 起改名，原「导师」；vibe coding 开发中）。  
用户与 AI 人设（persona）单聊，获得生活/学习/工作指导；App 收集用户数据形成洞察，  
并通过任务清单 + 定时提醒督促执行。用户**自行配置 LLM API**（LLM / 语音 / 生图等外部服务均自带 Key）。

- 定位：AI 陪伴 mentor，**联网运行**（用户自配 LLM / 语音服务；识别模型装后下载）
- **不设「纯离线 / 单机」约束**：账号体系与云端为后续规划方向，数据当前落本机只是现状、不是底线
- 交互核心：聊天为主界面，人设 = 聊天对象（类似联系人）
- 当前阶段：**核心闭环可用（真实 LLM / 任务提醒 / 档案注入），二期做 AI 提炼**

## 二、技术栈

| 层       | 选型                                                               |
| ------- | ---------------------------------------------------------------- |
| 语言 / UI | Kotlin + Jetpack Compose（Material 3）                             |
| 架构      | MVVM + Repository，手动依赖容器（`MentorApp`），无 Hilt                     |
| 本地库     | Room（personas / messages / tasks / profile_facts）+ DataStore（规划） |
| 网络      | OkHttp + SSE（OpenAI 兼容协议，`llm/OpenAiClient.kt`）                  |
| 构建      | Gradle Kotlin DSL，AGP 8.7.3，Kotlin 2.0.21，minSdk 26，targetSdk 35 |

## 三、目录结构

```
梦欢/
├── AGENTS.md                    ← 本文件（必须留在根目录，工具从这里自动加载）
├── docs/                        ← 配套文档（DESIGN.md / ROADMAP.md / CHANGELOG.md / PLAN-*.md）
├── preview/all.html             ← 旧版交互预览（已弃用，仅供历史参考）
├── app/src/main/java/com/menghuan/app/
│   ├── MainActivity.kt          # NavHost（home 四 tab Pager + chat/{personaId} + personas；deep link menghuan://open/{page}）+ MenghuanThemedRoot 主题根（解析外观模式 + 同步系统栏图标色）
│   ├── MentorApp.kt             # Application + 服务容器（db / persona / profile / settings 仓库 / extractionEngine / weeklyReportGenerator）
│   ├── data/
│   │   ├── model/Models.kt      # Persona(含 tone/pinned/avatarImagePath) / ToneStyle / ChatMessage(含 imagePath) / TaskItem / ProfileFact / AiInsight / AiWeeklyReport
│   │   ├── db/AppDatabase.kt    # Room v12 + 正式 Migration（9 DAO；v9 avatarImagePath/imagePath、v11 messages.reasoning、v12 ledger_entries/daily_suggestions；schema 导出到 app/schemas，禁破坏性重建）
│   │   ├── ExtractionEngine.kt  # AI 提炼流水线（水位线增量 + 近 7 天任务上下文 → LLM → facts/insights/taskSuggestions 落库/分发）
│   │   ├── WeeklyReportGenerator.kt # AI 周报（WeeklyReportMath 纯函数：周区间/统计聚合/prompt/宽容解析 + LLM 生成落库，同周覆盖）
│   │   ├── ProfileExtractor.kt  # 提炼 prompt + 宽容 JSON 解析（facts/insights/taskSuggestions，纯函数，JVM 单测）
│   │   ├── TaskSuggestionHub.kt # AI 任务建议中心（进程内存按 personaId 分发，确认后落库；同 title 当日去重）
│   │   ├── PersonaRepository.kt # 人设仓库（3 个种子均 isBuiltIn；updateTone/setPinned）
│   │   ├── AiPrompts.kt         # AI 预设提示词默认值（应用助手/基础/档案注入说明，设置里可改）+ 内置助手常量
│   │   ├── ProfileRepository.kt # 用户档案仓库（ProfileFact 增删改；v0.31.0 起无种子）
│   │   ├── SettingsRepository.kt# DataStore（API Key 经 ApiKeyCipher 加密；多接口 api_profiles / 生图接口 / theme_mode；可注入供测试）
│   │   ├── ApiProfiles.kt       # 多接口配置：ApiProfile/ImageApiConfig + ApiProfileCodec 纯函数编解码（老单接口虚拟读出）
│   │   ├── ImageGeneration.kt   # AI 生图：`[生图:…]` 标记解析（纯函数）+ OpenAI 兼容 /images/generations 下载落盘
│   │   ├── DailySuggestionGenerator.kt # 每日建议（v0.42.0 独立链路）：DailySuggestionMath 纯函数（FRAMEWORK schema + 可自定义 STRATEGY + 宽容解析）+ 上下文采集 + LLM 生成落库
│   │   ├── FileTextExtractor.kt # 文件正文抽取（v0.43.0）：txt/docx/xlsx/pptx/PDF 分类型抠文字喂模型，零依赖
│   │   ├── VoiceRecorder.kt     # 按住说话录音（v0.43.1）：WAV→AAC 回退链，失败原因回传 UI → filesDir/voice/
│   │   ├── SpeechToText.kt      # 语音转文字（v0.50.0 收成单级）：只有端侧 SherpaAsr，云端 Whisper 与系统识别器已删（实测两级永远走不通）
│   │   ├── SherpaAsr.kt         # 端侧离线识别（v0.50.0）：sherpa-onnx OfflineRecognizer + SenseVoice 非流式；模型在 filesDir/asr_sensevoice/；语音消息与通话共用同一实例
│   │   ├── AsrModelManager.kt   # 识别模型下载（v0.50.0）：Range 断点续传 + 逐文件 sha256 + SAF 导入 + 删除；源 ModelScope→hf-mirror；AsrDownloadSession 进程级进度
│   │   ├── TtsClient.kt         # 统一语音合成（v0.50.0）：硅基流动 / 小米 MiMo / 自定义 OpenAI 兼容三家，协议不统一逐家一个实现；失败原因回传
│   │   ├── TtsConfig.kt         # TTS 数据层纯函数（v0.50.0）：TtsProvider/TtsConfig/VoiceRef 编解码、voice 前缀拼法、容器嗅探、MiMo 上限
│   │   ├── VoiceCloner.kt       # 音色克隆（v0.50.0）：样本裁 15 秒→端侧 ASR 自动出逐字稿→multipart 上传换 uri；列音色（数组键名四路兜底）
│   │   ├── VoiceNoteMarker.kt   # 【语音条】标记解析（v0.50.0）：纯函数，剥标记 + 收空行
│   │   ├── StickerStore.kt      # 自定义表情文件管理（v0.43.0）：stickers/ 目录导入与删除
│   ├── llm/OpenAiClient.kt      # OpenAI 兼容 SSE 流式客户端（v0.38.0 起：streamEvents 带 usage 解析 + vision 多段 content）
│   ├── llm/ImageProbe.kt        # 生图接口探活（POST /images/generations 极简请求，生图端点没有 /models 可查）
│   ├── agent/                   # 手机 Agent MVP（v0.20.0）：无障碍感知 + 控件编号 + LLM 决策循环 + 手势执行 + 安全围栏；v0.37.0 起对话内 [操作手机:…] 标记直触发（完全权限无确认，挂应用级 scope）
│   ├── realtime/                # 实时语音通话（v0.46.0 管道版；v0.50.0 换引擎）：PipelineVoiceCallEngine（状态机唯一写手，VAD 分段→离线 ASR→LLM→切句→TtsClient→AudioTrack）+ CallVad（silero VAD，内置 assets）+ CallAudioManager（VOICE_COMMUNICATION 源）+ TtsAudioDecoder（容器嗅探分流 + 下混 + 重采样到 OUT_RATE）+ SentenceSplitter + RealtimeCallRepository；EdgeTtsClient/CallAsr/ReadAloudPlayer 已删；VoiceCallEngine 为可替换接口（将来插真双工）
│   ├── notify/                  # AlarmScheduler（每日重复/每日总结/每周日周报/每日建议 07:30/主动关心）+ TaskReminderReceiver（含稍后提醒）+ TaskSnoozeReceiver + DailySummaryReceiver + WeeklyReportReceiver + DailySuggestionReceiver + BootReceiver（跨天重置 + 闹钟重排 + 缺报补生成）+ ProactiveReceiver + AgentNotifier（Agent 进度常驻通知）
│   └── widget/TodayWidget.kt    # Glance 今日任务小组件（黑底白字 3 条，点击直达任务页）
│   └── ui/
│       ├── theme/Theme.kt       # 设计令牌：NeuPalette + LocalNeuPalette（浅/深两套色板严格反相）+ ThemeMode + personaPaletteOf
│       ├── theme/Icons.kt       # MIcons 纯线条图标（stroke 1.6–1.8，墨色 #000000）
│       ├── common/Neu.kt        # 组件核心：neuSurface 白面细线/纸叠阴影、figmaSelection 虚线选中框、NeuButton/NeuTextField/NeuSheet*
│       ├── common/AdaptiveScreen.kt # insets 唯一处理点 + 版心收窄（AdaptiveScreen）+ 平板双栏（AdaptiveSplitScreen）+ LocalWindowWidthClass
│       ├── chat/                # ChatListScreen（顶部搜索框滚动联动）+ ChatScreen（MarkdownText/按天分组/快捷打卡/微信风输入栏/语音条/文件气泡/生图气泡/失败重试）+ ChatViewModel + ChatDrafts
│       ├── chat/ChatInputPanels.kt # 输入区功能面板（v0.43.0，v0.45.0 收紧）：EmojiPanel（表情页 + 收藏页，收藏页末尾一个加号格=唯一添加入口）/ PlusPanel（相册/拍摄/文件）
│       ├── chat/CameraCaptureScreen.kt # App 内自绘拍摄界面（v0.43.1；v0.45.0 加淡入淡出 + 翻转镜头动效 + BackHandler）：CameraX + 白圆底黑图标；左相册/中快门/右翻转，拍完先确认再发
│       ├── chat/ChatComponents.kt # 共享件：ImageBubble 生图气泡 / StickerBubble 表情小气泡（v0.45.0，120dp 不可点开）/ VoiceBubble 语音条（v0.50.0 起带 isUser：AI 侧面色细线、用户侧黑底药丸）/ FileBubble 文件气泡 / ApiSwitchSheet 接口切换 / 提示词查看器
│       ├── persona/             # PersonaManageScreen + PersonaEditorSheet（新增/编辑/删除人设）
│       ├── realtime/            # 通话页（v0.46.0）：RealtimeCallScreen（大头像+状态主视觉、字幕辅助、静音/挂断大圆钮；缺模型/缺 TTS 提示条 + 一键拉起面板）+ RealtimeCallViewModel（引擎事件聚合字幕）；路由 call/{personaId}，入口在单聊加号面板第四格；v0.50.0 起字幕**按 VAD 段出**（离线非流式没有逐字 partial）
│       ├── data/DataScreen.kt   # 我的数据页（v0.42.0 顺序：本周回顾条置顶 → 今日建议 → 记账本单卡 → AI 洞察/周报合并入口；v0.42.1 去阴影+删说明小字）
│       ├── data/AiDetailScreen.kt # AI 洞察 + 周报 全屏详情页（v0.42.0：洞察全文 + 周报全文，最新一期默认展开）
│       ├── data/DataViewModel.kt # 数据页 VM（含纯函数 buildWeekReview / weekReviewStreak；LedgerStatsScope MONTH/WEEK + periodStats）
│       ├── ledger/              # 记账本（v0.42.0 建，v0.42.1 加周度统计）：LedgerScreen 全屏页（月/周可切统计卡）+ LedgerComponents 记一笔弹层/流水行 + LedgerInput 语音与解析
│       ├── profile/MeScreen.kt  # 「我的」页（第 4 tab：人设管理/基础档案/AI 图库/AI 提示词/设置 五张行卡 + MeViewModel；
│       │                        #   v0.51.0 起五张卡全部跳全屏页，档案编辑与 AI 图库也在这里定义成 ProfileScreen / GalleryScreen）
│       ├── tasks/TasksScreen.kt # 任务页（分组卡片/勾选打卡/长按删除 + TasksViewModel）
│       ├── common/MarkdownText.kt # AI 气泡轻量 Markdown（粗体/行内码/列表/标题/代码栅栏）
│       ├── settings/SettingsScreen.kt  # 设置主页（v0.51.0 起「我的」这条线全部是全屏页 + NavHost 路由，不再是弹层）
│       ├── settings/SettingsPage.kt    # 设置链全屏页统一骨架（顶栏返回 + 可滚动 + 版心收窄）+ SettingsRoutes 路由名表
│       ├── settings/SettingsScreens.kt # 外观 / 通知与提醒 / 数据与隐私 / 语音 / 关于 五个设置页
│       ├── settings/ApiConfigScreen.kt + PromptSettingsScreen.kt（AI 提示词八段）+ TtsConfigScreen.kt + AsrModelScreen.kt
```

## 四、设计系统（Figma 风，2026-09-28 用户拍板）

定调：**黑白界面骨架 + 鲜亮品牌色只作内容点缀**（取代 2026-09-27 的 Neumorphism 新拟态）。  
**v0.35.0 起自带深色模式**：跟随系统 / 浅色 / 深色三选一（设置 · 外观），两套色板严格反相。

**令牌读取是 `@Composable` 的（重要，v0.35.0 起的硬约定）**：所有颜色令牌  
（`NeuBackground` / `TextPrimary` / `NeuAccent` / `AccentInk` …）都是从  
`LocalNeuPalette` 读出来的 `@ReadOnlyComposable` getter，**只能写在 composable 作用域里**。  
`drawBehind` / `Canvas` 的 DrawScope 不是 composable——遇到这类回调先把令牌取成局部变量再传进去，  
否则编译不过（这也是改造后所有裸 `Color.Black/White` 必须换成令牌的原因）。

| 令牌                                                                        | 浅色值                                                                                                        | 深色值                                           | 用途                                                              |
| ------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------- | --------------------------------------------- | --------------------------------------------------------------- |
| `NeuBackground` / `NeuSurface`                                            | `#FFFFFF`                                                                                                  | `#0E0E11` / `#17171B`                         | 页面基底 / 凸起面                                                      |
| `NeuTrack`                                                                | `#F2F3F5`                                                                                                  | `#24242B`                                     | 凹陷/输入面（Figma 画布灰）                                               |
| `NeuHairline`                                                             | 黑 8%                                                                                                       | 白 12%                                         | 唯一分隔手段：1dp 细线 + 细线描边                                            |
| `TextPrimary` / `TextSecondary` / `MutedText` / `FaintText`               | `#000000` / `#595959` / `#8C8C8C` / `#BFBFBF`                                                              | `#F3F3F5` / `#B6B6BF` / `#85858F` / `#63636D` | 四级灰阶文字                                                          |
| `NeuAccent` / `NeuAccentDeep`                                             | `#000000` / `#1A1A1A`                                                                                      | `#FFFFFF` / `#EDEDF0`                         | 强调色：主按钮 / 勾选 / 选中 chip / 底栏胶囊                                   |
| `AccentInk`                                                               | `#FFFFFF`                                                                                                  | `#0E0E11`                                     | **强调色上的文字与图标色**（强调色一反相就必须跟着翻，否则深色下白字白底）                         |
| `NeuDanger`                                                               | `#F24E1E`                                                                                                  | `#FF6A45`                                     | 危险操作                                                            |
| `PaperShadow` / `SelectionInk` / `HandleFill` / `HandleInk` / `SheetGrip` | 黑 / 黑 / 白 / 黑 / `#E0E0E0`                                                                                  | 黑 / 白 / 面板色 / 白 / `#3A3A42`                   | 阴影、虚线选中框、手柄、弹层抓手                                                |
| 品牌色                                                                       | `BrandGreen #0ACF83` `BrandYellow #FFC700` `BrandPurple #A259FF` `BrandBlue #1ABCFE` `BrandSalmon #FF7262` | 同左                                            | **只作内容点缀**：Hero 渐变卡、打卡热力、persona 渐变头像环、Spark 图标，绝不用于界面骨架；深浅两版一致 |
| `HeroBrush` / `SparkBrush` / `AccentBrush`                                | 见 Theme.kt                                                                                                 | 跟随色板                                          | 绿→黄→紫→蓝扫掠 / 紫→橙线性 / 强调色竖渐变                                      |
| `personaBrush(id)` / `personaPaletteOf(id)`                               | 12 组品牌配色 × 2 走向                                                                                            | 同左                                            | 按 id **稳定散列**（重启后不变）的渐变环，纯函数可单测                                 |
| 气泡                                                                        | AI = 面色细线；用户 = 强调色底 + `AccentInk` 字（圆角 22dp，maxWidth = 版心 76%）                                             | 反相                                            | 聊天气泡，无尾巴                                                        |
| 图标                                                                        | 21–23px 纯线条符号（stroke 1.6–1.8），靠 `Icon(tint=令牌)` 上色，无底色圆                                                    | 同左                                            | `ui/theme/Icons.kt` MIcons                                      |
| 字体                                                                        | 系统字体栈 + 负字距（标题 -0.2~-0.4sp）；小标签用 letterSpacing +1sp 的大写风格（`figmaMono` 习惯）                                  | 同左                                            | 全局                                                              |

**组件核心实现（`ui/common/Neu.kt`，勿绕过；组件名沿用旧 API，内部已全部换装）：**

- `Modifier.neuSurface(cornerRadius, color, elevation, intensity, inset)`：`inset=true` 画 `NeuTrack` 灰平涂（输入框/凹陷胶囊）；否则白面 + 1dp hairline 描边。**v0.42.1 起全局零阴影**：纸叠阴影已删除，`elevation` 参数保留但失效（`@Suppress("UNUSED_PARAMETER")`，为不动几十个调用点）。**禁止 BlurMaskFilter 与 Modifier.blur**（minSdk 26 真机流畅）。
- `Modifier.figmaSelection(active)`：虚线 1.6dp 黑描边（dash 7/6）+ 四角 7dp 白底黑边手柄方块 —— Figma 选中框隐喻，用于选中 sheet 行/空状态描边。
- 按钮统一药丸 999dp（Primary=黑底白字）、`NeuChip` 选中=黑药丸白字、`NeuCheckCircle` 选中=AccentBrush 黑渐变、`NeuSwitch` 黑轨白钮。
- 按压反馈统一 `rememberNeuPressState()`：scale 0.96（110ms）。v0.42.1 起不再收矮阴影。
- 动效：弹簧优先（`NeuSpring` MediumBouncy / `NeuSnappySpring`），状态色用 animateColorAsState；`EaseOutExpo`（cubic-bezier(.16,1,.3,1)）管进出场（340–460ms）；底栏指示器 animateDpAsState 弹簧滑移，进场 scaleIn 0.985→1。
- **Material 容器的阴影陷阱（v0.42.1 实测）**：去阴影只改 `neuSurface` 不够，三类 Material 组件要**逐个显式归零**，且参数名各不相同 ——
  - `ModalBottomSheet`：只有 `tonalElevation`，**没有 `shadowElevation`**（写了直接编译不过）。
  - `AlertDialog`：M3 Android 重载**同样没有 `shadowElevation`**，阴影来自 `tonalElevation`（用 `javap` 反编译 material3 aar 确认过）。
  - `DropdownMenu`：有 `shadowElevation`（另有 `containerColor` / `shape` 可一并指定）。

**交互语言（可见入口规则，勿破坏）：**

- 顶栏可见图标按钮 = 常规功能（任务页 👁 隐藏已完成｜▶ Agent｜＋ 新建，三按钮需传 actionsWidth=150dp）
- 会话/洞察/周报历史的**长按菜单锚定在条目右下角**（DropdownMenu 的 Popup 锚定其直接父节点——须把菜单包进  
  右下角的小锚点 Box，align/offset 直接加在 DropdownMenu 上无效）
- **破坏性操作的长按一律只弹菜单，点菜单里的「删除」才真删**（2026-10-02 用户明确要求）：  
  不要在 `onLongClick` 里直接执行删除，也不要再为它套一层 AlertDialog——菜单那一下就是确认。  
  长按时补一次 `HapticFeedbackType.LongPress` 震动反馈。
- ~~长按头像 = 对话风格面板~~（对话风格 v0.32.0 整体取消：tone UI/切换/注入全删，personas.tone 列保留不用）
  - v0.35.0 起 **长按头像 = 切换当前生效的 AI 接口**（多接口配置，`ChatComponents.ApiSwitchSheet`）
- **搜索框只出现在会话列表顶部**（v0.35.0）：`atTop` 时顶栏下方显示凹陷搜索行；  
  一旦上滑离开顶部就收起、换成顶栏右侧的放大镜图标（**图标不带圆圈**，用户明确要求）
- 人设管理 / 基础档案 / AI 提示词 / 设置（含外观）入口**只在「我的」页**；
  （v0.45.0：AI 提示词从设置面板提出来、单独成一级行卡；设置面板里不再放人设管理，避免与「我的」页重复）  
  会话列表顶栏只留标题与搜索图标，不放任何其它图标按钮
  - **人设管理每张卡：点卡片本体 = 编辑，点右端那颗 `ChatBubble` = 进 TA 的会话**（v0.60.1）。
    那颗图标**不是装饰**（原来是 18dp 的 `FaintText` 箭头）：40dp 命中区 + 21dp 图标 + `TextSecondary`。
    它是被「删除会话」`setHidden` 隐藏的会话**唯一的找回路** —— 列表按 `hidden && 无消息` 过滤，
    而 `ChatViewModel.init` 一打开就 `setHidden(false)`，所以从这儿进去一次就回来了。别改回装饰箭头。
- **主题切换只在「设置 → 外观」子弹层里做**（v0.35.1），别再把三选一摊回设置面板底部
- 人设编辑器**点头像直接换图**（v0.35.1），渐变环恒定保留；不要再加「自定义」按钮或 emoji 输入框
  - 头像正下方**恒定有一颗细线胶囊**（v0.60.1 用户点名）：默认头像时写「点击编辑」（点它 = 选图），
    换成自定义图后写「恢复默认」（点它 = 清掉图）。**宽度钉死和头像框同为 56dp**（头像在整排里是
    `CenterStart`，同宽才自动对齐）、10sp / 22dp 高、`neuSurface(999.dp)` 发丝描边、**离头像 4dp**。
    原来是那颗 40dp 高的裸文字钮（`NeuTextButton`），又远又不像能点 —— 别再改回去。
- **界面不写说明性小字**（v0.42.1，用户明确要求）：空状态的客套话、「点格子看当天明细」这类操作提示、  
  「配置 AI 接口后即可生成」这类前置说明一律不留；**数据标签保留**（现金余额、收入/支出/结余、日期）。
- **数据页不挂手动生成/提炼动作**（v0.42.2）：区块头右侧不留任何动作文字，  
  今日建议的生成走点击弹窗，洞察/周报由水位线与定时任务自动产出。
- **API 配置是「卡片 + 点箭头看详情」，不是折叠段（v0.53.0 用户两次纠正，别再改回去）**：
  对话接口 / 生图接口 / 语音合成三家一律——每条配置是一张 `NeuSheetRow` 卡片，
  **点卡片本体 = 切换当前生效**，**点卡片右侧那颗箭头 = 用 `SettingsCardDialog` 从屏幕中间弹出这条的详细信息**
  （改字段、测试连接、保存、删除都在弹窗里）。卡片下面**不放**「编辑」「删除」文字按钮（容易误触）。
  语音合成页只列**已配好的模型卡片**（标题=模型名），底部一个「＋ 添加接入」→ 选服务商 → 同一个弹窗填。
  ⚠️ v0.52.0 曾把"对话接口/生图接口"两段做成折叠手风琴，用户明确说"不是这个样子的展开折叠"——
  手风琴 `SettingsSectionRow` 现在只留给「AI 提示词」那种名单页。
  箭头要**够大够明显**（40dp 命中区 + 21dp 图标 + `TextSecondary` 色），`NeuSheetRow(onChevronClick=…)` 已内置。
- **输入区按钮一律走缩放反馈、不用 Material ripple**（v0.45.0）：输入栏图标键用
  `ChatScreen.NeuFlatIconButton`、面板格子用 `tapNoRipple` / `rememberNeuPressState`。
  默认 ripple 是无界水波、淡出 300ms+，用户反馈"灰色阴影太久太大"；全应用语言是"无涟漪 + 轻微缩放"。
- **收藏表情气泡与图片气泡必须区分**（v0.45.0）：`MediaKind.STICKER`（发送时写入；
  老数据按落盘目录 `filesDir/stickers/` 识别回来）→ `StickerBubble` 固定 120dp、不可点开；
  普通图仍是 `ImageBubble`（可点开全屏）。两者共用 `imagePath` 通道，模型侧 vision 语义不变。
- **拍摄页是叠在聊天页上的覆盖层、不在导航栈里**（v0.45.0）：必须 `BackHandler { onCancel() }`
  接住侧滑返回，否则一路 pop 到桌面；进入相机走 `AnimatedVisibility` 淡入（+ 轻缩放）过渡；
  **翻转动效只加在翻转按钮上（绕 Y 轴转半圈），预览画面不做动画**（用户明确纠正过）。


**布局自适应（真机关键，勿破坏）：**
- 系统栏 insets **只由 `ui/common/AdaptiveScreen.kt` 统一处理**（`AdaptiveScreen` / `AdaptiveSplitScreen` 都负责 `statusBarsPadding` + `navigationBarsPadding`）。
  页面内部**不要**再写 `statusBarsPadding()`；`Scaffold` 用 `contentWindowInsets = WindowInsets(0,0,0,0)`。
  这是"真机比设计稿挤/矮"的根因，已修一次，别再改回去。
- **底栏只在 home（四 tab Pager）显示**：单聊页/人设管理页是 push 层级，
  隐藏底栏让输入栏直达屏幕底部（否则输入栏与底栏叠在一起，v0.3.0 修的错乱）。
  隐藏底栏页面的手势条避让由 AdaptiveScreen 的 navigationBarsPadding 补足。
- 底部导航**不要**用 Material `NavigationBar`，用 `MainActivity` 里的 `HomeBottomBar`
  （白色浮起药丸 + 黑色弹簧滑移选中胶囊 + 22dp 图标 + 9.5sp 标签，选中项图标翻白）。
- 顶级四 tab = `HorizontalPager`（消息/数据/任务/我的，可横滑，beyondViewportPageCount 保 4 页存活不丢滚动位置），
  底栏胶囊与 Pager 双向联动；单聊/人设管理不参与滑动（不在 Pager 里）。
- 宽度判源唯一：`calculateWindowSizeClass` → `LocalWindowWidthClass`（`ui/common/AdaptiveScreen.kt`），
  != Compact 即 >=600dp；不要另立 600dp 判断。
- 版心规则：手机（< 600dp）铺满；平板（>= 600dp）消息页双栏（左列表 360dp + 右单聊区，
  `AdaptiveSplitScreen`，点行不 push）；数据/任务/我的内容区最大宽 **840dp**（`WideMaxContentWidth`）
  并切两列网格（数据=档案|打卡并排+洞察两列；任务=LazyVerticalGrid 分组头跨列；我的=行卡两列），
  两侧浅灰边槽。
- 气泡等比例元素用**屏幕比例**（如气泡最大宽 = 版心 × 76%），不要写死 dp。

**系统栏与深色模式（真机关键，勿破坏）：**
- 浅色锁定 ≠ 高枕无忧：**必须退出系统 Force Dark**，`res/values-v29/themes.xml` 里
  `android:forceDarkAllowed=false` + `android:enforceNavigationBarContrast=false`。
  不关的话，系统深色模式下 Android 10+ 会对整棵 View 树强制反色，黑白的细线与纸叠阴影
  全被搅成糊色块（真机「全是乱码/UI 错乱」的根因，v0.2.1 修复）。
- 系统栏图标用 `SystemBarStyle.light(...)` **锁定深色图标**，不要用 `auto`——auto 跟随系统
  深色模式会把图标翻成白色，浅色底上直接看不见。
- 冷启动底色：`values/colors.xml` 的 `neu_background`(#FFFFFF) 挂在主题
  `android:windowBackground` 上，消除启动闪烁；改 NeuBackground 时两处要同步。
- 启动图标 = 自适应图标（`mipmap-anydpi-v26`，前景 `drawable/ic_launcher_foreground.xml`
  = 黑色气泡+三点），minSdk 26 全覆盖，无需 legacy PNG；monochrome 在 API<33 自动忽略。

**页面结构（标准 IM 习惯）：**
- 聊天 tab 主页 = **会话列表**（搜索框**只在列表顶部**出现、上滑离开后换成顶栏放大镜；会话行=头像+名字+预览+时间+未读点，无身份标签）
- 手机点会话 → push 滑入**单聊页**（左上 ← 返回退出）；平板双栏模式右栏内嵌单聊（无返回键，点行切换）；**每个 persona 的会话消息完全独立**
- 单聊输入栏三颗按钮：清单=快捷打卡、图片=**发图给 AI 看**（vision，v0.38.0；以当前输入为字幕立即发出）、纸飞机=发送；顶栏放大镜无圆圈；**长按顶栏头像 = 切换 AI 接口**
  （~~笑脸=表情包面板~~ 已于 v0.35.1 取消：emoji 需求作废，`EmojiPack` 整个删除）
- 底部 tab：消息 / 数据 / 任务 / 我的，Pager 横滑 + 底栏胶囊弹簧联动
- **键盘弹起时列表要跟着滚（v0.45.1）**：聊天页是 `Column`=[顶栏]+[列表 `weight(1f)`]+[输入栏]，
  键盘一弹输入栏被顶高、列表视口变矮，而 `LazyColumn` **不会自动保持贴底** → 最新一条被挤出屏幕外。
  `ChatScreen` 里监听视口高度，**只在视口收缩且收缩前本来贴底**时按收缩量 `scrollBy` 跟随到底
  （翻历史不打断，微信式）。判断用**收缩前一帧**的贴底状态，键盘无动画瞬时弹起也准。改这块先问用户。
- 数据页顺序（v0.35.0）：**本周卡（打卡热力 + 完成度合并，置顶）** → AI 洞察（标题右侧箭头可折叠）→ AI 周报（**卡片内折叠**，收起时留 2 行预览 + 「展开全文」，板块标题不再带箭头）；**档案不在数据页**，只在「我的」页

## 五、数据模型核心约定

1. **人设（Persona）不写死**：用户可增、删、改。**v0.31.0 起取消 3 个示例导师种子——
   新用户的人设/档案/消息全部为空**，仅预置一个内置「应用助手」🤖（应用使用指南会话，
   `PersonaRepository.seedIfEmpty()` 空表时播种；提示词可在「我的 · AI 提示词」里改，删除后从设置保存会重建）。
   `conversationId = personaId`，一设一会话，消息互不混淆。人设可物理删除
   （级联清理消息/未读键/水位线；删光后会话列表显示虚线框「＋ 新建人设」）。
2. **对话风格（ToneStyle）已于 v0.32.0 整体取消**（用户要求删掉温和陪伴/严格教练/苏格拉底反问）：
   UI（长按头像面板、编辑器 chips）、ChatViewModel tone 状态与 prompt 注入全删；
   `personas.tone` 列与 `ToneStyle` 枚举保留（数据库兼容 + 导入默认值），运行时不再读取。
3. system prompt 组装顺序（接 LLM 时）：`persona.systemPrompt` + `tone.promptSnippet` +
   档案注入说明（`AiPrompts.DEFAULT_PROFILE_PREAMBLE`，可改）+ 用户档案（ProfileFact）；
   人设无设定词时用基础提示词（`AiPrompts.DEFAULT_BASE_PROMPT`，可改）。
   **三段预设提示词都可在「我的 · AI 提示词」修改**（v0.31.0：`ui/settings/PromptSettingsSheet.kt`；
   v0.45.0 起入口从设置面板上提到「我的」页一级行卡；
   档案注入说明的默认措辞 = 仅供背景参考、禁止 AI 主动复述档案——用户反馈 AI 满嘴档案而加）。
4. API 密钥走 Android Keystore / EncryptedSharedPreferences，**不允许明文落盘**。
   Keystore 不可用时才回退落明文（`keyPlain` 字段，JVM 单测即走这条路径，绝不让密钥凭空消失）。
4.1 **多接口配置（v0.35.0）**：`api_profiles` 存 JSON 数组（`ApiProfileCodec` 编解码，纯函数），
   `api_active_profile` 记生效项。老版本单接口配置**不在库里搬**，而是读时"虚拟"当成
   「默认接口」读出（`apiProfiles` flow），升级零操作；`saveApiConfig` 同时镜像写回老键名兜底。
   删掉正在生效的接口自动回落到剩下第一条；`activeProfileId` 也会校验指向是否还存在。
4.2 **生图接口（v0.35.0）**：独立于对话接口的 `img_*` 键。协议约定是模型在回复里输出
   单行 `[生图:英文提示词]`（`ImageGenerationClient.MARK_START/END`），App 解析后调
   `POST {baseUrl}/images/generations`，图片落 `filesDir/images/`，`messages.imagePath` 存绝对路径。
   **为什么用标记而不是 function calling**：DeepSeek/智谱/通义等 OpenAI 兼容端点对 `tools`
   支持并不一致，标记协议最稳。`stripMarker` 保证标记不进入用户可见文字。
4.3 ~~表情包~~ **已于 v0.35.1 取消**（用户明确作废该需求）：`data/EmojiPack.kt`、
   `EmojiPanel`、`MIcons.Smile` 与相关测试全部删除。emoji 仍可当普通文字发出去。
5. **任务双板块（v0.12.0）**：`TaskItem.repeatDaily` = 每日任务（无日期、跨天经
   `completionDate` 自动回未完成、提醒走每日重复闹钟）；基础任务一次性带日期。
   勾选完成写 `task_completions`（taskId+day 主键）——打卡热力只统计每日任务、
   周目标完成度只统计基础任务、快捷打卡 7 天圆点同源。
6. **AI 任务建议（v0.12.0）**：提炼 LLM 同产出 `taskSuggestions`（宽容解析），
   经 `TaskSuggestionHub`（进程内存）分发到聊天页卡片，用户确认才写 tasks（createdBy=ai）；
   同 title 当日不重复建议、同名任务已存在则过滤。
7. **AI 周报（v0.21.0）**：`ai_weekly_reports`（Room v8 + MIGRATION_7_8，weekStart 唯一索引）
   = 每周日 21:30 `WeeklyReportReceiver` 经 `WeeklyReportGenerator` 生成（200–400 字中文，
   中性客观无人设腔，同 ProfileExtractor 的独立性原则）；同周重复生成 = 覆盖（REPLACE）。
   补生成三处：App 启动（MainActivity）/ BootReceiver / 到点 Receiver，均查上周缺报静默补。
   API 未配置不生成不报错；数据页板块无任何周报时整个隐藏。
   周报统计口径同 §五.5：完成度只算基础任务、打卡只算每日任务。

## 六、工作流约定（重要）

1. **UI 直接改 Compose**：不做预览确认环节（预览约束已废弃，2026-09-28 用户拍板）。
2. **改动 Compose 后自查**：Kotlin 文件注意未使用的 import 要清理；ViewModel 用 `viewModelFactory`
   + `APPLICATION_KEY` 模式获取仓库（暂无 Hilt）。
3. **种子数据**集中在：`PersonaRepository.seedIfEmpty()`（人设）与 `ChatViewModel.seedMessages()`（按 personaId 分会话）。
4. 项目记忆在 `.workbuddy/memory/`（日志按天追加，长期约定在 MEMORY.md），跨会话先读它。


## 七、路线图

**已拆到 [`docs/ROADMAP.md`](docs/ROADMAP.md)**（按优先级排列的功能台账，做完一项勾一项）。
改功能状态时去那边勾；这里只留一句指路，避免 AGENTS.md 越滚越长、每次都整篇进上下文。


## 八、本地构建（已验证可用）

本机已具备完整工具链，**不要下载 Android Studio，也不要用 `./gradlew`**（项目无 wrapper，gradlew 会联网拉 190MB 发行包）。

⚠️ **编译 / 测试 / 打包一律由用户发话才做（2026-10-05 用户拍板，最高优先级）**：
AI 改完代码**只交付源码**——**不主动跑单元测试、不主动跑截图测试、不主动编译、不主动出包**；
只有用户明确说"编译 / 测试 / 打包 / 出包"时才执行对应动作。
（用户原话："不用 AI 去跑测试了；打包让用户决定什么时候打包。"）
下面这些命令与流程**保留为"用户叫了就照它做"的操作手册**，不是"每轮必做"的清单。

| 组件 | 路径 |
|---|---|
| JDK 17 | `C:/Users/16621/.workbuddy//toolchains//jdk17` |
| Android SDK | `C:/Users/16621/.workbuddy//toolchains//android-sdk`（platform-34/35，build-tools 34.0.0/35.0.0，cmdline-tools/latest） |
| Gradle 8.14.3 | `C:/Users/16621/.gradle/wrapper/dists/gradle-8.14.3-all/<hash>/gradle-8.14.3/bin/gradle.bat` |

```bash
cd "C:/Users/16621/Desktop/梦欢"
export JAVA_HOME="C:/Users/16621/.workbuddy/toolchains/jdk17"   # 正斜杠的 Windows 路径实测可用；MSYS 的 /c/... 形式会让 gradle.bat 报 invalid directory
export ANDROID_HOME="C:/Users/16621/.workbuddy/toolchains/android-sdk"
"<gradle.bat 路径>" assembleDebug --no-daemon --console=plain > logs/build.log 2>&1
```

**构建日志约定（2026-10-02 用户拍板）**：所有 gradle / 测试输出**重定向到 `logs/` 文件夹**（如 `logs/build.log`、`logs/test.log`），每次覆盖同名文件。**禁止**把 log 写到项目根、也**禁止**用 `host_safe_delete` 删 log（删一次弹一次权限，用户明确反感）。`logs/` 已被 `.gitignore` 的 `*.log` 覆盖，不入库。需要保留某次日志就手动改名存档。

已有配置（勿删）：`local.properties`（sdk.dir）、`gradle.properties` 的 `android.overridePathCheck=true`（项目路径含中文与「·」）、`settings.gradle.kts` 里的阿里云镜像（必须排在 google() 之前）。

四个已知坑与解法见技能 `capacitor-android-offline-build`：AGP 拉不到（镜像）、中文路径（overridePathCheck）、缺 cmdline-tools 导致静默挂起（看日志是否刷 "waiting for package manifests"）、JAVA_HOME 路径格式。

产物校验：
```bash
SDK="C:/Users/16621/.workbuddy/toolchains/android-sdk"
"$SDK/build-tools/35.0.0/aapt2.exe" dump badging app/build/outputs/apk/debug/app-debug.apk | grep -E "^package|application-label|sdkVersion"
"$SDK/build-tools/35.0.0/apksigner.bat" verify --print-certs app/build/outputs/apk/debug/app-debug.apk
```

**分发装真机用 release 包**（2026-09-27 已验证）：`assembleRelease` 已开 R8 混淆压缩 + 资源收缩 + debug 签名（与 debug 包同证书，可直接覆盖升级），体积 ≈1.5MB（debug 18MB）。规则在 `app/proguard-rules.pro`——Compose/Room/OkHttp/coroutines/DataStore 全自带 consumer rules，**不要手写多余 keep**；本 App 无反射取类，新增依赖若带反射序列化（Gson/Retrofit 等）需补规则。dex 抽查法：解出 classes.dex 后 `grep -aoE "Lcom/menghuan/app/[A-Za-z0-9/$]+;"`，Manifest 组件应原名保留。

**产物 APK 命名硬性规定（2026-10-02 用户拍板更新，勿违反）**：产物复制到项目根目录，命名 `梦欢-v{versionName}-release.apk`。
- **根目录只保留当前版**：每次出新包，把根目录里的老 APK **移动到 `archive/`**（不删！），再把新包放到根目录。
- **`archive/` 目录**：所有历史 APK 都放这里，`.gitignore` 已经排除 `*.apk` 所以不会入库；
  需要回退到老版本时直接从 `archive/` 取，不用去回收站翻。
- 判定以 versionName 为准，patch/minor/major 一视同仁——不再区分"覆盖 vs 新建"，都走归档流。
- **禁止**：不要再用 `host_safe_delete` 把老 APK 送回收站。用户明确反馈：能归档就别删。
- ⚠️ **出包后立刻归档，别等下一次**（2026-10-04 用户再次点名）：v0.44.0 那次就是忘了移，
  根目录堆了三个包。流程 = 出包 → 复制新包到根目录 → **同一步把根目录的老包 `mv` 进 `archive/`**。
- ⚠️ **v0.44.1 起 APK 是 83MB**（内置 sherpa-onnx so + 中文模型），`archive/` 会按 83MB/版涨。
  磁盘吃紧时**先问用户**再决定裁剪哪些历史包，不要自己删。

**版本节奏规定（2026-10-01 用户拍板，最高优先级，勿违反）**：**用户说一次需求 = 一次版本更新。**
- 收到一批需求（哪怕含十几条子项、横跨 UI/数据/AI 多层）就在**同一个 versionName** 内一次做完，
  **严禁按子功能拆成 v0.35.0 / v0.36.0 / v0.37.0 一串版本**（那是 2026-09-29~30 期间 AI 自主迭代的毛病，用户已明确纠正）。
- 一批需求 = 一次 minor+1（功能项占多数）或 patch+1（纯修复），中间**不 bump version、不出包**。
- **改完 = 交付，就地停手**：代码改完**不主动跑测试、不主动编译、不主动出包**；
  **打包时机完全由用户决定**（他说打包才打包）。
- 版本号只在用户新需求到来时才 +1；不要为了"显得有进展"主动升版。

**JVM 截图测试（无真机时"亲眼"验证 UI，2026-09-28 新增；⚠️ 2026-10-05 起 AI **不主动跑**，仅用户点名时执行）**：`app/src/test/java/com/menghuan/app/UiRenderTest.kt` 启动真实 MainActivity、按用户路径点击导航各页，Roborazzi 用真机同款 Skia 管线出 PNG。用法（用户要求时）：

```bash
# 中文路径坑之五：Gradle 测试 worker 经 java @argfile 传 classpath，JDK17 启动器按系统 GBK
# 解码 UTF-8 字节，中文路径条目失效（ClassNotFoundException）。必须先映射 ASCII 盘符：
subst X: "C:\Users\16621\Desktop\梦欢"
cd X:/
gradle recordRoborazziDebug   # 产物 app/build/screenshots/*.png；镜像已在 gradle 配好（android-all ~150MB 首跑下载）
```
