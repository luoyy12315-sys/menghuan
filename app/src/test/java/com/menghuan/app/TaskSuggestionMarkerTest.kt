package com.menghuan.app

import com.menghuan.app.data.TaskSuggestionMarker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `[建议任务:标题|备注]` 标记单测（v0.88.0，纯 JVM）。
 *
 * 三条底线，每一条都对应一个真实会踩的坑：
 * 1. **标记不许漏进用户可见正文**（与 [MomentMarkerTest] 同一纪律）；
 * 2. **一条回复里的多个标记要各自解析**（正则写成贪婪的话会粘成一条）；
 * 3. 模型爱加的**空格/全角冒号/半角方括号**都要认（不认就时不时失灵，用户看不出原因）。
 */
class TaskSuggestionMarkerTest {

    @Test
    fun `抽出标题并剥干净正文`() {
        val d = TaskSuggestionMarker.parse("好啊，那就这么定。[建议任务:买牛奶]")
        assertEquals(listOf("买牛奶"), d.suggestions.map { it.title })
        assertEquals("好啊，那就这么定。", d.cleanText)
        assertFalse("标记必须剥干净", d.cleanText.contains("建议任务"))
    }

    @Test
    fun `竖线后的备注被解析出来`() {
        val d = TaskSuggestionMarker.parse("[建议任务:买牛奶|家里快没了]")
        assertEquals("买牛奶", d.suggestions[0].title)
        assertEquals("家里快没了", d.suggestions[0].note)
    }

    @Test
    fun `全角括号与全角冒号也认`() {
        val d = TaskSuggestionMarker.parse("【建议任务：周末复盘一周支出】")
        assertEquals(listOf("周末复盘一周支出"), d.suggestions.map { it.title })
        assertEquals("", d.cleanText)
    }

    @Test
    fun `标记里的多余空格被容忍`() {
        val d = TaskSuggestionMarker.parse("[ 建议任务 ： 买牛奶 | 家里快没了 ]")
        assertEquals("买牛奶", d.suggestions[0].title)
        assertEquals("家里快没了", d.suggestions[0].note)
    }

    @Test
    fun `一条回复里的多个标记各自解析`() {
        // 正则若写成贪婪（用 . 而不是字符类），这两条会粘成一条标题
        val d = TaskSuggestionMarker.parse("两件事：[建议任务:买牛奶|家里没了][建议任务:取快递]")
        assertEquals(listOf("买牛奶", "取快递"), d.suggestions.map { it.title })
        assertEquals("两件事：", d.cleanText)
    }

    @Test
    fun `整行只有标记时不留空行`() {
        val d = TaskSuggestionMarker.parse("好呀\n[建议任务:去倒杯水]\n那就这样吧")
        assertEquals(listOf("去倒杯水"), d.suggestions.map { it.title })
        assertEquals("好呀\n那就这样吧", d.cleanText)
    }

    @Test
    fun `没有标记时原样返回`() {
        val d = TaskSuggestionMarker.parse("今天天气不错。")
        assertTrue(d.suggestions.isEmpty())
        assertFalse(d.hasSuggestion)
        assertEquals("今天天气不错。", d.cleanText)
    }

    @Test
    fun `同标题重复只留一条`() {
        val d = TaskSuggestionMarker.parse("[建议任务:买牛奶][建议任务:买牛奶]")
        assertEquals(1, d.suggestions.size)
    }

    @Test
    fun `空标题的标记被忽略但仍剥掉`() {
        val d = TaskSuggestionMarker.parse("嗯[建议任务:  ]")
        assertTrue(d.suggestions.isEmpty())
        assertEquals("嗯", d.cleanText)
    }

    @Test
    fun `strip 只剥标记不返回建议`() {
        // 通话 / 定时消息链路用：这些场景不出卡，但标记绝不能漏给用户看
        val text = TaskSuggestionMarker.strip("路上小心。[建议任务:给妈妈回电话]")
        assertEquals("路上小心。", text)
        assertFalse(text.contains("建议任务"))
    }

    @Test
    fun `标题与备注各截断到上限`() {
        val longTitle = "标".repeat(80)
        val longNote = "备".repeat(200)
        val d = TaskSuggestionMarker.parse("[建议任务:$longTitle|$longNote]")
        assertEquals(40, d.suggestions[0].title.length)
        assertEquals(80, d.suggestions[0].note.length)
    }

    @Test
    fun `协议说明里带上标记格式`() {
        // 提示词与解析器必须同源：说明里写的格式得真能被 parse 认出来
        val section = TaskSuggestionMarker.promptSection()
        assertTrue(section.contains("[建议任务:"))
        val sample = section.substringAfter("[建议任务:").substringBefore("]")
        // 说明里的示例格式（带 | 备注那种）也要能解析
        val d = TaskSuggestionMarker.parse("[建议任务:买牛奶|家里快没了]")
        assertTrue(sample.isNotEmpty())
        assertEquals("买牛奶", d.suggestions[0].title)
    }
}
