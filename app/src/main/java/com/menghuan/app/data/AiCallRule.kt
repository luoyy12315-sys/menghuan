package com.menghuan.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * **一个人设**的「主动打电话」规则（v0.53.6：从"全局一套"改成"每人设一套"）。
 *
 * 三条配置项一一对应用户的要求：
 * - [enabled]：是否允许 TA 主动给我打电话；
 * - [time]：每天几点**检查**一次（`HH:mm`）；
 * - [idleDays]：距上次聊天多久没交流才打（天）。
 *
 * 三者是**与**的关系（[time] 到点 → 看 [idleDays] 够不够 → 打），
 * 这样"每天固定时间"不会被理解成"每天准点骚扰"：
 * 到点时如果你刚聊过（没到 [idleDays]），TA 就不打。
 * 想"到点必打"的话把 [idleDays] 设成 1 天即可（只要不是今天聊过就会打）。
 *
 * [lastCallDay] 是**该人设各自**的"今天打过没"记录（epochDay），
 * 所以人设之间天然互不影响：小美今天打过，不影响教练今晚也打一次。
 */
data class AiCallRule(
    val personaId: Long,
    val enabled: Boolean = false,
    val time: String = DEFAULT_AI_CALL_TIME,
    val idleDays: Int = 3,
    val lastCallDay: Long = 0L,
)

/** 规则的编解码（纯函数；脏数据跳过而不是崩，与本项目其它 JSON 存储同一套姿势） */
object AiCallRuleCodec {

    fun encode(list: List<AiCallRule>): String {
        val array = JSONArray()
        list.forEach { r ->
            array.put(
                JSONObject()
                    .put("personaId", r.personaId)
                    .put("enabled", r.enabled)
                    .put("time", r.time)
                    .put("idleDays", r.idleDays)
                    .put("lastCallDay", r.lastCallDay),
            )
        }
        return array.toString()
    }

    fun decode(raw: String): List<AiCallRule> {
        if (raw.isBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optLong("personaId", 0L)
                if (id <= 0L) continue
                add(
                    AiCallRule(
                        personaId = id,
                        enabled = o.optBoolean("enabled", false),
                        time = o.optString("time").trim().ifEmpty { DEFAULT_AI_CALL_TIME },
                        idleDays = o.optInt("idleDays", 3).coerceIn(1, 30),
                        lastCallDay = o.optLong("lastCallDay", 0L),
                    ),
                )
            }
        }
    }
}

/** 按人设规则判定"这一通该不该打"（纯函数，时间与"上次聊天"都作参数传入，便于单测） */
object AiCallRuleLogic {

    /**
     * 到点后的判定：
     * ① 这个规则得是开着的；
     * ② 今天还没为**这个人设**打过；
     * ③ 不在静默时段（22:30–08:00）；
     * ④ 距上次跟 TA 聊天 ≥ [AiCallRule.idleDays] 天（从没聊过也算）。
     *
     * 注意 ④ 用的是**该人设会话**的最后一条消息时间，
     * 所以"A 人设刚聊过、B 人设三天没聊"时只有 B 会打 —— 这就是"互不影响"的核心。
     */
    fun shouldRing(
        rule: AiCallRule,
        minuteOfDay: Int,
        today: Long,
        lastChatAt: Long,
        now: Long,
    ): Boolean {
        if (!rule.enabled) return false
        if (rule.lastCallDay == today) return false
        if (AiCallLogic.inQuietHours(minuteOfDay)) return false
        if (lastChatAt <= 0L) return true
        val idleMs = rule.idleDays.toLong() * 24 * 60 * 60 * 1000L
        return now - lastChatAt >= idleMs
    }
}
