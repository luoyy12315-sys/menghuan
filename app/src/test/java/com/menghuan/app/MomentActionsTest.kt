package com.menghuan.app

import androidx.test.core.app.ApplicationProvider
import com.menghuan.app.data.MomentActions
import com.menghuan.app.data.MomentMath
import com.menghuan.app.data.model.MomentEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 动态动作落库（v0.79.0 从 ChatViewModel 抽出的实现）。
 *
 * ⚠️ 用户后来拍板「动态只存于主动关心那条链」——聊天链已整块退出动态（协议段、每轮名单、
 * 落库调用点全删），所以这份实现现在只有**一个调用方**，`Limits.CHAT` 也一起删了。
 * 这里测的就是这条链要守的几条：先验 id、点赞幂等、按上限截断。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = MenghuanApp::class, sdk = [34])
class MomentActionsTest {

    private fun app() = ApplicationProvider.getApplicationContext<MenghuanApp>()

    @Test
    fun `回帖与点赞都先验帖子存在_编出来的编号静默丢弃`() = runBlocking {
        val a = app()
        val applied = MomentActions.apply(
            a.db,
            personaId = 7L,
            raw = "[动态#999:不知道哪来的帖子]\n[点赞:999]",
        )
        assertEquals("不存在的帖子不回帖", 0, applied.replies)
        assertEquals("不存在的帖子不点赞", 0, applied.likes)
        assertEquals("什么都没落库", 0, applied.total)
        assertTrue(a.db.momentDao().observeAll().first().isEmpty())
    }

    @Test
    fun `点赞幂等_同一轮或相邻轮说两遍只记一次`() = runBlocking {
        val a = app()
        val postId = a.db.momentDao().upsert(MomentEntry(personaId = 0L, text = "我今天去爬山了"))

        val first = MomentActions.apply(a.db, personaId = 7L, raw = "好棒\n[点赞:$postId]")
        val second = MomentActions.apply(a.db, personaId = 7L, raw = "[点赞:$postId]")

        assertEquals("第一次真的点了", 1, first.likes)
        assertEquals("重复的赞不再落一次", 0, second.likes)
        val post = a.db.momentDao().getById(postId)!!
        assertEquals("赞只记了一条", listOf("7"), MomentMath.likedByIds(post.likedBy))
    }

    @Test
    fun `主动链的上限更收_多写的动作一律不落`() = runBlocking {
        val a = app()
        val one = a.db.momentDao().upsert(MomentEntry(personaId = 0L, text = "第一条"))
        val two = a.db.momentDao().upsert(MomentEntry(personaId = 0L, text = "第二条"))
        val three = a.db.momentDao().upsert(MomentEntry(personaId = 0L, text = "第三条"))

        val applied = MomentActions.apply(
            a.db,
            personaId = 7L,
            raw = "[动态#%d:回第一条]\n[动态#%d:回第二条]\n[点赞:%d]\n[点赞:%d]\n[点赞:%d]\n[动态:我也发一条]"
                .format(one, two, one, two, three),
            limits = MomentActions.Limits.PROACTIVE,
        )

        // 用户拍板"同主动关心的档位"：每次最多回 1 条 + 赞 1 条（发帖 1 条）
        assertEquals("回帖只落 1 条", 1, applied.replies)
        assertEquals("点赞只落 1 条", 1, applied.likes)
        assertEquals("发帖只落 1 条", 1, applied.posts)

        val all = a.db.momentDao().observeAll().first()
        assertEquals("库里也只有 1 条回复（parentId != 0）", 1, all.count { it.parentId != 0L })
        assertEquals("库里只有 1 条新帖（parentId == 0 且作者是角色）", 1, all.count { it.parentId == 0L && it.personaId == 7L })
    }

    @Test
    fun `没有标记时什么都不做`() = runBlocking {
        val a = app()
        val applied = MomentActions.apply(a.db, personaId = 7L, raw = "今天天气不错，你吃饭了吗？")
        assertEquals(0, applied.total)
        assertFalse(a.db.momentDao().observeAll().first().isNotEmpty())
    }
}
