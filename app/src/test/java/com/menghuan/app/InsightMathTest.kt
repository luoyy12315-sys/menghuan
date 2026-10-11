package com.menghuan.app

import com.menghuan.app.data.InsightContext
import com.menghuan.app.data.InsightMath
import com.menghuan.app.data.model.InsightPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * AI 洞察的解析与取材单测（v0.88.0，纯 JVM）。
 *
 * 最重要的一条是 **[允许 0 条]** —— 用户点名要求的能力，
 * 也是这次重构和旧链路最大的行为差别（旧链路是提炼副产品，模型为了交差会硬编）。
 */
class InsightMathTest {

    @Test
    fun `正常 JSON 解析出多条洞察`() {
        val raw = """
            {"insights":[
              {"title":"晚间效率更高","detail":"连续几天下午的任务都堆到晚上才做"},
              {"title":"本周支出偏高","detail":"外卖比上周多一倍"}]}
        """.trimIndent()
        val result = InsightMath.parse(raw)
        assertEquals(2, result.size)
        assertEquals("晚间效率更高", result[0].first)
        assertEquals("连续几天下午的任务都堆到晚上才做", result[0].second)
        assertEquals("本周支出偏高", result[1].first)
    }

    @Test
    fun `空数组返回空列表而不是失败`() {
        // 这是「允许 0 条」的核心：空数组是正常结果，解析器必须原样返回空
        val result = InsightMath.parse("""{"insights":[]}""")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `代码栅栏包裹也能解析`() {
        val raw = """
            好的，以下是归纳结果：
            ```json
            {"insights":[{"title":"作息在变规律","detail":"最近几天都 23 点前睡"}]}
            ```
        """.trimIndent()
        val result = InsightMath.parse(raw)
        assertEquals(1, result.size)
        assertEquals("作息在变规律", result[0].first)
    }

    @Test
    fun `完全非 JSON 返回空列表不抛异常`() {
        assertTrue(InsightMath.parse("抱歉，这期数据没什么可说的。").isEmpty())
    }

    @Test
    fun `畸形条目跳过而保留有效条目`() {
        val raw = """{"insights":[{"title":"","detail":"缺标题"},{"detail":"缺标题"},{"title":"有效洞察"}]}"""
        val result = InsightMath.parse(raw)
        assertEquals(listOf("有效洞察"), result.map { it.first })
    }

    @Test
    fun `超过 3 条被裁到 3 条`() {
        val items = (1..8).joinToString(",") { """{"title":"洞察$it","detail":"说明$it"}""" }
        val result = InsightMath.parse("""{"insights":[$items]}""")
        assertEquals(3, result.size)
        assertEquals("洞察1", result.first().first)
        assertEquals("洞察3", result.last().first)
    }

    @Test
    fun `标题与说明截断到上限`() {
        val longTitle = "标".repeat(80)
        val longDetail = "说".repeat(400)
        val result = InsightMath.parse("""{"insights":[{"title":"$longTitle","detail":"$longDetail"}]}""")
        assertEquals(40, result[0].first.length)
        assertEquals(200, result[0].second.length)
    }

    // ===== prompt 取材 =====

    @Test
    fun `prompt 带上三类数据与周期标签`() {
        val ctx = InsightContext(
            day = LocalDate.of(2026, 10, 4),
            period = InsightPeriod.WEEKLY,
            facts = listOf("作息" to "23:00 前入睡"),
            doneTasks = listOf("每日阅读"),
            pendingTasks = listOf("写周报"),
            overdueCount = 2,
            expenseCents = 12_00,
            incomeCents = 300_00,
            expenseCount = 3,
            topExpenseCents = 50_00,
            topExpenseNote = "和朋友吃饭",
        )
        val prompt = InsightMath.buildPrompt(ctx)
        assertTrue("应标出周期", prompt.contains("本周"))
        assertTrue("应带记忆", prompt.contains("23:00 前入睡"))
        assertTrue("应带已完成任务", prompt.contains("每日阅读"))
        assertTrue("应带未完成任务", prompt.contains("写周报"))
        assertTrue("应带逾期计数", prompt.contains("2"))
        assertTrue("应带支出", prompt.contains("12.00"))
        assertTrue("应带最大一笔", prompt.contains("50.00"))
        assertTrue("应带最大一笔备注", prompt.contains("和朋友吃饭"))
    }

    @Test
    fun `无支出时不提钱`() {
        val ctx = InsightContext(
            day = LocalDate.of(2026, 10, 4),
            period = InsightPeriod.DAILY,
            facts = listOf("职业" to "设计师"),
            doneTasks = emptyList(),
            pendingTasks = emptyList(),
            overdueCount = 0,
            expenseCents = 0,
            incomeCents = 0,
            expenseCount = 0,
            topExpenseCents = 0,
            topExpenseNote = "",
        )
        val prompt = InsightMath.buildPrompt(ctx)
        assertFalse(prompt.contains("[收支]"))
        assertFalse(prompt.contains("[最大一笔支出]"))
    }

    @Test
    fun `systemPrompt 拼接框架与策略`() {
        val withStrategy = InsightMath.systemPrompt("自定义洞察策略")
        assertTrue(withStrategy.contains(InsightMath.FRAMEWORK))
        assertTrue(withStrategy.contains("自定义洞察策略"))
        // 空策略只发框架
        assertEquals(InsightMath.FRAMEWORK, InsightMath.systemPrompt(""))
    }

    @Test
    fun `默认策略明说允许 0 条`() {
        // 「允许 0 条」是用户点名要的能力，写死在策略里；改掉这条会回归"每问必答"
        assertTrue(InsightMath.DEFAULT_STRATEGY.contains("0 条"))
        assertTrue(InsightMath.DEFAULT_STRATEGY.contains("宁缺毋滥"))
    }
}
