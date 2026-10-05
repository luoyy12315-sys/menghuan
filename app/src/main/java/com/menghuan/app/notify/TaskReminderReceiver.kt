package com.menghuan.app.notify

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
import com.menghuan.app.MainActivity
import com.menghuan.app.MenghuanApp
import com.menghuan.app.data.ProactiveChat
import com.menghuan.app.data.model.isEffectivelyDone
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
                val app = context.applicationContext as MenghuanApp
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
                    Intent(Intent.ACTION_VIEW, Uri.parse("menghuan://open/tasks"), context, MainActivity::class.java)
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

/**
 * 每日总结（每晚 21:30，可关）：「今日完成 x/y」通知，点击直达任务页。
 * 今日任务 = 每日任务 + 今日到期的基础任务；完成判定与任务页同口径（isEffectivelyDone）。
 */
class DailySummaryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as MenghuanApp
                val enabled = runCatching { app.settingsRepository.notificationsEnabled.first() }.getOrDefault(true) &&
                    runCatching { app.settingsRepository.dailySummaryEnabled.first() }.getOrDefault(true)
                // 明天的总结无论如何先排上（关了开关就静默）
                AlarmScheduler.scheduleDailySummary(context)

                val (done, total) = summarizeToday(app.db.taskDao().observeAll().first())
                if (!enabled || total == 0) return@launch // 没有今日任务就不打扰

                val manager = NotificationManagerCompat.from(context)
                if (Build.VERSION.SDK_INT >= 33 &&
                    context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    return@launch
                }
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "每日总结", NotificationManager.IMPORTANCE_DEFAULT)
                )
                val openTasks = PendingIntent.getActivity(
                    context,
                    SUMMARY_NOTIF_ID,
                    Intent(Intent.ACTION_VIEW, Uri.parse("menghuan://open/tasks"), context, MainActivity::class.java)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE,
                )
                val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("今日完成 $done/$total")
                    .setContentText(if (done == total) "全部完成，稳。" else "还有 ${total - done} 项，收尾再休息。")
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setAutoCancel(true)
                    .setContentIntent(openTasks)
                    .build()
                manager.notify(SUMMARY_NOTIF_ID, notification)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "daily_summary"
        private const val SUMMARY_NOTIF_ID = 2_000_000_001

        /** 今日任务统计（纯函数）：每日任务 + 今日到期基础任务 → (完成数, 总数) */
        fun summarizeToday(tasks: List<com.menghuan.app.data.model.TaskItem>): Pair<Int, Int> {
            val todayEpochDay = java.time.LocalDate.now().toEpochDay()
            val todayStart = java.time.LocalDate.now()
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            val todayEnd = java.time.LocalDate.now().plusDays(1)
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            val todays = tasks.filter {
                it.repeatDaily || (it.dueAt == 0L || it.dueAt in todayStart until todayEnd)
            }
            val done = todays.count { it.isEffectivelyDone(todayEpochDay) }
            return done to todays.size
        }
    }
}

/**
 * AI 周报（每周日 21:30）：到点生成「本周」周报 + 静默补生成上周缺口（周末没开机）。
 * 生成经 LLM（API 未配置则静默跳过）；开关打开时新生成完毕发一条通知（点击直达数据页）。
 */
class WeeklyReportReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 到点自排下一个周日 21:30（三重自愈之一）
                AlarmScheduler.scheduleWeeklyReport(context)
                val app = context.applicationContext as MenghuanApp
                val zone = java.time.ZoneId.systemDefault()
                val monday = java.time.LocalDate.now(zone).with(java.time.DayOfWeek.MONDAY)
                var generated = 0
                // 本周（周日 21:30 = 即将收尾的一周）先生成，失败不阻断补生成
                if (runCatching { app.weeklyReportGenerator.generateForWeek(monday, zone) }.getOrNull() != null) {
                    generated++
                }
                // 上周若因没开机缺报 → 补生成
                if (app.weeklyReportGenerator.ensureReport(monday.minusDays(7), zone) != null) {
                    generated++
                }
                // 通知开关在 IO 线程复查（默认关：周报静默生成，数据页可看）
                if (generated == 0) return@launch
                val notifyEnabled = runCatching { app.settingsRepository.notificationsEnabled.first() }
                    .getOrDefault(false) &&
                    runCatching { app.settingsRepository.weeklyReportNotifyEnabled.first() }.getOrDefault(false)
                if (!notifyEnabled) return@launch

                val manager = NotificationManagerCompat.from(context)
                if (Build.VERSION.SDK_INT >= 33 &&
                    context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    return@launch
                }
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID_WEEKLY, "AI 周报", NotificationManager.IMPORTANCE_DEFAULT)
                )
                val openData = PendingIntent.getActivity(
                    context,
                    WEEKLY_NOTIF_ID,
                    Intent(Intent.ACTION_VIEW, Uri.parse("menghuan://open/data"), context, MainActivity::class.java)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE,
                )
                val notification = NotificationCompat.Builder(context, CHANNEL_ID_WEEKLY)
                    .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
                    .setContentTitle("AI 周报已生成")
                    .setContentText(if (generated > 1) "本周与上周各补了一期，点开看看" else "本周回顾写好了，点开看看")
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setAutoCancel(true)
                    .setContentIntent(openData)
                    .build()
                manager.notify(WEEKLY_NOTIF_ID, notification)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val CHANNEL_ID_WEEKLY = "weekly_report"
        private const val WEEKLY_NOTIF_ID = 2_000_000_003
    }
}

/**
 * 每日建议（v0.42.0）：每天早上 07:30 生成今天的建议。
 * 生成经 LLM（API 未配置则静默跳过）；开关打开时新生成完毕发一条通知（点击直达数据页）。
 */
class DailySuggestionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 到点自排明天（三重自愈之一）
                AlarmScheduler.scheduleDailySuggestion(context)
                val app = context.applicationContext as MenghuanApp
                val today = java.time.LocalDate.now()
                // 昨天若因没开机缺了建议 → 一并补上（保证历史不断档）
                runCatching { app.dailySuggestionGenerator.ensureToday(today.minusDays(1)) }
                val generated = runCatching { app.dailySuggestionGenerator.ensureToday(today) }.getOrNull()
                if (generated == null) return@launch

                val notifyEnabled = runCatching { app.settingsRepository.notificationsEnabled.first() }
                    .getOrDefault(false) &&
                    runCatching { app.settingsRepository.dailySuggestionNotifyEnabled.first() }.getOrDefault(false)
                if (!notifyEnabled) return@launch

                val manager = NotificationManagerCompat.from(context)
                if (Build.VERSION.SDK_INT >= 33 &&
                    context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    return@launch
                }
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID_DAILY, "每日建议", NotificationManager.IMPORTANCE_DEFAULT)
                )
                val openData = PendingIntent.getActivity(
                    context,
                    DAILY_NOTIF_ID,
                    Intent(Intent.ACTION_VIEW, Uri.parse("menghuan://open/data"), context, MainActivity::class.java)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE,
                )
                val notification = NotificationCompat.Builder(context, CHANNEL_ID_DAILY)
                    .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
                    .setContentTitle("今天的建议来了")
                    .setContentText(generated.title)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setAutoCancel(true)
                    .setContentIntent(openData)
                    .build()
                manager.notify(DAILY_NOTIF_ID, notification)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val CHANNEL_ID_DAILY = "daily_suggestion"
        private const val DAILY_NOTIF_ID = 2_000_000_007
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
                val app = context.applicationContext as MenghuanApp
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
                // 每日总结（21:30）重排；开关在 Receiver 内复查
                AlarmScheduler.scheduleDailySummary(context)
                // 人设主动关心（20:30）重排；开关在 Receiver 内复查（v0.38.0）
                AlarmScheduler.scheduleProactive(context)
                // AI 周报闹钟重排 + 上周缺报补生成（如周末没开机）
                AlarmScheduler.scheduleWeeklyReport(context)
                val monday = java.time.LocalDate.now().with(java.time.DayOfWeek.MONDAY)
                app.weeklyReportGenerator.ensureReport(monday.minusDays(7))
                // 每日建议闹钟重排（补生成由到点 Receiver 做，这里不主动发请求，避免开机风暴）
                AlarmScheduler.scheduleDailySuggestion(context)
                // AI 主动打电话（v0.53.5）：定时 + 智能检查两个闹钟一起重排（只排钟，不发请求）
                runCatching { com.menghuan.app.data.AiCall.rearm(app.settingsRepository, context) }
                // 定时主动消息（v0.53.5）：每条一个闹钟，全部重排
                runCatching {
                    app.settingsRepository.scheduledMessages.first()
                        .filter { it.enabled }
                        .forEach { AlarmScheduler.scheduleScheduledMessage(context, it.id, it.time) }
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * 人设主动关心（v0.38.0）：每晚 20:30 触发一次。
 * 开关在 data/ProactiveChat.maybeGreet 内复查（默认关：关了就静默，明天照排不丢调度），
 * 当天已发过（proactive_last_day）同样静默。
 */
class ProactiveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 明天的先排上（三重自愈：App 启动 / BootReceiver / 到点自排）
                AlarmScheduler.scheduleProactive(context)
                val app = context.applicationContext as MenghuanApp
                runCatching { ProactiveChat.maybeGreet(app.db, app.settingsRepository, context) }
            } finally {
                pending.finish()
            }
        }
    }
}
