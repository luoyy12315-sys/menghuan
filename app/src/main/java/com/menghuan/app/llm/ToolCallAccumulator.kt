package com.menghuan.app.llm

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一次完整的工具调用（流式分片聚合后的产物，P3-1）。
 *
 * [argumentsJson] 是**未解析的原始 JSON 串**——模型不保证生成合法 JSON，
 * 也不保证参数与 schema 一致（OpenAI 官方文档原话），解析与校验一律留给执行侧；
 * 这里只做"把分片拼回去"这一件事，拼错了也比擅自修补强。
 */
data class PendingToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

/**
 * OpenAI 流式 `tool_calls` 分片聚合器（P3-1，纯 JVM 可测）。
 *
 * 协议事实（OpenAI 官方 function-calling 指南 + langchain4j / openai-kotlin 的聚合逻辑）：
 * - 每个分片带 `index`，**同一个 index 属于同一次调用**；一轮可以有多个并行调用，
 *   必须按 index 分槽，绝不能共用一个缓冲（共用 = 并行调用时参数 JSON 互相污染）。
 * - 只有某个 index 的**第一个分片**带 `id` 与 `function.name`，后续分片这两个字段
 *   为 null，只带 `function.arguments` 的增量串；部分兼容网关会把 name/id 也切片，
 *   所以 id/name 一律**追加**而不是赋值（只发一次的网关下追加与赋值等价）。
 * - `finish_reason == "tool_calls"` 只是提示，不依赖它：流结束（[DONE] 或连接耗尽）
 *   时槽位非空即视为完整一轮调用请求。
 */
class ToolCallAccumulator {

    private class Slot {
        val id = StringBuilder()
        val name = StringBuilder()
        val args = StringBuilder()
    }

    /** TreeMap：结果按 index 升序，与模型发出顺序一致，也保证测试可断言 */
    private val slots = java.util.TreeMap<Int, Slot>()

    val isEmpty: Boolean get() = slots.isEmpty()

    /** 喂入一个 chunk 的 `delta.tool_calls` 数组（没有该字段时调用方根本不该调这里） */
    fun accept(toolCalls: JSONArray) {
        for (i in 0 until toolCalls.length()) {
            val tc = toolCalls.optJSONObject(i) ?: continue
            // index 缺省时按数组位置算：兼容极少数不写 index 的网关（单调用场景不会错）
            val index = tc.optInt("index", i)
            val slot = slots.getOrPut(index) { Slot() }
            appendIfString(slot.id, tc.opt("id"))
            val fn = tc.optJSONObject("function") ?: continue
            appendIfString(slot.name, fn.opt("name"))
            appendIfString(slot.args, fn.opt("arguments"))
        }
    }

    /** 流结束后取聚合结果；id 缺失时补一个占位 id（协议要求回灌时 id 必须对应得上） */
    fun result(): List<PendingToolCall> = slots.map { (index, s) ->
        PendingToolCall(
            id = s.id.toString().ifBlank { "call_$index" },
            name = s.name.toString(),
            argumentsJson = s.args.toString(),
        )
    }

    private fun appendIfString(target: StringBuilder, value: Any?) {
        // org.json 会把 JSON null 读成 JSONObject.NULL / 字面量，只有真 String 才追加
        if (value is String && value.isNotEmpty()) target.append(value)
    }
}
