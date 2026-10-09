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

    // ===== v0.77.0：AI 点赞 =====

    @Test
    fun `点赞标记抽出 id 并剥干净正文`() {
        val d = MomentMarker.parse("这条我喜欢。\n[点赞:7]")
        assertEquals(listOf(7L), d.likes)
        assertTrue(d.posts.isEmpty())
        assertTrue(d.replies.isEmpty())
        assertEquals("这条我喜欢。", d.cleanText)
        assertFalse("点赞标记必须剥干净", d.cleanText.contains("点赞"))
    }

    @Test
    fun `点赞与回帖可以同时在一条里`() {
        val d = MomentMarker.parse("[动态#3:不错]\n[点赞:3]")
        assertEquals(listOf(3L to "不错"), d.replies)
        assertEquals(listOf(3L), d.likes)
        assertEquals("", d.cleanText)
    }

    @Test
    fun `全角冒号的点赞也认`() {
        val d = MomentMarker.parse("[点赞：12]")
        assertEquals(listOf(12L), d.likes)
    }

    @Test
    fun `点赞 id 去重`() {
        // 模型可能重复说两遍，落库前先去重（避免同一条帖子写好几次）
        val d = MomentMarker.parse("[点赞:5] 好喜欢 [点赞:5]")
        assertEquals(listOf(5L), d.likes)
    }

    @Test
    fun `只点赞也算想做点什么`() {
        assertTrue(MomentMarker.parse("[点赞:1]").wantsAnything)
    }

    @Test
    fun `超长点赞编号不当标记`() {
        val raw = "[点赞:1234567890123]"
        val d = MomentMarker.parse(raw)
        assertTrue("过长 id 不该被认成点赞标记", d.likes.isEmpty())
        assertTrue("正文要原样留着", d.cleanText.contains("点赞"))
    }

    @Test
    fun `固定说明里点赞语法在_且点名了看动态的两个时机与记忆可读`() {
        val p = MomentMarker.promptSection()
        assertTrue("要点出点赞语法", p.contains("[点赞:帖子id]"))
        // v0.79.0 **改正**：v0.77.0 把用户那句"AI 回复动态只能在跟 AI 聊天时回复"当成了产品要求，
        // 写成"你只能在聊天里顺手做、没有刷动态的能力"——**理解反了**。
        // 用户原话是"不跟 AI 聊的时候它也会看我动态，像真人一样"。
        assertTrue("要点出看动态的两个时机", p.contains("你看到动态的时机有两个"))
        assertTrue("主动找用户时也能看到动态", p.contains("让你主动去找用户"))
        assertFalse(
            "不许再写「只能在聊天里做」",
            p.contains("所有动态动作都只发生在你回复用户这条消息的过程中"),
        )
        // 保留的仍然是"别假装自己一直在线"（不诚实，也不是真话）
        assertTrue("要禁止假装随时在线", p.contains("别假装自己一直在线"))
        // v0.77.0：AI 记忆可读要说清楚
        assertTrue("要说清 AI 看得到记忆", p.contains("你看得到用户的记忆"))
    }
}
