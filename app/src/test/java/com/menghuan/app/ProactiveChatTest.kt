package com.menghuan.app

import androidx.test.core.app.ApplicationProvider
import com.menghuan.app.data.MomentMath
import com.menghuan.app.data.ProactiveChat
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
 * v0.79.0：角色**主动来找用户**时，App 给不给他看动态、看哪几条。
 *
 * 用户原话："不跟 AI 聊的时候它也会看我动态，像真人一样"。
 * v0.95 起名单**含其他角色发的帖**（用户点名「人设之间也能互相回复动态、跟真人一样」）；
 * 不变的两条：**自己发的不列**（不诱它自问自答）、
 * **这个角色还没回过也没赞过的** —— 真人不会对同一条动态反复评论，
 * 这条也保证两个角色不会在同一帖下互聊个没完。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = MenghuanApp::class, sdk = [34])
class ProactiveChatTest {

    private fun app() = ApplicationProvider.getApplicationContext<MenghuanApp>()

    @Test
    fun `用户与其他角色的帖都列_自己的不列_已回过或已赞过的不再给它看`() = runBlocking {
        val a = app()
        val me = 0L
        val persona = 7L

        val fresh = a.db.momentDao().upsert(MomentEntry(personaId = me, text = "刚发的一条：今天去爬山了"))
        val replied = a.db.momentDao().upsert(MomentEntry(personaId = me, text = "这条它已经回过了"))
        val liked = a.db.momentDao().upsert(MomentEntry(personaId = me, text = "这条它已经赞过了"))
        // v0.95：别的角色发的帖**也在本批范围内**（人设之间互相看动态、回帖、点赞）
        a.db.momentDao().upsert(MomentEntry(personaId = 9L, text = "另一个角色发的帖"))
        // 它自己发的帖不列（不诱它自问自答）
        a.db.momentDao().upsert(MomentEntry(personaId = persona, text = "它自己发的帖"))

        a.db.momentDao().upsert(MomentEntry(personaId = persona, parentId = replied, text = "回过了"))
        a.db.momentDao().setLikedBy(liked, MomentMath.toggleLike("", persona.toString()))

        val block = ProactiveChat.momentsFeedBlock(a.db, persona)

        assertTrue("新的那条要在", block.contains("今天去爬山了"))
        assertFalse("回过的不该再摆到它面前", block.contains("这条它已经回过了"))
        assertFalse("赞过的不该再摆到它面前", block.contains("这条它已经赞过了"))
        assertTrue("v0.95：别的角色发的帖也要在", block.contains("另一个角色发的帖"))
        assertFalse("自己发的帖不该列（自问自答风险）", block.contains("它自己发的帖"))
        assertTrue("要带编号（模型才能回帖/点赞）", block.contains("· $fresh "))
    }

    @Test
    fun `一条都没有时返回空串_不塞空标题`() = runBlocking {
        val a = app()
        assertEquals("", ProactiveChat.momentsFeedBlock(a.db, 7L))
    }

    @Test
    fun `最多只给五条_最新的在前`() = runBlocking {
        val a = app()
        (1..7).forEach { i ->
            a.db.momentDao().upsert(MomentEntry(personaId = 0L, text = "第 $i 条", createdAt = 1_000L + i))
        }
        val block = ProactiveChat.momentsFeedBlock(a.db, 7L)
        assertTrue("最新那条要在", block.contains("第 7 条"))
        assertFalse("太旧的不该挤进来", block.contains("第 1 条"))
        val lines = block.lines().filter { it.startsWith("· ") }
        assertEquals("最多 5 条", 5, lines.size)
    }

    // ===== v0.94：三开关下「这一枪干什么」的决策 =====

    @Test
    fun `决策_电话优先于消息`() {
        val action = ProactiveChat.decideAction(
            messageOn = true, messageRemaining = true,
            momentsOn = true,
            callOn = true, callsToday = 0, callLimit = 1, quietHours = false,
        )
        assertEquals(ProactiveChat.Action.CALL, action)
    }

    @Test
    fun `决策_静默时段不打电话自动落回消息`() {
        val action = ProactiveChat.decideAction(
            messageOn = true, messageRemaining = true,
            momentsOn = true,
            callOn = true, callsToday = 0, callLimit = 1, quietHours = true,
        )
        assertEquals(ProactiveChat.Action.MESSAGE, action)
    }

    @Test
    fun `决策_当日电话次数打满落回消息`() {
        val action = ProactiveChat.decideAction(
            messageOn = true, messageRemaining = true,
            momentsOn = true,
            callOn = true, callsToday = 2, callLimit = 2, quietHours = false,
        )
        assertEquals(ProactiveChat.Action.MESSAGE, action)
    }

    @Test
    fun `决策_关信息开动态就是只发动态`() {
        val action = ProactiveChat.decideAction(
            messageOn = false, messageRemaining = true,
            momentsOn = true,
            callOn = false, callsToday = 0, callLimit = 1, quietHours = false,
        )
        assertEquals(ProactiveChat.Action.MOMENTS_ONLY, action)
    }

    @Test
    fun `决策_配额发完就是今天到此为止`() {
        // 「每天最多几条」= 每天主动行为次数：动态动作也占配额，发完不再动（防刷屏）
        val action = ProactiveChat.decideAction(
            messageOn = true, messageRemaining = false,
            momentsOn = true,
            callOn = false, callsToday = 0, callLimit = 1, quietHours = false,
        )
        assertEquals(ProactiveChat.Action.NONE, action)
    }

    @Test
    fun `决策_全关就是什么都不做`() {
        val action = ProactiveChat.decideAction(
            messageOn = false, messageRemaining = true,
            momentsOn = false,
            callOn = false, callsToday = 0, callLimit = 1, quietHours = false,
        )
        assertEquals(ProactiveChat.Action.NONE, action)
    }

    @Test
    fun `电话开关_老装机的主动电话规则能推断成新开关`() = runBlocking {
        val a = app()
        val repo = a.settingsRepository
        // 新键没写过 → 看老的 aiCallRules.enabled
        assertFalse(repo.proactiveCallEnabled(7L).first())
        repo.upsertAiCallRule(com.menghuan.app.data.AiCallRule(personaId = 7L, enabled = true))
        assertTrue("老开关开着要推断成新开关开", repo.proactiveCallEnabled(7L).first())
        // 新键写过就以新键为准（显式关掉覆盖老的开）
        repo.setProactiveCallEnabled(7L, false)
        assertFalse(repo.proactiveCallEnabled(7L).first())
    }

    @Test
    fun `电话当日计数_跨天自动归零`() = runBlocking {
        val a = app()
        val repo = a.settingsRepository
        assertEquals(0, repo.proactiveCallsTodayOf(7L, 100L))
        repo.markProactiveCall(7L, 100L)
        repo.markProactiveCall(7L, 100L)
        assertEquals(2, repo.proactiveCallsTodayOf(7L, 100L))
        assertEquals("第二天重新计数", 0, repo.proactiveCallsTodayOf(7L, 101L))
    }
}
