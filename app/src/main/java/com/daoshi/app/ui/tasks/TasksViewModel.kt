package com.daoshi.app.ui.tasks

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.daoshi.app.MentorApp
import com.daoshi.app.data.db.AppDatabase
import com.daoshi.app.data.model.ChatMessage
import com.daoshi.app.data.model.TaskCompletion
import com.daoshi.app.data.model.TaskItem
import com.daoshi.app.data.model.isEffectivelyDone
import com.daoshi.app.notify.AlarmScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 任务页 VM：任务清单 Room 化。
 * 保存/勾选/删除同步调度通知；未完成任务的未来提醒在取消勾选时重排。
 * 每日任务（repeatDaily）：完成按 completionDate 判定并写 task_completions 记录，
 * 提醒走每日重复闹钟；勾选完成时向主导师会话写入打卡回执（用户消息）。
 */
class TasksViewModel(
    private val db: AppDatabase,
    private val appContext: Context,
) : ViewModel() {

    val tasks: StateFlow<List<TaskItem>> = db.taskDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addTask(
        title: String,
        note: String,
        dueAt: Long,
        remindAt: Long?,
        repeatDaily: Boolean = false,
    ) = viewModelScope.launch {
        runCatching {
            val id = db.taskDao().upsert(
                TaskItem(
                    title = title,
                    note = note,
                    dueAt = dueAt,
                    remindAt = remindAt,
                    repeatDaily = repeatDaily,
                )
            )
            when {
                repeatDaily && remindAt != null ->
                    AlarmScheduler.scheduleDaily(appContext, id, title, remindAt)
                remindAt != null -> AlarmScheduler.schedule(appContext, id, title, remindAt)
            }
            com.daoshi.app.widget.TodayWidgetRefresher.refresh(appContext)
        }
    }

    /** 编辑任务：标题/备注/日期/提醒/每日重复均可改；每日任务提醒按每日重排 */
    fun updateTask(task: TaskItem) = viewModelScope.launch {
        runCatching {
            db.taskDao().update(task)
            val remindAt = task.remindAt
            when {
                task.repeatDaily && remindAt != null ->
                    AlarmScheduler.scheduleDaily(appContext, task.id, task.title, remindAt)
                !task.isDone && remindAt != null && remindAt > System.currentTimeMillis() ->
                    AlarmScheduler.schedule(appContext, task.id, task.title, remindAt)
                else -> AlarmScheduler.cancel(appContext, task.id, task.title)
            }
            com.daoshi.app.widget.TodayWidgetRefresher.refresh(appContext)
        }
    }

    /** 勾选/取消勾选：完成状态按"当天有效完成"翻转，并同步完成记录与闹钟 */
    fun toggle(task: TaskItem) = viewModelScope.launch {
        val nowDone = !task.isEffectivelyDone()
        val todayEpochDay = LocalDate.now().toEpochDay()
        val updated = task.copy(
            isDone = nowDone,
            completionDate = if (nowDone) todayEpochDay else 0L,
        )
        val saved = runCatching { db.taskDao().update(updated) }
        if (saved.isFailure) return@launch
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
                AlarmScheduler.scheduleDaily(appContext, task.id, updated.title, remindAt)
            nowDone -> AlarmScheduler.cancel(appContext, task.id, task.title)
            remindAt != null && remindAt > System.currentTimeMillis() ->
                // 取消勾选且提醒仍在未来：重新挂上闹钟
                AlarmScheduler.schedule(appContext, task.id, updated.title, remindAt)
        }
        if (nowDone) {
            // 打卡回执：写进主导师（种子 id=1）会话作为用户消息，进入 LLM 历史上下文
            runCatching {
                db.messageDao().insert(
                    ChatMessage(
                        conversationId = MENTOR_PERSONA_ID,
                        isFromUser = true,
                        content = "✅ 已完成打卡：${task.title}",
                    )
                )
            }
        }
        com.daoshi.app.widget.TodayWidgetRefresher.refresh(appContext)
    }

    fun delete(task: TaskItem) = viewModelScope.launch {
        runCatching {
            db.taskDao().delete(task.id)
            db.taskCompletionDao().deleteAllForTask(task.id)
            AlarmScheduler.cancel(appContext, task.id, task.title)
            com.daoshi.app.widget.TodayWidgetRefresher.refresh(appContext)
        }
    }

    companion object {
        /** 主导师 = 种子人设「阿布」，打卡回执固定写入该会话 */
        const val MENTOR_PERSONA_ID = 1L

        val Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MentorApp
                TasksViewModel(app.db, app.applicationContext)
            }
        }
    }
}
