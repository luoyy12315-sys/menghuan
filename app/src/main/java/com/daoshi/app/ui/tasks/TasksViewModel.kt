package com.daoshi.app.ui.tasks

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.daoshi.app.MentorApp
import com.daoshi.app.data.db.AppDatabase
import com.daoshi.app.data.model.TaskItem
import com.daoshi.app.notify.AlarmScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 任务页 VM：任务清单 Room 化（此前为内存假数据）。
 * 保存/勾选/删除同步调度通知；未完成任务的未来提醒在取消勾选时重排。
 */
class TasksViewModel(
    private val db: AppDatabase,
    private val appContext: Context,
) : ViewModel() {

    val tasks: StateFlow<List<TaskItem>> = db.taskDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addTask(title: String, note: String, dueAt: Long, remindAt: Long?) =
        viewModelScope.launch {
            val id = db.taskDao().upsert(
                TaskItem(title = title, note = note, dueAt = dueAt, remindAt = remindAt)
            )
            remindAt?.let { AlarmScheduler.schedule(appContext, id, title, it) }
        }

    fun toggle(task: TaskItem) = viewModelScope.launch {
        val updated = task.copy(isDone = !task.isDone)
        db.taskDao().update(updated)
        val remindAt = updated.remindAt
        if (updated.isDone) {
            AlarmScheduler.cancel(appContext, task.id, task.title)
        } else if (remindAt != null && remindAt > System.currentTimeMillis()) {
            // 取消勾选且提醒仍在未来：重新挂上闹钟
            AlarmScheduler.schedule(appContext, task.id, updated.title, remindAt)
        }
    }

    fun delete(task: TaskItem) = viewModelScope.launch {
        db.taskDao().delete(task.id)
        AlarmScheduler.cancel(appContext, task.id, task.title)
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MentorApp
                TasksViewModel(app.db, app.applicationContext)
            }
        }
    }
}
