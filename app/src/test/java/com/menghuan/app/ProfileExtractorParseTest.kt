package com.menghuan.app

import com.menghuan.app.data.ProfileExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 提炼解析器单测（纯 JVM，无 Android 依赖）：宽容解析各种真实模型输出形态 */
class ProfileExtractorParseTest {

    @Test
    fun `正常 JSON 解析出 facts 与 insights`() {
        val raw = """
            {"facts":[{"key":"作息","value":"通常 00:30–07:45"},{"key":"职业","value":"自由职业"}],
             "insights":[{"title":"熬夜与短视频强相关","detail":"睡前刷短视频的日子平均晚睡 82 分钟"}]}
        """.trimIndent()
        val result = ProfileExtractor.parse(raw)
        assertEquals(listOf("作息" to "通常 00:30–07:45", "职业" to "自由职业"), result.facts)
        assertEquals(listOf("熬夜与短视频强相关" to "睡前刷短视频的日子平均晚睡 82 分钟"), result.insights)
    }

    @Test
    fun `markdown 代码栅栏包裹也能解析`() {
        val raw = """
            好的，以下是提炼结果：
            ```json
            {"facts":[{"key":"当前目标","value":"每天 23:00 前睡觉"}],"insights":[]}
            ```
        """.trimIndent()
        val result = ProfileExtractor.parse(raw)
        assertEquals(listOf("当前目标" to "每天 23:00 前睡觉"), result.facts)
        assertTrue(result.insights.isEmpty())
    }

    @Test
    fun `空数组返回空结果`() {
        val result = ProfileExtractor.parse("""{"facts":[],"insights":[]}""")
        assertTrue(result.facts.isEmpty())
        assertTrue(result.insights.isEmpty())
    }

    @Test
    fun `完全非 JSON 的输出不抛异常返回空`() {
        val result = ProfileExtractor.parse("抱歉，我无法从这段对话中提炼信息。")
        assertTrue(result.facts.isEmpty())
        assertTrue(result.insights.isEmpty())
    }

    @Test
    fun `畸形条目被跳过而非整体失败`() {
        val raw = """{"facts":[{"key":"工作","value":"带内容"},{"key":"","value":"缺 key"},{}],"insights":[]}"""
        val result = ProfileExtractor.parse(raw)
        assertEquals(listOf("工作" to "带内容"), result.facts)
    }

    @Test
    fun `taskSuggestions 解析出标题备注与 daily 标记`() {
        val raw = """
            {"facts":[],"insights":[],"taskSuggestions":[
              {"title":"23:00 前放下手机","note":"卧室不带手机","daily":true},
              {"title":"周末复盘一周支出"}]}
        """.trimIndent()
        val result = ProfileExtractor.parse(raw)
        // v0.36.0 数量松绑：AI 自主决定条数，软防灾上限 5；这里 2 条都保留
        assertEquals(2, result.taskSuggestions.size)
        assertEquals("23:00 前放下手机", result.taskSuggestions[0].title)
        assertEquals("卧室不带手机", result.taskSuggestions[0].note)
        assertTrue(result.taskSuggestions[0].daily)
        assertEquals("周末复盘一周支出", result.taskSuggestions[1].title)
        assertTrue(!result.taskSuggestions[1].daily)
    }

    @Test
    fun `taskSuggestions 单条 daily 缺省为 false`() {
        val result = ProfileExtractor.parse(
            """{"facts":[],"insights":[],"taskSuggestions":[{"title":"周末复盘一周支出"}]}""",
        )
        assertEquals("周末复盘一周支出", result.taskSuggestions[0].title)
        assertTrue(!result.taskSuggestions[0].daily)
    }

    @Test
    fun `insights 软上限内全部保留`() {
        val result = ProfileExtractor.parse(
            """{"facts":[],"insights":[
                {"title":"甲","detail":"说明甲"},{"title":"乙","detail":"说明乙"}]}""",
        )
        assertEquals(listOf("甲", "乙"), result.insights.map { it.first })
    }

    @Test
    fun `insights 超过软上限 10 条被裁到 10 条`() {
        // 防灾：模型失控吐 15 条洞察时，代码层砍到 10 条保护 UI 与数据库
        val items = (1..15).joinToString(",") { """{"title":"洞察$it","detail":"说明$it"}""" }
        val result = ProfileExtractor.parse("""{"facts":[],"insights":[$items]}""")
        assertEquals(10, result.insights.size)
        assertEquals("洞察1", result.insights.first().first)
        assertEquals("洞察10", result.insights.last().first)
    }

    @Test
    fun `taskSuggestions 畸形条目跳过、软上限内保留全部有效条目`() {
        val raw = """
            {"facts":[],"insights":[],"taskSuggestions":[
              {},{"note":"缺标题"},{"title":"有效任务"},{"这":1},
              {"title":"甲"},{"title":"乙"},{"title":"丙"}]}
        """.trimIndent()
        val result = ProfileExtractor.parse(raw)
        // 4 条有效 + 3 条畸形：畸形跳过，有效的全部保留（软上限 5 未触发）
        assertEquals(listOf("有效任务", "甲", "乙", "丙"), result.taskSuggestions.map { it.title })
    }

    @Test
    fun `taskSuggestions 超过软上限 5 条被裁到 5 条`() {
        val items = (1..8).joinToString(",") { """{"title":"任务$it"}""" }
        val result = ProfileExtractor.parse("""{"facts":[],"insights":[],"taskSuggestions":[$items]}""")
        assertEquals(5, result.taskSuggestions.size)
        assertEquals("任务1", result.taskSuggestions.first().title)
        assertEquals("任务5", result.taskSuggestions.last().title)
    }

    @Test
    fun `filterSuggestions 跳过同名任务与当日已建议并按标题去重`() {
        val incoming = listOf(
            com.menghuan.app.data.TaskSuggestion("已存在的任务"),
            com.menghuan.app.data.TaskSuggestion("今天建议过了"),
            com.menghuan.app.data.TaskSuggestion("新建议"),
            com.menghuan.app.data.TaskSuggestion("新建议"),
        )
        val fresh = ProfileExtractor.filterSuggestions(
            incoming,
            existingTaskTitles = setOf("已存在的任务"),
            suggestedToday = setOf("今天建议过了"),
        )
        assertEquals(listOf("新建议"), fresh.map { it.title })
    }
}
