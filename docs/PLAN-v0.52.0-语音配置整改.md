# PLAN v0.52.0 — 语音配置整改（用户 2026-10-05 一批六条）

> 施工依据。协议出处：`https://api-docs.siliconflow.cn/docs/api/audio-speech-post`（用户提供）
> + `docs/RESEARCH-v0.50.0-TTS协议查证.md`。**改语音栈前先读本文件与 AGENTS.md 零.4。**

## 用户原话逐条对应

| # | 反馈 | 根因 | 本批做法 |
|---|---|---|---|
| 1 | 切硅基/小米/自定义时下面 Key·地址·模型都是共用的，"切换应该像切 3 个不同界面" | 只有一套 `tts_*` 键，provider 只是一个字段 | **每家一套独立配置**（按 provider 分桶），切到哪就读那家自己的内容 |
| 2 | 选 MP3 克隆提示"文件格式不对" | `Mp3Decoder` 用 `MediaCodec`，输入 `MediaFormat` 硬写 **24000/1 声道**，真实流是 44.1kHz → 0 帧 → 上层判"只支持 mp3/wav" | 换 **MediaExtractor + MediaCodec** 统一解码器（mp3/wav/m4a/aac/**mp4** 都能抽音轨）→ 裁 15 s → 24kHz 单声道 WAV 上传 |
| 3 | "刷新我的克隆音色"什么意思？要一个**音色板块**：只放我克隆的 + 两个预设（一男一女），板块右上角就是克隆按钮，支持 mp3/wav/**mp4**；手填音色 ID 也放这里；人设页只列板块里有的，**不要试听和添加** | 预设 8/9 个太多、克隆列表是手动按钮、试听和添加散落在人设编辑器 | 见 §D |
| 4 | 通话显示"声音合成失败：音频解码失败"（试听是有声的） | 同 #2 的解码器；试听走 `MediaPlayer`（系统自己协商格式）所以正常 | **通话链路改要 WAV**（`response_format=wav` + `sample_rate=24000` = 正好等于 `OUT_RATE`，零解码零重采样）；万一网关仍回 mp3，§A 的新解码器兜底 |
| 5 | AI 语音条播完了还在动 | `VoiceBubble` 的 `isPlaying` 只靠 `MediaPlayer.onCompletion`；MP3 尾部 padding / 无 Xing 头时该回调不来 | **看门狗**：播放中按 position/duration 轮询 + 绝对超时兜底，保证一定复位 |
| 6 | 所有 API 配置（文本/图像/语音）都要展开收起，防误触 | 现在整页表单直接摊开 | 手风琴化：标题行默认收起，展开才有字段与保存 |

## A. 统一音频解码（修 #2 / #4 根因）

- 新增 `realtime/MediaAudioDecoder.kt`：`decodeFile(file): Pcm?` = MediaExtractor 选第一条音轨
  → MediaCodec 同步解码 → 下混单声道 → (PCM16, sampleRate)。
  覆盖 mp3 / wav / m4a / aac / **mp4** / flac / ogg（平台解码器有什么就认什么），WAV 先走已有 `WavPcm` 直解。
- **删除 `realtime/Mp3Decoder.kt`**（被取代；它的输入格式 hint 是本次两个 bug 的根因）。
- `VoiceCloner.prepareSample`：解码统一走 §A；失败原因要说清"这个文件里没有可解码的音轨"，不要再谎报"只支持 mp3/wav"。
- `TtsAudioDecoder`：MP3 分支改成"落临时文件 → §A 解码"。

## B. 通话要 WAV，语音条留 MP3

- `TtsClient.synthesize(text, voice, target)`，`target = CALL | NOTE`：
  - CALL：硅基/自定义 `response_format="wav"` + `sample_rate=CallAudioManager.OUT_RATE`；MiMo 本来就是 wav，不动。
  - NOTE / 试听：`mp3`（体积小、`MediaPlayer` 直接播）。
- 目的：通话链路彻底不依赖 MP3 解码；#4 从"能不能解"变成"不需要解"。

## C. 每家独立配置（#1）

- DataStore 分桶键：`tts_<PROVIDER>_api_key_enc` / `..._key_plain` / `_base_url` / `_model` / `_voice` / `_manual_voice_id`；
  `tts_provider` 只记"当前是哪一家"。
- **一次性迁移**：老单桶 `tts_*` 有值且该家桶空 → 当成那家的数据搬进去再删老键（用户视角零操作）。
- Key 仍走 `ApiKeyCipher`，Keystore 不可用才回退明文（AGENTS §五.4 不破）。
- `ttsConfig` flow 仍给"当前家"；新增 `ttsConfigOf(provider)` 给 UI 切 tab 用。

## D. 音色板块（#3）

- **预设砍到每家两个，一男一女**（用户点名）：硅基 `alex`(男) + `anna`(女)；小米 `白桦`(男) + `冰糖`(女)；自定义不给预设，只有手填 ID。
- 板块位置：**设置 → 语音** 页内新增「音色」段，标题右侧一颗「克隆音色」按钮（仅硅基；别家给一句为什么）。
- 板块内容 = 该家的 2 个预置 + 我的克隆音色（**进页面自动拉取**，不再放"刷新我的克隆音色"这种看不懂的黑话）+ 手填音色 ID 一行；
  每行右侧「试听」与「选为默认」。
- 克隆弹层：选文件（mp3/wav/m4a/**mp4**）→ 端侧识别自动出逐字稿 → 命名 → 上传。
- **人设编辑页只列板块里有的音色，删掉试听与手填**（用户点名）。

## E. 手风琴化（#6）

- `ApiConfigScreen`（对话多接口 + 生图接口）、`TtsConfigScreen`（三家各一段）、`AsrModelScreen`
  全部改成「标题行 + 展开收起」，默认收起；展开才出现输入框与保存，防误触。
- 沿用 `PromptSettingsScreen` 已有的手风琴语义（一段展开、改过的标「已自定义」）。

## F. 语音条复位（#5）

- `VoicePlayer` 暴露 `positionMs()` / `durationMs()`；`ChatScreen` 播放期间 `LaunchedEffect(playingVoiceId)`
  每 120ms 轮询：`!isPlaying` 或 position ≥ duration（含 300ms 容差）→ stop + 清空；
  再加一个 `duration + 3000ms` 的绝对超时兜底，保证 UI 不可能卡在"播放中"。

## G. 手册同步（AGENTS 零.5 硬要求）

`AiPrompts.DEFAULT_ASSISTANT_PROMPT` 的语音章必须跟着改：每家独立配置、音色板块路径与内容、
支持的样本格式（含 mp4）、通话用 WAV、"刷新我的克隆音色"这种措辞不再出现。
**每加一条 `replace` 必须在 `AiPromptsTest` 配一对断言**（新的在 + 旧的不在）。

## H. 验收

- 单测：分桶与迁移（`TtsConfigTest` 扩展）、`TtsAudioFormat`/`synthesize(target)` 请求体形状（`TtsClientTest`）、
  解码器纯函数部分（裁剪/下混已有）。
- 截图：`UiRenderTest` 覆盖语音页的音色板块、语音合成三家的手风琴、API 配置手风琴。
- 出包：versionCode 101 / versionName 0.52.0，一个包（本批 = 一次版本，AGENTS §六）。
- 真机待验（用户手上没 Key 的部分照旧标注）：克隆上传 mp4、通话有声、语音条复位。
