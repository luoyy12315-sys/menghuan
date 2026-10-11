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

    // v0.62.0 换代（_v2）：渠道声音创建即锁死，不换 id 老装机永远补不上提示音
    private const val CHANNEL_ID = "scheduled_message_v2"
    private const val LEGACY_CHANNEL_ID = "scheduled_message"
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

        val persona = pickPersona(db, message.personaId) ?: return false

        // 需要联网时先搜（Key 没配就当作没搜到，由下面的措辞如实说明）
        val searchBlock: String = if (message.withSearch) {
            val provider = runCatching { settings.searchProvider.first() }.getOrDefault(SEARCH_BOCHA)
            // v0.59.0：按当前生效那一家取 Key/地址（自定义家有独立的一份）
            val key = runCatching { settings.searchKeyFor(provider) }.getOrDefault("")
            val customEndpoint = runCatching { settings.searchEndpointFor(provider) }.getOrDefault("")
            if (key.isBlank()) {
                ""
            } else {
                val hits = runCatching {
                    WebSearchClient.search(provider, key, message.instruction, customEndpoint = customEndpoint)
                }
                    .getOrDefault(emptyList())
                WebSearchClient.toPromptBlock(hits, WebSearchClient.labelOf(provider))
            }
        } else {
            ""
        }

        // v0.70.0：记忆按人设分开（这条定时消息是发给某个角色的，不是发给全体 AI）
        val sharedReadable = runCatching { settings.profileSharedReadable.first() }.getOrDefault(true)
        // v0.98.0：走两条线（基础档案全带 + 她自己的记忆 ≤ PROFILE_FACTS_MAX），
        // 不再合成一条整体截断 —— 否则用户基础档案一多，她的记忆一条都进不来。
        val facts = MemoryScope.split(
            runCatching { db.profileFactDao().observeAll().first() }.getOrDefault(emptyList()),
            persona.id, sharedReadable,
        ).forInject(PromptBlocks.PROFILE_FACTS_MAX)
        // v0.94（用户拍板「砍干净、不加设置」）：任务块从这条链砍掉 ——
        // 定时消息的素材就是「用户给的要求」本身，任务提醒归任务闹钟那条专门链路。
        // 任务现状只留聊天链（ChatViewModel.taskStatusBlock）。

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
            // v0.98.0：套上「别把档案念出来」那段包装语（用户可在设置里改）。
            // 这条消息是直接推给用户的，档案被原样罗列出来最像机器人群发。
            append("【用户档案】")
            append(
                runCatching { settings.profilePreamble.first() }
                    .getOrDefault(AiPrompts.DEFAULT_PROFILE_PREAMBLE),
            )
            if (facts.isEmpty()) append("暂无")
            else {
                append("\n")
                // 上限已在 split(...).forInject(...) 里定过，这里不再重复截
                facts.forEach { append("- ").append(it.key).append("：").append(it.value).append("\n") }
            }
            // v0.94：与主动关心/来电开场白同一形状——TA 开口前先知道自己上次说到哪了
            append("\n").append(PromptBlocks.recentChatBlock(db, persona.id)).append("\n")
            append("\n【硬性要求】只输出这条消息本身；不要解释你在执行任务、也不要提「系统/定时任务」；")
            append("新闻类要给出你依据的条目（不要编链接），消息控制在 200 字以内。")
        }

        val reply = runCatching {
            OpenAiClient(api.baseUrl, api.apiKey, api.model).collectReply(
                persona.systemPrompt,
                listOf(ChatMessage(conversationId = 0, isFromUser = true, content = userContext)),
            )
        }.getOrNull() ?: return false
        // v0.88.0：这条链路**不出卡**（用户点名「只在聊天页出卡」），但任务建议标记必须剥掉，
        // 否则用户会在气泡里看到 `[建议任务:…]` 这种内部协议。
        val text = com.menghuan.app.data.CompanionMarker.strip(
            com.menghuan.app.data.TaskSuggestionMarker.strip(reply.text.trim())
        )
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

    /**
     * 这条定时消息该由谁来发。**只看它自己写的那个人，找不到就不发。**
     *
     * ⚠️ v0.89.0 修：（原实现）「指定人设 → 主人设 → 第一个人设」的兜底是 v0.53.5 早期的遗留，
     * 那时候还没有"按人设存"这套模型。它的语义是"找不到你要的人就换一个发"，
     * 而 `MENTOR_PERSONA_ID` 恰好就是内置**应用助手** —— 于是用户把那个角色删掉之后，
     * 定时消息没有失效，而是被**静默改嫁**给了应用助手：名字显示成"应用助手"、
     * 会话落在助手那条、用的是助手的手册当 system prompt，指令却还是写给原来那个角色的。
     * 用户看到的现像就是「我删了人设，它照样每天从应用助手发过来」。
     *
     * 定时电话（[AiCall.ring]）与人设主动关心（[ProactiveChat.maybeGreet]）本来就没有这个兜底，
     * 三条链路的口径在这里对齐：**目标人设没了 = 这条不再发**。
     *
     * [ScheduledMessage.personaId] 里 `0` = 主人设（写这条时还没指名），那是**用户明确选的**，
     * 照旧按主人设理解；只有"指名了又不在了"才不发。
     */
    private suspend fun pickPersona(db: AppDatabase, wanted: Long): Persona? {
        if (wanted <= 0L) {
            // 0 = 主人设：内置助手在时就用它（与写这条时的呈现一致），助手被删则退回列表第一个
            val all = runCatching { db.personaDao().observeAll().first() }.getOrDefault(emptyList())
            return all.firstOrNull { it.id == MENTOR_PERSONA_ID } ?: all.firstOrNull()
        }
        return runCatching { db.personaDao().getById(wanted) }.getOrNull()
    }

    private fun notify(context: Context, personaId: Long, personaName: String, preview: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        // v0.62.0：提示音显式设上，系统勿扰时自动改走"仅震动"那条渠道
        val channelId = NotifyAlert.channel(
            context,
            CHANNEL_ID,
            "定时主动消息",
            "你设好的定时消息到点送达时的提醒",
            legacyIds = listOf(LEGACY_CHANNEL_ID),
        )
        val home = Intent(Intent.ACTION_VIEW, Uri.parse("menghuan://open/chatList"), context, MainActivity::class.java)
        val chat = Intent(Intent.ACTION_VIEW, Uri.parse("menghuan://chat/$personaId"), context, MainActivity::class.java)
        val contentIntent = PendingIntent.getActivities(
            context,
            NOTIFICATION_ID_BASE,
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
        runCatching { manager.notify(NOTIFICATION_ID_BASE, notification) }
    }
}
