package com.menghuan.app.data

import android.content.Context
import com.menghuan.app.MenghuanApp
import com.menghuan.app.data.model.ChatMessage
import com.menghuan.app.data.model.TaskCompletion
import com.menghuan.app.data.model.TaskItem
import com.menghuan.app.data.model.isEffectivelyDone
import com.menghuan.app.notify.AlarmScheduler
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/** 打卡回执固定写入的主人设会话（主人设（当前 activeProfile 关联的第一个非应用助手人设）） */
const val MENTOR_PERSONA_ID = 1L

/**
 * 任务勾选动作（任务页与桌面小组件共用）：
 * 完成/未完成按「当天有效完成」翻转，同步完成记录（task_completions）、
 * 闹钟重排与打卡回执（主人设会话用户消息，进入 LLM 历史）。
 */
object TaskActions {

    /** 按任务 id 勾选/取消（任务不存在则静默） */
    suspend fun toggle(context: Context, taskId: Long) {
        val app = context.applicationContext as MenghuanApp
        val task = runCatching {
            app.db.taskDao().observeAll().first().firstOrNull { it.id == taskId }
        }.getOrNull() ?: return
        toggleTask(context, task)
    }

    /** 勾选/取消勾选给定任务 */
    suspend fun toggleTask(context: Context, task: TaskItem) {
        val app = context.applicationContext as MenghuanApp
        val db = app.db
        // ⚠️ 失败态点勾选 = **把它改成完成**（不是取消完成）：失败与完成是互斥的两个终态，
        // 用户在一条"失败"上点圆圈，意图一定是"我其实做完了"，不是"撤回到没发生"。
        // 撤回失败是长按菜单里单独那条「取消失败」。
        val nowDone = !task.isEffectivelyDone()
        val todayEpochDay = LocalDate.now().toEpochDay()
        val updated = task.copy(
            isDone = nowDone,
            isFailed = false, // 完成与失败互斥：转成完成时一定清掉失败
            completionDate = if (nowDone) todayEpochDay else 0L,
        )
        val saved = runCatching { db.taskDao().update(updated) }
        if (saved.isFailure) return
        runCatching {
            if (nowDone) {
                db.taskCompletionDao().insert(TaskCompletion(taskId = task.id, day = todayEpochDay))
            } else {
                db.taskCompletionDao().delete(taskId = task.id, day = todayEpochDay)
            }
        }
        val remindAt = updated.remindAt
        when {
            // 每日任务：提醒逐日滚动保持不变（当天已完成时 Receiver 会静默）
            updated.repeatDaily && remindAt != null ->
                AlarmScheduler.scheduleDaily(context, task.id, updated.title, remindAt)
            nowDone -> AlarmScheduler.cancel(context, task.id, task.title)
            remindAt != null && remindAt > System.currentTimeMillis() ->
                // 取消勾选且提醒仍在未来：重新挂上闹钟
                AlarmScheduler.schedule(context, task.id, updated.title, remindAt)
        }
        if (nowDone) {
            // 打卡回执：写进主人设会话作为用户消息，进入 LLM 历史上下文。
            // v0.56.11（用户点名）：**内置「应用助手」不收打卡回执**——完成任务不该在使用
            // 手册的那个会话里刷"已完成打卡"。
            // ⚠️ 说句实话：`MENTOR_PERSONA_ID` 就是内置助手（id=1），所以正常装机里
            // 这段**一次也不会落库**，如今只是"万一 1 号位是用户自己的人设"时的兜底。
            // 「让 AI 知道任务做完了」自 v0.77.0 起由 `ChatViewModel.taskStatusBlock()`
            // 承担（每轮把今日任务状态喂进**每个**会话），不再依赖这条只落在单个会话的回执。
            runCatching {
                val target = db.personaDao().getById(MENTOR_PERSONA_ID)
                if (target != null && !target.isBuiltIn) {
                    db.messageDao().insert(
                        ChatMessage(
                            conversationId = MENTOR_PERSONA_ID,
                            isFromUser = true,
                            content = "✅ 已完成打卡：${task.title}",
                        )
                    )
                }
            }
        }
        com.menghuan.app.widget.TodayWidgetRefresher.refresh(context)
    }

    /**
     * 把任务标为**失败**（v0.74.0，长按菜单）。
     *
     * 失败是正式终态，所以这里做四件事，与勾选完成**对称但有两处刻意不同**：
     * 1. `isDone = false` + `isFailed = true`（互斥），`completionDate` 记当天
     *    —— 每日任务靠它跨天重置（第二天早上这条又回到"待处理"，不是永久失败）；
     * 2. **删掉当天可能已有的完成记录**（task_completions）—— 先勾了完成再改成失败，
     *    热力/连续天数必须跟着撤回，否则"失败"那天还挂着打卡；
     * 3. **取消闹钟**（失败的任务不该再提醒）；
     * 4. **不写打卡回执** —— 那句话是"已完成"，失败不写进 LLM 历史（正是"失败"的反面）。
     */
    suspend fun markFailed(context: Context, taskId: Long) {
        val app = context.applicationContext as MenghuanApp
        val db = app.db
        val task = runCatching {
            db.taskDao().observeAll().first().firstOrNull { it.id == taskId }
        }.getOrNull() ?: return
        val todayEpochDay = LocalDate.now().toEpochDay()
        val updated = task.copy(isDone = false, isFailed = true, completionDate = todayEpochDay)
        if (runCatching { db.taskDao().update(updated) }.isFailure) return
        // 撤回当天可能已有的完成记录（失败与打卡不能同时存在）
        runCatching { db.taskCompletionDao().delete(taskId = taskId, day = todayEpochDay) }
        runCatching { AlarmScheduler.cancel(context, task.id, task.title) }
        com.menghuan.app.widget.TodayWidgetRefresher.refresh(context)
    }

    /**
     * 取消失败，回到"未处理"（v0.74.0，长按菜单）。
     *
     * 与 [toggleTask] 的"取消完成"分开：那条会把任务翻成完成，
     * 而用户想撤回失败时意图是"当没发生过"——恢复成待办，并把未来提醒重新挂上。
     */
    suspend fun clearFailed(context: Context, taskId: Long) {
        val app = context.applicationContext as MenghuanApp
        val db = app.db
        val task = runCatching {
            db.taskDao().observeAll().first().firstOrNull { it.id == taskId }
        }.getOrNull() ?: return
        val updated = task.copy(isFailed = false, isDone = false, completionDate = 0L)
        if (runCatching { db.taskDao().update(updated) }.isFailure) return
        val remindAt = updated.remindAt
        when {
            updated.repeatDaily && remindAt != null ->
                AlarmScheduler.scheduleDaily(context, task.id, updated.title, remindAt)
            remindAt != null && remindAt > System.currentTimeMillis() ->
                AlarmScheduler.schedule(context, task.id, updated.title, remindAt)
        }
        com.menghuan.app.widget.TodayWidgetRefresher.refresh(context)
    }
}
