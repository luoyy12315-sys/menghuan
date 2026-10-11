package com.menghuan.app.data

import android.util.Log
import com.menghuan.app.data.db.AppDatabase
import com.menghuan.app.data.model.ChatMessage
import com.menghuan.app.data.model.ConversationSummary
import com.menghuan.app.data.model.MediaKind
import com.menghuan.app.llm.OpenAiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * 滚动摘要的 prompt 分层（与 [ProfileExtractor] 同一套路）：
 * - [FRAMEWORK] = 硬编码不可改：输入形状（已有摘要 + 新增对话）、输出形状（只吐摘要正文）。
 * - [DEFAULT_STRATEGY] = 用户可在「设置 · AI 提示词 · 对话摘要策略」里覆盖：
 *   该保留什么、该丢什么、写多长。
 *
 * 输出是**纯文本**（不是 JSON）：摘要要原样拼进 system prompt，多一层 schema
 * 只是给模型多一次写错的机会。
 */
object SummaryExtractor {

    /** 硬编码框架：输入/输出形状约束（不可编辑） */
    const val FRAMEWORK: String =
        "你是对话摘要器。用户给你两段输入：「已有摘要」（可能为空）与「新增对话」。\n" +
            "把两者合并压缩成一段新的摘要：新摘要要**自包含**——只读它就能知道这段关系里发生过什么，" +
            "不许出现「见上文」「如前所述」这种指向被丢掉内容的写法。\n" +
            "对话里标注为「AI角色」的一方是虚拟角色的扮演内容，可以保留TA们之间的约定、承诺、重要事件与情感节点，" +
            "但不要把角色设定复述成人格说明。\n" +
            "只输出摘要正文（纯文本、分段随意），不要输出 JSON、不要加「摘要：」前缀、不要解释你在做什么。"

    /** 默认策略：保留什么 / 丢什么 / 多长（用户可在设置里覆盖） */
    const val DEFAULT_STRATEGY: String =
        "规则：\n" +
            "1. 必须保留：双方明确做过的约定与承诺、对用户重要的事件与决定、用户明确要求记住的事、" +
            "未了结的话题与待办、关系里的关键转折。\n" +
            "2. 可以丢掉：寒暄、日常闲聊细节、已经了结的临时话题、重复的表达。\n" +
            "3. 语气中性、按时间脉络组织；总长度控制在 600 字以内，旧摘要里的重要条目不许因为新增内容琐碎就被挤掉。"

    /** 完整 system prompt = 框架 + 策略；策略为空/空白时只发框架 */
    fun systemPrompt(strategy: String = DEFAULT_STRATEGY): String =
        if (strategy.isBlank()) FRAMEWORK else "$FRAMEWORK\n$strategy"
}

/**
 * 滚动摘要引擎（v0.82.0 P1-1，全计划最高价值项）：**20 万字历史预算之外的旧对话不再蒸发**。
 *
 * ## 与提炼水位线同款的手法
 * 每个会话在 `conversation_summaries` 里有一行：`coveredMessageId` 是水位线，
 * `id ≤ 它` 的消息已经被摘要覆盖。每当「水位线之后、且已掉出历史预算窗口」的积压超过
 * [TRIGGER_CHARS]（3 万字，用户拍板），就把积压里**最旧的一段**（封顶 [SEGMENT_CAP_CHARS]，输入有界）
 * 连同旧摘要一起发给 LLM 压缩成新摘要，水位线推进到那段最后一条。
 *
 * ## 为什么摘要能放 system prompt 而不伤缓存（§零.9）
 * 摘要只在**水位线推进**那一刻才变——3 万字攒一次，是低频事件；同一水位线内逐字节稳定。
 * 它拼在 system prompt **尾部**（见 ChatViewModel.reply 的注入点），
 * 前面的人设 + 档案 + 能力说明整段前缀照旧命中缓存。
 *
 * 失败静默（它是尽力而为的旁路，绝不影响聊天）；用户在「会话设置 → 对话摘要」可查看、可删除，
 * 全局开关在「设置 · AI 提示词 · 对话摘要策略」段上（默认开）。
 */
class SummaryEngine(
    private val db: AppDatabase,
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope,
) {

    /** 同一时刻只跑一个压缩（摘要本身 3 万字才触发一次，串行足够，双跑只是白花一份 token） */
    private val mutex = Mutex()

    /** 聊天回复成功落库后调用（尽力而为，不阻塞、不抛出） */
    fun maybeSummarize(personaId: Long) {
        scope.launch {
            if (!mutex.tryLock()) return@launch
            try {
                runCatching { run(personaId) }
                    .onFailure { Log.w(TAG, "summarize failed for persona=$personaId", it) }
            } finally {
                mutex.unlock()
            }
        }
    }

    internal suspend fun run(personaId: Long) {
        if (!settingsRepository.rollingSummaryEnabled.first()) return
        // 应用助手会话与提炼同一条纪律：「怎么用 App」的问答不值得摘要
        val personaName = runCatching { db.personaDao().getById(personaId)?.name }.getOrNull() ?: return
        if (personaName == ASSISTANT_PERSONA_NAME) return
        val config = settingsRepository.apiConfigFor(personaId).first()
        if (!config.isComplete) return

        val existing = db.conversationSummaryDao().get(personaId)
        val watermark = existing?.coveredMessageId ?: 0L

        // v0.86.0：历史窗口按角色可配了，所以摘要的判据必须**每轮读同一个来源**，
        // 不能再引用编译期常量（否则用户把窗口调小后，历史按新窗口砍、摘要仍按老窗口算，
        // 窗口边缘会有一段消息既不进历史也不进摘要 —— 静默蒸发）。
        val budget = runCatching { settingsRepository.historyCharBudget(personaId).first() }
            .getOrDefault(settingsRepository.DEFAULT_HISTORY_CHAR_BUDGET)

        // 从旧到新翻页拉积压，**攒够"判定 + 一段"所需的量就停**：
        // 判据要知道积压是否超（预算 + 阈值），分段最多再吃 SEGMENT_CAP_CHARS，
        // 再多拉一个字都是白读（超长会话不为算总数整表扫）。
        val need = budget + TRIGGER_CHARS + SEGMENT_CAP_CHARS
        val backlog = ArrayList<ChatMessage>()
        var offset = 0
        var backlogChars = 0
        // v0.97.0：是否已经把水位线之后的**全部**消息读进来了（读到短页 = 到底了）
        var reachedEnd = false
        while (backlogChars < need) {
            val raw = db.messageDao().getAfterIdPaged(personaId, watermark, BACKLOG_PAGE, offset)
            if (raw.isEmpty()) {
                reachedEnd = true
                break
            }
            // 记忆待审系统气泡是 UI 提示不是对话（与提炼/LLM 历史同一剔除口径）。
            // ⚠️ "短页 = 到底了"的判据必须看**过滤前**的行数：过滤后不足一页≠没有更旧的消息。
            val page = raw.filter {
                MediaKind.fromLegacy(it.mediaKind, it.imagePath) != MediaKind.MEMORY_NOTICE
            }
            backlog += page
            offset += BACKLOG_PAGE
            backlogChars += page.sumOf { it.content.length }
            if (raw.size < BACKLOG_PAGE) {
                reachedEnd = true
                break
            }
        }

        // v0.97.0（静默空洞）：判据必须用**实际留下的字数**，而不是"预算"。
        //
        // 历史选取（[com.menghuan.app.ui.chat.selectHistoryByCharBudget]）是**贪心**的：
        // 从最新往回收，遇到一条比剩余预算还长的消息就整个停住 —— 它实际留下的可能远少于预算。
        // 以前这里按 `budget` 估算"掉出窗口的量"，两者对不上时，那段消息会
        // **既不进 LLM 历史、也进不了摘要**（v0.82.0 注释警告过、v0.86.0 修过一次的坑）。
        // 现在直接把同一个纯函数套在 backlog 上问"你到底留下了多少字"，口径与聊天链完全一致。
        // 只在 `reachedEnd` 时才算（积压已全部读进来，算出来的数才准）；没到底说明积压远超阈值，
        // 必然已经该触发，用 budget 近似即可。
        val retainedChars = if (reachedEnd) {
            com.menghuan.app.ui.chat.selectHistoryByCharBudget(backlog, budget)
                .sumOf { it.content.length }
        } else {
            budget
        }
        if (!needsSummary(backlogChars, retainedChars)) return

        // 目标 = 把"掉出窗口"的那部分收进摘要；单次封顶 SEGMENT_CAP_CHARS（输入有界），
        // 剩下的下次触发再收——一次想把陈年旧账全压完，输入本身就先爆预算。
        val targetChars = (backlogChars - retainedChars).coerceAtMost(SEGMENT_CAP_CHARS)
        val segment = pickSegment(backlog, targetChars)
        if (segment.isEmpty()) return
        val newWatermark = segment.last().id

        val transcript = buildString {
            segment.forEach { m ->
                val text = m.content.trim()
                if (text.isEmpty()) return@forEach
                append(if (m.isFromUser) "用户：" else "AI角色「$personaName」：")
                append(text)
                append("\n\n")
            }
        }
        if (transcript.isBlank()) {
            // 整段都是空内容（语音条占位等）：不烧 token，水位线照样推过这段
            db.conversationSummaryDao().upsert(
                ConversationSummary(
                    conversationId = personaId,
                    coveredMessageId = newWatermark,
                    summary = existing?.summary.orEmpty(),
                )
            )
            return
        }
        val userContent = buildString {
            append("【已有摘要】\n")
            append(existing?.summary?.takeIf { it.isNotBlank() } ?: "（还没有摘要）")
            append("\n\n【新增对话】\n")
            append(transcript)
        }
        val strategy = runCatching { settingsRepository.summaryStrategy.first() }
            .getOrDefault(SummaryExtractor.DEFAULT_STRATEGY)
        val reply = OpenAiClient(config.baseUrl, config.apiKey, config.model)
            .collectReply(
                SummaryExtractor.systemPrompt(strategy),
                listOf(ChatMessage(conversationId = 0, isFromUser = true, content = userContent)),
            )
        runCatching {
            UsageTracker.add(
                settingsRepository, reply.usageTokens ?: 0, config.model,
                reply.promptTokens, reply.cachedPromptTokens,
            )
        }
        val newSummary = reply.text.trim()
        // 模型吐空了：水位线不动，下次重试同一段（宁可重复送，不拿空摘要覆盖旧摘要）
        if (newSummary.isEmpty()) return
        db.conversationSummaryDao().upsert(
            ConversationSummary(
                conversationId = personaId,
                coveredMessageId = newWatermark,
                summary = newSummary,
            )
        )
    }

    companion object {
        private const val TAG = "SummaryEngine"

        /**
         * 历史窗口字数在 v0.86.0 后**不再是常量** —— 按角色可配，唯一来源是
         * `SettingsRepository.historyCharBudget(personaId)`（[run] 里每轮读）。
         * 这里保留不了编译期引用：写成常量就有"用户改了窗口、摘要还按老窗口算"的风险，
         * 那会导致窗口边缘的消息静默蒸发（v0.82.0 警告过的坑）。
         */

        /** 掉出窗口的积压超过这么多字就触发一次压缩（3 万字，2026-10-09 用户拍板） */
        const val TRIGGER_CHARS = 30_000

        /** 单次压缩的输入封顶：输入有界是这条链路的硬约束 */
        const val SEGMENT_CAP_CHARS = 40_000

        /** 积压分段拉取的页大小 */
        private const val BACKLOG_PAGE = 200

        /** 触发判据（纯函数，单测口径）：掉出历史窗口的积压是否已攒够一次压缩 */
        fun needsSummary(backlogChars: Int, historyBudgetChars: Int): Boolean =
            backlogChars - historyBudgetChars > TRIGGER_CHARS

        /**
         * 从积压（从旧到新）里取出本次压缩的一段（纯函数，单测口径）：
         * 累计字数够 [targetChars] 即停，**至少取一条**（一条就超长的消息也得能过去，否则死锁）。
         */
        fun pickSegment(backlog: List<ChatMessage>, targetChars: Int): List<ChatMessage> {
            if (backlog.isEmpty() || targetChars <= 0) return emptyList()
            val out = ArrayList<ChatMessage>()
            var chars = 0
            for (m in backlog) {
                if (out.isNotEmpty() && chars >= targetChars) break
                out += m
                chars += m.content.length
            }
            return out
        }
    }
}
