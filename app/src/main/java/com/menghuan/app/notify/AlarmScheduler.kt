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

    /**
     * AI 主动打电话（v0.53.6 起**按人设独立**）：
     * requestCode = base + personaId % 10000，所以每个人设一个闹钟、互不干扰。
     */
    private const val AI_CALL_PERSONA_BASE = 2_000_100
    private const val AI_CALL_ONCE_BASE = 2_000_200

    /** AiCallReceiver 的 extra：这一枪打给谁（人设 id），以及是不是"口头约定"那一路 */
    const val EXTRA_AI_CALL_PERSONA = "aiCallPersonaId"
    const val EXTRA_AI_CALL_ONCE = "aiCallOnce"

    /** ScheduledMessageReceiver 的 extra：哪一条定时消息到点了（requestCode 用它的 hashCode） */
    const val EXTRA_SCHEDULED_ID = "scheduledMessageId"

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
     * AI 主动打电话（v0.53.6，**按人设独立**）：**每个人设一个闹钟**，在它自己设定的时刻触发；
     * 到点由 [AiCallReceiver] 按"那个人的规则"判定打不打，并自己排明天。
     */
    fun scheduleAiCallForPersona(context: Context, personaId: Long, hhmm: String) {
        val minuteOfDay = com.menghuan.app.data.AiCallLogic.minuteOfDayOf(hhmm) ?: return
        val zone = java.time.ZoneId.systemDefault()
        var next = java.time.LocalDate.now(zone).atTime(minuteOfDay / 60, minuteOfDay % 60)
        if (next.atZone(zone).toInstant().toEpochMilli() <= System.currentTimeMillis()) {
            next = next.plusDays(1)
        }
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = aiCallPendingIntent(context, personaId, once = false)
        val at = next.atZone(zone).toInstant().toEpochMilli()
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancelAiCallForPersona(context: Context, personaId: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(aiCallPendingIntent(context, personaId, once = false))
    }

    /** 口头约定那一路（同样**按人设**排）：用户在聊天里让 TA"待会打给我"，到点一次性触发 */
    fun scheduleAiCallOnce(context: Context, personaId: Long, at: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = aiCallPendingIntent(context, personaId, once = true)
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancelAiCallOnce(context: Context, personaId: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(aiCallPendingIntent(context, personaId, once = true))
    }

    private fun aiCallPendingIntent(context: Context, personaId: Long, once: Boolean): PendingIntent {
        val base = if (once) AI_CALL_ONCE_BASE else AI_CALL_PERSONA_BASE
        val requestCode = base + (personaId % 10_000L).toInt()
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, AiCallReceiver::class.java)
                .putExtra(EXTRA_AI_CALL_PERSONA, personaId)
                .putExtra(EXTRA_AI_CALL_ONCE, once),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 定时主动消息（v0.53.5）：**每条一个闹钟**（requestCode 用 id 的 hashCode），
     * 到点由 [ScheduledMessageReceiver] 自己排明天。多条并存时互不干扰。
     */
    fun scheduleScheduledMessage(context: Context, id: String, hhmm: String) {
        val minuteOfDay = com.menghuan.app.data.AiCallLogic.minuteOfDayOf(hhmm) ?: return
        val zone = java.time.ZoneId.systemDefault()
        var next = java.time.LocalDate.now(zone).atTime(minuteOfDay / 60, minuteOfDay % 60)
        if (next.atZone(zone).toInstant().toEpochMilli() <= System.currentTimeMillis()) {
            next = next.plusDays(1)
        }
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = scheduledMessagePendingIntent(context, id)
        val at = next.atZone(zone).toInstant().toEpochMilli()
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancelScheduledMessage(context: Context, id: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(scheduledMessagePendingIntent(context, id))
    }

    private fun scheduledMessagePendingIntent(context: Context, id: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            Intent(context, ScheduledMessageReceiver::class.java)
                .putExtra(EXTRA_SCHEDULED_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

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
