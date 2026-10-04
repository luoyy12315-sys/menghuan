package com.menghuan.app

import androidx.test.core.app.ApplicationProvider
import com.menghuan.app.data.TaskActions
import com.menghuan.app.data.model.TaskItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * 任务勾选动作（任务页与桌面小组件共用的 TaskActions）：
 * toggle 翻转完成态 + 写/删 task_completions + 打卡回执进主导师会话；再 toggle 完整回退。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = MenghuanApp::class, sdk = [34])
class TaskActionsToggleTest {

    @Test
    fun `勾选再取消完整回退`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MenghuanApp>()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val todayEpochDay = LocalDate.now().toEpochDay()

        // 每日任务（种子竞态无关：直接插，不依赖 seedIfEmpty）
        val taskId = app.db.taskDao().upsert(TaskItem(title = "背 20 个单词", repeatDaily = true))

        // 勾选：完成态 + 完成记录 + 打卡回执
        TaskActions.toggle(context, taskId)
        val done = app.db.taskDao().observeAll().first().first { it.id == taskId }
        assertEquals(true, done.isDone)
        assertEquals(todayEpochDay, done.completionDate)
        assertEquals(1, app.db.taskCompletionDao().observeAll().first().count { it.taskId == taskId && it.day == todayEpochDay })
        val receipts = app.db.messageDao().getAfterId(com.menghuan.app.data.MENTOR_PERSONA_ID, 0L)
        assertTrue(receipts.any { it.content.contains("已完成打卡：背 20 个单词") })

        // 取消：完成态回退 + 完成记录删除 + 回执不撤回（历史事实保留）
        TaskActions.toggle(context, taskId)
        val undone = app.db.taskDao().observeAll().first().first { it.id == taskId }
        assertEquals(false, undone.isDone)
        assertEquals(0, undone.completionDate)
        assertEquals(0, app.db.taskCompletionDao().observeAll().first().count { it.taskId == taskId && it.day == todayEpochDay })
        assertTrue(app.db.messageDao().getAfterId(com.menghuan.app.data.MENTOR_PERSONA_ID, 0L).isNotEmpty())
    }

    @Test
    fun `不存在的任务静默跳过`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        TaskActions.toggle(context, 99999L) // 不抛出即通过
        assertTrue(true)
    }
}
