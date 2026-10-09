package com.menghuan.app

import com.menghuan.app.data.ChatAppearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 聊天外观（v0.80.0 推倒重做）的数据层回归。
 *
 * 这一层是**纯数据**：颜色存 ARGB Long、0L = 「跟主题」哨兵，不碰 Compose 的 Color，
 * 所以能在纯 JVM 单测里跑（全项目纪律）。UI 层的 resolve（哨兵 → 主题令牌）在 ui/theme，
 * 那边要 @Composable 才能读主题，测不到 —— 能被自动测试守住的正是这里的编解码与哨兵语义。
 */
class ChatAppearanceTest {

    @Test
    fun `气泡样式编解码往返不变`() {
        val style = ChatAppearance.BubbleStyle(
            bg = 0xFF3A4A7AL,
            ink = 0xFFFFFFFFL,
            border = 0xFF111111L,
            radiusDp = 12,
        )
        val back = ChatAppearance.BubbleStyle.decode(style.encode())
        assertEquals(style, back)
    }

    @Test
    fun `空串与坏串一律回默认 不崩`() {
        assertEquals(ChatAppearance.BubbleStyle.DEFAULT, ChatAppearance.BubbleStyle.decode(""))
        assertEquals(ChatAppearance.BubbleStyle.DEFAULT, ChatAppearance.BubbleStyle.decode(null))
        assertEquals(ChatAppearance.BubbleStyle.DEFAULT, ChatAppearance.BubbleStyle.decode("1|2"))
        assertEquals(ChatAppearance.BubbleStyle.DEFAULT, ChatAppearance.BubbleStyle.decode("x|y|z|w"))
    }

    @Test
    fun `默认值的哨兵语义`() {
        val s = ChatAppearance.BubbleStyle.DEFAULT
        assertEquals(0L, s.bg)          // 背景跟主题
        assertEquals(0L, s.ink)         // 字色自动对比
        assertEquals(0L, s.border)      // 无边框
        assertEquals(-1, s.radiusDp)    // 圆角用默认 18
    }

    @Test
    fun `自动字色按亮度挑黑白`() {
        // 亮底 → 近黑字；暗底 → 近白字（这是"怎么改都看得见"的保底）
        assertEquals(0xFF111111L, ChatAppearance.autoInkFor(0xFFF7F7F8L))
        assertEquals(0xFF111111L, ChatAppearance.autoInkFor(0xFFE7DCCBL))
        assertEquals(0xFFFCFCFCL, ChatAppearance.autoInkFor(0xFF1A1A1AL))
        assertEquals(0xFFFCFCFCL, ChatAppearance.autoInkFor(0xFF3A4A7AL))
    }

    @Test
    fun `枚举认不出时一律回默认 老数据不崩`() {
        assertEquals(ChatAppearance.FontFamilyKey.SANS, ChatAppearance.FontFamilyKey.fromKey("???"))
        assertEquals(ChatAppearance.FontFamilyKey.SANS, ChatAppearance.FontFamilyKey.fromKey(null))
        assertEquals(ChatAppearance.FontWeightKey.NORMAL, ChatAppearance.FontWeightKey.fromKey("w900"))
        assertEquals(ChatAppearance.FontWeightKey.NORMAL, ChatAppearance.FontWeightKey.fromKey(null))
    }

    @Test
    fun `字号与圆角的可用区间`() {
        // 设置页的滑条按这两个区间铺，改动要同步 UI
        assertTrue(ChatAppearance.DEFAULT_FONT_SP in ChatAppearance.MIN_FONT_SP..ChatAppearance.MAX_FONT_SP)
        assertTrue(
            ChatAppearance.BubbleStyle.DEFAULT_RADIUS_DP
                in ChatAppearance.MIN_RADIUS_DP..ChatAppearance.MAX_RADIUS_DP
        )
        // 0 是合法圆角（直角），所以"没设过"必须用 -1 当哨兵、不能拿 0 顶替
        assertTrue(ChatAppearance.BubbleStyle.NO_RADIUS < ChatAppearance.MIN_RADIUS_DP)
    }

    @Test
    fun `明暗两套结构上是分开的 改一套不动另一套`() {
        val a = ChatAppearance()
        val changed = a.copy(light = a.light.copy(mine = a.light.mine.copy(bg = 0xFF123456L)))
        assertEquals(0L, changed.dark.mine.bg)
        assertEquals(0xFF123456L, changed.light.mine.bg)
        assertEquals(0L, changed.light.other.bg)
    }
}
