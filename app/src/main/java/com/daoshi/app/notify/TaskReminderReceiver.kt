package com.daoshi.app.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.daoshi.app.MainActivity
import com.daoshi.app.MentorApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

/** 到点弹通知督促打卡（通知点击拉起 App） */
class TaskReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(AlarmScheduler.EXTRA_TASK_ID, 0)
        val title = intent.getStringExtra(AlarmScheduler.EXTRA_TITLE) ?: "任务提醒"

        val manager = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return // 未授权通知：静默放弃（权限在设置提醒时申请）
        }
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "任务提醒",
                    NotificationManager.IMPORTANCE_HIGH,
                )
            )
        }
        val openApp = PendingIntent.getActivity(
            context,
            taskId.toInt(),
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("该打卡了")
            .setContentText(title)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        manager.notify(taskId.toInt(), notification)
    }

    private companion object {
        const val CHANNEL_ID = "task_reminder"
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
                app.db.taskDao().observeAll().first()
                    .filter { !it.isDone && (it.remindAt ?: 0L) > now }
                    .forEach { AlarmScheduler.schedule(context, it.id, it.title, it.remindAt!!) }
            } finally {
                pending.finish()
            }
        }
    }
}
