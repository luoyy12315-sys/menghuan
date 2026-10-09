package com.menghuan.app

import com.menghuan.app.data.SummaryEngine
import com.menghuan.app.data.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滚动摘要的纯函数单测（v0.82.0 P1-1，纯 JVM）。
 *
 * 钉的是两条边界，错了都不报错、只是"TA 好像忘了"：
 * ① **触发判据**：掉出历史窗口的积压没攒够 3 万字就发请求 = 白烧钱；
 *    攒够了不发 = 旧对话继续蒸发。
 * ② **分段选取**：单次压缩的输入必须有界（封顶），且"一条就超长"的消息也得能过去（否则死锁）。
 */
class SummaryEngineTest {

    private fun msg(id: Long, chars: Int) = ChatMessage(
        id = id, conversationId = 1, isFromUser = id % 2L == 0L,
        content = "字".repeat(chars), timestamp = id,
    )

    @Test
    fun `积压掉出窗口不到3万字不触发_攒够才触发`() {
        val budget = 200_000
        // 窗口内：压根不需要摘要
        assertFalse(SummaryEngine.needsSummary(150_000, budget))
        // 掉出窗口但不到阈值：再攒攒（一次压缩 3 万字以下的尾巴不划算）
        assertFalse(SummaryEngine.needsSummary(200_000 + 30_000, budget))
        // 掉出窗口超过 3 万字：该压缩了
        assertTrue(SummaryEngine.needsSummary(200_000 + 30_001, budget))
        assertTrue(SummaryEngine.needsSummary(500_000, budget))
        // 边界：budget 为 0（极端配置）时积压即全部，超阈值即触发
        assertTrue(SummaryEngine.needsSummary(30_001, 0))
        assertFalse(SummaryEngine.needsSummary(30_000, 0))
    }

    @Test
    fun `分段从最旧取起_累计够目标就停`() {
        val backlog = listOf(msg(1, 10_000), msg(2, 10_000), msg(3, 10_000), msg(4, 10_000))
        val seg = SummaryEngine.pickSegment(backlog, 25_000)
        assertEquals("最旧的三条（累计 3 万字 ≥ 目标 2.5 万）", listOf(1L, 2L, 3L), seg.map { it.id })
    }

    @Test
    fun `一条就超长的消息也得能过去_否则水位线永远推不动`() {
        val backlog = listOf(msg(1, 50_000), msg(2, 100))
        val seg = SummaryEngine.pickSegment(backlog, 1_000)
        assertEquals("至少取一条", listOf(1L), seg.map { it.id })
    }

    @Test
    fun `目标为零或积压为空都取不出东西`() {
        assertTrue(SummaryEngine.pickSegment(emptyList(), 1_000).isEmpty())
        assertTrue(SummaryEngine.pickSegment(listOf(msg(1, 100)), 0).isEmpty())
    }

    @Test
    fun `目标超过全部积压就整段取走`() {
        val backlog = listOf(msg(1, 100), msg(2, 100))
        assertEquals(2, SummaryEngine.pickSegment(backlog, 999_999).size)
    }
}
