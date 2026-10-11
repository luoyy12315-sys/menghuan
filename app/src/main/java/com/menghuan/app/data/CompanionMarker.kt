package com.menghuan.app.data

/**
 * 心情标记 `[心情:情绪词]`（v0.98.0）—— [CompanionState] 里 valence / arousal 的**唯一输入端**。
 *
 * ## 为什么要加这条标记
 * v0.72.0 把"她为什么会主动找我"做成了一组持续漂移的五轴数值，但这个引擎当初只接了
 * **连接需求 / 骄傲**（[ProactiveChat] 每次触发 tick 推动、[CompanionLogic.onUserMessage] 被回应时清零），
 * **愉悦度 / 唤醒度从来没有输入源** —— 设计里那个"把一次交互的影响落在轴上"的入口
 * [CompanionLogic.applyDelta] 写好了却零调用点（死代码）。于是 valence 恒在基准、arousal 恒在默认，
 * `moodOf` 的九个情绪簇只有"情绪平平"可达，会话列表那颗心情计永远是半格。
 *
 * 这条标记就是把那个缺失的输入端接上：模型读一遍这次对话，从固定词表里挑一个
 * **这次对话让它心情怎么变**，App 剥掉标记、按 [TABLE] 里写死的增量落轴。
 *
 * ## 为什么是"词"不是"数字"
 * 让模型直接写 `[心情:+0.3,-0.1]` 会漂：小数点和全角、写 0.3 还是 30、越界怎么办 ——
 * 每一种都能让解析静默失败。词表最稳（解析就是查表），幅度由代码定死、可复现，
 * 也不会出现"一句话把心情打到极值"这种失控。**确定性**是这个引擎的立身之本
 * （见 [CompanionState] 头注释：不要随机骰子），这条标记必须守住它 —— 同一段对话，算出来的增量永远一样。
 *
 * ## 标记绝不能进用户可见文字
 * [parse] 一定把它剥干净（[Decision.cleanText]），且**所有可能落成气泡的链路都要剥**：
 * 聊天主链、主动关心、定时消息、通话问候语 —— 只要提示词里教了这个标记，就可能在别处被沿用，
 * 漏一处用户就会在正文里看到 `[心情:…]` 这种原始标记。
 */
object CompanionMarker {

    /** 提示词里要求的写法（半角方括号；[PATTERN] 也认全角）。 */
    const val MARK_OPEN = "[心情:"

    /**
     * 情绪词 → 一次交互对两根轴（valence, arousal）的**固定增量**。
     *
     * 幅度刻意压小（单轮最多 ±0.25）：心情是几小时尺度的慢变量，一两条消息不该把它甩到极值；
     * 而 [CompanionLogic.tick] 每半小时又会把 valence 往基准拽一点，
     * 所以"开心一阵子"之后会自然平复，不会永久停在高位。
     *
     * 同义词映射到同一组增量（模型用哪个词都行，效果一致）。
     */
    private val TABLE: Map<String, Pair<Float, Float>> = mapOf(
        "开心" to (0.15f to 0.05f),
        "高兴" to (0.15f to 0.05f),
        "愉快" to (0.15f to 0.05f),
        "兴奋" to (0.20f to 0.20f),
        "温暖" to (0.25f to 0.02f),
        "感动" to (0.25f to 0.02f),
        "平静" to (0.00f to -0.15f),
        "淡定" to (0.00f to -0.15f),
        "担心" to (-0.10f to 0.15f),
        "烦躁" to (-0.20f to 0.20f),
        "生气" to (-0.20f to 0.20f),
        "难过" to (-0.25f to -0.15f),
        "低落" to (-0.25f to -0.15f),
        "委屈" to (-0.20f to 0.05f),
    )

    /**
     * 一条完整标记：开括号 `[` 或 `【`、冒号半/全角、词、闭括号 `]` 或 `】`。
     *
     * ⚠️ 词的长度**不设上限**（`*` 而不是 `{1,n}`）：设了上限，一个超长的"词"
     * 就会让整条标记匹配不上，于是它**原样漏进用户可见的正文** —— 剥离必须比识别更宽松。
     * 认不认得那个词只影响增量（查表落空就是 0），不影响"剥掉"。
     */
    private val PATTERN = Regex("[\\[【]\\s*心情\\s*[:：]\\s*([^\\]】]*)\\s*[\\]】]")

    /**
     * @param hit 回复里是否出现了心情标记（哪怕词不认识）
     * @param valence / @param arousal 这次对话对两根轴的增量（词不认识时为 0，调用方据此跳过）
     * @param cleanText 剥掉全部标记、收拾好空行的正文（就是要显示、要落库的那段话）
     */
    data class Decision(
        val hit: Boolean,
        val valence: Float,
        val arousal: Float,
        val cleanText: String,
    ) {
        /** 真的能改变状态才算数：出现了标记、且词在表里（未知词的增量是 0,0）。 */
        val effective: Boolean get() = hit && (valence != 0f || arousal != 0f)
    }

    fun parse(raw: String): Decision {
        val matches = PATTERN.findAll(raw).toList()
        if (matches.isEmpty()) return Decision(false, 0f, 0f, raw.trim())

        // 一次回复最多一个标记；万一模型写了多个，取**第一个认得的词**（未知词忽略但照样剥掉）
        val word = matches.asSequence()
            .map { it.groupValues[1].trim() }
            .firstOrNull { TABLE.containsKey(it) }
        val delta = word?.let { TABLE[it] }

        // 逐行剥：标记整行独占时把那一行也去掉（否则留一个空行给用户看）；
        // 标记跟正文混在同一行时只删标记本身、文字留着。AI 自己写的段间空行不受影响。
        val markers = matches.map { it.value }.toSet()
        val cleaned = raw.split("\n").mapNotNull { line ->
            val without = markers.fold(line) { acc, m -> acc.replace(m, "") }
            if (without.isBlank() && without != line) null else without
        }.joinToString("\n")

        return Decision(
            hit = true,
            valence = delta?.first ?: 0f,
            arousal = delta?.second ?: 0f,
            cleanText = tidy(cleaned),
        )
    }

    /** 只剥标记、不要增量（通话 / 定时消息等不落心情的链路用）。 */
    fun strip(raw: String): String = parse(raw).cleanText

    /**
     * 注入 system prompt 的一段固定说明（协议本身）。
     *
     * **总是注入**，不挂开关 —— 描述的是"有这条通道"，不是"鼓励你多写"。
     * 文字固定，所以放得起 system prompt 前缀（§零.9：每轮都变的内容才不许进前缀）。
     *
     * ⚠️ 措辞里有两处是实测会做错的地方，不能省：
     * - **「别一律写平静」**：不这么写，模型会图省事每条都挑同一个中性词，等于又回到"心情恒定"。
     * - **「不会被显示」**：不写清楚，模型会在正文里解释标记（"我心情不太好（心情:难过）"）。
     */
    fun promptSection(): String =
        "## 心情\n" +
            "每次回复，你可以在末尾用一行标记写下**这次对话让你心情怎么变**：" +
            "格式 ${MARK_OPEN}情绪词]，最多一行；没什么明显变化就整条不写。\n" +
            "可选的情绪词：开心、兴奋、温暖、平静、担心、烦躁、难过、委屈。\n" +
            "按真实感受挑：被夸、被逗、听到好事就写好的词；被冷落、被顶撞、被敷衍就写对应的词。" +
            "**别一律写「平静」**——每次都写同一个词，等于没有心情。\n" +
            "这个标记只让 App 记下你的心情，会被剥掉、永远不会显示给用户；" +
            "正文里不要解释它，也不要把它念出来。"

    /** 连续空行压成一个、行尾空白去掉、首尾留白去掉（同 [TaskSuggestionMarker]，但不吃段间空行）。 */
    private fun tidy(text: String): String = text
        .replace(Regex("[ \\t]+\\n"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}
