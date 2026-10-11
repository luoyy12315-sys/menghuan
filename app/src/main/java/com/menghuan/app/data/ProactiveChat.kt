package com.menghuan.app.data

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.menghuan.app.MainActivity
import com.menghuan.app.data.db.AppDatabase
import com.menghuan.app.data.model.ChatMessage
import com.menghuan.app.data.model.Persona
import com.menghuan.app.llm.OpenAiClient
import com.menghuan.app.notify.NotifyAlert
import kotlinx.coroutines.flow.first

/** 主动关心的默认随机时间段（v0.56.8）：20:00–23:00 */
const val DEFAULT_PROACTIVE_WINDOW_START = "20:00"
const val DEFAULT_PROACTIVE_WINDOW_END = "23:00"

/**
 * 人设主动关心（v0.38.0；**v0.56.8 改成"随机时刻 + 时间感知 + 每天条数自定义"**；
 * **v0.94 重做成三个独立开关**：主动发消息 / 主动发动态 / 主动打电话，全关 = 不触发）。
 *
 * 机制：
 * - **每个角色一套**（v0.56.8 第二批）：各自的时间段（起–止，默认 20:00–23:00）
 *   与**每天最多几条**（默认 1）、各自的开关 —— 所以是**每个角色一颗闹钟**，互不干扰；
 * - "最多几条"是**上限不是定额**：当天第一次要发时，在 `1..上限` 里**随机摇一个数**当今天的计划，
 *   当天就按这个数发完，不超过就行（今天设 5 条，可能只来 2 条）。
 *   v0.94：它是"每天主动行为次数"——纯动态表态（不发消息的那种）也占一格，防刷屏；
 * - **一次触发只做一件事**（v0.94，用户拍板）：电话开着且次数（每天最多几通，1–9）没用完、
 *   不在静默时段（22:30–08:00）就打电话，否则发消息/做动态；电话走 [AiCall.proactiveRing]；
 * - 每次到点发完，**在剩下的窗口里再随机取一个时刻**排下一次；
 *   窗口过完了（或今天的计划条数用完了）就排到明天的窗口 —— "什么时候来"是随机的，不是准点打卡；
 * - 内容交给模型，并**把当前时间告诉它**（现在是几点 + 早上/中午/下午/晚上/深夜），
 *   让它说贴合时间的话（早上问候早、深夜轻声）；
 * - **顺便看一眼大家的动态**（v0.79.0 起看用户的，用户点名「不跟 AI 聊的时候它也会看我动态、像真人一样」；
 *   v0.95 起**其他角色的帖子也看**，用户点名「人设之间也能互相回复动态、跟真人一样」）：
 *   组装上下文时把大家最近发的、**这个角色还没回过也没赞过**的帖子一并给它（最多 5 条），
 *   它可以发一条新帖（v0.94 起发新帖**只属于这条链**）、回一句或点个赞 ——
 *   动作走 [MomentMarker] 标记，落库走 [MomentActions]。
 *   每个角色对每条帖最多表态一次（回过/赞过就不再列给它），所以两个角色不会在同一帖下互聊个没完；
 * - 一轮只服务**这一个角色**（闹钟上带着人设 id）；老版本那条全局闹钟没有 id，
 *   走兼容分支：所有开了开关的角色里挑一个 —— 今天发得最少、其次最久没发过的优先；
 * - 每次都要 API 配置齐全、生成成功才落库与计数 —— 宁缺毋滥，绝不打扰。
 */
object ProactiveChat {

    /**
     * 主动关心的**硬约束**（不可编，与调用形状绑定）：交代"你是谁、在做什么"+ 输出形状。
     * 改这里不影响解析，但删掉「只输出这一条消息本身」会让解释性文字混进落库的正文。
     */
    const val HINT_FRAMEWORK: String =
        "请以你的人设身份，主动给用户发一条消息。" +
            "只输出这一条消息本身，不要任何解释、列表、标题或 emoji 堆砌。"

    /**
     * 主动关心的**默认策略**（可在「设置 · AI 提示词 · 主动关心策略」里覆盖）：取材、语气、长度。
     *
     * ⚠️ v0.98.0 之前这段是 `private const val SYSTEM_INSTRUCTION`，**全工程零引用**
     * ——「不超过 60 字」「不要出现『我注意到系统显示』这类出戏措辞」这两条从来没生效过。
     * 这次把它接上，同时按项目既有形状（`FRAMEWORK` 硬编码 + `DEFAULT_STRATEGY` 可覆盖）拆成两段：
     * 长度这类用户有权分歧的东西（有人就想要更短/更长的关心）放可编这半。
     */
    const val DEFAULT_STRATEGY: String =
        "结合下面给出的**当前时间**、用户档案与最近的聊天片段，自然地表达关心、问候或轻轻的推动：" +
            "该问候早的时候就说早，到深夜就轻声一点、不要再安排白天的事；" +
            "聊得好就顺着聊。" +
            "硬性要求：不超过 60 字；不要出现「我注意到系统显示」这类出戏措辞。"

    /** 拼主动关心的 system prompt：人设设定词 + 硬约束 + 策略（策略留空则只用硬约束） */
    fun systemPrompt(personaPrompt: String, strategy: String = DEFAULT_STRATEGY): String = buildString {
        if (personaPrompt.isNotBlank()) append(personaPrompt).append("\n\n")
        append(HINT_FRAMEWORK)
        if (strategy.isNotBlank()) append("\n").append(strategy)
    }

    /** 通知渠道（低打扰） */
    // v0.62.0 换代（_v2）：渠道声音创建即锁死，不换 id 老装机永远补不上提示音
    private const val CHANNEL_ID = "proactive_greeting_v2"
    private const val LEGACY_CHANNEL_ID = "proactive_greeting"
    private const val NOTIFICATION_ID = 2_000_000_006

    /**
     * 这个角色的主动关心通知 / PendingIntent 用的 id（v0.97.0，审查 B38）。
     *
     * 原来两处各写一遍 `NOTIFICATION_ID + (personaId % 1000)`：
     * ① `% 1000` 的槽位只有 1000 个，两个角色的 id 相差 1000 的倍数就会**互相覆盖通知**
     * （点开还进错会话 —— contentIntent 也跟着串了）；② 同一个式子写两遍，
     * 改一处漏一处就是"通知和点击目标不是同一个 PendingIntent"。
     * 收到一个函数里、槽位放宽到 10 万（要撞得攒够 10 万个角色，实际不可能），
     * 两处必然同值。
     */
    private fun notifIdFor(personaId: Long): Int =
        NOTIFICATION_ID + (personaId % 100_000L).toInt()

    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /**
     * 下一次该在什么时候发：在 `[startHhmm]–[endHhmm]` 这个窗口里**随机取一个时刻**。
     *
     * - 窗口跨零点（止 ≤ 起）按"到第二天"处理，例如 23:00–01:00；
     * - 今天窗口还早 → 整个窗口里随机；已经在窗口里 → 从"现在 + 1 分钟"到窗口结束之间随机；
     * - 窗口已经过完（或剩下的不足 1 分钟）→ 顺延到明天同窗口。
     *
     * 纯函数（`now` 与 `random` 都作参数传进来），可单测。
     */
    fun nextFireAt(
        now: Long,
        startHhmm: String,
        endHhmm: String,
        random: java.util.Random = java.util.Random(),
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): Long {
        val startMin = AiCallLogic.minuteOfDayOf(startHhmm) ?: (20 * 60)
        val rawEnd = AiCallLogic.minuteOfDayOf(endHhmm) ?: (23 * 60)
        // 止 ≤ 起 = 跨零点：把"止"挪到第二天，后面用绝对时刻计算，不再受分钟数回绕影响
        val endMin = if (rawEnd <= startMin) rawEnd + 24 * 60 else rawEnd
        val dayStart = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        var winStart = dayStart + startMin * 60_000L
        var winEnd = dayStart + endMin * 60_000L
        repeat(2) {
            if (winEnd > now + 60_000L) {
                val lo = maxOf(winStart, now + 60_000L)
                if (winEnd > lo) {
                    return lo + (random.nextDouble() * (winEnd - lo)).toLong()
                }
            }
            winStart += DAY_MS
            winEnd += DAY_MS
        }
        return winStart
    }

    /**
     * 按**每个角色自己**的时间段**随机**排下一次（v0.56.8 第二批；v0.94 门槛改为三开关任一）。
     *
     * 开了任一开关（发信息 / 发帖 / 打电话）的角色各排一颗；全关的把它的闹钟摘掉。
     * 三个调用点（App 启动 / 开机重排 / 到点自排）都用它，省得各自读配置。
     */
    suspend fun scheduleNext(db: AppDatabase, settings: SettingsRepository, context: Context) {
        // v0.56.7 及更早那条**全局**闹钟（不带人设 id）先摘掉，免得和按角色的闹钟重复触发
        runCatching { com.menghuan.app.notify.AlarmScheduler.cancelProactive(context) }
        val ids = runCatching { db.personaDao().observeAll().first().map { it.id } }
            .getOrDefault(emptyList())
        ids.forEach { id ->
            runCatching {
                if (settings.anyProactiveEnabled(id)) {
                    val (start, end) = settings.proactiveWindow(id).first()
                    com.menghuan.app.notify.AlarmScheduler
                        .scheduleProactiveForPersona(context, id, start, end)
                } else {
                    com.menghuan.app.notify.AlarmScheduler.cancelProactiveForPersona(context, id)
                }
            }
        }
    }

    /**
     * 只重排**这一个角色**的闹钟（设置页里开关一拨、时间段/条数一改就调它）：
     * 任一开关开着就按它自己的窗口重新随机排一次，全关就把闹钟摘掉。
     */
    suspend fun schedulePersona(settings: SettingsRepository, context: Context, personaId: Long) {
        runCatching { com.menghuan.app.notify.AlarmScheduler.cancelProactive(context) }
        runCatching {
            if (settings.anyProactiveEnabled(personaId)) {
                val (start, end) = settings.proactiveWindow(personaId).first()
                com.menghuan.app.notify.AlarmScheduler
                    .scheduleProactiveForPersona(context, personaId, start, end)
            } else {
                com.menghuan.app.notify.AlarmScheduler.cancelProactiveForPersona(context, personaId)
            }
        }
    }

    /**
     * 这一次触发干什么（v0.94 三开关：发信息 / 发帖 / 打电话）。
     *
     * - [CALL]：**电话优先**（用户拍板「一次触发只做一件事」）——开了主动打电话、
     *   今天还没打满次数、且不在静默时段，这一次就是打电话（不发消息）；
     * - [MESSAGE]：发消息（顺手做动态动作，如果发帖开着）；占「每天最多几条」的配额；
     * - [MOMENTS_ONLY]：发信息关着但发帖开着 —— 只去动态上表态，不发消息、不弹通知；
     *   **也占配额**（「每天最多几条」本质是"每天主动行为次数"——不占的话闹钟一天能
     *   触发很多次，动态会被刷屏）。配额发完 = 今天到此为止。
     * - [NONE]：这一枪什么都不做。
     */
    internal enum class Action { NONE, CALL, MESSAGE, MOMENTS_ONLY }

    internal fun decideAction(
        messageOn: Boolean,
        messageRemaining: Boolean,
        momentsOn: Boolean,
        callOn: Boolean,
        callsToday: Int,
        callLimit: Int,
        quietHours: Boolean,
    ): Action = when {
        // 电话守静默时段（22:30–08:00 不打），消息/动态不受静默管
        callOn && callsToday < callLimit && !quietHours -> Action.CALL
        messageOn && messageRemaining -> Action.MESSAGE
        momentsOn && messageRemaining -> Action.MOMENTS_ONLY
        else -> Action.NONE
    }

    /**
     * 执行一次主动关心流程。全程静默：任何前置不满足都直接返回，不报错不打扰。
     *
     * @param onlyPersonaId 这一枪打给谁（闹钟上带的人设 id）；-1 = 老版本那条全局闹钟，
     *                      退回"所有开了开关的角色里挑一个"。
     * @return true = 本次真的做成了一件事（发了消息 / 打了电话 / 在动态上表了态）
     */
    suspend fun maybeGreet(
        db: AppDatabase,
        settingsRepository: SettingsRepository,
        context: Context,
        onlyPersonaId: Long = -1L,
    ): Boolean {
        val api = settingsRepository.apiConfig.first()
        if (!api.isComplete) return false

        val today = java.time.LocalDate.now().toEpochDay()
        val quietHours = AiCallLogic.inQuietHours(AiCallLogic.minuteOfDayAt(System.currentTimeMillis()))
        // v0.94：候选 = 三个开关（发信息 / 发帖 / 打电话）有一个开着的角色
        val personas = db.personaDao().observeAll().first()
            .filter { it.id != MENTOR_PERSONA_ID }
            .filter { it.id == onlyPersonaId || onlyPersonaId < 0L }
            .filter { settingsRepository.anyProactiveEnabled(it.id) }
        if (personas.isEmpty()) return false

        // ① 各自的配额：每个角色自己的"今天已发 / 今天计划"（计划条数 = 在 1..上限 里随机摇的）
        val counts = HashMap<Long, Int>()
        val plans = HashMap<Long, Int>()
        personas.forEach { p ->
            counts[p.id] = settingsRepository.proactiveSentTodayOf(p.id, today)
            plans[p.id] = settingsRepository.proactivePlannedToday(
                p.id, today, settingsRepository.proactiveDailyLimit(p.id).first(),
            )
        }

        // ② 各角色这一次该干什么（三开关 + 配额 + 静默时段共同决定）
        data class Cand(val persona: Persona, val action: Action)

        val actionable = personas.mapNotNull { p ->
            val action = decideAction(
                messageOn = settingsRepository.personaProactive.first()[p.id] == true,
                messageRemaining = (counts[p.id] ?: 0) < (plans[p.id] ?: 1),
                momentsOn = settingsRepository.proactiveMomentsEnabled(p.id).first(),
                callOn = settingsRepository.proactiveCallEnabled(p.id).first(),
                callsToday = settingsRepository.proactiveCallsTodayOf(p.id, today),
                callLimit = settingsRepository.proactiveCallLimit(p.id).first(),
                quietHours = quietHours,
            )
            if (action == Action.NONE) null else Cand(p, action)
        }
        if (actionable.isEmpty()) return false // 今天的计划条数已经发完 → 明天的窗口再见

        // ③ 这一轮挑谁（多角色时也适用）：今天发得最少、其次最久没发过的（并列时随机）
        val lastDays = actionable.associate { it.persona.id to settingsRepository.proactiveLastDayOf(it.persona.id) }
        val target = actionable.shuffled()
            .minWithOrNull(compareBy({ counts[it.persona.id] ?: 0 }, { lastDays[it.persona.id] ?: 0L }))
            ?: return false
        val persona = target.persona

        // ③.5 她现在想不想开口（v0.72.0）：五轴按时间漂移之后，意愿低于 CONSIDER 这一轮就不发。
        // 跳过**不消耗今天的配额**（不写 sent 计数）—— 所以「她今天没找我」可能是真没想说，不是额度用完了。
        val nowMs = System.currentTimeMillis()
        val stale = CompanionLogic.decode(settingsRepository.companionStateRaw(persona.id).first())
        val sinceMsgMin = settingsRepository.companionLastMessageAt(persona.id).first()
            .let { if (it > 0L) (nowMs - it) / 60_000f else 120f }
        // 补算的漂移长度用「离上次算过多久」，加速段看「离上次收到他消息多久」——两件事，不能混成一个
        val sinceCalcMin = if (stale.atMs > 0L) (nowMs - stale.atMs) / 60_000f else sinceMsgMin
        val state = CompanionLogic
            .tick(stale, sinceCalcMin.coerceAtMost(7 * 24 * 60f), sinceMsgMin)
            .copy(atMs = nowMs)
        runCatching { settingsRepository.setCompanionStateRaw(persona.id, CompanionLogic.encode(state)) }
        if (CompanionLogic.desire(state).ordinal < ReachOut.CONSIDER.ordinal) {
            // 不发也要留证据：不然排查「她为什么不主动找我」时，日志里一个字都没有
            android.util.Log.i("ProactiveChat", "personaId=" + persona.id + " 意愿=" +
                CompanionLogic.desire(state) + "，这一轮不发")
            return false
        }

        // ④ 电话优先（v0.94）：这一枪是打电话 —— 占了次数就不再发消息。
        // 打不成（生成失败等）落回消息/动态，不白瞎这一枪。
        val messageOn = settingsRepository.personaProactive.first()[persona.id] == true
        val momentsOn = settingsRepository.proactiveMomentsEnabled(persona.id).first()
        var action = target.action
        if (action == Action.CALL) {
            val rang = runCatching { AiCall.proactiveRing(context, db, settingsRepository, persona) }
                .getOrDefault(false)
            if (rang) {
                settingsRepository.markProactiveCall(persona.id, today)
                return true
            }
            action = decideAction(
                messageOn = messageOn,
                messageRemaining = (counts[persona.id] ?: 0) < (plans[persona.id] ?: 1),
                momentsOn = momentsOn,
                callOn = false, callsToday = 0, callLimit = 0, quietHours = true,
            )
        }
        if (action == Action.NONE) return false
        val messageEligible = action == Action.MESSAGE

        // 上下文：**当前时间**（v0.56.8 加，让它说贴合时间的话）+ 档案 + 动态
        // v0.70.0：主动关心是**这个角色**要说的话，只带它自己的记忆 + 允许共享的基础档案
        // v0.98.0：走两条线（基础档案全带 + 她自己的记忆 ≤ PROFILE_FACTS_MAX），
        // 不再合成一条整体截断 —— 否则用户基础档案一多，她的记忆一条都进不来。
        val facts = MemoryScope.split(
            db.profileFactDao().observeAll().first(),
            persona.id, runCatching { settingsRepository.profileSharedReadable.first() }.getOrDefault(true),
        ).forInject(PromptBlocks.PROFILE_FACTS_MAX)
        val zone = java.time.ZoneId.systemDefault()
        val clock = java.time.LocalTime.now(zone)
        val hhmm = String.format(java.util.Locale.US, "%02d:%02d", clock.hour, clock.minute)
        val period = when (clock.hour) {
            in 5..10 -> "早上"
            in 11..13 -> "中午"
            in 14..17 -> "下午"
            in 18..22 -> "晚上"
            else -> "深夜"
        }
        // v0.94（用户拍板「砍干净、不加设置」）：任务块从主动链整块砍掉 ——
        // TA 主动找你时顺口报待办是最像机器人的行为，任务提醒归任务闹钟那条专门链路。
        // 聊天链（ChatViewModel.taskStatusBlock）保留：用户在对话里问到时它得有料。
        val momentsBlock = if (momentsOn) momentsFeedBlock(db, persona.id) else ""
        // v0.94：主动开口前先知道自己上次说到哪了（原来只有电话开场白带，三条链现在同一形状）
        val recentBlock = PromptBlocks.recentChatBlock(db, persona.id)
        val userContext = buildString {
            append("【现在的时间】").append(hhmm).append("（").append(period).append("）\n\n")
            // 心情只给模型看：promptContext 自己带了「不要把这段念给用户」那句约束
            val moodLine = CompanionLogic.promptContext(state)
            if (moodLine.isNotBlank()) append(moodLine).append("\n\n")
            // v0.98.0：套上「别把档案念出来」那段包装语。主动关心是**直接发给用户的一段话**，
            // 恰恰是最容易把档案原样背出来的场景，而这里原来没有这条约束（设置项对它静默失效）。
            append("【用户档案】")
            append(
                runCatching { settingsRepository.profilePreamble.first() }
                    .getOrDefault(AiPrompts.DEFAULT_PROFILE_PREAMBLE),
            )
            if (facts.isEmpty()) append("暂无")
            // 上限已在 split(...).forInject(...) 里定过，这里不再重复截
            else facts.forEach { append("\n- ").append(it.key).append("：").append(it.value) }
            append("\n\n").append(recentBlock)
            if (momentsOn) {
                // v0.94：**发新帖只在主动链**（聊天链已禁发）；发帖不需要编号，所以这段总是教
                append(
                    "\n\n想发条新动态就写 `[动态:一句话]` —— 内容是你的心情、正在做的事、或看到用户说的想接的那一句；" +
                        "只在真的有东西可说时发，别为刷存在感而发。",
                )
                // v0.79.0 / v0.95：大家最近发的动态（用户的 + 其他角色的；已经回过/赞过的不再列，
                // 避免对同一条反复评论——这条纪律也保证两个角色不会在同一帖下互聊个没完）
                if (momentsBlock.isNotBlank()) {
                    append("\n\n").append(momentsBlock)
                    append(
                        "\n\n如果你想对上面某条动态说点什么，可以顺手回一句或点个赞：" +
                            "回帖写 `[动态#编号:内容]`、点赞写 `[点赞:编号]`（编号用行首那个数字）。" +
                            "**没有想说的就别写** —— 为了凑动作而回比不回更糟；" +
                            "标记 App 会剥掉，不会显示给用户，正文里也别解释它们。",
                    )
                }
            }
            if (messageEligible) {
                append("\n\n请直接输出这条主动消息：")
            } else {
                // 只发动态（发信息关着或今天的消息配额发完了）：这一枪没有消息要发
                append("\n\n这次**不要输出任何聊天消息**（App 不会把它发给用户）——只做上面的动态动作；")
                append("没有想做的就什么都不写。")
            }
        }

        // v0.98.0：主动关心的策略可编（「设置 · AI 提示词 · 主动关心策略」）。
        // 读失败退回默认 —— 它进的是 system prompt，读不到不该让整枪哑火。
        val proactiveStrategy = runCatching { settingsRepository.proactiveStrategy.first() }
            .getOrDefault(DEFAULT_STRATEGY)
        val reply = runCatching {
            OpenAiClient(api.baseUrl, api.apiKey, api.model)
                .collectReply(
                    systemPrompt(persona.systemPrompt, proactiveStrategy),
                    listOf(ChatMessage(conversationId = 0, isFromUser = true, content = userContext)),
                )
        }.getOrNull() ?: return false
        // v0.82.0：主动关心也要记账——它是"看不见的自动消耗"，之前漏记导致用户月底对账低估用量。
        runCatching {
            UsageTracker.add(
                settingsRepository,
                reply.usageTokens ?: 0, api.model,
                reply.promptTokens, reply.cachedPromptTokens,
            )
        }
        val raw = reply.text.trim()
        if (raw.isEmpty()) return false

        if (!messageEligible) {
            // 只发动态：动作落库，不发消息、不弹通知；**占一次配额**（限频，见 Action 注释）
            val applied = MomentActions.apply(db, persona.id, raw, MomentActions.Limits.PROACTIVE)
            if (applied.total <= 0) return false
            val sent = (counts[persona.id] ?: 0) + 1
            settingsRepository.setProactiveToday(persona.id, today, sent, plans[persona.id] ?: 1)
            return true
        }

        // 动态动作（v0.79.0）：标记**先剥掉再落消息** —— 屏幕上出现 `[动态#3:…]` 就是把内部协议漏给用户。
        // 只剩标记、没有正文的那种回复（模型光顾着回帖忘了说话）整轮丢掉：
        // 主动关心是要"给你发一句话"，没有一句话就不该打扰你，那一次也不计数（下次窗口照常）。
        // 任务建议标记（v0.88.0）同样剥掉但**不出卡**（用户点名「只在聊天页出卡」）——
        // 这条链路的回复不是"正在聊天"，卡片没有落点；剥是为了别把标记漏给用户看。
        // 心情标记只是"聊天链"的协议（说明段只注入聊天 system prompt），主动链不解析；
        // 但模型可能顺手沿用，剥掉防漏 —— 同 [TaskSuggestionMarker] 的纪律（§零.4.2）
        val clean = com.menghuan.app.data.CompanionMarker.strip(
            com.menghuan.app.data.TaskSuggestionMarker.strip(MomentMarker.parse(raw).cleanText)
        )
        if (clean.isEmpty()) return false
        if (momentsOn) MomentActions.apply(db, persona.id, raw, MomentActions.Limits.PROACTIVE)

        db.messageDao().insert(
            ChatMessage(conversationId = persona.id, isFromUser = false, content = clean)
        )
        val sent = (counts[persona.id] ?: 0) + 1
        settingsRepository.setProactiveToday(persona.id, today, sent, plans[persona.id] ?: 1)
        notify(context, persona.id, persona.name, clean)
        return true
    }

    /**
     * 大家最近发的、**这个角色还没回过也没赞过的**帖子（v0.79.0；v0.95 起含其他角色），
     * 拼成给模型看的清单。
     *
     * 四条边界：
     * - **v0.95：其他角色的帖子也列**（用户点名「人设之间也能互相回复动态、跟真人一样」；
     *   v0.79.0 时只看用户发的，注释里留着"别的角色的帖子是另一条需求"——这就是那条需求）。
     *   作者名从 personaDao 取、标「（另一位角色）」，与聊天链那份名单同一个标注口径；
     * - **自己发的不列**：它不是"可表态对象"——这一枪就是"去动态上表态"，自己的帖列出来
     *   只会诱它自问自答（v0.85.0 时聊天链会保留自己的帖并标「你自己」，那是聊天顺手做的事；
     *   那条链现在已经完全不看动态了，全 App 只剩这一处名单）；
     * - **已经回过或赞过的不再摆到它面前**：真人不会对同一条反复评论。这天然防止了
     *   "每天主动关心一次、就把同一条帖子回十遍"的刷屏，也保证两个角色不会在同一帖下
     *   互聊个没完（每个角色对每条帖最多表态一次，用完就再也看不到它）；
     * - 最多 5 条、最新的在前（形状由 [MomentMarker.feedBlock] 定；聊天链那条同名名单已随
     *   「动态只存于主动关心链」一起移除，现在全 App 只有这一份）。
     *
     * 空则返回空串（调用方不塞空标题 —— 那既是白花 token，又会勾模型编一个编号出来）。
     *
     * `internal` 而不是 private：这段"挑哪些帖给它看"的规则是这条链路的核心，值得单独测
     * （`ProactiveChatTest`）。
     */
    internal suspend fun momentsFeedBlock(db: AppDatabase, personaId: Long): String {
        val all = runCatching { db.momentDao().observeAll().first() }.getOrDefault(emptyList())
        val fresh = all.filter { it.parentId == 0L && it.personaId != personaId }
            .sortedByDescending { it.createdAt }
            // 已回过/已赞过的不再摆到它面前（唯一判定 [MomentMath.engagedBy]）
            .filter { post -> !MomentMath.engagedBy(all, post, personaId) }
            .take(5)
        if (fresh.isEmpty()) return ""
        val names = runCatching { db.personaDao().observeAll().first() }
            .getOrDefault(emptyList()).associate { it.id to it.name }
        return MomentMarker.feedBlock(
            fresh.map {
                MomentMarker.feedLine(
                    id = it.id,
                    author = if (it.personaId == 0L) "用户" else names[it.personaId] ?: "TA",
                    text = it.text,
                    otherAi = it.personaId != 0L,
                )
            },
        )
    }

    private fun notify(context: Context, personaId: Long, personaName: String, preview: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        // v0.62.0：提示音显式设上（原来吃渠道默认值，真机上容易被 ROM 判成静音），
        // 并且**系统勿扰时自动改走"仅震动"那条渠道**
        val channelId = NotifyAlert.channel(
            context,
            CHANNEL_ID,
            "人设主动关心",
            "人设在随机时间发来的主动问候（每天条数可在角色的「主动关心」里设）",
            legacyIds = listOf(LEGACY_CHANNEL_ID),
        )
        val home = Intent(Intent.ACTION_VIEW, Uri.parse("menghuan://open/chatList"), context, MainActivity::class.java)
        val chat = Intent(Intent.ACTION_VIEW, Uri.parse("menghuan://chat/$personaId"), context, MainActivity::class.java)
        val contentIntent = PendingIntent.getActivities(
            context,
            notifIdFor(personaId),
            arrayOf(home, chat),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(personaName)
            .setContentText(preview.take(80))
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview.take(200)))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(notifIdFor(personaId), notification) }
    }
}
