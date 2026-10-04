package com.menghuan.app.data

import com.menghuan.app.data.db.AppDatabase
import com.menghuan.app.data.model.ChatMessage
import com.menghuan.app.data.model.Persona
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** 未读会话数（纯函数，JVM 单测）：口径与会话列表行 showDot 完全一致 */
object UnreadLogic {

    /**
     * 统计当前应显示未读点的会话数：
     * - 可见会话 = !(hidden 且无消息)（微信式删除的会话不计）
     * - 未读 = 最后一条消息来自 AI，且晚于该会话的已读时刻
     */
    fun count(
        personas: List<Persona>,
        lastMessages: List<ChatMessage>,
        readMarks: Map<Long, Long>,
    ): Int {
        val byConversation = lastMessages.associateBy { it.conversationId }
        return personas.count { persona ->
            val last = byConversation[persona.id]
            !(persona.hidden && last == null) &&
                last != null && !last.isFromUser &&
                last.timestamp > (readMarks[persona.id] ?: 0L)
        }
    }
}

/**
 * 应用级未读计数器：底栏「消息」tab 的未读小点数据源。
 * 流常驻应用作用域（WhileSubscribed：无订阅 5 秒后自动停）。
 */
class UnreadCenter(
    db: AppDatabase,
    personaRepository: PersonaRepository,
    settingsRepository: SettingsRepository,
    scope: CoroutineScope,
) {
    val unreadCount: StateFlow<Int> = combine(
        personaRepository.observePersonas(),
        db.messageDao().observeLastMessages(),
        settingsRepository.readMarks,
    ) { personas, lastMessages, readMarks ->
        UnreadLogic.count(personas, lastMessages, readMarks)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), 0)
}
