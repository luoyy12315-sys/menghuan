package com.menghuan.app.realtime

import android.content.Context
import android.util.Log
import com.menghuan.app.data.SherpaAsr
import com.menghuan.app.data.TtsClient
import com.menghuan.app.data.TtsTarget
import com.menghuan.app.data.model.ChatMessage
import com.menghuan.app.llm.OpenAiClient
import com.menghuan.app.llm.StreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 可替换的通话引擎接口（v0.46.0 管道版是第一个实现；将来接 GLM-Realtime
 * 真双工时新增实现类，UI 与通话记录层不动）。
 */
interface VoiceCallEngine {
    val state: StateFlow<RealtimeCallState>
    val events: SharedFlow<RealtimeCallEvent>

    /**
     * AI **正在播**的语音电平（0f–1f，v0.58.0）。
     * 视频通话用它驱动 Live2D 口型——它的口径是"播放头真播到这儿了"，
     * 不是"这一句已经发给播放器了"，所以嘴巴不会比声音先动。
     * 语音通话不读它，不产生任何额外开销。
     */
    val aiLevel: StateFlow<Float>

    fun start(config: RealtimeCallConfig)
    /** v0.53.7：AI 主动打来的电话，接通后由 AI 先开口（引擎就绪后调用一次） */
    fun speakFirst()    fun hangUp()
    fun setMuted(muted: Boolean)

    /** VM 销毁兜底：未挂断就离开页面时补一次完整清理，然后终止引擎协程域 */
    fun destroy()
}

/**
 * 管道式语音通话引擎（v0.46.0；v0.50.0 换识别与合成）：
 *
 *   AudioRecord(VOICE_COMMUNICATION, 16k) 持续采样
 *     ├─ Silero VAD：话语分段 + 打断检测（用户开口 → barge-in）
 *     ├─ 端侧离线 ASR（SenseVoice）：整段吃完出字，字幕**按段出**（非流式，没有逐字 partial）
 *     ↓
 *   OpenAiClient.streamEvents（人设 prompt 同文字聊天）
 *     ↓ SentenceSplitter 按句切
 *   统一 TtsClient（硅基 / MiMo / 自定义，与 AI 语音条同一条链）→ 解成 PCM → AudioTrack 播放
 *
 * 状态机唯一写手（任务书 §十二）；转写只在内存，挂断时经 [saveCall] 一次性落库。
 * 所有资源（AudioRecord/AudioTrack/VAD/coroutine）在 hangUp 里全量释放，
 * 每次通话 = 新引擎实例，天然支持"第二次通话正常"（任务书 §二十二）。
 */
class PipelineVoiceCallEngine(
    context: Context,
    private val llm: OpenAiClient,
    private val tts: TtsClient,
    private val appScope: CoroutineScope,
    private val saveCall: suspend (
        personaId: Long, startedAt: Long, endedAt: Long,
        turns: List<CallTurnData>, status: String, initiatedByAi: Boolean,
    ) -> Unit,
) : VoiceCallEngine {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(RealtimeCallState.IDLE)
    override val state: StateFlow<RealtimeCallState> = _state

    private val _events = MutableSharedFlow<RealtimeCallEvent>(extraBufferCapacity = 128)
    override val events: SharedFlow<RealtimeCallEvent> = _events

    private val _aiLevel = MutableStateFlow(0f)
    override val aiLevel: StateFlow<Float> = _aiLevel

    private var config: RealtimeCallConfig? = null
    private var audio: CallAudioManager? = null
    private var vad: CallVad? = null

    private var mainLoop: Job? = null
    private var llmJob: Job? = null
    private var ttsJob: Job? = null
    private var ttsChannel: Channel<String>? = null

    private val muted = java.util.concurrent.atomic.AtomicBoolean(false)

    private val turns = ArrayList<CallTurnData>()
    /** 本轮 AI 回复中**已播放**的句子（打断时以此收口 AI 转写；未播出的不记） */
    private val spoken = StringBuffer()
    private var lastMuted = false
    private var bargeInSpeechMs = 0L
    private var startedAt = 0L
    private var hadError = false

    override fun start(config: RealtimeCallConfig) {
        val s = _state.value
        if (s != RealtimeCallState.IDLE && s != RealtimeCallState.ENDED && s != RealtimeCallState.ERROR) return
        this.config = config
        synchronized(turns) { turns.clear() }
        _state.value = RealtimeCallState.CONNECTING
        emitState()

        val am = CallAudioManager()
        if (!am.start(appContext)) {
            am.close(appContext)
            fail("无法启动麦克风，请检查权限后重试")
            return
        }
        val v = runCatching { CallVad(appContext.assets) }.getOrNull()
        if (v == null) {
            am.close(appContext)
            fail("语音组件初始化失败")
            return
        }
        audio = am
        vad = v
        // 口型电平：只有视频通话那边会订阅 aiLevel，这里挂监听本身只是几次原子写，
        // 语音通话不多做任何事（方案文档 §二.一：语音通话不许被视觉层拖累）
        am.levelListener = { v0 -> _aiLevel.value = v0 }
        startedAt = System.currentTimeMillis()
        lastMuted = false
        bargeInSpeechMs = 0
        mainLoop = scope.launch {
            // 同 ttsPlayer：这个协程也在 applicationScope 上，抛出来就是整个 App 没了。
            // 采样/VAD/识别里任何意外都接住，报一条错误并收线，不让用户只看到闪退。
            try {
                // 识别模型不随包（228MB，装后下载），加载权重要几秒——必须在 IO 线程做，
                // 否则 start() 所在的主线程直接 ANR。这段时间状态停在 CONNECTING。
                if (!SherpaAsr.warmUp(appContext)) {
                    am.close(appContext)
                    v.release()
                    audio = null
                    vad = null
                    fail("语音识别模型未就绪，请先在设置里下载")
                    return@launch
                }
                _state.value = RealtimeCallState.LISTENING
                emitState()
                audioLoop(am, v)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                Log.e(TAG, "call loop failed", e)
                hadError = true
                _events.tryEmit(
                    RealtimeCallEvent.Error("通话出错：${e.javaClass.simpleName}：${e.message ?: "无详细信息"}"),
                )
            }
        }
    }

    override fun setMuted(m: Boolean) {
        muted.set(m)
    }

    /** 挂断：停录音/播放 → 关 DataChannel/连接的对应物（无 WebRTC）→ 释放资源 → 存记录 → ENDED */
    override fun hangUp() {
        val s = _state.value
        if (s == RealtimeCallState.IDLE || s == RealtimeCallState.ENDED || s == RealtimeCallState.DISCONNECTING) return
        _state.value = RealtimeCallState.DISCONNECTING
        emitState()
        mainLoop?.cancel()
        llmJob?.cancel()
        ttsJob?.cancel()
        ttsChannel?.close()
        audio?.stopPlayback()
        audio?.close(appContext) // 恢复 AudioManager（任务书 §二十一）
        _aiLevel.value = 0f      // 口型随挂断立刻归零，视觉层不会停在张嘴那一帧
        vad?.release()
        audio = null
        vad = null

        val endedAt = System.currentTimeMillis()
        val durationMs = if (startedAt > 0) endedAt - startedAt else 0
        val snapshot = synchronized(turns) { turns.toList() }
        val status = if (hadError) "error" else "completed"
        val cfg = config
        _state.value = RealtimeCallState.ENDED
        emitState()
        if (cfg != null) {
            // 存到应用级作用域：即使 VM 立刻销毁（引擎 scope.cancel）记录也不会丢
            appScope.launch {
                runCatching { saveCall(cfg.personaId, startedAt, endedAt, snapshot, status, cfg.initiatedByAi) }
                    .onFailure { Log.e(TAG, "save call failed: ${it.message}") }
            }
        }
    }

    /** VM 销毁兜底：没挂断就离开页面（被系统回收）也走一遍完整清理 */
    override fun destroy() {
        if (_state.value != RealtimeCallState.ENDED) hangUp()
        scope.cancel()
    }

    // ---- 主循环：采样 → VAD/ASR → 话语收口 → respond ----

    private suspend fun audioLoop(am: CallAudioManager, v: CallVad) =
        withContext(Dispatchers.IO) {
            val f = FloatArray(CallVad.WINDOW)
            while (scope.isActive && _state.value != RealtimeCallState.ENDED) {
                val chunk = am.readChunk() ?: continue
                if (muted.get()) {
                    if (!lastMuted) {
                        lastMuted = true
                        v.reset() // 丢弃静音期间攒下的半截语音段
                        if (_state.value == RealtimeCallState.USER_SPEAKING) {
                            _state.value = RealtimeCallState.LISTENING
                            emitState()
                        }
                    }
                    continue
                }
                if (lastMuted) {
                    lastMuted = false
                    v.reset()
                }

                for (i in chunk.indices) f[i] = chunk[i] / 32768f
                val seg = v.accept(f)

                val st = _state.value
                if (v.speechDetected) {
                    if (st == RealtimeCallState.LISTENING || st == RealtimeCallState.AI_THINKING || st == RealtimeCallState.AI_SPEAKING) {
                        _state.value = RealtimeCallState.USER_SPEAKING
                        emitState()
                    }
                }

                // barge-in：AI 出声/思考期间用户持续开口 ≥ 0.3s → 打断（任务书 §十一）
                val inAiTurn = llmJob?.isActive == true
                if (inAiTurn && v.speechDetected) {
                    bargeInSpeechMs += (CallVad.WINDOW * 1000L) / CallVad.IN_RATE
                    if (bargeInSpeechMs >= BARGE_IN_MS) {
                        bargeInSpeechMs = 0
                        interrupt()
                    }
                } else {
                    bargeInSpeechMs = 0
                }

                if (seg != null) {
                    // 离线非流式：整段吃完才出字，所以字幕**按段出**，没有逐字 partial。
                    // 解码这几百毫秒里音频循环暂停——此刻用户刚说完、正等 AI 接话，
                    // 丢掉的是静音尾巴，barge-in 也不需要在这段窗口里生效。
                    val text = SherpaAsr.transcribeSamples(appContext, seg.samples)
                    if (!text.isNullOrBlank()) {
                        synchronized(turns) { turns += CallTurnData("user", text, System.currentTimeMillis()) }
                        _events.tryEmit(RealtimeCallEvent.UserFinal(text))
                        respond(text)
                    }
                }
            }
        }

    // ---- AI 回复：LLM 流式 → 切句 → TTS 队列 ----

    /**
     * v0.53.7：AI 主动打来的电话 —— 接通后**由 AI 先开口**，不等用户说话。
     * 实现是塞一条"电话已接通"的内部提示当输入触发一次回复；它**不进 `turns`**，
     * 所以不会被当成"用户说的话"落库、也不会出现在字幕里。
     */
    override fun speakFirst() {
        if (config?.initiatedByAi != true) return
        respond("（电话已经接通，用户接起来了。请你先开口打招呼。）")
    }

    private fun respond(text: String) {
        llmJob?.cancel()
        ttsJob?.cancel()
        ttsChannel?.close()
        audio?.stopPlayback()

        val cfg = config ?: return
        llmJob = scope.launch {
            _state.value = RealtimeCallState.AI_THINKING
            emitState()
            val history = synchronized(turns) {
                turns.map { ChatMessage(conversationId = 0L, content = it.text, isFromUser = it.speaker == "user", timestamp = it.timestamp) }
            } + ChatMessage(conversationId = 0L, content = text, isFromUser = true, timestamp = System.currentTimeMillis())

            spoken.setLength(0)
            val channel = Channel<String>(Channel.UNLIMITED)
            ttsChannel = channel
            ttsJob = scope.launch { ttsPlayer(channel) }

            val buf = StringBuilder()
            // v0.53.9：**字幕跟着"整段回复"走**。以前切句时把 buf 清成"还没念的那截"，
            // 字幕也就跟着退成那一截 —— 等到念完才由 AiFinal 一次性补全，
            // 真机反馈就是"他先说完话，文字才出来"。现在 shown 只增不减，
            // 文字随流式生成出现（先出字、后出声），念的过程中一直挂着。
            val shown = StringBuilder()
            var lastEmit = 0L
            try {
                llm.streamEvents(cfg.systemPrompt, history).collect { ev ->
                    if (ev is StreamEvent.Delta) {
                        buf.append(ev.text)
                        shown.append(ev.text)
                        val now = System.currentTimeMillis()
                        if (now - lastEmit > 200) {
                            lastEmit = now
                            _events.tryEmit(RealtimeCallEvent.AiDelta(shown.toString()))
                        }
                        val (sentences, rest) = SentenceSplitter.drain(buf.toString())
                        if (sentences.isNotEmpty()) {
                            sentences.forEach { channel.send(it) }
                            buf.clear().append(rest)
                            _events.tryEmit(RealtimeCallEvent.AiDelta(shown.toString()))
                        }
                    }
                }
                val tail = buf.toString().trim()
                if (tail.isNotEmpty()) channel.send(tail)
                channel.close()
                ttsJob?.join()
                val said = spoken.toString().trim()
                if (said.isNotEmpty()) {
                    synchronized(turns) { turns += CallTurnData("ai", said, System.currentTimeMillis()) }
                    _events.tryEmit(RealtimeCallEvent.AiFinal(said))
                }
                if (_state.value == RealtimeCallState.AI_THINKING || _state.value == RealtimeCallState.AI_SPEAKING) {
                    _state.value = RealtimeCallState.LISTENING
                    emitState()
                }
            } catch (ce: CancellationException) {
                runCatching { channel.close() }
                throw ce
            } catch (e: Exception) {
                runCatching { channel.close() }
                Log.e(TAG, "respond failed", e)
                hadError = true
                _events.tryEmit(RealtimeCallEvent.Error("回复失败：${e.message ?: "网络异常"}"))
                _state.value = RealtimeCallState.LISTENING
                emitState()
            }
        }
    }

    /**
     * 逐句合成 + 播放。
     *
     * **整段包 try/catch**（v0.53.1）：这个协程跑在 `applicationScope` 上，里面任何没被接住的
     * 异常都会直接冒到默认处理器 → 整个 App 闪退（真机反馈"AI 还没出声就闪退"就是这个形状）。
     * 通话里最可能抛的是 AudioTrack 状态类异常（挂断/插话时另一条线程正在 release 轨道），
     * 那不该让进程死——接住、报一条错误、继续走字幕兜底。
     */
    private suspend fun ttsPlayer(channel: Channel<String>) = withContext(Dispatchers.IO) {
        val voice = config?.voice ?: return@withContext
        var spoke = false
        try {
            for (sentence in channel) {
                var result = tts.synthesize(sentence, voice, TtsTarget.CALL)
                if (result == null) result = tts.synthesize(sentence, voice, TtsTarget.CALL) // 瞬时失败重试一次
                // v0.52.0：通话改要 WAV@OUT_RATE，正常路径**不经过任何解码**；
                // 万一网关仍回 MP3，交给 MediaExtractor 兜底（它读真实采样率/声道，要落临时文件）
                val pcm = result?.let { TtsAudioDecoder.decode(it, CallAudioManager.OUT_RATE, appContext.cacheDir) }
                if (pcm != null) {
                    if (!spoke) {
                        if (audio?.playStart() != true) {
                            _events.tryEmit(RealtimeCallEvent.Error("声音播放初始化失败"))
                            break
                        }
                        spoke = true
                        _state.value = RealtimeCallState.AI_SPEAKING
                        emitState()
                    }
                    audio?.playPcm(pcm) { scope.isActive && ttsJob?.isActive != false }
                } else if (!spoke) {
                    // 整轮一句都出不来声：把原因亮给用户（远程诊断真机无声问题），之后走字幕兜底
                    val why = if (result == null) tts.lastFailure ?: "未知原因"
                        else "服务商回的 ${result.format} 解不开，请检查这家是否支持该格式"
                    _events.tryEmit(RealtimeCallEvent.Error("声音合成失败：$why"))
                }
                synchronized(spoken) { spoken.append(sentence) }
            }
        } catch (ce: CancellationException) {
            throw ce // 插话/挂断是正常的取消，不是故障
        } catch (e: Throwable) {
            Log.e(TAG, "tts player failed", e)
            hadError = true
            _events.tryEmit(
                RealtimeCallEvent.Error("播放出错：${e.javaClass.simpleName}：${e.message ?: "无详细信息"}"),
            )
        }
        if (spoke) audio?.stopPlayback()
    }

    /** barge-in：掐掉 LLM 与 TTS，已播出的句子记为 AI 轮，立即回到倾听 */
    private fun interrupt() {
        llmJob?.cancel()
        llmJob = null
        ttsChannel?.close()
        ttsJob?.cancel()
        ttsJob = null
        audio?.stopPlayback()
        val said = spoken.toString().trim()
        if (said.isNotEmpty()) {
            synchronized(turns) { turns += CallTurnData("ai", said, System.currentTimeMillis()) }
            _events.tryEmit(RealtimeCallEvent.AiFinal(said))
        }
        _state.value = RealtimeCallState.INTERRUPTED
        emitState()
        _state.value = RealtimeCallState.LISTENING
        emitState()
    }

    private fun fail(message: String) {
        hadError = true
        Log.e(TAG, message)
        _events.tryEmit(RealtimeCallEvent.Error(message))
        _state.value = RealtimeCallState.ERROR
        emitState()
    }

    private fun emitState() {
        _events.tryEmit(RealtimeCallEvent.State(_state.value))
    }

    private companion object {
        const val TAG = "VoiceCallEngine"
        const val BARGE_IN_MS = 300L
    }
}
