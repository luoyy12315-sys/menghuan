package com.menghuan.app.data

import com.menghuan.app.data.db.AppDatabase
import com.menghuan.app.data.model.Persona
import kotlinx.coroutines.flow.Flow
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
        if (persona.name == ASSISTANT_PERSONA_NAME) {
            // 亲手改过助手的设定词 = 用户接管这份手册，从此不再自动覆盖；
            // 改回与默认完全一致（含「全部恢复默认」）= 重新交还给 App 维护
            settingsRepository?.setAssistantPromptCustomized(
                persona.systemPrompt.trim() != AiPrompts.DEFAULT_ASSISTANT_PROMPT.trim(),
            )
        }
        return db.personaDao().upsert(persona)
    }

    /**
     * 物理删除人设 = 级联清理：该会话全部消息 + 未读标记 + 提炼水位线 + 会话级接口覆盖 + 人设音色
     * + 通话视觉素材目录。
     * （人设无独立闹钟；任务闹钟按任务独立，不受影响。）
     */
    suspend fun delete(id: Long) {
        db.messageDao().clearConversation(id)
        db.personaDao().deleteById(id)
        settingsRepository?.removeReadMark(id)
        settingsRepository?.removeExtractionWatermark(id)
        settingsRepository?.removePersonaApiProfile(id)
        settingsRepository?.removeVoiceRef(id)
        // v0.54.4：专属铃声与「主动关心」开关一并清掉（不留孤儿键）
        settingsRepository?.setPersonaRingtone(id, "")
        settingsRepository?.setPersonaProactive(id, false)
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
            if (db.personaDao().getByName(ASSISTANT_PERSONA_NAME) == null) {
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
     * 1. 用户改过吗？改过就永不覆盖（保存路径 [save] 会置标记；「全部恢复默认」把标记清掉、重新纳管）。
     *    DataStore 里是 null（本机制引入前的老装机）时，用"这段是不是内置手册的形状"一次性判：
     *    形状对得上=历史版手册（升到最新），对不上=用户自己写的（保护）。
     * 2. 没改过就无条件对齐当前默认（等于就直接返回，不多写一次库）。
     */
    suspend fun refreshAssistantPromptIfNeeded() {
        runCatching {
            val settings = settingsRepository ?: return
            val persona = db.personaDao().getByName(ASSISTANT_PERSONA_NAME) ?: return
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
