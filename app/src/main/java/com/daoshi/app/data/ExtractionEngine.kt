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
 * AI 提炼流水线（AGENTS 路线图「conversation → 洞察 → 档案」）：
 * - 增量：每个会话维护消息 id 水位线，只把新消息送给 LLM（攒够 [EXTRACT_EVERY] 条触发一次）
 * - 产出：ProfileFact（按 key 覆盖，manual 条目不被 AI 改 source）+ AiInsight（按标题去重，留最近 20 条）
 * - 运行在应用级作用域：聊天页退出不打断；失败静默（提炼是尽力而为的旁路，绝不影响聊天）
 * - 数据页提供手动「提炼」入口（extractAll，跳过水位线阈值但仍只送增量）
 */
class ExtractionEngine(
    private val db: AppDatabase,
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope,
) {

    /** 攒够多少条新消息触发一次自动提炼 */
    private val extractEvery = 8

    /** 聊天回复成功落库后调用（尽力而为，不阻塞、不抛出） */
    fun maybeExtract(personaId: Long) {
        scope.launch { runCatching { runExtract(personaId, force = false) } }
    }

    /** 数据页手动触发：所有会话立即提炼一次（仍只送增量消息）；挂起直到完成 */
    suspend fun extractAll() {
        val personaIds = db.personaDao().observeAll().first().map { it.id }
        personaIds.forEach { personaId ->
            runCatching { runExtract(personaId, force = true) }
                .onFailure { Log.w(TAG, "extraction failed for persona=$personaId", it) }
        }
    }

    private suspend fun runExtract(personaId: Long, force: Boolean) {
        val config = settingsRepository.apiConfig.first()
        if (!config.isComplete) return // 未配置 API：提炼不可用，静默跳过

        val watermark = settingsRepository.extractionWatermark(personaId)
        val newMessages = db.messageDao().getAfterId(personaId, watermark)
        if (newMessages.isEmpty()) return
        if (!force && newMessages.size < extractEvery) return

        val transcript = newMessages.joinToString("\n\n") { message ->
            (if (message.isFromUser) "用户：" else "导师：") + message.content
        }
        val sb = StringBuilder()
        OpenAiClient(config.baseUrl, config.apiKey, config.model)
            .chat(ProfileExtractor.SYSTEM_PROMPT, listOf(ChatMessage(conversationId = 0, isFromUser = true, content = transcript)))
            .collect { token -> sb.append(token) }

        val extracted = ProfileExtractor.parse(sb.toString())
        mergeFacts(extracted.facts)
        saveInsights(extracted.insights)
        // 水位线推进到最后一条已处理消息；即便模型这次没产出，也不重复送同一段
        settingsRepository.setExtractionWatermark(personaId, newMessages.last().id)
    }

    private companion object {
        const val TAG = "ExtractionEngine"
    }

    private suspend fun mergeFacts(facts: List<Pair<String, String>>) {
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
        }
    }

    private suspend fun saveInsights(insights: List<Pair<String, String>>) {
        insights.forEach { (title, detail) ->
            if (db.aiInsightDao().getByTitle(title) == null) {
                db.aiInsightDao().insert(AiInsight(title = title, detail = detail))
            }
        }
        db.aiInsightDao().prune()
    }
}
