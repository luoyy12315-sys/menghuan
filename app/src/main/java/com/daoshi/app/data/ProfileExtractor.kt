package com.daoshi.app.data

import org.json.JSONObject

/** AI 建议的任务（不落库：聊天页卡片确认后才写入 tasks） */
data class TaskSuggestion(
    val title: String,
    val note: String = "",
    val daily: Boolean = false,
)

/**
 * 提炼 prompt 与响应解析（纯函数，JVM 单测覆盖）。
 * LLM 被要求只输出 JSON；但真实模型可能裹 markdown 代码栅栏或夹带说明文字，
 * 解析按"截取首个 { 到末个 }"宽容处理，任何失败返回空结果而不是抛异常。
 */
object ProfileExtractor {

    /** 提炼请求的 system prompt（对话以"用户：/导师："文本行注入为单条 user 消息） */
    const val SYSTEM_PROMPT: String =
        "你是用户画像提炼器。从对话记录中提炼关于用户的长期画像信息与可执行任务建议，供用户的导师后续使用。\n" +
            "只输出 JSON，格式：\n" +
            "{\"facts\":[{\"key\":\"条目名\",\"value\":\"内容\"}]," +
            "\"insights\":[{\"title\":\"洞察标题\",\"detail\":\"洞察说明\"}]," +
            "\"taskSuggestions\":[{\"title\":\"任务名\",\"note\":\"备注\",\"daily\":true}]}\n" +
            "规则：\n" +
            "1. facts 提炼稳定画像：目标、作息、职业、偏好、困扰等；只输出对话中明确出现的信息；与已有档案重复且无更新就不要输出。\n" +
            "2. insights 是从对话看出的行为模式或可行建议，最多 2 条，没有就空数组。\n" +
            "3. taskSuggestions 是从对话中提炼的、用户适合立刻建立的任务（具体、可执行、可打卡），最多 2 条，没有就空数组；daily=true 表示适合每天重复的任务。\n" +
            "4. 全部用简体中文，value/title/detail 控制在 40 字以内，note 控制在 30 字以内。\n" +
            "5. 不要输出 JSON 以外的任何文字。"

    data class Extracted(
        val facts: List<Pair<String, String>> = emptyList(),
        val insights: List<Pair<String, String>> = emptyList(),
        val taskSuggestions: List<TaskSuggestion> = emptyList(),
    )

    /** 模型输出 → (档案条目, 洞察, 任务建议)；单条畸形数据跳过，整体失败返回空 */
    fun parse(raw: String): Extracted {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return Extracted()
        return runCatching {
            val root = JSONObject(raw.substring(start, end + 1))
            val facts = root.optJSONArray("facts")
                ?.let { arr -> (0 until arr.length()).mapNotNull { i ->
                    runCatching {
                        val o = arr.getJSONObject(i)
                        val k = o.optString("key").trim()
                        val v = o.optString("value").trim()
                        if (k.isNotEmpty() && v.isNotEmpty()) k to v else null
                    }.getOrNull()
                } }.orEmpty()
            val insights = root.optJSONArray("insights")
                ?.let { arr -> (0 until arr.length()).mapNotNull { i ->
                    runCatching {
                        val o = arr.getJSONObject(i)
                        val t = o.optString("title").trim()
                        val d = o.optString("detail").trim()
                        if (t.isNotEmpty() && d.isNotEmpty()) t to d else null
                    }.getOrNull()
                } }.orEmpty()
            val suggestions = root.optJSONArray("taskSuggestions")
                ?.let { arr -> (0 until arr.length()).mapNotNull { i ->
                    runCatching {
                        val o = arr.getJSONObject(i)
                        val t = o.optString("title").trim()
                        if (t.isEmpty()) return@mapNotNull null
                        TaskSuggestion(
                            title = t.take(40),
                            note = o.optString("note").trim().take(60),
                            daily = o.optBoolean("daily", false),
                        )
                    }.getOrNull()
                } }.orEmpty()
            Extracted(facts.take(6), insights.take(2), suggestions.take(2))
        }.getOrDefault(Extracted())
    }

    /**
     * 建议去重（纯函数）：跳过已存在同名任务的、当日已建议过的；同轮内按标题去重。
     * 同 title 当日不重复建议的日期状态由 TaskSuggestionHub 维护。
     */
    fun filterSuggestions(
        suggestions: List<TaskSuggestion>,
        existingTaskTitles: Set<String>,
        suggestedToday: Set<String>,
    ): List<TaskSuggestion> = suggestions
        .filter { it.title !in existingTaskTitles && it.title !in suggestedToday }
        .distinctBy { it.title }
}
