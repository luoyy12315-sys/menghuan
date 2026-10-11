package com.menghuan.app

import com.menghuan.app.data.MemoryMarker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `<add:内容>` 记忆写入标记单测（v0.70.0，纯 JVM）。
 *
 * 盯两件事：**标记绝不能漏进用户可见的正文**（漏了就是屏幕上挂着 `<add:...>`），
 * 以及**全角冒号也要认**（模型会把冒号"纠正"成全角，只认半角会让记忆时不时失灵，
 * 而那种失灵用户完全看不出原因——`【语音条】` 的同族教训）。
 */
class MemoryMarkerTest {

    @Test
    fun `抽出一条并剥干净正文`() {
        val d = MemoryMarker.parse("好哒 <add:这家伙一加班就忘记吃饭> 你要按时吃饭呀")
        assertEquals(listOf("这家伙一加班就忘记吃饭"), d.items)
        assertEquals("好哒 你要按时吃饭呀", d.cleanText)
        assertFalse("标记不许留在正文里", d.cleanText.contains("<add:"))
    }

    @Test
    fun `全角冒号也认`() {
        val d = MemoryMarker.parse("嗯 <add：她周五要交稿>")
        assertEquals(listOf("她周五要交稿"), d.items)
        assertEquals("嗯", d.cleanText)
    }

    @Test
    fun `多条按出现顺序去重`() {
        val d = MemoryMarker.parse(
            "<add:住在杭州> <add:养了只猫> <add:住在杭州> 收到"
        )
        assertEquals(listOf("住在杭州", "养了只猫"), d.items)
        assertEquals("收到", d.cleanText)
    }

    @Test
    fun `整条只有标记时正文为空但条目仍在`() {
        val d = MemoryMarker.parse("<add:下周出差去上海>")
        assertEquals(1, d.items.size)
        assertEquals("", d.cleanText)
    }

    @Test
    fun `没有标记时正文一字不动`() {
        val raw = "今天过得怎么样？有没有好好吃饭。"
        val d = MemoryMarker.parse(raw)
        assertTrue(d.items.isEmpty())
        assertEquals(raw, d.cleanText)
    }

    @Test
    fun `尖括号里没内容或超长都不当条目`() {
        // 空内容
        assertTrue(MemoryMarker.parse("好 <add:>").items.isEmpty())
        // 超过 200 字的一律不当记忆：模型跑偏时会把整段话塞进标记
        val long = "忘".repeat(300)
        assertTrue(MemoryMarker.parse("<add:$long>").items.isEmpty())
    }

    @Test
    fun `标记独占一行时剥干净_正文两段都还在`() {
        val d = MemoryMarker.parse("第一行\n<add:记住这件事>\n第四行")
        assertEquals(listOf("记住这件事"), d.items)
        assertFalse("标记不许有残留", d.cleanText.contains("<add"))
        assertTrue(d.cleanText.contains("第一行"))
        assertTrue(d.cleanText.contains("第四行"))
        // 剥完留下的是一个**正常的段落空行**（与 VoiceNoteMarker 同一口径：
        // 只压三个以上换行，不碰用户本来分段用的空行），所以这里断言的是"不出现三个换行"
        assertFalse(d.cleanText.contains("\n\n\n"))
    }

    @Test
    fun `keyOf 取句首当短标签_长句截到 24 字`() {
        val item = "这家伙一加班就忘记吃饭而且不管多晚都必须点一杯冰美式才肯睡"
        assertTrue(item.length > 24)
        val key = MemoryMarker.keyOf(item)
        assertEquals(24, key.length)
        assertTrue(item.startsWith(key))
        // 短句不硬凑：原样当标签
        assertEquals("住在杭州", MemoryMarker.keyOf("住在杭州"))
    }

    @Test
    fun `提示词段把三条硬规矩都写进去了`() {
        val s = MemoryMarker.promptSection()
        assertTrue("要教它用自己的视角记（不然会写成系统日志体）", s.contains("你自己的视角"))
        assertTrue("要拦住它把标记内容再念一遍", s.contains("不要把标记的内容再念一遍"))
        assertTrue("要明说琐碎的别记", s.contains("不要记"))
        assertTrue(s.contains("<add:"))
        // v0.77.0 用户点名「不清楚 AI 记忆是否可读」；这句原本挂在动态协议段的尾巴上，
        // 聊天链整块移除动态协议后搬到这里（别再让它跟着那段一起消失）
        assertTrue("要说清 AI 看得到记忆", s.contains("你看得到用户的记忆"))
    }

    /**
     * v0.98.0：说明段拆成「硬约束 + 可编策略」，且**可编的那半必须能被替换**。
     *
     * 拆分的意义就在于用户能改策略；如果 `promptSection(strategy)` 忽略了传进来的值
     * （比如又硬编码回默认），那设置页那个输入框就是个摆设 —— 这条专门钉住它。
     */
    @Test
    fun `策略可编_硬约束必须始终保留`() {
        val custom = "只说重点，别的别记。"
        val s = MemoryMarker.promptSection(custom)
        assertTrue("用户写的策略要进去", s.contains(custom))
        assertTrue("默认策略被替换掉，不该同时出现", !s.contains("以你自己的视角记"))
        // 硬约束与策略无关，换掉策略后必须一条不少
        assertTrue("格式说明不能丢", s.contains("<add:一句话>"))
        assertTrue("剥除说明不能丢", s.contains("不要把标记的内容再念一遍"))
        assertTrue("可读性说明不能丢", s.contains("你看得到用户的记忆"))
        // 策略清空 = 只发硬约束（仍是一段能用的说明）
        val blank = MemoryMarker.promptSection("   ")
        assertTrue(blank.contains("<add:一句话>"))
        assertTrue(!blank.contains("以你自己的视角记"))
    }
}
