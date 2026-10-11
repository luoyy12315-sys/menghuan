package com.menghuan.app.data

import java.time.Instant
import java.time.ZoneId

/**
 * 「AI 打电话」的纯逻辑（v0.53.5）。
 *
 * 这里只做**不需要 Context、不需要数据库**的判断与解析，所以能整块丢进 JVM 单测：
 * 时间是参数不是系统时钟，区间比较也全是纯算术。
 *
 * 现存的触发路：
 * ① 定时电话：用户自己排的「几点打」（闹钟到点直接打，判定只有条目自己的启用状态）；
 * ② 主动电话：并入主动关心的随机时刻（v0.94，判定在 ProactiveChat.decideAction）；
 * ③ 口头约定：用户在聊天里说"待会打给我" → AI 输出 `[打电话:30]` → [parseWantedCallAt]。
 *
 * v0.94 删掉的：`canCallBySchedule` / `canCallBySmart`（"每天检查一次 + 几天没聊"那套），
 * 与它一起下线的还有 MAX_PER_DAY（每日次数改成按角色的「每天最多几次」，
 * 见 SettingsRepository.proactiveCallLimit）。
 */
object AiCallLogic {

    /** 静默时段：22:30 – 08:00 一律不打 */
    private const val QUIET_START_MIN = 22 * 60 + 30
    private const val QUIET_END_MIN = 8 * 60

    /** 口头约定标记（与 `[生图:…]` / `[操作手机:…]` 同一套写法） */
    const val MARK_START = "[打电话:"
    const val MARK_END = "]"

    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** 是否落在静默时段。跨零点所以用 or（22:30 之后 或 08:00 之前） */
    fun inQuietHours(minuteOfDay: Int): Boolean =
        if (QUIET_START_MIN <= QUIET_END_MIN) {
            minuteOfDay in QUIET_START_MIN until QUIET_END_MIN
        } else {
            minuteOfDay >= QUIET_START_MIN || minuteOfDay < QUIET_END_MIN
        }

    /**
     * 解析 AI 回复里的口头约定标记，返回**该打电话的时刻**（毫秒）；无标记/解析失败返回 null。
     *
     * 两种写法（提示词会把这两种教给模型）：
     * - `[打电话:30]`   —— 30 分钟后（1~1440）；
     * - `[打电话:21:30]` —— 当天 21:30，**已经过了就顺延到明天**。
     */
    fun parseWantedCallAt(
        raw: String,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? {
        val start = raw.indexOf(MARK_START)
        if (start < 0) return null
        val end = raw.indexOf(MARK_END, start + MARK_START.length)
        if (end < 0) return null
        val body = raw.substring(start + MARK_START.length, end).trim()
        if (body.isEmpty()) return null

        if (body.contains(':')) {
            val parts = body.split(':')
            if (parts.size != 2) return null
            val h = parts[0].trim().toIntOrNull() ?: return null
            val m = parts[1].trim().toIntOrNull() ?: return null
            if (h !in 0..23 || m !in 0..59) return null
            // v0.79.0：「今天」必须**从 `now` 推**，不许读系统时钟 —— 本类开头的约定就是
            // "时间是参数不是系统时钟"。这里以前用 `LocalDate.now(zone)`，跟基准脱钩：
            // 单测给 2026-10-05 的 now、实现却按**运行当天**的日期算，于是"21:30"被排到几天后，
            // 一条用例因此红了好几个版本（既有历史红之一）。
            // 生产上 `now` 就是 System.currentTimeMillis()，所以用户没感知，
            // 但函数不再是纯函数、测试也没法钉它 —— 修掉。
            val base = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val at = base.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
            return if (at <= now) at + DAY_MS else at
        }

        val minutes = body.toIntOrNull() ?: return null
        if (minutes !in 0..1440) return null
        // v0.53.8：0 = **立刻**。给 10 秒缓冲（排闹钟本身要时间，"马上"不该被拖到一分钟）
        if (minutes == 0) return now + 10_000L
        return now + minutes * 60_000L
    }

    /** 从回复里剥掉这个标记（它只是给 App 的信号，不该出现在气泡文字里） */
    fun stripMarker(raw: String): String {
        val start = raw.indexOf(MARK_START)
        if (start < 0) return raw.trim()
        val end = raw.indexOf(MARK_END, start + MARK_START.length)
        val text = if (end < 0) {
            raw.substring(0, start)
        } else {
            raw.substring(0, start) + raw.substring(end + MARK_END.length)
        }
        return text.trim()
    }

    /**
     * 排闹钟前的兜底：太近（不足 1 分钟）或太远（超过 24 小时）都不认，
     * 免得模型胡写一个 3 天后的时间把闹钟排到天边。
     */
    fun normalizeWantedAt(at: Long?, now: Long): Long? {
        if (at == null) return null
        val delta = at - now
        // v0.53.8：下限从 1 分钟放宽到 5 秒 —— 用户说"立刻"，排的就是十几秒后，
        // 卡 1 分钟会把"立刻"整个挡掉、什么都不发生（用户反馈"我叫他立刻打，没打过来"）。
        if (delta < 5_000L || delta > DAY_MS) return null
        return at
    }

    /** `HH:mm` → 当天分钟数；解析不出来返回 null（设置页存的就是这个格式） */
    fun minuteOfDayOf(hhmm: String): Int? {
        val parts = hhmm.split(':')
        if (parts.size != 2) return null
        val h = parts[0].trim().toIntOrNull() ?: return null
        val m = parts[1].trim().toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    /** 时间戳 → 当天分钟数（判定静默时段用） */
    fun minuteOfDayAt(now: Long, zone: ZoneId = ZoneId.systemDefault()): Int {
        val t = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalTime()
        return t.hour * 60 + t.minute
    }
}
