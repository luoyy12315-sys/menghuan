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
}

/**
 * 一次通话的配置：人设快照（personaId/systemPrompt 由调用方组装好）+ TTS 音色。
 * 通话对象就是现有 Persona（任务书 §十七），不建第二套人格系统。
 */
data class RealtimeCallConfig(
    val personaId: Long,
    val personaName: String,
    /** 已组装的 system prompt：persona.systemPrompt（或基础提示词）+ 档案注入说明 + 用户档案 */
    val systemPrompt: String,
    /** Edge TTS 音色（DataStore 可改，默认晓晓） */
    val voice: String = DEFAULT_TTS_VOICE,
    /** 挂断后是否把转写落库 */
    val saveTranscript: Boolean = true,
) {
    companion object {
        const val DEFAULT_TTS_VOICE = "zh-CN-XiaoxiaoNeural"
    }
}

/** 通话期间内存暂存的一轮对话（挂断时一次性写 Room，partial 绝不入库） */
data class CallTurnData(
    val speaker: String, // "user" / "ai"
    val text: String,
    val timestamp: Long,
)
