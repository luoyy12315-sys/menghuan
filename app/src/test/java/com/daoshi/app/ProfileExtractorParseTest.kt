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
}
