package com.menghuan.app

import com.menghuan.app.ui.data.DayCount
import com.menghuan.app.ui.data.habitStreak
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** 打卡连续天数（纯函数）：宽恕规则——今天还没打卡不断签，从昨天往回数 */
class HabitStreakTest {

    private val today = LocalDate.of(2026, 9, 30)
    private fun days(vararg dones: Int): List<DayCount> =
        dones.mapIndexed { index, done -> DayCount(today.minusDays((dones.size - 1 - index).toLong()), done) }

    @Test
    fun `全部未打卡为0`() {
        assertEquals(0, habitStreak(days(0, 0, 0, 0, 0, 0, 0)))
    }

    @Test
    fun `连续N天含今天`() {
        assertEquals(3, habitStreak(days(0, 0, 0, 0, 1, 1, 1)))
    }

    @Test
    fun `今天未打卡不断签_从昨天起数`() {
        // 昨天前连续 2 天，今天还没打卡：streak 仍算 2（宽恕规则）
        assertEquals(2, habitStreak(days(0, 0, 0, 0, 1, 1, 0)))
    }

    @Test
    fun `中断即清零重计`() {
        // 前半周连打但中间断了一天，只剩昨天连续 1 天
        assertEquals(2, habitStreak(days(1, 1, 1, 0, 1, 1, 0)))
    }

    @Test
    fun `全勤7天`() {
        assertEquals(7, habitStreak(days(1, 1, 1, 1, 1, 1, 1)))
    }
}
