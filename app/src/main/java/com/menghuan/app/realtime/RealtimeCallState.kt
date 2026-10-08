package com.menghuan.app.realtime

/**
 * 通话状态机（第一阶段任务书 §十二）：状态**只由引擎写**，UI 只观察。
 * CONNECTED 供真双工引擎（GLM-Realtime 等）使用；管道引擎从 CONNECTING 直达 LISTENING。
 */
enum class RealtimeCallState {
    IDLE,
    CONNECTING,
    CONNECTED,
    LISTENING,
    USER_SPEAKING,
    AI_THINKING,
    AI_SPEAKING,
    INTERRUPTED,
    DISCONNECTING,
    ENDED,
    ERROR,
}

/** 引擎对外事件：字幕与状态变化都从这里出（UI 收集渲染，不解析任何 JSON） */
sealed interface RealtimeCallEvent {
    /** 用户说话中（端侧流式识别的实时 partial） */
    data class UserPartial(val text: String) : RealtimeCallEvent

    /** 用户一句话收口（VAD 判定说完），即将请求 LLM */
    data class UserFinal(val text: String) : RealtimeCallEvent

    /** AI 回复流式增量（fullText = 本轮已生成的全文，渲染时整段替换） */
    data class AiDelta(val fullText: String) : RealtimeCallEvent

    /** AI 一轮回复收口（正常说完或被打断后的已说部分） */
    data class AiFinal(val text: String) : RealtimeCallEvent

    data class State(val state: RealtimeCallState) : RealtimeCallEvent
    data class Error(val message: String) : RealtimeCallEvent
    /**
     * AI 在回复里点了名要换的表情（v0.69.0，标记 `[表情:名]` 已由引擎剥掉，这里只发名字）。
     *
     * **只有 Live2D 形象才认它**：伪视频没有可驱动的参数，语音通话连视觉层都不创建
     * （AGENTS.md §零.7"语音通话必须完全不碰视觉层"），所以那两条路收到就丢 ——
     * 表情没换成不等于通话出了问题，不许报错、不许弹提示。
     */
    data class Expression(val name: String) : RealtimeCallEvent
}

/**
 * 一次通话的配置：人设快照（personaId/systemPrompt/voice 由调用方组装好）。
 * 通话对象就是现有 Persona（任务书 §十七），不建第二套人格系统；
 * 音色按人设各自保存（DataStore tts_voice_<personaId>，空 = 当前 provider 的默认预置音色）。
 */
data class RealtimeCallConfig(
    val personaId: Long,
    val personaName: String,
    /** 已组装的 system prompt：persona.systemPrompt（或基础提示词）+ 档案注入说明 + 用户档案 */
    val systemPrompt: String,
    /** 音色（v0.50.0 起是 [VoiceRef]：哪家 provider + 该家认的音色串；通话与 AI 语音条共用） */
    val voice: com.menghuan.app.data.VoiceRef,
    /**
     * v0.53.7：这通电话**是不是 AI 主动打来的**。
     * 它决定两件事：① 通话记录卡落在 AI 那一侧还是我这一侧；
     * ② 接通后是 AI 先开口（是它打的）还是等用户先说（我打给它的）。
     */
    val initiatedByAi: Boolean = false,
)

/** 通话期间内存暂存的一轮对话（挂断时一次性写 Room，partial 绝不入库） */
data class CallTurnData(
    val speaker: String, // "user" / "ai"
    val text: String,
    val timestamp: Long,
)
