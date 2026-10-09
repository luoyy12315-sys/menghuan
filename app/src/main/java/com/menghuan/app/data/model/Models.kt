package com.menghuan.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 人设：完全由用户定义，不写死。
 * 默认只预置一个内置应用助手作为示例，用户可修改、删除、新增任意人设
 * （学习教练、健身教练、心理咨询师……甚至可以不是"陪伴者"）。
 */
@Entity(tableName = "personas")
data class Persona(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,               // 名字，如"阿布"
    val title: String,              // 身份，如"朋友 / 陪伴者"
    val avatarEmoji: String,        // 头像（MVP 用 emoji，后续可换图片）
    @ColumnInfo(defaultValue = "")
    val avatarImagePath: String = "", // 自定义头像图片的绝对路径（非空时优先于 emoji）
    val systemPrompt: String,       // 用户可自由编辑的设定词
    val isBuiltIn: Boolean = false, // 预置人设标记（仍可编辑，仅不可删除）
    @ColumnInfo(defaultValue = "GENTLE")
    val tone: String = ToneStyle.GENTLE.name,  // 该会话当前对话风格（持久化）
    @ColumnInfo(defaultValue = "0")
    val pinned: Boolean = false,    // 会话置顶（长按会话行切换）
    @ColumnInfo(defaultValue = "0")
    val hidden: Boolean = false,    // 已删除的会话（微信式：清空消息后从列表隐藏，新消息/重新打开即恢复）
    /**
     * 视频通话的视觉形象类型（v0.58.0）：`NONE` / `LIVE2D` / `VIDEO`，
     * 存 [com.menghuan.app.data.CallVisualType] 的枚举名。
     *
     * 这一列只回答"想用哪种视觉"；具体素材（哪些 mp4、哪个模型包、准备好没有）
     * 在 `filesDir/call_visuals/<personaId>/metadata.json` 里，两边职责分开（方案文档 §十九）。
     * 旧数据默认 NONE，所以升级后语音通话与聊天的行为完全不变（方案文档 §二十）。
     */
    @ColumnInfo(defaultValue = "NONE")
    val videoVisualType: String = "NONE",
    /**
     * 角色立绘的**本机文件绝对路径**（v0.58.1，`filesDir/portraits/`）。
     *
     * 两个用途：① 人设页/以后要显示全身形象的地方；② **AI 生图时当参考图发给生图服务**，
     * 让 TA 画"自己"时长相、发色、服装对得上（见 ImageGenerationClient.generate 的 refImage）。
     *
     * ⚠️ 这和 [avatarImagePath]（会话头像，圆裁小图）是两回事，别共用一个字段：
     * 头像多半是脸的大特写，拿去做生图参考会把整套服装都丢掉。
     * 旧的 DataStore 键 `persona_art` 已在 v0.58.0 废弃清理，这一列是它的正式替代。
     */
    @ColumnInfo(defaultValue = "")
    val artImagePath: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

/** 对话风格：附加在人设之上的语气维度，长按头像快速切换 */
enum class ToneStyle(val label: String, val promptSnippet: String) {
    GENTLE(
        "温和陪伴",
        "你以温和、共情的方式交流，先接纳情绪，再给出建议。",
    ),
    STRICT(
        "严格教练",
        "你以严格、直接的方式交流，指出问题毫不客气，重视执行与结果。",
    ),
    SOCRATIC(
        "苏格拉底反问",
        "你以苏格拉底式追问引导用户自己找到答案，少给结论，多问问题。",
    ),
}

@Entity(
    tableName = "messages",
    // 会话加载（WHERE conversationId ORDER BY timestamp）与列表预览都按此路径查，没索引就是全表扫描
    indices = [Index(value = ["conversationId", "timestamp"])],
)
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val content: String,
    val isFromUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    /**
     * 图片消息的**本机文件绝对路径**（v0.35.0）：
     * - AI 生成图：ChatViewModel 解析 `[生图:…]` 标记后调生图接口下载落盘
     * - 表情包：只有纯 emoji 文本，无需此字段（留空即可）
     * 非空时气泡渲染图片，且 content 只作为该图的说明文字（可为空）。
     */
    @ColumnInfo(defaultValue = "")
    val imagePath: String = "",
    /**
     * 思考链全文（v0.41.4）：推理类模型（deepseek-reasoner / GLM / qwen-thinking 等）
     * 在正文前生成的 reasoning 内容。只在长按菜单里按需查看，**绝不渲染进气泡**，
     * 也**绝不回送进下一轮请求**（回送既费 token 又会把模型的旧思路带偏）。
     * 非推理模型恒为空串。
     */
    @ColumnInfo(defaultValue = "")
    val reasoning: String = "",
    /**
     * 媒体类型（v0.43.0）：决定气泡怎么渲染。
     * 空串 = 老数据，按 [MediaKind.fromLegacy] 推断（有 imagePath 就是图，否则纯文本），
     * 这样存量消息不用批量回填也能正确显示。
     */
    @ColumnInfo(defaultValue = "")
    val mediaKind: String = "",
    /**
     * 语音消息的**本机音频绝对路径**（v0.43.0，filesDir/voice/）。
     * 用户按住说话录制；`content` 存语音识别出的文字（AI 收到的是文字，
     * 但消息列表**只显示语音条**，不显示这段文字——用户明确要求）。
     */
    @ColumnInfo(defaultValue = "")
    val audioPath: String = "",
    /** 语音时长（毫秒），语音条上显示 */
    @ColumnInfo(defaultValue = "0")
    val audioDurationMs: Long = 0,
    /**
     * 附件消息的**本机文件绝对路径**（v0.43.0，非图片非语音的任意文件）。
     * 能抽出文字的（txt/pdf/docx…）已在 `content` 里带上抽取结果供模型阅读；
     * 抽不出的（压缩包 / 扫描件）只有文件名，模型看不到内容。
     */
    @ColumnInfo(defaultValue = "")
    val attachmentPath: String = "",
    /** 附件原始文件名（气泡上显示） */
    @ColumnInfo(defaultValue = "")
    val attachmentName: String = "",
    /** 附件字节数（气泡上显示大小） */
    @ColumnInfo(defaultValue = "0")
    val attachmentSize: Long = 0,
)

/** 消息媒体类型（v0.43.0）。空串按老数据推断，见 [fromLegacy]。 */
enum class MediaKind {
    TEXT, IMAGE, AUDIO, FILE,
    /**
     * 自定义表情（v0.45.0）：与图片共用 imagePath 通道（模型照样能看到图），
     * 但气泡要**小一圈且不可点开**——用户明确要求"跟图片不一样，显示区域小点，并且不能点开"。
     */
    STICKER,
    /**
     * 语音通话记录卡（v0.49.0）：通话挂断后落进聊天流（"语音通话 · X分X秒"），
     * attachmentPath 存 call_sessions.id，点气泡弹出该次通话的文字转写。
     */
    CALL;

    companion object {
        /**
         * 老数据推断：有图就是 IMAGE，否则 TEXT。
         * v0.45.0 起：v0.43~0.44 期间发出的表情包存成了 IMAGE，这里按落盘目录
         * （filesDir/stickers/）识别回来，让老表情也走新的小气泡。
         */
        fun fromLegacy(raw: String, imagePath: String): MediaKind {
            if (raw.isNotBlank()) {
                val parsed = entries.firstOrNull { it.name == raw } ?: TEXT
                return if (parsed == IMAGE && imagePath.contains("/stickers/")) STICKER else parsed
            }
            return if (imagePath.isNotBlank()) IMAGE else TEXT
        }
    }
}

/**
 * 用户自传表情（v0.43.0）：从相册选图导入，存在应用私有 filesDir/stickers/。
 * 在表情面板「我的表情」页点一下即直接发送（与 emoji 的"插入输入框"不同）。
 */
@Entity(tableName = "stickers")
data class Sticker(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 本机图片绝对路径 */
    val path: String,
    /** 加入时间，列表按倒序展示（新加的在前） */
    val addedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "tasks")
data class TaskItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val note: String = "",
    @ColumnInfo(defaultValue = "0")
    val dueAt: Long = 0,            // 归属日期（当天 0 点毫秒值），0 = 视为今天；每日任务不使用
    val remindAt: Long? = null,     // 到点弹通知督促；每日任务 = 每日重复提醒的时刻锚点（只取时刻）
    val isDone: Boolean = false,
    @ColumnInfo(defaultValue = "user")
    val createdBy: String = "user", // user = 用户自建 / ai = AI 建议（AI 建议落库也标记 ai）
    @ColumnInfo(defaultValue = "0")
    val repeatDaily: Boolean = false, // 每日任务：无日期概念，每天重置未完成，提醒走每日重复闹钟
    @ColumnInfo(defaultValue = "0")
    val completionDate: Long = 0,   // 完成归属日（epochDay）：每日任务跨天即视为未完成
    /**
     * 任务失败（v0.74.0）。**与 [isDone] 互斥的正式终态**，不是标记：
     * 落库、可取消、**计入完成率分母**（用户拍板"失败计入分母"）。
     *
     * ⚠️ 为什么不复用 `isDone` + 另一个布尔：失败与完成在**提醒、统计、回执、逾期判定**
     * 四处行为都不同（见各处 `isFailed` 分支），揉进 `isDone` 会让每次读 `isDone` 都要先问
     * "这次到底是成是败"，很快就分不清。多一列反而让每个分支都自解释。
     *
     * ⚠️ 失败**不写 task_completions**：那张表是"打卡成功"的记录，
     * 热力/连续天数只认成功，失败进了它等于把失败算成打卡。
     */
    @ColumnInfo(defaultValue = "0")
    val isFailed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

/** 当天有效完成状态：每日任务跨天自动回到未完成（展示层判定，不等 BootReceiver 归一化） */
fun TaskItem.isEffectivelyDone(todayEpochDay: Long = java.time.LocalDate.now().toEpochDay()): Boolean =
    if (repeatDaily) isDone && completionDate == todayEpochDay && !isFailed else isDone && !isFailed

/**
 * 当天有效失败状态（v0.74.0）。与 [isEffectivelyDone] 对称：
 * 每日任务跨天重置，失败也一并回到未处理；`isFailed` 与 `isDone` 互斥由写入侧保证。
 */
fun TaskItem.isEffectivelyFailed(todayEpochDay: Long = java.time.LocalDate.now().toEpochDay()): Boolean =
    if (repeatDaily) isFailed && completionDate == todayEpochDay else isFailed

/** 打卡完成记录：每次勾选完成写一行（近 7 天热力 / 打卡小圆点 / AI 提炼任务上下文的数据源） */
@Entity(tableName = "task_completions", primaryKeys = ["taskId", "day"])
data class TaskCompletion(
    val taskId: Long,
    val day: Long, // epochDay
)

/** 用户档案：手动填写 + AI 从对话中提炼，注入人设上下文 */
@Entity(tableName = "profile_facts")
data class ProfileFact(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val value: String,
    val source: String = "manual",  // manual / ai
    val updatedAt: Long = System.currentTimeMillis(),
    /**
     * 这条记忆属于谁（v0.70.0 起）。**0 = 用户自己的基础档案**，`> 0` = 只在该角色的会话里生效。
     *
     * 为什么必须分：以前整张表是全局的，A 角色聊出来的"用户爱吃辣"会原样注进 B 角色的 system prompt，
     * 两个角色互相串味（用户拍板"记忆按人设彻底分开"）。
     * 0 这一档保留是因为"我是谁、我在哪工作"这类事实天然属于人本身，不属于某个 AI。
     */
    val personaId: Long = 0,
    /** 粗分类（身份/喜好/作息/事件…），只用于列表分组与排序，不参与注入判定 */
    val category: String = "",
    /**
     * 生命周期（照 dudulove-memory 的六态收敛）：
     * `active` 正常参与注入；`core` 永久记忆，**豁免去重与衰减**；
     * `staging` 待入库（AI 刚说 `<add:>` 还没落定）；`rejected` 用户明确拒过；`archived` 停用。
     */
    val lifecycle: String = "active",
) {
    /** 参与注入的唯一判据：`rejected` 与 `archived` 一律不进 prompt（§零.9 的准入口径） */
    val injectable: Boolean get() = lifecycle != "rejected" && lifecycle != "archived"
}

/** AI 洞察：提炼流水线从对话中产出的行为模式观察（数据页「AI 洞察」真数据源） */
@Entity(tableName = "ai_insights")
data class AiInsight(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val detail: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/** AI 周报：每周日 21:30 由 LLM 生成（同 weekStart 重复生成 = 覆盖，weekStart 周一 epochDay） */
@Entity(
    tableName = "ai_weekly_reports",
    indices = [Index(value = ["weekStart"], unique = true)],
)
data class AiWeeklyReport(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val weekStart: Long,  // 周一 epochDay
    val weekEnd: Long,    // 周日 epochDay
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * AI 生图记录（v0.40.0）：图库不只是成功图片的相册，而是完整生成记录——
 * 成功/失败、何时发起、提示词、用的哪个生图模型、哪个会话发起。
 * 失败的请求不留图片文件，但记录必须留痕（可追溯）。
 */
@Entity(tableName = "image_generations")
data class ImageGenerationRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val personaId: Long,           // 发起会话（人设）
    val personaName: String,       // 冗余存名字：人设删了记录还在
    val model: String,             // 生图模型的模型名
    val prompt: String,            // 画面提示词
    val success: Boolean,
    val imagePath: String = "",    // 成功时的本机路径；失败为空
)

/**
 * 记账流水（v0.42.0）：一笔收入或一笔支出。
 *
 * 设计取舍：
 * - **没有分类/分组字段**（用户明确不要）——一条流水只需要知道"多少钱 + 进还是出 + 怎么来的"。
 * - **现金余额不入库**：余额 = 全部流水的有符号累加，由 [LedgerMath] 纯函数算出。
 *   存余额字段就要维护一致性（删一条流水得回滚余额），而流水是唯一事实来源，累加永远自洽。
 * - [amountCents] 用「分」存储（Long），避免 Double 累加误差；展示层再除以 100。
 */
@Entity(
    tableName = "ledger_entries",
    indices = [Index(value = ["occurredAt"])],
)
data class LedgerEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** true = 收入（现金 +），false = 支出（现金 -） */
    val isIncome: Boolean,
    /** 金额，单位「分」，恒为正数；收支方向由 [isIncome] 决定 */
    val amountCents: Long,
    /** 备注（手动录入可填；语音/识图自动提取的原始描述也放这里） */
    val note: String = "",
    /** 录入来源：manual / voice / image */
    @ColumnInfo(defaultValue = "manual")
    val source: String = LedgerSource.MANUAL,
    /** 发生时间（毫秒）：默认当前时刻，用户可选改 */
    val occurredAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    val createdAt: Long = System.currentTimeMillis(),
)

/** 记账录入来源 */
object LedgerSource {
    const val MANUAL = "manual"
    const val VOICE = "voice"
    const val IMAGE = "image"

    fun label(source: String): String = when (source) {
        VOICE -> "语音"
        IMAGE -> "识图"
        else -> "手动"
    }
}

/**
 * 每日建议（v0.42.0）：独立于「AI 洞察 / AI 周报」的第三条 AI 产出链路。
 *
 * 与洞察的区别：洞察是「回看过去」的行为模式总结（长期累积，不删）；
 * 每日建议是「面向今天」的一条可执行建议，每天一条，可长按转成任务。
 * 同 [day] 唯一索引 → 重复生成即覆盖（与 [AiWeeklyReport] 同套路，不堆垃圾数据）。
 */
@Entity(
    tableName = "daily_suggestions",
    indices = [Index(value = ["day"], unique = true)],
)
data class DailySuggestion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 归属日 epochDay（按本地时区当天 0 点算） */
    val day: Long,
    /** 建议标题（一句话，可直接作为任务标题） */
    val title: String,
    /** 展开说明：为什么给这条建议、怎么执行 */
    val detail: String = "",
    /** 已转成任务（长按转任务后置位，避免重复转） */
    @ColumnInfo(defaultValue = "0")
    val converted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 记账的纯计算（JVM 单测覆盖）：
 * 余额、月度聚合、金额解析都放在这里，UI 与 ViewModel 只调不实现。
 */
object LedgerMath {

    /** 现金余额（分）= 所有流水有符号累加。支出为负、收入为正。 */
    fun balanceCents(entries: List<LedgerEntry>): Long =
        entries.fold(0L) { acc, e -> acc + signedCents(e) }

    /** 单笔流水的有符号金额（分） */
    fun signedCents(entry: LedgerEntry): Long =
        if (entry.isIncome) entry.amountCents else -entry.amountCents

    /** 某月的收入/支出合计（分） */
    data class MonthTotals(val incomeCents: Long, val expenseCents: Long) {
        val netCents: Long get() = incomeCents - expenseCents
    }

    /** 按 [java.time.YearMonth] 过滤并入账；时区取系统默认（本机应用，无跨时区诉求） */
    fun monthTotals(
        entries: List<LedgerEntry>,
        year: Int,
        month: Int,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): MonthTotals {
        var income = 0L
        var expense = 0L
        entries.forEach { e ->
            val d = java.time.Instant.ofEpochMilli(e.occurredAt).atZone(zone).toLocalDate()
            if (d.year == year && d.monthValue == month) {
                if (e.isIncome) income += e.amountCents else expense += e.amountCents
            }
        }
        return MonthTotals(income, expense)
    }

    /** 该月的日聚合（用于月度统计里的柱状/折线走势），key = 当月第几天 */
    fun dailyNetCents(
        entries: List<LedgerEntry>,
        year: Int,
        month: Int,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): Map<Int, Long> {
        val map = mutableMapOf<Int, Long>()
        entries.forEach { e ->
            val d = java.time.Instant.ofEpochMilli(e.occurredAt).atZone(zone).toLocalDate()
            if (d.year == year && d.monthValue == month) {
                map[d.dayOfMonth] = (map[d.dayOfMonth] ?: 0L) + signedCents(e)
            }
        }
        return map
    }

    // ===== v0.42.1 周度统计（口径与「本周回顾条」一致：周一为一周起点） =====

    /** 某日期所在周的周一（ISO 口径，与 DataViewModel.buildWeekReview 完全一致） */
    fun weekStartOf(date: java.time.LocalDate): java.time.LocalDate =
        date.with(java.time.DayOfWeek.MONDAY)

    /** 某周的接续偏移：delta=-1 上一周，+1 下一周。用 plusWeeks 而非加 7 天，语义更清楚 */
    fun shiftWeek(weekStart: java.time.LocalDate, delta: Long): java.time.LocalDate =
        weekStart.plusWeeks(delta)

    /**
     * 某周的收支合计（分）。周区间 = [weekStart, weekStart+7)。
     * 口径与月度一致：按流水发生的本地日期落周，不做跨周切分。
     */
    fun weekTotals(
        entries: List<LedgerEntry>,
        weekStart: java.time.LocalDate,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): MonthTotals {
        var income = 0L
        var expense = 0L
        val end = weekStart.plusDays(7)
        entries.forEach { e ->
            val d = java.time.Instant.ofEpochMilli(e.occurredAt).atZone(zone).toLocalDate()
            if (!d.isBefore(weekStart) && d.isBefore(end)) {
                if (e.isIncome) income += e.amountCents else expense += e.amountCents
            }
        }
        return MonthTotals(income, expense)
    }

    /**
     * 某周内每天的净额（分），key = 1..7（周一=1 … 周日=7）。
     * 用「周内第几天」而不是「当月第几天」，这样周视图的柱子位置固定 7 根。
     */
    fun weeklyNetCents(
        entries: List<LedgerEntry>,
        weekStart: java.time.LocalDate,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): Map<Int, Long> {
        val map = mutableMapOf<Int, Long>()
        val end = weekStart.plusDays(7)
        entries.forEach { e ->
            val d = java.time.Instant.ofEpochMilli(e.occurredAt).atZone(zone).toLocalDate()
            if (!d.isBefore(weekStart) && d.isBefore(end)) {
                val idx = d.dayOfWeek.value // 周一=1 … 周日=7
                map[idx] = (map[idx] ?: 0L) + signedCents(e)
            }
        }
        return map
    }

    /**
     * 从自由文本里抓金额（分）。语音转写和识图结果都可能带"花了 35 块 8"这种口语，
     * 所以要能容忍：货币符号、小数点、中文数字单位（块/元/毛）、千分位。
     * 抓不到返回 null，交给 UI 提示用户手填。
     */
    fun parseAmountToCents(raw: String): Long? {
        if (raw.isBlank()) return null
        // 先归一化：全角数字/小数点 → 半角，去掉千分位逗号与空格
        val s = raw.map { c ->
            when (c) {
                in '０'..'９' -> ('0' + (c - '０'))
                '．' -> '.'
                else -> c
            }
        }.joinToString("").replace(",", "").replace("，", "").replace(" ", "")

        val m = Regex("""(\d+(?:\.\d{1,2})?)""").find(s) ?: return null
        val value = m.groupValues[1].toDoubleOrNull() ?: return null
        if (value <= 0.0) return null
        return Math.round(value * 100)
    }

    /** 判断文本表达的收支方向：出现"收/入账/工资/报销/退款"等词视为收入 */
    fun detectIncome(raw: String): Boolean {
        val incomeHints = listOf("收入", "收到", "入账", "进账", "工资", "报销", "退款", "返现", "赚了", "红包", "收款")
        val expenseHints = listOf("支出", "花了", "消费", "付了", "买了", "付款", "支出", "扣款", "缴费")
        val incomeScore = incomeHints.count { raw.contains(it) }
        val expenseScore = expenseHints.count { raw.contains(it) }
        return incomeScore > expenseScore
    }

    /** 分 → 展示字符串（两位小数，无货币符号） */
    fun formatCents(cents: Long): String {
        val neg = cents < 0
        val abs = if (neg) -cents else cents
        val s = "${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
        return if (neg) "-$s" else s
    }
}

/**
 * 语音通话会话（v0.46.0）：点挂断后一次性落库（通话期间转写只在内存，
 * **不把 partial 写进 Room**）。只存文字转写，不存原始音频。
 */
@Entity(
    tableName = "call_sessions",
    indices = [Index("personaId"), Index("startedAt")],
)
data class CallSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personaId: Long,
    val startedAt: Long,
    val endedAt: Long = 0,
    val durationMs: Long = 0,
    /** completed = 正常挂断 / error = 异常中断 */
    val status: String = "completed",
)

/** 通话逐轮转写：speaker = user / ai，timestamp = 该轮结束（收口）时刻 */
@Entity(
    tableName = "call_turns",
    indices = [Index("sessionId")],
)
data class CallTurn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val speaker: String,
    val text: String,
    val timestamp: Long,
)

/**
 * 动态（v0.73.0，推特式帖子）：一张表同时装「帖子」和「回复」。
 *
 * `parentId = 0` = 一条帖子；非 0 = 指向那条帖子。回复只有一层，
 * 回复的回复也挂同一个 parentId —— 推特也是这个形状，多层树会把 UI 拖成楼中楼套娃。
 *
 * `personaId`：0 = 用户本人发的，非 0 = 哪个 AI 发的。点赞没有单独的表，
 * `likedBy` 存逗号分隔的 id 串（用户固定是 `u`）；数据量级用不上关系表，
 * 多一张表就多一次 join 与一处不一致。
 *
 * v0.76.0 加了 [images] / [location] 两列：
 * - `images`：**\n 分隔的本机绝对路径**，文件落在 `filesDir/moment_images/`
 *   （见 [com.menghuan.app.data.MomentImageStore]）。不存相册 Uri —— 用户删了原图
 *   或换了 App，Uri 读出来的就是一片空白。只有帖子用得上，回复恒为空串。
 * - `location`：**自由文字的地址**，不是经纬度。发的人本来就是手写"家""西湖边的星巴克"，
 *   存经纬度反而要再引地图 SDK 逆地理才有字（大陆机器上基本拿不到）。
 */
@Entity(
    tableName = "moments",
    indices = [Index("createdAt"), Index("parentId")],
)
data class MomentEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(defaultValue = "0") val personaId: Long = 0,
    @ColumnInfo(defaultValue = "0") val parentId: Long = 0,
    @ColumnInfo(defaultValue = "") val text: String = "",
    @ColumnInfo(defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "") val likedBy: String = "",
    /** v0.76.0：多图（\n 分隔的本机绝对路径；上限 [MomentMath.IMAGES_MAX] 张） */
    @ColumnInfo(defaultValue = "") val images: String = "",
    /** v0.76.0：位置（自由文字，上限 [MomentMath.LOCATION_MAX] 字） */
    @ColumnInfo(defaultValue = "") val location: String = "",
)
