package com.menghuan.app.data

import com.menghuan.app.data.db.AppDatabase
import com.menghuan.app.data.model.DailySuggestion
import com.menghuan.app.data.model.LedgerEntry
import com.menghuan.app.data.model.LedgerMath
import com.menghuan.app.data.model.TaskItem
import com.menghuan.app.data.model.isEffectivelyDone
import com.menghuan.app.llm.OpenAiClient
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId

/**
 * 每日建议的输入素材（纯数据，JVM 单测口径）。
 *
 * 与 [WeeklyReportMath] 的区别：周报回看一周，每日建议只看**今天该做什么**，
 * 所以素材是「今日任务完成情况 + 近期档案/洞察 + 昨日流水概况」，而不是一周聚合。
 */
data class DailyContext(
    val day: LocalDate,
    /** 今日任务完成 x/y（每日任务 + 今日到期基础任务，同任务页口径） */
    val doneToday: Int,
    val totalToday: Int,
    /** 今日未完成任务名（最多 8 条，防 prompt 膨胀） */
    val pendingTitles: List<String>,
    /** 近 30 天档案（key → value，最多 10 条） */
    val facts: List<Pair<String, String>>,
    /** 近期洞察标题（最多 5 条） */
    val insights: List<String>,
    /** 昨日支出（分；无记录为 0，用于"花钱提醒"类建议的取材） */
    val yesterdayExpenseCents: Long,
    /** 当月支出（分） */
    val monthExpenseCents: Long,
)

/**
 * 每日建议 prompt 分层 + 宽容解析（纯函数，JVM 单测覆盖）。
 *
 * 分层原则同 [ProfileExtractor] / [WeeklyReportMath]：
 * - [FRAMEWORK]：输出格式约束（硬编码不可改），改了 [parse] 会解析失败
 * - [DEFAULT_STRATEGY]：选材与语气（用户可在「设置 · AI 提示词 · 每日建议策略」里覆盖）
 */
object DailySuggestionMath {

    const val FRAMEWORK: String =
        "你是每日建议生成器。只输出 JSON，格式：\n" +
            "{\"title\":\"一句话建议（不超过 20 字，可直接作为任务标题）\"," +
            "\"detail\":\"展开说明（不超过 80 字：为什么给这条建议 + 怎么执行）\"}\n" +
            "全部用简体中文。不要输出 JSON 以外的任何文字（不要代码栅栏、不要解释）。"

    const val DEFAULT_STRATEGY: String =
        "根据用户今天的状态给出一条今天就能执行的建议。要求：\n" +
            "1. 只给一条，必须是今天可做的具体动作（不要「长期要坚持」这类无法执行的话）。\n" +
            "2. 优先取材：今天还没完成的任务（可给出更小的第一步）、近期的档案约束（作息/健康/学业），" +
            "以及最近的支出情况（只在明显异常时才提，不要每期都算钱）。\n" +
            "3. 语气平实直接，像靠谱的朋友提醒一句，不说教、不打鸡血、不扮演角色、不用感叹号堆情绪。\n" +
            "4. 信息不足时给一条低门槛的通用建议（如「把今天最想拖着不做的那件事先做 10 分钟」），不要编造用户没说过的事。"

    fun systemPrompt(strategy: String = DEFAULT_STRATEGY): String =
        if (strategy.isBlank()) FRAMEWORK else "$FRAMEWORK\n$strategy"

    /** 素材 → user 消息内容（单测校验关键信息确实进了 prompt） */
    fun buildPrompt(ctx: DailyContext): String = buildString {
        append("[今天] ${ctx.day.monthValue}月${ctx.day.dayOfMonth}日\n")
        append("[今日任务] 完成 ${ctx.doneToday}/${ctx.totalToday} 项\n")
        if (ctx.pendingTitles.isNotEmpty()) {
            append("[还没做的]\n")
            ctx.pendingTitles.forEach { append("- $it\n") }
        }
        if (ctx.facts.isNotEmpty()) {
            append("[用户档案]\n")
            ctx.facts.forEach { append("- ${it.first}：${it.second}\n") }
        }
        if (ctx.insights.isNotEmpty()) {
            append("[近期洞察]\n")
            ctx.insights.forEach { append("- $it\n") }
        }
        if (ctx.yesterdayExpenseCents > 0 || ctx.monthExpenseCents > 0) {
            append("[支出情况] 昨日 ${LedgerMath.formatCents(ctx.yesterdayExpenseCents)} 元，")
            append("本月累计 ${LedgerMath.formatCents(ctx.monthExpenseCents)} 元\n")
        }
        append("请给出今天的一条建议。")
    }

    /**
     * 宽容解析：模型可能裹代码栅栏 / 加说明文字 / 只回一段纯文本。
     * 与周报解析同一套路——能解析成 JSON 就取 String 类型的 title/detail
     * （optString 会把 JSON 数字强转成字面量，这是 v0.20.1 的教训），
     * 拿不到 JSON 就把整段当标题用（短文本直接可用），空白视为失败。
     */
    fun parse(raw: String): Pair<String, String>? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start >= 0 && end > start) {
            val json = runCatching { org.json.JSONObject(trimmed.substring(start, end + 1)) }.getOrNull()
            if (json != null) {
                val title = (json.opt("title") as? String)?.trim().orEmpty()
                val detail = (json.opt("detail") as? String)?.trim().orEmpty()
                if (title.isNotEmpty()) return title.take(40) to detail.take(160)
                return null // 确实是 JSON 但没有可用 title：整体视为无效
            }
        }
        // 纯文本兜底：剥掉代码栅栏，第一行当标题、其余当说明
        val fenced = Regex("```[a-zA-Z]*\\s*([\\s\\S]*?)```").find(trimmed)?.groupValues?.get(1)?.trim()
        val text = (fenced ?: trimmed).trim()
        if (text.isEmpty()) return null
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val title = lines.firstOrNull()?.removePrefix("-")?.trim()?.take(40).orEmpty()
        if (title.isEmpty()) return null
        return title to lines.drop(1).joinToString(" ").take(160)
    }
}

/**
 * 每日建议生成器（v0.42.0）：
 * - 输入 = 今日任务完成情况 + 近 30 天档案 + 近期洞察 + 支出概况 → LLM → 一条建议落库
 * - 落库 daily_suggestions（同 day 覆盖）；API 未配置 → 返回 null（静默）
 * - [ensureToday] = 补生成：仅当今天尚无建议时生成（App 启动 / BootReceiver / 每日闹钟）
 */
class DailySuggestionGenerator(
    private val db: AppDatabase,
    private val settingsRepository: SettingsRepository,
) {

    /** 生成指定日期的建议并落库；无 API / 失败返回 null */
    suspend fun generateForDay(
        day: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): DailySuggestion? {
        val config = settingsRepository.apiConfig.first()
        if (!config.isComplete) return null

        val ctx = collectContext(day, zone)

        val sb = StringBuilder()
        val strategy = runCatching { settingsRepository.dailySuggestionStrategy.first() }
            .getOrDefault(DailySuggestionMath.DEFAULT_STRATEGY)
        val reply = OpenAiClient(config.baseUrl, config.apiKey, config.model).collectReply(
            DailySuggestionMath.systemPrompt(strategy),
            listOf(
                com.menghuan.app.data.model.ChatMessage(
                    conversationId = 0,
                    isFromUser = true,
                    content = DailySuggestionMath.buildPrompt(ctx),
                )
            ),
        )
        sb.append(reply.text)
        runCatching { UsageTracker.add(settingsRepository, reply.usageTokens ?: 0) }
        val parsed = DailySuggestionMath.parse(sb.toString()) ?: return null

        db.dailySuggestionDao().upsert(
            DailySuggestion(
                day = day.toEpochDay(),
                title = parsed.first,
                detail = parsed.second,
            )
        )
        return db.dailySuggestionDao().getByDay(day.toEpochDay())
    }

    /** 补生成：今天还没建议才生成（静默，失败不抛出） */
    suspend fun ensureToday(day: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): DailySuggestion? {
        if (db.dailySuggestionDao().getByDay(day.toEpochDay()) != null) return null
        return runCatching { generateForDay(day, zone) }.getOrNull()
    }

    /** 汇总生成素材（可被测试单独调用） */
    suspend fun collectContext(day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): DailyContext {
        val tasks = runCatching { db.taskDao().observeAll().first() }.getOrDefault(emptyList())
        val facts = runCatching { db.profileFactDao().observeAll().first() }.getOrDefault(emptyList())
        val insights = runCatching { db.aiInsightDao().latest() }.getOrDefault(emptyList())
        val ledger = runCatching { db.ledgerDao().observeAll().first() }.getOrDefault(emptyList())

        val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val todays = tasks.filter {
            it.repeatDaily || it.dueAt == 0L || (it.dueAt >= dayStart && it.dueAt < dayEnd)
        }
        val todayEpochDay = day.toEpochDay()

        val yesterday = day.minusDays(1)
        val yStart = yesterday.atStartOfDay(zone).toInstant().toEpochMilli()
        val yEnd = dayStart

        return DailyContext(
            day = day,
            doneToday = todays.count { it.isEffectivelyDone(todayEpochDay) },
            totalToday = todays.size,
            pendingTitles = todays
                .filter { !it.isEffectivelyDone(todayEpochDay) }
                .map { it.title }
                .take(8),
            facts = facts.takeLast(10).map { it.key to it.value },
            insights = insights.take(5).map { it.title },
            yesterdayExpenseCents = expenseBetween(ledger, yStart, yEnd),
            monthExpenseCents = LedgerMath.monthTotals(ledger, day.year, day.monthValue, zone).expenseCents,
        )
    }

    private fun expenseBetween(entries: List<LedgerEntry>, fromMs: Long, toMs: Long): Long =
        entries.filter { !it.isIncome && it.occurredAt >= fromMs && it.occurredAt < toMs }
            .sumOf { it.amountCents }
}
