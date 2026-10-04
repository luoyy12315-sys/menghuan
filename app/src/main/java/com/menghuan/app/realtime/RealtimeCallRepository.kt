package com.menghuan.app.realtime

import com.menghuan.app.data.db.AppDatabase
import com.menghuan.app.data.model.CallSession
import com.menghuan.app.data.model.CallTurn
import kotlinx.coroutines.flow.Flow

/**
 * 通话记录仓库：通话期间引擎只在内存攒 [CallTurnData]，
 * 挂断时**一次性**写 Room（任务书 §二十：partial 绝不入库）。只存文字不存音频，
 * 转写恒定保存（用户拍板：不留开关）。同时在聊天流里落一条通话记录卡
 * （"语音通话 · X分X秒"，点开看本次文字转写——v0.49.0）。
 */
class RealtimeCallRepository(private val db: AppDatabase) {

    suspend fun saveCall(
        personaId: Long,
        startedAt: Long,
        endedAt: Long,
        turns: List<CallTurnData>,
        status: String,
    ) {
        val sessionId = db.callDao().insertSession(
            CallSession(
                personaId = personaId,
                startedAt = startedAt,
                endedAt = endedAt,
                durationMs = (endedAt - startedAt).coerceAtLeast(0),
                status = status,
            ),
        )
        if (turns.isNotEmpty()) {
            db.callDao().insertTurns(
                turns.map {
                    CallTurn(
                        sessionId = sessionId,
                        speaker = it.speaker,
                        text = it.text,
                        timestamp = it.timestamp,
                    )
                },
            )
        }
        // 聊天流里的通话记录卡：头像方向朝用户侧，点开弹文字转写（CallLogSheet）
        val minutes = (endedAt - startedAt).coerceAtLeast(0) / 60000
        val seconds = ((endedAt - startedAt).coerceAtLeast(0) % 60000) / 1000
        runCatching {
            db.messageDao().insert(
                com.menghuan.app.data.model.ChatMessage(
                    conversationId = personaId,
                    content = "语音通话 · ${minutes}分${seconds.toString().padStart(2, '0')}秒",
                    isFromUser = true,
                    timestamp = endedAt,
                    mediaKind = com.menghuan.app.data.model.MediaKind.CALL.name,
                    attachmentPath = sessionId.toString(),
                ),
            )
        }
    }

    fun observeSessions(personaId: Long): Flow<List<CallSession>> =
        db.callDao().observeSessions(personaId)

    suspend fun turns(sessionId: Long): List<CallTurn> = db.callDao().turns(sessionId)
}
