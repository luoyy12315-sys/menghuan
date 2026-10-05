package com.menghuan.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.menghuan.app.data.db.AppDatabase
import com.menghuan.app.data.model.ChatMessage
import com.menghuan.app.data.model.Persona
import com.menghuan.app.llm.OpenAiClient
import com.menghuan.app.ui.call.IncomingCallActivity
import kotlinx.coroutines.flow.first

/** 这一通电话是谁引起的（只用于日志与以后统计，不影响行为） */
enum class AiCallReason { SCHEDULED, SMART, REQUESTED }

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

    private const val CHANNEL_ID = "ai_call"
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
            "结合下面给出的用户档案、最近没完成的任务和最近的聊天片段，说一句自然的开场白：" +
            "聊得好就顺着聊，任务落下了就温和地提一句，不要指责，也不要念稿子。" +
            "硬性要求：不超过 50 字；只输出这句话本身，不要任何解释、列表、标题或 emoji 堆砌；" +
            "不要出现「我看到系统显示」这类出戏措辞。"

    /**
     * 发起一次来电（v0.53.6：**按人设**）。返回 true = 真的响了（开场白也生成好了）。
     *
     * 这里只做"最后一道兜底校验"：这个人的规则、静默时段、每人设每天一次的频率。
     * 时机判定（到点 + 好久没聊）在 [com.menghuan.app.notify.AiCallReceiver] 里已经做过。
     *
     * ⚠️ v0.54.5：**总开关已删掉** —— 设置里不再有「AI 主动打电话」这一页，改成
     * **每个角色自己的开关**（会话设置页的卡片）。所以这里只看**该角色的 rule.enabled**，
     * 不再有"全局关着就全体不打"这道门槛。
     */
    suspend fun ring(
        context: Context,
        db: AppDatabase,
        settings: SettingsRepository,
        personaId: Long,
        reason: AiCallReason,
    ): Boolean {
        if (personaId <= 0L) return false

        // ⚠️ v0.53.8：**用户在聊天里点名要求的那一通（REQUESTED）不受任何预设限制** ——
        // 不看那个人设的规则、不管静默时段、也不算每天次数。
        // 用户原话是"我叫他待会打他也得打"。
        // 踩过的坑：以前这里查了总开关与人设规则，用户明明叫它打、它却静默不打，
        // 而且用户完全不知道为什么（界面上什么提示都没有）。
        val requested = reason == AiCallReason.REQUESTED
        if (!requested) {
            val rule = runCatching { settings.aiCallRules.first() }
                .getOrDefault(emptyList())
                .firstOrNull { it.personaId == personaId }
                ?: return false
            if (!rule.enabled) return false
            val now = System.currentTimeMillis()
            if (AiCallLogic.inQuietHours(AiCallLogic.minuteOfDayAt(now))) return false
            val today = java.time.LocalDate.now().toEpochDay()
            if (rule.lastCallDay == today) return false
        }

        val api = runCatching { settings.apiConfig.first() }.getOrNull() ?: return false
        if (!api.isComplete) return false

        val persona = runCatching { db.personaDao().getById(personaId) }.getOrNull() ?: return false
        val greeting = runCatching { generateGreeting(db, api, persona) }.getOrNull() ?: return false
        if (greeting.isBlank()) return false

        // 只有"AI 自己决定打的"才占每天一次的名额；用户点名的不占
        if (!requested) {
            runCatching { settings.markAiCallDone(personaId, java.time.LocalDate.now().toEpochDay()) }
        }
        postIncomingCall(context, persona.id, persona.name, greeting)
        return true
    }

    /**
     * App 启动 / 开机 / 覆盖安装后重排闹钟：**逐个启用了的人设**各排一个。
     * **只排闹钟、不发任何请求** —— 与"进程启动不主动打 LLM"的既有约定一致。
     *
     * v0.54.5：总开关删掉后，这里不再有"全局关着就全不排"的分支 —— 开关就在各个角色的
     * 规则里（[AiCallRule.enabled]），只要那个角色自己开着就排。
     */
    suspend fun rearm(settings: SettingsRepository, context: Context) {
        settings.aiCallRules.first()
            .filter { it.enabled }
            .forEach {
                com.menghuan.app.notify.AlarmScheduler
                    .scheduleAiCallForPersona(context, it.personaId, it.time)
            }
    }

    // v0.53.6：不再"自动挑一个人设"——规则按人设存，谁来打完全由规则决定
    // （旧版 pickPersona 的"指定人设 → 主人设 → 第一个人设"这套兜底已经不需要了）

    /** 让模型写一句开场白（档案 + 未完成任务 + 最近几句对话，与"主动关心"同一套取材） */
    private suspend fun generateGreeting(db: AppDatabase, api: ApiConfig, persona: Persona): String? {
        val facts = runCatching { db.profileFactDao().observeAll().first() }.getOrDefault(emptyList())
        val tasks = runCatching { db.taskDao().observeAll().first() }
            .getOrDefault(emptyList())
            .filter { !it.isDone }
            .take(6)
        val recent = runCatching { db.messageDao().observeConversation(persona.id).first() }
            .getOrDefault(emptyList())
            .takeLast(6)

        val userContext = buildString {
            append("【用户档案】")
            if (facts.isEmpty()) append("暂无")
            else facts.take(10).forEach { append("\n- ").append(it.key).append("：").append(it.value) }
            append("\n\n【最近未完成任务】")
            if (tasks.isEmpty()) append("暂无")
            else tasks.forEach { append("\n- ").append(it.title) }
            append("\n\n【最近的聊天片段】")
            if (recent.isEmpty()) append("暂无")
            else recent.forEach { m ->
                append("\n- ").append(if (m.isFromUser) "用户：" else "你：")
                    .append(m.content.take(60))
            }
            append("\n\n").append(GREETING_INSTRUCTION)
        }

        val reply = OpenAiClient(api.baseUrl, api.apiKey, api.model).collectReply(
            persona.systemPrompt,
            listOf(ChatMessage(conversationId = 0, isFromUser = true, content = userContext)),
        )
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

        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "AI 来电", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "AI 主动打给你的来电（可在每个角色会话右上角 ⋮ 的整页设置里关掉「允许主动打电话」）"
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
