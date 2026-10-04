package com.menghuan.app.realtime

import android.content.Context
import android.util.Log
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
    fun start(config: RealtimeCallConfig)
    fun hangUp()
    fun setMuted(muted: Boolean)

    /** VM 销毁兜底：未挂断就离开页面时补一次完整清理，然后终止引擎协程域 */
    fun destroy()
}

/**
 * 管道式语音通话引擎（v0.46.0）：
 *
 *   AudioRecord(VOICE_COMMUNICATION, 16k) 持续采样
 *     ├─ Silero VAD：话语分段 + 打断检测（用户开口 → barge-in）
 *     ├─ 流式 ASR：partial 字幕（边说边出字）+ 一句话收口
 *     ↓
 *   OpenAiClient.streamEvents（DeepSeek，persona prompt 同文字聊天）
 *     ↓ SentenceSplitter 按句切
 *   Edge TTS（逐句合成）→ MediaCodec 解 MP3 → AudioTrack 播放
 *
 * 状态机唯一写手（任务书 §十二）；转写只在内存，挂断时经 [saveCall] 一次性落库。
 * 所有资源（AudioRecord/AudioTrack/VAD/ASR/coroutine）在 hangUp 里全量释放，
 * 每次通话 = 新引擎实例，天然支持"第二次通话正常"（任务书 §二十二）。
 */
class PipelineVoiceCallEngine(
    context: Context,
    private val llm: OpenAiClient,
    private val appScope: CoroutineScope,
    private val saveCall: suspend (
        personaId: Long, startedAt: Long, endedAt: Long,
        turns: List<CallTurnData>, status: String,
    ) -> Unit,
) : VoiceCallEngine {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(RealtimeCallState.IDLE)
    override val state: StateFlow<RealtimeCallState> = _state

    private val _events = MutableSharedFlow<RealtimeCallEvent>(extraBufferCapacity = 128)
    override val events: SharedFlow<RealtimeCallEvent> = _events

    private var config: RealtimeCallConfig? = null
    private var audio: CallAudioManager? = null
    private var vad: CallVad? = null
    private var asr: CallAsr? = null
    private var edgeTts: EdgeTtsClient? = null

    private var mainLoop: Job? = null
    private var llmJob: Job? = null
    private var ttsJob: Job? = null
    private var ttsChannel: Channel<String>? = null

    private val muted = java.util.concurrent.atomic.AtomicBoolean(false)

    private val turns = ArrayList<CallTurnData>()
    /** 本轮 AI 回复中**已播放**的句子（打断时以此收口 AI 转写；未播出的不记） */
    private val spoken = StringBuffer()
    private var lastPartial = ""
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
        val a = runCatching { CallAsr(appContext.assets) }.getOrNull()
        if (v == null || a == null) {
            am.close(appContext)
            v?.release()
            fail("语音组件初始化失败")
            return
        }
        audio = am
        vad = v
        asr = a
        edgeTts = EdgeTtsClient(config.voice)
        startedAt = System.currentTimeMillis()
        lastPartial = ""
        lastMuted = false
        bargeInSpeechMs = 0
        _state.value = RealtimeCallState.LISTENING
        emitState()
        mainLoop = scope.launch { audioLoop(am, v, a) }
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
        vad?.release()
        asr?.release()
        audio = null
        vad = null
        asr = null

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
                runCatching { saveCall(cfg.personaId, startedAt, endedAt, snapshot, status) }
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

    private suspend fun audioLoop(am: CallAudioManager, v: CallVad, a: CallAsr) =
        withContext(Dispatchers.IO) {
            val f = FloatArray(CallVad.WINDOW)
            while (scope.isActive && _state.value != RealtimeCallState.ENDED) {
                val chunk = am.readChunk() ?: continue
                if (muted.get()) {
                    if (!lastMuted) {
                        lastMuted = true
                        v.reset()
                        a.finalizeUtterance() // 丢弃静音期间的半截识别
                        if (lastPartial.isNotEmpty()) {
                            lastPartial = ""
                            _events.tryEmit(RealtimeCallEvent.UserPartial(""))
                        }
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
                a.feed(f) // 先喂识别器：VAD 判定段结束时，该段音频必然已全部喂入
                val seg = v.accept(f)

                // 流式 partial 字幕（文本变化才发）
                val p = a.partial()
                if (p.isNotEmpty() && p != lastPartial) {
                    lastPartial = p
                    _events.tryEmit(RealtimeCallEvent.UserPartial(p))
                }

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
                    val text = a.finalizeUtterance()
                    if (lastPartial.isNotEmpty()) {
                        lastPartial = ""
                        _events.tryEmit(RealtimeCallEvent.UserPartial(""))
                    }
                    if (text.isNotBlank()) {
                        synchronized(turns) { turns += CallTurnData("user", text, System.currentTimeMillis()) }
                        _events.tryEmit(RealtimeCallEvent.UserFinal(text))
                        respond(text)
                    }
                }
            }
        }

    // ---- AI 回复：LLM 流式 → 切句 → TTS 队列 ----

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
            var lastEmit = 0L
            try {
                llm.streamEvents(cfg.systemPrompt, history).collect { ev ->
                    if (ev is StreamEvent.Delta) {
                        buf.append(ev.text)
                        val now = System.currentTimeMillis()
                        if (now - lastEmit > 200) {
                            lastEmit = now
                            _events.tryEmit(RealtimeCallEvent.AiDelta(buf.toString()))
                        }
                        val (sentences, rest) = SentenceSplitter.drain(buf.toString())
                        if (sentences.isNotEmpty()) {
                            sentences.forEach { channel.send(it) }
                            buf.clear().append(rest)
                            _events.tryEmit(RealtimeCallEvent.AiDelta(buf.toString()))
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

    private suspend fun ttsPlayer(channel: Channel<String>) = withContext(Dispatchers.IO) {
        var spoke = false
        for (sentence in channel) {
            val mp3 = runCatching { edgeTts?.synthesize(sentence) }.getOrNull()
                ?: runCatching { edgeTts?.synthesize(sentence) }.getOrNull() // 失败重试一次
            if (mp3 != null) {
                val pcm = Mp3Decoder.decode(mp3)
                if (pcm != null) {
                    if (!spoke) {
                        if (audio?.playStart() != true) break
                        spoke = true
                        _state.value = RealtimeCallState.AI_SPEAKING
                        emitState()
                    }
                    audio?.playPcm(pcm.samples) { scope.isActive && ttsJob?.isActive != false }
                }
            }
            spoken.append(sentence)
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
