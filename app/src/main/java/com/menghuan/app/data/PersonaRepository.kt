package com.menghuan.app.data

import com.menghuan.app.data.db.AppDatabase
import com.menghuan.app.data.model.Persona
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 人设仓库：新用户仅预置一个内置「应用助手」，其余人设全部由用户自己创建（可增删改）。
 */
class PersonaRepository(
    private val db: AppDatabase,
    private val settingsRepository: SettingsRepository? = null,
    /** v0.58.0：删除人设时清掉他的通话视觉素材目录（仓库层没有 Context，由容器注入） */
    private val visualFilesCleaner: ((Long) -> Unit)? = null,
) {

    fun observePersonas(): Flow<List<Persona>> =
        db.personaDao().observeAll().onStart { seedOnce() }

    suspend fun getPersona(id: Long): Persona? = db.personaDao().getById(id)

    suspend fun save(persona: Persona): Long {
        // v0.68.0：判"是不是内置助手"不能只看名字 —— 改过名的那份也是它。
        // 认错的后果不是崩，而是**手册的纳管标记写错了对象**：
        // 用户改的是助手手册，标记却没置上 → 下一版启动直接把他的自定义内容冲掉。
        if (persona.name == ASSISTANT_PERSONA_NAME || isAssistantById(persona.id)) {
            // 亲手改过助手的设定词 = 用户接管这份手册，从此不再自动覆盖；
            // 改回与默认完全一致 = 重新交还给 App 维护（该段下方那颗「恢复」+「保存」就是这条路）
            settingsRepository?.setAssistantPromptCustomized(
                persona.systemPrompt.trim() != AiPrompts.DEFAULT_ASSISTANT_PROMPT.trim(),
            )
        }
        return db.personaDao().upsert(persona)
    }

    /**
     * 找到内置「应用助手」（v0.68.0）。**所有"找助手"的地方都必须走这一条**，不要再各自 `getByName`。
     *
     * 两级：
     * 1. 按固定名字 —— 正常装机一次就中；
     * 2. 名字找不到就认"内置人设里身上还挂着内置手册形状的那一份"。
     *
     * 为什么要有第 2 级：老逻辑只按名字查，用户一旦把助手改名（人设编辑器**不锁名字**），
     * 三件事会同时坏：① 提示词页查不到 → 显示的是出厂手册而不是他那份；
     * ② 点保存走 `?: Persona(…)` **再建一条内置助手**，列表里出现两个；
     * ③ 启动升级也按名字找 → 他那本手册从此永远停在改名那一刻。
     * 这一级把已经改过名的历史装机捞回来（新装机由编辑器锁名兜住）。
     */
    suspend fun findAssistant(): Persona? {
        val dao = db.personaDao()
        dao.getByName(ASSISTANT_PERSONA_NAME)?.let { return it }
        return dao.getBuiltIns().firstOrNull {
            AiPrompts.isBuiltinAssistantManual(it.systemPrompt.trim())
        }
    }

    private suspend fun isAssistantById(id: Long): Boolean =
        id != 0L && findAssistant()?.id == id

    /**
     * 物理删除人设 = 级联清理：该会话全部消息 + 未读标记 + 提炼水位线 + 会话级接口覆盖 + 人设音色
     * + 通话视觉素材目录。
     * （人设无独立闹钟；任务闹钟按任务独立，不受影响。）
     */
    suspend fun delete(id: Long) {
        db.messageDao().clearConversation(id)
        // v0.70.0：记忆按人设分开之后，删角色必须把它自己聊出来的那部分一起清掉，
        // 否则会留下一堆没人能读到的孤儿行（且 id 不复用，越攒越多）。
        // deleteForPersona 的 SQL 里写死了 `personaId != 0`：**用户本人的基础档案永远不跟着角色删**。
        // v0.82.0 P1-2：这些记忆的语义向量一起清（先拿 id 再删，顺序不能反）。
        val orphanFactIds = runCatching {
            db.profileFactDao().observeOwnedBy(id).first().map { it.id }
        }.getOrDefault(emptyList())
        if (orphanFactIds.isNotEmpty()) {
            runCatching { db.memoryEmbeddingDao().deleteForFacts(orphanFactIds) }
        }
        db.profileFactDao().deleteForPersona(id)
        // v0.82.0 P1-1：这个会话的滚动摘要一并删（会话没了，摘要就是孤儿行）
        runCatching { db.conversationSummaryDao().delete(id) }
        db.personaDao().deleteById(id)
        settingsRepository?.removeReadMark(id)
        settingsRepository?.removeExtractionWatermark(id)
        settingsRepository?.removePersonaApiProfile(id)
        settingsRepository?.removeVoiceRef(id)
        // v0.54.4：专属铃声与「主动关心」开关一并清掉（不留孤儿键）
        settingsRepository?.setPersonaRingtone(id, "")
        settingsRepository?.setPersonaProactive(id, false)
        // v0.66.0：视频通话背景的档位行一并摘掉（图片目录由 visualFilesCleaner 清）
        settingsRepository?.setCallBackground(id, "")
        // v0.58.0：视觉素材是几百 MB 的文件，人设没了就必须跟着没
        runCatching { visualFilesCleaner?.invoke(id) }
    }

    /** 对话风格按 persona 持久化（长按头像切换后保存） */
    suspend fun updateTone(id: Long, tone: String) = db.personaDao().updateTone(id, tone)

    /** 会话置顶（长按会话行切换） */
    suspend fun setPinned(id: Long, pinned: Boolean) = db.personaDao().setPinned(id, pinned)

    /** 会话删除标记（微信式隐藏，重新打开/新消息即恢复） */
    suspend fun setHidden(id: Long, hidden: Boolean) = db.personaDao().setHidden(id, hidden)

    /**
     * 种子只播一次。此前在 onStart 里"查空再插"没有并发保护：
     * 两个收集器（会话列表 + 数据统计）同时首次订阅时双双判空，种子重复插入，
     * 会话列表出现两条同一人设（截图测试抓到的真 bug）。
     */
    private val seedMutex = Mutex()
    @Volatile
    private var seeded = false

    private suspend fun seedOnce() {
        if (seeded) return
        seedMutex.withLock {
            if (seeded) return
            if (db.personaDao().count() == 0) {
                seed()
            }
            seeded = true
        }
    }

    /**
     * 种子只播一个内置「应用助手」（应用使用指南会话，提示词可在设置里改）。
     * 其他人设一律由用户自己创建——新用户的人设列表、基础档案、聊天消息全部为空
     * （v0.31.0 起取消三个示例人设种子，用户反馈）。
     */
    private suspend fun seed() {
        db.personaDao().upsert(
            Persona(
                name = ASSISTANT_PERSONA_NAME,
                title = ASSISTANT_PERSONA_TITLE,
                avatarEmoji = "🤖",
                isBuiltIn = true,
                systemPrompt = AiPrompts.DEFAULT_ASSISTANT_PROMPT,
            )
        )
        // 刚写进去的就是当前版手册，重新交还给 App 维护（否则此前"用户改过"的标记
        // 会让这枚新助手永久卡在出厂版）
        settingsRepository?.setAssistantPromptCustomized(false)
    }

    /**
     * 确保内置「应用助手」存在（启动时调用）：缺失则补建——内置助手被删后下次启动回来；
     * 用户自己的其他人设不受影响（只补建缺失的助手，不覆盖任何已存在行）。
     */
    suspend fun ensureAssistant() {
        runCatching {
            if (findAssistant() == null) {
                seed()
            }
        }
    }

    /**
     * 内置「应用助手」的使用手册跟着版本走：只要用户没亲手改过设定词，每次启动都刷成
     * 最新的 [AiPrompts.DEFAULT_ASSISTANT_PROMPT]（AGENTS.md 零.5 的硬要求）。
     *
     * **v0.51.1 重写的缘故**：旧实现是"库里这段是否**精确等于**某个历史默认文案"，
     * 而那份历史文案表只登到 v0.38——每版都是在
     * `DEFAULT_ASSISTANT_PROMPT` 上原地追加 `.replace()`，中间态从来没登记过，
     * 于是 v0.41~v0.50 之间升级的用户再也匹配不上，手册永久卡在旧版：
     * v0.50.0 写了整章语音配置教程，真机上助手却依然答不上"语音怎么配"。
     *
     * 新判定只看两件事，**以后改手册不必再登记任何历史全文**：
     * 1. 用户改过吗？改过就永不覆盖（保存路径 [save] 会置标记；在提示词页把这一格「恢复」成默认
     *    再「保存」，标记就清掉、重新纳管 —— v0.54.4 起页面上没有全局的「全部恢复默认」按钮了）。
     *    DataStore 里是 null（本机制引入前的老装机）时，用"这段是不是内置手册的形状"一次性判：
     *    形状对得上=历史版手册（升到最新），对不上=用户自己写的（保护）。
     * 2. 没改过就无条件对齐当前默认（等于就直接返回，不多写一次库）。
     */
    suspend fun refreshAssistantPromptIfNeeded() {
        runCatching {
            val settings = settingsRepository ?: return
            // 走 findAssistant（v0.68.0）：按名字找会让改过名的装机永久停在旧手册上
            val persona = findAssistant() ?: return
            val default = AiPrompts.DEFAULT_ASSISTANT_PROMPT
            val current = persona.systemPrompt.trim()
            if (current == default.trim()) return
            val customized = settings.assistantPromptCustomized()
                ?: !AiPrompts.isBuiltinAssistantManual(current)
            if (!customized) {
                db.personaDao().upsert(persona.copy(systemPrompt = default))
            }
        }
    }
}
