package com.menghuan.app

import com.menghuan.app.data.AsrReadout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 端侧语气读数 → 给模型那一句话的纯函数单测（v0.69.0）。
 *
 * 守住两条：认不出的标签不许把英文原词甩给模型；没有信号时整条不注入（不占 token）。
 */
class AsrReadoutTest {

    @Test
    fun `情绪标签翻成中文措辞`() {
        val hint = AsrReadout(text = "嗯", emotion = "SAD").toneHint()
        assertTrue(hint.contains("有点低落"))
        assertTrue("要明说是语音判读，不是用户说的字", hint.contains("语音"))
    }

    @Test
    fun `中性情绪整条不写——没有信息量还费token`() {
        assertEquals("", AsrReadout(text = "嗯", emotion = "NEUTRAL").toneHint())
        assertEquals("", AsrReadout(text = "嗯", emotion = "NEUTRAL", event = "Speech").toneHint())
    }

    @Test
    fun `认不出的标签不翻译也不甩英文原词`() {
        val hint = AsrReadout(text = "嗯", emotion = "EMO").toneHint()
        assertEquals("表外情绪不该产出任何句子", "", hint)
        assertFalse(AsrReadout(text = "嗯", emotion = "FRUSTRATED").toneHint().contains("FRUSTRATED"))
    }

    @Test
    fun `背景在唱歌是一条有用的信号`() {
        val hint = AsrReadout(text = "嗯", emotion = "HAPPY", event = "Sing").toneHint()
        assertTrue(hint.contains("心情不错"))
        assertTrue(hint.contains("正在唱歌"))
    }

    @Test
    fun `只有事件没有情绪也能出一句`() {
        val hint = AsrReadout(text = "嗯", event = "BGM").toneHint()
        assertTrue(hint.contains("背景有音乐"))
    }

    @Test
    fun `全空时不注入任何东西`() {
        assertEquals("", AsrReadout(text = "嗯").toneHint())
        assertEquals("", AsrReadout(text = "嗯", emotion = "  ", event = " ").toneHint())
    }

    @Test
    fun `大小写与空格不影响识别`() {
        assertTrue(AsrReadout(text = "嗯", emotion = "happy").toneHint().contains("心情不错"))
        assertTrue(AsrReadout(text = "嗯", emotion = " Sad ").toneHint().contains("有点低落"))
    }

    @Test
    fun `带空格的事件标签也能认——SenseVoice 原样就是 Ambient_sounds`() {
        assertTrue(AsrReadout(text = "嗯", event = "Ambient sounds").toneHint().contains("背景有环境声"))
    }
}
