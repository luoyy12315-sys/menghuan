package com.menghuan.app

import com.menghuan.app.data.model.TaskItem
import com.menghuan.app.data.model.isEffectivelyDone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 每日任务跨天重置语义（纯 JVM）：完成记录过期即回未完成；基础任务不受影响 */
class TasksDailyResetTest {

    private val today: Long = LocalDate.now().toEpochDay()
    private val yesterday: Long = LocalDate.now().minusDays(1).toEpochDay()

    @Test
    fun `每日任务当天完成视为已完成`() {
        val task = TaskItem(id = 1, title = "每日阅读", repeatDaily = true, isDone = true, completionDate = today)
        assertTrue(task.isEffectivelyDone())
    }

    @Test
    fun `每日任务完成记录过期自动回到未完成`() {
        val task = TaskItem(id = 1, title = "每日阅读", repeatDaily = true, isDone = true, completionDate = yesterday)
        assertFalse("昨天的完成不该延续到今天", task.isEffectivelyDone())
    }

    @Test
    fun `每日任务 isDone=false 一律未完成`() {
        val task = TaskItem(id = 1, title = "每日阅读", repeatDaily = true, isDone = false, completionDate = today)
        assertFalse(task.isEffectivelyDone())
    }

    @Test
    fun `基础任务只看 isDone 不受 completionDate 影响`() {
        val done = TaskItem(id = 2, title = "交稿", isDone = true, completionDate = 0)
        val undone = TaskItem(id = 3, title = "跑步", isDone = false)
        assertTrue(done.isEffectivelyDone())
        assertFalse(undone.isEffectivelyDone())
        assertEquals(0, undone.completionDate)
    }
}
