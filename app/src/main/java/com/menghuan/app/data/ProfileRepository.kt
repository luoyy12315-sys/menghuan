package com.menghuan.app.data

import com.menghuan.app.data.db.AppDatabase
import com.menghuan.app.data.model.ProfileFact
import kotlinx.coroutines.flow.Flow

/**
 * 用户档案仓库：手动维护的 ProfileFact，聊天时作为背景参考注入 system prompt。
 * v0.31.0 起不再播种子档案——新用户的档案为空，条目全部由用户自己添加（或在「我的」页编辑）。
 */
class ProfileRepository(private val db: AppDatabase) {

    /**
     * 「基础档案」页看到的 = **personaId = 0 那一档**（v0.70.0）。
     *
     * 以前这里返回整张表，于是各角色被提炼出来的记忆会一股脑出现在用户的档案页里，
     * 用户以为那是"我自己填的资料"，删也不是留也不是。现在各角色的记忆归记忆页（按角色看）。
     */
    fun observeFacts(): Flow<List<ProfileFact>> = db.profileFactDao().observeGlobal()

    suspend fun getFact(id: Long): ProfileFact? = db.profileFactDao().getById(id)

    suspend fun save(fact: ProfileFact): Long = db.profileFactDao().upsert(fact)

    suspend fun delete(id: Long) = db.profileFactDao().delete(id)
}
