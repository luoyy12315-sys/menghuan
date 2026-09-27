package com.daoshi.app.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 任务提醒调度：AlarmManager 精确闹钟（ Android 12+ 无「闹钟权限」时退化为非精确）。
 * PendingIntent 以 taskId 作 requestCode，天然唯一，便于取消。
 */
object AlarmScheduler {

    const val EXTRA_TASK_ID = "taskId"
    const val EXTRA_TITLE = "title"

    fun schedule(context: Context, taskId: Long, title: String, remindAt: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, taskId, title)
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, remindAt, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, remindAt, pi)
        }
    }

    fun cancel(context: Context, taskId: Long, title: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context, taskId, title))
    }

    private fun pendingIntent(context: Context, taskId: Long, title: String): PendingIntent {
        val intent = Intent(context, TaskReminderReceiver::class.java)
            .putExtra(EXTRA_TASK_ID, taskId)
            .putExtra(EXTRA_TITLE, title)
        return PendingIntent.getBroadcast(
            context,
            taskId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
