package com.menghuan.app.data

import android.util.Log
import com.menghuan.app.data.db.AppDatabase
import com.menghuan.app.data.model.MemoryEmbedding
import com.menghuan.app.data.model.ProfileFact
import com.menghuan.app.llm.OpenAiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * 记忆向量的后台补算器（v0.82.0 P1-2）。
 *
 * ## 为什么是"召回时顺手补"，而不是写入时算
 * 记忆的写入点有好几处（`<add:>` 暂存、提炼合并、记忆页审批），逐个埋点迟早漏一个；
 * 而**没有向量的记忆只是本轮用不上语义分**（词法分照常），没有任何正确性问题。
 * 所以索引做成懒的：召回路过哪几条就补哪几条，常聊的自然先齐，冷门的永远不白算。
 *
 * 一批最多 [BATCH] 条、**一次请求**（OpenAI 兼容端点的 input 本来就是数组），
 * 失败静默——下一轮召回会再试，与"端点调不通不偷改开关"同一条纪律。
 */
object MemoryEmbeddingIndexer {

    private const val TAG = "MemoryEmbeddingIndexer"

    /** 单次补算的最大条数（一次请求带上界，防一次把全库塞进去） */
    const val BATCH = 16

    private val mutex = Mutex()

    /** 发现缺向量就后台补（不阻塞调用方；同一时刻只跑一批，双跑只是白花一份 token） */
    fun ensureIndexed(
        scope: CoroutineScope,
        db: AppDatabase,
        settingsRepository: SettingsRepository,
        facts: List<ProfileFact>,
        config: EmbeddingConfig,
    ) {
        if (facts.isEmpty()) return
        scope.launch {
            if (!mutex.tryLock()) return@launch
            try {
                runCatching { run(db, settingsRepository, facts, config) }
                    .onFailure { Log.w(TAG, "index failed", it) }
            } finally {
                mutex.unlock()
            }
        }
    }

    internal suspend fun run(
        db: AppDatabase,
        settingsRepository: SettingsRepository,
        facts: List<ProfileFact>,
        config: EmbeddingConfig,
    ) {
        val bucket = settingsRepository.embeddingBucketOf(config)
        val ids = facts.map { it.id }
        val have = db.memoryEmbeddingDao().existingIds(ids, bucket).toSet()
        val missing = facts.filter { it.id !in have }.take(BATCH)
        if (missing.isEmpty()) return
        val vectors = OpenAiClient(config.baseUrl, config.apiKey, config.model)
            .embedBatchOrThrow(missing.map { "${it.key}：${it.value}" })
        db.memoryEmbeddingDao().upsertAll(
            missing.zip(vectors).map { (f, v) ->
                MemoryEmbedding(factId = f.id, bucket = bucket, vector = EmbeddingVector.encode(v))
            }
        )
    }
}
