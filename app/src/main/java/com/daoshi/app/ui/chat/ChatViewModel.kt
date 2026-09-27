package com.daoshi.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.daoshi.app.MentorApp
import com.daoshi.app.data.PersonaRepository
import com.daoshi.app.data.SettingsRepository
import com.daoshi.app.data.db.AppDatabase
import com.daoshi.app.data.model.ChatMessage
import com.daoshi.app.data.model.Persona
import com.daoshi.app.data.model.ToneStyle
import com.daoshi.app.llm.ApiException
import com.daoshi.app.llm.OpenAiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val isStreaming: Boolean = false,
    val error: String? = null,   // 请求失败提示（可重试）；失败消息不写入历史
)

/**
 * 单聊页 VM：每个 persona 一个独立会话（personaId 区分，消息互不混淆）。
 * - 打开时加载 Room 持久化消息，为空则显示种子对话
 * - 发送 → 持久化用户消息 → OpenAiClient 流式回复 → 边收边显 → 完成后持久化
 * - system prompt = persona.systemPrompt + tone.promptSnippet + 用户档案（ProfileFact）
 * - 对话风格按 persona 持久化（长按头像切换，重进保留）
 * - 请求失败（含 HTTP 错误）：不落库、给错误条 + 重试；已收到部分回复则保留已收内容
 */
class ChatViewModel(
    private val personaRepository: PersonaRepository,
    private val settingsRepository: SettingsRepository,
    private val db: AppDatabase,
    private val personaId: Long,
) : ViewModel() {

    private val _activePersona = MutableStateFlow<Persona?>(null)
    val activePersona: StateFlow<Persona?> = _activePersona.asStateFlow()

    /** 当前对话风格：长按头像切换，按 persona 持久化 */
    private val _tone = MutableStateFlow(ToneStyle.GENTLE)
    val tone: StateFlow<ToneStyle> = _tone.asStateFlow()

    private val _uiState = MutableStateFlow(ChatUiState(messages = seedMessages(personaId)))
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val persona = personaRepository.getPersona(personaId)
                ?: personaRepository.observePersonas().first().firstOrNull()
            _activePersona.value = persona
            if (persona != null) {
                _tone.value = runCatching { ToneStyle.valueOf(persona.tone) }
                    .getOrDefault(ToneStyle.GENTLE)
            }
            // 已有持久化消息则覆盖种子
            val persisted = db.messageDao().observeConversation(personaId).first()
            if (persisted.isNotEmpty()) {
                _uiState.value = _uiState.value.copy(messages = persisted)
            }
        }
    }

    fun onInputChange(text: String) {
        _uiState.value = _uiState.value.copy(input = text)
    }

    fun selectTone(tone: ToneStyle) {
        _tone.value = tone
        _activePersona.value = _activePersona.value?.copy(tone = tone.name)
        viewModelScope.launch { personaRepository.updateTone(personaId, tone.name) }
    }

    fun send() {
        val text = _uiState.value.input.trim()
        if (text.isEmpty() || _uiState.value.isStreaming) return
        val userMsg = ChatMessage(conversationId = personaId, isFromUser = true, content = text)
        _uiState.value = _uiState.value.copy(
            input = "",
            messages = _uiState.value.messages + userMsg,
            isStreaming = true,
            error = null,
        )
        viewModelScope.launch {
            db.messageDao().insert(userMsg)
            reply()
        }
    }

    /** 上次请求失败后重试（用户消息已在历史中） */
    fun retry() {
        if (_uiState.value.isStreaming) return
        _uiState.value = _uiState.value.copy(isStreaming = true, error = null)
        viewModelScope.launch { reply() }
    }

    /** 组装 system prompt 并流式获取回复 */
    private suspend fun reply() {
        val persona = _activePersona.value
        val config = settingsRepository.apiConfig.first()

        // 未配置 API 或人设缺失：模拟回复（演示模式）
        if (persona == null || !config.isComplete) {
            appendMockReply()
            return
        }

        val systemPrompt = buildString {
            append(persona.systemPrompt)
            append("\n\n").append(_tone.value.promptSnippet)
            val facts = db.profileFactDao().observeAll().first()
            if (facts.isNotEmpty()) {
                append("\n\n以下是用户档案，回答时请结合这些信息：")
                facts.forEach { append("\n- ").append(it.key).append("：").append(it.value) }
            }
        }
        // 历史取持久化消息：UI 种子对话是展示用假数据，绝不能发给 LLM 当真历史
        val history = db.messageDao().observeConversation(personaId)
            .first()
            .filter { it.content.isNotBlank() }
            .takeLast(20)

        val sb = StringBuilder()
        try {
            OpenAiClient(config.baseUrl, config.apiKey, config.model)
                .chat(systemPrompt, history)
                .collect { token ->
                    sb.append(token)
                    replaceStreamingTail(sb.toString())
                }
        } catch (e: CancellationException) {
            // 用户中途退出：把已显示的部分回复存档（看到过的内容不该消失），再照常传播取消
            persistReply(sb)
            throw e
        } catch (e: ApiException) {
            // HTTP 失败：一个 token 都没收到 → 错误条 + 重试，不落库
            if (sb.isEmpty()) {
                _uiState.value = _uiState.value.copy(isStreaming = false, error = friendlyApiError(e))
                return
            }
            // 已收到部分回复：保留已收内容并正常落库
        } catch (e: Exception) {
            if (sb.isEmpty()) {
                // 一个 token 都没收到：不落库，给出可重试的错误提示
                _uiState.value = _uiState.value.copy(
                    isStreaming = false,
                    error = "回复失败：${e.message ?: "网络异常"}",
                )
                return
            }
            // 已收到部分回复：保留已收内容并正常落库
        }
        persistReply(sb)
        _uiState.value = _uiState.value.copy(isStreaming = false)
    }

    /** 流结束后落库 assistant 回复；NonCancellable 保证已取消的作用域里也能写完 */
    private suspend fun persistReply(sb: StringBuilder) {
        if (sb.isEmpty()) return
        withContext(NonCancellable) {
            db.messageDao().insert(
                ChatMessage(conversationId = personaId, isFromUser = false, content = sb.toString())
            )
        }
    }

    /** API 错误 → 用户能看懂、知道下一步做什么的文案 */
    private fun friendlyApiError(e: ApiException): String = when (e.code) {
        401, 403 -> "API Key 无效或无权限，请在「设置 · API 配置」检查"
        404 -> "接口地址或模型名不存在，请检查配置"
        429 -> "请求太频繁或额度不足，稍后再试"
        in 500..599 -> "服务端暂时不可用（${e.code}），稍后再试"
        else -> "请求失败（${e.code}）"
    }

    /** 流式期间：替换列表末尾的 assistant 占位消息 */
    private fun replaceStreamingTail(text: String) {
        val msgs = _uiState.value.messages.toMutableList()
        val tail = msgs.lastOrNull()
        if (tail != null && !tail.isFromUser) {
            msgs[msgs.lastIndex] = tail.copy(content = text)
        } else {
            msgs.add(ChatMessage(conversationId = personaId, isFromUser = false, content = text))
        }
        _uiState.value = _uiState.value.copy(messages = msgs)
    }

    private suspend fun appendMockReply() {
        val mock = when (personaId) {
            2L -> "很好。记住这个承诺，我会检查。"
            3L -> "那么，今晚你愿意迈出的最小一步是什么？"
            else -> "记下了。这事今晚复盘细聊。"
        }
        delay(600)
        val msg = ChatMessage(conversationId = personaId, isFromUser = false, content = mock)
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages + msg,
            isStreaming = false,
        )
        db.messageDao().insert(msg)
    }

    companion object {
        fun factory(personaId: Long) = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MentorApp
                ChatViewModel(app.personaRepository, app.settingsRepository, app.db, personaId)
            }
        }

        /** 每个会话的独立种子对话（仅在 Room 无记录时显示） */
        private fun seedMessages(personaId: Long): List<ChatMessage> = when (personaId) {
            2L -> listOf(
                ChatMessage(conversationId = 2, isFromUser = false, content = "打卡：今天 07:45 起床，达标。"),
                ChatMessage(conversationId = 2, isFromUser = true, content = "周末想睡到中午行不行……"),
                ChatMessage(conversationId = 2, isFromUser = false, content = "不行。07:45 是底线，周末最多延到 08:30。现在回答我：今晚 23:00 前睡觉，能不能做到？"),
            )
            3L -> listOf(
                ChatMessage(conversationId = 3, isFromUser = false, content = "你说想「改变自己」。能具体说说，是哪个时刻让你想到这个词的吗？"),
                ChatMessage(conversationId = 3, isFromUser = true, content = "大概是又一次熬夜到两点，第二天什么都没干成的时候。"),
                ChatMessage(conversationId = 3, isFromUser = false, content = "如果那样的早晨再发生一次，你会失去什么你最在意的东西？"),
            )
            else -> listOf(
                ChatMessage(conversationId = 1, isFromUser = false, content = "昨晚说好的 11 点前睡，打卡记录显示你 1:24 才放下手机。说说原因？"),
                ChatMessage(conversationId = 1, isFromUser = true, content = "本来打算睡了，结果刷短视频没刹住……"),
                ChatMessage(conversationId = 1, isFromUser = true, content = "你先别骂我"),
                ChatMessage(conversationId = 1, isFromUser = false, content = "我不骂人，我只看数据：本周第三次了。换个方案——22:50 我提醒你，23:00 自动进勿扰。另外把「睡前刷手机」单独拆成追踪目标。"),
            )
        }
    }
}
