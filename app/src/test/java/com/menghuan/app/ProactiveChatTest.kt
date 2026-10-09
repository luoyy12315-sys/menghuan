package com.menghuan.app

import androidx.test.core.app.ApplicationProvider
import com.menghuan.app.data.MomentMath
import com.menghuan.app.data.ProactiveChat
import com.menghuan.app.data.model.MomentEntry
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
 * 这段挑选规则是那条需求的核心：只看**用户自己发的**、
 * **这个角色还没回过也没赞过的** —— 真人不会对同一条动态反复评论。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = MenghuanApp::class, sdk = [34])
class ProactiveChatTest {

    private fun app() = ApplicationProvider.getApplicationContext<MenghuanApp>()

    @Test
    fun `只列用户发的_已回过或已赞过的不再给它看`() = runBlocking {
        val a = app()
        val me = 0L
        val persona = 7L

        val fresh = a.db.momentDao().upsert(MomentEntry(personaId = me, text = "刚发的一条：今天去爬山了"))
        val replied = a.db.momentDao().upsert(MomentEntry(personaId = me, text = "这条它已经回过了"))
        val liked = a.db.momentDao().upsert(MomentEntry(personaId = me, text = "这条它已经赞过了"))
        // 别的角色发的帖不在本批范围内（用户要的是"看我的动态"）
        a.db.momentDao().upsert(MomentEntry(personaId = 9L, text = "另一个角色发的帖"))

        a.db.momentDao().upsert(MomentEntry(personaId = persona, parentId = replied, text = "回过了"))
        a.db.momentDao().setLikedBy(liked, MomentMath.toggleLike("", persona.toString()))

        val block = ProactiveChat.userMomentsBlock(a.db, persona)

        assertTrue("新的那条要在", block.contains("今天去爬山了"))
        assertFalse("回过的不该再摆到它面前", block.contains("这条它已经回过了"))
        assertFalse("赞过的不该再摆到它面前", block.contains("这条它已经赞过了"))
        assertFalse("别的角色发的帖不在本批范围", block.contains("另一个角色发的帖"))
        assertTrue("要带编号（模型才能回帖/点赞）", block.contains("· $fresh "))
    }

    @Test
    fun `一条都没有时返回空串_不塞空标题`() = runBlocking {
        val a = app()
        assertEquals("", ProactiveChat.userMomentsBlock(a.db, 7L))
    }

    @Test
    fun `最多只给五条_最新的在前`() = runBlocking {
        val a = app()
        (1..7).forEach { i ->
            a.db.momentDao().upsert(MomentEntry(personaId = 0L, text = "第 $i 条", createdAt = 1_000L + i))
        }
        val block = ProactiveChat.userMomentsBlock(a.db, 7L)
        assertTrue("最新那条要在", block.contains("第 7 条"))
        assertFalse("太旧的不该挤进来", block.contains("第 1 条"))
        val lines = block.lines().filter { it.startsWith("· ") }
        assertEquals("最多 5 条", 5, lines.size)
    }
}
