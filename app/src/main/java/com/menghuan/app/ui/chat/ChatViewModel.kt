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
import com.menghuan.app.data.TtsClients
import com.menghuan.app.data.TtsConfig
import com.menghuan.app.data.TtsVoices
import com.menghuan.app.data.VoiceNoteMarker
import com.menghuan.app.data.VoiceRef
import com.menghuan.app.data.db.AppDatabase
import com.menghuan.app.agent.AgentAccessibilityService
import com.menghuan.app.agent.AgentPrompts
import com.menghuan.app.agent.AgentRunner
import com.menghuan.app.data.model.ChatMessage
import com.menghuan.app.data.model.MediaKind
import com.menghuan.app.data.model.Persona
import com.menghuan.app.data.model.TaskItem
import com.menghuan.app.data.model.isEffectivelyDone
import com.menghuan.app.data.model.isEffectivelyFailed
import com.menghuan.app.llm.ApiException
import com.menghuan.app.llm.NetworkStack
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
import kotlinx.coroutines.delay
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
    /**
     * 正在朗读哪一条消息（v0.59.0，null = 没在念）。
     * 长按菜单的「朗读」和「自动朗读 AI 回复」共用这一个状态：**同一时刻只念一条**，
     * 气泡上的图标因此能变成「停止」，用户不用猜"怎么停"。
     */
    val readingId: Long? = null,
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

/** 跨页面的"正在生成"快照（[ChatGenerationBus] 用） */
data class ChatGenerationState(
    /** 正在等模型回复的会话（同一时刻可能不止一个会话在跑） */
    val textBusy: Set<Long> = emptySet(),
    /** 正在生图的会话 → 画面提示词（生图动画卡要用） */
    val imageBusy: Map<Long, String> = emptyMap(),
)

/**
 * 「正在生成」的跨页面登记处（v0.57）。
 *
 * ## 为什么需要它
 * `ChatViewModel` 绑在 `chat/{personaId}` 这条导航记录上——**退出聊天页就被销毁**、
 * `viewModelScope` 随之取消，正在跑的流式请求连同还没落库的回复一起消失（用户反馈：
 * "退出对话页面后 AI 生成就中断了"）。修法分两步：
 * 1. 生成链路改挂**应用级作用域**（见 `ChatViewModel.generationScope`），退出页面不掐断；
 * 2. 但"哪个会话正在生成"这个事实**不能只放在 VM 里**（VM 都没了）——放这里。
 *    重新进入会话时，新 VM 一挂上就知道"它还在生成"，把三点气泡接回来。
 *
 * ## 为什么这里只登记状态、不跑逻辑
 * 与 `AgentRunner`（自己跑逻辑 + 自己登记状态）不同：生成函数 `reply()` 与 VM 的
 * `_activePersona` / `_uiState` / 一堆私有成员耦合很深，整体搬出去是纯粹的白折腾。
 * 所以生成仍留在 VM 里跑（只是换了作用域），这个对象只当一个**不随页面走的信箱**。
 */
object ChatGenerationBus {

    private val _state = MutableStateFlow(ChatGenerationState())
    val state: StateFlow<ChatGenerationState> = _state.asStateFlow()

    /** 已销毁的会话里失败过的提示：回来后补显示一次（会话 id → 文案 to 错误条类型），显示完即清 */
    private val pendingErrors = MutableStateFlow<Map<Long, Pair<String, String>>>(emptyMap())

    fun beginText(conversationId: Long) {
        _state.update { it.copy(textBusy = it.textBusy + conversationId) }
    }

    fun endText(conversationId: Long) {
        _state.update { it.copy(textBusy = it.textBusy - conversationId) }
    }

    fun beginImage(conversationId: Long, prompt: String) {
        _state.update { it.copy(imageBusy = it.imageBusy + (conversationId to prompt)) }
    }

    fun endImage(conversationId: Long) {
        _state.update { it.copy(imageBusy = it.imageBusy - conversationId) }
    }

    fun postError(conversationId: Long, message: String, kind: String) {
        pendingErrors.update { it + (conversationId to (message to kind)) }
    }

    /** 取走并清空该会话的待显示错误（没有则返回 null），保证只补显示一次 */
    fun takeError(conversationId: Long): Pair<String, String>? {
        val hit = pendingErrors.value[conversationId] ?: return null
        pendingErrors.update { it - conversationId }
        return hit
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

/** 回复里只有 【语音条】 标记、没有正文时的占位文案（没东西可合成，只能退回文字） */
const val VOICE_ONLY_PLACEHOLDER = "（这条没说出内容）"

/**
 * 整条回复只有 `<add:…>` 时的占位（v0.70.0）。
 *
 * 为什么不能留空：记忆已经静静进了暂存区，而屏幕上是一条空白消息 ——
 * 用户看到的就是"AI 没反应"，实际发生的是一件他不知道的事。
 * 这一句要**说得像 TA 自己在想**，不是系统日志（与 `<add:>` 的"脑内记忆"口径一致）。
 */
const val MEMORY_ONLY_PLACEHOLDER = "💭 记下了"

/**
 * 整条回复只有 `[动态:…]` / `[动态#id:…]` 时的占位（v0.73.0）。
 * 与 [MEMORY_ONLY_PLACEHOLDER] 同一条理由：帖子已经落进时间线了，
 * 聊天里留一条空白消息 = 用户以为 TA 没反应。
 */
const val MOMENT_ONLY_PLACEHOLDER = "🌙 去发了条动态"

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
 * 生图能力提示（只在生图模型配齐时注入 system prompt）。
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
 * AI 语音条能力提示（v0.50.0；总开关打开且 TTS 已配置时注入 system prompt）。
 * 与生图、手机操控同构的单行标记协议，解析见 [com.menghuan.app.data.VoiceNoteMarker]。
 *
 * 分层（同生图 v0.36.0 机制）：
 * - [VOICE_NOTE_FRAMEWORK]：标记格式硬约束（不可改，改了 parse 匹配不到整条流程就断）
 * - [VOICE_NOTE_DEFAULT_STRATEGY]：什么时候该用说的、什么时候必须打字（用户可在设置里覆盖）
 */
const val VOICE_NOTE_FRAMEWORK: String =
    "语音条触发标记格式（严格不可改）：当你决定这条回复用「说」的而不是打字时，" +
        "在回复里单独加一行 【语音条】，正文照常写。App 会把正文合成为一条语音气泡，" +
        "正文不会另外显示（用户长按语音条可以看到文字）。" +
        "标记只用于触发合成，不要在正文里解释这个标记，也不要重复它；一次回复最多一个标记。"

/**
 * 语音条**策略**（v0.50.0 建；v0.56.17 补规则）。
 *
 * ⚠️ v0.56.17 背景：用户报「AI 从来不发语音条，我叫它发也不发」（无报错、语音合成已配齐）。
 * 逐环节排查（MiMo 协议实现 / 能力说明注入条件 / SSE 解析 / 标记解析 / 消息升级 / 气泡渲染）
 * **都没找到确定性缺陷**；用户随后在**同一个旧包**上又自行恢复了——说明"由模型自行判断何时用语音"
 * 本身带随机性，不是一条必现的 bug（别拿它当已确认结论）。
 * 但旧文案确实有个真空：整段只写"要克制 / 不要连续发 / 不适合说的用文字"，
 * **一句都没写"用户明确要求发语音时该怎么办"**，而 [VOICE_NOTE_FRAMEWORK] 又把决定权交给模型
 * （"当你决定这条回复用说的"）——模型读到的净效果偏"别用"。
 * （对照：生图 / 打电话的提示词都带"用户明确要求时"的硬规则。）
 * 所以这次把规则写全：① 用户明确要求 → 必须带标记；② 自己挑时机 → 短情绪句放心用；
 * ③ 长回答 / 清单一律文字。**别再把它改回"只讲克制"**。
 */
const val VOICE_NOTE_DEFAULT_STRATEGY: String =
    "语音条是你可以主动使用的能力，分两种情况：\n" +
        "① 用户明确要求你发语音时（发条语音 / 用语音说 / 说给我听 / 语音回我），" +
        "这条回复**必须**带语音条——在回复里单独加一行 【语音条】，正文就写你要说的那句话；" +
        "不要只在正文里用文字答应「好，我发语音给你」却不带标记（那样用户什么也收不到）。\n" +
        "② 你自己挑时机时：短、口语化、带情绪的回复（问候、安慰、鼓励、道晚安、一句提醒）" +
        "这类一两句话能说完的，用语音条很自然；长回答、清单、步骤说明、" +
        "任何用户需要照着做或反复看的内容一律用文字。\n" +
        "用语音条不会让用户看不到你写的话（长按语音条能看到正文），所以该用就用，不必顾虑。" +
        "同一段对话里不要连续每条都发语音条（第 ① 种情况除外）；" +
        "用户说过「打字告诉我」「发文字」之后就一直用文字，直到他再次要求语音。" +
        "正文不要写「我发条语音给你」这类旁白，直接说要说的话。"

fun voiceNoteHint(strategy: String = VOICE_NOTE_DEFAULT_STRATEGY): String =
    if (strategy.isBlank()) VOICE_NOTE_FRAMEWORK else "$VOICE_NOTE_FRAMEWORK\n$strategy"

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
    /** v0.53.5：口头约定「待会打给我」要排闹钟，排钟要 Context（JVM 单测传 null = 跳过排钟） */
    private val appContext: android.content.Context? = null,
) : ViewModel() {

    private val _activePersona = MutableStateFlow<Persona?>(null)
    val activePersona: StateFlow<Persona?> = _activePersona.asStateFlow()

    private val _uiState = MutableStateFlow(
        ChatUiState(messages = emptyList(), input = ChatDrafts.get(personaId))
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var draftWriteJob: kotlinx.coroutines.Job? = null

    /**
     * 生成链路用的作用域（v0.57）：**优先应用级作用域**——生成挂在这里，退出聊天页
     * （VM 被销毁、viewModelScope 被取消）也不会被掐断；JVM 单测直接 new 本类、
     * 没有应用级作用域时退回 viewModelScope，行为与旧版一致。
     */
    private val generationScope: CoroutineScope get() = appScope ?: viewModelScope

    /**
     * 本 VM 是否已被销毁（用户退出了聊天页）。
     *
     * 生成还在应用级作用域里跑，这时若报错，[setError] 写进 `_uiState` 没人看得到 ——
     * 于是转投 [ChatGenerationBus]，等用户回到会话时补显示一次（项目铁律：失败不许静默）。
     */
    @Volatile
    private var viewModelCleared = false

    override fun onCleared() {
        viewModelCleared = true
        // v0.59.0：离开会话就把正在念的停掉并释放 MediaPlayer。
        // 生成可以留在后台继续跑（v0.57），但**声音不该跟着用户跑到别的页面去**。
        runCatching { stopReading() }
        super.onCleared()
    }

    /**
     * 统一的失败出口：复位流式标记 + 上屏错误条；**若本 VM 已销毁**，同时把这条错误
     * 投到 [ChatGenerationBus]，让重新进入会话时能补看到（否则异步失败就等于没发生）。
     */
    private fun setError(message: String, kind: String) {
        _uiState.update {
            it.copy(isStreaming = false, generating = false, error = message, errorKind = kind)
        }
        if (viewModelCleared) ChatGenerationBus.postError(personaId, message, kind)
    }

    /** 回读落库消息并上屏，**不动流式标记**（重进会话接上别人的生成时用，见 init） */
    private suspend fun reloadMessages() {
        val persisted = runCatching { db.messageDao().observeConversation(personaId).first() }
            .getOrDefault(emptyList())
        if (persisted.isNotEmpty()) _uiState.update { it.copy(messages = persisted) }
    }

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

    /** 是否至少有一个可用生图模型（决定要不要给模型发生图指令） */
    val imageApiReady: StateFlow<Boolean> = settingsRepository.imageProfiles
        .map { list -> list.any { it.isComplete } }
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
            }
        }
        // 人设**持续观察**（v0.56.14 修"编辑人设保存后回到对话页不生效"）：
        // 原来只在 init 里 getPersona 读一次，之后三点菜单 → 编辑人设 → 保存回到对话页，
        // 名字/头像/设定词全是旧的，要退到消息页重进（ViewModel 重建）才刷新。
        // 改成 Room observe 驱动即改即生效；指定 id 找不到时回落列表第一个
        // （保持原一次性读的兜底语义：deep link/旧入口可能传已不存在的 id）。
        viewModelScope.launch {
            runCatching {
                personaRepository.observePersonas().collect { list ->
                    _activePersona.value = list.firstOrNull { it.id == personaId } ?: list.firstOrNull()
                }
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
        // v0.57：接上"跨页面还在跑的那一轮生成"。生成挂在应用级作用域，退出聊天页不停；
        // 重新进来时这里做两件事：① 把三点气泡/生图卡接回来；② 补显示离开期间那次失败。
        viewModelScope.launch {
            // 上一次生成在别处（页面已销毁）失败时留下的错误：补显示一次
            ChatGenerationBus.takeError(personaId)?.let { (message, kind) ->
                _uiState.update { it.copy(error = message, errorKind = kind) }
            }
            // 本会话是否曾由总线接上过生成：只有"接过又结束"才需要收尾，
            // 否则别人的生成状态一变就会误伤本会话刚发出去的那条消息
            var wasBusy = false
            ChatGenerationBus.state.collect { bus ->
                val textMine = personaId in bus.textBusy
                if (textMine) {
                    if (!_uiState.value.isStreaming) {
                        _uiState.update { it.copy(isStreaming = true, generating = true, error = null) }
                        // 流式期间 DB 观察会被跳过（见本类 init 顶部的说明），而重进的 VM
                        // 手里没有"乐观上屏"的那条用户消息 —— 先回读一次，别让会话看着是空的
                        reloadMessages()
                    }
                } else if (wasBusy && _uiState.value.isStreaming) {
                    // 本会话这一轮生成刚结束（可能是在别的页面跑完的）：回读结果并复位。
                    // ⚠️ 必须同时看 wasBusy：只看 isStreaming 的话，**别的会话**的生成一结束
                    // 就会把本会话刚乐观上屏、还没落库的那条消息冲掉（多会话并发时真会撞上）。
                    finishStream()
                    _uiState.update { it.copy(isGeneratingImage = false, imagePrompt = "") }
                    ChatGenerationBus.takeError(personaId)?.let { (message, kind) ->
                        _uiState.update { it.copy(error = message, errorKind = kind) }
                    }
                }
                wasBusy = textMine
                val prompt = bus.imageBusy[personaId]
                if (prompt != null) {
                    _uiState.update {
                        if (it.isGeneratingImage) it else it.copy(isGeneratingImage = true, imagePrompt = prompt)
                    }
                } else if (_uiState.value.isGeneratingImage) {
                    _uiState.update { it.copy(isGeneratingImage = false, imagePrompt = "") }
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

    /**
     * TTS 文本清洗：剥掉生成标记与 Markdown 记号，只留读起来自然的话。
     * AI 语音条与通话合成共用（原名 cleanForSpeech，v0.50.0 随朗读功能一起改名）。
     */
    private fun cleanForTts(text: String): String = text
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
     * 录音蒙层里选「转文字」松手（v0.53.4）：**只发文字**，不挂语音文件。
     * 转写为空的情况由调用方兜底成发语音（不丢内容），这里只管有字时的发送。
     */
    fun sendVoiceAsText(text: String) {
        if (text.isBlank() || _uiState.value.isStreaming) return
        send(
            ChatMessage(
                conversationId = personaId,
                isFromUser = true,
                content = text.trim(),
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
     *
     * v0.56.6：新增 [caption] —— 附件现在**先挂在输入框上方**，用户写在输入框里的那句说明
     * 就是它，发送时一并带上（并清掉输入框）。顺序是"用户的话在前、抽出的正文在后"。
     */
    fun sendFile(
        path: String,
        name: String,
        size: Long,
        extractedText: String,
        caption: String = "",
    ) {
        if (path.isBlank() || _uiState.value.isStreaming) return
        val content = listOf(caption.trim(), extractedText.trim())
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
        send(
            ChatMessage(
                conversationId = personaId,
                isFromUser = true,
                content = content,
                attachmentPath = path,
                attachmentName = name,
                attachmentSize = size,
                mediaKind = MediaKind.FILE.name,
            ),
            clearInput = true,
        )
    }

    /** 发送共用链路：乐观上屏 → 落库 → LLM 回复 */
    private fun send(userMsg: ChatMessage, clearInput: Boolean) {
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
        // v0.57：整轮生成挂应用级作用域（[generationScope]），退出聊天页不掐断
        generationScope.launch {
            val saved = runCatching { db.messageDao().insert(userMsg) }
            // 他说话了：想念清零、骄傲软一点（状态引擎的"被回应"事件，v0.72.0）。
            // 放在落库之后 —— 消息没存上就不该顺手改动她的心情。
            if (saved.isSuccess) {
                val nowMs = System.currentTimeMillis()
                runCatching {
                    settingsRepository.markCompanionMessageAt(personaId, nowMs)
                    val s = com.menghuan.app.data.CompanionLogic.decode(
                        settingsRepository.companionStateRaw(personaId).first()
                    )
                    settingsRepository.setCompanionStateRaw(
                        personaId,
                        com.menghuan.app.data.CompanionLogic
                            .onUserMessage(s).copy(atMs = nowMs)
                            .let { com.menghuan.app.data.CompanionLogic.encode(it) }
                    )
                }
            }
            if (saved.isFailure) {
                setError(
                    "消息保存失败：${saved.exceptionOrNull()?.message ?: "存储异常"}",
                    ErrorBar.KIND_INFO,
                )
                return@launch
            }
            runGeneration()
        }
    }

    /**
     * 删除单条已落库消息（长按气泡菜单）：
     * v0.60 起**先本地摘掉、再落库**——Room 的重发要等一轮查询，删除类操作必须当场见效
     * （用户点名"删了通话记录条要退出去重进才消失"）。落库失败会**把这条放回原位**并报错，
     * 不许出现"界面上没了、库里还在"的假删除（异步失败必须能看到）。
     */
    fun deleteMessage(message: ChatMessage) = viewModelScope.launch {
        if (message.id <= 0) return@launch // 种子演示消息不在 DB，不可删
        removeLocal(listOf(message.id))
        val ok = runCatching { db.messageDao().delete(message.id) }.isSuccess
        if (!ok) {
            restoreLocal(message)
            setError("删除失败，这条消息还在（存储异常）", ErrorBar.KIND_INFO)
        }
    }

    /** 批量删除（v0.54.3 多选）：逐条删已落库消息；v0.60 起同样先本地摘掉再落库 */
    fun deleteMessages(messages: List<ChatMessage>) = viewModelScope.launch {
        val targets = messages.filter { it.id > 0 }
        if (targets.isEmpty()) return@launch
        removeLocal(targets.map { it.id })
        var failed = false
        targets.forEach { m ->
            if (runCatching { db.messageDao().delete(m.id) }.isFailure) {
                failed = true
                restoreLocal(m)
            }
        }
        if (failed) setError("有消息没删掉（存储异常），已放回列表", ErrorBar.KIND_INFO)
    }

    /** 本地摘掉若干条（按 id）——删除的"立刻见效"就靠这一步，不等 Room 重发 */
    private fun removeLocal(ids: List<Long>) {
        val gone = ids.toSet()
        _uiState.update { it.copy(messages = it.messages.filterNot { m -> m.id in gone }) }
    }

    /** 落库失败（以 Room 为准）：把没删掉的那条按时间戳插回原位 */
    private fun restoreLocal(message: ChatMessage) {
        _uiState.update { state ->
            if (state.messages.any { it.id == message.id }) state
            else state.copy(
                messages = (state.messages + message).sortedBy { it.timestamp },
            )
        }
    }

    /**
     * 批量转发（v0.54.3 多选）：把选中消息**复制**一份写进目标会话，原消息不动。
     * 时间戳按顺序递增，保证在目标会话里保持原有先后；
     * 媒体消息（图/语音/文件）直接复用本机文件路径，不复制文件。
     */
    fun forwardMessages(messages: List<ChatMessage>, targetPersonaId: Long) = viewModelScope.launch {
        if (messages.isEmpty()) return@launch
        val base = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            messages.forEachIndexed { index, m ->
                runCatching {
                    db.messageDao().insert(
                        m.copy(id = 0, conversationId = targetPersonaId, timestamp = base + index),
                    )
                }
            }
        }
    }

    /** 上次请求失败后重试（用户消息已在历史中） */
    fun retry() {
        if (_uiState.value.isStreaming) return
        _uiState.update { it.copy(isStreaming = true, generating = true, error = null) }
        // v0.57：同样挂应用级作用域，退出聊天页不掐断
        generationScope.launch { runGeneration() }
    }

    /**
     * 跑一轮生成（v0.57 起挂 [generationScope]）：
     * 在 [ChatGenerationBus] 上登记"本会话正在生成文字回复"，跑完（或异常）必定解登记——
     * 重新进入会话的 VM 靠这份登记把三点气泡接回来，登记不清就会永远显示"正在生成"。
     */
    private suspend fun runGeneration() {
        ChatGenerationBus.beginText(personaId)
        try {
            reply()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // ⚠️ 这条协程跑在 applicationScope 上：异常不接住会直接冒到默认处理器 → 进程死掉
            // （同 `CrashLog` / `PipelineVoiceCallEngine` 里反复强调的那条）。这里兜底成错误条，
            // 宁可显示"生成失败"，也不能因为一次生成把整个 App 崩了。
            setError("生成失败：${e.message ?: "未知异常"}", ErrorBar.KIND_RETRY)
        } finally {
            // 正常路径在 finishStream() 之后就已解登记，这里再解一次（集合去重，幂等），
            // 保证任何提前 return / 异常都不会把会话钉死在"生成中"
            ChatGenerationBus.endText(personaId)
        }
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
            setError("配置读取失败：${e.message ?: "存储异常"}", ErrorBar.KIND_API)
            return
        }

        // 未配置 API 或人设缺失：模拟回复（演示模式）
        if (persona == null || !config.isComplete) {
            appendMockReply()
            return
        }

        val facts = try {
            // v0.70.0：记忆按人设分开。以前是整张表原样拼进来，A 角色聊出来的私事
            // 会一字不差地从 B 角色嘴里说出来。现在只取「这个角色的 + 基础档案（开关允许时）」。
            // 读全表再在内存里筛，是因为筛选要同时知道开关值，而开关在 DataStore 不在 SQL。
            runCatching { db.profileFactDao().observeAll().first() }.getOrDefault(emptyList())
                .let { all ->
                    val shared = runCatching { settingsRepository.profileSharedReadable.first() }.getOrDefault(true)
                    com.menghuan.app.data.MemoryScope.visible(all, personaId, shared)
                }
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
            // 生图能力说明：只有生图模型配齐才告诉模型它能生图，否则别诱导用户白等一张图
            val imageReady = try {
                settingsRepository.imageProfiles.first().any { it.isComplete }
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
            // 会话联网的结果 v0.69.0 起**不再拼进这段 system prompt**：它是每轮都变的内容，
            // 留在前缀里等于每次搜索都把整段（人设 + 档案 + 全部能力说明）的缓存作废一次。
            // 现在走本轮易变上下文，见 webSearchBlock() 与 runtimeContextNow()。

            // 打电话能力说明（v0.53.8）：**总是注入**，不再看任何开关。
            // 理由：用户明确点名要的一通（"立刻打给我"）不该被拦住 ——
            // 以前关着就不注入，用户叫它打、它连标记都不输出，用户以为坏了（踩过）。
            // （v0.54.5 起连「AI 主动打电话」总开关本身都删了，开关只决定"TA 自己打不打"，与这条无关）
            if (isNotEmpty()) append("\n\n")
            append(AI_CALL_HINT)
            // AI 语音条能力说明（v0.50.0；v0.53.4 起总开关已删，只看 TTS 是否配齐）——
            // 没配语音合成时告诉模型它能发语音，用户只会收到一条合成失败的提示。
            val voiceNoteReady = try {
                ttsConfig().isComplete
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (voiceNoteReady) {
                if (isNotEmpty()) append("\n\n")
                val strategy = try {
                    settingsRepository.voiceNoteStrategy.first()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    VOICE_NOTE_DEFAULT_STRATEGY
                }
                append(voiceNoteHint(strategy))
            }
            // 记忆写入协议（v0.70.0）：这一段的文字是**固定的**，所以放得起前缀（不伤缓存）。
            // 与 §零.9 里"每轮都变的内容不许进 system prompt"不冲突 —— 变的是命中的记忆，不是这段说明。
            if (isNotEmpty()) append("\n\n")
            append(com.menghuan.app.data.MemoryMarker.promptSection())
            // 动态发帖/回帖协议（v0.73.0）：同样是**固定**文字，放得起前缀。
            // 每轮变的「有哪些帖子可回」不在这里，走易变上下文（见 momentFeedBlock）。
            if (isNotEmpty()) append("\n\n")
            append(com.menghuan.app.data.MomentMarker.promptSection())
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
            setError("历史读取失败：${e.message ?: "存储异常"}", ErrorBar.KIND_RETRY)
            return
        }
        // 本次请求里是否带了用户图片（模型不支持 vision 时 400，提示语要能对上）
        val hadImage = history.any { it.isFromUser && it.imagePath.isNotBlank() }

        val sb = StringBuilder()
        // 思考链全文（v0.41.4）：与正文分开收，一起落库，只在长按菜单里查看
        val thinking = StringBuilder()
        var usageTokens: Int? = null
        // v0.69.0：输入与其中缓存命中的读数（服务商没回就一直是 0，不参与命中率计算）
        var usagePrompt = 0
        var usageCached = 0
        var streamError: Throwable? = null
        // v0.41.4 后台生成一次性发送：不再有打字机追赶循环——正文在后台收完为止，
        // 期间 UI 只挂三点气泡（isStreaming/generating），收到 [DONE] 后整段落库上屏。
        // 这样用户看到的是"思考 → 完整回复弹出"，而不是逐字吐字（用户明确要求）。
        // v0.57：改挂 [generationScope]（不再是 viewModelScope）——退出聊天页也会把这一轮收完
        val collectJob = generationScope.launch {
            try {
                OpenAiClient(config.baseUrl, config.apiKey, config.model)
                    .streamEvents(systemPrompt, history, runtimeContextNow()) { path ->
                        // 用户发的图现场降采样转 base64（AI 生成图是 assistant 消息，不进请求）
                        com.menghuan.app.data.VisionImage.encodeDataUrl(path)
                    }
                    .collect { ev ->
                        when (ev) {
                            is com.menghuan.app.llm.StreamEvent.Delta -> sb.append(ev.text)
                            is com.menghuan.app.llm.StreamEvent.Reasoning -> thinking.append(ev.text)
                            is com.menghuan.app.llm.StreamEvent.Usage -> {
                                usageTokens = ev.tokens
                                usagePrompt = ev.promptTokens
                                usageCached = ev.cachedPromptTokens
                            }
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
        recordUsage(usageTokens, config.model, usagePrompt, usageCached)
        val failure = streamError
        if (failure is ApiException) {
            // HTTP 失败：一个 token 都没收到 → 错误条 + 按状态码分级，不落库
            if (sb.isEmpty()) {
                setError(friendlyApiError(failure, hadImage), apiErrorKind(failure.code))
                return
            }
            // 已收到部分回复：保留已收内容并正常落库
        } else if (failure != null) {
            if (sb.isEmpty()) {
                setError("回复失败：${failure.message ?: "网络异常"}", ErrorBar.KIND_RETRY)
                return
            }
            // 已收到部分回复：保留已收内容并正常落库
        }
        if (sb.isEmpty()) {
            // 服务端 200 但没有任何流式内容（网关错误页 / 非 SSE 响应）：
            // 明确报错而不是静默复位，否则用户不知道发生了什么
            setError("回复为空：请检查模型名是否正确，或稍后重试", ErrorBar.KIND_RETRY)
            return
        }
        // 落库并返回原始回复（含 [生图:…] 标记）：文字已剥标记落库，
        // 这里把原串带回去供生图解析
        val (rawReply, replyId) = persistReply(sb, reasoning)
        finishStream()
        // 文字这一段落库完成 = "生成中"这一刻结束。**先解登记**，好让重新进入会话的 VM
        // 立刻看到完整回复；后面的语音条/生图有自己的登记（imageBusy），各管各的。
        ChatGenerationBus.endText(personaId)
        // AI 语音条（v0.50.0）：回复里带 【语音条】 标记 → 合成语音，把刚落库那条文字原地升级成语音条
        maybeGenerateVoiceNote(rawReply, replyId)
        // v0.59.0：「自动朗读 AI 的回复」**按角色各开各的**（开关在会话设置第一组最后那行），默认全关。
        // AI 自己已经发了语音条的情况**跳过**——那条本来就是念出来的，再读一遍就是两遍同样的话。
        if (!VoiceNoteMarker.parse(rawReply).wantsVoice &&
            runCatching { settingsRepository.chatReadAloud.first()[personaId] == true }
                .getOrDefault(false)
        ) {
            readAloud(replyId, rawReply)
        }
        // 生图（v0.35.0）：模型按 system prompt 约定输出 [生图:提示词] 标记时，
        // 文字已剥掉标记落库，这里再把图生出来落盘并挂成一条图片消息
        maybeGenerateImage(rawReply)
        // 对话内操控手机（v0.37.0）：回复里带 [操作手机:目标] 标记 → 直接启动 AgentLoop
        maybeRunAgent(rawReply)
        // `<add:内容>` → 待入库（v0.70.0）。放在文字落库之后：一条回复先要能正常显示，
        // 记忆只是它的副作用，副作用失败绝不能把正文带走。
        maybeStageMemories(rawReply)
        // `[动态:…]` / `[动态#id:…]` → 落进动态时间线（v0.73.0）。同上：副作用，失败不带走正文。
        maybePostMoments(rawReply)
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
            setError(blocked.first, blocked.second)
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
    // ===== v0.59.0：朗读（长按菜单单条 + 自动朗读开关）=====
    //
    // 合成链**复用语音条那一条**（TtsClients + 人设音色三级回落），不另起一套；
    // 播放用一个临时文件交给 MediaPlayer，念完/切走/离开页面都释放。
    //
    // ⚠️ 复位不只靠 `onCompletion`：MP3 尾部有 padding 时那个回调可能整场不来，
    // 气泡就会永远停在"正在念"的样式（语音条那边踩过，见 ChatScreen 的看门狗）。
    // 所以这里另挂一个按音频时长算的兜底定时。
    //
    // [readToken] 是"这一轮朗读"的序号：任何一次打断（再点一条、手动停、发新消息）
    // 都会 ++，旧协程回来发现自己过期就直接放弃，不会把新的状态覆盖掉。

    private var readToken = 0
    private var readPlayer: android.media.MediaPlayer? = null

    /** 长按菜单：念这一条；再点同一条 = 停 */
    fun readAloud(messageId: Long, text: String) {
        if (_uiState.value.readingId == messageId) {
            stopReading()
            return
        }
        stopReading()
        val token = ++readToken
        _uiState.update { it.copy(readingId = messageId) }
        viewModelScope.launch {
            val spoken = cleanForTts(text)
            if (spoken.isBlank()) {
                finishReading(token, "这条没有可念的文字")
                return@launch
            }
            val config = ttsConfig()
            if (!config.isComplete) {
                finishReading(token, "语音合成未配置（设置 → 语音模型）")
                return@launch
            }
            val client = TtsClients.from(config, appContext)
            val audio = client.synthesize(spoken, voiceRefFor(config))
            if (audio == null) {
                finishReading(token, "朗读失败：${client.lastFailure ?: "未知原因"}")
                return@launch
            }
            if (token != readToken) return@launch   // 等合成这段时间里用户已经切走了
            // appContext 是可空的（VM 允许不带 context 构造）：没了就直接放弃，不硬解引用
            val ctx = appContext ?: run { finishReading(token, "无法播放"); return@launch }
            val file = java.io.File(ctx.cacheDir, "read_${token}.${audio.format.extension}")
            val started = runCatching {
                file.writeBytes(audio.bytes)
                android.media.MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    setOnCompletionListener { if (token == readToken) stopReading() }
                    // onError 的回调**必须回 true**（表示"错误我接了，别再往上报"），
                    // 不然 MediaPlayer 会把错误继续抛到未捕获异常
                    setOnErrorListener { _, _, _ ->
                        if (token == readToken) stopReading()
                        true
                    }
                    prepare()
                    start()
                }
            }.getOrNull()
            if (started == null) {
                runCatching { file.delete() }
                finishReading(token, "音频播放起不来")
                return@launch
            }
            readPlayer = started
            // 兜底：按**真实音频时长**+4 秒收状态。onCompletion 不来的情况真机上出现过
            // （MP3 尾部 padding），不能拿文件大小猜时长——各家码率不同，猜了会提前停或迟迟不停。
            launch {
                val ms = probeDurationMs(file)
                delay(if (ms > 0L) ms + 4_000L else 60_000L)
                if (token == readToken) stopReading()
            }
        }
    }

    /** 停掉正在念的（幂等：没在念时调用也安全） */
    fun stopReading() {
        readToken++
        runCatching { readPlayer?.takeIf { it.isPlaying }?.stop() }
        runCatching { readPlayer?.release() }
        readPlayer = null
        _uiState.update { if (it.readingId == null) it else it.copy(readingId = null) }
    }

    private suspend fun finishReading(token: Int, reason: String?) {
        if (token != readToken) return
        stopReading()
        if (!reason.isNullOrBlank()) setError(reason, ErrorBar.KIND_RETRY)
    }

    private suspend fun ttsConfig(): TtsConfig =
        runCatching { settingsRepository.ttsConfig.first() }.getOrDefault(TtsConfig())

    /**
     * 本会话该用哪个音色：人设专属 → 音色板块默认 → 该家第一个预置
     * （三级回落集中在 [SettingsRepository.voiceForConversation]，通话走同一个）。
     */
    private suspend fun voiceRefFor(config: TtsConfig): VoiceRef =
        runCatching { settingsRepository.voiceForConversation(personaId, config) }
            .getOrElse { VoiceRef(config.provider, TtsVoices.defaultVoiceId(config.provider)) }

    /**
     * 回复里带 `【语音条】` → 合成语音，把**刚落库的那条文字消息原地升级**成语音条（v0.50.0）。
     *
     * ## 为什么是"升级同一条"而不是像生图那样另插一条
     * 语音条与文字是同一句话的两种形态，另插一条用户就会看到"一段文字 + 一条说同样内容的语音"
     * （参考实现专门做了个"自动去重"开关来治这个病）。升级同一条天然不重复，
     * 而且**合成失败时什么都不用回滚**——那条消息本来就是文字，降级是免费的，只需把原因说一次。
     *
     * ## 为什么失败一定要出声
     * 静默失败是这个项目踩过的坑（云端 Whisper 兜底三个版本一直返回空，用户只看到"AI 没反应"）。
     */
    private suspend fun maybeGenerateVoiceNote(rawReply: String, messageId: Long) {
        if (messageId <= 0L) return
        val decision = VoiceNoteMarker.parse(rawReply)
        if (!decision.wantsVoice) return
        val text = cleanForTts(decision.cleanText)
        if (text.isBlank()) return

        val config = ttsConfig()
        if (!config.isComplete) {
            setError("AI 想发语音，但语音合成未配置（设置 · 语音）", ErrorBar.KIND_API)
            return
        }
        val client = TtsClients.from(config, appContext)
        val audio = client.synthesize(text, voiceRefFor(config))
        if (audio == null) {
            setError("语音合成失败，已保留文字：${client.lastFailure ?: "未知原因"}", ErrorBar.KIND_RETRY)
            return
        }
        withContext(NonCancellable) {
            val written = runCatching {
                val dir = java.io.File(filesDir, "voice").apply { if (!exists()) mkdirs() }
                val file = java.io.File(dir, "ai_${System.currentTimeMillis()}.${audio.format.extension}")
                file.writeBytes(audio.bytes)
                db.messageDao().updateVoice(
                    id = messageId,
                    mediaKind = MediaKind.AUDIO.name,
                    audioPath = file.absolutePath,
                    audioDurationMs = probeDurationMs(file),
                )
                true
            }.getOrDefault(false)
            if (!written) {
                setError("语音已合成但写入失败，已保留文字", ErrorBar.KIND_RETRY)
            }
        }
    }

    /** 语音时长（语音条上要显示）；取不到就 0，气泡会显示 0″——比编一个数字诚实 */
    private fun probeDurationMs(file: java.io.File): Long = runCatching {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrDefault(0L)

    private suspend fun maybeGenerateImage(rawReply: String) {
        val prompt = ImageGenerationClient("", "", "").findPromptIn(rawReply) ?: return
        // 默认生图模型排最前；它调不通就按列表顺序自动换下一个，全部试完才算失败（v0.53.4 用户要求）
        val clients = imageClients()
        if (clients.isEmpty()) {
            setError("回复里带了生图请求，但生图模型未配置（设置 · API 配置）", ErrorBar.KIND_API)
            return
        }
        _uiState.update { it.copy(isGeneratingImage = true, imagePrompt = prompt) }
        // v0.57：生图也登记到总线上——退出聊天页时这一张还在生成，回来能看到生图卡
        ChatGenerationBus.beginImage(personaId, prompt)
        var path: String? = null
        var usedClient: ImageGenerationClient? = null
        // 每个模型失败的原因都收集起来（v0.56.17）：以前全部失败只回一句"模型未配置或调用失败"，
        // 服务端明明说了原因（如豆包"size 至少 3686400 像素"、模型未开通…）却看不到
        val failures = mutableListOf<String>()
        // v0.58.1：TA 有立绘就把立绘当**参考图**发过去（图生图），让"AI 画自己"时
        // 发色 / 瞳色 / 服装 / 画风对得上。没立绘就一个字都不多发，行为和以前完全一致。
        val artFile = runCatching {
            db.personaDao().getById(personaId)?.artImagePath
                ?.takeIf { it.isNotBlank() }
                ?.let { java.io.File(it) }
                ?.takeIf { it.isFile && it.length() > 0L }
        }.getOrNull()
        for (c in clients) {
            val r = c.generate(prompt, ImageGenerationClient.imagesDir(imagesDir), artFile)
            if (r.path != null) {
                path = r.path
                usedClient = c
                // 图出来了但参考图没生效（这家不认）：必须说，否则用户以为那张像 TA
                r.note?.let { setError(it, ErrorBar.KIND_API) }
                break
            }
            failures += "${c.model}：${r.error ?: "未知原因"}"
        }
        _uiState.update { it.copy(isGeneratingImage = false, imagePrompt = "") }
        ChatGenerationBus.endImage(personaId)
        // 生图记录（v0.40.0）：成功失败都留痕——何时、哪个会话发起、用的哪个模型、什么提示词。
        // 失败不留图片文件，但记录必须有，图库里可追溯。
        // 模型名（v0.53.4）：成功记**实际成功那个**；全失败时记尝试过的清单（默认在前），
        // 别让失败记录的模型列空着（用户点名"要记录是哪个模型发起的"）。
        runCatching {
            val modelName = usedClient?.model ?: clients.joinToString(" / ") { it.model }
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
            // 把服务端原话带出来（多模型时逐条列），而不是笼统的"调用失败"
            setError(
                "生图失败：" + failures.joinToString("；").ifBlank { "模型未配置或调用失败" },
                ErrorBar.KIND_RETRY,
            )
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
    /**
     * `<add:内容>` 的落点：写进**当前这个角色**的记忆（v0.70.0；v0.71.0 起新条目进待入库）。
     *
     * ## 为什么新条目落 `staging`
     * 模型说"我记住了"不等于那条值得记 —— 它会把"你今天吃了个包子"也存下来。
     * 新条目先落 `staging`，由用户在「TA 的记忆」页点头才生效（`MemoryScope.visible` 不注入 staging）。
     * v0.70.0 那一批之所以先直接写 `active`，是因为**当时还没有审批入口**：
     * 没有入口的 staging 等于一个用户看不见的死功能。现在那一页有了，才切回暂存。
     *
     * ## 四条不许回退的规则
     * - **被拒过的句子永久不再回来**（`rejected`）：用户摇过头的事再记第二次，比不记更惹恼人；
     * - **已经生效的不许降级回 staging**：同一件事被重复提及时只更新内容 ——
     *   否则表现是"TA 明明记得的事忽然又不记得了"；
     * - **`core`（用户设为永久）与 `archived`（用户手动停用）都不动**：那是人的决定，不是 AI 的；
     * - 整条回复只有标记时聊天界面上要留一句 `💭 记下了`，不能发一条空白消息。
     */
    private suspend fun maybeStageMemories(rawReply: String) {
        val items = com.menghuan.app.data.MemoryMarker.parse(rawReply).items
        if (items.isEmpty()) return
        val existing = runCatching { db.profileFactDao().observeOwnedBy(personaId).first() }
            .getOrDefault(emptyList())
        items.forEach { item ->
            if (existing.any { it.lifecycle == "rejected" && it.value == item }) return@forEach
            val dup = existing.firstOrNull { it.value == item && it.lifecycle != "rejected" }
                ?: existing.firstOrNull {
                    it.key == com.menghuan.app.data.MemoryMarker.keyOf(item) && it.lifecycle != "rejected"
                }
            val lifecycle = when (dup?.lifecycle) {
                null -> "staging"        // 全新的：等用户在记忆页点头
                "staging" -> "staging"   // 已在待入库里：更新内容，状态不变
                else -> dup.lifecycle    // active / core / archived 一律不降级
            }
            runCatching {
                db.profileFactDao().upsert(
                    com.menghuan.app.data.model.ProfileFact(
                        id = dup?.id ?: 0L,
                        key = com.menghuan.app.data.MemoryMarker.keyOf(item),
                        value = item,
                        source = "ai",
                        updatedAt = System.currentTimeMillis(),
                        personaId = personaId,
                        lifecycle = lifecycle,
                    )
                )
            }
        }
    }

    /**
     * 回复里带 `[动态:内容]` / `[动态#id:内容]` → 落进动态时间线（v0.73.0）。
     *
     * 放在文字落库**之后**，与 [maybeStageMemories] 同一条纪律：一条回复先要能正常显示，
     * 发帖只是它的副作用，副作用失败绝不能把正文带走（整条链路包在 runCatching 里）。
     *
     * 三条口径：
     * - **回帖先验 id**：模型会编一个不存在的编号（`[动态#99:…]`）。那种回复指向空气，
     *   时间线上算不出条数、点进去还会白屏 —— 直接丢弃，并留一条日志。
     *   正文里那个标记已经被剥掉了，所以用户看不见这条没落成的回复；这是刻意的，
     *   把模型的协议错误甩给用户看只会变成"这 App 奇怪"。
     * - **一帖一条上限**：一轮回复最多发 2 条，防它把一次回答拆成刷屏（时间线是公共空间）。
     * - **AI 不能删、不能改用户发的帖**：这条链路只有 insert，没有 update/delete。
     */
    private suspend fun maybePostMoments(rawReply: String) {
        // v0.79.0：落库逻辑抽到 MomentActions —— 动态现在有两条链路（聊天 / 主动关心），
        // 校验口径必须只有一份；这里只管"什么时候调"，不管"怎么落"。
        com.menghuan.app.data.MomentActions.apply(
            db,
            personaId,
            rawReply,
            com.menghuan.app.data.MomentActions.Limits.CHAT,
        )
    }

    private suspend fun persistReply(sb: StringBuilder, reasoning: String = ""): Pair<String, Long> {
        if (sb.isEmpty()) return "" to 0L
        val raw = sb.toString()
        val imgClient = imageClient()
        val hasImageMarker = imgClient.findPromptIn(raw) != null
        val voiceNote = VoiceNoteMarker.parse(raw)
        // 口头约定（v0.53.5）：用户说"待会打给我"，模型回一个 [打电话:N] / [打电话:HH:mm]
        val nowMs = System.currentTimeMillis()
        val wantedCallAt = com.menghuan.app.data.AiCallLogic.normalizeWantedAt(
            com.menghuan.app.data.AiCallLogic.parseWantedCallAt(raw, nowMs),
            nowMs,
        )
        // 【语音条】也要在这一层剥掉：合成成功与否都不该让用户看见标记本身
        val withoutVoiceNote = if (voiceNote.wantsVoice) voiceNote.cleanText else raw
        // 记忆标记 `<add:内容>` 同样在这一层剥（v0.70.0）：条目进"待入库"，正文不许带标记
        val memory = com.menghuan.app.data.MemoryMarker.parse(withoutVoiceNote)
        // 动态标记 `[动态:…]` / `[动态#id:…]`（v0.73.0）：同一条纪律 —— 标记是给 App 的信号，
        // 帖子另由 maybePostMoments 落进时间线，正文里一个字都不许留
        val moment = com.menghuan.app.data.MomentMarker.parse(memory.cleanText)
        val text = AgentPrompts.stripMarker(
            imgClient.stripMarker(
                com.menghuan.app.data.AiCallLogic.stripMarker(moment.cleanText)
            ),
        ).ifBlank {
            // 整条回复只有标记、没有正文：给一句占位（图/执行卡/语音条随后接管观感）
            when {
                hasImageMarker -> IMAGE_ONLY_PLACEHOLDER
                voiceNote.wantsVoice -> VOICE_ONLY_PLACEHOLDER
                // 只写了 `<add:…>`：用户得看见"TA 记了一笔"，否则这条回复看起来是空的，
                // 而记忆已经静静进了暂存区 —— 无声的变化最难查
                memory.items.isNotEmpty() -> MEMORY_ONLY_PLACEHOLDER
                // 只写了 `[动态:…]` 同理：TA 去发动态了，屏幕上要给一句交代
                moment.wantsAnything -> MOMENT_ONLY_PLACEHOLDER
                else -> AGENT_ONLY_PLACEHOLDER
            }
        }
        var messageId = 0L
        withContext(NonCancellable) {
            messageId = runCatching {
                db.messageDao().insert(
                    ChatMessage(
                        conversationId = personaId,
                        isFromUser = false,
                        content = text,
                        reasoning = reasoning,
                    )
                )
            }.getOrDefault(0L)
        }
        // 排那通约定的电话（v0.53.6：按人设排；拿不到 Context 的单测环境就跳过，不影响落库）
        if (wantedCallAt != null) {
            runCatching {
                appContext?.let {
                    com.menghuan.app.notify.AlarmScheduler.scheduleAiCallOnce(it, personaId, wantedCallAt)
                }
            }
        }
        return raw to messageId
    }

    /** 生图客户端（只用于解析/剥除 `[生图:…]` 标记，不参与实际生成；配置为空也能正确剥标记） */
    private suspend fun imageClient(): ImageGenerationClient {
        val cfg = runCatching { settingsRepository.imageApiConfig.first() }.getOrNull()
        return ImageGenerationClient(
            cfg?.baseUrl.orEmpty(),
            cfg?.apiKey.orEmpty(),
            cfg?.model.orEmpty(),
        )
    }

    /**
     * 全部可用的生图客户端，**默认模型排最前**（v0.53.4）。
     * [maybeGenerateImage] 按这个顺序依次尝试：默认那条调不通就换下一个，
     * 这就是"默认生图模型不可用时自动切换到其他模型继续生图"的实现。
     */
    private suspend fun imageClients(): List<ImageGenerationClient> {
        val profiles = runCatching { settingsRepository.imageProfiles.first() }.getOrDefault(emptyList())
        val activeId = runCatching { settingsRepository.imageActiveProfileId.first() }.getOrDefault("")
        val ready = profiles.filter { it.isComplete }
        if (ready.isEmpty()) return emptyList()
        val default = ready.firstOrNull { it.id == activeId }
        val ordered = listOfNotNull(default) + ready.filterNot { it.id == default?.id }
        return ordered.map { ImageGenerationClient(it.baseUrl, it.apiKey, it.model) }
    }

    /**
     * API 错误 → 用户能看懂、知道下一步做什么的文案；带图时 400 顺带提示 vision 可能不支持。
     *
     * **v0.56.17：把服务端原话带在括号里**。以前只回我们自己的固定话术，真机踩过：
     * 豆包方舟 404 的原话是 `The model or endpoint … does not exist **or you do not have access to it**`
     * ——它同时能表达"模型名错"和"模型没开通/无权限"两种含义，而我们的固定话术
     * "接口地址或模型名不存在，请检查配置"把用户引去查本来正确的地址。
     * 本项目的铁律（已踩过四次）：**报错不许吞服务端原话**，自己的猜测只能垫底。
     */
    private fun friendlyApiError(e: ApiException, hadImage: Boolean = false): String {
        val base = when (e.code) {
            401, 403 -> "API Key 无效或无权限，请在「设置 · API 配置」检查"
            404 -> "接口地址或模型名不存在，请检查配置"
            429 -> "请求太频繁或额度不足，稍后再试"
            in 500..599 -> "服务端暂时不可用（${e.code}），稍后再试"
            else -> "请求失败（${e.code}）"
        }
        // 原话截断到 120 字：错误条只显示 2 行，太长的服务端文案会把关键信息挤出屏幕
        val detail = NetworkStack.apiErrorText(e.body)?.take(120)
        val withDetail = if (detail != null && !base.contains(detail)) "$base（$detail）" else base
        return if (hadImage && e.code == 400) {
            "$withDetail · 也可能当前模型不支持看图，试试纯文字或换个模型"
        } else {
            withDetail
        }
    }

    /** 按状态码定错误条动作：Key/配置问题给"去配置"，其余给"重试" */
    private fun apiErrorKind(code: Int): String =
        if (code == 401 || code == 403 || code == 404) ErrorBar.KIND_API else ErrorBar.KIND_RETRY

    /**
     * 用量记账：服务商回了 usage 才记，拿不到不猜。
     * v0.56.7：[model] 一并带上 —— 用户在设置里要看到**每个模型各自**的用量。
     * v0.57：挂 [generationScope] —— 这轮生成可能是在聊天页已经退出（VM 已销毁）之后才收到
     * usage 的，用 viewModelScope 会连记账一起被取消掉。
     */
    /**
     * 本会话联网检索的结果块（v0.69.0 起它是**易变上下文**，不再进 system prompt）。
     * 
     * 三种情况都要给出可读的话而不是空串，因为模型拿不到块就会开始凭记忆编新闻：
     * 没配 Key、搜到了、一家都没搜到 —— 原来那三套措辞整段搬过来，一个字没改。
     */
    private suspend fun webSearchBlock(): String = buildString {
        // 会话联网（v0.53.6）：这个对话开着联网时，拿用户这一句当查询先搜一遍，再把结果给它。
        // 复用「联网搜索」里配好的搜索服务（博查 / Tavily），不另配一套。
        //
        // ⚠️ v0.56.5 修「配好 Key、打开联网，角色仍然不联网」：
        // 原来查询串取的是 `_uiState.value.input`，而 `send()` 在调用本函数**之前**
        // 已经把输入框清空了（`input = ""`）→ `query` 恒为空 → 整段联网注入**从来没执行过**。
        // 现在改成读**最后一条用户消息**（就是刚发出去那句，落库与上屏都已完成），
        // 重试时也仍然拿得到同一句。
        val webSearchOn = try {
            settingsRepository.chatWebSearch.first()[personaId] == true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (webSearchOn) {
            val query = _uiState.value.messages.lastOrNull { it.isFromUser }?.content?.trim().orEmpty()
            if (query.isNotEmpty()) {
                val provider = try {
                    settingsRepository.searchProvider.first()
                } catch (e: Exception) {
                    com.menghuan.app.data.SEARCH_BOCHA
                }
                // v0.59.0：Key 与接口地址都按**当前生效那一家**取——
                // 自定义家有独立的一份 Key 和一个用户自填的地址，
                // 拿内置那家去取会串成"切到自定义就查不了"
                val searchKey = try {
                    settingsRepository.searchKeyFor(provider)
                } catch (e: Exception) {
                    ""
                }
                val customEndpoint = try {
                    settingsRepository.searchEndpointFor(provider)
                } catch (e: Exception) {
                    ""
                }
                if (isNotEmpty()) append("\n\n")
                append("## 联网\n")
                if (searchKey.isBlank()) {
                    append("用户打开了本会话的联网开关，但还没配搜索服务的 Key")
                    append("（设置 → 模型配置 → 联网搜索）。")
                    append("被问到最新信息时请**如实说明暂时查不了**，不要凭记忆编。")
                } else {
                    val hits = runCatching {
                        com.menghuan.app.data.WebSearchClient.search(
                            provider, searchKey, query, customEndpoint = customEndpoint,
                        )
                    }.getOrDefault(emptyList())
                    val block = com.menghuan.app.data.WebSearchClient
                        .toPromptBlock(hits, com.menghuan.app.data.WebSearchClient.labelOf(provider))
                    if (block.isBlank()) {
                        append("这次没取到搜索结果（网络或额度问题）。")
                        append("如果问题涉及最新消息，请**如实说查不到**，不要编造。")
                    } else {
                        append("下面是刚搜到的结果，优先依据它回答，拿不准的地方就说没查到：\n")
                        append(block)
                    }
                }
            }
        }
    }

    /**
     * 本轮的易变上下文（v0.69.0）：当前时间 + 本会话的联网检索结果。
     *
     * **刻意不拼进 system prompt**：时间在 system 里 = 前缀每分钟作废一次，缓存永远命不中；
     * 贴到最后一条用户消息上，前面那一大段（人设 + 档案 + 历史）才逐字节稳定。
     * 口径与实现都在 [com.menghuan.app.data.RuntimeContext]（通话那边共用它，别两处各拼一份）。
     *
     * 之前根本没告诉模型"现在是几点、今天星期几"—— 于是"今晚 / 明天 / 上周"全靠猜。
     */
    private suspend fun runtimeContextNow(): String = buildString {
        append(com.menghuan.app.data.RuntimeContext.clockLineNow())
        // 联网结果同为易变内容：贴到最后一条用户消息，别放回前缀
        val web = webSearchBlock()
        if (web.isNotBlank()) {
            if (isNotEmpty()) append("\n\n")
            append(web)
        }
        // 记忆检索（v0.70.0）：宿主按这一句去搜，**不等模型开口要**
        val recall = memoryRecallBlock()
        if (recall.isNotBlank()) {
            if (isNotEmpty()) append("\n\n")
            append(recall)
        }
        // 动态时间线现状（v0.73.0）：模型要回帖就得知道**有哪些编号可回**。
        // 这份名单每轮都变（新帖随时进、旧帖会被删），进 system prompt 就是每轮作废前缀；
        // 所以和记忆检索一样贴到最后一条用户消息上（§零.9）。
        val feed = momentFeedBlock()
        if (feed.isNotBlank()) {
            if (isNotEmpty()) append("\n\n")
            append(feed)
        }
        // 今日任务状态（v0.77.0，用户点名「AI 识别不了任务已经完成」）：
        // 以前"任务完成了"只以一条用户消息 `✅ 已完成打卡：xx` 的形式落在主人设会话里，
        // **别的会话看不到、AI 也不会把它当状态**，于是它照样催"你今天还没打卡吧"。
        // 现在把当前的任务状态直接喂进上下文（完成/未完成/失败分列），每轮现算——
        // 状态随打卡时刻变，进 system prompt 就是每轮作废前缀（§零.9）。
        val tasks = taskStatusBlock()
        if (tasks.isNotBlank()) {
            if (isNotEmpty()) append("\n\n")
            append(tasks)
        }
    }

    /**
     * 今日任务状态（v0.77.0）。
     *
     * 只报**当前还没有结束的任务**（未完成的）+ 今天已完成的**计数与名字**：
     * - 未完成逐条列出来，AI 才知道该催哪几件、不该催哪些已经做完的；
     * - 已完成的列名字，AI 说"你今天的 xx 做完啦"才不是凭空恭维；
     * - 每日任务跨天重置用 [isEffectivelyDone] / [isEffectivelyFailed] 判定，
     *   与任务页/周报同一口径（别自己写 `isDone`，那会把昨天的完成算成今天的）。
     *
     * 没有任何任务（列表为空）+ 今天也没完成任何事 → 返回空串，不往上下文里塞空标题。
     */
    private suspend fun taskStatusBlock(): String {
        val all = runCatching { db.taskDao().observeAll().first() }.getOrDefault(emptyList())
        if (all.isEmpty()) return ""
        val today = java.time.LocalDate.now().toEpochDay()
        val done = all.filter { it.isEffectivelyDone(today) }
        val failed = all.filter { it.isEffectivelyFailed(today) }
        val pending = all.filter { !it.isEffectivelyDone(today) && !it.isEffectivelyFailed(today) }
        if (done.isEmpty() && failed.isEmpty() && pending.isEmpty()) return ""
        return buildString {
            append("## 用户的任务现状\n")
            append("（这是实时状态，用户说「我做完了 xx」时以这里为准，别凭印象说人家没做。）\n")
            if (pending.isEmpty()) {
                append("· 待办：今天没有未完成的任务。\n")
            } else {
                append("· 还没完成（${pending.size} 件）：")
                append(pending.take(8).joinToString("、") { it.title })
                if (pending.size > 8) append(" 等 ${pending.size} 件")
                append("\n")
            }
            if (done.isNotEmpty()) {
                append("· 今天已完成（${done.size} 件）：")
                append(done.take(8).joinToString("、") { it.title })
                if (done.size > 8) append(" 等 ${done.size} 件")
                append("\n")
            }
            if (failed.isNotEmpty()) {
                append("· 今天标了失败（${failed.size} 件）：")
                append(failed.take(8).joinToString("、") { it.title })
                append("\n")
            }
        }.trimEnd()
    }

    /**
     * 「你手上有哪些帖子可以回」那一段（v0.73.0）。
     *
     * 只给**帖子**（parentId=0），不给回复：回复不能再被回复，列出编号只会诱导它写出
     * `[动态#回复id:…]`，那条会因为指向的不是帖子而被 [maybePostMoments] 丢掉。
     *
     * 6 条是实测的平衡点：再多模型开始回每条都像在刷屏，而时间线翻两屏也就这些。
     */
    private suspend fun momentFeedBlock(): String {
        val all = runCatching { db.momentDao().observeAll().first() }.getOrDefault(emptyList())
        val posts = all.filter { it.parentId == 0L }.sortedByDescending { it.createdAt }.take(6)
        if (posts.isEmpty()) return ""
        val names = runCatching { db.personaDao().observeAll().first() }
            .getOrDefault(emptyList()).associate { it.id to it.name }
        val lines = posts.map { p ->
            com.menghuan.app.data.MomentMarker.feedLine(
                id = p.id,
                author = if (p.personaId == 0L) "用户" else names[p.personaId] ?: "TA",
                text = p.text,
            )
        }
        return com.menghuan.app.data.MomentMarker.feedBlock(lines)
    }

    /**
     * 把"这一句可能相关的记忆"捞出来给模型（v0.70.0）。
     *
     * ## 为什么是宿主主动推，而不是等模型发 `<search:关键词>`
     * 拉取式协议要求模型**先答一半、我们再把它要的结果喂回去重问一次**：
     * 每一轮多一次请求、多一笔钱，而且模型不一定每次都记得发那个标记
     * （不发的时候它就直接凭印象编，用户看不出区别）。
     * 推取式零额外请求，且命中率不依赖模型的自觉。等记忆页与"得分阈值"能调了，
     * 再把拉取式作为**补充**加回来（`MemoryScope.search` 已经留着这个入口）。
     *
     * ## 为什么放在易变上下文而不是 system prompt
     * 每条消息命中的记忆都不一样，进前缀就是每轮作废一次缓存（AGENTS.md §零.9）。
     */
    private suspend fun memoryRecallBlock(): String {
        val query = _uiState.value.messages.lastOrNull { it.isFromUser }?.content?.trim().orEmpty()
        if (query.isEmpty()) return ""
        val all = runCatching { db.profileFactDao().observeAll().first() }.getOrDefault(emptyList())
        val shared = runCatching { settingsRepository.profileSharedReadable.first() }.getOrDefault(true)
        // 阈值 60：与爱语「记忆库模糊搜索得分 0-100、默认 60」同口径（见 MemoryScope.search 注释）。
        // 宁缺毋滥 —— 捞进不相干的记忆会让它把别人的事安在用户头上，比想不起来更糟。
        val hits = com.menghuan.app.data.MemoryScope.search(all, personaId, shared, query, minScore = 60, limit = 3)
        if (hits.isEmpty()) return ""
        return buildString {
            append("## 你想起来的事\n")
            append("（以下是你自己记下的关于这个人的事，按相关度排；想用就用，")
            append("**不要一条条念出来给用户听**，它只是帮你把话接对。）\n")
            hits.forEach { append("- ").append(it.fact.value).append("\n") }
        }.trimEnd()
    }

    private fun recordUsage(tokens: Int?, model: String, promptTokens: Int = 0, cachedTokens: Int = 0) {
        if (tokens == null && promptTokens <= 0 && cachedTokens <= 0) return
        generationScope.launch {
            runCatching {
                com.menghuan.app.data.UsageTracker.add(
                    settingsRepository, tokens ?: 0, model, promptTokens, cachedTokens,
                )
            }
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
        /** v0.53.5：口头约定打电话的能力说明（v0.53.7 起**总是注入**，不依赖开关；v0.54.5 总开关已删） */
        const val AI_CALL_HINT =
            "## 打电话\n" +
                "用户让你「立刻打给他 / 待会打给他 / 半小时后叫醒他 / 今晚九点打给他」时，你**做得到**：" +
                "在回复末尾另起一行输出 `[打电话:N]`，N 是从此刻起**多少分钟**（0 = 立刻，最大 1440）；" +
                "如果用户给的是具体钟点，改成输出 `[打电话:HH:mm]`（24 小时制）。\n" +
                "同时**一定要在正文里明确答应他**（例如「好，我这就打给你」「行，半小时后见」），" +
                "这样他才确定你收到了 —— 只输出标记、正文不吭声，用户会以为你没反应。\n" +
                "**只有用户明确要求你打电话时才输出这个标记**，其他任何场合都不要输出；" +
                "也别在正文里解释这个标记本身。"

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
                    app.applicationContext,
                )
            }
        }
    }
}
