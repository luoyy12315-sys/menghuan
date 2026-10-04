package com.menghuan.app

import com.menghuan.app.data.VoiceNoteMarker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 【语音条】标记解析。标记绝不能漏进用户可见文字，所以"剥干净"这件事逐个边界钉住。
 */
class VoiceNoteMarkerTest {

    @Test
    fun `没有标记时原样返回`() {
        val raw = "今天过得怎么样？"
        val decision = VoiceNoteMarker.parse(raw)
        assertFalse(decision.wantsVoice)
        assertEquals(raw, decision.cleanText)
    }

    @Test
    fun `标记在开头时剥掉并保留正文`() {
        val decision = VoiceNoteMarker.parse("【语音条】早点睡，明天还要早起。")
        assertTrue(decision.wantsVoice)
        assertEquals("早点睡，明天还要早起。", decision.cleanText)
    }

    @Test
    fun `标记单独占一行时不留空行`() {
        val decision = VoiceNoteMarker.parse("【语音条】\n\n这段话是说给你听的。")
        assertTrue(decision.wantsVoice)
        assertEquals("这段话是说给你听的。", decision.cleanText)
    }

    @Test
    fun `标记在结尾时也剥掉`() {
        val decision = VoiceNoteMarker.parse("我记得你说过要早睡。\n【语音条】")
        assertTrue(decision.wantsVoice)
        assertEquals("我记得你说过要早睡。", decision.cleanText)
    }

    @Test
    fun `半角方括号写法同样认`() {
        val decision = VoiceNoteMarker.parse("[语音条]换行也要认。")
        assertTrue(decision.wantsVoice)
        assertEquals("换行也要认。", decision.cleanText)
    }

    @Test
    fun `重复标记全部剥掉`() {
        val decision = VoiceNoteMarker.parse("【语音条】开头一句。【语音条】")
        assertTrue(decision.wantsVoice)
        assertEquals("开头一句。", decision.cleanText)
        assertFalse("标记不该残留", decision.cleanText.contains("语音条"))
    }

    @Test
    fun `只有标记没有正文时 wantsVoice 为真但正文为空`() {
        val decision = VoiceNoteMarker.parse("【语音条】")
        assertTrue(decision.wantsVoice)
        assertEquals("", decision.cleanText)
    }

    @Test
    fun `正文里的方括号不被误伤`() {
        val raw = "参考 [生图:提示词] 这种写法。"
        val decision = VoiceNoteMarker.parse(raw)
        assertFalse(decision.wantsVoice)
        assertEquals(raw, decision.cleanText)
    }

    @Test
    fun `标记夹在段落中间时前后正文都保留`() {
        val decision = VoiceNoteMarker.parse("第一段。\n【语音条】\n第二段。")
        assertTrue(decision.wantsVoice)
        assertEquals("第一段。\n\n第二段。", decision.cleanText)
    }
}
