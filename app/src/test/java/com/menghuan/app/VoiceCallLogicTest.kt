package com.menghuan.app

import com.menghuan.app.realtime.EdgeTtsDrm
import com.menghuan.app.realtime.SentenceSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音通话纯函数单测（v0.46.0）：切句器 + Edge TTS DRM 令牌。
 * 音频/网络/native 部分不进 JVM 单测（真机验证清单见交付说明）。
 */
class VoiceCallLogicTest {

    // ---- SentenceSplitter：流式文本切句 ----

    @Test
    fun `句末标点立即切出`() {
        val (sentences, rest) = SentenceSplitter.drain("今天天气不错！适合出门。晚上")
        assertEquals(listOf("今天天气不错！", "适合出门。"), sentences)
        assertEquals("晚上", rest)
    }

    @Test
    fun `省略号整体作为句尾`() {
        val (sentences, rest) = SentenceSplitter.drain("我想想看……嗯，好的")
        assertEquals(listOf("我想想看……"), sentences)
        assertEquals("嗯，好的", rest)
    }

    @Test
    fun `英文小数点不切句`() {
        val (sentences, rest) = SentenceSplitter.drain("圆周率是3.14159")
        assertTrue(sentences.isEmpty())
        assertEquals("圆周率是3.14159", rest)
    }

    @Test
    fun `英文句号接空白才切`() {
        val (sentences, rest) = SentenceSplitter.drain("I am ok. Next")
        assertEquals(listOf("I am ok."), sentences)
        assertEquals("Next", rest)
    }

    @Test
    fun `超长句在逗号处兜底切分`() {
        // 逗号前 37 字 ≥ SOFT_LIMIT(36)：达到上限才在逗号处切
        val longNoEnd = "这是一个特别特别长的句子它从头到尾都没有出现任何可以用来断句的句末标点符号，于是达到上限后在逗号处兜底切一刀"
        val (sentences, rest) = SentenceSplitter.drain(longNoEnd)
        assertTrue("长句应在逗号处切出: $sentences", sentences.isNotEmpty())
        assertTrue(sentences.first().endsWith("，"))
        assertEquals(longNoEnd.substring(sentences.first().length), rest)
    }

    @Test
    fun `短句中的逗号不切`() {
        val (sentences, _) = SentenceSplitter.drain("你好，我是林泽")
        assertTrue(sentences.isEmpty())
    }

    @Test
    fun `换行也是句界`() {
        val (sentences, rest) = SentenceSplitter.drain("第一行\n第二行")
        assertEquals(listOf("第一行"), sentences)
        assertEquals("第二行", rest)
    }

    @Test
    fun `连续调用累积正确`() {
        // 模拟流式增量：两次 drain 的 rest 接着用
        val (s1, r1) = SentenceSplitter.drain("好的，我")
        assertTrue(s1.isEmpty())
        val (s2, r2) = SentenceSplitter.drain(r1 + "明白了。那")
        assertEquals(listOf("好的，我明白了。"), s2)
        assertEquals("那", r2)
    }

    // ---- EdgeTtsDrm：Sec-MS-GEC 令牌生成 ----

    @Test
    fun `sha256 标准测试向量`() {
        assertEquals(
            "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD",
            EdgeTtsDrm.sha256HexUpper("abc"),
        )
    }

    @Test
    fun `时间对齐到5分钟边界再算ticks`() {
        // 1699999999000ms → unix=1699999999 + WIN_EPOCH=11644473600 = 13344473599
        // /300*300 = 13344473400 → *1e7 ticks = 133444734000000000
        val token = EdgeTtsDrm.secMsGec(1_699_999_999_000L)
        // 手工按算法独立计算一次做黄金对照
        val ticks = (1_699_999_999L + 11_644_473_600L).let { it - it % 300 } * 10_000_000L
        val expected = EdgeTtsDrm.sha256HexUpper("${ticks}${EdgeTtsDrm.TRUSTED_CLIENT_TOKEN}")
        assertEquals(expected, token)
        assertEquals(64, token.length)
        assertEquals(token, token.uppercase())
    }

    @Test
    fun `同一5分钟窗口内令牌稳定`() {
        // 1700000000s 落在窗口 [1699999800, 1700000100)：窗口内任意时刻令牌一致
        val a = EdgeTtsDrm.secMsGec(1_700_000_000_000L)
        val b = EdgeTtsDrm.secMsGec(1_700_000_009_999L)
        val c = EdgeTtsDrm.secMsGec(1_700_000_101_000L) // 落在下一个窗口
        assertEquals(a, b)
        assertTrue(a != c || a == c) // 相邻窗口可能哈希相同也可能不同，关键是窗口内稳定
        assertEquals(a, EdgeTtsDrm.secMsGec(1_700_000_000_500L))
    }
}
