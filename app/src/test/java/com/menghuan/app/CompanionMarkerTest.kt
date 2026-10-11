package com.menghuan.app

import com.menghuan.app.data.CompanionLogic
import com.menghuan.app.data.CompanionMarker
import com.menghuan.app.data.CompanionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `[心情:情绪词]` 单测（v0.98.0，纯 JVM）。
 *
 * 盯三件事：
 * 1. **标记绝不能漏进用户可见的正文**（漏了就是屏幕上挂着 `[心情:…]`）—— 同 `MemoryMarkerTest` 的底线；
 * 2. **全角括号 / 冒号也要认**（模型会在两种括号之间来回切，只认半角会时不时失灵）；
 * 3. **词表 → 增量 → 真的推动 valence/arousal**：这是它存在的全部意义 ——
 *    在这条链之前 valence/arousal 没有任何输入源，心情计永远半格（见 [CompanionState] 头注释）。
 */
class CompanionMarkerTest {

    @Test
    fun `抽出一个词并剥干净正文`() {
        val d = CompanionMarker.parse("好呀，那我们明天见。\n[心情:开心]")
        assertTrue(d.hit)
        assertTrue(d.effective)
        assertTrue("开心应当是正面的", d.valence > 0f)
        assertEquals("好呀，那我们明天见。", d.cleanText)
        assertFalse("标记不许留在正文里", d.cleanText.contains("心情"))
    }

    @Test
    fun `全角括号与冒号也认`() {
        val d = CompanionMarker.parse("知道啦【心情：难过】")
        assertTrue(d.effective)
        assertTrue("难过应当是负面的", d.valence < 0f)
        assertEquals("知道啦", d.cleanText)
        assertFalse(d.cleanText.contains("心情"))
    }

    @Test
    fun `未知词照样剥掉但不产生增量`() {
        val d = CompanionMarker.parse("嗯嗯。[心情:暴躁]")
        assertTrue("出现了标记就要算 hit", d.hit)
        assertFalse("词不在表里，不该动状态", d.effective)
        assertEquals(0f, d.valence, 1e-6f)
        assertEquals(0f, d.arousal, 1e-6f)
        assertFalse("认不认得词不影响『剥掉』", d.cleanText.contains("心情"))
    }

    @Test
    fun `没有标记时正文一字不动`() {
        val raw = "今天过得怎么样？有没有好好吃饭。"
        val d = CompanionMarker.parse(raw)
        assertFalse(d.hit)
        assertFalse(d.effective)
        assertEquals(raw, d.cleanText)
    }

    @Test
    fun `标记独占一行时剥干净_正文两段都还在`() {
        val d = CompanionMarker.parse("第一行\n[心情:温暖]\n第四行")
        assertTrue(d.effective)
        assertTrue(d.cleanText.contains("第一行"))
        assertTrue(d.cleanText.contains("第四行"))
        assertFalse("标记不许有残留", d.cleanText.contains("心情"))
        // 剥完留下一个正常段落空行，不出现三个换行
        assertFalse(d.cleanText.contains("\n\n\n"))
    }

    @Test
    fun `整条只有标记时正文为空但标记仍被记下`() {
        val d = CompanionMarker.parse("[心情:平静]")
        assertTrue(d.hit)
        assertTrue("平静会压低唤醒度", d.arousal < 0f)
        assertEquals("", d.cleanText)
    }

    @Test
    fun `多个标记取第一个认得的词`() {
        // 模型偶尔会连写两个：取第一个能查表的，其余一并剥掉
        val d = CompanionMarker.parse("[心情:未知词啊][心情:烦躁]")
        assertTrue(d.effective)
        assertTrue("烦躁应当是负面的", d.valence < 0f)
        assertFalse(d.cleanText.contains("心情"))
    }

    @Test
    fun `超长或空词也不许漏出标记`() {
        val long = "很".repeat(80)
        assertFalse("超长的词也要被剥掉", CompanionMarker.parse("嗯[心情:$long]").cleanText.contains("心情"))
        assertFalse("空词也要被剥掉", CompanionMarker.parse("嗯[心情:]").cleanText.contains("心情"))
    }

    @Test
    fun `提示词段把两条硬约束写进去了`() {
        val s = CompanionMarker.promptSection()
        assertTrue("要教格式", s.contains("[心情:"))
        assertTrue("要拦住它一律写中性词（否则又退回『心情恒定』）", s.contains("别一律写"))
        assertTrue("要明说标记不会被显示", s.contains("不会显示"))
    }

    // ===== 接到引擎上：这条标记必须真的能改变心情 =====

    @Test
    fun `增量落到轴上是叠加且夹在界内的`() {
        val s = CompanionState(valence = 0.9f, arousal = 0.9f)
        val after = CompanionLogic.applyDelta(s, valence = 0.25f, arousal = 0.2f)
        assertEquals(1f, after.valence, 1e-6f)
        assertEquals(1f, after.arousal, 1e-6f)
    }

    @Test
    fun `反复叠加正情绪最终会走出『情绪平平』`() {
        var s = CompanionState()
        repeat(3) { s = CompanionLogic.applyDelta(s, valence = 0.25f, arousal = 0.05f) }
        val badge = CompanionLogic.badgeOf(s)
        assertTrue("心情计进度要过半", badge.progress > 0.5f)
        assertFalse(
            "叠了几次正情绪标签不该还是中性 —— 这正是修好的标志",
            badge.label == CompanionLogic.NEUTRAL_LABEL,
        )
    }
}
