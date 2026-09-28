package com.daoshi.app.data

import android.util.Log
import com.daoshi.app.data.db.AppDatabase
import com.daoshi.app.data.model.AiInsight
import com.daoshi.app.data.model.ChatMessage
import com.daoshi.app.data.model.ProfileFact
import com.daoshi.app.llm.OpenAiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * AI 提炼流水线（AGENTS 路线图「conversation → 洞察 → 档案/任务」）：
 * - 增量：每个会话维护消息 id 水位线，只把新消息送给 LLM（攒够 [extractEvery] 条触发一次）
 * - 产出：ProfileFact（按 key 覆盖，manual 条目不被 AI 改 source）+ AiInsight（按标题去重，留最近 20 条）
 *        + TaskSuggestion（进程内存分发到聊天页卡片，确认后才写 tasks；同 title 当日不重复建议）
 * - 运行在应用级作用域：聊天页退出不打断；失败静默（提炼是尽力而为的旁路，绝不影响聊天）
 * - 数据页提供手动「提炼」入口（extractAll 返回统计摘要，用于结果反馈）
 */
class ExtractionEngine(
    private val db: AppDatabase,
    private val settingsRepository: SettingsRepository,
    private val suggestionHub: TaskSuggestionHub,
    private val scope: CoroutineScope,
) {

    /** 提炼统计摘要（手动提炼的结果反馈用） */
    data class ExtractSummary(
        val personasScanned: Int = 0,
        val newFacts: Int = 0,
        val updatedFacts: Int = 0,
        val newInsights: Int = 0,
        val newSuggestions: Int = 0,
        val skippedNoApi: Boolean = false,
    ) {
        val hasChanges: Boolean get() = newFacts + updatedFacts + newInsights + newSuggestions > 0
    }

    /** 攒够多少条新消息触发一次自动提炼 */
    private val extractEvery = 8

    /** 聊天回复成功落库后调用（尽力而为，不阻塞、不抛出） */
    fun maybeExtract(personaId: Long) {
        scope.launch { runCatching { runExtract(personaId, force = false) } }
    }

    /** 数据页手动触发：所有会话立即提炼一次（仍只送增量消息）；挂起直到完成并返回摘要 */
    suspend fun extractAll(): ExtractSummary {
        val personaIds = db.personaDao().observeAll().first().map { it.id }
        var summary = ExtractSummary(personasScanned = personaIds.size)
        personaIds.forEach { personaId ->
            runCatching { runExtract(personaId, force = true) }
                .onSuccess { summary += it }
                .onFailure { Log.w(TAG, "extraction failed for persona=$personaId", it) }
        }
        return summary
    }

    private suspend fun runExtract(personaId: Long, force: Boolean): ExtractSummary {
        val config = settingsRepository.apiConfig.first()
        if (!config.isComplete) return ExtractSummary(skippedNoApi = true) // 未配置 API：静默跳过

        val watermark = settingsRepository.extractionWatermark(personaId)
        val newMessages = db.messageDao().getAfterId(personaId, watermark)
        if (newMessages.isEmpty()) return ExtractSummary()

        val transcript = newMessages.joinToString("\n\n") { message ->
            (if (message.isFromUser) "用户：" else "导师：") + message.content
        }
        val sb = StringBuilder()
        OpenAiClient(config.baseUrl, config.apiKey, config.model)
            .chat(ProfileExtractor.SYSTEM_PROMPT, listOf(ChatMessage(conversationId = 0, isFromUser = true, content = transcript)))
            .collect { token -> sb.append(token) }

        val extracted = ProfileExtractor.parse(sb.toString())
        val (newFacts, updatedFacts) = mergeFacts(extracted.facts)
        val newInsights = saveInsights(extracted.insights)
        val newSuggestions = saveSuggestions(personaId, extracted.taskSuggestions)
        // 水位线推进到最后一条已处理消息；即便模型这次没产出，也不重复送同一段
        settingsRepository.setExtractionWatermark(personaId, newMessages.last().id)
        return ExtractSummary(
            newFacts = newFacts,
            updatedFacts = updatedFacts,
            newInsights = newInsights,
            newSuggestions = newSuggestions,
        )
    }

    /** 任务建议：去重（同名任务/当日已建议）后经 hub 分发到聊天页卡片；返回新建议数 */
    private suspend fun saveSuggestions(personaId: Long, suggestions: List<TaskSuggestion>): Int {
        if (suggestions.isEmpty()) return 0
        val existingTitles = runCatching { db.taskDao().observeAll().first().map { it.title } }
            .getOrDefault(emptyList())
            .toSet()
        val fresh = suggestionHub.filterAndRecord(suggestions, existingTitles)
        suggestionHub.publish(personaId, fresh)
        return fresh.size
    }

    /** @return 新增数 to 更新数 */
    private suspend fun mergeFacts(facts: List<Pair<String, String>>): Pair<Int, Int> {
        var newCount = 0
        var updatedCount = 0
        facts.forEach { (key, value) ->
            val existing = db.profileFactDao().getByKey(key)
            db.profileFactDao().upsert(
                ProfileFact(
                    id = existing?.id ?: 0,
                    key = key,
                    value = value,
                    // 用户手填的条目保持 manual 标记（值可被 AI 刷新，但来源语义不篡改）
                    source = if (existing?.source == "manual") "manual" else "ai",
                    updatedAt = System.currentTimeMillis(),
                )
            )
            if (existing == null) newCount++ else updatedCount++
        }
        return newCount to updatedCount
    }

    private suspend fun saveInsights(insights: List<Pair<String, String>>): Int {
        var inserted = 0
        insights.forEach { (title, detail) ->
            if (db.aiInsightDao().getByTitle(title) == null) {
                db.aiInsightDao().insert(AiInsight(title = title, detail = detail))
                inserted++
            }
        }
        db.aiInsightDao().prune()
        return inserted
    }

    private operator fun ExtractSummary.plus(other: ExtractSummary) = ExtractSummary(
        personasScanned = personasScanned,
        newFacts = newFacts + other.newFacts,
        updatedFacts = updatedFacts + other.updatedFacts,
        newInsights = newInsights + other.newInsights,
        newSuggestions = newSuggestions + other.newSuggestions,
        skippedNoApi = skippedNoApi && other.skippedNoApi,
    )

    private companion object {
        const val TAG = "ExtractionEngine"
    }
}
