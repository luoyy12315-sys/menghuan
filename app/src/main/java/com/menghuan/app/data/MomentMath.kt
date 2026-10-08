package com.menghuan.app.data

import com.menghuan.app.data.model.MomentEntry

/**
 * 动态（朋友圈/推特式时间线）的纯计算（v0.73.0，JVM 单测覆盖）。
 *
 * ## 一张表装两种行
 * `parentId = 0` 是**帖子**，非 0 是**回复**。没有第三层：回复的回复也挂同一个 parentId。
 * 推特本人就是这个形状（楼中楼只有一层），而三层以上的树会把 UI 拖成缩套娃，
 * 陪伴 App 的讨论量级也根本用不上。
 *
 * ## 为什么点赞存成一串 id 而不是关系表
 * `likedBy` 是逗号分隔的 id 串（用户固定是 [USER_ID]，AI 用它的 personaId）。
 * 本机的量级是"几十个角色 × 几百条动态"，多一张表就多一次 join、多一处能不一致的地方。
 * 真要统计"谁赞过"再拆表不迟。
 *
 * ## 排序口径只在这一处
 * DAO 只按时间倒序把全量拿到内存，帖子倒序、每条帖子里的回复**正序**
 * （时间线读起来才是"上面先说、下面后说"，与推特的帖子详情页一致）。
 */
object MomentMath {

    /** 用户本人在 `likedBy` 里的固定标识（personaId 是数字，不会和它撞） */
    const val USER_ID = "u"

    /** 一条帖子 + 它下面的回复（回复按时间正序） */
    data class Thread(val post: MomentEntry, val replies: List<MomentEntry>) {
        val replyCount: Int get() = replies.size
        val likeCount: Int get() = likeCountOf(post)
    }

    /** 回复的字数上限：比帖子短，鼓励"一句话"而不是把回复写成小作文 */
    const val REPLY_MAX = 200

    /** 帖子的字数上限（推特 280 的口径，中文按字算更紧，这里取 300） */
    const val POST_MAX = 300

    fun isReply(m: MomentEntry): Boolean = m.parentId != 0L

    /**
     * 全量 → 时间线：帖子按时间倒序，每个 thread 带自己的回复（正序）。
     *
     * ⚠️ **孤儿回复要丢掉**：删帖子时 [com.menghuan.app.data.db.MomentDao.deleteThread] 会连带删回复，
     * 但如果用户在两条数据之间手动清过库、或将来改删除逻辑漏了一支，
     * `parentId` 指向不存在的帖子的那种行会永远挂不进任何 thread —— 与其让它凭空消失，
     * 不如在这里显式丢掉，时间线的条数才和屏幕上看到的一致。
     */
    fun threads(all: List<MomentEntry>): List<Thread> {
        val posts = all.filter { !isReply(it) }.sortedByDescending { it.createdAt }
        if (posts.isEmpty()) return emptyList()
        val ids = posts.map { it.id }.toSet()
        val byParent = all.filter { isReply(it) && it.parentId in ids }
            .groupBy { it.parentId }
            .mapValues { (_, v) -> v.sortedBy { it.createdAt } }
        return posts.map { Thread(it, byParent[it.id] ?: emptyList()) }
    }

    /**
     * 某条帖子详情页要的那一组：thread 不存在（帖子被删）返回 null，
     * 页面据此直接返回上一级，而不是画一个空白标题。
     */
    fun threadOf(all: List<MomentEntry>, id: Long): Thread? =
        threads(all).firstOrNull { it.post.id == id }

    // ===== 点赞 =====

    fun likedByIds(raw: String): List<String> =
        raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * 翻转某个人的赞（幂等：再来一次就是取消）。
     *
     * 返回**新的 likedBy 串**，交给 DAO 整列覆盖 —— 不做增量 SQL，
     * 因为 SQLite 里没有"从逗号串里删一个元素"的干净写法。
     */
    fun toggleLike(raw: String, who: String): String {
        val ids = likedByIds(raw).toMutableList()
        if (!ids.remove(who)) ids += who
        return ids.joinToString(",")
    }

    fun likedBy(raw: String, who: String): Boolean = who in likedByIds(raw)

    fun likeCountOf(m: MomentEntry): Int = likedByIds(m.likedBy).size

    // ===== 相对时间 =====

    /**
     * 时间线上的时间戳：推特口径的"刚刚 / 5 分 / 3 小时 / 昨天 / 10月8日"。
     *
     * 为什么不用 `DateUtils.getRelativeTimeSpanString`：它是 Android framework 的，
     * JVM 单测里加载不了（本项目所有纯函数都要能在 JVM 跑），而且它的中文措辞
     * 与"昨天/周几"的切换点和我们要的不一致。
     */
    fun timeLabel(
        createdAt: Long,
        now: Long = System.currentTimeMillis(),
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): String {
        val diff = now - createdAt
        if (diff < 0) return "刚刚"
        val minute = 60_000L
        val hour = 60 * minute
        val day = 24 * hour
        return when {
            diff < minute -> "刚刚"
            diff < hour -> "${diff / minute} 分钟前"
            diff < day -> "${diff / hour} 小时前"
            diff < 7 * day -> "${diff / day} 天前"
            else -> {
                val d = java.time.Instant.ofEpochMilli(createdAt).atZone(zone).toLocalDate()
                "${d.monthValue}月${d.dayOfMonth}日"
            }
        }
    }

    /** 详情页的绝对时间（帖子下面显示一次，让"几天前"不再模糊） */
    fun absoluteLabel(
        createdAt: Long,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): String {
        val dt = java.time.Instant.ofEpochMilli(createdAt).atZone(zone).toLocalDateTime()
        return java.time.format.DateTimeFormatter
            .ofPattern("yyyy年M月d日 HH:mm")
            .format(dt)
    }

    /** 发帖/回复的收口：去空白、压到上限字数；空串 = 不发 */
    fun sanitize(text: String, max: Int = POST_MAX): String {
        val t = text.trim()
        return if (t.length <= max) t else t.take(max)
    }
}
