package com.menghuan.app

import com.menghuan.app.data.ScheduledMessage
import com.menghuan.app.data.ScheduledMessageCodec
import com.menghuan.app.data.ScheduledMessageLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「定时主动消息」的纯逻辑单测（v0.53.5）：编解码往返、脏数据容错、到点判定。
 * 不碰 Android 框架，纯 JVM。
 */
class ScheduledMessageTest {

    private fun msg(
        id: String = "m1",
        time: String = "07:30",
        enabled: Boolean = true,
        lastSentDay: Long = 0L,
    ) = ScheduledMessage(id = id, personaId = 42L, time = time, instruction = "发今天的新闻", withSearch = true, enabled = enabled, lastSentDay = lastSentDay)

    @Test
    fun `编解码往返一致`() {
        val list = listOf(msg("a"), msg("b", time = "22:00"))
        assertEquals(list, ScheduledMessageCodec.decode(ScheduledMessageCodec.encode(list)))
    }

    @Test
    fun `脏数据不崩：不是 JSON 当空、缺 id 跳过、缺字段给默认值`() {
        assertEquals(emptyList<ScheduledMessage>(), ScheduledMessageCodec.decode("不是 json"))
        assertEquals(emptyList<ScheduledMessage>(), ScheduledMessageCodec.decode(""))
        // 缺 id 的那条会被跳过
        assertEquals(emptyList<ScheduledMessage>(), ScheduledMessageCodec.decode("""[{"time":"08:00"}]"""))
        // 缺字段补默认
        val one = ScheduledMessageCodec.decode("""[{"id":"x"}]""")
        assertEquals(1, one.size)
        assertEquals("08:00", one[0].time)
        assertEquals(0L, one[0].personaId)
        assertTrue(one[0].enabled)
        assertFalse(one[0].withSearch)
    }

    @Test
    fun `到点判定：过了设定时刻才发`() {
        val today = 20_000L
        // 07:30 设定，现在 07:29 → 不发
        assertFalse(ScheduledMessageLogic.isDue(msg(time = "07:30"), 7 * 60 + 29, today))
        // 07:30 → 发
        assertTrue(ScheduledMessageLogic.isDue(msg(time = "07:30"), 7 * 60 + 30, today))
        // 09:00（闹钟被系统推迟过）→ 仍然要发，不能因为"正好错过"就永远不发
        assertTrue(ScheduledMessageLogic.isDue(msg(time = "07:30"), 9 * 60, today))
    }

    @Test
    fun `到点判定：当天发过就不再发、关掉的永不发、时间非法不发`() {
        val today = 20_000L
        assertFalse(ScheduledMessageLogic.isDue(msg(lastSentDay = today), 9 * 60, today))
        assertFalse(ScheduledMessageLogic.isDue(msg(enabled = false), 9 * 60, today))
        assertFalse(ScheduledMessageLogic.isDue(msg(time = "25:00"), 23 * 60, today))
        // 昨天发过 → 今天照发
        assertTrue(ScheduledMessageLogic.isDue(msg(lastSentDay = today - 1), 9 * 60, today))
    }
}
