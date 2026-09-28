package com.daoshi.app.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.daoshi.app.MainActivity
import com.daoshi.app.MentorApp
import com.daoshi.app.data.model.isEffectivelyDone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 到点弹通知督促打卡（点击直达任务页） */
class TaskReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(AlarmScheduler.EXTRA_TASK_ID, 0)
        val title = intent.getStringExtra(AlarmScheduler.EXTRA_TITLE) ?: "任务提醒"

        // 通知开关/权限在 IO 线程读（DataStore 为 suspend API），goAsync 撑住广播生命周期
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as MentorApp
                val enabled = runCatching { app.settingsRepository.notificationsEnabled.first() }
                    .getOrDefault(true)
                if (!enabled) return@launch // 设置里关掉了提醒：闹钟仍到点，但不打扰

                // 每日任务：无论本次是否提示，先排明天的闹钟（逐日滚动）；当天已完成则不再打扰
                val task = runCatching { app.db.taskDao().observeAll().first().firstOrNull { it.id == taskId } }
                    .getOrNull()
                if (task?.repeatDaily == true) {
                    task.remindAt?.let {
                        AlarmScheduler.scheduleDaily(context, taskId, task.title, it)
                    }
                    if (task.isEffectivelyDone()) return@launch
                }

                val manager = NotificationManagerCompat.from(context)
                if (Build.VERSION.SDK_INT >= 33 &&
                    context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    return@launch // 未授权通知：静默放弃（权限在设置提醒时申请）
                }
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "任务提醒", NotificationManager.IMPORTANCE_HIGH)
                )

                // 点击直达任务页：deep link 由 NavHost 解析（Action VIEW + 显式组件，无需 manifest 配置）
                val openTasks = PendingIntent.getActivity(
                    context,
                    (taskId % Int.MAX_VALUE).toInt(),
                    Intent(Intent.ACTION_VIEW, Uri.parse("daoshi://tasks"), context, MainActivity::class.java)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE,
                )
                val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("该打卡了")
                    .setContentText(title)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setAutoCancel(true)
                    .setContentIntent(openTasks)
                    // 稍后提醒：取消本条通知，10 分钟后重排同一条闹钟（打卡场景刚需）
                    .addAction(
                        0,
                        "10 分钟后再提醒",
                        PendingIntent.getBroadcast(
                            context,
                            ((taskId % Int.MAX_VALUE) + 1).toInt(),
                            Intent(context, TaskSnoozeReceiver::class.java)
                                .putExtra(AlarmScheduler.EXTRA_TASK_ID, taskId)
                                .putExtra(AlarmScheduler.EXTRA_TITLE, title),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                    .build()
                manager.notify((taskId % Int.MAX_VALUE).toInt(), notification)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val CHANNEL_ID = "task_reminder"
    }
}

/** 通知「稍后提醒」action：取消当前通知，10 分钟后重排同一条提醒闹钟 */
class TaskSnoozeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(AlarmScheduler.EXTRA_TASK_ID, 0)
        val title = intent.getStringExtra(AlarmScheduler.EXTRA_TITLE) ?: "任务提醒"
        NotificationManagerCompat.from(context).cancel((taskId % Int.MAX_VALUE).toInt())
        AlarmScheduler.schedule(
            context,
            taskId,
            title,
            System.currentTimeMillis() + 10 * 60_000L,
        )
    }
}

/** 重启 / 覆盖安装后重排所有未来的提醒（闹钟不跨重启、不跨安装包更新存活） */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as MentorApp
                val now = System.currentTimeMillis()
                val tasks = app.db.taskDao().observeAll().first()
                // 每日任务跨天重置：完成记录不是今天的一律回到未完成
                val todayEpochDay = java.time.LocalDate.now().toEpochDay()
                tasks
                    .filter { it.repeatDaily && it.isDone && it.completionDate != todayEpochDay }
                    .forEach { task ->
                        runCatching {
                            app.db.taskDao().update(task.copy(isDone = false, completionDate = 0))
                        }
                    }
                // 重排闹钟：每日任务按每日重复滚动；基础任务只排未来提醒
                tasks.forEach { task ->
                    when {
                        task.repeatDaily && task.remindAt != null ->
                            AlarmScheduler.scheduleDaily(context, task.id, task.title, task.remindAt!!)
                        !task.isDone && (task.remindAt ?: 0L) > now ->
                            AlarmScheduler.schedule(context, task.id, task.title, task.remindAt!!)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
