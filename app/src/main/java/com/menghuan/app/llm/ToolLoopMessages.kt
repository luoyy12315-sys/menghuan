package com.menghuan.app.llm

import org.json.JSONArray
import org.json.JSONObject

/**
 * tools 回环里两类"中间消息"的构建（P3-1，纯函数 JVM 可测）。
 *
 * 这些消息**只活在单次回复的请求重放里**（OpenAiClient.streamEvents 的 extraMessages），
 * 不落 Room：assistant 的 tool_calls 与 role:tool 结果是本轮推理的中间产物，
 * 落库会污染对话历史与 20 万字预算截尾的语义。
 */

/**
 * assistant 的 tool_calls 回显消息（OpenAI 协议要求：回灌工具结果前，
 * 必须先把"模型要求调用"这条 assistant 消息原样带回，id 一一对应）。
 *
 * [content] 是本轮已经流出的正文（可能为空：模型不讲话直接要工具）——
 * 空时发 JSON null 而不是空串，与官方示例一致。
 */
fun buildAssistantToolCallsMessage(calls: List<PendingToolCall>, content: String): JSONObject {
    val arr = JSONArray()
    calls.forEach { c ->
        arr.put(
            JSONObject()
                .put("id", c.id)
                .put("type", "function")
                .put(
                    "function",
                    JSONObject()
                        .put("name", c.name)
                        .put("arguments", c.argumentsJson),
                ),
        )
    }
    return JSONObject()
        .put("role", "assistant")
        .put("content", if (content.isBlank()) JSONObject.NULL else content)
        .put("tool_calls", arr)
}

/** role:tool 结果消息：tool_call_id 必须与 assistant 消息里的 id 对得上 */
fun buildToolResultMessage(toolCallId: String, content: String): JSONObject =
    JSONObject()
        .put("role", "tool")
        .put("tool_call_id", toolCallId)
        .put("content", content)
