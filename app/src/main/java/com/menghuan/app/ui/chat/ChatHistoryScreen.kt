package com.menghuan.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.data.model.ChatMessage
import com.menghuan.app.data.model.Persona
import com.menghuan.app.ui.common.NeuCircleButton
import com.menghuan.app.ui.common.NeuTextButton
import com.menghuan.app.ui.common.NeuTopBar
import com.menghuan.app.ui.settings.SettingsPage
import com.menghuan.app.ui.settings.settingsContainer
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuTrack
import com.menghuan.app.ui.theme.TextPrimary
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 聊天记录（搜索）页（v0.53.6）：对话页右上角菜单 →「查看聊天记录」。
 *
 * 用途：翻很早以前聊过的东西。**搜索走 SQL 的 LIKE**（`messageDao.searchMessages`，本来就有的能力），
 * 拿回来再按本会话过滤 —— 不新增数据库查询、不动 Room。
 * 不填关键词时列出**最近 50 条**（免得一进来就把几千条塞进列表）。
 */
@Composable
fun ChatHistoryScreen(personaId: Long, onBack: () -> Unit) {
    val app = settingsContainer()
    var persona by remember(personaId) { mutableStateOf<Persona?>(null) }
    var query by remember(personaId) { mutableStateOf("") }
    var all by remember(personaId) { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var matched by remember(personaId) { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var loading by remember(personaId) { mutableStateOf(true) }

    LaunchedEffect(personaId) {
        persona = runCatching { app.db.personaDao().getById(personaId) }.getOrNull()
        all = runCatching { app.db.messageDao().observeConversation(personaId).first() }
            .getOrDefault(emptyList())
        loading = false
    }

    LaunchedEffect(query, all) {
        val q = query.trim()
        matched = if (q.isEmpty()) {
            all.takeLast(50).reversed()
        } else {
            runCatching { app.db.messageDao().searchMessages(q) }
                .getOrDefault(emptyList())
                .filter { it.conversationId == personaId }
                .sortedByDescending { it.timestamp }
        }
    }

    SettingsPage(title = persona?.name?.let { "与 $it 的聊天记录" } ?: "聊天记录", onBack = onBack) {
        Spacer(Modifier.height(10.dp))
        // 搜索框（项目里输入框的极简样式）
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(NeuTrack)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(MIcons.Search, contentDescription = null, tint = MutedText, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                androidx.compose.foundation.text.BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = TextPrimary),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(TextPrimary),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (query.isEmpty()) {
                    Text("搜一句你记得的话", fontSize = 14.sp, color = MutedText)
                }
            }
            if (query.isNotEmpty()) {
                NeuTextButton("清空", onClick = { query = "" })
            }
        }
        Spacer(Modifier.height(12.dp))

        when {
            loading -> Text("读取中…", fontSize = 13.sp, color = MutedText)
            matched.isEmpty() -> Text(
                if (query.isBlank()) "还没有聊过" else "没搜到包含「${query.trim()}」的消息",
                fontSize = 13.sp,
                color = MutedText,
            )
            else -> {
                Text(
                    if (query.isBlank()) "最近 ${matched.size} 条" else "找到 ${matched.size} 条",
                    fontSize = 11.sp,
                    color = MutedText,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(matched, key = { it.id }) { m ->
                        HistoryRow(message = m, personaName = persona?.name.orEmpty())
                    }
                    item { Spacer(Modifier.height(20.dp)) }
                }
            }
        }
    }
}

/** 一条历史消息：谁说 + 什么时候 + 内容（最多 4 行，点开能看全就不做了，够用） */
@Composable
private fun HistoryRow(message: ChatMessage, personaName: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(NeuTrack)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (message.isFromUser) "我" else personaName.ifBlank { "TA" },
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
            )
            Spacer(Modifier.width(8.dp))
            Text(formatStamp(message.timestamp), fontSize = 10.sp, color = MutedText)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            message.content.ifBlank {
                when {
                    message.imagePath.isNotBlank() -> "[图片]"
                    message.audioPath.isNotBlank() -> "[语音]"
                    else -> "[空消息]"
                }
            },
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = TextPrimary,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** `今天 14:30` / `10-03 14:30`（跨年再补年份由 formatStamp 处理） */
private fun formatStamp(millis: Long): String {
    val zone = ZoneId.systemDefault()
    val time = Instant.ofEpochMilli(millis).atZone(zone)
    val day = time.toLocalDate()
    val hm = time.format(DateTimeFormatter.ofPattern("HH:mm"))
    return when (day) {
        LocalDate.now(zone) -> "今天 $hm"
        LocalDate.now(zone).minusDays(1) -> "昨天 $hm"
        else -> "${time.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))}"
    }
}
