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
import com.daoshi.app.data.model.TaskItem
import com.daoshi.app.notify.AlarmScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 任务页 VM：任务清单 Room 化（此前为内存假数据）。
 * 保存/勾选/删除同步调度通知；未完成任务的未来提醒在取消勾选时重排。
 * 勾选完成时向主导师会话写入打卡回执（用户消息），下轮对话 LLM 即可感知执行情况。
 */
class TasksViewModel(
    private val db: AppDatabase,
    private val appContext: Context,
) : ViewModel() {

    val tasks: StateFlow<List<TaskItem>> = db.taskDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addTask(title: String, note: String, dueAt: Long, remindAt: Long?) =
        viewModelScope.launch {
            runCatching {
                val id = db.taskDao().upsert(
                    TaskItem(title = title, note = note, dueAt = dueAt, remindAt = remindAt)
                )
                remindAt?.let { AlarmScheduler.schedule(appContext, id, title, it) }
            }
        }

    /** 编辑任务：标题/备注/日期/提醒均可改，提醒在未来则重排闹钟 */
    fun updateTask(task: TaskItem) = viewModelScope.launch {
        runCatching {
            db.taskDao().update(task)
            val remindAt = task.remindAt
            if (!task.isDone && remindAt != null && remindAt > System.currentTimeMillis()) {
                AlarmScheduler.schedule(appContext, task.id, task.title, remindAt)
            } else {
                AlarmScheduler.cancel(appContext, task.id, task.title)
            }
        }
    }

    fun toggle(task: TaskItem) = viewModelScope.launch {
        val updated = task.copy(isDone = !task.isDone)
        val saved = runCatching { db.taskDao().update(updated) }
        if (saved.isFailure) return@launch
        val remindAt = updated.remindAt
        if (updated.isDone) {
            AlarmScheduler.cancel(appContext, task.id, task.title)
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
        } else if (remindAt != null && remindAt > System.currentTimeMillis()) {
            // 取消勾选且提醒仍在未来：重新挂上闹钟
            AlarmScheduler.schedule(appContext, task.id, updated.title, remindAt)
        }
    }

    fun delete(task: TaskItem) = viewModelScope.launch {
        runCatching {
            db.taskDao().delete(task.id)
            AlarmScheduler.cancel(appContext, task.id, task.title)
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
