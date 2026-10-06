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
        val nowDone = !task.isEffectivelyDone()
        val todayEpochDay = LocalDate.now().toEpochDay()
        val updated = task.copy(
            isDone = nowDone,
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
            // 手册的那个会话里刷"已完成打卡"；其他人设会话维持原行为。
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
}
