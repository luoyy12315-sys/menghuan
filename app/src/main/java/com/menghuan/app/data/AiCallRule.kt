package com.menghuan.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * **一个人设**的「打电话」规则（v0.53.6：从"全局一套"改成"每人设一套"）。
 *
 * ⚠️ **v0.94：这套规则的语义已经大部分下线** ——
 * 「几天没聊就主动打」（[time]/[idleDays]/[lastCallDay]）与「定时电话总开关」（[scheduledEnabled]）
 * 都不再被读取（主动电话并入主动关心的随机时刻，定时电话只看条目自己的启用状态）。
 * 字段保留只为**兼容老数据**（codec 要能读懂 v0.93 及更早写出的 JSON）。
 *
 * 还活着的用途只有两个：
 * - [enabled]：老装机「主动电话」开关的存量 —— 新键没写过时，
 *   由 `SettingsRepository.proactiveCallEnabled` **读取时推断**成新的「主动打电话」开关；
 * - [instruction]：老装机在弹层里写过的「接通后说什么」，主动/定时电话的开场白继续生效。
 */
data class AiCallRule(
    val personaId: Long,
    val enabled: Boolean = false,
    val time: String = DEFAULT_AI_CALL_TIME,
    val idleDays: Int = 3,
    val lastCallDay: Long = 0L,
    /** 打电话时要说的内容（开场白要求）；空 = 交给模型自己按情境组织 */
    val instruction: String = "",
    /**
     * 「定时电话」总开关（v0.56.6）。
     *
     * 用户点名的结构：电话设置里保留 [enabled]（**允许主动打电话**）作为"TA 能不能主动打"的总闸，
     * 另加一个**定时电话**开关 —— 打开后由用户按时间点一条条添加定时电话
     * （几点拨打 + 接通后说什么），**不再用 [idleDays] 那种"多久没聊才打"的触发方式**。
     * [time]/[idleDays] 两个字段保留只为兼容老数据，新界面不再编辑它们、也不再据此排闹钟。
     */
    val scheduledEnabled: Boolean = false,
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
                    .put("lastCallDay", r.lastCallDay)
                    .put("instruction", r.instruction)
                    .put("scheduledEnabled", r.scheduledEnabled),
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
                        instruction = o.optString("instruction").trim(),
                        scheduledEnabled = o.optBoolean("scheduledEnabled", false),
                    ),
                )
            }
        }
    }
}

// v0.94：AiCallRuleLogic.shouldRing（"每天检查一次 + 几天没聊"判定）已随那条触发链整条删除 ——
// 主动电话并入主动关心的随机时刻（ProactiveChat.decideAction），不再有这套判定。
