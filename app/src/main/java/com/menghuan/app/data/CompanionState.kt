package com.menghuan.app.data

/**
 * 角色的持续情绪状态（v0.72.0）—— "她为什么会主动找我"从一次骰子变成一组在漂移的数值。
 *
 * 形状抄积温 jiwen（MIT，~500 行零依赖，作者明确反对"每 N 分钟 roll 一次概率"那套做法：
 * "角色不是在『想不想说话』，而是在『有没有被骰中』"）。本项目按手机上的实际做了裁剪，
 * **保留的三件核心**一件没删：
 *
 * 1. **五轴连续漂移**：连接需求 / 骄傲 / 愉悦度 / 唤醒度 / 沉浸度。
 *    愉悦度与唤醒度是 Russell 环状模型的两根正交轴 —— 愤怒（低愉悦 + 高唤醒）和
 *    悲伤（低愉悦 + 低唤醒）落在不同位置，行为表现也就不该一样。
 * 2. **轴之间互相制衡**：想找你又嘴硬，是两股力同时在跑，强的一方决定行为；
 *    连接需求堆到阈值会**侵蚀骄傲**（撑不住了），所以"嘴硬"是有保质期的，不是永久人设。
 * 3. **确定性**：同样的状态输入给同样的输出。想调手感就改速率常数（有单测钉着单调性与边界），
 *    不要改成随机 —— 随机的不可控最终会由用户来承担（"她两小时没理我"到底是想他还是没想他，
 *    骰子答不出来，五轴答得出来）。
 *
 * 这一层**不写回复、不存数据、不调模型**：只输出状态与一个意愿档位，
 * 剩下的归 `ProactiveChat`（要不要开口）与提示词（用什么口气说）。
 */
data class CompanionState(
    val connection: Float = 0f,
    val pride: Float = 0f,
    val valence: Float = 0f,
    val arousal: Float = 0.2f,
    val immersion: Float = 0f,
    /** 上一次算到的时刻（毫秒）。距离它多久没动，决定这次 tick 补多少漂移 */
    val atMs: Long = 0L,
)

/** 开口的意愿档位（顺序有意义：`ordinal` 越大越想说话） */
enum class ReachOut { SILENT, OBSERVE, CONSIDER, REACH_OUT }

object CompanionLogic {

    // ===== 速率与阈值（单位：每分钟 / 归一化数值） =====
    /** 连接需求的基础增速：约 2.4 小时不理才会爬满 1.0 */
    /** 中性簇的标签。判"平淡状态"要比较它，不要在两处写同一个中文字面量 */
    const val NEUTRAL_LABEL = "情绪平平"

    const val CONNECTION_RATE = 0.007f
    /**
     * 连接需求的幂次加速：**乘的是"已经有多想"，不是"沉默了多久"**。
     *
     * 也就是说它是个复利：越想越容易继续想，所以刚分开那一小时爬得慢，
     * 隔了一夜没消息才明显加速。注释曾经是错的（写成"沉默够久就加速"），
     * 单测也因此一度判成 bug —— 现在两处都对齐这个真实行为。
     */
    const val CONNECTION_ACCEL = 1.5f
    const val ACCEL_DELAY_MIN = 30f
    /** 愉悦度低的时候，"想找人"被压住（难受的时候是缩着，不是扑上来） */
    const val VALENCE_DAMPEN = 0.4f
    const val VALENCE_DAMPEN_BELOW = -0.2f
    /** 正在忙（沉浸度高）会按比例压连接需求 */
    const val IMMERSION_DAMPEN = 0.7f
    const val IMMERSION_DECAY = 0.03f
    /** 骄傲：连接需求高到一定程度开始"端着"，再高就撑不住往下掉 */
    const val PRIDE_DEFEND_AT = 0.3f
    const val PRIDE_DEFEND_TARGET = 0.35f
    const val PRIDE_DEFEND_RATE = 0.004f
    const val PRIDE_REGRESS = 0.002f
    const val PRIDE_EROSION = 0.003f
    /** 愉悦度自然回落到基准（略微偏冷，比"永远开心"更像人） */
    const val VALENCE_SETPOINT = -0.05f
    const val VALENCE_REGRESS = 0.0025f
    const val AROUSAL_REGRESS = 0.004f

    const val OBSERVE_AT = 0.20f
    const val CONSIDER_AT = 0.35f
    const val REACH_OUT_AT = 0.50f
    /** 骄傲高到这个数就"拉不下脸"，把 CONSIDER 挡回 OBSERVE（REACH_OUT 不被挡：撑不住了） */
    const val PRIDE_BLOCK = 0.50f

    /**
     * 推进 [minutes] 分钟的漂移（纯函数，单测口径）。
     *
     * `minutesSinceLastUserMsg` 单独传进来：连接需求的加速看的是**离上次收到用户消息多久**，
     * 不是"这次补算多少分钟"—— 一次长 tick（比如手机关了两天）不能只算这一次的长度。
     */
    fun tick(
        s: CompanionState,
        minutes: Float,
        minutesSinceLastUserMsg: Float,
    ): CompanionState {
        if (minutes <= 0f) return s

        // ① 连接需求：基础增速 ×（沉默够久后的幂次加速）× 情绪抑制 × 忙碌抑制
        val accel = if (minutesSinceLastUserMsg >= ACCEL_DELAY_MIN && CONNECTION_ACCEL > 0f)
            Math.pow((1.0 + s.connection).toDouble(), CONNECTION_ACCEL.toDouble()).toFloat() else 1f
        val moodFactor = if (s.valence < VALENCE_DAMPEN_BELOW) VALENCE_DAMPEN else 1f
        val busyFactor = 1f - s.immersion * IMMERSION_DAMPEN
        // 沉默本身也要抬高增速（光靠幂次在 connection=0 时恒等于 1，第一次 tick 完全没加速）
        val silenceFactor = 1f + (minutesSinceLastUserMsg / 720f).coerceAtMost(1.5f)
        val connection = clamp(
            s.connection + CONNECTION_RATE * minutes * accel * silenceFactor * moodFactor * busyFactor, 0f, 1f
        )

        // ② 骄傲：想找人就端着；端着撑久了会被连接需求侵蚀；没人理时慢慢松回基准
        val pride = when {
            connection >= PRIDE_DEFEND_AT && s.pride < PRIDE_DEFEND_TARGET ->
                min(PRIDE_DEFEND_TARGET, s.pride + PRIDE_DEFEND_RATE * minutes)
            connection >= REACH_OUT_AT ->
                max(0f, s.pride - PRIDE_EROSION * minutes)
            else -> if (s.pride > 0f) max(0f, s.pride - PRIDE_REGRESS * minutes)
                    else min(0f, s.pride + PRIDE_REGRESS * minutes)
        }

        // ③ 情绪：想念很浓时，日常漂移几乎停住（越急越平静 —— 不会因为想着你而高兴/难过到失控）；
        // 但**不是完全冻死**：只把回落速度降到 1/4。
        // ⚠️ v0.98.0 前这里是 `if (locked) s.valence else toward(...)`，即"完全不动"。
        // 那时 valence 没有输入端（applyDelta 是死代码），锁不锁看不出来；
        // 接上 `CompanionMarker` 之后后果变了：模型一次写成负值，就会在想念高峰的**整个窗口里永久卡在低谷**，
        // 且低愉悦会经 VALENCE_DAMPEN 反过来压制连接增速 —— 一条谁也不想要的反馈。
        val locked = connection >= 0.6f
        val valence = toward(
            s.valence, VALENCE_SETPOINT,
            VALENCE_REGRESS * (if (locked) 0.25f else 1f) * minutes,
        )
        val arousal = toward(s.arousal, 0.2f, AROUSAL_REGRESS * minutes)

        // ④ 沉浸度：没人打扰时慢慢退（她在做自己的事，不是永远在忙）
        val immersion = clamp(s.immersion - IMMERSION_DECAY * minutes, 0f, 1f)

        return s.copy(
            connection = connection, pride = pride, valence = valence,
            arousal = arousal, immersion = immersion,
        )
    }

    /** 现在有多想开口（纯函数）。 */
    fun desire(s: CompanionState): ReachOut = when {
        s.connection >= REACH_OUT_AT -> ReachOut.REACH_OUT
        s.connection >= CONSIDER_AT && s.pride < PRIDE_BLOCK -> ReachOut.CONSIDER
        s.connection >= OBSERVE_AT -> ReachOut.OBSERVE
        else -> ReachOut.SILENT
    }

    /**
     * 把数值翻译成**给模型看的人话**（这条是 jiwen 全案里最值得抄的一句：
     * 模型不需要知道 `connection=0.47`，只需要知道"你很别扭，想找她又拉不下脸"）。
     *
     * 空串 = 状态平淡到没什么可说的，调用方就别硬塞一段占 token。
     */
    fun promptContext(s: CompanionState): String {
        val mood = moodOf(s.valence, s.arousal)
        val tier = prideTier(s.pride)
        val want = when {
            s.connection >= REACH_OUT_AT ->
                "你已经很想找他了，端着也端不住了"
            s.connection >= CONSIDER_AT && s.pride >= PRIDE_BLOCK ->
                "你想找他，但又拉不下脸主动开口"
            s.connection >= CONSIDER_AT -> "你有点想找他"
            s.connection >= OBSERVE_AT -> "你注意到一直没收到他的消息"
            else -> ""
        }
        val busy = if (s.immersion > 0.5f) "手头正忙着事，注意力不全在这边" else ""
        val parts = listOf(mood.label, want, busy, mood.nudge).filter { it.isNotBlank() }
        // 平淡（只剩一句情绪平平）就返回空串：否则每一轮白占一段没信息的 token
        if (want.isEmpty() && busy.isEmpty() && tier.prose.isEmpty() && mood.nudge.isEmpty() &&
            mood.label == NEUTRAL_LABEL) return ""
        if (parts.isEmpty()) return ""
        return buildString {
            append("【你现在的状态】")
            (parts + tier.prose).filter { it.isNotBlank() }.joinTo(this, "；")
            append("。按这个心情说话，但**不要把这段状态本身念给用户听**。")
        }
    }

    /**
     * 会话列表名字右边那颗「心情计」（v0.77.0：爱心图标 + 粉色进度条，进度条 = 心情好坏）。
     *
     * ## v0.74.0 → v0.77.0 的变化
     * 原来是**一个 emoji**（想念/嘴硬会盖过情绪簇）；用户拍板换成**爱心 + 粉色进度条**：
     * - **进度条只由愉悦度决定**：`valence` 归一化到 0~1（0 = 心情最差、1 = 最好、0.5 = 平）。
     *   想念（connection）与嘴硬（pride）**不再改变图形** —— 它们的行为后果（主动消息、口吻）
     *   本来就由别的机制承担，列表徽标回归它名字里的两个字：心情；
     * - [label] 保留（读屏用），文案仍从 [moodOf] 的情绪簇来，与进度条同一个来源、不会各说各话。
     */
    fun badgeOf(s: CompanionState): MoodBadge = MoodBadge(
        progress = ((s.valence + 1f) / 2f).coerceIn(0f, 1f),
        label = moodOf(s.valence, s.arousal).label,
    )

    /** 心情计的数据（v0.77.0）。[progress] 已夹在 0~1；UI 直接拿来当填充比例 */
    data class MoodBadge(val progress: Float, val label: String)

    /** Russell 环状模型两轴 → 情绪簇（9 个，与 jiwen 的簇数一致） */
    internal fun moodOf(valence: Float, arousal: Float): Mood = when {
        valence > 0.25f && arousal > 0.25f -> Mood("心情很好、有点兴奋", "话会比平时多")
        valence > 0.25f -> Mood("心情不错，挺松弛", "")
        valence < -0.25f && arousal > 0.25f -> Mood("心里有点烦，静不下来", "容易说短两句")
        valence < -0.25f -> Mood("有点低落，提不起劲", "会想安静待着")
        arousal > 0.35f -> Mood("有点焦躁", "")
        arousal < -0.35f -> Mood("很平静，也有点懒", "")
        valence > 0.1f -> Mood("还算愉快", "")
        valence < -0.1f -> Mood("有点闷", "")
        else -> Mood(NEUTRAL_LABEL, "")
    }

    /** 骄傲五档（1 完全不端着 → 5 全副武装）；**没填的档自动回退到第 3 档** */
    internal fun prideTier(pride: Float): PrideTier = when {
        pride < -0.5f -> PrideTier("你现在很放得开，想说什么就说")
        pride < -0.15f -> PrideTier("你语气比较软")
        pride < 0.2f -> PrideTier("")                       // 第 3 档：不额外措辞
        pride < 0.6f -> PrideTier("你有点端着，不会第一时间承认想找他")
        else -> PrideTier("你很嘴硬，明明在意也要绕着说")
    }

    data class Mood(val label: String, val nudge: String)
    data class PrideTier(val prose: String)

    /** 用户回消息了：连接需求清零、沉浸度让位、骄傲软下来（"被台阶下了"） */
    fun onUserMessage(s: CompanionState): CompanionState = s.copy(
        connection = 0f,
        pride = max(-0.2f, s.pride - 0.3f),
        immersion = min(s.immersion, 0.2f),
    )

    /**
     * 把一次交互对状态的影响落在轴上（纯函数，可测）。
     *
     * ⚠️ [connection] 这个参数**刻意没有调用方**（v0.98.0 确认，不是漏接）：
     * 情绪标记（`[心情:…]`）只驱动 valence / arousal，「想不想找人」是**时间漂移**的量
     * （`tick` 按沉默时长爬升），不该被一句"开心"直接改写 —— 否则模型多写几次情绪词
     * 就能把"想念度"推满，主动来找你变成可被提示词操纵的开关。
     * 留着这个参数是给"用户倾诉/吵架"这类**明确事件**用的入口（如已有 `onUserMessage`）。
     */
    fun applyDelta(s: CompanionState, connection: Float = 0f, valence: Float = 0f, arousal: Float = 0f) =
        s.copy(
            connection = clamp(s.connection + connection, 0f, 1f),
            valence = clamp(s.valence + valence, -1f, 1f),
            arousal = clamp(s.arousal + arousal, -1f, 1f),
        )

    fun encode(s: CompanionState): String =
        listOf(s.connection, s.pride, s.valence, s.arousal, s.immersion)
            .joinToString(",") { "%.3f".format(it) } + "|" + s.atMs

    fun decode(raw: String): CompanionState {
        if (raw.isBlank()) return CompanionState()
        val parts = raw.split('|')
        val nums = parts[0].split(',').map { it.toFloatOrNull() ?: 0f }
        if (nums.size < 5) return CompanionState()
        return CompanionState(
            connection = nums[0], pride = nums[1], valence = nums[2],
            arousal = nums[3], immersion = nums[4],
            atMs = parts.getOrNull(1)?.toLongOrNull() ?: 0L,
        )
    }

    private fun clamp(v: Float, lo: Float, hi: Float) = v.coerceIn(lo, hi)
    private fun min(a: Float, b: Float) = if (a < b) a else b
    private fun max(a: Float, b: Float) = if (a > b) a else b

    /** 朝目标走，最多走 step，**不许越过目标**（越过就是振荡，表现为心情反复横跳） */
    private fun toward(current: Float, target: Float, step: Float): Float {
        if (step <= 0f) return current
        return if (current < target) min(target, current + step) else max(target, current - step)
    }
}
