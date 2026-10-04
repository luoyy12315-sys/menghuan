package com.menghuan.app

import com.menghuan.app.data.model.TaskItem
import com.menghuan.app.notify.DailySummaryReceiver
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** 每日总结统计口径（纯 JVM）：今日 = 每日任务 + 今日到期（含无日期）基础任务 */
class DailySummaryTest {

    @Test
    fun `今日完成统计含每日任务与今日到期基础任务`() {
        val todayEpochDay = LocalDate.now().toEpochDay()
        val todayStart = LocalDate.now()
            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val tasks = listOf(
            TaskItem(id = 1, title = "每日阅读", repeatDaily = true, isDone = true, completionDate = todayEpochDay),
            TaskItem(id = 2, title = "每日运动", repeatDaily = true), // 今天未完成
            TaskItem(id = 3, title = "今天到期", dueAt = todayStart, isDone = true),
            TaskItem(id = 4, title = "明天到期", dueAt = todayStart + 86_400_000),
            TaskItem(id = 5, title = "昨天的逾期", dueAt = todayStart - 86_400_000),
        )
        val (done, total) = DailySummaryReceiver.summarizeToday(tasks)
        assertEquals("总数 = 2 条每日任务 + 1 条今日到期", 3, total)
        assertEquals("完成 = 已打卡每日任务 + 已完成今日任务", 2, done)
    }

    @Test
    fun `每日任务昨天完成今天未打卡则不计完成`() {
        val yesterday = LocalDate.now().minusDays(1).toEpochDay()
        val tasks = listOf(
            TaskItem(id = 1, title = "每日阅读", repeatDaily = true, isDone = true, completionDate = yesterday),
        )
        val (done, total) = DailySummaryReceiver.summarizeToday(tasks)
        assertEquals(1, total)
        assertEquals(0, done)
    }
}
