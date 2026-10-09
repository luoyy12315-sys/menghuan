package com.menghuan.app.data

import com.menghuan.app.data.db.AppDatabase
import com.menghuan.app.data.model.MomentEntry

/**
 * 动态动作的落库（v0.79.0 从 `ChatViewModel.maybePostMoments` 抽出来）。
 *
 * ## 为什么要抽成一份
 * 动态现在有**两条链路**会产生动作：
 * ① 聊天：模型回复用户时顺手发帖 / 回帖 / 点赞；
 * ② 主动关心（v0.79.0，用户点名「不跟 AI 聊的时候它也会看我动态」）：App 让角色主动找用户时，
 *    把用户最近发的动态一并给它看，它可以顺手回一句或点个赞。
 *
 * 两条路径要做的校验完全一样（帖子必须存在、同一人只赞一次、正文长度夹取），
 * 各写一份必然分叉（本项目吃过"两边各说各话"的亏）—— 所以这里只有一个实现，
 * 差别只在 [Limits]：聊天松一点（顺手多发），主动链更收（别像刷屏机器）。
 *
 * ⚠️ 三个硬纪律（从原实现原样搬过来，别改）：
 * - **回帖/点赞先验 id**：模型凭空编的编号静默丢掉 + 日志留痕，绝不在库里造一条指向空气的记录；
 * - **点赞是幂等的「只加不取消」**：模型可能把 `[点赞:5]` 重复说两遍，toggle 会把第一次的赞撤销掉，
 *   用户看到的是"AI 点了赞又没了"。取消点赞不开放给 AI；
 * - 正文一律过 [MomentMath.sanitize] 夹长度。
 */
object MomentActions {

    /**
     * 一轮里的动作上限。
     *
     * - [CHAT]：与 v0.77.0 的字面值一致（发 2 / 回 2 / 赞 3）；
     * - [PROACTIVE]：用户拍板"同主动关心的档位"—— 每次最多回 1 条、赞 1 条，
     *   发新帖 1 条（主动关心的正文本来就只有一条消息，顺手发帖不该变成刷屏）。
     */
    data class Limits(val posts: Int, val replies: Int, val likes: Int) {
        companion object {
            val CHAT = Limits(posts = 2, replies = 2, likes = 3)
            val PROACTIVE = Limits(posts = 1, replies = 1, likes = 1)
        }
    }

    /** 这一次真正落库的动作数（给日志与单测用）。 */
    data class Applied(val posts: Int, val replies: Int, val likes: Int) {
        val total: Int get() = posts + replies + likes
    }

    /**
     * 解析 [raw] 里的动态标记并落库。
     *
     * @return 真正生效的动作数 —— 被丢掉的（不存在的 id、重复的赞）不计入。
     */
    suspend fun apply(
        db: AppDatabase,
        personaId: Long,
        raw: String,
        limits: Limits = Limits.CHAT,
        now: Long = System.currentTimeMillis(),
    ): Applied {
        val decision = MomentMarker.parse(raw)
        if (!decision.wantsAnything) return Applied(0, 0, 0)

        var posted = 0
        var replied = 0
        var liked = 0

        decision.posts.take(limits.posts).forEach { text ->
            runCatching {
                db.momentDao().upsert(
                    MomentEntry(
                        personaId = personaId,
                        parentId = 0L,
                        text = MomentMath.sanitize(text, MomentMath.POST_MAX),
                        createdAt = now,
                    )
                )
            }.onSuccess { posted++ }
        }

        decision.replies.take(limits.replies).forEach { (parentId, text) ->
            val parent = runCatching { db.momentDao().getById(parentId) }.getOrNull()
            if (parent == null) {
                android.util.Log.w("Menghuan", "moment reply dropped: post $parentId does not exist")
                return@forEach
            }
            runCatching {
                db.momentDao().upsert(
                    MomentEntry(
                        personaId = personaId,
                        parentId = parentId,
                        text = MomentMath.sanitize(text, MomentMath.REPLY_MAX),
                        createdAt = now,
                    )
                )
            }.onSuccess { replied++ }
        }

        // AI 点赞：赞记成**这个角色自己的标识**（`likedBy` 里存 personaId 字符串），
        // 与用户本人（[MomentMath.USER_ID] = "u"）区分，于是「谁赞过」在数据上是分得清的。
        val myLikeId = personaId.toString()
        decision.likes.take(limits.likes).forEach { postId ->
            val post = runCatching { db.momentDao().getById(postId) }.getOrNull()
            if (post == null) {
                android.util.Log.w("Menghuan", "moment like dropped: post $postId does not exist")
                return@forEach
            }
            if (MomentMath.likedBy(post.likedBy, myLikeId)) return@forEach
            runCatching {
                db.momentDao().setLikedBy(postId, MomentMath.toggleLike(post.likedBy, myLikeId))
            }.onSuccess { liked++ }
        }

        return Applied(posts = posted, replies = replied, likes = liked)
    }
}
