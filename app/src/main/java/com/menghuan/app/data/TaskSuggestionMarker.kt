package com.menghuan.app.data

/**
 * AI 任务建议标记协议（v0.88.0）。
 *
 * ## 为什么改
 * 以前任务建议是**提炼链的副产品**：每攒够 6 轮对话，[ExtractionEngine] 顺带让模型吐一批
 * taskSuggestions，经 [TaskSuggestionHub] 分发到聊天页卡片。三个毛病：
 * 1. **时机由轮数决定，不由对话内容决定** —— 用户随口聊两句不想被建议任务；
 * 2. 提炼是后台批量跑，卡片出现的时间和"AI 刚说了句话"完全脱节；
 * 3. 同一批建议被打包成好几条，用户看到一排卡片而不是"聊到这儿 TA 顺口提了一句"。
 *
 * 现在改成**标记驱动**（和 `【语音条】`、`[生图:…]` 同族）：AI 觉得这件事值得做成任务时，
 * 在回复里写一条 `[建议任务:标题|备注]`，App 剥掉标记、把这条建议挂在那条回复下面出卡。
 * 好处是**时机由模型判断**、和当前话题天然对齐（就是"说完这句话顺手帮你记一下"）。
 *
 * 用标记而不是 function calling 的理由同 [VoiceNoteMarker]：OpenAI 兼容端点对 `tools`
 * 的支持不一致，标记最稳。
 *
 * ## 标记绝不能进用户可见文字
 * [parse] 一定会剥干净（见 [Decision.cleanText]），且**所有链路都要剥** ——
 * 聊天主链、主动关心、定时电话、定时消息，任何可能落成气泡的回复都得先过一遍，
 * 否则用户会在文字里看到 `[建议任务:…]` 这种原始标记。
 *
 * ## 只在聊天页出卡
 * 用户点名「只在聊天页出卡」。通话/定时消息链路**只剥标记、不建卡**（[parse] 照跑，
 * 调用方忽略 [Decision.suggestions] 即可）—— 那些场景的回复不是"正在聊天"，出卡没有落点。
 */
object TaskSuggestionMarker {

    /**
     * 提示词里要求的写法。参数用 `|` 分隔：标题 | 备注（备注可省略）。
     * `[建议任务: 喝水 | 每小时一杯 ]` 这种带空格的也要认（模型爱加空格）。
     */
    const val MARK_OPEN = "[建议任务:"

    /**
     * 注入 system prompt 的一段固定说明（协议本身）。
     *
     * **总是注入**，不挂任何开关 —— 它描述的是"有这条通道"，不是"鼓励你多提建议"；
     * 提不提由 [DEFAULT_STRATEGY] 那段语气约束管。文字固定，所以放得起 system prompt 前缀。
     */
    fun promptSection(): String =
        "## 建议任务（想做就顺手记一条）\n" +
            "当聊天里出现了**用户自己说过、且确实值得做成任务**的事（不是你自己编的、也不是寒暄），" +
            "你可以在回复里单独写一行：\n" +
            "${MARK_OPEN}标题]\n" +
            "要补说明就用 ${MARK_OPEN}标题|备注]，比如 ${MARK_OPEN}买牛奶|家里快没了]。\n" +
            "用户话里带了具体日子时，再接一段日期：${MARK_OPEN}周五面试|带简历|2026-10-09]。\n" +
            "写完之后 App 会在你这句话下面挂一张卡片，用户点一下才真的建任务——所以**别在正文里" +
            "再重复说一遍「我帮你加个任务吧」**，卡片本身就是这句话。\n" +
            "克制着用：一次回复最多一条；用户没提、只是你自己觉得「他应该做这个」的，不要提。" +
            "标记只用于触发出卡，不要在正文里解释这个标记，也不要重复它。"

    /**
     * 匹配一条完整标记，捕获三段参数（标题 | 备注 | 日期）。
     *
     * - 开括号：[ 或 【（各有半/全角变体）
     * - 标题：非空、不含 `|` 和闭括号
     * - 备注：可选
     * - 日期：可选（用户话里有具体日子时给，容错解析见 [ProfileExtractor.normalizeDueDate]）
     * - 闭括号：] 或 】
     *
     * 用非贪婪 + `[^|\]】]*` 的字符类而不是 `.` —— 否则一条回复里两个标记会粘成一条。
     */
    private val PATTERN = Regex(
        "[\\[【]\\s*建议任务\\s*[:：]\\s*([^|\\]】]+?)\\s*" +
            "(?:\\|\\s*([^|\\]】]*?)\\s*)?" +
            "(?:\\|\\s*([^\\]】]*?)\\s*)?" +
            "[\\]】]"
    )

    /**
     * @param suggestions 从回复里解析出的任务建议（0 条表示模型这次没提）
     * @param cleanText 剥掉全部标记、并收拾好空行的正文（就是要显示的那段话）
     */
    data class Decision(
        val suggestions: List<TaskSuggestion>,
        val cleanText: String,
    ) {
        val hasSuggestion: Boolean get() = suggestions.isNotEmpty()
    }

    fun parse(raw: String): Decision {
        val matches = PATTERN.findAll(raw).toList()
        if (matches.isEmpty()) return Decision(emptyList(), raw)

        val suggestions = matches.mapNotNull { m ->
            val title = m.groupValues[1].trim()
            if (title.isEmpty()) return@mapNotNull null
            val note = m.groupValues.getOrNull(2).orEmpty().trim()
            val due = m.groupValues.getOrNull(3).orEmpty().trim()
            TaskSuggestion(
                title = title.take(40),
                note = note.take(80),
                // 宽容解析日期（与记忆链同一套：2026-10-09 / 10月9日 都认；解不出落空串）
                dueDate = ProfileExtractor.normalizeDueDate(due),
            )
        }.distinctBy { it.title }

        // 逐行剥：**标记整行独占时把那一行也去掉**（否则会留一个空行给用户看）；
        // 标记跟正文混在同一行时只删标记本身、文字留着。这样 AI 自己写的段间空行不受影响。
        val markerTexts = matches.map { it.value }.toSet()
        val cleaned = raw.split("\n").mapNotNull { line ->
            val withoutMarker = markerTexts.fold(line) { acc, m -> acc.replace(m, "") }
            // 这一行原本有标记、剥完只剩空白 → 整行丢掉
            if (withoutMarker.isBlank() && withoutMarker != line) null else withoutMarker
        }.joinToString("\n")

        return Decision(suggestions, tidy(cleaned))
    }

    /**
     * 只剥标记、不要建议（通话/定时消息等"不出卡"的链路用）。
     *
     * 这些链路照样可能触发标记（提示词是全局注入的），不剥就会漏字面量给用户看。
     */
    fun strip(raw: String): String = parse(raw).cleanText

    /** 连续空白行压成一个、行尾空白去掉、首尾留白去掉（同 [VoiceNoteMarker]，但不吃段间空行） */
    private fun tidy(text: String): String = text
        .replace(Regex("[ \\t]+\\n"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}
