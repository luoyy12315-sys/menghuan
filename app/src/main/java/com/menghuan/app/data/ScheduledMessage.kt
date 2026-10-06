package com.menghuan.app.data

import org.json.JSONArray
import org.json.JSONObject

/** 这条定时项是「发消息」还是「打电话」（v0.56.6）—— 两种共用一套存储、闹钟与「几点 + 说什么」表单 */
const val KIND_SCHEDULED_MESSAGE = "message"
const val KIND_SCHEDULED_CALL = "call"

/**
 * 一条「定时主动消息」（v0.53.5）：到点让**某个人设**按你写的指令说点什么。
 *
 * 与「人设主动关心」的区别：那个是每天固定时间、自动挑最常聊的人设、内容也由模型自由发挥；
 * 这个是**你自己配的** —— 谁发、几点发、发什么，都可以多条并存
 * （例：「小美 每天 07:30 发今天的重要新闻」「教练 每天 22:00 问我今天练了没」）。
 *
 * [withSearch] = true 时先联网搜一遍（服务商与 Key 在设置里配），把结果喂给模型再写，
 * 这样"今天的新闻"才是真的今天的。
 *
 * **v0.56.6**：新增 [kind] —— `call` 时同一条记录表示「**定时电话**」：到点由 TA **打过来**，
 * [instruction] 就是"接通后说什么"。与定时消息同构（每天几点 + 说什么 + 启用），
 * 于是闹钟、存储、"编辑/删除"表单全部复用同一套，不另起一份。
 */
data class ScheduledMessage(
    val id: String,
    /** 发消息 / 打电话的人设；0 = 主人设 */
    val personaId: Long = 0L,
    /** `HH:mm` */
    val time: String = "08:00",
    /** 你给它的指令：发消息时是"发什么"，打电话时是"接通后说什么" */
    val instruction: String = "",
    /** 是否先联网搜索再写（不联网时模型只能凭旧知识，新闻类会不准）。仅发消息有意义 */
    val withSearch: Boolean = false,
    val enabled: Boolean = true,
    /** 上次发送日（epochDay；当天发过就不再发） */
    val lastSentDay: Long = 0L,
    /** [KIND_SCHEDULED_MESSAGE] 或 [KIND_SCHEDULED_CALL]；老数据没有这个字段 → 一律按发消息 */
    val kind: String = KIND_SCHEDULED_MESSAGE,
)

/** 定时主动消息的编解码（纯函数，脏数据一律跳过而不是崩） */
object ScheduledMessageCodec {

    fun encode(list: List<ScheduledMessage>): String {
        val array = JSONArray()
        list.forEach { m ->
            array.put(
                JSONObject()
                    .put("id", m.id)
                    .put("personaId", m.personaId)
                    .put("time", m.time)
                    .put("instruction", m.instruction)
                    .put("withSearch", m.withSearch)
                    .put("enabled", m.enabled)
                    .put("lastSentDay", m.lastSentDay)
                    .put("kind", m.kind),
            )
        }
        return array.toString()
    }

    /** 宽容解析：整体不是 JSON 就当空；缺 id 的跳过；其余字段缺失给默认值 */
    fun decode(raw: String): List<ScheduledMessage> {
        if (raw.isBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id").trim()
                if (id.isEmpty()) continue
                add(
                    ScheduledMessage(
                        id = id,
                        personaId = o.optLong("personaId", 0L),
                        time = o.optString("time").trim().ifEmpty { "08:00" },
                        instruction = o.optString("instruction"),
                        withSearch = o.optBoolean("withSearch", false),
                        enabled = o.optBoolean("enabled", true),
                        lastSentDay = o.optLong("lastSentDay", 0L),
                        // 老记录没有 kind → 按"发消息"读（v0.56.6 之前的都只有发消息）
                        kind = o.optString("kind").trim().ifEmpty { KIND_SCHEDULED_MESSAGE },
                    ),
                )
            }
        }
    }
}


/** 定时主动消息的纯逻辑（到点判断） */
object ScheduledMessageLogic {

    /**
     * 这条现在该不该发：开着、今天还没发过、且当前时间已过它设定的时刻。
     *
     * 用"已过"而不是"正好等于"：手机可能休眠、闹钟可能被系统推迟几分钟，
     * 严格相等会让消息永远发不出去。
     */
    fun isDue(message: ScheduledMessage, minuteOfDay: Int, today: Long): Boolean {
        if (!message.enabled) return false
        if (message.lastSentDay == today) return false
        val at = AiCallLogic.minuteOfDayOf(message.time) ?: return false
        return minuteOfDay >= at
    }
}
