package com.menghuan.app.data

import android.app.NotificationChannel
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
import kotlinx.coroutines.flow.first

/**
 * 人设主动关心（v0.38.0）：每晚 20:30 由 ProactiveReceiver 触发，
 * 让最常聊的人设以自己的口吻主动发一条消息进会话，并推送通知。
 *
 * 约束：
 * - **按角色开关**（v0.54.4：在每个人设的整页里，默认关），只有开了的角色才会轮到；
 * - **每个角色每天最多一条**（persona_proactive_last_day 按 id 记 epochDay 去重），
 *   API 配置齐全、生成成功时才落库——宁缺毋滥，绝不打扰；
 * - 内容基于档案 + 近 7 天任务情况，硬约束 60 字内、自然口吻、不解释"我在执行任务"。
 */
object ProactiveChat {

    private const val SYSTEM_INSTRUCTION =
        "请以你的人设身份，主动给用户发今晚的一条消息。" +
            "结合下面给出的用户档案与最近任务完成情况，自然地表达关心、问候或轻轻的推动：" +
            "聊得好就顺着聊，任务落下了就温和地提一句，不要指责。" +
            "硬性要求：不超过 60 字；只输出这一条消息本身，不要任何解释、列表、标题或 emoji 堆砌；" +
            "不要出现「我注意到系统显示」这类出戏措辞。"

    /** 通知渠道（低打扰） */
    private const val CHANNEL_ID = "proactive_greeting"
    private const val NOTIFICATION_ID = 2_000_000_006

    /**
     * 执行一次主动关心流程。全程静默：任何前置不满足都直接返回，不报错不打扰。
     * @return true = 本次真的生成并落库了一条消息
     */
    suspend fun maybeGreet(db: AppDatabase, settingsRepository: SettingsRepository, context: Context): Boolean {
        // v0.54.4：改为**按角色**——逐个检查开了「主动关心」的人设，每人每天最多一条
        val enabled = settingsRepository.personaProactive.first().filterValues { it }.keys
        if (enabled.isEmpty()) return false

        val api = settingsRepository.apiConfig.first()
        if (!api.isComplete) return false

        val today = java.time.LocalDate.now().toEpochDay()
        val personas = db.personaDao().observeAll().first()
            .filter { it.id != MENTOR_PERSONA_ID && it.id in enabled }
        if (personas.isEmpty()) return false

        // 上下文：档案（长期画像）+ 近 7 天任务完成情况（所有角色共读一份）
        val facts = db.profileFactDao().observeAll().first()
        val zone = java.time.ZoneId.systemDefault()
        val weekAgo = java.time.LocalDate.now(zone).minusDays(7)
            .atStartOfDay(zone).toInstant().toEpochMilli()
        val tasks = db.taskDao().observeAll().first()
            .filter { !it.isDone && (it.dueAt == 0L || it.dueAt >= weekAgo) }
            .take(8)
        val userContext = buildString {
            append("【用户档案】")
            if (facts.isEmpty()) append("暂无")
            else facts.take(10).forEach { append("\n- ").append(it.key).append("：").append(it.value) }
            append("\n\n【最近未完成任务】")
            if (tasks.isEmpty()) append("暂无")
            else tasks.forEach { append("\n- ").append(it.title) }
            append("\n\n请直接输出这条主动消息：")
        }

        var sent = false
        for (persona in personas) {
            if (settingsRepository.proactiveLastDayOf(persona.id) == today) continue
            val reply = runCatching {
                OpenAiClient(api.baseUrl, api.apiKey, api.model)
                    .collectReply(
                        persona.systemPrompt,
                        listOf(ChatMessage(conversationId = 0, isFromUser = true, content = userContext)),
                    )
            }.getOrNull() ?: continue
            val text = reply.text.trim()
            if (text.isEmpty()) continue

            db.messageDao().insert(
                ChatMessage(conversationId = persona.id, isFromUser = false, content = text)
            )
            settingsRepository.setProactiveLastDayOf(persona.id, today)
            notify(context, persona.id, persona.name, text)
            sent = true
        }
        return sent
    }

    private fun notify(context: Context, personaId: Long, personaName: String, preview: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "人设主动关心", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "人设每天最多一条的主动问候（可在通知设置关闭）"
            }
        )
        val home = Intent(Intent.ACTION_VIEW, Uri.parse("menghuan://open/chatList"), context, MainActivity::class.java)
        val chat = Intent(Intent.ACTION_VIEW, Uri.parse("menghuan://chat/$personaId"), context, MainActivity::class.java)
        val contentIntent = PendingIntent.getActivities(
            context,
            NOTIFICATION_ID,
            arrayOf(home, chat),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(personaName)
            .setContentText(preview.take(80))
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview.take(200)))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }
}
