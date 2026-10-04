# 梦欢 语音栈执行方案（v0.50.0 · 施工版）

> 本文是**照着施工**用的执行方案：每一步都能独立落地、独立验收。
> 决策背景与推演过程见同目录 [`PLAN-v0.50.0-语音方案-讨论稿.md`](PLAN-v0.50.0-语音方案-讨论稿.md)（历史记录，不再更新）。
>
> **版本节奏**：本批需求 = 一次 **v0.50.0**（功能项占多数，minor+1）。全部步骤做完、单测与截图自检通过后**只出一个包**，中途不 bump、不出包。

---

## 0. 目标与边界

**一句话目标**：把语音栈从「三套识别 + 两套合成」收敛成 **一条识别链 + 一条合成链 + 一个标记协议 + 三个开关**；识别模型**零内置、装后下载**。

**交付物**
1. 识别：端侧 sherpa-onnx **离线非流式 SenseVoice**，语音消息与通话共用同一份模型；模型不随包，装后引导下载。
2. 合成：统一 `TtsClient`，支持 **硅基流动 CosyVoice2 / 小米 MiMo / 自定义 OpenAI 兼容端点**，用户自选；支持**音色克隆**（每个人设一个专属声音）。
3. AI 语音条：AI 用 `【语音条】` 标记自主决定发语音，落成可播放气泡、长按可转文字。
4. 删除：Edge TTS、聊天「朗读」开关（右上角喇叭）、三级级联的两级兜底（云端 Whisper / 系统识别器）。

**本期不做（范围外）**
- 不做「模型可选」——只保留 SenseVoice 一个识别模型。
- 不做真双工通话——沿用现有对讲机式（VAD 分段 → 识别 → 回复 → 合成）。
- 不做「simulated streaming」（非流式模型模拟流式）——列为后续可选优化。
- 不做朗读功能——已明确删除，不再以任何形式回归。

---

## 1. 施工总览

| 步 | 内容 | 主要产出文件 | 依赖 | 验收 |
|---|---|---|---|---|
| 1 | 识别引擎换离线 SenseVoice，砍级联 | `SherpaAsr.kt`、`CallAsr.kt`、`SpeechToText.kt`、`assets/asr/` | — | 单测 + 真机识别准确率对比 |
| 2 | 模型下载器 + 缺失引导 | 新增 `AsrModelManager.kt`、`ui/settings/AsrModelSheet.kt` | 步 1 | 真机：下载/断点续传/手动导入/校验 |
| 3 | 统一 TTS 抽象（三家）+ 音色克隆 | 新增 `TtsClient.kt`、`TtsConfig.kt`、`VoiceCloner.kt`、`ui/settings/TtsConfigSheet.kt` | — | 真机：三家各合成一句出声 |
| 4 | AI 语音条标记 + 删朗读 | `ChatViewModel.kt`、`ChatComponents.kt`、`AiPrompts.kt`、`ChatScreen.kt`、`SettingsRepository.kt` | 步 3 | 单测 + 截图 + 真机播放 |
| 5 | 通话并入统一 TTS + 离线 ASR | `PipelineVoiceCallEngine.kt`、`RealtimeCallConfig`、`RealtimeCallViewModel/Screen.kt` | 步 1、3 | 真机整通电话 |
| 6 | 体积与构建清理 | `app/build.gradle.kts`、`assets/asr/` | 步 1 | `assembleRelease` 体积对比 |
| 7 | 测试、截图自检、出包 | `app/src/test/**`、根目录 APK | 全部 | 单测全绿 + 真机验收 |

---

## 2. 分步明细

### 步骤 1：识别换离线 SenseVoice + 砍级联

**引擎形态**：sherpa-onnx **`OfflineRecognizer`**（离线非流式），SenseVoice 模型，一次吃完整段音频再出文字。

**模型文件**（不内置，放 `filesDir/asr_sensevoice/`）
- `model.int8.onnx`（SenseVoice，中/英/日/韩/粤，实施前实测体积，约 200MB+ 量级）
- `tokens.txt`
- ⚠️ **不下载 `.tar.bz2` 整包**：Android 无内置 bz2/tar 解压。改为**直连两个原始文件**（HF 镜像 / GitHub Releases 的 raw 文件），下载器只管这两个文件。

**改动点**

| 文件 | 现状 | 改成 |
|---|---|---|
| `data/SherpaAsr.kt` | `object`，双检锁懒加载 `OnlineRecognizer`（流式），assets `asr/` 四件套 | 改 `OfflineRecognizer` + `OfflineSenseVoiceModelConfig`；模型从 `filesDir/asr_sensevoice/` 读；`modelPresent()` 改查该目录两文件是否齐全 |
| ↑ 公开 API | `modelPresent(context): Boolean`、`suspend transcribeWav(context, wavPath): String?` | **签名不变**（调用方零改动）；内部流程：`WavPcm.readMonoAt` → `createStream` → `acceptWaveform` → `decode` → `getResult().text` |
| `realtime/CallAsr.kt` | `class`，构造即 new `OnlineRecognizer`（流式）；`feed/partial/finalizeUtterance/release` | 改 `OfflineRecognizer`；API 收敛为 **`transcribe(samples: FloatArray): String`**（吃一整段） + `release()`；删 `feed/partial/finalizeUtterance` |
| `realtime/CallVad.kt` | `silero_vad.onnx`（`assets/asr/`，~1.8MB） | **不动**（体积小，继续内置） |
| `data/SpeechToText.kt` | 三级级联：端侧 → 云端 Whisper → 系统识别器 | **只留端侧**；`transcribe` 删 `cloudTranscribe`、`onPartial` 参数；删 `transcribeFile`（系统识别器） |
| `ui/chat/ChatScreen.kt` L899-904 | 传 `cloudTranscribe = { vm.cloudTranscribe(f) }` | 改为直接 `SpeechToText.transcribe(context, file.absolutePath)` |
| `ui/chat/ChatViewModel.kt` L506 | `cloudTranscribe`（走 `OpenAiClient.transcribe` 云端 Whisper） | 删除该方法；`OpenAiClient.transcribe` 实施时 grep 确认无其它调用后一并删 |
| `assets/asr/` | `encoder/decoder/joiner.int8.onnx` + `tokens.txt` + `silero_vad.onnx` | 删前四个（zipformer 四件套），**只留 `silero_vad.onnx`** |

**实施前置动作（防返工）**：用 `javap` 反编译 `app/libs/sherpa-onnx-1.13.8.aar` 里的 `classes.jar`，**核对 `OfflineRecognizer` / `OfflineModelConfig` / SenseVoice 配置类的真实字段名**（不同版本字段名会变），再动手写。这一步不做等于赌运气。

**情感检测说明**：SenseVoice 推理会**顺带**输出情绪标签（开心/难过/生气/中性）与音频事件（笑声/掌声等），**不额外耗时**。本期**只取 `text`**，情绪先不用；后续若要「让 AI 感知用户语气」，可直接从同一个 result 里取，无需改模型。

**验收**：`WavPcmTest`/`SpeechToTextTest` 适配后通过；真机录一段带口音的普通话，与旧 14M 流式模型对比准确率（主观即可，明显变准为过）。

---

### 步骤 2：模型下载器 + 缺失引导

**新增 `data/AsrModelManager.kt`**
- `fun isReady(context): Boolean`——`filesDir/asr_sensevoice/` 下两文件齐全且 sha256 匹配。
- `suspend fun download(context, source, onProgress: (Float) -> Unit): Result<Unit>`——OkHttp **Range 断点续传**，逐文件下载，边下边算 sha256。
- `fun delete(context)`——清模型（给「释放空间」用）。
- `suspend fun importFrom(context, uri): Result<Unit>`——SAF 手动导入（用户已自备模型时）。
- **三个来源**（按优先级）：① `hf-mirror.com` 镜像；② GitHub Releases 直链；③ 手动导入。
- 失败要能**看到原因**（网络/校验/空间），不静默。

**新增 `ui/settings/AsrModelSheet.kt`**：来源选择 → 进度条 → 暂停/继续/重试 → 校验结果 → 已就绪状态；入口挂在「设置」里，也可由缺模型提示直接拉起。

**触发点**：点语音输入键、或进通话页时若 `!isReady` → **弹下载入口**（含大小提示与流量提醒），不静默失败、不假死。

**验收**：真机走完 下载→暂停→继续→校验通过→识别可用；手动导入一条通路也要通。

---

### 步骤 3：统一 TTS 抽象（三家）+ 音色克隆

**新增 `data/TtsClient.kt`**
```kotlin
interface TtsClient {
    suspend fun synthesize(text: String, voice: VoiceRef): ByteArray?   // 返回音频字节（mp3/wav）
    val lastFailure: String?                                            // 失败原因回传 UI
}
```
- 三家实现，统一走 `POST {baseUrl}/audio/speech`（OpenAI 兼容体：`model` / `input` / `voice` / `response_format`）：
  - `SiliconFlowTts`（默认，模型 `CosyVoice2-0.5B`）
  - `XiaomiMimoTts`（响应体里直接带音频）
  - `CustomOpenAiTts`（用户填 baseUrl / model / key）
- **协议细节以各家官方文档为准**，实施时逐家核对请求体与响应字段，不凭记忆写。

**新增 `data/TtsConfig.kt`**（纯函数编解码，可 JVM 单测）：`TtsProvider` 枚举 + 配置读写。

**新增 `data/VoiceCloner.kt`**：上传音频样本 → **自动截取前 15 秒** → **≤10MB 校验** → 调 provider 克隆接口 → 得 `voiceId` 落库/落设置。

**播放**：单条语音用 `MediaPlayer`（语音条）；通话沿用现有 `Mp3Decoder` → `CallAudioManager.playPcm`（AudioTrack）。

**UI**
- 新增 `ui/settings/TtsConfigSheet.kt`：「设置 → 语音合成」——选 provider、填 key/baseUrl/model、**试听**一句。
- `ui/persona/PersonaEditorSheet.kt`：音色选择从「Edge 预设列表」改为「provider 音色列表 + 我的克隆音色 + 试听」。

**验收**：三家各合成一句，真机出声；克隆一条音色并能用它合成。

---

### 步骤 4：AI 语音条标记 + 删朗读

**新增 `data/VoiceNoteMarker.kt`**（纯函数，可 JVM 单测）：解析回复里的 `【语音条】` 标记 → `(cleanText, wantsVoice)`，参照 `data/ImageGeneration.kt` 的标记协议写法（`MARK_START/END` + `stripMarker`，保证标记不进用户可见文字）。

**`data/AiPrompts.kt`**：新增「语音条策略」提示词段（框架/硬规则部分硬编码，可调策略部分可编辑），并加入「我的 · AI 提示词」设置面板。沿用现有做法：**不改历史锚点常量，只在生效版上追加/替换**。

**`ui/chat/ChatViewModel.kt`**
- 回复落库后：若 `wantsVoice` → `TtsClient.synthesize` → 落 `filesDir/voice/` → 插一条 AI 消息（`audioPath` + `mediaKind = MediaKind.AUDIO` + `audioDurationMs` + `content = cleanText`）。
- **TTS 失败降级为纯文字**，并把失败原因提示一次（不静默）。
- **删除朗读**：`readAloudIfEnabled`、`readAloudJob`、`stopReadAloud`（L432-448）；`cleanForSpeech` **改名 `cleanForTts` 保留**（语音条与通话都要用它剥标记）。

**删除清单**
| 位置 | 内容 |
|---|---|
| `realtime/ReadAloudPlayer.kt` | 整个文件删除 |
| `ui/chat/ChatScreen.kt` | 右上角喇叭入口（L415-429）、`soundOn` 状态（L156、L167-170） |
| `data/SettingsRepository.kt` | `chat_read_aloud` 键（L74）、`chatReadAloud` Flow（L530-531）、`setChatReadAloud`（L533-535） |

**`ui/chat/ChatComponents.kt` · `VoiceBubble`（L477-534）改造**
- 加 `isUser: Boolean` 参数：**用户侧**保持现状（`NeuAccent` 黑底药丸 + `AccentInk`）；**AI 侧**改「面色 + 细线」（与 AI 文字气泡同一视觉体系），宽高/声波/时长逻辑复用。
- 长按菜单加**「转文字」**：AI 语音条默认只发语音条（不带文字），长按可展开 `content` 文字。
- 播放态由调用方传入（沿用 `isPlaying` / `onToggle`）。

**验收**：单测（标记解析）；截图（AI 侧语音条样式）；真机（AI 主动发语音条 → 播放 → 长按转文字）。

---

### 步骤 5：通话并入统一 TTS + 离线 ASR

**`realtime/PipelineVoiceCallEngine.kt`**
- `ttsPlayer(channel)`：`EdgeTtsClient(voice).synthesize(...)` → **`TtsClient.synthesize(...)`**；`Mp3Decoder.decode` → `audio.playPcm` **保留不动**。
- `audioLoop`：`CallVad.accept` 返回整段后，改调 **`CallAsr.transcribe(segment.samples)`**，字幕**按段出**（不再有 `partial` 中间态）。
- **barge-in 不变**（VAD 检测持续开口 ≥300ms → `interrupt()`：cancel LLM + close TTS Channel + `audio.stopPlayback()`）。
- `RealtimeCallConfig.voice: String` → **`VoiceRef`**（provider + voiceId）。
- **TTS 未配置时**：不出声，走字幕兜底 + 顶部提示「未配置语音合成」。

**`ui/realtime/RealtimeCallViewModel.kt`** L99-102：读新 TTS 配置与音色，替换 `DEFAULT_TTS_VOICE` 逻辑。
**`ui/realtime/RealtimeCallScreen.kt`**：加「识别模型未下载 / TTS 未配置」提示条，并给一键跳转。

**删除**：`realtime/EdgeTtsClient.kt`（整个文件）。`Mp3Decoder.kt` **保留**（云端 TTS 多为 mp3）。

**验收**：真机打一通完整电话——每段识别准确、TTS 出声、能打断、挂断后记录正常落库。

---

### 步骤 6：体积与构建清理

| 位置 | 改动 |
|---|---|
| `app/build.gradle.kts` L24-26 | `abiFilters += listOf("arm64-v8a")`——**去掉 `armeabi-v7a`** |
| `app/build.gradle.kts` L50-52 | `noCompress` **保留 `onnx`**（`silero_vad.onnx` 仍在 assets 需要它）；`txt` 可评估移除（tokens 不再内置） |
| `assets/asr/` | 只留 `silero_vad.onnx`（zipformer 四件套已删） |
| `proguard-rules.pro` | `-keep class com.k2fsa.sherpa.onnx.**` **保持不变**（JNI 字段不能被混淆） |

**验收**：`assembleRelease` 成功；对比出包体积（预期：内置模型移除 + 去 v7a 后显著下降）。

---

### 步骤 7：测试、截图自检、出包

**JVM 单测**
- 新增：`VoiceNoteMarkerTest`（标记解析边界）、`TtsConfigTest`（配置编解码）、`AsrModelManagerTest`（纯逻辑：校验/续传拼接）。
- 改造：`SpeechToTextTest`（级联删除后行为变化）、`AiPromptsTest`（提示词锚点与新段）、涉及朗读的 `ChatViewModelTest` 断言。

**截图自检**：`subst X: "C:\Users\16621\Desktop\梦欢"` → `cd X:/` → `recordRoborazziDebug`，看 AI 侧语音条、TTS 配置面板、模型下载面板三处渲染。

**真机验收**（CameraX 类功能截图测不了，必须真机）：识别准确率、通话整通、语音条播放与转文字、三家 TTS 出声、模型下载全流程。

**出包**：`assembleRelease` → 复制 `梦欢-v0.50.0-release.apk` 到根目录 → **同一步**把根目录旧包 `mv` 进 `archive/`。

---

## 3. 文件级改动清单

**保留不动**
`data/WavPcm.kt`、`realtime/CallVad.kt`、`realtime/CallAudioManager.kt`、`realtime/CallTones.kt`、`realtime/SentenceSplitter.kt`、`realtime/RealtimeCallState.kt`、`realtime/RealtimeCallRepository.kt`、`realtime/Mp3Decoder.kt`、`proguard-rules.pro`

**改造**
`data/SherpaAsr.kt`｜`realtime/CallAsr.kt`｜`data/SpeechToText.kt`｜`realtime/PipelineVoiceCallEngine.kt`｜`ui/chat/ChatViewModel.kt`｜`ui/chat/ChatScreen.kt`｜`ui/chat/ChatComponents.kt`｜`ui/persona/PersonaEditorSheet.kt`｜`ui/realtime/RealtimeCallViewModel.kt`｜`ui/realtime/RealtimeCallScreen.kt`｜`data/SettingsRepository.kt`｜`data/AiPrompts.kt`｜`app/build.gradle.kts`

**删除**
`realtime/EdgeTtsClient.kt`｜`realtime/ReadAloudPlayer.kt`｜`assets/asr/{encoder,decoder,joiner}-*.onnx` + `tokens.txt`

**新增**
`data/TtsClient.kt`｜`data/TtsConfig.kt`｜`data/VoiceCloner.kt`｜`data/VoiceNoteMarker.kt`｜`data/AsrModelManager.kt`｜`ui/settings/TtsConfigSheet.kt`｜`ui/settings/AsrModelSheet.kt`

---

## 4. 设置与数据变更

**Room**：**无新表**。AI 语音条复用 `messages` 现成字段（`audioPath` / `audioDurationMs` / `mediaKind` / `content`）。

**DataStore**
| 键 | 动作 |
|---|---|
| `chat_read_aloud` | **删** |
| `call_voice_<personaId>` | **改造**：存 `VoiceRef`（provider + voiceId），不再是 Edge 音色名 |
| `tts_provider` / `tts_base_url` / `tts_api_key` / `tts_model` | **新增**（`tts_api_key` 走 `ApiKeyCipher` 加密，**不允许明文落盘**） |
| `tts_voice_<personaId>` | **新增**（人设专属音色/克隆音色） |

**兼容**：老 `call_voice_<personaId>` 存的是 Edge 音色名 → 读时若未配 provider 则**忽略并回落默认**，不报错、不崩。

---

## 5. 风险与应对

| 风险 | 应对 |
|---|---|
| sherpa-onnx AAR 的 SenseVoice 配置字段名与预期不符 | 步 1 前置用 `javap` 核对；不符则按实际字段调整（**先核对再写**） |
| 模型下载源被墙 / 链接失效 | 三来源级联（镜像 → Releases → 手动导入）；校验失败可重下 |
| 云端 TTS 未配 / Key 失效 → 通话无声 | 字幕兜底 + 明确提示，不静默；不做"假装成功" |
| 离线模型单次推理延迟（长音频更明显） | 语音消息可接受；通话按 VAD 分段，**每段控制在短句**，避免长段等待 |
| 去 v7a 影响 32 位老机 | 已知取舍，用户已确认；保留 `archive/` 旧包可回退 |
| 语音条与通话抢 TTS 配额/并发 | 语音条合成与通话分属不同链路，各自独立；通话进行中不触发语音条 |

---

## 6. 施工纪律（沿用 AGENTS.md）

1. 每一步**先核对再写**，不试错式连改；拿不准就停下来问。
2. 改 Compose 后清理未用 import；新增依赖若带反射序列化需补 keep 规则。
3. 构建/测试日志一律重定向到 `logs/`（如 `logs/build.log`），**不写项目根、不删 log**。
4. 出包后**立刻归档**：新包进根目录、旧包进 `archive/`，同一步完成。