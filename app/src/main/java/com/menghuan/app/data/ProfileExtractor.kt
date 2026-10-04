package com.menghuan.app.data

import org.json.JSONObject

/** AI 建议的任务（不落库：聊天页卡片确认后才写入 tasks） */
data class TaskSuggestion(
    val title: String,
    val note: String = "",
    val daily: Boolean = false,
    /** 到期日期 ISO yyyy-MM-dd；空 = 无日期（v0.38.0 日期型记忆） */
    val dueDate: String = "",
)

/**
 * 提炼 prompt 与响应解析（纯函数，JVM 单测覆盖）。
 * LLM 被要求只输出 JSON；但真实模型可能裹 markdown 代码栅栏或夹带说明文字，
 * 解析按"截取首个 { 到末个 }"宽容处理，任何失败返回空结果而不是抛异常。
 *
 * **v0.36.0 提示词分层**：
 * - [FRAMEWORK] = 硬编码不可改：JSON schema、字段名、"只输出 JSON"约束。
 *   改这个会让 [parse] 崩溃，所以不暴露给用户编辑。
 * - [DEFAULT_STRATEGY] = 用户可在「设置 · AI 提示词 · AI 洞察策略」里覆盖：
 *   宁缺毋滥的具体标准、什么算长期画像、字段长度约束、数量指引。
 * - [systemPrompt] = 拼接两者，作为最终发给 LLM 的 system prompt。
 *
 * **v0.36.0 数量松绑**：洞察 / 任务建议不再硬编码"最多 1 条"，
 * 改为 AI 根据信息价值自主决定（可以是 0 条），代码保留宽松软上限防灾
 * （[MAX_INSIGHTS] / [MAX_SUGGESTIONS]）——正常输出 0~3 条不受影响，
 * 只是防止某个模型突然吐 50 条把 UI 撑爆。
 */
object ProfileExtractor {

    /** 硬编码框架：JSON schema + 输出格式约束（不可编辑，改了 parse 会崩） */
    const val FRAMEWORK: String =
        "你是用户画像提炼器。从对话记录中提炼关于**用户本人**的长期画像信息与可执行任务建议，供后续对话参考。\n" +
            "铁律：对话记录里标注为「AI角色」的一方，是用户创建的虚拟角色在扮演（角色名见行首），**不是用户本人**。\n" +
            "角色的性格、设定、称呼、台词、情节走向一律不得写进 facts / insights / taskSuggestions；\n" +
            "也不要提炼用户与角色之间的关系、互动方式或对角色的态度——那不是用户本人的画像。\n" +
            "只提炼「用户：」一方真实、稳定的长期属性。\n" +
            "只输出 JSON，格式：\n" +
            "{\"facts\":[{\"key\":\"条目名\",\"value\":\"内容\"}]," +
            "\"insights\":[{\"title\":\"洞察标题\",\"detail\":\"洞察说明\"}]," +
            "\"taskSuggestions\":[{\"title\":\"任务名\",\"note\":\"备注\",\"daily\":true,\"date\":\"yyyy-MM-dd 可选：仅当用户明确提到未来某天要做的事（面试/约会/截止）时填，没提到就不加 date 字段\"}]}\n" +
            "全部用简体中文。不要输出 JSON 以外的任何文字。"

    /**
     * 宽容解析模型给的日期（纯函数，JVM 单测口径）：
     * 接受 2026-10-05 / 2026/10/5 / 10月5日（补当前年）；解析不出或无效返回空串。
     */
    fun normalizeDueDate(raw: String, today: java.time.LocalDate = java.time.LocalDate.now()): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        // 完整日期：2026-10-05 / 2026/10/5 / 2026年10月5日
        val full = Regex("^(\\d{4})[-/年](\\d{1,2})[-/月](\\d{1,2})日?$").find(text)
        if (full != null) {
            val y = full.groupValues[1].toIntOrNull() ?: return ""
            val mo = full.groupValues[2].toIntOrNull() ?: return ""
            val d = full.groupValues[3].toIntOrNull() ?: return ""
            return runCatching { java.time.LocalDate.of(y, mo, d).toString() }.getOrDefault("")
        }
        // 只有月日：10月5日 —— 补当前年；已过去超过 30 天的视为明年的日子
        val short = Regex("^(\\d{1,2})月(\\d{1,2})日?$").find(text) ?: return ""
        val mo = short.groupValues[1].toIntOrNull() ?: return ""
        val d = short.groupValues[2].toIntOrNull() ?: return ""
        return runCatching {
            var candidate = today.withMonth(mo).withDayOfMonth(d)
            if (candidate.isBefore(today.minusDays(30))) candidate = candidate.plusYears(1)
            candidate.toString()
        }.getOrDefault("")
    }

    /** 默认策略：宁缺毋滥 + 数量指引（用户可在设置里覆盖） */
    const val DEFAULT_STRATEGY: String =
        "规则：\n" +
            "0. 宁缺毋滥是最高原则：只记录对后续对话长期有价值、且对话中有明确依据的信息。" +
            "普通闲聊、寒暄、一次性情绪、临时话题、模糊猜测一律不记；没有值得记录的就输出空数组。\n" +
            "1. facts 只提炼用户本人的长期稳定属性：职业/学业、长期目标、重要作息与健康约束、明确的长期偏好。" +
            "明确不收：AI 角色的任何属性、一次性事件、当下的情绪、角色扮演情节、" +
            "用户与 AI 角色的关系或互动方式、称呼昵称、琐碎的聊天细节。" +
            "每条 value 不超过 40 字；与已知档案重复且无变化就不要输出。\n" +
            "2. insights 只在对话中出现明确的重复模式、或用户提供了具体数据支撑时才输出，" +
            "根据信息价值自主决定条数（宁少勿滥，可以是 0 条）；泛泛的鼓励、常识性建议、模棱两可的观察、" +
            "单次对话的具体内容、角色扮演里的情节都不要输出。\n" +
            "3. taskSuggestions 只在用户明确表达想做某事、或明确承认需要执行的行动时才建议，" +
            "根据信息价值自主决定条数（宁少勿滥，可以是 0 条）；必须具体、可执行、可打卡；不确定就不要输出。" +
            "daily=true 表示适合每天重复的任务。\n" +
            "4. title/detail 控制在 40 字以内，note 控制在 30 字以内。"

    /** 完整 system prompt = 框架 + 策略；策略为空/空白时只发框架 */
    fun systemPrompt(strategy: String = DEFAULT_STRATEGY): String =
        if (strategy.isBlank()) FRAMEWORK else "$FRAMEWORK\n$strategy"

    /** 软防灾上限：AI 自主决定条数，但极端情况（模型失控吐几十条）仍要保护 UI 与数据库 */
    private const val MAX_FACTS = 6
    private const val MAX_INSIGHTS = 10
    private const val MAX_SUGGESTIONS = 5

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
                            dueDate = normalizeDueDate(o.optString("date")),
                        )
                    }.getOrNull()
                } }.orEmpty()
            // 软防灾上限：正常输出远低于此，仅在模型失控时兜底
            Extracted(facts.take(MAX_FACTS), insights.take(MAX_INSIGHTS), suggestions.take(MAX_SUGGESTIONS))
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
