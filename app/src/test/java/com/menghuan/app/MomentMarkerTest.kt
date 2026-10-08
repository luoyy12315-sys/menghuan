package com.menghuan.app

import com.menghuan.app.data.MomentMarker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `[动态:…]` / `[动态#id:…]` 标记单测（v0.73.0，纯 JVM）。
 *
 * 与 [MemoryMarkerTest] 同一条底线：**标记不许漏进用户可见的正文**；
 * 再加两条这一族特有的：回帖的 id 要抽对、模型编出来的长 id 不能当成标记吞掉。
 */
class MomentMarkerTest {

    @Test
    fun `抽出帖子并剥干净正文`() {
        val d = MomentMarker.parse("今天过得不错。[动态:雨停的时候楼下的猫出来了]")
        assertEquals(listOf("雨停的时候楼下的猫出来了"), d.posts)
        assertTrue(d.replies.isEmpty())
        assertEquals("今天过得不错。", d.cleanText)
        assertFalse("标记必须剥干净", d.cleanText.contains("动态"))
    }

    @Test
    fun `全角冒号也认`() {
        val d = MomentMarker.parse("[动态：想你了]")
        assertEquals(listOf("想你了"), d.posts)
        assertEquals("", d.cleanText)
    }

    @Test
    fun `整行只有标记时不留空行`() {
        val d = MomentMarker.parse("好呀\n[动态:去倒杯水]\n那就这样吧")
        assertEquals(listOf("去倒杯水"), d.posts)
        assertEquals("好呀\n那就这样吧", d.cleanText)
    }

    @Test
    fun `回帖抽出 id 与内容`() {
        val d = MomentMarker.parse("[动态#7:我也在等雨停]")
        assertEquals(emptyList<String>(), d.posts)
        assertEquals(listOf(7L to "我也在等雨停"), d.replies)
        assertEquals("", d.cleanText)
    }

    @Test
    fun `发帖与回帖混在一条回复里都要抽到`() {
        val d = MomentMarker.parse(
            "嗯。\n[动态:他今天终于把那份表交了]\n[动态#3:你猜我刚才在想谁]",
        )
        assertEquals(listOf("他今天终于把那份表交了"), d.posts)
        assertEquals(listOf(3L to "你猜我刚才在想谁"), d.replies)
        assertEquals("嗯。", d.cleanText)
    }

    @Test
    fun `超长编号不当标记_宁可让用户看见`() {
        // 13 位以上不是本机自增能到的量级，多半是模型编的；不吞掉整句话
        val raw = "[动态#1234567890123:这句话是真的]"
        val d = MomentMarker.parse(raw)
        assertTrue("过长 id 不该被认成回复标记", d.replies.isEmpty())
        assertTrue("正文要原样留着", d.cleanText.contains("这句话是真的"))
    }

    @Test
    fun `空内容不产出条目`() {
        val d = MomentMarker.parse("[动态:]正文在这儿")
        assertTrue(d.posts.isEmpty())
        assertFalse(d.wantsAnything)
    }

    @Test
    fun `没有帖子时不塞空标题进上下文`() {
        assertEquals("", MomentMarker.feedBlock(emptyList()))
        val block = MomentMarker.feedBlock(listOf(MomentMarker.feedLine(7, "小满", "今天下了很大的雨")))
        assertTrue(block.contains("· 7 小满：今天下了很大的雨"))
    }

    @Test
    fun `固定说明里两条语法都在`() {
        val p = MomentMarker.promptSection()
        assertTrue(p.contains("[动态:一句话]"))
        assertTrue(p.contains("[动态#帖子id:一句话]"))
        assertTrue(p.contains("不要为了刷存在感而发"))
    }
}
