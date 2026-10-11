package com.menghuan.app.data

import android.util.Log
import com.menghuan.app.data.db.AppDatabase
import com.menghuan.app.data.model.DailySuggestion
import com.menghuan.app.llm.OpenAiClient
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId

/**
 * 每日建议的输入素材（纯数据，JVM 单测口径）。
 *
 * **v0.88.0 换成"只吃昨天的洞察"**（用户点名：「建议要流水干嘛，每天就一条建议，
 * 就是根据昨天的洞察结果生成」）。
 *
 * 以前这里是「今日任务完成情况 + 近 30 天档案 + 近期洞察标题 + 昨日/当月支出」——
 * 一大把原始数据丢给模型，让它自己从零归纳。问题有两个：
 * 1. 07:30 跑的时候"今日完成 0/x"是个必然结论（一天刚开始），这一项等于噪音；
 * 2. 建议和洞察各说各话，用户看到的是两份互不相干的 AI 输出。
 *
 * 现在改成**两段式**：晚上 23:00 洞察先把数据归纳成结论（[InsightEngine]），
 * 早上 07:30 建议只读昨天那批结论、把它翻译成"今天做一件什么事"。
 * 素材因此极简：几条洞察标题 + 说明 + 日期，模型没有原始数据可瞎编。
 */
data class DailyContext(
    val day: LocalDate,
    /**
     * 昨天的洞察（title to detail），已按「日 + 周 + 月」一起取。
     *
     * **空列表是正常输入**：昨晚没配 API、没数据、或者洞察本来就 0 条时，
     * 调用点（[DailySuggestionGenerator.ensureToday]）据此**不生成建议** ——
     * 没有洞察可依据就不硬凑一条，这是用户明确要的「0 条洞察则不生成建议」。
     */
    val insights: List<Pair<String, String>>,
    /** 洞察归属日（展示用，让模型知道这批结论是哪天的） */
    val insightDay: LocalDate,
)

/**
 * 每日建议 prompt 分层 + 宽容解析（纯函数，JVM 单测覆盖）。
 *
 * 分层原则同 [ProfileExtractor]：
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
        "根据上面给出的洞察，给出一条**今天就能执行**的建议。要求：\n" +
            "1. 只给一条，必须是今天可做的具体动作（不要「长期要坚持」这类无法执行的话）。\n" +
            "2. **建议必须从洞察里长出来**：先说清是哪条洞察让你这么建议，再给动作。\n" +
            "   洞察里没提到的事，不要凭常识补一条进来。\n" +
            "3. 语气平实直接，像靠谱的朋友提醒一句，不说教、不打鸡血、不扮演角色、不用感叹号堆情绪。\n" +
            "4. 洞察偏「没得说」这类结论时，给一条低门槛的动作（如「把今天最想拖着不做的那件事先做 10 分钟」）。"

    fun systemPrompt(strategy: String = DEFAULT_STRATEGY): String =
        if (strategy.isBlank()) FRAMEWORK else "$FRAMEWORK\n$strategy"

    /** 素材 → user 消息内容（单测校验关键信息确实进了 prompt） */
    fun buildPrompt(ctx: DailyContext): String = buildString {
        append("[今天] ${ctx.day.monthValue}月${ctx.day.dayOfMonth}日\n")
        append("[昨天的洞察]（${ctx.insightDay.monthValue}月${ctx.insightDay.dayOfMonth}日归纳）\n")
        ctx.insights.forEach { (title, detail) ->
            append("- $title")
            if (detail.isNotBlank()) append("：$detail")
            append("\n")
        }
        append("请根据这些洞察，给出今天的一条建议。")
    }

    /**
     * 宽容解析：模型可能裹代码栅栏 / 加说明文字 / 只回一段纯文本。
     * 与洞察解析同一套路——能解析成 JSON 就取 String 类型的 title/detail
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
 * 每日建议生成器（v0.42.0；**v0.88.0 数据源换成"昨天的洞察"**）：
 * - 输入 = 昨天（含日/周/月）的洞察结论 → LLM → 一条建议落库
 * - **没有洞察就不生成**（用户点名）：返回 null，当天数据页显示「今天还没有建议」三态之一
 * - 落库 daily_suggestions（同 day 覆盖）；API 未配置 → 返回 null（静默）
 * - [ensureToday] = 补生成：仅当今天尚无建议时生成（App 启动 / BootReceiver / 每日闹钟）
 */
class DailySuggestionGenerator(
    private val db: AppDatabase,
    private val settingsRepository: SettingsRepository,
) {

    /** 生成指定日期的建议并落库；无 API / 无洞察 / 失败返回 null */
    suspend fun generateForDay(
        day: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): DailySuggestion? {
        val config = settingsRepository.apiConfig.first()
        if (!config.isComplete) return null

        val ctx = collectContext(day, zone)
        // 没有洞察可依据 → 不生成（不是失败，是设计：宁可不给，也不凭空编一条）
        if (ctx.insights.isEmpty()) return null

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
        runCatching { UsageTracker.add(settingsRepository, reply.usageTokens ?: 0, config.model,
            reply.promptTokens, reply.cachedPromptTokens) }
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
        // v0.97.0：静默是刻意的（旁路失败不该影响任何主流程），但**不能连日志都不留** ——
        // 以前异常在这里被 getOrNull() 吞掉，用户看到"今天没有建议"时分不清是没洞察还是抛了异常。
        return runCatching { generateForDay(day, zone) }
            .onFailure { Log.w(TAG, "generate daily suggestion failed (day=$day)", it) }
            .getOrNull()
    }

    /**
     * 汇总生成素材（可被测试单独调用）。
     *
     * **v0.88.0 只剩一件事：取昨天的洞察。** 三条洞察（日/周/月）只要归属日相同就一起取到，
     * 正是「统筹日 + 周 + 月洞察」想要的效果 —— 周日晚上会有日洞察和周洞察同一天落库，
     * 周一早上的建议就能同时用上"昨天做了什么"和"这一周怎么样"。
     */
    suspend fun collectContext(day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): DailyContext {
        val yesterday = day.minusDays(1)
        val insights = runCatching { db.aiInsightDao().forDay(yesterday.toEpochDay()) }
            .getOrDefault(emptyList())
        return DailyContext(
            day = day,
            insights = insights.take(MAX_INSIGHTS).map { it.title to it.detail },
            insightDay = yesterday,
        )
    }

    private companion object {
        const val TAG = "DailySuggestion"
        /** 最多喂 6 条洞察（日+周+月最多也就 9 条，超过就是模型没守 0~3 条约束） */
        const val MAX_INSIGHTS = 6
    }
}
