# 梦欢 · 项目长期记忆

> 仅保留仍有约束力的结论；版本细节见 `docs/CHANGELOG.md` 与 `.workbuddy/memory/2026-*.md`。

## 最高优先级
- 新功能/疑难修复先搜 GitHub 成熟案例（中英文关键词、`android ... sample`、`awesome-android ...`），再适配；没把握先说明，不做试错式连改。
- 有歧义或多条路线先问清效果、场景、代价，让用户选；讲方案先描述/画出结果，不先抛实现术语。
- 测试、编译、打包由用户决定；默认改完源码即停。一批需求只算一个版本，不拆版本。
- 功能入口/行为变化必须同步 `AiPrompts.DEFAULT_ASSISTANT_PROMPT`；外部服务地址必须查证官方来源。
- 键盘与语音现行栈未经点名不动；数据当前只存本机。

## 构建
- JDK17：`C:/Users/16621/.workbuddy/toolchains/jdk17`；Android SDK 同级；Gradle 8.14.3 用本机分发，项目无 wrapper，勿用 `gradlew`。
- `JAVA_HOME` 必须 `export`，`PATH` 加 `$JAVA_HOME/bin`；日志写 `logs/`。
- 中文路径 JVM 构建先 `subst X: "C:\Users\16621\Desktop\梦欢"`，再从 `X:` 构建。
- Compose 静态检查不能替代 `:app:compileDebugKotlin`；`scrollBy` 需导入 `androidx.compose.foundation.gestures.scrollBy`。
- 勿删 `local.properties`、`android.overridePathCheck=true`、阿里云 Maven 镜像。
- 出包：升版本 → assembleRelease → 根目录 `梦欢-v<版本>-release.apk` → 上版入 `archive/` → CHANGELOG/助手手册/断言同步。

## UI / Compose
- Figma 风：黑白骨架；品牌色只作内容点缀。令牌与组件统一走 `ui/common/Neu.kt`；图标只用 `MIcons`。
- 核心色：背景/表面白，轨道 `#F2F3F5`，发丝线黑 8%，主强调黑，危险 `#F24E1E`；强调色上文字/图标跟随 `AccentInk`。
- 动效弹簧优先；进出场避免边缩边 `fadeOut`；比例尺寸用 `LocalContentWidth`。
- `neuSurface(intensity=0f)` 会整条透明；凹陷灰槽用 `inset=true`。
- 颜色令牌在 composable 先取局部变量再进绘制 lambda；自绘描边内缩半个线宽。
- 独立 composable 不直接用 `Modifier.weight`；流程状态放 UiState；过滤节点直接不组合，不用 alpha=0。
- 动画容器不直接挂 `imePadding`；小尺寸自绘图标最终需截图放大验收。
- 异步数据必须区分「未加载/空/有数据」；loaded 从主数据流派生，不另发 Room 全表查询。
- 页面 pop 时底栏显隐与 push 过渡同步，避免导航状态先切导致闪跳。

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
- deep link 域名是 `menghuan`；编辑人设是独立路由页。
- AI 语音条合成成功后原地升级原消息；播放结束必须主动复位。
- Room 变更同步实体、版本/迁移、注册迁移与 schema；金额以分存 Long，余额由流水累加。
- LLM 历史必须带角色与虚拟角色背景；上下文预算按字数，封顶 20 万。

## 助手手册 replace 链
- 新 `.replace()` 只追加链尾，目标使用前序处理后的最终文本且不带版本号。
- 每条 replace 在 `AiPromptsTest` 同时断言新文案存在、旧文案不存在；中文词用中文引号。
- 启动升级机制保持 `refreshAssistantPromptIfNeeded` + `assistant_prompt_customized` 三态，不恢复历史全文比对。

## 禁止复活
- 打字机 `streamingText`、表情包、API 列表 ✓、语音两个总开关、设定词模板按钮。
- 已删：`EdgeTtsClient.kt`、`ReadAloudPlayer.kt`、`realtime/Mp3Decoder.kt`。
