package com.menghuan.app.ui.realtime

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.menghuan.app.MenghuanApp
import com.menghuan.app.data.AiPrompts
import com.menghuan.app.data.AsrModelManager
import com.menghuan.app.data.TtsClients
import com.menghuan.app.data.TtsConfig
import com.menghuan.app.data.model.Persona
import com.menghuan.app.llm.OpenAiClient
import com.menghuan.app.realtime.CallTurnData
import com.menghuan.app.realtime.CallTones
import com.menghuan.app.realtime.PipelineVoiceCallEngine
import com.menghuan.app.realtime.RealtimeCallConfig
import com.menghuan.app.realtime.RealtimeCallEvent
import com.menghuan.app.realtime.RealtimeCallRepository
import com.menghuan.app.realtime.RealtimeCallState
import com.menghuan.app.realtime.VoiceCallEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 通话页 UI 状态（引擎状态 + 字幕聚合；字幕轮次只在内存，挂断后由仓库落库） */
data class RealtimeCallUiState(
    val persona: Persona? = null,
    val state: RealtimeCallState = RealtimeCallState.IDLE,
    val userPartial: String = "",
    val aiPartial: String = "",
    val turns: List<CallTurnData> = emptyList(),
    val muted: Boolean = false,
    val error: String? = null,
    /** 对话接口未配置（不进引擎，页面给出明确提示） */
    val llmMissing: Boolean = false,
    /** 语音合成未配置：电话能打通、能识别、能出字幕，但 AI 不出声 */
    val ttsMissing: Boolean = false,
    /** 端侧识别模型未下载：整通电话识别不了，页面给一键跳转下载 */
    val asrMissing: Boolean = false,
)

class RealtimeCallViewModel(
    private val app: MenghuanApp,
    private val personaId: Long,
    /** v0.53.7：这通电话是不是 AI 主动打来的（决定谁先开口 + 记录卡落哪一侧） */
    private val initiatedByAi: Boolean = false,
    /** v0.53.7：AI 打来时已经生成好的开场白，作为它第一句的参考 */
    private val greeting: String = "",
) : ViewModel() {

    private val db = app.db
    private val settings = app.settingsRepository

    private var engine: VoiceCallEngine? = null

    private val _ui = MutableStateFlow(RealtimeCallUiState())
    val ui: StateFlow<RealtimeCallUiState> = _ui

    init {
        viewModelScope.launch {
            val persona = runCatching {
                db.personaDao().observeAll().first().find { it.id == personaId }
            }.getOrNull()
            _ui.update { it.copy(persona = persona) }
        }
    }

    /** 进页即叫（权限已授予时）；幂等 */
    fun start() {
        if (engine != null || _ui.value.llmMissing) return
        viewModelScope.launch {
            val persona = runCatching {
                db.personaDao().observeAll().first().find { it.id == personaId }
            }.getOrNull() ?: run {
                _ui.update { it.copy(error = "人设不存在") }
                return@launch
            }
            val config = runCatching { settings.apiConfigFor(personaId).first() }.getOrNull()
            if (config == null || !config.isComplete) {
                _ui.update { it.copy(llmMissing = true) }
                return@launch
            }
            // 识别模型不随包（228MB 装后下载）：没模型这通电话一句都识别不了，
            // 进页就先说清楚并给下载入口，别让用户对着电话讲半天发现 AI 全聋。
            if (!AsrModelManager.isReady(app)) {
                _ui.update { it.copy(asrMissing = true) }
            }

            // system prompt 组装与文字聊天同源（ChatViewModel.reply 同语义）：
            // 人设设定词（空则基础提示词）+ 档案注入说明 + 用户档案；不注入生图/手机操控能力
            val facts = runCatching { db.profileFactDao().observeAll().first() }.getOrDefault(emptyList())
            val preamble = runCatching { settings.profilePreamble.first() }
                .getOrDefault(AiPrompts.DEFAULT_PROFILE_PREAMBLE)
            val basePrompt = runCatching { settings.basePrompt.first() }
                .getOrDefault(AiPrompts.DEFAULT_BASE_PROMPT)
            val personaPrompt = persona.systemPrompt.trim()
            val systemPrompt = buildString {
                if (personaPrompt.isNotEmpty()) append(personaPrompt) else append(basePrompt)
                if (facts.isNotEmpty()) {
                    append("\n\n").append(preamble)
                    facts.forEach { append("\n- ").append(it.key).append("：").append(it.value) }
                }
                // 通话措辞：回复会被朗读，Markdown/列表/长篇都会变成糟糕的听觉体验
                append("\n\n当前是实时语音通话：用自然口语回答，每次只说两三句短句，")
                append("不要使用 Markdown、列表、代码或颜文字，不要一次问多个问题。")
                // v0.53.7：**必须告诉它这通电话是谁打的** —— 否则它会当成自己接到的电话，
                // 出现"喂？你找我什么事"这种完全错位的开场（用户反馈"AI 不知道是他给我打的电话"）。
                if (initiatedByAi) {
                    append("\n\n⚠️ 这通电话是**你主动打给用户的**（不是用户打给你）。")
                    append("接通后由你先开口，直接说你想说的话，")
                    append("不要问「喂？」「你找我有事吗」这类接电话的话。")
                    if (greeting.isNotBlank()) {
                        append("\n你原本想说的第一句是：").append(greeting)
                        append("（照这个意思自然说出来即可，不用一字不差）")
                    }
                } else {
                    append("\n\n这通电话是**用户打给你的**，接通后先听用户开口，不要抢先长篇大论。")
                }
            }

            // 音色与聊天里的语音条走同一个三级回落（人设专属 → 音色板块默认 → 该家第一个预置）
            val ttsConfig = runCatching { settings.ttsConfig.first() }.getOrDefault(TtsConfig())
            val voice = settings.voiceForConversation(personaId, ttsConfig)
            val client = OpenAiClient(config.baseUrl, config.apiKey, config.model)
            val repo = RealtimeCallRepository(db)
            val eng = PipelineVoiceCallEngine(
                app,
                client,
                TtsClients.from(ttsConfig, app),
                app.applicationScope,
            ) { p, st, et, turns, status, _ ->
                // 第 6 个参数（谁打的）用闭包里的 initiatedByAi，引擎那边只是转发
                repo.saveCall(p, st, et, turns, status, initiatedByAi)
            }
            engine = eng
            // TTS 没配齐也能打通电话：识别与 LLM 照常，只是不出声、全靠字幕。
            // 但必须**明确告诉用户**为什么没声——静默无声是这个项目排查最久的问题。
            _ui.update { it.copy(ttsMissing = !ttsConfig.isComplete) }
            viewModelScope.launch {
                eng.events.collect { onEvent(it) }
            }
            // 模型加载 / 麦克风启动在 IO 线程做，别卡主线程进页动画
            withContext(Dispatchers.IO) {
                eng.start(RealtimeCallConfig(personaId, persona.name, systemPrompt, voice, initiatedByAi))
            }
        }
    }

    fun hangUp() {
        CallTones.play(CallTones.Kind.HANG_UP)
        engine?.hangUp()
    }

    fun toggleMute() {
        val eng = engine ?: return
        val next = !_ui.value.muted
        eng.setMuted(next)
        _ui.update { it.copy(muted = next) }
    }

    // 接通音只放一次
    private var connectTonePlayed = false

    private fun onEvent(ev: RealtimeCallEvent) {
        when (ev) {
            is RealtimeCallEvent.State -> {
                _ui.update { it.copy(state = ev.state) }
                // 接通音：首次进入倾听态（引擎初始化完成）
                if (ev.state == RealtimeCallState.LISTENING && !connectTonePlayed) {
                    connectTonePlayed = true
                    CallTones.play(CallTones.Kind.CONNECT)
                    // v0.53.7：AI 主动打来的电话，接通音之后由 **AI 先开口**（不等用户说话）
                    if (initiatedByAi) engine?.speakFirst()
                }
            }
            is RealtimeCallEvent.UserPartial -> _ui.update { it.copy(userPartial = ev.text) }
            is RealtimeCallEvent.UserFinal -> _ui.update {
                it.copy(userPartial = "", turns = it.turns + CallTurnData("user", ev.text, System.currentTimeMillis()))
            }
            is RealtimeCallEvent.AiDelta -> _ui.update { it.copy(aiPartial = ev.fullText) }
            is RealtimeCallEvent.AiFinal -> _ui.update {
                it.copy(aiPartial = "", turns = it.turns + CallTurnData("ai", ev.text, System.currentTimeMillis()))
            }
            is RealtimeCallEvent.Error -> {
                _ui.update { it.copy(error = ev.message) }
                viewModelScope.launch {
                    delay(6_000)
                    _ui.update { if (it.error == ev.message) it.copy(error = null) else it }
                }
            }
        }
    }

    override fun onCleared() {
        engine?.destroy()
        super.onCleared()
    }

    companion object {
        fun Factory(
            personaId: Long,
            initiatedByAi: Boolean = false,
            greeting: String = "",
        ) = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MenghuanApp
                RealtimeCallViewModel(app, personaId, initiatedByAi, greeting)
            }
        }
    }
}
