package com.menghuan.app.data

import com.menghuan.app.data.db.AppDatabase

/**
 * 记忆生命周期的自动维护（v0.82.0 P1-3）：六态不靠用户手动管。
 *
 * - **降级**在这里：active 条目 [MemoryScope.AUTO_ARCHIVE_AFTER_MS]（90 天）没被召回命中
 *   → 自动降 `archived`（不进注入、仍可在记忆页搜到）。`core` 豁免（那是人的决定）。
 * - **升级**在召回链路上：`archived` 被高分召回命中 → 当场升回 `active`
 *   （ChatViewModel.memoryRecallBlock，想起即复活）。
 *
 * 调用点：进程启动一次（MenghuanApp）+ 每次提炼顺手扫一次（ExtractionEngine）。
 * 纯 DB 操作，不发任何 LLM 请求。
 */
object MemoryLifecycle {

    /** 把超期未被想起的 active 降 archived；返回本次归档条数（诊断/测试用） */
    suspend fun sweepAutoArchive(db: AppDatabase, nowMs: Long): Int {
        var archived = 0
        db.profileFactDao().listActive().forEach { fact ->
            if (MemoryScope.shouldAutoArchive(fact, nowMs)) {
                db.profileFactDao().upsert(fact.copy(lifecycle = "archived"))
                archived++
            }
        }
        return archived
    }
}
