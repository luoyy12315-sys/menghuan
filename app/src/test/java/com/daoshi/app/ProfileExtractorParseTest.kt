package com.daoshi.app

import com.daoshi.app.data.ProfileExtractor
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
        assertEquals(2, result.taskSuggestions.size)
        assertEquals("23:00 前放下手机", result.taskSuggestions[0].title)
        assertEquals("卧室不带手机", result.taskSuggestions[0].note)
        assertTrue(result.taskSuggestions[0].daily)
        // daily 缺省 = false
        assertEquals("周末复盘一周支出", result.taskSuggestions[1].title)
        assertTrue(!result.taskSuggestions[1].daily)
    }

    @Test
    fun `taskSuggestions 畸形条目跳过且最多保留两条`() {
        val raw = """
            {"facts":[],"insights":[],"taskSuggestions":[
              {},{"note":"缺标题"},{"title":"有效任务"},{"这":1},
              {"title":"甲"},{"title":"乙"},{"title":"丙"}]}
        """.trimIndent()
        val result = ProfileExtractor.parse(raw)
        assertEquals(listOf("有效任务", "甲"), result.taskSuggestions.map { it.title })
    }

    @Test
    fun `filterSuggestions 跳过同名任务与当日已建议并按标题去重`() {
        val incoming = listOf(
            com.daoshi.app.data.TaskSuggestion("已存在的任务"),
            com.daoshi.app.data.TaskSuggestion("今天建议过了"),
            com.daoshi.app.data.TaskSuggestion("新建议"),
            com.daoshi.app.data.TaskSuggestion("新建议"),
        )
        val fresh = ProfileExtractor.filterSuggestions(
            incoming,
            existingTaskTitles = setOf("已存在的任务"),
            suggestedToday = setOf("今天建议过了"),
        )
        assertEquals(listOf("新建议"), fresh.map { it.title })
    }
}
