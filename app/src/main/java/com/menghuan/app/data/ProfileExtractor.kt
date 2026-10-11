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
 * **提示词分层**：
 * - [FRAMEWORK] = 硬编码不可改：JSON schema、字段名、"只输出 JSON"约束。
 *   改这个会让 [parse] 崩溃，所以不暴露给用户编辑。
 * - [DEFAULT_STRATEGY] = 用户可在「设置 · AI 提示词 · 记忆提炼策略」里覆盖：
 *   宁缺毋滥的具体标准、什么算长期画像、字段长度约束、数量指引。
 * - [systemPrompt] = 拼接两者，作为最终发给 LLM 的 system prompt。
 *
 * **v0.88.0 职责收窄：这里现在只产 facts（记忆）**。
 * insights 挪去 `InsightEngine`（改从「记忆+任务+记账」按天/周/月归纳），
 * taskSuggestions 挪去主对话链（AI 用 `[建议任务:…]` 标记主动触发）。
 * 一次对话提炼只解决一件事，提示词与判据都短了一半。
 *
 * **v0.36.0 数量松绑**：不再硬编码"最多 1 条"，改由 AI 按信息价值自主决定（可以是 0 条），
 * 代码保留宽松软上限防灾（[MAX_FACTS]）——正常输出 0~3 条不受影响，
 * 只是防止某个模型突然吐 50 条把 UI 撑爆。
 */
object ProfileExtractor {

    /** 硬编码框架：JSON schema + 输出格式约束（不可编辑，改了 parse 会崩） */
    const val FRAMEWORK: String =
        "你是用户画像提炼器。从对话记录中提炼关于**用户本人**的长期画像信息，供后续对话参考。\n" +
            "铁律：对话记录里标注为「AI角色」的一方，是用户创建的虚拟角色在扮演（角色名见行首），**不是用户本人**。\n" +
            "角色的性格、设定、称呼、台词、情节走向一律不得写进 facts；\n" +
            "也不要提炼用户与角色之间的关系、互动方式或对角色的态度——那不是用户本人的画像。\n" +
            "只提炼「用户：」一方真实、稳定的长期属性。\n" +
            "只输出 JSON，格式：\n" +
            "{\"facts\":[{\"key\":\"条目名\",\"value\":\"内容\"}]}\n" +
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

    /** 默认策略：宁缺毋滥 + 命名规范（用户可在设置里覆盖） */
    const val DEFAULT_STRATEGY: String =
        "规则：\n" +
            "0. 宁缺毋滥是最高原则：只记录对后续对话长期有价值、且对话中有明确依据的信息。" +
            "普通闲聊、寒暄、一次性情绪、临时话题、模糊猜测一律不记；没有值得记录的就输出空数组。\n" +
            "1. 只提炼用户本人的长期稳定属性：职业/学业、长期目标、重要作息与健康约束、明确的长期偏好。" +
            "明确不收：AI 角色的任何属性、一次性事件、当下的情绪、角色扮演情节、" +
            "用户与 AI 角色的关系或互动方式、称呼昵称、琐碎的聊天细节。" +
            "每条 value 不超过 40 字；与已知档案重复且无变化就不要输出。\n" +
            "1.1 **条目名（key）的命名规范**（v0.85.0，用户反馈「记忆老是重复」）：" +
            "用 2-6 字的**名词**，直接说是什么事（「工作」「作息」「口味」「生日」「过敏」），" +
            "**不要加修饰、不要带版本感**（不写「目前的工作」「最近的工作情况」，就写「工作」）。" +
            "同一个概念永远用同一个名字 —— 要记的事如果和上面【已经记住的条目名】里某条是同一件事，" +
            "**必须复用那个名字**（哪怕你觉得另一个说法更好），只更新 value；" +
            "换个说法重新起名会在库里变成两条重复记忆，这是明确要避免的。\n" +
            "2. 一次可以输出多条，也可以一条都不输出（0 条是正常的，不是失败）。"

    /** 完整 system prompt = 框架 + 策略；策略为空/空白时只发框架 */
    fun systemPrompt(strategy: String = DEFAULT_STRATEGY): String =
        if (strategy.isBlank()) FRAMEWORK else "$FRAMEWORK\n$strategy"

    /** 软防灾上限：AI 自主决定条数，但极端情况（模型失控吐几十条）仍要保护 UI 与数据库 */
    private const val MAX_FACTS = 6

    data class Extracted(
        val facts: List<Pair<String, String>> = emptyList(),
    )

    /** 模型输出 → 档案条目；单条畸形数据跳过，整体失败返回空 */
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
            Extracted(facts.take(MAX_FACTS))
        }.getOrDefault(Extracted())
    }
}
