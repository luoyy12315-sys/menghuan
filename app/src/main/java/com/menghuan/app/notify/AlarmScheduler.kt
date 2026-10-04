package com.menghuan.app.notify

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

    /** 每日总结闹钟专用 requestCode（避免与 taskId 撞号） */
    private const val SUMMARY_REQUEST_CODE = 2_000_000_001

    /** AI 周报闹钟专用 requestCode */
    private const val WEEKLY_REPORT_REQUEST_CODE = 2_000_000_002

    /** 人设主动关心闹钟专用 requestCode（每晚 20:30，v0.38.0） */
    private const val PROACTIVE_REQUEST_CODE = 2_000_000_005

    /** 每日建议闹钟专用 requestCode（每天早上 07:30，v0.42.0） */
    private const val DAILY_SUGGESTION_REQUEST_CODE = 2_000_000_006

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

    /**
     * 每日重复提醒：remindAt 只取其中的时刻，排下一次出现（今天的已过则排明天）。
     * 到点后由 TaskReminderReceiver 再次调用本方法实现逐日滚动。
     */
    fun scheduleDaily(context: Context, taskId: Long, title: String, remindAt: Long) {
        val zone = java.time.ZoneId.systemDefault()
        val time = java.time.Instant.ofEpochMilli(remindAt).atZone(zone).toLocalTime()
        var next = java.time.LocalDate.now(zone).atTime(time)
        if (next.atZone(zone).toInstant().toEpochMilli() <= System.currentTimeMillis()) {
            next = next.plusDays(1)
        }
        schedule(context, taskId, title, next.atZone(zone).toInstant().toEpochMilli())
    }

    /** 每日总结（固定 21:30）：requestCode 与任务闹钟隔离，开关即时生效 */
    fun scheduleDailySummary(context: Context, at: Long? = null) {
        val zone = java.time.ZoneId.systemDefault()
        var next = java.time.LocalDate.now(zone).atTime(21, 30)
        at?.let {
            next = java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalTime().let { t ->
                java.time.LocalDate.now(zone).atTime(t)
            }
        }
        if (next.atZone(zone).toInstant().toEpochMilli() <= System.currentTimeMillis()) {
            next = next.plusDays(1)
        }
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = summaryPendingIntent(context)
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.atZone(zone).toInstant().toEpochMilli(), pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.atZone(zone).toInstant().toEpochMilli(), pi)
        }
    }

    fun cancelDailySummary(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(summaryPendingIntent(context))
    }

    private fun summaryPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            SUMMARY_REQUEST_CODE,
            Intent(context, DailySummaryReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * AI 周报（每周日 21:30）：对齐下一个周日，到点由 WeeklyReportReceiver 自排下周。
     * 生成在 Receiver 内做 API 开关检查（未配置则静默），无用户开关。
     */
    fun scheduleWeeklyReport(context: Context, at: Long? = null) {
        val zone = java.time.ZoneId.systemDefault()
        var next = java.time.LocalDate.now(zone)
            .with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.SUNDAY))
            .atTime(21, 30)
        at?.let {
            next = java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalTime().let { t ->
                java.time.LocalDate.now(zone).atTime(t)
            }
        }
        if (next.atZone(zone).toInstant().toEpochMilli() <= System.currentTimeMillis()) {
            next = next.plusWeeks(1)
        }
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = weeklyReportPendingIntent(context)
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.atZone(zone).toInstant().toEpochMilli(), pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.atZone(zone).toInstant().toEpochMilli(), pi)
        }
    }

    private fun weeklyReportPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            WEEKLY_REPORT_REQUEST_CODE,
            Intent(context, WeeklyReportReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** 人设主动关心（每晚 20:30，默认关）：到点由 ProactiveReceiver 自排明天，当天已发过会静默 */
    fun scheduleProactive(context: Context) {
        val zone = java.time.ZoneId.systemDefault()
        var next = java.time.LocalDate.now(zone).atTime(20, 30)
        if (next.atZone(zone).toInstant().toEpochMilli() <= System.currentTimeMillis()) {
            next = next.plusDays(1)
        }
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = proactivePendingIntent(context)
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.atZone(zone).toInstant().toEpochMilli(), pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.atZone(zone).toInstant().toEpochMilli(), pi)
        }
    }

    fun cancelProactive(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(proactivePendingIntent(context))
    }

    /**
     * 每日建议（每天早上 07:30，v0.42.0）：到点由 DailySuggestionReceiver 自排明天。
     * 生成在 Receiver 内做 API 开关检查（未配置则静默），无用户开关。
     */
    fun scheduleDailySuggestion(context: Context) {
        val zone = java.time.ZoneId.systemDefault()
        var next = java.time.LocalDate.now(zone).atTime(7, 30)
        if (next.atZone(zone).toInstant().toEpochMilli() <= System.currentTimeMillis()) {
            next = next.plusDays(1)
        }
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = dailySuggestionPendingIntent(context)
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.atZone(zone).toInstant().toEpochMilli(), pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.atZone(zone).toInstant().toEpochMilli(), pi)
        }
    }

    private fun dailySuggestionPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            DAILY_SUGGESTION_REQUEST_CODE,
            Intent(context, DailySuggestionReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun proactivePendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            PROACTIVE_REQUEST_CODE,
            Intent(context, ProactiveReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

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
