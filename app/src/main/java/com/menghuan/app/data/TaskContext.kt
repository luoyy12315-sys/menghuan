package com.menghuan.app.data

import com.menghuan.app.data.model.TaskItem
import com.menghuan.app.data.model.isEffectivelyDone
import com.menghuan.app.data.model.isEffectivelyFailed
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 给模型看的「任务现状」唯一来源（v0.84.0）。
 *
 * ## 为什么要有这个文件
 * 用户点名「AI 跟我聊天的时候老是时不时提到一下无关紧要的任务，包括已经完成的和已经过时
 * 的和很久之后的」。查下来根因不是模型乱说，而是**宿主喂给它的料本身就没有时间信息**：
 * 聊天、主动关心、定时电话、定时消息四条链路各自拼一段「【最近未完成任务】+ 一串任务名」，
 * 只看 `isDone`，不看 `dueAt`——
 * - 三个月前就逾期的任务，因为 `isDone == false`，每次都被列出来，模型自然拿它当"你还有事没做"；
 * - 每日任务跨天之后 `isDone` 还是旧值（判定该走 [isEffectivelyDone]），昨天做完的事今天又被催；
 * - 明天/下个月才到期的事和今天该做的不分先后，模型随手就挑一条说。
 *
 * 光在提示词里写「不要提过期的」是不管用的：**料里带着过期任务，模型就有理由提**。
 * 所以这里做两件事——① 时间分桶（今天 / 明天 / 近期），② 附上引用纪律。
 *
 * ## 口径（v0.84.0 用户拍板：「只提今天/明天到期的」）
 * - **今天到期**（含每日任务、含 `dueAt == 0`）：主列表，AI 可以自然提、可以催；
 * - **明天到期**：次列表，允许"提前一天提一句"；
 * - **今天已完成**：只给计数与名字，让 AI 说"你今天 xx 做完啦"不是凭空恭维；
 *   **但不许拿它当话题反复念叨**；
 * - **逾期未完成**：只给一个计数（不给名字），明说「别主动提，用户问起才说」——
 *   给名字等于又把过期任务塞进模型视野；
 * - **3 天以后的**：整块不进上下文。
 *
 * ## 位置纪律
 * 本块内容**每轮都变**（打一次卡、过零点就变），按 §零.9 只许贴到最后一条用户消息
 * （易变上下文），**绝不能进 system prompt**。调用点各自负责放到正确位置，
 * 本文件只负责"长什么样"。
 */
object TaskContext {

    /** 上下文里最多列几条今天到期的任务（再多模型也不会一条条说，只会刷屏） */
    private const val MAX_TODAY = 8

    /** 明天到期的最多列几条 */
    private const val MAX_TOMORROW = 4

    /** 今天已完成最多列几个名字 */
    private const val MAX_DONE = 8

    /**
     * 任务的**时间分桶结果**。纯数据，方便单测直接断言分桶对不对。
     *
     * [overdueCount] 只留计数不给名字：逾期任务的名字一旦进上下文，模型很难忍住不提。
     */
    data class Buckets(
        val today: List<TaskItem>,
        val tomorrow: List<TaskItem>,
        val done: List<TaskItem>,
        val failed: List<TaskItem>,
        val overdueCount: Int,
    ) {
        /** 三桶全空 + 没有逾期 → 不值得往上下文里塞一段空标题 */
        val isEmpty: Boolean
            get() = today.isEmpty() && tomorrow.isEmpty() && done.isEmpty() &&
                failed.isEmpty() && overdueCount == 0
    }

    /**
     * 分桶（纯函数：`today` 传进来，单测口径）。
     *
     * [TaskItem.dueAt] 的语义照 [TaskItem] 的注释：当天 0 点毫秒值，`0` = 今天，每日任务不用它。
     * 「今天/明天」一律用**归属日**比，不用时间戳比 —— 跨零点、时区都跟着 [zone] 走。
     */
    fun bucket(
        all: List<TaskItem>,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Buckets {
        val todayDay = today.toEpochDay()
        val tomorrowDay = todayDay + 1
        val done = ArrayList<TaskItem>()
        val failed = ArrayList<TaskItem>()
        val todayList = ArrayList<TaskItem>()
        val tomorrowList = ArrayList<TaskItem>()
        var overdue = 0
        for (t in all) {
            // 先排掉「今天已经了结」的：已完成 / 今天标失败（每日任务跨天会自动回到未处理）
            if (t.isEffectivelyDone(todayDay)) {
                done += t
                continue
            }
            if (t.isEffectivelyFailed(todayDay)) {
                failed += t
                continue
            }
            // 每日任务没有日期概念，天天都算「今天该做」
            if (t.repeatDaily) {
                todayList += t
                continue
            }
            val day = dueDayOf(t, zone)
            when {
                day == null || day == todayDay -> todayList += t   // dueAt=0 视为今天
                day == tomorrowDay -> tomorrowList += t
                day < todayDay -> overdue++                        // 逾期：只记数，不给名字
                // day > tomorrowDay：3 天以后的整块不进上下文，什么都不做
            }
        }
        return Buckets(
            today = todayList,
            tomorrow = tomorrowList,
            done = done,
            failed = failed,
            overdueCount = overdue,
        )
    }

    /** 归属日的 epochDay；`dueAt == 0` 返回 null（调用点按「今天」处理） */
    private fun dueDayOf(task: TaskItem, zone: ZoneId): Long? {
        if (task.dueAt == 0L) return null
        return Instant.ofEpochMilli(task.dueAt).atZone(zone).toLocalDate().toEpochDay()
    }

    /**
     * 拼给模型的「任务现状」整块。空桶返回空串（调用点据此决定加不加分隔符）。
     *
     * [includeDiscipline] = 是否附引用纪律。聊天主链路要附；通话/定时消息这种「开口就得说事」
     * 的场景，纪律收在各自的开场指令里更有效，可以传 false 只取事实。
     */
    fun block(
        all: List<TaskItem>,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        includeDiscipline: Boolean = true,
    ): String {
        val b = bucket(all, today, zone)
        if (b.isEmpty) return ""
        return buildString {
            append("## 用户的任务现状\n")
            append("（实时状态；用户说「我做完了 xx」时以这里为准，别凭印象说人家没做。）\n")
            if (b.today.isEmpty()) {
                append("· 今天该做：没有（别硬找话催）\n")
            } else {
                append("· 今天该做（${b.today.size} 件）：")
                append(b.today.take(MAX_TODAY).joinToString("、") { it.title })
                if (b.today.size > MAX_TODAY) append(" 等 ${b.today.size} 件")
                append("\n")
            }
            if (b.tomorrow.isNotEmpty()) {
                append("· 明天到期（${b.tomorrow.size} 件）：")
                append(b.tomorrow.take(MAX_TOMORROW).joinToString("、") { it.title })
                if (b.tomorrow.size > MAX_TOMORROW) append(" 等 ${b.tomorrow.size} 件")
                append("\n")
            }
            if (b.done.isNotEmpty()) {
                append("· 今天已完成（${b.done.size} 件）：")
                append(b.done.take(MAX_DONE).joinToString("、") { it.title })
                if (b.done.size > MAX_DONE) append(" 等 ${b.done.size} 件")
                append("\n")
            }
            if (b.failed.isNotEmpty()) {
                append("· 今天标了失败（${b.failed.size} 件）：")
                append(b.failed.take(MAX_DONE).joinToString("、") { it.title })
                append("\n")
            }
            if (b.overdueCount > 0) {
                append("· 另有 ${b.overdueCount} 件早该做但没做（**不要主动提**，用户问起再答）\n")
            }
            if (includeDiscipline) {
                append("\n").append(DISCIPLINE)
            }
        }.trimEnd()
    }

    /**
     * 引用纪律（v0.84.0，用户点名「老是提无关紧要的任务」）。
     *
     * 这段话是**约束模型怎么用上面那份料**，不是让模型复述。写在料旁边而不是 system prompt，
     * 既是位置纪律（§零.9）也顺带保证：料在，纪律就在，二者不会走散。
     */
    const val DISCIPLINE: String =
        "上面这份清单是给你「必要时查证」，**不是待办播报稿**：\n" +
            "- 只在当下这件事**真的和某条任务相关**时顺口带一句，跟当前话题无关就别往任务上拐；\n" +
            "- **只许提「今天该做」和「明天到期」的**。3 天以后的、还没到跟前的，一个字都不用提；\n" +
            "- **已完成的任务不要拿来当话题**：用户主动说起可以接，除此之外别翻旧账、别反复表扬同一件；\n" +
            "- 逾期没做的（上面只给了数字）**不要主动提**，那是催债不是关心；用户问起再如实说；\n" +
            "- 一天里提任务的次数要少，别每条回复都挂着一个任务，那是打卡机不是陪聊。"

    /** 提炼侧（[ProfileExtractor]）要看的一份「近况」：只补今天/明天 + 今天已完成，供判断是否值得再建议 */
    fun forExtraction(
        all: List<TaskItem>,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val b = bucket(all, today, zone)
        if (b.isEmpty) return ""
        return buildString {
            append("【用户现有的任务】\n")
            if (b.today.isNotEmpty()) {
                append("- 今天该做：")
                append(b.today.take(MAX_TODAY).joinToString("、") { it.title })
                append("\n")
            }
            if (b.tomorrow.isNotEmpty()) {
                append("- 明天到期：")
                append(b.tomorrow.take(MAX_TOMORROW).joinToString("、") { it.title })
                append("\n")
            }
            if (b.done.isNotEmpty()) {
                append("- 今天已完成：")
                append(b.done.take(MAX_DONE).joinToString("、") { it.title })
                append("\n")
            }
            append("（已经列在上面的不要再建议一遍；只补用户**新**提到、且确实值得做成任务的事。）")
        }
    }
}
