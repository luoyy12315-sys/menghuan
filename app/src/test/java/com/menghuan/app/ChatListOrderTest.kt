package com.menghuan.app

import com.menghuan.app.data.model.ChatMessage
import com.menghuan.app.data.model.Persona
import com.menghuan.app.ui.chat.orderConversations
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 会话列表排序（v0.77.0，纯 JVM）。
 *
 * 需求原文：「聊天列表按……最新的消息放在最上面」。规则：
 * 置顶优先 → 有消息的在无消息的前面 → 最后消息时间倒序 → 无消息内部创建时间倒序。
 */
class ChatListOrderTest {

    private fun persona(id: Long, pinned: Boolean = false, createdAt: Long = id) = Persona(
        id = id,
        name = "P$id",
        title = "",
        avatarEmoji = "🙂",
        systemPrompt = "",
        pinned = pinned,
        createdAt = createdAt,
    )

    private fun lastMsg(conversationId: Long, timestamp: Long) = ChatMessage(
        conversationId = conversationId,
        isFromUser = false,
        content = "hi",
        timestamp = timestamp,
    )

    @Test
    fun `按最后消息时间倒序_最近聊的在最上`() {
        val personas = listOf(persona(1), persona(2), persona(3))
        val last = mapOf(
            1L to lastMsg(1, 100),
            2L to lastMsg(2, 300),
            3L to lastMsg(3, 200),
        )
        val ordered = orderConversations(personas, last).map { it.id }
        assertEquals(listOf(2L, 3L, 1L), ordered)
    }

    @Test
    fun `置顶永远在最前_即使很旧没聊`() {
        val personas = listOf(persona(1), persona(2, pinned = true), persona(3))
        val last = mapOf(
            1L to lastMsg(1, 999),
            2L to lastMsg(2, 1),
            3L to lastMsg(3, 500),
        )
        val ordered = orderConversations(personas, last).map { it.id }
        assertEquals(listOf(2L, 1L, 3L), ordered)
    }

    @Test
    fun `没有消息的沉到最底`() {
        val personas = listOf(persona(1), persona(2), persona(3))
        // 只有 1 号有消息
        val last = mapOf(1L to lastMsg(1, 500))
        val ordered = orderConversations(personas, last).map { it.id }
        assertEquals("有消息的 1 在最前，无消息的按创建时间倒序（3 后 2）", listOf(1L, 3L, 2L), ordered)
    }

    @Test
    fun `置顶但无消息也只在置顶组里沉底_不越到有消息的普通会话之前`() {
        // 置顶组优先；置顶组内部也遵守"有消息在前"
        val personas = listOf(persona(1, pinned = true), persona(2, pinned = true), persona(3))
        val last = mapOf(
            1L to lastMsg(1, 1),   // 置顶但很旧
            3L to lastMsg(3, 999), // 非置顶但最新
            // 2 号置顶且无消息
        )
        val ordered = orderConversations(personas, last).map { it.id }
        assertEquals("置顶组（有消息的 1 在无消息的 2 前）整体压在非置顶的 3 之前", listOf(1L, 2L, 3L), ordered)
    }

    @Test
    fun `全部无消息按创建时间倒序`() {
        val personas = listOf(
            persona(1, createdAt = 100),
            persona(2, createdAt = 300),
            persona(3, createdAt = 200),
        )
        val ordered = orderConversations(personas, emptyMap()).map { it.id }
        assertEquals(listOf(2L, 3L, 1L), ordered)
    }
}
