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
import com.menghuan.app.data.model.Persona
import com.menghuan.app.llm.OpenAiClient
import kotlinx.coroutines.flow.first

/**
 * 「定时主动消息」的执行体（v0.53.5）：到点之后让指定人设按用户写的指令说一段，落进会话并通知。
 *
 * 与「人设主动关心」的分工：
 * - 主动关心 = 每天固定时间、自动挑人设、内容自由发挥；
 * - 这里 = **用户自己配的**（人设 / 时间 / 指令都可指定，可多条），
 *   而且**可以带联网搜索** —— 搜到的东西喂给模型再写，"今天的新闻"才是真的今天的。
 *
 * 两条底线：
 * 1. **搜不到就如实说搜不到**，绝不让模型拿旧知识冒充新闻（这是本项目最容易造出假信息的地方）；
 * 2. 任何失败都静默返回 false，不因为一条定时消息把 App 弄崩。
 */
object ScheduledMessageSender {

    private const val CHANNEL_ID = "scheduled_message"
    private const val NOTIFICATION_ID_BASE = 2_000_000_100

    suspend fun send(
        context: Context,
        db: AppDatabase,
        settings: SettingsRepository,
        message: ScheduledMessage,
    ): Boolean {
        val api = runCatching { settings.apiConfig.first() }.getOrNull() ?: return false
        if (!api.isComplete) return false
        if (message.instruction.isBlank()) return false

        val persona = pickPersona(db, settings, message.personaId) ?: return false

        // 需要联网时先搜（Key 没配就当作没搜到，由下面的措辞如实说明）
        val searchBlock: String = if (message.withSearch) {
            val provider = runCatching { settings.searchProvider.first() }.getOrDefault(SEARCH_BOCHA)
            val key = runCatching { settings.searchApiKey.first() }.getOrDefault("")
            if (key.isBlank()) {
                ""
            } else {
                val hits = runCatching { WebSearchClient.search(provider, key, message.instruction) }
                    .getOrDefault(emptyList())
                WebSearchClient.toPromptBlock(hits, WebSearchClient.labelOf(provider))
            }
        } else {
            ""
        }

        val facts = runCatching { db.profileFactDao().observeAll().first() }.getOrDefault(emptyList())
        val tasks = runCatching { db.taskDao().observeAll().first() }
            .getOrDefault(emptyList())
            .filter { !it.isDone }
            .take(6)

        val userContext = buildString {
            append("现在是").append(java.time.LocalTime.now().withSecond(0).withNano(0))
                .append("，用户给你设了一条定时消息，到点了。请按下面的要求，主动给用户发一条消息。\n\n")
            append("【用户给的要求】\n").append(message.instruction).append("\n\n")
            if (message.withSearch) {
                if (searchBlock.isBlank()) {
                    append("【联网搜索】这次**没有取到结果**（可能没配搜索 Key、或网络/额度问题）。")
                    append("请如实说明「暂时查不到最新情况」，**绝对不要凭记忆编造新闻、数据或链接**。\n\n")
                } else {
                    append(searchBlock).append("\n")
                }
            }
            append("【用户档案】")
            if (facts.isEmpty()) append("暂无")
            else {
                append("\n")
                facts.take(8).forEach { append("- ").append(it.key).append("：").append(it.value).append("\n") }
            }
            append("【最近未完成任务】")
            if (tasks.isEmpty()) append("暂无")
            else {
                append("\n")
                tasks.forEach { append("- ").append(it.title).append("\n") }
            }
            append("\n【硬性要求】只输出这条消息本身；不要解释你在执行任务、也不要提「系统/定时任务」；")
            append("新闻类要给出你依据的条目（不要编链接），消息控制在 200 字以内。")
        }

        val reply = runCatching {
            OpenAiClient(api.baseUrl, api.apiKey, api.model).collectReply(
                persona.systemPrompt,
                listOf(ChatMessage(conversationId = 0, isFromUser = true, content = userContext)),
            )
        }.getOrNull() ?: return false
        val text = reply.text.trim()
        if (text.isEmpty()) return false

        val today = java.time.LocalDate.now().toEpochDay()
        runCatching {
            db.messageDao().insert(
                ChatMessage(conversationId = persona.id, isFromUser = false, content = text),
            )
        }
        runCatching { settings.markScheduledMessageSent(message.id, today) }
        notify(context, persona.id, persona.name, text)
        return true
    }

    /** 指定人设 → 主人设 → 第一个人设 */
    private suspend fun pickPersona(db: AppDatabase, settings: SettingsRepository, wanted: Long): Persona? {
        val all = runCatching { db.personaDao().observeAll().first() }.getOrDefault(emptyList())
        return all.firstOrNull { it.id == wanted }
            ?: all.firstOrNull { it.id == MENTOR_PERSONA_ID }
            ?: all.firstOrNull()
    }

    private fun notify(context: Context, personaId: Long, personaName: String, preview: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "定时主动消息", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "你设好的定时消息到点送达时的提醒"
            },
        )
        val home = Intent(Intent.ACTION_VIEW, Uri.parse("menghuan://open/chatList"), context, MainActivity::class.java)
        val chat = Intent(Intent.ACTION_VIEW, Uri.parse("menghuan://chat/$personaId"), context, MainActivity::class.java)
        val contentIntent = PendingIntent.getActivities(
            context,
            NOTIFICATION_ID_BASE,
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
        runCatching { manager.notify(NOTIFICATION_ID_BASE, notification) }
    }
}
