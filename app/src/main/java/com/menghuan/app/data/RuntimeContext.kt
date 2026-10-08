package com.menghuan.app.data

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 每轮请求都要变、但**绝不能进 system prompt** 的那一小段上下文（v0.69.0）。
 *
 * ## 为什么要单独一个文件、为什么要挑位置
 * 服务商的隐式前缀缓存按"请求开头逐字节相同"计费与提速。把当前时间写进 system prompt，
 * 等于每分钟把整段前缀（人设 + 记忆 + 全部历史）作废一次 —— 账单涨、首 token 变慢，
 * 而且不报错，只能从 `cached_tokens` 那个读数看出来。
 * 所以本项目的口径固定为：**易变内容贴到最后一条用户消息上**，位置理由写在
 * [com.menghuan.app.llm.OpenAiClient.RUNTIME_CONTEXT_MARK] 的注释里。
 *
 * ## 为什么聊天和通话共用这一份
 * 两处以前各自拼字符串，早晚一个字不一样（一个带星期、一个不带），
 * 模型对"现在几点"的认知就随入口变。和 `TtsConfig.TTS_CALL_SAMPLE_RATE` 那条
 * "同一个数只许有一个来源"是同一条纪律。
 */
object RuntimeContext {

    private val CLOCK_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm", Locale.CHINA)

    /**
     * 时间那一行（纯函数：传进来的时刻决定输出，单测口径）。
     * 星期用 `TextStyle.FULL` + 中文 locale —— 模型拿到"星期四"比拿到"2026-10-08"更能说对"周五要上班"。
     */
    fun clockLine(at: LocalDateTime): String {
        val weekday = at.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.CHINA)
        return "当前时间：" + at.format(CLOCK_FORMAT) + "（" + weekday + "）"
    }

    /** 用本机时区的此刻取一行（调用点用它，测试用 [clockLine]） */
    fun clockLineNow(zone: ZoneId = ZoneId.systemDefault()): String =
        clockLine(LocalDateTime.now(zone))

    /**
     * 组装本轮的易变上下文。目前只有时间一条；
     * 后面接进来的检索结果 / 环境感知（前台应用、正在听歌等）**都从这里加**，
     * 不要再往 system prompt 里塞第二个动态段。
     */
    fun build(zone: ZoneId = ZoneId.systemDefault()): String = clockLineNow(zone)
}
