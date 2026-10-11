package com.menghuan.app.data

import com.menghuan.app.data.db.AppDatabase

/**
 * 各条「模型来写一段话」链路的共享取材块（v0.94）。
 *
 * ## 为什么要集中
 * 主动关心 / 定时消息 / AI 来电开场白三条链各写各的取材，已经长出了
 * **档案条数 10/8/10 不一致**、**电话开场白带最近聊天、主动链不带** 这类没有设计含义的分叉。
 * 这里收口两样：
 * - [PROFILE_FACTS_MAX]：喂给模型的**角色记忆**条数，四条链（主动关心 / 定时消息 / 来电开场白 /
 *   实时通话）一律 10 —— **基础档案另算、全带不设限**（v0.98.0 起拆成两条线，见 [MemoryScope.split]）；
 * - [recentChatBlock]：「最近的聊天片段」，三条链同一个形状
 *   （TA 主动找你时不记得你们上次聊到哪，是最出戏的地方之一）。
 */
object PromptBlocks {

    /**
     * 单轮请求里**这个角色自己的记忆**条数上限（主动关心 / 定时消息 / 来电开场白 / 实时通话共用）。
     *
     * ⚠️ v0.98.0 起它只管"角色记忆"这一条线：基础档案（用户手填的）**全带、不设上限**，
     * 见 [com.menghuan.app.data.MemoryScope.split]。以前它数的是"两条线合并后的总数"，
     * 于是用户基础档案一多，角色的记忆一条都挤不进来。
     */
    const val PROFILE_FACTS_MAX = 10

    /** 最近片段取几条 */
    private const val RECENT_COUNT = 6

    /** 每条截多少字：一行展示得下的长度，整段塞进去只会让模型抓不住重点 */
    private const val RECENT_TEXT_MAX = 60

    /**
     * 「最近的聊天片段」块：TA 主动开口前先知道自己上次跟用户说到哪了。
     *
     * 空会话返回「暂无」（与【用户档案】的兜底同形状），调用方**无条件拼**，
     * 不用判空——这段本来就该在场。
     */
    internal suspend fun recentChatBlock(db: AppDatabase, personaId: Long): String {
        val recent = runCatching { db.messageDao().getRecentPaged(personaId, RECENT_COUNT, 0).asReversed() }
            .getOrDefault(emptyList())
        return buildString {
            append("【最近的聊天片段】")
            if (recent.isEmpty()) {
                append("暂无")
            } else {
                recent.forEach { m ->
                    append("\n- ").append(if (m.isFromUser) "用户：" else "你：")
                        .append(m.content.take(RECENT_TEXT_MAX))
                }
            }
        }
    }
}
