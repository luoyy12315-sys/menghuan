package com.menghuan.app.data

import com.menghuan.app.data.model.ChatMessage
import java.time.Instant
import java.time.ZoneId

/** 单会话导出（纯函数，JVM 单测）：消息 → Markdown 文本（系统分享用） */
object ConversationExport {

    /**
     * 会话记录 Markdown：按时间正序，AI 消息加粗名字，用户消息用「我」。
     * 内容逐条引用块排版，空会话返回 null（调用层提示）。
     */
    fun buildMarkdown(
        personaName: String,
        personaTitle: String,
        messages: List<ChatMessage>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String? {
        if (messages.isEmpty()) return null
        return buildString {
            append("# 与 $personaName（$personaTitle）的对话记录\n\n")
            messages.forEach { message ->
                val time = Instant.ofEpochMilli(message.timestamp).atZone(zone)
                val stamp = "${time.monthValue}月${time.dayOfMonth}日 ${time.hour.toString().padStart(2, '0')}:" +
                    time.minute.toString().padStart(2, '0')
                if (message.isFromUser) {
                    append("**我** · $stamp\n\n")
                } else {
                    append("**$personaName** · $stamp\n\n")
                }
                message.content.lines().forEach { line -> append("> $line\n") }
                append("\n")
            }
            append("---\n\n由「梦欢」App 导出\n")
        }
    }
}
