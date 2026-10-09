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
import com.menghuan.app.llm.OpenAiClient
import com.menghuan.app.notify.NotifyAlert
import kotlinx.coroutines.flow.first

/** 主动关心的默认随机时间段（v0.56.8）：20:00–23:00 */
const val DEFAULT_PROACTIVE_WINDOW_START = "20:00"
const val DEFAULT_PROACTIVE_WINDOW_END = "23:00"

/**
 * 人设主动关心（v0.38.0；**v0.56.8 改成"随机时刻 + 时间感知 + 每天条数自定义"**）。
 *
 * 机制：
 * - **每个角色一套**（v0.56.8 第二批）：各自的时间段（起–止，默认 20:00–23:00）
 *   与**每天最多几条**（默认 1）、各自的开关 —— 所以是**每个角色一颗闹钟**，互不干扰；
 * - "最多几条"是**上限不是定额**：当天第一次要发时，在 `1..上限` 里**随机摇一个数**当今天的计划，
 *   当天就按这个数发完，不超过上限就行（今天设 5 条，可能只来 2 条）；
 * - 每次到点发完，**在剩下的窗口里再随机取一个时刻**排下一次；
 *   窗口过完了（或今天的计划条数发完了）就排到明天的窗口 —— "什么时候来"是随机的，不是准点打卡；
 * - 内容交给模型，并**把当前时间告诉它**（现在是几点 + 早上/中午/下午/晚上/深夜），
 *   让它说贴合时间的话（早上问候早、深夜轻声）；
 * - **顺便看一眼用户的动态**（v0.79.0，用户点名「不跟 AI 聊的时候它也会看我动态、像真人一样」）：
 *   组装上下文时把用户最近发的、**这个角色还没回过也没赞过**的帖子一并给它（最多 5 条），
 *   它可以顺手回一句或点个赞 —— 动作走 [MomentMarker] 标记，落库走 [MomentActions]。
 *   用户不聊天时动态也被回应，就是靠这一条；**不新增闹钟、不多花一次请求**（同一次调用里决定）。
 * - 一轮只服务**这一个角色**（闹钟上带着人设 id）；老版本那条全局闹钟没有 id，
 *   走兼容分支：所有开了开关的角色里挑一个 —— 今天发得最少、其次最久没发过的优先；
 * - 每次都要 API 配置齐全、生成成功才落库与计数 —— 宁缺毋滥，绝不打扰。
 */
object ProactiveChat {

    private const val SYSTEM_INSTRUCTION =
        "请以你的人设身份，主动给用户发一条消息。" +
            "结合下面给出的**当前时间**、用户档案与最近任务完成情况，自然地表达关心、问候或轻轻的推动：" +
            "该问候早的时候就说早，到深夜就轻声一点、不要再安排白天的事；" +
            "聊得好就顺着聊，任务落下了就温和地提一句，不要指责。" +
            "硬性要求：不超过 60 字；只输出这一条消息本身，不要任何解释、列表、标题或 emoji 堆砌；" +
            "不要出现「我注意到系统显示」这类出戏措辞。"

    /** 通知渠道（低打扰） */
    // v0.62.0 换代（_v2）：渠道声音创建即锁死，不换 id 老装机永远补不上提示音
    private const val CHANNEL_ID = "proactive_greeting_v2"
    private const val LEGACY_CHANNEL_ID = "proactive_greeting"
    private const val NOTIFICATION_ID = 2_000_000_006

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
     * 按**每个角色自己**的时间段**随机**排下一次（v0.56.8 第二批）。
     *
     * 开了开关的角色各排一颗；关掉的（表里值为 0）把它的闹钟摘掉。
     * 三个调用点（App 启动 / 开机重排 / 到点自排）都用它，省得各自读配置。
     */
    suspend fun scheduleNext(settings: SettingsRepository, context: Context) {
        // v0.56.7 及更早那条**全局**闹钟（不带人设 id）先摘掉，免得和按角色的闹钟重复触发
        runCatching { com.menghuan.app.notify.AlarmScheduler.cancelProactive(context) }
        settings.personaProactive.first().forEach { (id, on) ->
            runCatching {
                if (on) {
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
     * 开着就按它自己的窗口重新随机排一次，关着就把闹钟摘掉。
     */
    suspend fun schedulePersona(settings: SettingsRepository, context: Context, personaId: Long) {
        runCatching { com.menghuan.app.notify.AlarmScheduler.cancelProactive(context) }
        runCatching {
            val on = settings.personaProactive.first()[personaId] == true
            if (on) {
                val (start, end) = settings.proactiveWindow(personaId).first()
                com.menghuan.app.notify.AlarmScheduler
                    .scheduleProactiveForPersona(context, personaId, start, end)
            } else {
                com.menghuan.app.notify.AlarmScheduler.cancelProactiveForPersona(context, personaId)
            }
        }
    }

    /**
     * 执行一次主动关心流程。全程静默：任何前置不满足都直接返回，不报错不打扰。
     *
     * @param onlyPersonaId 这一枪打给谁（闹钟上带的人设 id）；-1 = 老版本那条全局闹钟，
     *                      退回"所有开了开关的角色里挑一个"。
     * @return true = 本次真的生成并落库了一条消息
     */
    suspend fun maybeGreet(
        db: AppDatabase,
        settingsRepository: SettingsRepository,
        context: Context,
        onlyPersonaId: Long = -1L,
    ): Boolean {
        val enabled = if (onlyPersonaId >= 0L) {
            setOf(onlyPersonaId)
        } else {
            settingsRepository.personaProactive.first().filterValues { it }.keys
        }
        if (enabled.isEmpty()) return false

        val api = settingsRepository.apiConfig.first()
        if (!api.isComplete) return false

        val today = java.time.LocalDate.now().toEpochDay()
        val personas = db.personaDao().observeAll().first()
            .filter { it.id != MENTOR_PERSONA_ID && it.id in enabled }
        if (personas.isEmpty()) return false

        // ① 各自的额度：每个角色自己的"今天已发 / 今天计划"（计划条数 = 在 1..上限 里随机摇的）
        val counts = HashMap<Long, Int>()
        val plans = HashMap<Long, Int>()
        personas.forEach { p ->
            counts[p.id] = settingsRepository.proactiveSentTodayOf(p.id, today)
            plans[p.id] = settingsRepository.proactivePlannedToday(
                p.id, today, settingsRepository.proactiveDailyLimit(p.id).first(),
            )
        }
        val pending = personas.filter { (counts[it.id] ?: 0) < (plans[it.id] ?: 1) }
        if (pending.isEmpty()) return false // 今天的计划条数已经发完 → 明天的窗口再见

        // ② 这一轮挑谁（多角色时也适用）：今天发得最少、其次最久没发过的（并列时随机）
        val lastDays = pending.associate { it.id to settingsRepository.proactiveLastDayOf(it.id) }
        val target = pending.shuffled()
            .minWithOrNull(compareBy({ counts[it.id] ?: 0 }, { lastDays[it.id] ?: 0L }))
            ?: return false


        // ②.5 她现在想不想开口（v0.72.0）：五轴按时间漂移之后，意愿低于 CONSIDER 这一轮就不发。
        // 跳过**不消耗今天的配额**（不写 sent 计数）—— 所以「她今天没找我」可能是真没想说，不是额度用完了。
        val nowMs = System.currentTimeMillis()
        val stale = CompanionLogic.decode(settingsRepository.companionStateRaw(target.id).first())
        val sinceMsgMin = settingsRepository.companionLastMessageAt(target.id).first()
            .let { if (it > 0L) (nowMs - it) / 60_000f else 120f }
        // 补算的漂移长度用「离上次算过多久」，加速段看「离上次收到他消息多久」——两件事，不能混成一个
        val sinceCalcMin = if (stale.atMs > 0L) (nowMs - stale.atMs) / 60_000f else sinceMsgMin
        val state = CompanionLogic
            .tick(stale, sinceCalcMin.coerceAtMost(7 * 24 * 60f), sinceMsgMin)
            .copy(atMs = nowMs)
        runCatching { settingsRepository.setCompanionStateRaw(target.id, CompanionLogic.encode(state)) }
        if (CompanionLogic.desire(state).ordinal < ReachOut.CONSIDER.ordinal) {
            // 不发也要留证据：不然排查「她为什么不主动找我」时，日志里一个字都没有
            android.util.Log.i("ProactiveChat", "personaId=" + target.id + " 意愿=" +
                CompanionLogic.desire(state) + "，这一轮不发")
            return false
        }

        // 上下文：**当前时间**（v0.56.8 加，让它说贴合时间的话）+ 档案 + 近 7 天未完成任务
        // v0.70.0：主动关心是**这个角色**要说的话，只带它自己的记忆 + 允许共享的基础档案
        val facts = MemoryScope.visible(
            db.profileFactDao().observeAll().first(),
            target.id, runCatching { settingsRepository.profileSharedReadable.first() }.getOrDefault(true),
        )
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
        val weekAgo = java.time.LocalDate.now(zone).minusDays(7)
            .atStartOfDay(zone).toInstant().toEpochMilli()
        val tasks = db.taskDao().observeAll().first()
            .filter { !it.isDone && (it.dueAt == 0L || it.dueAt >= weekAgo) }
            .take(8)
        val momentsBlock = userMomentsBlock(db, target.id)
        val userContext = buildString {
            append("【现在的时间】").append(hhmm).append("（").append(period).append("）\n\n")
            // 心情只给模型看：promptContext 自己带了「不要把这段念给用户」那句约束
            val moodLine = CompanionLogic.promptContext(state)
            if (moodLine.isNotBlank()) append(moodLine).append("\n\n")
            append("【用户档案】")
            if (facts.isEmpty()) append("暂无")
            else facts.take(10).forEach { append("\n- ").append(it.key).append("：").append(it.value) }
            append("\n\n【最近未完成任务】")
            if (tasks.isEmpty()) append("暂无")
            else tasks.forEach { append("\n- ").append(it.title) }
            // v0.79.0：用户最近发的动态（已经回过/赞过的不再列，避免对同一条反复评论）
            if (momentsBlock.isNotBlank()) {
                append("\n\n").append(momentsBlock)
                append(
                    "\n\n如果你想对上面某条动态说点什么，可以在消息之外顺手回一句或点个赞：" +
                        "回帖写 `[动态#编号:内容]`、点赞写 `[点赞:编号]`（编号用行首那个数字）。" +
                        "**没有想说的就别写** —— 为了凑动作而回比不回更糟；" +
                        "标记 App 会剥掉，不会显示给用户，正文里也别解释它们。",
                )
            }
            append("\n\n请直接输出这条主动消息：")
        }

        val reply = runCatching {
            OpenAiClient(api.baseUrl, api.apiKey, api.model)
                .collectReply(
                    target.systemPrompt,
                    listOf(ChatMessage(conversationId = 0, isFromUser = true, content = userContext)),
                )
        }.getOrNull() ?: return false
        val raw = reply.text.trim()
        if (raw.isEmpty()) return false
        // 动态动作（v0.79.0）：标记**先剥掉再落消息** —— 屏幕上出现 `[动态#3:…]` 就是把内部协议漏给用户。
        // 只剩标记、没有正文的那种回复（模型光顾着回帖忘了说话）整轮丢掉：
        // 主动关心是要"给你发一句话"，没有一句话就不该打扰你，那一次也不计数（下次窗口照常）。
        val clean = MomentMarker.parse(raw).cleanText
        if (clean.isEmpty()) return false
        MomentActions.apply(db, target.id, raw, MomentActions.Limits.PROACTIVE)

        db.messageDao().insert(
            ChatMessage(conversationId = target.id, isFromUser = false, content = clean)
        )
        val sent = (counts[target.id] ?: 0) + 1
        settingsRepository.setProactiveToday(target.id, today, sent, plans[target.id] ?: 1)
        notify(context, target.id, target.name, clean)
        return true
    }

    /**
     * 用户最近发的、**这个角色还没回过也没赞过的**帖子（v0.79.0），拼成给模型看的清单。
     *
     * 三条边界：
     * - 只看**用户自己发的**（`personaId == 0`）—— 用户的原话是"我不跟 AI 聊的时候它也会看我动态"，
     *   重点是"看我的"。别的角色的帖子属于另一件事（真人也会刷到，但那是另一条需求）；
     * - **已经回过或赞过的不再摆到它面前**：真人不会对同一条反复评论。这天然防止了
     *   "每天主动关心一次、就把同一条帖子回十遍"的刷屏；
     * - 最多 5 条、最新的在前（与聊天里那份 [MomentMarker.feedBlock] 同一个形状，
     *   模型在两条链路上看到的格式一致）。
     *
     * 空则返回空串（调用方不塞空标题 —— 那既是白花 token，又会勾模型编一个编号出来）。
     *
     * `internal` 而不是 private：这段"挑哪些帖给它看"的规则（只列用户发的、跳过已回/已赞）
     * 是 v0.79.0 这条链路的核心，值得单独测（`ProactiveChatTest`）。
     */
    internal suspend fun userMomentsBlock(db: AppDatabase, personaId: Long): String {
        val all = runCatching { db.momentDao().observeAll().first() }.getOrDefault(emptyList())
        val myLikeId = personaId.toString()
        val fresh = all.filter { it.parentId == 0L && it.personaId == 0L }
            .sortedByDescending { it.createdAt }
            .filter { post ->
                all.none { it.parentId == post.id && it.personaId == personaId } &&
                    !MomentMath.likedBy(post.likedBy, myLikeId)
            }
            .take(5)
        if (fresh.isEmpty()) return ""
        return MomentMarker.feedBlock(
            fresh.map { MomentMarker.feedLine(id = it.id, author = "用户", text = it.text) },
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
            NOTIFICATION_ID + (personaId % 1000L).toInt(),
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
        runCatching { manager.notify(NOTIFICATION_ID + (personaId % 1000L).toInt(), notification) }
    }
}
