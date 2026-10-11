package com.menghuan.app

import androidx.test.core.app.ApplicationProvider
import com.menghuan.app.data.PromptBlocks
import com.menghuan.app.data.model.ChatMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 三条「模型来写一段话」链路的共享取材块（v0.94）。
 *
 * 盯两件事：形状（用户/你 的行首 + 截断）与**不串人设**——
 * 主动开口时把别的角色的聊天记录带进来，是最伤代入感的分叉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = MenghuanApp::class, sdk = [34])
class PromptBlocksTest {

    private fun app() = ApplicationProvider.getApplicationContext<MenghuanApp>()

    @Test
    fun `最近聊天片段只带这个角色自己的会话`() = runBlocking {
        val a = app()
        a.db.messageDao().insert(ChatMessage(conversationId = 7L, isFromUser = true, content = "今天去爬山了"))
        a.db.messageDao().insert(ChatMessage(conversationId = 7L, isFromUser = false, content = "山顶风大吗"))
        a.db.messageDao().insert(ChatMessage(conversationId = 9L, isFromUser = true, content = "给另一个角色的话"))

        val block = PromptBlocks.recentChatBlock(a.db, 7L)

        assertTrue("标题在", block.contains("【最近的聊天片段】"))
        assertTrue("用户发言在", block.contains("用户：今天去爬山了"))
        assertTrue("TA 的发言标「你：」", block.contains("你：山顶风大吗"))
        assertFalse("别的角色的消息绝不能漏进来", block.contains("给另一个角色的话"))
    }

    @Test
    fun `空会话给暂无兜底`() = runBlocking {
        val a = app()
        assertTrue(PromptBlocks.recentChatBlock(a.db, 7L).contains("暂无"))
    }

    @Test
    fun `超长内容按行截断`() = runBlocking {
        val a = app()
        a.db.messageDao().insert(ChatMessage(conversationId = 7L, isFromUser = true, content = "长".repeat(200)))
        val block = PromptBlocks.recentChatBlock(a.db, 7L)
        assertFalse("整段 200 字不该原样塞进去", block.contains("长".repeat(200)))
        assertTrue("截断后的 60 字要在", block.contains("长".repeat(60)))
    }
}
