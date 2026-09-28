package com.daoshi.app.data

import com.daoshi.app.data.db.AppDatabase
import com.daoshi.app.data.model.Persona
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 人设仓库：默认种子仅为示例，全部可编辑。
 */
class PersonaRepository(private val db: AppDatabase) {

    fun observePersonas(): Flow<List<Persona>> =
        db.personaDao().observeAll().onStart { seedOnce() }

    suspend fun getPersona(id: Long): Persona? = db.personaDao().getById(id)

    suspend fun save(persona: Persona): Long = db.personaDao().upsert(persona)

    suspend fun delete(id: Long) = db.personaDao().deleteCustom(id)

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

    /** 调用方已保证空库（seedOnce 里 COUNT 判定） */
    private suspend fun seed() {
        db.personaDao().upsert(
            Persona(
                name = "阿布",
                title = "人生导师",
                avatarEmoji = "🖋️",
                isBuiltIn = true,
                systemPrompt = """
                    你是用户的个人全能指导导师，覆盖生活、学习、工作三个领域。
                    你会记住用户的目标与习惯，主动发现问题，给出可执行的建议，
                    并在用户逃避任务时直接点破。回复简洁、具体，少说教，多行动。
                """.trimIndent(),
            )
        )
        db.personaDao().upsert(
            Persona(
                name = "严格教练",
                title = "严格教练",
                avatarEmoji = "🎯",
                isBuiltIn = true,
                systemPrompt = "你是用户的严格教练，只关心执行和结果，指出问题毫不客气。",
            )
        )
        db.personaDao().upsert(
            Persona(
                name = "苏格拉底",
                title = "苏格拉底",
                avatarEmoji = "💭",
                isBuiltIn = true,
                systemPrompt = "你是用户的苏格拉底式导师，通过连续追问帮助用户自己找到答案，少给结论。",
            )
        )
    }
}
