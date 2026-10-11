package com.menghuan.app

import com.menghuan.app.data.AiCallLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * AI 主动打电话的纯逻辑单测（v0.53.5）。
 *
 * 全是纯函数（时间一律作为参数传进来），所以不需要 Robolectric、秒级跑完。
 * v0.94：「每天检查一次 + 几天没聊」那套判定（canCallBySchedule / canCallBySmart / shouldRing）
 * 已随主动电话并入主动关心整条删除，对应用例一并删除；剩下的是仍活着的三块：
 * **静默时段跨零点**、**口头约定标记的两种写法**、**规则编解码兼容**（老数据要读得动）。
 */
class AiCallLogicTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun tsOf(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        java.time.LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    // ===== 静默时段（22:30–08:00，跨零点）=====

    @Test
    fun `静默时段跨零点判定正确`() {
        assertFalse(AiCallLogic.inQuietHours(9 * 60))            // 09:00 可打
        assertFalse(AiCallLogic.inQuietHours(22 * 60 + 29))      // 22:29 还能打
        assertTrue(AiCallLogic.inQuietHours(22 * 60 + 30))       // 22:30 起静默
        assertTrue(AiCallLogic.inQuietHours(23 * 60 + 59))
        assertTrue(AiCallLogic.inQuietHours(0))                  // 午夜静默
        assertTrue(AiCallLogic.inQuietHours(7 * 60 + 59))        // 07:59 仍静默
        assertFalse(AiCallLogic.inQuietHours(8 * 60))            // 08:00 解禁
    }

    // ===== 口头约定标记 =====

    @Test
    fun `口头约定：分钟数写法解析成 now 加 N 分钟`() {
        val now = tsOf(2026, 10, 5, 10, 0)
        val at = AiCallLogic.parseWantedCallAt("好，我待会打给你。\n[打电话:30]", now, zone)
        assertEquals(now + 30 * 60_000L, at)
    }

    @Test
    fun `口头约定：钟点写法当天已过就顺延到明天`() {
        val morning = tsOf(2026, 10, 5, 10, 0)
        assertEquals(tsOf(2026, 10, 5, 21, 30), AiCallLogic.parseWantedCallAt("[打电话:21:30]", morning, zone))
        // 晚上 23:00 说"九点打给我" → 指明天早上九点
        val night = tsOf(2026, 10, 5, 23, 0)
        assertEquals(tsOf(2026, 10, 6, 9, 0), AiCallLogic.parseWantedCallAt("[打电话:9:00]", night, zone))
    }

    @Test
    fun `口头约定：越界与残缺都不认`() {
        val now = tsOf(2026, 10, 5, 10, 0)
        assertNull(AiCallLogic.parseWantedCallAt("没有标记", now, zone))
        assertNull(AiCallLogic.parseWantedCallAt("[打电话:abc]", now, zone))
        assertNull(AiCallLogic.parseWantedCallAt("[打电话:2000]", now, zone))  // 超过 24h
        assertNull(AiCallLogic.parseWantedCallAt("[打电话:25:00]", now, zone)) // 非法钟点
        assertNull(AiCallLogic.parseWantedCallAt("[打电话:30", now, zone))     // 没有收尾
        // v0.53.7：**0 = 立刻** —— 排 10 秒后。
        // （用户"我叫他立刻打，没打过来"踩过：以前 0 被判非法 → 静默什么都不发生）
        assertEquals(now + 10_000L, AiCallLogic.parseWantedCallAt("[打电话:0]", now, zone))
    }

    @Test
    fun `口头约定标记会被剥掉且不影响正文`() {
        assertEquals("好，我待会打给你。", AiCallLogic.stripMarker("好，我待会打给你。\n[打电话:30]"))
        assertEquals("前半后半", AiCallLogic.stripMarker("前半[打电话:30]后半"))
        assertEquals("没有标记", AiCallLogic.stripMarker("没有标记"))
    }

    @Test
    fun `排钟兜底：太近或太远都不认`() {
        val now = tsOf(2026, 10, 5, 10, 0)
        assertNull(AiCallLogic.normalizeWantedAt(null, now))
        assertNull(AiCallLogic.normalizeWantedAt(now + 2_000L, now))                  // 2 秒后太近（下限 5 秒）
        assertNull(AiCallLogic.normalizeWantedAt(now + 25L * 60 * 60 * 1000, now))    // 25 小时后太远
        // v0.53.7：下限放宽到 5 秒 —— "立刻"排的就是 10 秒后，卡 1 分钟会把它整个挡掉
        assertEquals(now + 10_000L, AiCallLogic.normalizeWantedAt(now + 10_000L, now))
        assertEquals(now + 30 * 60_000L, AiCallLogic.normalizeWantedAt(now + 30 * 60_000L, now))
    }

    // ===== 设置里的时刻字符串 =====

    @Test
    fun `时刻字符串解析`() {
        assertEquals(21 * 60, AiCallLogic.minuteOfDayOf("21:00"))
        assertEquals(8 * 60 + 5, AiCallLogic.minuteOfDayOf(" 8:05 "))
        assertNull(AiCallLogic.minuteOfDayOf("24:00"))
        assertNull(AiCallLogic.minuteOfDayOf("九点"))
        assertEquals(21 * 60 + 30, AiCallLogic.minuteOfDayAt(tsOf(2026, 10, 5, 21, 30), zone))
    }

    // ===== 按人设的规则（v0.53.6：每人设一套、互不影响）=====

    private fun rule(
        personaId: Long = 1L,
        enabled: Boolean = true,
        idleDays: Int = 3,
        lastCallDay: Long = 0L,
    ) = com.menghuan.app.data.AiCallRule(
        personaId = personaId,
        enabled = enabled,
        time = "21:00",
        idleDays = idleDays,
        lastCallDay = lastCallDay,
    )

    @Test
    fun `规则的编解码往返与脏数据容错`() {
        val list = listOf(rule(1L), rule(2L, enabled = false, idleDays = 7))
        assertEquals(list, com.menghuan.app.data.AiCallRuleCodec.decode(com.menghuan.app.data.AiCallRuleCodec.encode(list)))
        assertEquals(emptyList<com.menghuan.app.data.AiCallRule>(), com.menghuan.app.data.AiCallRuleCodec.decode("垃圾"))
        // personaId 缺失/为 0 的条目跳过
        assertEquals(
            emptyList<com.menghuan.app.data.AiCallRule>(),
            com.menghuan.app.data.AiCallRuleCodec.decode("""[{"enabled":true}]"""),
        )
        // idleDays 越界会被夹回 1..30
        assertEquals(
            30,
            com.menghuan.app.data.AiCallRuleCodec.decode("""[{"personaId":9,"idleDays":999}]""")[0].idleDays,
        )
    }
}
