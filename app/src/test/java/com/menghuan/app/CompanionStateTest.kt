package com.menghuan.app

import com.menghuan.app.data.CompanionLogic
import com.menghuan.app.data.CompanionState
import com.menghuan.app.data.ReachOut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 角色情绪状态引擎单测（v0.72.0）。
 *
 * 钉的是**单调性、边界、阈值转移**这三类（积温作者也是拿这三类当回归基线的）：
 * 这一层的输出决定"她会不会主动找我"，一旦不单调（沉默更久反而更不想说话）
 * 或者数值越界（connection 掉到负数再慢慢爬回来），表现就是"她的性格随机坏掉"，
 * 而且不报错 —— 只能靠测试钉。
 */
class CompanionStateTest {

    private val base = CompanionState()

    @Test
    fun `沉默越久越想人 而且加速段让它爬得更快`() {
        val short = CompanionLogic.tick(base, minutes = 20f, minutesSinceLastUserMsg = 20f)
        val long = CompanionLogic.tick(base, minutes = 20f, minutesSinceLastUserMsg = 300f)
        assertTrue("同样 20 分钟，沉默更久要更想人", long.connection > short.connection)
    }

    @Test
    fun `单调性 一分钟一分钟推 连接需求只增不减`() {
        var s = CompanionState()
        var prev = 0f
        repeat(120) {
            s = CompanionLogic.tick(s, 1f, minutesSinceLastUserMsg = 10f + it)
            assertTrue("连接需求不许倒退：$prev → ${s.connection}", s.connection >= prev)
            prev = s.connection
        }
    }

    @Test
    fun `数值永不越界`() {
        var s = CompanionState(connection = 0.99f, pride = 0.99f, valence = -1f, arousal = 1f, immersion = 1f)
        repeat(600) {
            s = CompanionLogic.tick(s, 5f, minutesSinceLastUserMsg = 900f)
            assertTrue(s.connection in 0f..1f)
            assertTrue(s.pride in -1f..1f)
            assertTrue(s.valence in -1f..1f)
            assertTrue(s.arousal in -1f..1f)
            assertTrue(s.immersion in 0f..1f)
        }
    }

    @Test
    fun `愉悦度低会压住想找人`() {
        val ok = CompanionLogic.tick(CompanionState(valence = 0.6f), 60f, 60f)
        val low = CompanionLogic.tick(CompanionState(valence = -0.6f), 60f, 60f)
        assertTrue("难过的时候是缩着，不是扑上来", low.connection < ok.connection)
    }

    @Test
    fun `嘴硬撑久了会被连接需求侵蚀`() {
        var s = CompanionState(connection = 0.62f, pride = 0.8f)
        val start = s.pride
        repeat(60) { s = CompanionLogic.tick(s, 1f, minutesSinceLastUserMsg = 400f) }
        assertTrue("骄傲必须往下掉，不然'嘴硬'是永久的", s.pride < start)
    }

    @Test
    fun `四档意愿按连接需求与骄傲共同决定`() {
        assertEquals(ReachOut.SILENT, CompanionLogic.desire(CompanionState(connection = 0.05f)))
        assertEquals(ReachOut.OBSERVE, CompanionLogic.desire(CompanionState(connection = 0.25f)))
        assertEquals(ReachOut.CONSIDER, CompanionLogic.desire(CompanionState(connection = 0.4f, pride = 0f)))
        // 高骄傲把 CONSIDER 挡回 OBSERVE —— 这就是"想找又拉不下脸"
        assertEquals(ReachOut.OBSERVE, CompanionLogic.desire(CompanionState(connection = 0.4f, pride = 0.9f)))
        // 但撑不住了（REACH_OUT）不看骄傲
        assertEquals(ReachOut.REACH_OUT, CompanionLogic.desire(CompanionState(connection = 0.55f, pride = 0.9f)))
    }

    @Test
    fun `状态翻译成人话 并且空状态什么都不塞`() {
        val flat = CompanionLogic.promptContext(CompanionState())
        assertEquals("平淡状态不该占 token", "", flat)
        val miss = CompanionLogic.promptContext(CompanionState(connection = 0.42f, pride = 0.8f))
        assertTrue(miss.contains("拉不下脸"))
        assertTrue("要明说别把状态念给用户", miss.contains("不要把这"))
        // 数值本身绝不出现
        assertTrue(!miss.contains("0.42"))
    }

    @Test
    fun `用户回消息后连接需求清零 骄傲软下来`() {
        val before = CompanionState(connection = 0.7f, pride = 0.9f, immersion = 0.9f)
        val after = CompanionLogic.onUserMessage(before)
        assertEquals(0f, after.connection, 1e-6f)
        assertTrue(after.pride < before.pride)
        assertTrue(after.immersion < before.immersion)
    }

    @Test
    fun `编码解码往返回合`() {
        val s = CompanionState(0.4123f, -0.2222f, 0.1f, 0.5f, 0.05f, atMs = 1_700_000_000_000L)
        val back = CompanionLogic.decode(CompanionLogic.encode(s))
        assertEquals(s.connection, back.connection, 1e-3f)
        assertEquals(s.pride, back.pride, 1e-3f)
        assertEquals(s.atMs, back.atMs)
    }

    @Test
    fun `坏数据不炸 直接退回默认状态`() {
        listOf("", "垃圾", "0.1,0.2", "a,b,c,d,e|x").forEach { bad ->
            val s = CompanionLogic.decode(bad)
            assertEquals(0f, s.connection, 1e-6f)
            assertEquals(0L, s.atMs)
        }
    }

    @Test
    fun `朝目标回落时不许越过目标`() {
        // valence 基准是略微偏冷；从很开心的地方往回落，一步跨过头就是振荡
        val s = CompanionLogic.tick(CompanionState(valence = 0.9f), 1f, minutesSinceLastUserMsg = 0f)
        org.junit.Assert.assertTrue(s.valence < 0.9f && s.valence > CompanionLogic.VALENCE_SETPOINT)
    }

    // ===== 会话列表心情计（v0.77.0：爱心 + 进度条，进度 = 心情好坏）=====

    @Test
    fun `进度条只由愉悦度决定_零点在中间_两端不打爆`() {
        // valence -1..1 → 0..1；valence = 0 → 恰好半格
        assertEquals(0f, CompanionLogic.badgeOf(CompanionState(valence = -1f)).progress, 1e-4f)
        assertEquals(1f, CompanionLogic.badgeOf(CompanionState(valence = 1f)).progress, 1e-4f)
        assertEquals(0.5f, CompanionLogic.badgeOf(CompanionState()).progress, 1e-4f)
    }

    @Test
    fun `想念与嘴硬不再改变进度条_徽标回归心情`() {
        // v0.74.0 时高 connection 会盖成 🥺；v0.77.0 起进度条与 label 都只看愉悦度/唤醒度
        val s = CompanionState(connection = 0.55f, valence = 0.8f, arousal = 0.8f)
        val b = CompanionLogic.badgeOf(s)
        assertEquals(0.9f, b.progress, 1e-4f)
        // label 是情绪簇文案，不是「很想找你」
        org.junit.Assert.assertNotEquals("很想找你", b.label)
    }

    @Test
    fun `平淡状态也有进度与说明 不是空`() {
        val b = CompanionLogic.badgeOf(CompanionState())
        assertTrue("进度必须在 0~1 内", b.progress in 0f..1f)
        assertEquals(CompanionLogic.NEUTRAL_LABEL, b.label)
    }

    @Test
    fun `每个情绪簇都有说明_进度始终在零一之间`() {
        val samples = listOf(
            CompanionState(valence = 0.6f, arousal = 0.6f),   // 兴奋
            CompanionState(valence = 0.6f, arousal = 0f),     // 不错
            CompanionState(valence = -0.6f, arousal = 0.6f),  // 烦
            CompanionState(valence = -0.6f, arousal = 0f),    // 低落
            CompanionState(arousal = 0.6f),                   // 焦躁
            CompanionState(arousal = -0.6f),                  // 平静懒
            CompanionState(valence = 0.15f),                  // 还算愉快
            CompanionState(valence = -0.15f),                 // 有点闷
        )
        samples.forEach {
            val b = CompanionLogic.badgeOf(it)
            assertTrue("进度必须在 0~1 内：${b.progress}", b.progress in 0f..1f)
            assertTrue("说明不能空", b.label.isNotEmpty())
        }
    }
}
