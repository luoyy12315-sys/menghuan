package com.menghuan.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.menghuan.app.data.db.AppDatabase
import com.menghuan.app.data.model.ChatMessage
import com.menghuan.app.data.model.Persona
import com.menghuan.app.llm.OpenAiClient
import com.menghuan.app.notify.NotifyAlert
import com.menghuan.app.ui.call.IncomingCallActivity
import kotlinx.coroutines.flow.first

/** 这一通电话是谁引起的（只用于日志与以后统计，不影响行为） */
enum class AiCallReason {
    SCHEDULED,
    /** v0.94 已下线：「几天没聊」那一路；保留枚举值只是不让历史日志里的引用变孤儿 */
    @Deprecated("主动电话已并入主动关心的随机时刻（v0.94），不再有这个原因")
    SMART,
    REQUESTED,
}

/**
 * AI 主动打电话（v0.53.5）。
 *
 * 到点之后做两件事：**让模型按当前情境生成开场白**，然后**发一个来电**（Android 14+ 拿到
 * 全屏通知权限就是满屏来电界面，拿不到就退化成高优先级通知 + 铃声，点一下照样进通话）。
 * 接通后走的就是现有的实时语音通话引擎 —— 这里只负责"把人叫起来"。
 *
 * 三条铁律（跟"人设主动关心"一脉相承）：
 * - **任何一步失败都静默返回 false**，绝不因为"想打个电话"把 App 弄崩；
 * - **频次与静默在这里再兜一层**（定时/智能两路 Receiver 已判过，口头约定那一路绕过 Receiver）；
 * - **开场白由 AI 先开口**（用户点名），所以要在这里就生成好，不能让用户接起来先干等。
 */
object AiCall {

    // v0.62.0 换代（_v2）：这条渠道补了铃声与震动，而渠道配置创建即锁死——
    // 不换 id，已经装过机的用户拿到的还是老那条（可能连声音都被系统档压着）
    private const val CHANNEL_ID = "ai_call_v2"
    private const val LEGACY_CHANNEL_ID = "ai_call"
    private const val NOTIFICATION_ID = 2_000_000_009

    /** 来电页要的三个 extra */
    const val EXTRA_PERSONA_ID = "callPersonaId"
    const val EXTRA_PERSONA_NAME = "callPersonaName"
    const val EXTRA_GREETING = "callGreeting"

    /** 接听后回主界面时带的自定义 extra：让 MainActivity 直接跳到这条人设的通话页 */
    const val EXTRA_OPEN_CALL_PERSONA = "openCallPersonaId"

    /** v0.53.7：把"AI 打来的"这件事与开场白一路带到通话页（它决定谁先开口 + 记录卡落哪侧） */
    const val EXTRA_OPEN_CALL_GREETING = "openCallGreeting"
    const val EXTRA_OPEN_CALL_BY_AI = "openCallByAi"

    private const val GREETING_INSTRUCTION =
        "现在你要给用户打电话，接通后**由你先开口**。" +
            "结合下面给出的用户档案和最近的聊天片段，说一句自然的开场白：" +
            "聊得好就顺着聊。" +
            "硬性要求：不超过 50 字；只输出这句话本身，不要任何解释、列表、标题或 emoji 堆砌；" +
            "不要出现「我看到系统显示」这类出戏措辞。"

    /**
     * 发起一次来电（v0.53.6：**按人设**）。返回 true = 真的响了（开场白也生成好了）。
     *
     * 现在只剩两路：
     * - **REQUESTED**：用户在聊天里点名要的那一通，不受任何预设限制；
     * - **byExplicitSchedule**：用户自己排的**定时电话**（每条只看它自己的启用状态，
     *   不看静默时段、不占任何名额 —— 他可能一天配两条）。
     *
     * v0.94：「AI 自己决定何时打」那一路（几天没聊 + 每天一次）整条下线，
     * 主动电话并入主动关心的随机时刻（见 [proactiveRing]）。
     */
    suspend fun ring(
        context: Context,
        db: AppDatabase,
        settings: SettingsRepository,
        personaId: Long,
        reason: AiCallReason,
        instructionOverride: String? = null,
        byExplicitSchedule: Boolean = false,
    ): Boolean {
        if (personaId <= 0L) return false

        // ⚠️ v0.53.8：**用户在聊天里点名要求的那一通（REQUESTED）不受任何预设限制** ——
        // 不看那个人设的规则、不管静默时段、也不算每天次数。
        // 用户原话是"我叫他待会打他也得打"。
        // 踩过的坑：以前这里查了总开关与人设规则，用户明明叫它打、它却静默不打，
        // 而且用户完全不知道为什么（界面上什么提示都没有）。
        val requested = reason == AiCallReason.REQUESTED
        // v0.94：「几天没聊就主动打」整条下线（主动电话并入主动关心的随机时刻，见 proactiveRing）。
        // 现在只剩两路：用户点名的、用户自己排的定时电话。
        if (!requested && !byExplicitSchedule) return false
        // 规则只用来拿老装机写过的「说什么」（主动电话弹层 v0.56.8 起就不再编辑它，只剩存量）
        val rule = runCatching { settings.aiCallRules.first() }
            .getOrDefault(emptyList())
            .firstOrNull { it.personaId == personaId }

        val api = runCatching { settings.apiConfig.first() }.getOrNull() ?: return false
        if (!api.isComplete) return false

        val instruction = instructionOverride?.takeIf { it.isNotBlank() } ?: rule?.instruction.orEmpty()
        val persona = runCatching { db.personaDao().getById(personaId) }.getOrNull() ?: return false
        val greeting = runCatching {
            generateGreeting(db, api, persona, instruction, settings)
        }.getOrNull() ?: return false
        if (greeting.isBlank()) return false

        postIncomingCall(context, persona.id, persona.name, greeting)
        return true
    }

    /**
     * 主动关心链打来的电话（v0.94）：电话**并入主动关心的随机时刻**，
     * 不再走「几天没聊」那套独立闹钟与判定 —— 开关 / 静默时段 / 当日次数
     * 全由调用方（ProactiveChat.maybeGreet）判过，这里只负责生成开场白 + 发来电。
     * 老装机在「主动电话」里写过的「说什么」继续生效（rule.instruction 存量）。
     */
    suspend fun proactiveRing(
        context: Context,
        db: AppDatabase,
        settings: SettingsRepository,
        persona: Persona,
    ): Boolean {
        val api = runCatching { settings.apiConfig.first() }.getOrNull() ?: return false
        if (!api.isComplete) return false
        val instruction = runCatching { settings.aiCallRules.first() }
            .getOrDefault(emptyList())
            .firstOrNull { it.personaId == persona.id }?.instruction.orEmpty()
        val greeting = runCatching {
            generateGreeting(db, api, persona, instruction, settings)
        }.getOrNull() ?: return false
        if (greeting.isBlank()) return false
        postIncomingCall(context, persona.id, persona.name, greeting)
        return true
    }

    /**
     * App 启动 / 开机 / 覆盖安装后重排闹钟。
     * **只排闹钟、不发任何请求** —— 与"进程启动不主动打 LLM"的既有约定一致。
     *
     * v0.94：「主动电话」的"每天检查一次"闹钟整条下线（电话并入主动关心的随机时刻，
     * 由 ProactiveChat.scheduleNext 统一排）；这里只剩**定时电话**那一条条。
     */
    suspend fun rearm(settings: SettingsRepository, context: Context) {
        // 定时电话：每条一个闹钟（只看条目自己的启用状态，不再有总开关）
        val scheduled = runCatching { settings.scheduledMessages.first() }.getOrDefault(emptyList())
        scheduled
            .filter { it.kind == KIND_SCHEDULED_CALL && it.enabled }
            .forEach { call ->
                com.menghuan.app.notify.AlarmScheduler
                    .scheduleScheduledMessage(context, call.id, call.time)
            }
    }

    // v0.53.6：不再"自动挑一个人设"——规则按人设存，谁来打完全由规则决定
    // （旧版 pickPersona 的"指定人设 → 主人设 → 第一个人设"这套兜底已经不需要了）

    /**
     * 让模型写一句开场白（档案 + 最近几句对话，v0.94 起不再带任务块）。
     *
     * [instruction] = 用户在这个角色的「电话」里填的**"说什么"**（v0.54.7）：
     * 非空时作为**硬性内容要求**排在情境材料前面 —— 用户明确要我说的，优先于模型自己发挥。
     */
    private suspend fun generateGreeting(
        db: AppDatabase,
        api: ApiConfig,
        persona: Persona,
        instruction: String,
        settings: SettingsRepository,
    ): String? {
        // v0.70.0：记忆按人设分开 —— 这通电话是打给**这个角色**的，只该带上它自己聊出来的记忆
        val sharedReadable = runCatching { settings.profileSharedReadable.first() }.getOrDefault(true)
        // v0.98.0：走两条线（基础档案全带 + 她自己的记忆 ≤ PROFILE_FACTS_MAX），
        // 不再合成一条整体截断 —— 否则用户基础档案一多，她的记忆一条都进不来。
        val facts = MemoryScope.split(
            runCatching { db.profileFactDao().observeAll().first() }.getOrDefault(emptyList()),
            persona.id, sharedReadable,
        ).forInject(PromptBlocks.PROFILE_FACTS_MAX)
        // v0.94（用户拍板「砍干净、不加设置」）：任务块从开场白砍掉 —— 任务提醒归任务闹钟，
        // 电话一接通就背待办是最出戏的开头。任务现状只留聊天链。
        // 最近聊天片段走 PromptBlocks 共享块（三条链同一形状）。
        val recentBlock = PromptBlocks.recentChatBlock(db, persona.id)

        val userContext = buildString {
            if (instruction.isNotBlank()) {
                append("【这次打电话要说的内容（用户指定，必须围绕它开口）】\n")
                append(instruction)
                append("\n\n")
            }
            // v0.98.0：套上「别把档案念出来」那段包装语（用户可在设置里改）。
            // 开场白是被**说**出来的，最容易变成"你的职业是…作息是…"这种报户口。
            append("【用户档案】")
            append(
                runCatching { settings.profilePreamble.first() }
                    .getOrDefault(AiPrompts.DEFAULT_PROFILE_PREAMBLE),
            )
            if (facts.isEmpty()) append("暂无")
            // 上限已在 split(...).forInject(...) 里定过，这里不再重复截
            else facts.forEach { append("\n- ").append(it.key).append("：").append(it.value) }
            append("\n\n").append(recentBlock)
            append("\n\n").append(GREETING_INSTRUCTION)
        }

        val reply = OpenAiClient(api.baseUrl, api.apiKey, api.model).collectReply(
            persona.systemPrompt,
            listOf(ChatMessage(conversationId = 0, isFromUser = true, content = userContext)),
        )
        // v0.82.0：AI 打电话的开场白也是一次 LLM 调用，进用量账（与主动关心同一盲区）。
        runCatching {
            UsageTracker.add(settings, reply.usageTokens ?: 0, api.model,
                reply.promptTokens, reply.cachedPromptTokens)
        }
        val text = reply.text.trim()
        return text.takeIf { it.isNotEmpty() }?.take(160)
    }

    /**
     * 发来电：能要全屏就要全屏（锁屏直接弹来电界面），要不到就高优先级通知兜底。
     * 60 秒没人理自动撤掉，不留在通知栏里当垃圾。
     */
    private fun postIncomingCall(context: Context, personaId: Long, name: String, greeting: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        // 来电渠道**不跟着勿扰降级**（打电话就是要叫得人），v0.62.0 起显式设上铃声与震动；
        // 真正"穿勿扰响"靠的是来电页把铃声放到**闹钟音轨**上（见 CallRingtone.loopPlayer）
        NotifyAlert.dropLegacy(manager, listOf(LEGACY_CHANNEL_ID))
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "AI 来电", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "AI 主动打给你的来电（可在每个角色会话右上角 ⋮ 的整页设置里关掉「允许主动打电话」）"
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    android.media.AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                enableVibration(true)
                // 来电是"一直震到接"：repeat 从下标 1 起循环（0 = 不循环）。
                // ⚠️ SDK 里的方法名是 createWaveform，不是网上常写的 createWavePattern
                setVibrationEffect(
                    android.os.VibrationEffect.createWaveform(longArrayOf(0, 250, 250, 250), 1)
                )
            }
        )

        val intent = Intent(context, IncomingCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_PERSONA_ID, personaId)
            .putExtra(EXTRA_PERSONA_NAME, name)
            .putExtra(EXTRA_GREETING, greeting)
        val fullScreen = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(name)
            .setContentText("想跟你说话")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(fullScreen)
            .setTimeoutAfter(60_000L)
        if (canUseFullScreenIntent(context)) {
            builder.setFullScreenIntent(fullScreen, true)
        }
        runCatching { manager.notify(NOTIFICATION_ID, builder.build()) }
    }

    /**
     * Android 14（API 34）起 `USE_FULL_SCREEN_INTENT` 默认不给普通应用，
     * 必须用户在系统设置里手动允许；拿不到就退化成"通知 + 铃声"。
     */
    fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return runCatching { manager.canUseFullScreenIntent() }.getOrDefault(false)
    }
}
