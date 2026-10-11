package com.menghuan.app

import com.menghuan.app.data.model.ChatMessage
import com.menghuan.app.ui.chat.HISTORY_CHAR_BUDGET
import com.menghuan.app.ui.chat.selectHistoryByCharBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上下文窗口字数预算的选取（`selectHistoryByCharBudget` 纯函数口径）。
 *
 * v0.39.0 原为 (0, 200000) 计费档写死 20 万；v0.86.0 改为**每个角色的设置项**
 * （`SettingsRepository.historyCharBudget`），[HISTORY_CHAR_BUDGET] 降为默认值 1.2 万字。
 * 纯函数本身没变，这里只钉住默认值与选取行为。
 */
class HistoryBudgetTest {

    private fun msg(content: String) = ChatMessage(conversationId = 1L, isFromUser = true, content = content)

    @Test
    fun `预算内全部保留且顺序不变`() {
        val all = listOf(msg("一"), msg("二"), msg("三"))
        assertEquals(all, selectHistoryByCharBudget(all, 100))
    }

    @Test
    fun `超预算从最旧的开始丢`() {
        val all = listOf(msg("很长很长很长的第一条"), msg("第二条"), msg("第三条"))
        val picked = selectHistoryByCharBudget(all, 7)
        // 从新到旧收：第三条(3) → 第二条(3) → 第一条(9) 放不下
        assertEquals(listOf("第二条", "第三条"), picked.map { it.content })
    }

    @Test
    fun `最新一条永远保留`() {
        val all = listOf(msg("老消息"), msg("超长超长超长超长超长的最新消息"))
        val picked = selectHistoryByCharBudget(all, 5)
        assertEquals(listOf("超长超长超长超长超长的最新消息"), picked.map { it.content })
    }

    @Test
    fun `预算为零只留最新`() {
        val all = listOf(msg("一"), msg("二"))
        assertEquals(listOf("二"), selectHistoryByCharBudget(all, 0).map { it.content })
        assertEquals(listOf("二"), selectHistoryByCharBudget(all, -1).map { it.content })
    }

    @Test
    fun `默认窗口是 1_2 万字`() {
        assertEquals(12_000, HISTORY_CHAR_BUDGET)
    }
}
