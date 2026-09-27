package com.daoshi.app.data

import com.daoshi.app.data.db.AppDatabase
import com.daoshi.app.data.model.ProfileFact
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 用户档案仓库：手动维护的 ProfileFact，聊天时注入 system prompt。
 * 首次启动播 4 条种子（与预览一致），全部可编辑、可删除。
 */
class ProfileRepository(private val db: AppDatabase) {

    fun observeFacts(): Flow<List<ProfileFact>> =
        db.profileFactDao().observeAll().onStart { seedOnce() }

    suspend fun getFact(id: Long): ProfileFact? = db.profileFactDao().getById(id)

    suspend fun save(fact: ProfileFact): Long = db.profileFactDao().upsert(fact)

    suspend fun delete(id: Long) = db.profileFactDao().delete(id)

    /** 种子只播一次（Mutex 防并发首订竞态，COUNT 判空省全量查询） */
    private val seedMutex = Mutex()
    @Volatile
    private var seeded = false

    private suspend fun seedOnce() {
        if (seeded) return
        seedMutex.withLock {
            if (seeded) return
            if (db.profileFactDao().count() == 0) {
                seed()
            }
            seeded = true
        }
    }

    private suspend fun seed() {
        val seeds = listOf(
            "当前目标" to "每天 23:00 前睡觉",
            "作息" to "通常 00:30–07:45",
            "职业" to "自由职业",
            "关注领域" to "睡眠 · 英语 · 健身",
        )
        seeds.forEach { (key, value) ->
            db.profileFactDao().upsert(ProfileFact(key = key, value = value))
        }
    }
}
