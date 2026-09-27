package com.daoshi.app.ui.chat

/**
 * 会话草稿（进程内存级）：切走/退出会话再回来，未发送的输入还在。
 * 刻意不落盘——App 被杀后草稿消失是 IM 的常见取舍，避免"过期草稿"误导。
 */
object ChatDrafts {
    private val drafts = mutableMapOf<Long, String>()

    fun get(personaId: Long): String = drafts[personaId] ?: ""

    fun set(personaId: Long, text: String) {
        if (text.isEmpty()) drafts.remove(personaId) else drafts[personaId] = text
    }

    fun clear(personaId: Long) {
        drafts.remove(personaId)
    }
}
