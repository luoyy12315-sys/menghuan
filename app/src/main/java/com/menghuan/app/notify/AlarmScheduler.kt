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

    /**
     * 人设主动关心：v0.56.7 及更早那条**全局**闹钟（固定 20:30，v0.38.0）的 requestCode。
     * v0.56.8 第二阶段起改成**每个角色一个闹钟**（[PROACTIVE_PERSONA_BASE]），
     * 这个码只留着**取消**升级前排上的那条旧闹钟（[cancelProactive]），不再排新的。
     */
    private const val PROACTIVE_REQUEST_CODE = 2_000_000_005

    /**
     * 人设主动关心（v0.56.8 第二批，**按角色**）：requestCode = base + personaId % 10000，
     * 每个角色一个闹钟 —— 因为**时间段与条数都是每个角色一套**，A 的窗口和 B 的窗口互不干扰。
     */
    private const val PROACTIVE_PERSONA_BASE = 2_000_300

    /** ProactiveReceiver 的 extra：这一枪打给谁（人设 id）；-1 = 老版本那条全局闹钟 */
    const val EXTRA_PROACTIVE_PERSONA = "proactivePersonaId"

    /** 每日建议闹钟专用 requestCode（每天早上 07:30，v0.42.0） */
    private const val DAILY_SUGGESTION_REQUEST_CODE = 2_000_000_006

    /** AI 洞察闹钟基底 requestCode（v0.88.0：日/周/月三条，+ index） */
    private const val INSIGHT_REQUEST_BASE = 2_000_000_010

    /** 洞察统一触发时刻：23:00（用户选的「排法 A」——晚上跑洞察，次日 07:30 的建议读昨天的洞察） */
    private const val DAY_INSIGHT_HOUR = 23

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
     * 人设主动关心（v0.38.0；v0.56.8 改成"在用户设的时间段里随机取时刻"；
     * **第二批起每个角色一个闹钟**，因为时间段/条数都是按角色一套）。
     *
     * 不再固定 20:30：由 [com.menghuan.app.data.ProactiveChat.nextFireAt] 在这个角色自己的
     * `[startHhmm]–[endHhmm]` 窗口里随机挑一个时刻排上；到点由 ProactiveReceiver 发完再随机排下一次
     * （今天窗口过完就排明天）。调用方用 `ProactiveChat.scheduleNext(settings, context)` 即可，
     * 不必自己读配置。
     */
    fun scheduleProactiveForPersona(context: Context, personaId: Long, startHhmm: String, endHhmm: String) {
        val at = com.menghuan.app.data.ProactiveChat
            .nextFireAt(System.currentTimeMillis(), startHhmm, endHhmm)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = proactivePendingIntent(context, personaId)
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** 关掉某个角色的主动关心（或角色被删）→ 把它那颗闹钟摘掉 */
    fun cancelProactiveForPersona(context: Context, personaId: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(proactivePendingIntent(context, personaId))
    }

    /**
     * 清掉 v0.56.7 及更早那条**全局**闹钟（固定 20:30）。
     * 它没带人设 id，到点会走 ProactiveReceiver 的兼容分支；排新的之前先摘掉，避免重复触发。
     */
    fun cancelProactive(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(legacyProactivePendingIntent(context))
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

    /**
     * AI 洞察闹钟（v0.88.0）：日 / 周 / 月三条链**共用时刻 23:00**，
     * 靠 requestCode 区分，各自的 PendingIntent 上带周期 extra。
     *
     * 为什么要分三颗而不是一颗「每晚跑一遍、自己判断今天是不是周日/月末」：
     * 三条链的**幂等键是"周期 + 归属日"**（见 [InsightEngine.ensureWeekly] 等），
     * 分开排在补跑场景下更干净 —— 用户周三才发现 App 没开过，日洞察补周三日那一颗，
     * 周洞察补上一颗周日那一颗，互不牵扯。
     *
     * 排的是"下一个还没到的 23:00"：日链 = 今晚/明晚；周链 = 下个周日；
     * 月链 = 下个月末。到点由 [InsightReceiver] 跑完再自排下一次。
     */
    fun scheduleInsights(context: Context) {
        scheduleInsight(context, InsightReceiver.PERIOD_DAILY, nextDailyInsightAt())
        scheduleInsight(context, InsightReceiver.PERIOD_WEEKLY, nextWeeklyInsightAt())
        scheduleInsight(context, InsightReceiver.PERIOD_MONTHLY, nextMonthlyInsightAt())
    }

    /** 单独重排某一条（Receiver 到点自排下一次用） */
    fun scheduleInsight(context: Context, period: String, at: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = insightPendingIntent(context, period)
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun insightPendingIntent(context: Context, period: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            INSIGHT_REQUEST_BASE + InsightReceiver.ALL_PERIODS.indexOf(period),
            Intent(context, InsightReceiver::class.java)
                .putExtra(InsightReceiver.EXTRA_PERIOD, period),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** 下一个 23:00（今天还没到就是今天） */
    private fun nextDailyInsightAt(zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long {
        var next = java.time.LocalDate.now(zone).atTime(DAY_INSIGHT_HOUR, 0)
        if (next.atZone(zone).toInstant().toEpochMilli() <= System.currentTimeMillis()) {
            next = next.plusDays(1)
        }
        return next.atZone(zone).toInstant().toEpochMilli()
    }

    /** [InsightReceiver] 到点自排下一次用（同一个算法，别在两处各写一遍） */
    fun nextDailyInsightAtForTest(): Long = nextDailyInsightAt()

    fun nextWeeklyInsightAtForTest(): Long = nextWeeklyInsightAt()

    fun nextMonthlyInsightAtForTest(): Long = nextMonthlyInsightAt()

    /**
     * 下一个周日的 23:00。
     *
     * 注意：「今天就是周日、但 23:00 还没到」时应该排今天 —— 否则这一周就被跳过了。
     * 所以判据是"周日 && (今天还没到 23:00 || 今天不是周日)"，写成循环更不容易出错。
     */
    private fun nextWeeklyInsightAt(zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long {
        val now = System.currentTimeMillis()
        var day = java.time.LocalDate.now(zone)
        // 最多找 8 天必然撞上（一周），这里用 while 不用取模，逻辑一眼可验
        var guard = 0
        while (guard++ < 8) {
            if (day.dayOfWeek == java.time.DayOfWeek.SUNDAY) {
                val at = day.atTime(DAY_INSIGHT_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
                if (at > now) return at
            }
            day = day.plusDays(1)
        }
        return day.atTime(DAY_INSIGHT_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
    }

    /** 下个月末（当月最后一天）的 23:00；今天是月末且未到 23:00 → 就是今天 */
    private fun nextMonthlyInsightAt(zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long {
        val now = System.currentTimeMillis()
        var last = java.time.LocalDate.now(zone).withDayOfMonth(
            java.time.LocalDate.now(zone).lengthOfMonth()
        )
        if (last.atTime(DAY_INSIGHT_HOUR, 0).atZone(zone).toInstant().toEpochMilli() <= now) {
            val nextMonth = last.plusMonths(1)
            last = nextMonth.withDayOfMonth(nextMonth.lengthOfMonth())
        }
        return last.atTime(DAY_INSIGHT_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
    }

    /** 每个角色一颗：requestCode 按人设 id 拉开，PendingIntent 上带 id，到点知道该发给谁 */
    private fun proactivePendingIntent(context: Context, personaId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            PROACTIVE_PERSONA_BASE + (personaId % 10_000L).toInt(),
            Intent(context, ProactiveReceiver::class.java)
                .putExtra(EXTRA_PROACTIVE_PERSONA, personaId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** 旧版全局闹钟（无 id）：只在 [cancelProactive] 里用来取消，不排新的 */
    private fun legacyProactivePendingIntent(context: Context): PendingIntent =
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
