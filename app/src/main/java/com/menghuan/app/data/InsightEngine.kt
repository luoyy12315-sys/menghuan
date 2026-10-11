package com.menghuan.app.data

import android.util.Log
import com.menghuan.app.data.db.AppDatabase
import com.menghuan.app.data.model.AiInsight
import com.menghuan.app.data.model.ChatMessage
import com.menghuan.app.data.model.InsightPeriod
import com.menghuan.app.data.model.LedgerMath
import com.menghuan.app.data.model.TaskItem
import com.menghuan.app.data.model.isEffectivelyDone
import com.menghuan.app.llm.OpenAiClient
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId

/**
 * 洞察的输入素材（纯数据，JVM 单测口径）。
 *
 * **与 v0.87.0 及以前最大的区别：素材里没有一句对话转录。**
 * 以前洞察是 `ExtractionEngine` 提炼对话时的副产品 —— 本质是"这段聊天给我的读后感"，
 * 与用户真实在做什么无关（用户点名：「AI 洞察肯定不是调用聊天记录」）。
 * 现在一次洞察 = 把**已经沉淀下来的结构化数据**归纳一遍：
 * 记忆（长期画像）+ 任务（执行情况）+ 记账（钱的流向）。
 */
data class InsightContext(
    /** 归属日：日洞察 = 当天，周洞察 = 周日当天，月洞察 = 月末当天 */
    val day: LocalDate,
    val period: String,
    /** 本期内新增/更新过的记忆（key → value） */
    val facts: List<Pair<String, String>>,
    val doneTasks: List<String>,
    val pendingTasks: List<String>,
    val overdueCount: Int,
    /** 本期支出 / 收入（分） */
    val expenseCents: Long,
    val incomeCents: Long,
    /** 本期支出笔数（0 笔时明细段整段不提） */
    val expenseCount: Int,
    /** 本期支出里最大的一笔（分），用于"这笔花得最多"这类具体说法 */
    val topExpenseCents: Long,
    val topExpenseNote: String,
)

/**
 * 洞察 prompt 分层 + 宽容解析（纯函数，JVM 单测覆盖）。
 *
 * 分层原则同 [ProfileExtractor]：
 * - [FRAMEWORK]：输出格式约束（硬编码不可改），改了 [parse] 会解析失败
 * - [DEFAULT_STRATEGY]：选材与语气（「设置 · AI 提示词 · AI 洞察策略」里覆盖）
 *
 * **允许 0 条**（v0.88.0 用户点名「允许 0 条」）：这是本文件最重要的设计约束。
 * 以前洞察由提炼强行附带产出，模型为了"交差"会硬编；现在明说"这一天没什么可说的
 * 就输出空数组"，宁缺毋滥。空数组是正常结果，不是失败，下游据此跳过（[InsightEngine] 返回 0，
 * 每日建议在 0 条洞察时直接不生成）。
 */
object InsightMath {

    const val FRAMEWORK: String =
        "你是生活洞察归纳器。根据给出的用户数据，归纳出值得用户知道的**观察**。\n" +
            "只输出 JSON，格式：\n" +
            "{\"insights\":[{\"title\":\"一句话结论（不超过 20 字）\",\"detail\":\"依据与解释（不超过 100 字）\"}]}\n" +
            "全部用简体中文。不要输出 JSON 以外的任何文字。"

    /**
     * 默认策略（用户可覆盖）。
     *
     * 关键约束写在这里而不是框架里，因为它们是**可调的取材口径**，不是格式契约。
     * 第 1 条「允许 0 条」是硬要求：模型默认倾向"每问必答"，不点名就会硬凑。
     */
    const val DEFAULT_STRATEGY: String =
        "要求：\n" +
            "1. **宁缺毋滥是最高原则**：数据里没有值得说的规律时，输出 \"insights\":[]。\n" +
            "   0 条是正常结果，不是失败——不要为了有东西可写而把数据复述一遍。\n" +
            "2. 只写**从数据里看出来的规律或趋势**，不要复述清单。\n" +
            "   反例（禁止）：「你今天完成了 3 项任务」——这是复述，不是洞察。\n" +
            "   正例：「连续几天你的任务都堆到晚上才做，白天基本空着」——这是观察。\n" +
            "3. 优先说**用户自己不容易察觉**的事：模式、变化、反常、以及数据之间的矛盾\n" +
            "   （例如：支出比往常高、任务反复往后拖、记忆里说的目标和实际执行对不上）。\n" +
            "4. 结论要有依据：detail 里点出你是看了哪几项数据得出的，别凭空下判断。\n" +
            "5. 语气平实、具体、不夸张，像观察日记，不说教、不打鸡血、不扮演角色、不用感叹号堆情绪。\n" +
            "6. 一条 insight 只说一件事；能说几条说几条，最多 3 条。"

    fun systemPrompt(strategy: String = DEFAULT_STRATEGY): String =
        if (strategy.isBlank()) FRAMEWORK else "$FRAMEWORK\n$strategy"

    /** 素材 → user 消息内容（单测校验关键信息确实进了 prompt） */
    fun buildPrompt(ctx: InsightContext): String = buildString {
        append("[周期] ${InsightPeriod.label(ctx.period)}（${ctx.day.monthValue}月${ctx.day.dayOfMonth}日）\n")
        if (ctx.facts.isNotEmpty()) {
            append("[本期新增或更新的记忆]\n")
            ctx.facts.forEach { append("- ${it.first}：${it.second}\n") }
        }
        if (ctx.doneTasks.isNotEmpty()) {
            append("[已完成的任务]\n")
            ctx.doneTasks.forEach { append("- $it\n") }
        }
        if (ctx.pendingTasks.isNotEmpty()) {
            append("[没做完的任务]\n")
            ctx.pendingTasks.forEach { append("- $it\n") }
        }
        if (ctx.overdueCount > 0) {
            append("[逾期未完成] ${ctx.overdueCount} 项\n")
        }
        if (ctx.expenseCount > 0 || ctx.incomeCents > 0) {
            append("[收支] 支出 ${LedgerMath.formatCents(ctx.expenseCents)} 元（${ctx.expenseCount} 笔）")
            if (ctx.incomeCents > 0) append("，收入 ${LedgerMath.formatCents(ctx.incomeCents)} 元")
            append("\n")
            if (ctx.topExpenseCents > 0) {
                append("[最大一笔支出] ${LedgerMath.formatCents(ctx.topExpenseCents)} 元")
                if (ctx.topExpenseNote.isNotBlank()) append("（${ctx.topExpenseNote}）")
                append("\n")
            }
        }
        append("请归纳这个周期内值得用户知道的洞察（没有就说 0 条）。")
    }

    /**
     * 宽容解析：模型可能裹代码栅栏 / 加说明文字 / 回空数组。
     *
     * 与 [ProfileExtractor.parse] 同一套路，但**空结果不算失败**：
     * 拿不到任何可用条目就返回空列表，调用点据此"什么都不写库"。
     */
    fun parse(raw: String): List<Pair<String, String>> {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        return runCatching {
            val root = org.json.JSONObject(raw.substring(start, end + 1))
            val arr = root.optJSONArray("insights") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val o = arr.getJSONObject(i)
                    val title = o.optString("title").trim()
                    val detail = o.optString("detail").trim()
                    if (title.isNotEmpty()) title.take(40) to detail.take(200) else null
                }.getOrNull()
            }.take(MAX_INSIGHTS)
        }.getOrDefault(emptyList())
    }

    /** 软防灾上限：策略里写了最多 3 条，这里再兜一层（模型失控时不至于刷屏） */
    private const val MAX_INSIGHTS = 3
}

/**
 * AI 洞察引擎（v0.88.0，替代「提炼时顺带产洞察」的旧链路）。
 *
 * ## 三条子链
 * - **日洞察**：每天 23:00，素材 = 当天新增/更新的记忆 + 当天的任务 + 当天的记账；
 * - **周洞察**：每周日 23:00，素材 = 本周的（周一 0 点起）；
 * - **月洞察**：每月月末 23:00，素材 = 本月的（1 号 0 点起）。
 *
 * 三条各自独立、各自允许多条也允许 0 条；同一天同周期只生成一次（幂等靠
 * [AppDatabase] 的 `getByPeriodAndDay`），失败静默（洞察是旁路，绝不影响用户做事）。
 *
 * ## 为什么都是 23:00
 * 用户选的「排法 A」：晚上跑洞察（当天数据已经齐了，任务该做的做完了、账该记的记了），
 * **次日 07:30 的每日建议再去读昨天的洞察**。这样建议看到的是"已经沉淀过的结论"，
 * 而不是自己从原始数据里现炒 —— 两者分离、各自可解释。
 */
class InsightEngine(
    private val db: AppDatabase,
    private val settingsRepository: SettingsRepository,
) {

    /** 生成某天的日洞察；已有则返回 null（幂等），无 API/失败也返回 null */
    suspend fun ensureDaily(day: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): List<AiInsight> {
        if (db.aiInsightDao().getByPeriodAndDay(InsightPeriod.DAILY, day.toEpochDay()) != null) return emptyList()
        return generate(day, day, InsightPeriod.DAILY, zone)
    }

    /** 周洞察：周期 = 该周的周一 0 点 → 周日末（[day] 应为周日当天） */
    suspend fun ensureWeekly(day: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): List<AiInsight> {
        if (db.aiInsightDao().getByPeriodAndDay(InsightPeriod.WEEKLY, day.toEpochDay()) != null) return emptyList()
        return generate(day.minusDays(6), day, InsightPeriod.WEEKLY, zone)
    }

    /** 月洞察：周期 = 该月 1 号 → 月末（[day] 应为当月最后一天） */
    suspend fun ensureMonthly(day: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): List<AiInsight> {
        if (db.aiInsightDao().getByPeriodAndDay(InsightPeriod.MONTHLY, day.toEpochDay()) != null) return emptyList()
        return generate(day.withDayOfMonth(1), day, InsightPeriod.MONTHLY, zone)
    }

    /**
     * 生成并落库（[from]–[to] 闭区间的归属日）。
     *
     * 落到 [AiInsight.day] 的是**周期结束日**（[to]）：这是有意的 —— 「昨天的洞察」
     * 在每日建议那边就是按归属日取，日/周/月三种只要归属日相同就会被一起取到。
     */
    private suspend fun generate(
        from: LocalDate,
        to: LocalDate,
        period: String,
        zone: ZoneId,
    ): List<AiInsight> {
        val config = runCatching { settingsRepository.apiConfig.first() }
            .onFailure { Log.w(TAG, "read api config failed", it) }
            .getOrNull() ?: return emptyList()
        if (!config.isComplete) return emptyList()

        val ctx = collectContext(from, to, period, zone)
        // 三类数据全空 → 这一天真的没什么可归纳的，不必白跑一次 LLM（也省 token）
        if (ctx.facts.isEmpty() && ctx.doneTasks.isEmpty() && ctx.pendingTasks.isEmpty() &&
            ctx.expenseCount == 0 && ctx.incomeCents == 0L
        ) {
            return emptyList()
        }

        val strategy = runCatching { settingsRepository.insightStrategy.first() }
            .getOrDefault(InsightMath.DEFAULT_STRATEGY)
        val reply = runCatching {
            OpenAiClient(config.baseUrl, config.apiKey, config.model).collectReply(
                InsightMath.systemPrompt(strategy),
                listOf(
                    ChatMessage(
                        conversationId = 0,
                        isFromUser = true,
                        content = InsightMath.buildPrompt(ctx),
                    )
                ),
            )
        }
            // v0.97.0：**失败必须留痕**。以前这里 `getOrNull() ?: return emptyList()` 把
            // 网络错误、401、超时全部吞成"这次没有洞察"——用户看到的是"AI 从来没给过我洞察"，
            // 而日志里也一个字都没有，谁也没法判断到底是没数据还是接线错了。
            .onFailure { Log.w(TAG, "insight LLM call failed (period=$period)", it) }
            .getOrNull() ?: return emptyList()
        runCatching {
            UsageTracker.add(
                settingsRepository, reply.usageTokens ?: 0, config.model,
                reply.promptTokens, reply.cachedPromptTokens,
            )
        }

        val parsed = InsightMath.parse(reply.text)
        val day = to.toEpochDay()
        val saved = ArrayList<AiInsight>(parsed.size)
        parsed.forEach { (title, detail) ->
            runCatching {
                db.aiInsightDao().insert(
                    AiInsight(title = title, detail = detail, period = period, day = day)
                )
            }.onSuccess { saved += AiInsight(0, title, detail, period = period, day = day) }
        }
        // 按周期分桶清理（日 30 / 周 12 / 月 12）：以前"全表留 20"会让周月洞察被日洞察挤掉
        runCatching { db.aiInsightDao().pruneDaily() }
        runCatching { db.aiInsightDao().pruneWeekly() }
        runCatching { db.aiInsightDao().pruneMonthly() }
        return saved
    }

    /**
     * 汇总素材（可被测试单独调用）。
     *
     * 每个数据源都按 `[from, to]` 归属日过滤，**不读对话转录** —— 这是本次重构的核心。
     */
    suspend fun collectContext(
        from: LocalDate,
        to: LocalDate,
        period: String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): InsightContext {
        val fromMs = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val toMs = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        // 1) 记忆：本期内新增/更新的条目（updatedAt 落在区间）。只取用户本人那一档，
        //    与每日建议同一口径 —— 各角色聊出来的私事不进这条全局链路。
        val facts = runCatching { db.profileFactDao().observeAll().first() }.getOrDefault(emptyList())
        val freshFacts = MemoryScope.visibleGlobal(facts)
            .filter { it.updatedAt in fromMs until toMs }
            .takeLast(MAX_FACTS)
            .map { it.key to it.value }

        // 2) 任务：本期内了结的（完成记录在区间内）+ 当前仍未完成/逾期
        val tasks = runCatching { db.taskDao().observeAll().first() }.getOrDefault(emptyList())
        val doneIds = runCatching { db.taskCompletionDao().observeAll().first() }.getOrDefault(emptyList())
            .filter { it.day in from.toEpochDay()..to.toEpochDay() }
            .map { it.taskId }
            .toSet()
        val titleById = tasks.associate { it.id to it.title }
        val doneTasks = doneIds.mapNotNull { titleById[it] }.take(MAX_TASKS)
        val todayDay = to.toEpochDay()
        val pending = tasks.filter { !it.isEffectivelyDone(todayDay) && it.id !in doneIds }
        val pendingTasks = pending.filter { !isOverdue(it, todayDay, zone) }
            .map { it.title }.take(MAX_PENDING)
        val overdueCount = pending.count { isOverdue(it, todayDay, zone) }

        // 3) 记账：本期的收支（按 occurredAt 落在区间内过滤）
        val ledger = runCatching { db.ledgerDao().observeAll().first() }.getOrDefault(emptyList())
        val inRange = ledger.filter { it.occurredAt in fromMs until toMs }
        val expenses = inRange.filter { !it.isIncome }
        val top = expenses.maxByOrNull { it.amountCents }

        return InsightContext(
            day = to,
            period = period,
            facts = freshFacts,
            doneTasks = doneTasks,
            pendingTasks = pendingTasks,
            overdueCount = overdueCount,
            expenseCents = expenses.sumOf { it.amountCents },
            incomeCents = inRange.filter { it.isIncome }.sumOf { it.amountCents },
            expenseCount = expenses.size,
            topExpenseCents = top?.amountCents ?: 0L,
            topExpenseNote = top?.note.orEmpty(),
        )
    }

    /** 该任务在 [todayDay] 看来已经逾期（每日任务不算逾期：它天天都在今天） */
    private fun isOverdue(task: TaskItem, todayDay: Long, zone: ZoneId): Boolean {
        if (task.repeatDaily) return false
        if (task.dueAt == 0L) return false
        return java.time.Instant.ofEpochMilli(task.dueAt).atZone(zone).toLocalDate().toEpochDay() < todayDay
    }

    private companion object {
        const val TAG = "InsightEngine"
        /** 记忆最多喂 12 条（再多是噪音，模型只会复述） */
        const val MAX_FACTS = 12
        const val MAX_TASKS = 15
        const val MAX_PENDING = 10
    }
}
