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
 * 每日建议（v0.42.0）：每天早上 07:30 生成今天的建议。
 * 生成经 LLM（API 未配置则静默跳过）；**只静默落库，不发通知** —— 用户第二天打开数据页就看得到。
 *
 * ⚠️ 这里原本还有一段「生成完发一条通知」，挂在 `dailySuggestionNotifyEnabled` 开关后面。
 * 但那个开关**从 v0.42.0 引入起就没有任何 UI 写入口**（setter 零调用，默认 `false` 永久生效），
 * 所以那段通知代码**一次都没执行过**。已整段删除（连同 `CHANNEL_ID_DAILY` / `DAILY_NOTIF_ID`），
 * 与「洞察不发通知」的处理保持一致。
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
                runCatching { app.dailySuggestionGenerator.ensureToday(today) }
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * AI 洞察（v0.88.0）：日 / 周 / 月三条子链共用这一个接收器，靠 extra 里的 period 区分。
 *
 * 到点做两件事，顺序不能反：
 * 1. **先自排下一次**（三重自愈之一，另两处是 App 启动与 BootReceiver）——
 *    生成可能因为没配 API / 网络失败而静默返回，但调度绝不能因为一次失败就断掉；
 * 2. 再跑对应周期的生成（幂等：同周期同归属日只生成一次）。
 *
 * 洞察**不发通知**（用户没要求，且 23:00 打扰没必要）——它落库，用户第二天打开数据页就看到。
 * 唯一的可见反馈是次日 07:30 的每日建议会引用它。
 */
class InsightReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val period = intent.getStringExtra(EXTRA_PERIOD) ?: PERIOD_DAILY
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as MenghuanApp
                // 先排下一次：用"未来的下一个"重排同一颗，今天这颗已被消费
                runCatching {
                    val at = when (period) {
                        PERIOD_WEEKLY -> AlarmScheduler.nextWeeklyInsightAtForTest()
                        PERIOD_MONTHLY -> AlarmScheduler.nextMonthlyInsightAtForTest()
                        else -> AlarmScheduler.nextDailyInsightAtForTest()
                    }
                    AlarmScheduler.scheduleInsight(context, period, at)
                }
                val today = java.time.LocalDate.now()
                runCatching {
                    when (period) {
                        PERIOD_WEEKLY -> app.insightEngine.ensureWeekly(today)
                        PERIOD_MONTHLY -> app.insightEngine.ensureMonthly(today)
                        else -> app.insightEngine.ensureDaily(today)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_PERIOD = "insightPeriod"
        const val PERIOD_DAILY = "DAILY"
        const val PERIOD_WEEKLY = "WEEKLY"
        const val PERIOD_MONTHLY = "MONTHLY"

        /** 顺序即 requestCode 偏移，别调（见 AlarmScheduler.insightPendingIntent） */
        val ALL_PERIODS = listOf(PERIOD_DAILY, PERIOD_WEEKLY, PERIOD_MONTHLY)
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
                // 人设主动关心重排（v0.56.8：在用户设的时间段里随机取时刻）；开关在 Receiver 内复查
                com.menghuan.app.data.ProactiveChat.scheduleNext(app.db, app.settingsRepository, context)
                // 每日建议闹钟重排（补生成由到点 Receiver 做，这里不主动发请求，避免开机风暴）
                AlarmScheduler.scheduleDailySuggestion(context)
                // AI 洞察三条链重排（v0.88.0）；同样只排钟，不在这儿发请求
                AlarmScheduler.scheduleInsights(context)
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
 * 人设主动关心（v0.38.0；v0.56.8 改成随机时刻，**第二批起每个角色一颗闹钟**）。
 *
 * 闹钟上带着人设 id（[AlarmScheduler.EXTRA_PROACTIVE_PERSONA]）→ 只服务这一个角色；
 * 老版本那条全局闹钟没有 id（-1）→ 退回"所有开了开关的角色里挑一个"的兼容分支。
 * 开关在 data/ProactiveChat.maybeGreet 内复查（默认关：关了就静默，闹钟照排不丢调度），
 * 当天计划条数发完了同样静默。
 */
class ProactiveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val personaId = intent.getLongExtra(AlarmScheduler.EXTRA_PROACTIVE_PERSONA, -1L)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as MenghuanApp
                // 下一次先排上（三重自愈：App 启动 / BootReceiver / 到点自排）；
                // v0.56.8：下一次同样是**随机时刻**（今天窗口没用完就还在今天）
                runCatching {
                    if (personaId >= 0L) ProactiveChat.schedulePersona(app.settingsRepository, context, personaId)
                    else ProactiveChat.scheduleNext(app.db, app.settingsRepository, context)
                }
                runCatching { ProactiveChat.maybeGreet(app.db, app.settingsRepository, context, personaId) }
            } finally {
                pending.finish()
            }
        }
    }
}
