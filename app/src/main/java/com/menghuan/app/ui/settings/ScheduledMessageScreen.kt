package com.menghuan.app.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.data.AiCallLogic
import com.menghuan.app.data.SEARCH_BOCHA
import com.menghuan.app.data.SEARCH_TAVILY
import com.menghuan.app.data.ScheduledMessage
import com.menghuan.app.data.WebSearchClient
import com.menghuan.app.notify.AlarmScheduler
import com.menghuan.app.ui.common.NeuButton
import com.menghuan.app.ui.common.NeuButtonKind
import com.menghuan.app.ui.common.NeuChip
import com.menghuan.app.ui.common.NeuSheetRow
import com.menghuan.app.ui.common.NeuSwitch
import com.menghuan.app.ui.common.NeuTextButton
import com.menghuan.app.ui.common.NeuTextField
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.TextPrimary
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

/**
 * 「定时主动消息」（v0.53.5）：人设到点主动给你发一段。
 *
 * 用户点名要的形状：**能自己配** —— 谁发、几点发、发什么，都可以多条并存。
 * 典型用法：「小美 每天 07:30 发今天的重要新闻」「教练 每天 22:00 问我今天练了没」。
 *
 * 「发今天的重要新闻」这类需要**最新信息**的指令，必须打开**联网搜索**才有意义：
 * 模型自己不联网，不搜就只能凭旧记忆编（会给出过期甚至假的信息）。
 * 所以编辑弹窗里那个开关旁边写明了这一点，搜不到时也会让模型如实说"没查到"，不许编。
 */
@Composable
fun ScheduledMessageScreen(onBack: () -> Unit) {
    val app = settingsContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val messages by app.settingsRepository.scheduledMessages.collectAsState(initial = emptyList())
    val personas by app.personaRepository.observePersonas().collectAsState(initial = emptyList())
    val provider by app.settingsRepository.searchProvider.collectAsState(initial = SEARCH_BOCHA)
    val searchKey by app.settingsRepository.searchApiKey.collectAsState(initial = "")

    var editing by remember { mutableStateOf<ScheduledMessage?>(null) }
    var searchOpen by remember { mutableStateOf(false) }

    fun schedule(message: ScheduledMessage) {
        if (message.enabled) {
            AlarmScheduler.scheduleScheduledMessage(context, message.id, message.time)
        } else {
            AlarmScheduler.cancelScheduledMessage(context, message.id)
        }
    }

    SettingsPage(title = "定时主动消息", onBack = onBack) {
        Spacer(Modifier.height(14.dp))
        Text(
            "到点让指定的人设主动给你发一段——谁发、几点发、发什么，都由你定，可以配多条。",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MutedText,
        )
        Spacer(Modifier.height(14.dp))

        if (messages.isEmpty()) {
            Text("还没有配置", fontSize = 13.sp, color = MutedText, modifier = Modifier.height(30.dp))
        }
        messages.forEach { m ->
            val who = personas.firstOrNull { it.id == m.personaId }?.name ?: "主人设"
            NeuSheetRow(
                icon = MIcons.ChatBubble,
                title = who,
                subtitle = buildString {
                    append("每天 ").append(m.time)
                    if (m.withSearch) append(" · 联网搜索")
                    if (!m.enabled) append(" · 已关闭")
                    if (m.instruction.isNotBlank()) append(" · ").append(m.instruction.take(24))
                },
                onClick = { editing = m },
            )
            Spacer(Modifier.height(8.dp))
        }
        NeuTextButton("＋ 添加一条", onClick = {
            editing = ScheduledMessage(id = "sm" + System.currentTimeMillis())
        })

        Spacer(Modifier.height(22.dp))
        Text("联网搜索", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = TextPrimary, letterSpacing = 0.6.sp)
        Spacer(Modifier.height(8.dp))
        NeuSheetRow(
            icon = MIcons.Search,
            title = WebSearchClient.labelOf(provider),
            subtitle = if (searchKey.isBlank()) "未配置 Key · 点这里去配" else "已配置",
            onClick = { searchOpen = true },
        )
        Spacer(Modifier.height(6.dp))
        Spacer(Modifier.height(6.dp))
    }

    // 编辑一条
    editing?.let { initial ->
        var draft by remember(initial.id) { mutableStateOf(initial) }
        val isNew = messages.none { it.id == initial.id }
        SettingsCardDialog(
            title = if (isNew) "添加定时消息" else "编辑定时消息",
            onDismiss = { editing = null },
        ) {
            Text("谁发", fontSize = 12.sp, color = MutedText)
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NeuChip(text = "主人设", selected = draft.personaId == 0L) {
                    draft = draft.copy(personaId = 0L)
                }
                personas.forEach { p ->
                    NeuChip(text = p.name, selected = draft.personaId == p.id) {
                        draft = draft.copy(personaId = p.id)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            NeuTextField(
                value = draft.time,
                onValueChange = { draft = draft.copy(time = it) },
                label = "每天几点（HH:mm）",
                placeholder = "07:30",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            NeuTextField(
                value = draft.instruction,
                onValueChange = { draft = draft.copy(instruction = it) },
                label = "发什么",
                placeholder = "例：给我发今天的重要新闻，5 条以内",
                singleLine = false,
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("先联网搜一遍再写", fontSize = 14.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                NeuSwitch(checked = draft.withSearch, onToggle = { draft = draft.copy(withSearch = it) })
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "要「今天的新闻」这类最新信息就必须打开：模型自己不联网，不搜只会凭旧记忆编。" +
                    "（需要先在下面配好搜索 Key）",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MutedText,
            )
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("启用", fontSize = 14.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                NeuSwitch(checked = draft.enabled, onToggle = { draft = draft.copy(enabled = it) })
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                NeuButton(
                    text = "保存",
                    kind = NeuButtonKind.Primary,
                    enabled = draft.instruction.isNotBlank() &&
                        AiCallLogic.minuteOfDayOf(draft.time) != null,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val saved = draft.copy(time = draft.time.trim(), instruction = draft.instruction.trim())
                        scope.launch {
                            app.settingsRepository.upsertScheduledMessage(saved)
                            schedule(saved)
                        }
                        editing = null
                    },
                )
            }
            if (!isNew) {
                Spacer(Modifier.height(12.dp))
                NeuTextButton("删除这条", color = NeuDanger, onClick = {
                    scope.launch {
                        AlarmScheduler.cancelScheduledMessage(context, draft.id)
                        app.settingsRepository.deleteScheduledMessage(draft.id)
                    }
                    editing = null
                })
            }
        }
    }

    // 联网搜索服务配置
    if (searchOpen) {
        var keyDraft by remember { mutableStateOf(searchKey) }
        var providerDraft by remember { mutableStateOf(provider) }
        SettingsCardDialog(title = "联网搜索", onDismiss = { searchOpen = false }) {
            Text("服务商", fontSize = 12.sp, color = MutedText)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NeuChip(text = "博查（国内）", selected = providerDraft == SEARCH_BOCHA) {
                    providerDraft = SEARCH_BOCHA
                }
                NeuChip(text = "Tavily（国际）", selected = providerDraft == SEARCH_TAVILY) {
                    providerDraft = SEARCH_TAVILY
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "去 ${WebSearchClient.consoleUrlOf(providerDraft)} 注册并在控制台创建 API Key，粘贴到下面。" +
                    "接口：${WebSearchClient.endpointOf(providerDraft)}",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MutedText,
            )
            Spacer(Modifier.height(12.dp))
            NeuTextField(
                value = keyDraft,
                onValueChange = { keyDraft = it },
                label = "API Key",
                placeholder = "粘贴控制台里的 Key",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            NeuButton(
                text = "保存",
                kind = NeuButtonKind.Primary,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        app.settingsRepository.setSearchProvider(providerDraft)
                        app.settingsRepository.setSearchApiKey(keyDraft.trim())
                    }
                    searchOpen = false
                },
            )
        }
    }
}
