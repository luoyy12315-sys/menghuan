package com.menghuan.app

import com.menghuan.app.realtime.CallExpressionMarker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通话表情标记 `[表情:名]` 的解析单测（v0.69.0，纯 JVM）。
 *
 * 盯的是三件会**当场被用户看见或听见**的事：
 * 标记没剥干净（TTS 念出"表情 脸红"、字幕挂着方括号）、
 * 半个标记在流式过程中亮出来、
 * 以及候选清单为空时还去告诉模型"你可以用表情"（于是它自造名字、什么都不发生）。
 */
class CallExpressionMarkerTest {

    @Test
    fun `单个标记剥干净并取出名字`() {
        val p = CallExpressionMarker.parse("今晚早点睡 [表情:脸红]")
        assertEquals(listOf("脸红"), p.names)
        assertEquals("今晚早点睡", p.text)
        assertTrue(p.hasMarker)
    }

    @Test
    fun `多个标记按出现顺序去重`() {
        val p = CallExpressionMarker.parse("[表情:开心] 好呀 [表情:调皮] 再来 [表情:开心]")
        assertEquals(listOf("开心", "调皮"), p.names)
        assertEquals("好呀 再来", p.text)
    }

    @Test
    fun `整句只有标记时正文为空但名字仍在`() {
        // 爱语那一路允许"仅发表情"：这时一个字都不该送去合成
        val p = CallExpressionMarker.parse("[表情:害羞]")
        assertEquals(listOf("害羞"), p.names)
        assertEquals("", p.text)
    }

    @Test
    fun `没有标记时正文一字不动`() {
        val p = CallExpressionMarker.parse("正常的一句话。")
        assertEquals(emptyList<String>(), p.names)
        assertEquals("正常的一句话。", p.text)
        assertFalse(p.hasMarker)
    }

    @Test
    fun `空名字标记吃掉但不产出名字`() {
        val p = CallExpressionMarker.parse("好 [表情:] 呀")
        assertEquals(emptyList<String>(), p.names)
        assertEquals("好 呀", p.text)
    }

    @Test
    fun `超过长度上限的方括号不当标记_跑偏时不把脏东西带进渲染层`() {
        val long = "x".repeat(40)
        val p = CallExpressionMarker.parse("[$long]")
        assertEquals(emptyList<String>(), p.names)
        assertTrue("原样留在正文", p.text.contains(long))
    }

    @Test
    fun `显示口径会藏住还没收口的半个标记`() {
        // 流式途中屏幕上只剩"今晚早点睡"，不能出现 [表情:脸 这种尾巴
        assertEquals("今晚早点睡", CallExpressionMarker.stripForDisplay("今晚早点睡 [表情:脸"))
    }

    @Test
    fun `显示口径剥掉完整标记`() {
        assertEquals(
            "好呀",
            CallExpressionMarker.stripForDisplay("好呀 [表情:开心]"),
        )
    }

    @Test
    fun `显示口径不影响正文里正常的方括号`() {
        val raw = "我说 [备注] 就是这个意思"
        assertEquals(raw, CallExpressionMarker.stripForDisplay(raw))
    }

    @Test
    fun `候选清单为空时整段提示词都不注入`() {
        assertEquals("", CallExpressionMarker.promptSection(emptyList()))
        assertEquals("", CallExpressionMarker.promptSection(listOf("  ", "")))
    }

    @Test
    fun `提示词段列全候选并去重`() {
        val section = CallExpressionMarker.promptSection(listOf("脸红", "脸红", " 开心 "))
        assertTrue(section.contains("[表情:脸红]"))
        assertTrue(section.contains("[表情:开心]"))
        assertEquals("去重后只能出现一次", 1, Regex("脸红").findAll(section).count())
        // 明写"不许自己编"——模型自造名字会被准入判定丢掉，等于静默失效
        assertTrue(section.contains("不许自己编"))
    }
}
