package com.menghuan.app.data

import android.util.Log
import androidx.room.withTransaction
import com.menghuan.app.data.db.AppDatabase
import com.menghuan.app.data.model.ChatMessage
import com.menghuan.app.data.model.MediaKind
import com.menghuan.app.data.model.ProfileFact
import com.menghuan.app.llm.OpenAiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * AI 提炼流水线（ROADMAP.md「conversation → 记忆」）：
 * - 增量：每个会话维护消息 id 水位线，只把新消息送给 LLM
 *   （攒够该会话配置的轮数触发一次，见 [runExtract]；v0.86.0 起门槛按角色可配，
 *   旧版本写了常量却没读、等于每条消息都提炼，已修）
 * - 产出：**只有 ProfileFact**（按 key 覆盖，manual 条目不被 AI 改 source）
 * - 运行在应用级作用域：聊天页退出不打断；失败静默（提炼是尽力而为的旁路，绝不影响聊天）
 * - 数据页提供手动「提炼」入口（extractAll 返回统计摘要，用于结果反馈）
 *
 * ⚠️ **v0.88.0 职责收窄（用户拍板「把记忆和洞察拆开」）**：
 * 以前这里顺带产出 insights（洞察）与 taskSuggestions（任务建议），三个不同节奏的东西
 * 绑在一个旋钮上 —— 每 6 轮对话就强制吐一次洞察，洞察实际是"对话转录的读后感"，
 * 与任务/记账的真实数据无关。现在：
 * - **洞察**挪去 [InsightEngine]：按天/周/月跑，输入是「记忆 + 任务 + 记账」，不是对话；
 * - **任务建议**挪去主对话链的 `[建议任务:…]` 标记（像语音条那样由 AI 主动触发）；
 * 这条链路只负责一件事 —— 从对话里认人、记事实。
 */
class ExtractionEngine(
    private val db: AppDatabase,
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope,
) {

    /** 提炼统计摘要（手动提炼的结果反馈用） */
    data class ExtractSummary(
        val personasScanned: Int = 0,
        val newFacts: Int = 0,
        val updatedFacts: Int = 0,
        val skippedNoApi: Boolean = false,
    ) {
        val hasChanges: Boolean get() = newFacts + updatedFacts > 0
    }

    /** 聊天回复成功落库后调用（尽力而为，不阻塞、不抛出） */
    fun maybeExtract(personaId: Long) {
        scope.launch { runCatching { runExtract(personaId, force = false) } }
    }

    /** 数据页手动触发：所有会话立即提炼一次（仍只送增量消息）；挂起直到完成并返回摘要 */
    suspend fun extractAll(): ExtractSummary {
        val personas = db.personaDao().observeAll().first()
        // 内置「应用助手」是使用指南会话，不参与提炼（v0.34.0）：
        // "怎么用 App"的问答被提炼成用户档案属于噪音
        val targets = personas.filter { it.name != ASSISTANT_PERSONA_NAME }
        var summary = ExtractSummary(personasScanned = targets.size)
        targets.forEach { persona ->
            runCatching { runExtract(persona.id, force = true) }
                .onSuccess { summary += it }
                .onFailure { Log.w(TAG, "extraction failed for persona=${persona.id}", it) }
        }
        return summary
    }

    private suspend fun runExtract(personaId: Long, force: Boolean): ExtractSummary {
        // 应用助手会话直接跳过（自动/手动提炼都不碰）；人设行已删除同样跳过
        val personaName = runCatching { db.personaDao().getById(personaId)?.name }.getOrNull()
            ?: return ExtractSummary()
        if (personaName == ASSISTANT_PERSONA_NAME) return ExtractSummary()

        // v0.36.4：提炼用本会话的独立接口（未设覆盖时回落全局）
        val config = settingsRepository.apiConfigFor(personaId).first()
        if (!config.isComplete) return ExtractSummary(skippedNoApi = true) // 未配置 API：静默跳过

        val watermark = settingsRepository.extractionWatermark(personaId)
        val newMessages = db.messageDao().getAfterId(personaId, watermark)
            // v0.82.0：记忆待审系统气泡是 UI 提示不是对话，提炼时剔除（否则"💭 我记住了…"
            // 会被当成 AI 角色的台词喂给提炼模型）
            .filter { MediaKind.fromLegacy(it.mediaKind, it.imagePath) != MediaKind.MEMORY_NOTICE }
        if (newMessages.isEmpty()) return ExtractSummary()

        // v0.86.0：门槛真正接上（以前 [extractEvery] 是死代码 —— 定义但从未读取，
        // 结果是**每一条消息落库都触发一次提炼 LLM 调用**，而每次调用都要重发
        // 完整的框架提示词 + 任务清单 + 现有记忆条目名，成本高得离谱）。
        // 现在：攒够该会话配的轮数才提炼；手动提炼（force = true）无视门槛，点了就跑。
        if (!force) {
            val everyTurns = runCatching { settingsRepository.extractEveryTurns(personaId).first() }
                .getOrDefault(settingsRepository.DEFAULT_EXTRACT_EVERY_TURNS)
            // 「轮」= 一次用户发言 + 一次 AI 回复 = 2 行；只数用户侧，系统气泡/动态等额外行不干扰节奏
            val newTurns = newMessages.count { it.isFromUser }
            if (newTurns < everyTurns) return ExtractSummary()
        }

        // 角色标注（v0.38.2）：AI 一方是虚拟角色的扮演内容，不是用户本人——
        // 不标清楚，模型会把角色的人设/称呼/情节提炼成用户档案（用户实测踩坑）。
        // 角色名直接写进标注，与框架提示词里的「AI角色」铁律配套。
        val transcript = buildString {
            append("【背景】下面以「$personaName」开头的话，全部是虚拟 AI 角色的扮演内容，不是用户本人说的。\n\n")
            newMessages.forEach { message ->
                append(if (message.isFromUser) "用户：" else "AI角色「$personaName」：")
                append(message.content)
                append("\n\n")
            }
        }
        // v0.84.0 的「用户现有任务清单」照旧给上：它在这里的作用不是"让洞察引用执行数据"
        // （洞察已挪走），而是防重复记忆 —— 用户在对话里提到的事已经在任务清单里，
        // 模型据此判断"值得记"还是"已经记过"。
        val taskContext = buildTaskContext()
        // v0.85.0：**把已有记忆的条目名一并给模型**（用户点名「AI 的记忆老是重复的」）。
        //
        // 根因：以前提炼输入只有"对话记录 + 任务情况"，模型**不知道你已经记过什么**，
        // 于是同一个概念每次重新起名（「工作」/「职业」/「工作情况」）；而
        // [mergeFacts] 是按 key **精确匹配**合并的，措辞一变就判为新条目插库 ——
        // 攒几天库里全是同义条。给它看现有条目名，并在策略里要求"同义就复用已有的键"，
        // 才是从源头堵住（下游做同义合并要维护规则表，还容易误合）。
        val existingKeys = buildExistingKeys(personaId)
        val content = buildString {
            append(transcript)
            if (taskContext.isNotEmpty()) append("\n\n").append(taskContext)
            if (existingKeys.isNotEmpty()) {
                append("\n\n【已经记住的条目名】\n")
                append(existingKeys.joinToString("、"))
                append("\n（这些是**已经有**的记忆条目名。要记的事如果和其中某条说的是同一件事，")
                append("就**复用那个名字**、只更新 value，不要换个说法重新起一个 —— 换个名字会变成两条重复记忆。）")
            }
        }
        val sb = StringBuilder()
        // 提示词策略可由用户在「设置 · AI 提示词 · 记忆提炼策略」里覆盖；框架部分硬编码
        val strategy = runCatching { settingsRepository.extractionStrategy.first() }
            .getOrDefault(ProfileExtractor.DEFAULT_STRATEGY)
        val reply = OpenAiClient(config.baseUrl, config.apiKey, config.model)
            .collectReply(ProfileExtractor.systemPrompt(strategy), listOf(ChatMessage(conversationId = 0, isFromUser = true, content = content)))
        sb.append(reply.text)
        runCatching { UsageTracker.add(settingsRepository, reply.usageTokens ?: 0, config.model,
            reply.promptTokens, reply.cachedPromptTokens) }

        val extracted = ProfileExtractor.parse(sb.toString())
        val (newFacts, updatedFacts) = mergeFacts(extracted.facts, personaId)
        // 水位线推进到最后一条已处理消息；即便模型这次没产出，也不重复送同一段
        settingsRepository.setExtractionWatermark(personaId, newMessages.last().id)
        // v0.82.0 P1-3：顺手扫一次自动归档（90 天没被召回命中的 active 降 archived；
        // 纯 DB 操作、提炼本身就低频，顺带做不再单设闹钟）
        runCatching { MemoryLifecycle.sweepAutoArchive(db, System.currentTimeMillis()) }
        return ExtractSummary(
            newFacts = newFacts,
            updatedFacts = updatedFacts,
        )
    }

    /**
     * 附加上下文：近 7 天任务完成情况（任务名 + 次数）+ **用户现有的任务清单**（v0.84.0）。
     *
     * v0.84.0 补上后半段：以前只给"完成情况"，模型看不到用户**手上已经有哪些未完成任务**，
     * 于是同一件事会被反复建议成新任务 —— 任务库越堆越长，AI 在聊天里能提的"无关紧要的事"
     * 也就越多（用户点名「老是提无关紧要的任务」）。现在把今天/明天该做的、今天做完的一并给它，
     * 并在最后一句明说"已经列在上面的不要再建议"。
     *
     * 无任何可给的内容时返回空串（调用点据此不加分隔符）。
     */
    private suspend fun buildTaskContext(): String {
        val tasks = runCatching { db.taskDao().observeAll().first() }.getOrDefault(emptyList())
        val completions = runCatching { db.taskCompletionDao().observeAll().first() }.getOrDefault(emptyList())
        val existing = com.menghuan.app.data.TaskContext.forExtraction(tasks)
        val sinceDay = java.time.LocalDate.now().toEpochDay() - 6
        val titleById = tasks.associate { it.id to it.title }
        val lines = completions
            .filter { it.day >= sinceDay }
            .groupBy { titleById[it.taskId] }
            .filterKeys { it != null }
            .map { (title, list) -> "- $title（${list.size} 次）" }
            .sorted()
        if (lines.isEmpty()) return existing
        return buildString {
            if (existing.isNotBlank()) append(existing).append("\n\n")
            append("[近 7 天任务完成情况]")
            lines.forEach { append("\n").append(it) }
        }
    }

    /**
     * 这个角色**能读到的**记忆的条目名（v0.85.0）。
     *
     * 口径必须与注入对话时那份一致（[MemoryScope.visible]：自己的 + 开关允许时的基础档案），
     * 否则会出现"模型看得见某条目、却被告知它不存在"，照样重起一个名字。
     * **只给名字、不给 value**：值是模型要更新的对象，把旧值一并给它反而容易照抄旧内容，
     * 而这里的目的只有一个 —— 让它认得出"这件事我记过"。
     *
     * 无记忆时返回空列表（调用点据此不加那一段）。
     */
    private suspend fun buildExistingKeys(personaId: Long): List<String> {
        val all = runCatching { db.profileFactDao().observeAll().first() }.getOrDefault(emptyList())
        if (all.isEmpty()) return emptyList()
        val shared = runCatching { settingsRepository.profileSharedReadable.first() }.getOrDefault(true)
        return MemoryScope.visible(all, personaId, shared).map { it.key }
    }

    /**
     * @return 新增数 to 更新数
     *
     * v0.70.0：**同名判据带上 personaId**。以前 `getByKey(key)` 是全表找，
     * A 角色聊出的"口味=辣"会直接把 B 角色那条同名记忆改掉 ——
     * 记忆分开之后同名条目在不同角色下必须各算一条，否则"分开"只是表面。
     * 用户手填的基础档案（personaId = 0）也不该被某个角色的提炼结果覆盖，所以这里只找本角色的。
     */
    private suspend fun mergeFacts(facts: List<Pair<String, String>>, personaId: Long): Pair<Int, Int> {
        if (facts.isEmpty()) return 0 to 0
        var newCount = 0
        var updatedCount = 0
        // v0.97.0：一次取回本角色现有条目建索引，替代逐条 findScoped（N 条 = 2N 次往返），
        // 整批写进一个事务（以前逐条提交，中途失败会留下"合并了一半"的状态）。
        val byKey = HashMap<String, ProfileFact>()
        db.profileFactDao().getAllForPersona(personaId).forEach { byKey[it.key] = it }
        db.withTransaction {
            facts.forEach { (key, value) ->
                val existing = byKey[key]
                val row = ProfileFact(
                    id = existing?.id ?: 0,
                    key = key,
                    value = value,
                    // 用户手填的条目保持 manual 标记（值可被 AI 刷新，但来源语义不篡改）
                    source = if (existing?.source == "manual") "manual" else "ai",
                    updatedAt = System.currentTimeMillis(),
                    // 提炼产出**永远归到当前这个角色名下**；personaId = 0 那一档只有用户自己在
                    // 「基础档案」页里填的才进得去（否则"按人设分开"会被这条写入路径悄悄打穿）
                    personaId = personaId,
                    lifecycle = existing?.lifecycle ?: "active",
                    category = existing?.category ?: "",
                )
                val id = db.profileFactDao().upsert(row)
                // 同一批里若出现重复 key（模型偶尔重复输出），第二条必须复用刚落库的 id，
                // 否则 id=0 会再插一行（这张表在 (key, personaId) 上没有唯一约束）。
                byKey[key] = row.copy(id = id)
                if (existing == null) newCount++ else updatedCount++
            }
        }
        return newCount to updatedCount
    }

    private operator fun ExtractSummary.plus(other: ExtractSummary) = ExtractSummary(
        personasScanned = personasScanned,
        newFacts = newFacts + other.newFacts,
        updatedFacts = updatedFacts + other.updatedFacts,
        skippedNoApi = skippedNoApi && other.skippedNoApi,
    )

    private companion object {
        const val TAG = "ExtractionEngine"
    }
}
