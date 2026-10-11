package com.menghuan.app

import com.menghuan.app.data.ProfileExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提炼解析器单测（纯 JVM，无 Android 依赖）：宽容解析各种真实模型输出形态。
 *
 * ⚠️ **v0.88.0 职责收窄**：`ProfileExtractor.parse` 现在**只出 facts**。
 * insights 挪去 [com.menghuan.app.data.InsightMath]（从结构化数据归纳，不再解析对话转录），
 * taskSuggestions 挪去 [com.menghuan.app.data.TaskSuggestionMarker]（标记驱动）。
 * 所以本文件里所有 insights/taskSuggestions 相关断言整组删除，对应覆盖在各自的新测试里。
 */
class ProfileExtractorParseTest {

    @Test
    fun `正常 JSON 解析出 facts`() {
        val raw = """
            {"facts":[{"key":"作息","value":"通常 00:30–07:45"},{"key":"职业","value":"自由职业"}]}
        """.trimIndent()
        val result = ProfileExtractor.parse(raw)
        assertEquals(listOf("作息" to "通常 00:30–07:45", "职业" to "自由职业"), result.facts)
    }

    @Test
    fun `markdown 代码栅栏包裹也能解析`() {
        val raw = """
            好的，以下是提炼结果：
            ```json
            {"facts":[{"key":"当前目标","value":"每天 23:00 前睡觉"}]}
            ```
        """.trimIndent()
        val result = ProfileExtractor.parse(raw)
        assertEquals(listOf("当前目标" to "每天 23:00 前睡觉"), result.facts)
    }

    @Test
    fun `空数组返回空结果`() {
        val result = ProfileExtractor.parse("""{"facts":[]}""")
        assertTrue(result.facts.isEmpty())
    }

    @Test
    fun `完全非 JSON 的输出不抛异常返回空`() {
        val result = ProfileExtractor.parse("抱歉，我无法从这段对话中提炼信息。")
        assertTrue(result.facts.isEmpty())
    }

    @Test
    fun `畸形条目被跳过而非整体失败`() {
        val raw = """{"facts":[{"key":"工作","value":"带内容"},{"key":"","value":"缺 key"},{}]}"""
        val result = ProfileExtractor.parse(raw)
        assertEquals(listOf("工作" to "带内容"), result.facts)
    }

    @Test
    fun `facts 超过软上限 6 条被裁到 6 条`() {
        // 防灾：模型失控吐十几条时，代码层砍到 6 条保护 UI 与数据库
        val items = (1..12).joinToString(",") { """{"key":"条目$it","value":"内容$it"}""" }
        val result = ProfileExtractor.parse("""{"facts":[$items]}""")
        assertEquals(6, result.facts.size)
        assertEquals("条目1", result.facts.first().first)
        assertEquals("条目6", result.facts.last().first)
    }

    @Test
    fun `模型顺带吐的 insights 与 taskSuggestions 被忽略`() {
        // 收窄后的容忍度：框架已不再要求这两个字段，但真实模型可能按旧习惯照吐 ——
        // 解析器只取 facts，多余字段直接无视（不能因为多出来的键就整体失败）
        val raw = """
            {"facts":[{"key":"职业","value":"设计师"}],
             "insights":[{"title":"甲","detail":"说明"}],
             "taskSuggestions":[{"title":"任务"}]}
        """.trimIndent()
        val result = ProfileExtractor.parse(raw)
        assertEquals(listOf("职业" to "设计师"), result.facts)
    }
}
