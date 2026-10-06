package com.menghuan.app.data

import com.menghuan.app.data.db.AppDatabase
import com.menghuan.app.data.model.AiInsight
import com.menghuan.app.data.model.AiWeeklyReport
import com.menghuan.app.data.model.ChatMessage
import com.menghuan.app.data.model.Persona
import com.menghuan.app.data.model.ProfileFact
import com.menghuan.app.data.model.TaskCompletion
import com.menghuan.app.data.model.TaskItem
import com.menghuan.app.llm.OpenAiClient
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 周报统计聚合（纯数据，prompt 注入 + JVM 单测）。
 * 口径同 AGENTS §五 / 数据页：完成度只算基础任务、打卡只算每日任务。
 */
data class WeeklyStats(
    val weekStart: LocalDate,
    val weekEnd: LocalDate,
    val basicTotal: Int,
    val basicDone: Int,
    /** 每日任务打卡次数（仅 repeatDaily 任务的完成记录） */
    val dailyCompletions: Int,
    /** 每日任务打卡明细：任务名 → 次数 */
    val completionsByTitle: Map<String, Int>,
    /** 本周更新的档案（key → value，AI 来源标记 source） */
    val changedFacts: List<ProfileFact>,
    /** 本周产生的洞察（旧→新，最多 20 条） */
    val insights: List<AiInsight>,
)

/** 周报纯函数区：周区间 / 统计聚合 / prompt 组装 / 宽容解析（JVM 单测覆盖） */
object WeeklyReportMath {

    /**
     * 周区间（周一含 .. 周日含）：anchor 落在的 ISO 周。
     * 基础任务 dueAt = 0 视为「今天」——只在其今天落在区间内才计入（与数据页同口径）。
     */
    fun weekRange(anchor: LocalDate): Pair<LocalDate, LocalDate> {
        val monday = anchor.with(DayOfWeek.MONDAY)
        return monday to monday.plusDays(6)
    }

    /** 上一周区间（补生成用） */
    fun lastWeekRange(anchor: LocalDate): Pair<LocalDate, LocalDate> {
        val (monday, _) = weekRange(anchor)
        val lastMonday = monday.minusDays(7)
        return lastMonday to lastMonday.plusDays(6)
    }

    /**
     * 从原始表聚合一周统计：
     * - 基础任务：dueDate（dueAt=0 → anchor/今天）落在区间内者为「本周任务」
     * - 每日打卡：只统计 repeatDaily 任务的完成记录
     * - 档案：updatedAt 落在区间内（AI 徽标随 source 字段带给 prompt）
     * - 洞察：createdAt 落在区间内
     */
    fun aggregate(
        tasks: List<TaskItem>,
        completions: List<TaskCompletion>,
        facts: List<ProfileFact>,
        insights: List<AiInsight>,
        weekStart: LocalDate,
        weekEnd: LocalDate,
        zone: ZoneId,
        anchor: LocalDate = LocalDate.now(zone),
    ): WeeklyStats {
        val startMs = weekStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = weekEnd.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val anchorDay = anchor.toEpochDay()

        fun dueDayOf(task: TaskItem): Long =
            if (task.dueAt == 0L) anchorDay
            else Instant.ofEpochMilli(task.dueAt).atZone(zone).toLocalDate().toEpochDay()

        val basic = tasks.filter { !it.repeatDaily }
        val inWeek = basic.filter { dueDayOf(it) in weekStart.toEpochDay()..weekEnd.toEpochDay() }
        val dailyIds = tasks.filter { it.repeatDaily }.map { it.id }.toSet()
        val weekCompletions = completions.filter { it.day in weekStart.toEpochDay()..weekEnd.toEpochDay() }
        val dailyCompletions = weekCompletions.filter { it.taskId in dailyIds }
        val titleById = tasks.associate { it.id to it.title }
        val completionsByTitle = dailyCompletions
            .mapNotNull { completion -> titleById[completion.taskId]?.let { it to completion } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, list) -> list.size }

        return WeeklyStats(
            weekStart = weekStart,
            weekEnd = weekEnd,
            basicTotal = inWeek.size,
            basicDone = inWeek.count { it.isDone },
            dailyCompletions = dailyCompletions.size,
            completionsByTitle = completionsByTitle,
            changedFacts = facts.filter { it.updatedAt in startMs until endMs },
            insights = insights.filter { it.createdAt in startMs until endMs }.take(20),
        )
    }

    /**
     * 周报 system prompt 分层（v0.36.0，同 [com.menghuan.app.data.ProfileExtractor] 的独立性原则）：
     * - [FRAMEWORK]：输出格式约束（硬编码不可改），改了 [parseReport] 会解析失败
     * - [DEFAULT_STRATEGY]：字数 / 语气 / 结构（用户可在「设置 · AI 提示词 · AI 周报策略」里覆盖）
     * - [systemPrompt]：拼接两者，作为最终发给 LLM 的 system prompt
     */
    const val FRAMEWORK: String =
        "你是周报撰写器。只输出周报正文纯文本，不要标题，不要 markdown 代码栅栏，" +
            "不要 JSON 以外的解释。若输入信息不足以写某部分，直接跳过该部分。" +
            "对话记录里标注为「某某（AI）」的一方，是用户创建的虚拟角色在扮演，不是用户本人；" +
            "周报只写用户本人的真实生活，不要把角色的设定、称呼或扮演情节当作用户的事写进去。"

    const val DEFAULT_STRATEGY: String =
        "根据用户本周的任务完成、打卡、档案变更与对话记录，写一份 200–400 字的简体中文周报。" +
            "要求：语气中性客观，只陈述与总结事实，不扮演任何角色，不带人称滤镜；" +
            "先总结任务与打卡完成情况，再点出值得注意的规律或变化（如有），" +
            "最后给 1–2 条下周建议（如有依据）。"

    fun systemPrompt(strategy: String = DEFAULT_STRATEGY): String =
        if (strategy.isBlank()) FRAMEWORK else "$FRAMEWORK\n$strategy"

    /** 统计 + 聊天摘要（+ 上期周报要点）→ 单条 user 消息内容（单测校验关键数字在文中） */
    fun buildReportPrompt(
        stats: WeeklyStats,
        transcript: String,
        previousDigest: String? = null,
    ): String = buildString {
        append("[统计周期] ${stats.weekStart.monthValue}月${stats.weekStart.dayOfMonth}日 – " +
            "${stats.weekEnd.monthValue}月${stats.weekEnd.dayOfMonth}日\n")
        append("[基础任务] 完成 ${stats.basicDone}/${stats.basicTotal} 项\n")
        append("[每日任务打卡] 共 ${stats.dailyCompletions} 次")
        if (stats.completionsByTitle.isNotEmpty()) {
            append("：")
            append(
                stats.completionsByTitle.entries
                    .sortedByDescending { it.value }
                    .joinToString("、") { "${it.key}（${it.value} 次）" }
            )
        }
        append("\n")
        if (stats.changedFacts.isNotEmpty()) {
            append("[本周档案更新]\n")
            stats.changedFacts.forEach {
                append("- ${it.key}：${it.value}${if (it.source == "ai") "（AI 提炼）" else ""}\n")
            }
        }
        if (stats.insights.isNotEmpty()) {
            append("[本周 AI 洞察]\n")
            stats.insights.forEach { append("- ${it.title}：${it.detail}\n") }
        }
        if (transcript.isNotBlank()) {
            append("[本周对话节选]\n$transcript\n")
        }
        if (!previousDigest.isNullOrBlank()) {
            append("[上一期周报要点]\n$previousDigest\n")
        }
        append("请基于以上信息撰写周报正文。")
    }

    /**
     * 宽容解析：模型可能输出纯文本 / JSON {"content": ...} / 裹代码栅栏。
     * 大括号段能解析成 JSON 时只接受 String 类型的 content（数字等会被 optString
     * 强转成字面量，同 v0.20.1 OpenAiClient 的教训），无效即视为失败返回空串；
     * 否则按纯文本处理（剥栅栏），空白视为失败。
     */
    fun parseReport(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start >= 0 && end > start) {
            val json = runCatching { org.json.JSONObject(trimmed.substring(start, end + 1)) }.getOrNull()
            if (json != null) {
                val content = json.opt("content")
                if (content is String && content.isNotBlank()) return content.trim()
                return "" // 确实是 JSON 输出但没有可用的字符串 content：整体视为无效
            }
            // 大括号只是正文里的普通字符（解析不成 JSON）：按纯文本处理
        }
        val fenced = Regex("```[a-zA-Z]*\\s*([\\s\\S]*?)```").find(trimmed)?.groupValues?.get(1)?.trim()
        return (fenced ?: trimmed).trim()
    }

    /** 聊天记录摘要：按会话名标注角色，单条与总长截断（防 token 爆炸）；可排除指定会话（如应用助手） */
    fun buildTranscript(
        messages: List<ChatMessage>,
        personas: List<Persona>,
        maxTotalChars: Int = 4000,
        maxMessageChars: Int = 200,
        excludeConversationIds: Set<Long> = emptySet(),
    ): String {
        val visible = if (excludeConversationIds.isEmpty()) messages
        else messages.filter { it.conversationId !in excludeConversationIds }
        if (visible.isEmpty()) return ""
        val nameById = personas.associate { it.id to it.name }
        val sb = StringBuilder()
        for (message in visible) {
            if (sb.length >= maxTotalChars) break
            val speaker = if (message.isFromUser) "用户" else "${nameById[message.conversationId] ?: "AI"}（AI）"
            val content = message.content.replace('\n', ' ').take(maxMessageChars)
            sb.append("$speaker：$content\n")
        }
        return sb.toString().trim().take(maxTotalChars)
    }

    /** 分享文本（周报卡长按 → 系统分享）：期标签 + 正文 */
    fun shareText(report: AiWeeklyReport): String {
        val start = LocalDate.ofEpochDay(report.weekStart)
        val end = LocalDate.ofEpochDay(report.weekEnd)
        val period = "${start.monthValue}月${start.dayOfMonth}日 – ${end.monthValue}月${end.dayOfMonth}日"
        return "【梦欢 · AI 周报】$period\n\n${report.content}"
    }
}

/**
 * AI 周报生成器：
 * - 输入 = 本周任务/打卡/档案变更/洞察 + 本周对话节选（截断），经 OpenAiClient 生成 200–400 字周报
 * - 落库 ai_weekly_reports（同周覆盖）；API 未配置 → 返回 null（静默）
 * - [ensureReport] = 补生成：仅当该周尚无报告时生成（App 启动 / BootReceiver / 周日报钟触发）
 */
class WeeklyReportGenerator(
    private val db: AppDatabase,
    private val settingsRepository: SettingsRepository,
) {

    /** 生成指定周（weekStart = 周一）的周报并落库；无 API / 失败返回 null */
    suspend fun generateForWeek(
        weekStart: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): AiWeeklyReport? {
        val config = settingsRepository.apiConfig.first()
        if (!config.isComplete) return null

        val weekEnd = weekStart.plusDays(6)
        val startMs = weekStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = weekEnd.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val tasks = runCatching { db.taskDao().observeAll().first() }.getOrDefault(emptyList())
        val completions = runCatching { db.taskCompletionDao().observeAll().first() }.getOrDefault(emptyList())
        val facts = runCatching { db.profileFactDao().observeAll().first() }.getOrDefault(emptyList())
        val insights = runCatching { db.aiInsightDao().latest() }.getOrDefault(emptyList())
        val messages = runCatching { db.messageDao().getBetween(startMs, endMs) }.getOrDefault(emptyList())
        val personas = runCatching { db.personaDao().observeAll().first() }.getOrDefault(emptyList())

        val stats = WeeklyReportMath.aggregate(
            tasks = tasks,
            completions = completions,
            facts = facts,
            insights = insights,
            weekStart = weekStart,
            weekEnd = weekEnd,
            zone = zone,
        )
        val prompt = WeeklyReportMath.buildReportPrompt(
            stats = stats,
            transcript = WeeklyReportMath.buildTranscript(
                messages = messages,
                personas = personas,
                // 应用助手会话（使用指南闲聊）不进周报转录
                excludeConversationIds = personas
                    .filter { it.name == ASSISTANT_PERSONA_NAME }
                    .map { it.id }
                    .toSet(),
            ),
            // 上期周报要点（前 200 字）：让连续数周的周报有承接感
            previousDigest = runCatching {
                db.aiWeeklyReportDao().getByWeekStart(weekStart.minusDays(7).toEpochDay())?.content?.take(200)
            }.getOrNull(),
        )

        val sb = StringBuilder()
        // 提示词策略可由用户在「设置 · AI 提示词 · AI 周报策略」里覆盖；框架部分硬编码
        val strategy = runCatching { settingsRepository.weeklyReportStrategy.first() }
            .getOrDefault(WeeklyReportMath.DEFAULT_STRATEGY)
        val reply = OpenAiClient(config.baseUrl, config.apiKey, config.model)
            .collectReply(WeeklyReportMath.systemPrompt(strategy), listOf(ChatMessage(conversationId = 0, isFromUser = true, content = prompt)))
        sb.append(reply.text)
        runCatching { UsageTracker.add(settingsRepository, reply.usageTokens ?: 0, config.model) }
        val content = WeeklyReportMath.parseReport(sb.toString())
        if (content.isBlank()) return null

        db.aiWeeklyReportDao().upsert(
            AiWeeklyReport(
                weekStart = weekStart.toEpochDay(),
                weekEnd = weekEnd.toEpochDay(),
                content = content.take(MAX_CONTENT_CHARS),
            )
        )
        return db.aiWeeklyReportDao().getByWeekStart(weekStart.toEpochDay())
    }

    /** 补生成：该周尚无报告才生成（静默，失败不抛出）；返回新生成的那期，未生成返回 null */
    suspend fun ensureReport(weekStart: LocalDate, zone: ZoneId = ZoneId.systemDefault()): AiWeeklyReport? {
        if (db.aiWeeklyReportDao().getByWeekStart(weekStart.toEpochDay()) != null) return null
        return runCatching { generateForWeek(weekStart, zone) }.getOrNull()
    }

    private companion object {
        const val MAX_CONTENT_CHARS = 2000
    }
}
