package com.menghuan.app

import com.menghuan.app.data.ConversationExport
import com.menghuan.app.data.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** 会话导出 Markdown（纯 JVM）：空会话返回 null；正文含角色名/时间戳/逐行引用 */
class ConversationExportTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `空会话返回null`() {
        assertNull(ConversationExport.buildMarkdown("阿布", "人生导师", emptyList(), zone))
    }

    @Test
    fun `导出含角色与时间戳且逐行引用`() {
        // 2026-09-30 08:05 Asia/Shanghai
        val ts = java.time.LocalDateTime.of(2026, 9, 30, 8, 5)
            .atZone(zone).toInstant().toEpochMilli()
        val markdown = ConversationExport.buildMarkdown(
            personaName = "阿布",
            personaTitle = "人生导师",
            messages = listOf(
                ChatMessage(id = 1, conversationId = 1, isFromUser = true, content = "第一行\n第二行", timestamp = ts),
                ChatMessage(id = 2, conversationId = 1, isFromUser = false, content = "收到", timestamp = ts),
            ),
            zone = zone,
        )!!
        assertTrue(markdown.startsWith("# 与 阿布（人生导师）的对话记录"))
        assertTrue(markdown.contains("**我** · 9月30日 08:05"))
        assertTrue(markdown.contains("**阿布** · 9月30日 08:05"))
        // 多行消息逐行加引用前缀，行数不丢
        assertEquals(2, markdown.lines().count { it == "> 第一行" || it == "> 第二行" })
        assertTrue(markdown.contains("> 收到"))
        assertTrue(markdown.endsWith("由「梦欢」App 导出\n"))
    }
}
