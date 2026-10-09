package com.menghuan.app

import androidx.test.core.app.ApplicationProvider
import com.menghuan.app.data.MENTOR_PERSONA_ID
import com.menghuan.app.data.TaskActions
import com.menghuan.app.data.model.TaskItem
import com.menghuan.app.data.model.isEffectivelyDone
import com.menghuan.app.data.model.isEffectivelyFailed
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
 * toggle 翻转完成态 + 写/删 task_completions；再 toggle 完整回退。
 *
 * ⚠️ 打卡回执**不写**（这条历史红 2026-10-09 修）：
 * v0.56.11 用户点名「打卡不再给应用助手发回执」，而 `MENTOR_PERSONA_ID` 就是内置
 * 「应用助手」（id=1，`isBuiltIn = true`）——所以回执在正常装机里**永远不会落库**。
 * 用例原先断言"回执进主人设会话"，那正是被点掉的行为，测试过期了。
 * 「让 AI 知道任务做完了」这件事 v0.77.0 起由 `ChatViewModel.taskStatusBlock()`
 * 承担：每轮把今日任务状态直接喂进**每个**会话的上下文（比一条只落在单个会话的回执更可靠）。
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

        // 勾选：完成态 + 完成记录
        TaskActions.toggle(context, taskId)
        val done = app.db.taskDao().observeAll().first().first { it.id == taskId }
        assertEquals(true, done.isDone)
        assertEquals(todayEpochDay, done.completionDate)
        assertEquals(1, app.db.taskCompletionDao().observeAll().first().count { it.taskId == taskId && it.day == todayEpochDay })

        // 回执不写（见类注释）
        assertTrue(
            "勾选不该给内置助手写回执",
            app.db.messageDao().getAfterId(MENTOR_PERSONA_ID, 0L).none { it.content.contains("已完成打卡") },
        )

        // 取消：完成态回退 + 完成记录删除
        TaskActions.toggle(context, taskId)
        val undone = app.db.taskDao().observeAll().first().first { it.id == taskId }
        assertEquals(false, undone.isDone)
        assertEquals(0, undone.completionDate)
        assertEquals(0, app.db.taskCompletionDao().observeAll().first().count { it.taskId == taskId && it.day == todayEpochDay })
        assertTrue(
            "取消勾选同样不写回执",
            app.db.messageDao().getAfterId(MENTOR_PERSONA_ID, 0L).none { it.content.contains("已完成打卡") },
        )
    }

    @Test
    fun `不存在的任务静默跳过`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        TaskActions.toggle(context, 99999L) // 不抛出即通过
        assertTrue(true)
    }

    // ===== 失败终态（v0.74.0）=====

    @Test
    fun `标为失败落库 且清掉完成与打卡记录`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MenghuanApp>()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val todayEpochDay = LocalDate.now().toEpochDay()
        val taskId = app.db.taskDao().upsert(TaskItem(title = "跑五公里"))

        // 先勾完成（会写完成记录），再改成失败 —— 完成记录必须被撤回
        TaskActions.markFailed(context, taskId)
        val failed = app.db.taskDao().observeAll().first().first { it.id == taskId }
        assertEquals("失败态要落库", true, failed.isFailed)
        assertEquals("失败与完成互斥", false, failed.isDone)
        assertEquals("completionDate 记当天（每日任务靠它跨天重置）", todayEpochDay, failed.completionDate)
        assertEquals(
            "失败不许留下当天打卡记录",
            0,
            app.db.taskCompletionDao().observeAll().first().count { it.taskId == taskId && it.day == todayEpochDay },
        )
    }

    @Test
    fun `取消失败回到未处理`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MenghuanApp>()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val taskId = app.db.taskDao().upsert(TaskItem(title = "写周报"))

        TaskActions.markFailed(context, taskId)
        TaskActions.clearFailed(context, taskId)
        val back = app.db.taskDao().observeAll().first().first { it.id == taskId }
        assertEquals(false, back.isFailed)
        assertEquals(false, back.isDone)
        assertEquals(0L, back.completionDate)
    }

    @Test
    fun `失败态上勾选 转成完成并清掉失败`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MenghuanApp>()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val taskId = app.db.taskDao().upsert(TaskItem(title = "交房租"))

        TaskActions.markFailed(context, taskId)
        TaskActions.toggle(context, taskId) // 在失败态上点勾选 = "我其实做完了"
        val done = app.db.taskDao().observeAll().first().first { it.id == taskId }
        assertEquals("要转成完成", true, done.isDone)
        assertEquals("失败必须被清掉", false, done.isFailed)
    }

    @Test
    fun `失败计入完成率分母`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MenghuanApp>()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // 三条今日任务：一条完成、一条失败、一条未处理 → 分母 3、分子 1
        val a = app.db.taskDao().upsert(TaskItem(title = "完成的一项"))
        val b = app.db.taskDao().upsert(TaskItem(title = "失败的一项"))
        val c = app.db.taskDao().upsert(TaskItem(title = "没动的一项"))
        TaskActions.toggle(context, a)
        TaskActions.markFailed(context, b)
        val today = app.db.taskDao().observeAll().first()
            .filter { it.id == a || it.id == b || it.id == c }
        val todayEpochDay = LocalDate.now().toEpochDay()
        val done = today.count { it.isEffectivelyDone(todayEpochDay) }
        val failed = today.count { it.isEffectivelyFailed(todayEpochDay) }
        assertEquals("分子只算完成", 1, done)
        assertEquals("失败单独计数", 1, failed)
        assertEquals("分母含失败", 3, today.size)
    }
}
