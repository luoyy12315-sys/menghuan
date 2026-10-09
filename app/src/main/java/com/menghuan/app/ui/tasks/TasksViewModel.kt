package com.menghuan.app.ui.tasks

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.menghuan.app.MenghuanApp
import com.menghuan.app.data.db.AppDatabase
import com.menghuan.app.data.model.ChatMessage
import com.menghuan.app.data.model.TaskItem
import com.menghuan.app.notify.AlarmScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 任务页 VM：任务清单 Room 化。
 * 保存/勾选/删除同步调度通知；未完成任务的未来提醒在取消勾选时重排。
 * 每日任务（repeatDaily）：完成按 completionDate 判定并写 task_completions 记录，
 * 提醒走每日重复闹钟；勾选完成时向主人设会话写入打卡回执（用户消息）。
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
            com.menghuan.app.widget.TodayWidgetRefresher.refresh(appContext)
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
            com.menghuan.app.widget.TodayWidgetRefresher.refresh(appContext)
        }
    }

    /** 勾选/取消勾选：完整动作在 TaskActions（任务页与小组件共用） */
    fun toggle(task: TaskItem) = viewModelScope.launch {
        com.menghuan.app.data.TaskActions.toggleTask(appContext, task)
    }

    /** 标为失败（v0.74.0 长按菜单）：完整动作在 TaskActions */
    fun markFailed(task: TaskItem) = viewModelScope.launch {
        com.menghuan.app.data.TaskActions.markFailed(appContext, task.id)
    }

    /** 取消失败，回到未处理（v0.74.0 长按菜单） */
    fun clearFailed(task: TaskItem) = viewModelScope.launch {
        com.menghuan.app.data.TaskActions.clearFailed(appContext, task.id)
    }

    fun delete(task: TaskItem) = viewModelScope.launch {
        runCatching {
            db.taskDao().delete(task.id)
            db.taskCompletionDao().deleteAllForTask(task.id)
            AlarmScheduler.cancel(appContext, task.id, task.title)
            com.menghuan.app.widget.TodayWidgetRefresher.refresh(appContext)
        }
    }

    /** 自动执行摘要写入主人设会话（进入 LLM 历史，下轮对话可感知） */
    fun writeAgentSummary(content: String) = viewModelScope.launch {
        runCatching {
            db.messageDao().insert(
                ChatMessage(conversationId = com.menghuan.app.data.MENTOR_PERSONA_ID, isFromUser = true, content = content)
            )
        }
    }

    // ===== 「隐藏已完成」筛选（页面级视图状态，不落库） =====

    private val _hideDone = MutableStateFlow(false)
    val hideDone: StateFlow<Boolean> = _hideDone.asStateFlow()

    fun toggleHideDone() {
        _hideDone.value = !_hideDone.value
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MenghuanApp
                TasksViewModel(app.db, app.applicationContext)
            }
        }
    }
}
