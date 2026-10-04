package com.menghuan.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.menghuan.app.MenghuanApp
import com.menghuan.app.data.ApiProfile
import com.menghuan.app.data.DEFAULT_PROFILE_ID
import com.menghuan.app.data.ExtractionEngine
import com.menghuan.app.data.ImageGenerationClient
import com.menghuan.app.data.PersonaRepository
import com.menghuan.app.data.SettingsRepository
import com.menghuan.app.data.TaskSuggestion
import com.menghuan.app.data.TaskSuggestionHub
import com.menghuan.app.data.db.AppDatabase
import com.menghuan.app.agent.AgentAccessibilityService
import com.menghuan.app.agent.AgentPrompts
import com.menghuan.app.agent.AgentRunner
import com.menghuan.app.data.model.ChatMessage
import com.menghuan.app.data.model.MediaKind
import com.menghuan.app.data.model.Persona
import com.menghuan.app.data.model.TaskItem
import com.menghuan.app.llm.ApiException
import com.menghuan.app.llm.OpenAiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val isStreaming: Boolean = false,
    /**
     * 正在后台生成中（v0.41.4）：生成期间 UI 只挂三点气泡，**不产生任何 assistant 消息**；
     * 全部收完后整段落库上屏（用户要求的"后台生成、一次性发送"）。
     * 与 [isStreaming] 的区别：isStreaming 从发送到落库全程为 true，generating 只在
     * 等模型回复期间为 true（落库那一帧就归位）。
     */
    val generating: Boolean = false,
    val isGeneratingImage: Boolean = false, // AI 回复里带 [生图:…] 标记后正在生成图片
    val imagePrompt: String = "",           // 正在生成的画面提示词（生图动画卡展示用）
    val error: String? = null,   // 请求失败提示；失败消息不写入历史
    val errorKind: String = ErrorBar.KIND_RETRY, // 错误条动作种类（重试 / 去开启 / 去配置 / 无动作）
    val agentRun: AgentRunUiState? = null, // [操作手机:…] 触发的执行卡（null = 不显示）
)

/**
 * 错误条分级（v0.38.0）：不是所有错误都该给"重试"——配置/权限类错误重试毫无意义，
 * 而且重试会让模型重新生成回复，可能把带 [操作手机:…] 标记的操作再执行一遍。
 */
data class ErrorBar(val message: String, val kind: String) {
    companion object {
        const val KIND_RETRY = "retry"              // 网络类：重试按钮
        const val KIND_ACCESSIBILITY = "accessibility" // 无障碍未开：去开启（跳系统设置）
        const val KIND_API = "api"                  // API 未配置/Key 失效：去配置
        const val KIND_INFO = "info"                // 纯提示：无按钮
    }
}

/**
 * 对话触发手机操作的前置守卫（纯函数，单测口径）。
 * 返回 null = 放行；否则为 (提示文案, 错误条种类)。
 */
object AgentChatGate {
    fun decide(
        enabled: Boolean,
        serviceRunning: Boolean,
        alreadyRunning: Boolean,
        apiComplete: Boolean,
    ): Pair<String, String>? = when {
        !enabled -> "回复里带了操控手机请求，但该能力已在「设置 · AI 提示词」里关闭" to ErrorBar.KIND_INFO
        !serviceRunning -> "无障碍服务未开启，无法执行手机操作" to ErrorBar.KIND_ACCESSIBILITY
        alreadyRunning -> "上一个手机操作还在执行中，完成后再试" to ErrorBar.KIND_INFO
        !apiComplete -> "API 未配置，无法执行手机操作" to ErrorBar.KIND_API
        else -> null
    }
}

/**
 * 对话内手机执行卡（v0.37.0）：日志实时滚动；结束时摘要消息由 onFinish 落库，
 * 卡片随之收起（收起时机 = AgentRunner 回 Idle）。
 */
data class AgentRunUiState(
    val goal: String,
    val logs: List<String> = emptyList(),
    val running: Boolean = true,
    val success: Boolean = false,
    val summary: String = "",
    val steps: Int = 0,
)

/** 回复里只有操控标记、没有其它文字时的占位文案（执行卡承担全部观感） */
const val AGENT_ONLY_PLACEHOLDER = "（好的，交给我来操作）"

/** 回复里只有生图标记、没有其它文字时的占位文案（App 内部用，会立刻被图片消息覆盖观感） */
const val IMAGE_ONLY_PLACEHOLDER = "（正在为你生成图片）"

/**
 * 设置 · AI 提示词面板里的「生成逻辑说明」文案。
 * 与实现同源：提炼走 ExtractionEngine（攒够 8 条增量消息自动跑，
 * 数据页可手动「提炼」），周报走 WeeklyReportGenerator（每周日 21:30，
 * 启动/重启/到点都会补上周缺报）。
 */
const val insightLogicText =
    "【AI 洞察 · 怎么来的】\n" +
        "· 触发：某个会话累计新增 8 条消息后自动提炼一次；也可在数据页点「提炼」手动跑。\n" +
        "· 取数：该会话水位线之后的对话 + 近 7 天任务完成情况，「怎么用 App」的助手会话不计。\n" +
        "· 产出：长期画像（写入基础档案）、洞察（最多 1 条，宁缺毋滥）、可执行任务建议（最多 1 条，聊天页卡片确认后才落库）。\n" +
        "· 原则：只收有明确依据的信息，普通闲聊与猜测一律不记；同一标题的洞察会被新的一条替换，最多留 20 条。"

const val reportLogicText =
    "【AI 周报 · 怎么来的】\n" +
        "· 触发：每周日 21:30 自动生成上一周；App 启动、手机重启也会补上周缺报（无 API 时自动跳过）。\n" +
        "· 取数：上一周的基础任务完成情况、每日任务打卡记录、档案变更、AI 洞察、对话节选（同样排除助手会话）。\n" +
        "· 产出：200–400 字中性周报，同一周重复生成会覆盖，不会堆多期。\n" +
        "· 口径：完成度只算基础任务，打卡只算每日任务，两者分开统计。"

/**
 * 生图能力提示（只在生图接口已配置时注入 system prompt）。
 * 用方括号单行标记而不是 function calling：各家 OpenAI 兼容端点对 tools 支持不一致，
 * 标记协议最稳（解析见 ImageGenerationClient.findPromptIn）。
 *
 * **v0.36.0 分层**：
 * - [IMAGE_HINT_FRAMEWORK]：标记格式硬约束（不可改，改了 findPromptIn 匹配不到就整条流程崩）
 * - [IMAGE_HINT_DEFAULT_STRATEGY]：何时触发、如何写提示词（用户可在设置里覆盖）
 * - [imageHint]：拼接两者，作为最终注入 system prompt 的一段
 */
const val IMAGE_HINT_FRAMEWORK: String =
    "生图触发标记格式（严格不可改）：在回复里单独加一行 [生图:用英文写的画面提示词]，" +
        "其余文字照常写。标记只用于触发生图，不要在正文里解释这个标记，也不要重复它。"

const val IMAGE_HINT_DEFAULT_STRATEGY: String =
    "你可以生成图片，但这是被动能力：只在用户最近一条消息里明确要求" +
        "（画一张 / 生成图片 / 配图 / 来张…的图）时才输出标记；" +
        "用户没有明确要求就绝不生成——没人要求却主动发图片标记是严重错误；" +
        "之前生成过图片不构成继续生成的理由，同一话题最多画一次，不要反复画相似的画面；" +
        "英文提示词要具体描述画面主体、风格、光线、构图，避免抽象概念。"

fun imageHint(strategy: String = IMAGE_HINT_DEFAULT_STRATEGY): String =
    if (strategy.isBlank()) IMAGE_HINT_FRAMEWORK else "$IMAGE_HINT_FRAMEWORK\n$strategy"

/**
 * 手机操控能力提示（v0.37.0；总开关打开且无障碍服务在线时注入 system prompt）。
 * 与生图同构的单行标记协议，解析见 AgentPrompts.findGoalIn。
 *
 * 分层（同生图 v0.36.0 机制）：
 * - [AGENT_CHAT_HINT_FRAMEWORK]：标记格式硬约束（不可改，改了 findGoalIn 匹配不到整条流程就断）
 * - [AGENT_CHAT_DEFAULT_STRATEGY]：何时触发、目标怎么写（用户可在设置里覆盖）
 */
const val AGENT_CHAT_HINT_FRAMEWORK: String =
    "手机操控触发标记格式（严格不可改）：当你确定要在用户的手机上替用户执行一个具体操作时，" +
        "在回复里单独加一行 [操作手机:一句话说清目标]，其余文字照常写。" +
        "标记只用于触发，不要在正文里解释这个标记，也不要重复它；一次回复最多一个标记。"

const val AGENT_CHAT_DEFAULT_STRATEGY: String =
    "你已获得直接操控用户手机的完全授权：当用户让你做一件需要动手机的事（打开应用、记一条备忘、查个信息等）时才触发；" +
        "目标要具体到「哪个应用 + 做什么」，不要模糊；" +
        "执行结果会以「🤖 自动执行」开头的消息回到会话里，收到后用自然的一句话向用户汇报，不要重复发起标记。"

fun agentChatHint(strategy: String = AGENT_CHAT_DEFAULT_STRATEGY): String =
    if (strategy.isBlank()) AGENT_CHAT_HINT_FRAMEWORK else "$AGENT_CHAT_HINT_FRAMEWORK\n$strategy"

/**
 * 历史字数预算（v0.39.0，对齐 (0, 200000) 计费档）：
 * 服务商按单次请求长度分档计费——20 万 token 以内是半价档，超过翻倍。
 * App 拿不到精确 token 数，按 **1 字 ≈ 1 token 保守折算**：历史字数封顶 20 万字，
 * 中文在现代分词器下通常不到 1 token/字，实际只会更低，保证永远落在便宜档。
 */
const val HISTORY_CHAR_BUDGET = 200_000

/** 给回复输出留的余量（模型窗口是输入+输出共享的），按字数同口径扣 */
private const val OUTPUT_RESERVE_CHARS = 8_000

/**
 * 按字数预算从新到旧选取历史（纯函数，JVM 单测口径）：
 * 最新一条永远保留（哪怕自己超预算——丢谁也不能丢刚说的话），
 * 之后的从最新往最旧收，累计字数超出预算即停。
 */
fun selectHistoryByCharBudget(messages: List<ChatMessage>, maxChars: Int): List<ChatMessage> {
    if (maxChars <= 0) return messages.takeLast(1)
    var budget = maxChars
    val picked = ArrayList<ChatMessage>(messages.size)
    for (m in messages.asReversed()) {
        val cost = m.content.length
        if (picked.isNotEmpty() && cost > budget) break
        picked.add(m)
        budget -= cost
    }
    return picked.asReversed()
}

/**
 * 单聊页 VM：每个 persona 一个独立会话（personaId 区分，消息互不混淆）。
 * - 持续观察 Room 消息：打卡回执等外部写入实时上屏（流式期间以本地流为准）
 * - 发送 → 持久化用户消息 → OpenAiClient 流式回复 → 节流上屏 → 完成后持久化
 * - system prompt = persona.systemPrompt + 档案注入说明 + 用户档案（ProfileFact）
 * - 对话风格按 persona 持久化（长按头像切换，重进保留）
 * - 请求失败（含 HTTP 错误）：不落库、给错误条 + 重试；已收到部分回复则保留已收内容
 */
class ChatViewModel(
    private val personaRepository: PersonaRepository,
    private val settingsRepository: SettingsRepository,
    private val db: AppDatabase,
    private val extractionEngine: ExtractionEngine,
    private val taskSuggestionHub: TaskSuggestionHub,
    private val personaId: Long,
    /** 生图落盘目录（应用私有 filesDir；JVM 单测注入临时目录） */
    private val filesDir: java.io.File,
    /** 应用级作用域：对话内触发的 Agent 挂这里，聊天页退出不掐断执行（v0.37.0） */
    private val appScope: CoroutineScope? = null,
) : ViewModel() {

    private val _activePersona = MutableStateFlow<Persona?>(null)
    val activePersona: StateFlow<Persona?> = _activePersona.asStateFlow()

    private val _uiState = MutableStateFlow(
        ChatUiState(messages = emptyList(), input = ChatDrafts.get(personaId))
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var draftWriteJob: kotlinx.coroutines.Job? = null

    init {
        // 草稿落盘恢复：进程被杀后重新进入会话，未发送输入仍在
        // （仅当内存缓存为空时应用，避免覆盖用户已键入的内容）
        viewModelScope.launch {
            runCatching {
                val saved = settingsRepository.chatDraft(personaId)
                if (saved.isNotEmpty() && _uiState.value.input.isEmpty()) {
                    _uiState.update { it.copy(input = saved) }
                    com.menghuan.app.ui.chat.ChatDrafts.set(personaId, saved)
                }
            }
        }
    }

    /** AI 任务建议（提炼产出，进程内存）：AI 气泡下方卡片展示，确认后消失 */
    val taskSuggestions: StateFlow<List<TaskSuggestion>> = taskSuggestionHub.forPersona(personaId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ===== 多接口（v0.35.0 起；v0.36.4 改为按会话独立）：长按头像只切换"本会话"的接口 =====

    val apiProfiles: StateFlow<List<ApiProfile>> = settingsRepository.apiProfiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 全局默认接口 id（设置页里切的那个；本会话没单独设时跟随它） */
    val globalProfileId: StateFlow<String> = settingsRepository.activeProfileId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_PROFILE_ID)

    /** 本会话实际生效的接口 id（覆盖优先，覆盖被删则回落全局） */
    val activeProfileId: StateFlow<String> = settingsRepository.effectiveProfileId(personaId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_PROFILE_ID)

    /** 本会话的独立覆盖 id；null = 跟随全局 */
    val apiOverrideId: StateFlow<String?> = settingsRepository.personaApiProfileId(personaId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 长按头像选某个接口 = 只给本会话设覆盖，不动全局 */
    fun switchApiProfile(id: String) = viewModelScope.launch {
        runCatching { settingsRepository.setPersonaApiProfile(personaId, id) }
    }

    /** 清除本会话覆盖，回跟随全局 */
    fun followGlobalApi() = viewModelScope.launch {
        runCatching { settingsRepository.setPersonaApiProfile(personaId, null) }
    }

    /** 生图接口是否可用（决定要不要给模型发生图指令） */
    val imageApiReady: StateFlow<Boolean> = settingsRepository.imageApiConfig
        .map { it.isComplete }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** AI 建议 → 写入任务（repeatDaily 取 daily，来源标记 ai），卡片随之消失 */
    fun addSuggestedTask(suggestion: TaskSuggestion) = viewModelScope.launch {
        runCatching {
            // 日期型记忆（v0.38.0）：AI 识别的到期日落到任务的 dueAt（当天 20:00，提醒阈值无关紧要）
            val dueAt = suggestion.dueDate.takeIf { it.isNotBlank() }?.let {
                runCatching {
                    java.time.LocalDate.parse(it).atTime(20, 0)
                        .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                }.getOrDefault(0L)
            } ?: 0L
            db.taskDao().upsert(
                TaskItem(
                    title = suggestion.title,
                    note = suggestion.note,
                    repeatDaily = suggestion.daily,
                    createdBy = "ai",
                    dueAt = dueAt,
                )
            )
            taskSuggestionHub.consume(personaId, suggestion)
        }
    }

    init {
        viewModelScope.launch {
            runCatching {
                // 重新打开即恢复：微信式删除的会话，从搜索/任意入口再进入时取消隐藏
                personaRepository.setHidden(personaId, false)
                val persona = personaRepository.getPersona(personaId)
                    ?: personaRepository.observePersonas().first().firstOrNull()
                _activePersona.value = persona
            }
        }
        // 一次性修复历史 bug 落库的 "null" 乱码（v0.12.1 前 OpenAiClient 把推理模型的
        // "content": null 增量读成字面量 "null" 拼进回复）：纯乱码的 AI 消息删除，
        // 开头混入乱码前缀的剥掉前缀。只动 assistant 消息，用户输入永不修改。
        // 整段放 IO 线程：长会话几千条消息逐条正则跑在主线程上，就是"进对话页卡一下"的元凶之一；
        // Regex 提出循环，不再每条消息重新编译一次
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val garbagePrefix = Regex("^(?:null)+")
                db.messageDao().observeConversation(personaId).first().forEach { m ->
                    if (m.isFromUser) return@forEach
                    val cleaned = m.content.replace(garbagePrefix, "").trim()
                    when {
                        cleaned.isEmpty() -> db.messageDao().delete(m.id)
                        cleaned != m.content -> db.messageDao().updateContent(m.id, cleaned)
                    }
                }
            }
        }
        // 持续观察持久化消息：任务页勾选生成的打卡回执、未来其他写入源都实时上屏。
        // 流式期间跳过（占位尾巴尚未落库，本地列表才是最新视图）；流结束后 DB 即为事实源。
        // 有消息即视为已读：会话列表未读点随之消除
        viewModelScope.launch {
            runCatching {
                db.messageDao().observeConversation(personaId).collect { persisted ->
                    if (persisted.isNotEmpty()) {
                        _uiState.update { if (it.isStreaming) it else it.copy(messages = persisted) }
                        runCatching { settingsRepository.markChatRead(personaId) }
                    }
                }
            }
        }
    }

    // ===== 对话内手机执行卡（v0.37.0）=====

    /** 手动收起执行卡（结束后摘要消息已落进会话，卡片不再有信息量） */
    fun dismissAgentCard() {
        _uiState.update { it.copy(agentRun = null) }
    }

    init {
        viewModelScope.launch {
            // 重进会话时执行还在跑（应用级作用域，不随聊天页退出）：重新挂上卡片
            (AgentRunner.state.value as? AgentRunner.State.Running)?.let { run ->
                if (AgentRunner.runningPersonaId == personaId) {
                    _uiState.update { state ->
                        if (state.agentRun == null) {
                            state.copy(agentRun = AgentRunUiState(goal = run.goal, logs = run.logs))
                        } else state
                    }
                }
            }
            // 只跟踪本会话发起的执行：任务页 ▶ 发起的（agentRun == null）不进聊天卡片
            AgentRunner.state.collect { s ->
                when (s) {
                    is AgentRunner.State.Running -> _uiState.update { state ->
                        state.agentRun?.let { ar ->
                            state.copy(agentRun = ar.copy(goal = s.goal, logs = s.logs, running = true))
                        } ?: state
                    }
                    is AgentRunner.State.Finished -> _uiState.update { state ->
                        state.agentRun?.let { ar ->
                            state.copy(
                                agentRun = ar.copy(
                                    running = false,
                                    success = s.success,
                                    summary = s.summary,
                                    steps = s.steps,
                                )
                            )
                        } ?: state
                    }
                    AgentRunner.State.Idle -> _uiState.update { it.copy(agentRun = null) }
                }
            }
        }
    }

    fun onInputChange(text: String) {
        _uiState.update { it.copy(input = text) }
        ChatDrafts.set(personaId, text)
        persistDraft(text)
    }

    /** 草稿落盘：600ms 去抖（每次击键都写 DataStore 太重） */
    private fun persistDraft(text: String) {
        draftWriteJob?.cancel()
        draftWriteJob = viewModelScope.launch {
            kotlinx.coroutines.delay(600)
            runCatching { settingsRepository.setChatDraft(personaId, text) }
        }
    }

    /** 兑现已读：会话可见期间每条新消息都刷新阅读时刻，会话列表未读点立即消除 */
    fun markRead() = viewModelScope.launch {
        runCatching { settingsRepository.markChatRead(personaId) }
    }

    /** v0.47.1：单聊页三点 → 编辑人设弹层的保存（人设管理页之外的第二入口） */
    fun savePersona(p: com.menghuan.app.data.model.Persona) = viewModelScope.launch {
        runCatching { personaRepository.save(p) }
    }

    // ---- v0.49.0：AI 回复语音朗读（右上角喇叭开关；音色用人设的通话音色）----

    private var readAloudJob: kotlinx.coroutines.Job? = null

    /** 喇叭关闭 / 用户新发消息时调用：立即掐掉朗读 */
    fun stopReadAloud() {
        readAloudJob?.cancel()
        readAloudJob = null
    }

    private fun readAloudIfEnabled(text: String) {
        viewModelScope.launch {
            val enabled = runCatching { settingsRepository.chatReadAloud.first() }.getOrDefault(false)
            if (!enabled) return@launch
            stopReadAloud() // 新回复打断旧朗读
            val clean = cleanForSpeech(text)
            if (clean.isBlank()) return@launch
            readAloudJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val voice = runCatching { settingsRepository.callVoiceFor(personaId).first() }
                    .getOrDefault("").ifBlank { "zh-CN-XiaoxiaoNeural" }
                val mp3 = runCatching {
                    com.menghuan.app.realtime.EdgeTtsClient(voice).synthesize(clean)
                }.getOrNull() ?: return@launch
                val pcm = com.menghuan.app.realtime.Mp3Decoder.decode(mp3) ?: return@launch
                com.menghuan.app.realtime.ReadAloudPlayer.play(pcm.samples, pcm.sampleRate)            }
        }
    }

    /** 朗读文本清洗：剥生成标记与 Markdown 记号，只留 TTS 读起来自然的话 */
    private fun cleanForSpeech(text: String): String = text
        .replace(Regex("\\[生图:[^\\]]*\\]"), "")
        .replace(Regex("\\[操作手机:[^\\]]*\\]"), "")
        .replace(Regex("[*#`>~]"), "")
        .trim()

    fun send() {
        val text = _uiState.value.input.trim()
        if (text.isEmpty() || _uiState.value.isStreaming) return
        sendText(text)
    }

    /** 重试 / 外部触发等场景：以给定文本直接发送（走完整落库 + 回复链路） */
    fun sendText(text: String) {
        if (text.isBlank() || _uiState.value.isStreaming) return
        send(
            ChatMessage(conversationId = personaId, isFromUser = true, content = text),
            clearInput = true,
        )
    }

    /**
     * 发图给 AI 看（v0.38.0 vision 输入）：图片已由 UI 层拷进应用私有目录，
     * [imagePath] = 绝对路径；[caption] = 发送时输入框里的话（可空，纯图也行）。
     */
    fun sendImage(imagePath: String, caption: String) {
        if (imagePath.isBlank() || _uiState.value.isStreaming) return
        send(
            ChatMessage(
                conversationId = personaId,
                isFromUser = true,
                content = caption.trim(),
                imagePath = imagePath,
                mediaKind = MediaKind.IMAGE.name,
            ),
            clearInput = true,
        )
    }

    /**
     * 发语音（v0.43.0）：[audioPath] 已由 UI 层录好落盘，[durationMs] 供语音条显示，
     * [transcript] 是本地语音识别出的文字——**它只用于喂给模型**，
     * 消息列表渲染语音条、不显示这段文字（用户明确要求）。
     * 识别失败时 transcript 为空，`reply()` 里会退化成"（用户发了一段语音）"提示。
     */
    /**
     * 语音转文字（v0.43.1 新增）：**用本会话生效的 AI 接口做云端转写**。
     *
     * 为什么放在 ViewModel 而不是 UI 层：转写需要当前会话的 API 配置
     * （`apiConfigFor(personaId)`，长按头像可切换、只影响本会话），
     * 而配置只在这里能干净地取到。
     *
     * 返回 null = 走不通（没配接口 / 服务商不支持 `/audio/transcriptions` / 网络失败），
     * 由调用方降级到系统识别器（见 `SpeechToText.transcribe`）。
     */
    suspend fun cloudTranscribe(file: java.io.File): String? {
        val config = runCatching { settingsRepository.apiConfigFor(personaId).first() }.getOrNull()
            ?: return null
        if (!config.isComplete) return null
        return runCatching {
            OpenAiClient(config.baseUrl, config.apiKey, config.model).transcribe(file)
        }.getOrNull()
    }

    fun sendVoice(audioPath: String, durationMs: Long, transcript: String) {
        if (audioPath.isBlank() || _uiState.value.isStreaming) return
        send(
            ChatMessage(
                conversationId = personaId,
                isFromUser = true,
                content = transcript.trim(),
                audioPath = audioPath,
                audioDurationMs = durationMs,
                mediaKind = MediaKind.AUDIO.name,
            ),
            clearInput = false,
        )
    }

    /**
     * 发自定义表情（v0.43.0）：点一下立刻发出，不插输入框。
     * 复用 imagePath 通道（vision 也能看到这张图），mediaKind 标记成 STICKER（v0.45.0）——
     * 历史里按"小气泡、不可点开"渲染，与普通图片区分；模型看到的是图，语义不变。
     */
    fun sendSticker(path: String) {
        if (path.isBlank() || _uiState.value.isStreaming) return
        send(
            ChatMessage(
                conversationId = personaId,
                isFromUser = true,
                content = "",
                imagePath = path,
                mediaKind = MediaKind.STICKER.name,
            ),
            clearInput = false,
        )
    }

    /**
     * 发文件（v0.43.0）：[extractedText] 是本地抽取的正文（可为空）。
     * 非空时拼进 `content` 让模型能读到内容；为空则只留文件名，模型只能知道"用户发了个文件"。
     */
    fun sendFile(path: String, name: String, size: Long, extractedText: String) {
        if (path.isBlank() || _uiState.value.isStreaming) return
        send(
            ChatMessage(
                conversationId = personaId,
                isFromUser = true,
                content = extractedText.trim(),
                attachmentPath = path,
                attachmentName = name,
                attachmentSize = size,
                mediaKind = MediaKind.FILE.name,
            ),
            clearInput = false,
        )
    }

    /** 发送共用链路：乐观上屏 → 落库 → LLM 回复 */
    private fun send(userMsg: ChatMessage, clearInput: Boolean) {
        stopReadAloud() // 用户开口（新消息）掐掉上一条朗读
        _uiState.update {
            it.copy(
                input = if (clearInput) "" else it.input,
                messages = it.messages + userMsg,
                isStreaming = true,
                generating = true,
                error = null,
            )
        }
        ChatDrafts.clear(personaId)
        persistDraft("")
        viewModelScope.launch {
            val saved = runCatching { db.messageDao().insert(userMsg) }
            if (saved.isFailure) {
                _uiState.update {
                    it.copy(
                        isStreaming = false,
                        generating = false,
                        error = "消息保存失败：${saved.exceptionOrNull()?.message ?: "存储异常"}",
                        errorKind = ErrorBar.KIND_INFO,
                    )
                }
                return@launch
            }
            reply()
        }
    }

    /** 删除单条已落库消息（长按气泡菜单）；观察者流会实时重发，UI 自动更新 */
    fun deleteMessage(message: ChatMessage) = viewModelScope.launch {
        if (message.id <= 0) return@launch // 种子演示消息不在 DB，不可删
        runCatching { db.messageDao().delete(message.id) }
    }

    /** 上次请求失败后重试（用户消息已在历史中） */
    fun retry() {
        if (_uiState.value.isStreaming) return
        _uiState.update { it.copy(isStreaming = true, generating = true, error = null) }
        viewModelScope.launch { reply() }
    }

    /** 组装 system prompt 并流式获取回复 */
    private suspend fun reply() {
        val persona = _activePersona.value
        // v0.36.4：按本会话的独立接口取配置（未设覆盖时回落全局）
        val config = try {
            settingsRepository.apiConfigFor(personaId).first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update { it.copy(isStreaming = false, generating = false, error = "配置读取失败：${e.message ?: "存储异常"}", errorKind = ErrorBar.KIND_API) }
            return
        }

        // 未配置 API 或人设缺失：模拟回复（演示模式）
        if (persona == null || !config.isComplete) {
            appendMockReply()
            return
        }

        val facts = try {
            db.profileFactDao().observeAll().first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        // 无人设（systemPrompt 为空）= 普通 AI：不套人设、不叠对话风格，只带用户档案；
        // 对话风格（tone）是人设之上的语气维度，没有人设就没有叠加对象
        val personaPrompt = persona.systemPrompt.trim()
        val systemPrompt = buildString {
            if (personaPrompt.isNotEmpty()) {
                append(personaPrompt)
            }
            if (facts.isNotEmpty()) {
                if (personaPrompt.isNotEmpty()) append("\n\n")
                // 档案 = 背景参考，包装语可在设置里改（默认明确"不要主动复述档案"）
                val preamble = try {
                    settingsRepository.profilePreamble.first()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    com.menghuan.app.data.AiPrompts.DEFAULT_PROFILE_PREAMBLE
                }
                append(preamble)
                facts.forEach { append("\n- ").append(it.key).append("：").append(it.value) }
            }
            // 生图能力说明：只有生图接口配齐才告诉模型它能生图，否则别诱导用户白等一张图
            val imageReady = try {
                settingsRepository.imageApiConfig.first().isComplete
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (imageReady) {
                if (isNotEmpty()) append("\n\n")
                // 提示词策略可由用户在「设置 · AI 提示词 · 生图触发策略」里覆盖；框架部分硬编码
                val strategy = try {
                    settingsRepository.imageHintStrategy.first()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    IMAGE_HINT_DEFAULT_STRATEGY
                }
                append(imageHint(strategy))
            }
            // 手机操控能力说明（v0.37.0）：总开关打开且无障碍服务在线才注入，
            // 否则模型提了标记也执行不了，白白吊用户胃口
            val agentReady = try {
                settingsRepository.agentChatEnabled.first() && AgentAccessibilityService.isRunning()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (agentReady) {
                if (isNotEmpty()) append("\n\n")
                val strategy = try {
                    settingsRepository.agentChatStrategy.first()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AGENT_CHAT_DEFAULT_STRATEGY
                }
                append(agentChatHint(strategy))
            }
            if (isEmpty()) {
                val base = try {
                    settingsRepository.basePrompt.first()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    com.menghuan.app.data.AiPrompts.DEFAULT_BASE_PROMPT
                }
                append(base)
            }
        }
        // 历史取持久化消息：UI 种子对话是展示用假数据，绝不能发给 LLM 当真历史；
        // 同时剥离历史 bug 遗留的 "null" 乱码前缀，纯乱码消息整条剔除。
        // 带图的用户消息保留（没写字幕也发图，v0.38.0 vision 输入）
        val history = try {
            db.messageDao().observeConversation(personaId).first()
                .mapNotNull { m ->
                    if (!m.isFromUser) {
                        val cleaned = m.content.replace(Regex("^(?:null)+"), "").trim()
                        if (cleaned.isEmpty() && m.imagePath.isBlank()) return@mapNotNull null
                        // 已生成过的图片在历史里标注成过去完成时：原始提示词留在历史里
                        // 会诱导模型照葫芦画瓢继续自发输出 [生图:…]（用户实测：生成一次后频繁自发生图）
                        if (m.imagePath.isNotBlank()) {
                            return@mapNotNull m.copy(content = "（此前已生成过一张图片：${cleaned.take(60)}）")
                        }
                        m.copy(content = cleaned)
                    } else {
                        // v0.43.0：语音 / 文件在发给模型前补上"这是什么"的说明，
                        // 否则识别失败或抽不出文字时模型只收到一条空消息，完全不知道发生了什么。
                        when (MediaKind.fromLegacy(m.mediaKind, m.imagePath)) {
                            MediaKind.AUDIO -> {
                                val said = m.content.trim()
                                if (said.isEmpty()) {
                                    m.copy(content = "（我发了一段语音，但这边没能转成文字）")
                                } else {
                                    m.copy(content = "（我发了一段语音，说的是：$said）")
                                }
                            }
                            MediaKind.FILE -> {
                                val body = m.content.trim()
                                val name = m.attachmentName.ifBlank { "一个文件" }
                                if (body.isEmpty()) {
                                    m.copy(content = "（我发了一个文件：$name，内容这边读不出来）")
                                } else {
                                    m.copy(content = "（我发了文件「$name」，内容如下）\n$body")
                                }
                            }
                            else -> m
                        }
                    }
                }
                .filter {
                    it.content.isNotBlank() || it.imagePath.isNotBlank()
                }
                .let { selected ->
                    // (0, 200000) 计费档（v0.39.0）：历史字数 = 20 万 - system prompt 字数 - 回复余量，
                    // 从最新往最旧收，聊到 20 万字之前就是全量记忆
                    val budget = (HISTORY_CHAR_BUDGET - systemPrompt.length - OUTPUT_RESERVE_CHARS)
                        .coerceAtLeast(0)
                    selectHistoryByCharBudget(selected, budget)
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update { it.copy(isStreaming = false, generating = false, error = "历史读取失败：${e.message ?: "存储异常"}", errorKind = ErrorBar.KIND_RETRY) }
            return
        }
        // 本次请求里是否带了用户图片（模型不支持 vision 时 400，提示语要能对上）
        val hadImage = history.any { it.isFromUser && it.imagePath.isNotBlank() }

        val sb = StringBuilder()
        // 思考链全文（v0.41.4）：与正文分开收，一起落库，只在长按菜单里查看
        val thinking = StringBuilder()
        var usageTokens: Int? = null
        var streamError: Throwable? = null
        // v0.41.4 后台生成一次性发送：不再有打字机追赶循环——正文在后台收完为止，
        // 期间 UI 只挂三点气泡（isStreaming/generating），收到 [DONE] 后整段落库上屏。
        // 这样用户看到的是"思考 → 完整回复弹出"，而不是逐字吐字（用户明确要求）。
        val collectJob = viewModelScope.launch {
            try {
                OpenAiClient(config.baseUrl, config.apiKey, config.model)
                    .streamEvents(systemPrompt, history) { path ->
                        // 用户发的图现场降采样转 base64（AI 生成图是 assistant 消息，不进请求）
                        com.menghuan.app.data.VisionImage.encodeDataUrl(path)
                    }
                    .collect { ev ->
                        when (ev) {
                            is com.menghuan.app.llm.StreamEvent.Delta -> sb.append(ev.text)
                            is com.menghuan.app.llm.StreamEvent.Reasoning -> thinking.append(ev.text)
                            is com.menghuan.app.llm.StreamEvent.Usage -> usageTokens = ev.tokens
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                streamError = e
            }
        }
        collectJob.join()
        val reasoning = thinking.toString().trim()
        recordUsage(usageTokens)
        val failure = streamError
        if (failure is ApiException) {
            // HTTP 失败：一个 token 都没收到 → 错误条 + 按状态码分级，不落库
            if (sb.isEmpty()) {
                _uiState.update {
                    it.copy(
                        isStreaming = false,
                        generating = false,
                        error = friendlyApiError(failure, hadImage),
                        errorKind = apiErrorKind(failure.code),
                    )
                }
                return
            }
            // 已收到部分回复：保留已收内容并正常落库
        } else if (failure != null) {
            if (sb.isEmpty()) {
                _uiState.update {
                    it.copy(
                        isStreaming = false,
                        generating = false,
                        error = "回复失败：${failure.message ?: "网络异常"}",
                        errorKind = ErrorBar.KIND_RETRY,
                    )
                }
                return
            }
            // 已收到部分回复：保留已收内容并正常落库
        }
        if (sb.isEmpty()) {
            // 服务端 200 但没有任何流式内容（网关错误页 / 非 SSE 响应）：
            // 明确报错而不是静默复位，否则用户不知道发生了什么
            _uiState.update {
                it.copy(
                    isStreaming = false,
                    generating = false,
                    error = "回复为空：请检查模型名是否正确，或稍后重试",
                    errorKind = ErrorBar.KIND_RETRY,
                )
            }
            return
        }
        // 落库并返回原始回复（含 [生图:…] 标记）：文字已剥标记落库，
        // 这里把原串带回去供生图解析
        val rawReply = persistReply(sb, reasoning)
        finishStream()
        readAloudIfEnabled(rawReply)
        // 生图（v0.35.0）：模型按 system prompt 约定输出 [生图:提示词] 标记时，
        // 文字已剥掉标记落库，这里再把图生出来落盘并挂成一条图片消息
        maybeGenerateImage(rawReply)
        // 对话内操控手机（v0.37.0）：回复里带 [操作手机:目标] 标记 → 直接启动 AgentLoop
        maybeRunAgent(rawReply)
        // 提炼流水线：攒够增量消息后由引擎在应用级作用域自行决定是否调用 LLM
        extractionEngine.maybeExtract(personaId)
    }

    /**
     * 回复里带 `[操作手机:目标]` → 直接启动 AgentLoop（用户授了完全权限，无确认卡）。
     * 决策循环用本会话生效的接口；执行挂应用级作用域（Agent 要切走前台，聊天页退出不掐断）；
     * 摘要落库走 onFinish 回调（也在应用级），VM 销毁不丢结果。
     * 总开关关闭 / 无障碍未开 / 已有执行在跑：提示但不打断已落库的文字。
     */
    private suspend fun maybeRunAgent(rawReply: String) {
        val goal = AgentPrompts.findGoalIn(rawReply) ?: return
        val scope = appScope ?: return
        val enabled = runCatching { settingsRepository.agentChatEnabled.first() }.getOrDefault(true)
        val api = runCatching { settingsRepository.apiConfigFor(personaId).first() }.getOrNull()
        // 前置守卫（纯函数）：分级提示——无障碍给"去开启"、API 给"去配置"，其余纯提示不误导重试
        val blocked = AgentChatGate.decide(
            enabled = enabled,
            serviceRunning = AgentAccessibilityService.isRunning(),
            alreadyRunning = AgentRunner.state.value is AgentRunner.State.Running,
            apiComplete = api?.isComplete == true,
        )
        if (blocked != null) {
            _uiState.update { it.copy(error = blocked.first, errorKind = blocked.second) }
            return
        }
        _uiState.update { it.copy(agentRun = AgentRunUiState(goal = goal)) }
        AgentRunner.start(
            scope = scope,
            settingsRepository = settingsRepository,
            goal = goal,
            api = api,
            autoConfirm = true,
            personaId = personaId,
            onFinish = { result ->
                // 摘要进会话 = 打卡回执同款语义（用户侧消息，下一轮 LLM 可感知、可汇报）
                val text = "🤖 自动执行「$goal」${if (result.success) "完成" else "未完成"}" +
                    "（${result.steps} 步）：${result.summary}"
                runCatching {
                    db.messageDao().insert(
                        ChatMessage(conversationId = personaId, isFromUser = true, content = text)
                    )
                }
                // 卡片收起时机 = state 回 Idle（本 VM 的收集器跟进；VM 已销毁也无妨）
                AgentRunner.reset()
            },
        )
    }

    /**
     * 回复里带 `[生图:提示词]` → 调生图接口，图片落盘到本机 filesDir，
     * 再插入一条 imagePath 消息（气泡渲染图片，content 存提示词）。
     * 生图接口未配置或调用失败：只提示，不影响已落库的文字。
     */
    private suspend fun maybeGenerateImage(rawReply: String) {
        val client = imageClient()
        val prompt = client.findPromptIn(rawReply) ?: return
        if (!client.isConfigured()) {
            _uiState.update { it.copy(error = "回复里带了生图请求，但生图接口未配置（设置 · API 配置）", errorKind = ErrorBar.KIND_API) }
            return
        }
        _uiState.update { it.copy(isGeneratingImage = true, imagePrompt = prompt) }
        val path = client.generate(prompt, ImageGenerationClient.imagesDir(imagesDir))
        _uiState.update { it.copy(isGeneratingImage = false, imagePrompt = "") }
        // 生图记录（v0.40.0）：成功失败都留痕——何时、哪个会话发起、用的哪个模型、什么提示词。
        // 失败不留图片文件，但记录必须有，图库里可追溯。
        runCatching {
            val modelName = runCatching { settingsRepository.imageApiConfig.first().model }.getOrDefault("")
            val personaName = runCatching { db.personaDao().getById(personaId)?.name }.getOrNull() ?: "未知会话"
            db.imageGenerationDao().insert(
                com.menghuan.app.data.model.ImageGenerationRecord(
                    personaId = personaId,
                    personaName = personaName,
                    model = modelName,
                    prompt = prompt,
                    success = path != null,
                    imagePath = path.orEmpty(),
                )
            )
        }
        if (path == null) {
            _uiState.update { it.copy(error = "生图失败：接口未配置或调用失败", errorKind = ErrorBar.KIND_RETRY) }
            // 刚才那句「（正在为你生成图片）」占位别永远停在"正在生成"：改写成失败事实
            runCatching {
                db.messageDao().observeConversation(personaId).first().lastOrNull { m ->
                    !m.isFromUser && m.content == IMAGE_ONLY_PLACEHOLDER
                }?.let { m -> db.messageDao().updateContent(m.id, "（图片生成失败）") }
            }
            return
        }
        withContext(NonCancellable) {
            runCatching {
                db.messageDao().insert(
                    ChatMessage(
                        conversationId = personaId,
                        isFromUser = false,
                        content = prompt,
                        imagePath = path,
                    )
                )
            }
        }
    }

    private val imagesDir: java.io.File
        get() = ImageGenerationClient.imagesDir(filesDir)

    /**
     * 流结束后落库 assistant 回复；NonCancellable 保证已取消的作用域里也能写完。
     * 若回复里含 `[生图:…]` / `[操作手机:…]` 标记，落库前先把标记剥掉（标记只是给 App 的信号，
     * 不该出现在用户看到的文字里），图另由 [maybeGenerateImage] 挂成图片消息，
     * 手机操作另由 [maybeRunAgent] 起执行卡。
     * [reasoning] = 思考链全文（可为空），随正文一起落库，只在长按菜单里按需查看。
     */
    private suspend fun persistReply(sb: StringBuilder, reasoning: String = ""): String {
        if (sb.isEmpty()) return ""
        val raw = sb.toString()
        val imgClient = imageClient()
        val hasImageMarker = imgClient.findPromptIn(raw) != null
        val text = AgentPrompts.stripMarker(imgClient.stripMarker(raw)).ifBlank {
            // 整条回复只有标记、没有正文：给一句占位（图/执行卡随后接管观感）
            if (hasImageMarker) IMAGE_ONLY_PLACEHOLDER else AGENT_ONLY_PLACEHOLDER
        }
        withContext(NonCancellable) {
            runCatching {
                db.messageDao().insert(
                    ChatMessage(
                        conversationId = personaId,
                        isFromUser = false,
                        content = text,
                        reasoning = reasoning,
                    )
                )
            }
        }
        return raw
    }

    /** 生图客户端（每次现取配置：用户可能在设置里改过） */
    private suspend fun imageClient(): ImageGenerationClient {
        val cfg = runCatching { settingsRepository.imageApiConfig.first() }.getOrNull()
        return ImageGenerationClient(
            cfg?.baseUrl.orEmpty(),
            cfg?.apiKey.orEmpty(),
            cfg?.model.orEmpty(),
        )
    }

    /** API 错误 → 用户能看懂、知道下一步做什么的文案；带图时 400 顺带提示 vision 可能不支持 */
    private fun friendlyApiError(e: ApiException, hadImage: Boolean = false): String {
        val base = when (e.code) {
            401, 403 -> "API Key 无效或无权限，请在「设置 · API 配置」检查"
            404 -> "接口地址或模型名不存在，请检查配置"
            429 -> "请求太频繁或额度不足，稍后再试"
            in 500..599 -> "服务端暂时不可用（${e.code}），稍后再试"
            else -> "请求失败（${e.code}）"
        }
        return if (hadImage && e.code == 400) "$base · 也可能当前模型不支持看图，试试纯文字或换个模型" else base
    }

    /** 按状态码定错误条动作：Key/配置问题给"去配置"，其余给"重试" */
    private fun apiErrorKind(code: Int): String =
        if (code == 401 || code == 403 || code == 404) ErrorBar.KIND_API else ErrorBar.KIND_RETRY

    /** 用量记账：服务商回了 usage 才记，拿不到不猜 */
    private fun recordUsage(tokens: Int?) {
        if (tokens == null) return
        viewModelScope.launch {
            runCatching { com.menghuan.app.data.UsageTracker.add(settingsRepository, tokens) }
        }
    }

    /**
     * 结束一轮生成：回读落库后的完整消息列表，清掉流式/生成中标记。
     * v0.41.4 起正文不再走流式尾巴，这里就是"整段回复一次性上屏"的落点。
     */
    private suspend fun finishStream() {
        val persisted = runCatching { db.messageDao().observeConversation(personaId).first() }
            .getOrDefault(emptyList())
        _uiState.update {
            it.copy(
                isStreaming = false,
                generating = false,
                messages = persisted.ifEmpty { it.messages },
            )
        }
    }

    private suspend fun appendMockReply() {
        val mock = when (personaId) {
            2L -> "很好。记住这个承诺，我会检查。"
            3L -> "那么，今晚你愿意迈出的最小一步是什么？"
            else -> "记下了。这事今晚复盘细聊。"
        }
        val msg = ChatMessage(conversationId = personaId, isFromUser = false, content = mock)
        _uiState.update { it.copy(messages = it.messages + msg, isStreaming = false, generating = false) }
        runCatching { db.messageDao().insert(msg) }
    }

    companion object {
        fun factory(personaId: Long) = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MenghuanApp
                ChatViewModel(
                    app.personaRepository,
                    app.settingsRepository,
                    app.db,
                    app.extractionEngine,
                    app.taskSuggestionHub,
                    personaId,
                    app.filesDir,
                    app.applicationScope,
                )
            }
        }
    }
}
