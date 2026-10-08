package com.menghuan.app

import com.menghuan.app.live2d.InteractionRegion
import com.menghuan.app.live2d.InteractionRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "触碰→模型听得见的一句话"的用词单测（v0.71.0）。
 *
 * 钉的是两件会**骗到用户**的事：
 * 把内部枚举名/坐标甩给模型（它只能瞎猜），以及点到空白处却告诉模型"用户碰了你的身体"
 * —— 后者会让角色对一个没发生的接触表情绪，比不做互动更糟。
 */
class InteractionWordsTest {

    @Test
    fun `三个区各有一句人话`() {
        assertEquals("摸了摸你的头", InteractionRules.spokenActionOf(InteractionRegion.HEAD))
        assertEquals("轻轻碰了碰你的身体", InteractionRules.spokenActionOf(InteractionRegion.BODY))
        assertTrue(InteractionRules.spokenActionOf(InteractionRegion.OTHER).contains("没点到"))
    }

    @Test
    fun `空白处不许谎称被碰到`() {
        val other = InteractionRules.spokenActionOf(InteractionRegion.OTHER)
        assertFalse("不许出现「碰了你的身体」这种肯定句", other.contains("碰了碰你的身体"))
        assertFalse("不许出现「摸了摸你的头」", other.contains("摸了摸你的头"))
    }

    @Test
    fun `措辞里不许有枚举名或坐标`() {
        InteractionRegion.entries.forEach { r ->
            val s = InteractionRules.spokenActionOf(r)
            assertFalse("不许把内部 key 直接给模型：" + s, s.contains(r.key))
            assertFalse(s.contains(","))
        }
    }
}
