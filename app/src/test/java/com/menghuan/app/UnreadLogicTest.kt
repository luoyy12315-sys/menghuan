package com.menghuan.app

import com.menghuan.app.data.UnreadLogic
import com.menghuan.app.data.model.ChatMessage
import com.menghuan.app.data.model.Persona
import org.junit.Assert.assertEquals
import org.junit.Test

/** 底栏「消息」未读点纯逻辑（口径与会话列表行 showDot 一致） */
class UnreadLogicTest {

    private fun persona(id: Long, hidden: Boolean = false) = Persona(
        id = id, name = "p$id", title = "t", avatarEmoji = "🙂",
        systemPrompt = "", hidden = hidden,
    )

    private fun msg(conversationId: Long, fromUser: Boolean, ts: Long) = ChatMessage(
        id = conversationId * 100 + ts, conversationId = conversationId,
        isFromUser = fromUser, content = "hi", timestamp = ts,
    )

    @Test
    fun `导师新消息且晚于已读时刻才计未读`() {
        val personas = listOf(persona(1), persona(2), persona(3))
        val messages = listOf(
            msg(1, fromUser = false, ts = 100), // 未读
            msg(2, fromUser = true, ts = 100),  // 用户自己发的：不算
            msg(3, fromUser = false, ts = 50),  // 早于已读时刻：不算
        )
        val marks = mapOf<Long, Long>(3L to 60L)
        assertEquals(1, UnreadLogic.count(personas, messages, marks))
    }

    @Test
    fun `已读时刻之后或无标记时按未读计`() {
        val personas = listOf(persona(1), persona(2))
        val messages = listOf(
            msg(1, fromUser = false, ts = 100),
            msg(2, fromUser = false, ts = 100),
        )
        // 无标记 = 从未打开过：导师消息一律未读
        assertEquals(2, UnreadLogic.count(personas, messages, emptyMap()))
        // 已读时刻晚于消息：清除
        assertEquals(0, UnreadLogic.count(personas, messages, mapOf(1L to 200L, 2L to 200L)))
    }

    @Test
    fun `隐藏且无消息的会话不计未读`() {
        // 微信式删除：hidden 且无消息 → 列表不可见，也不该亮未读点
        val personas = listOf(persona(1, hidden = true), persona(2, hidden = true))
        assertEquals(0, UnreadLogic.count(personas, emptyList(), emptyMap()))
        // hidden 但有新消息：重新可见 → 计未读
        assertEquals(1, UnreadLogic.count(personas, listOf(msg(1, fromUser = false, ts = 100)), emptyMap()))
    }

    @Test
    fun `每个会话只取最后一条消息判定`() {
        val personas = listOf(persona(1))
        val messages = listOf(
            msg(1, fromUser = false, ts = 100), // 旧的导师消息
            msg(1, fromUser = true, ts = 200),  // 最后一条是用户：视为已读
        )
        assertEquals(0, UnreadLogic.count(personas, messages, emptyMap()))
    }
}
