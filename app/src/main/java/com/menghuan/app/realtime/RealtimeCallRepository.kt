package com.menghuan.app.realtime

import com.menghuan.app.data.db.AppDatabase
import com.menghuan.app.data.model.CallSession
import com.menghuan.app.data.model.CallTurn
import kotlinx.coroutines.flow.Flow

/**
 * 通话记录仓库：通话期间引擎只在内存攒 [CallTurnData]，
 * 挂断时**一次性**写 Room（任务书 §二十：partial 绝不入库）。只存文字不存音频。
 */
class RealtimeCallRepository(private val db: AppDatabase) {

    suspend fun saveCall(
        personaId: Long,
        startedAt: Long,
        endedAt: Long,
        turns: List<CallTurnData>,
        status: String,
        saveTranscript: Boolean,
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
        if (saveTranscript && turns.isNotEmpty()) {
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
    }

    fun observeSessions(personaId: Long): Flow<List<CallSession>> =
        db.callDao().observeSessions(personaId)

    suspend fun turns(sessionId: Long): List<CallTurn> = db.callDao().turns(sessionId)
}
