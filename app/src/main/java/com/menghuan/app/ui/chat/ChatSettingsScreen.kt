package com.menghuan.app.ui.chat

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.data.AiCallLogic
import com.menghuan.app.data.AiCallRule
import com.menghuan.app.data.CallRingtone
import com.menghuan.app.data.ScheduledMessage
import com.menghuan.app.notify.AlarmScheduler
import com.menghuan.app.ui.common.NeuButton
import com.menghuan.app.ui.common.NeuButtonKind
import com.menghuan.app.ui.common.NeuChip
import com.menghuan.app.ui.common.NeuSheetRow
import com.menghuan.app.ui.common.NeuSheetScaffold
import com.menghuan.app.ui.common.NeuSwitch
import com.menghuan.app.ui.common.NeuTextButton
import com.menghuan.app.ui.common.NeuTextField
import com.menghuan.app.ui.persona.PersonaVoiceSheet
import com.menghuan.app.ui.settings.SettingsLineRow
import com.menghuan.app.ui.settings.SettingsPage
import com.menghuan.app.ui.settings.settingsContainer
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.NeuHairline
import com.menghuan.app.ui.theme.NeuSurface
import com.menghuan.app.ui.theme.TextPrimary
import kotlinx.coroutines.launch

/** 「电话」规则里的默认来电时刻（固定 21:00，用户不必自己输） */
private const val DEFAULT_CALL_TIME = "21:00"

/**
 * 某个会话（人设）的**整页设置**（v0.54.4 建页；v0.54.5 卡片化；v0.54.6 再去卡片改灰线行）。
 *
 * 用户要求：对话框右上角「⋮」**不再弹小菜单，而是整页显示**；在这一页里配这个角色自己的东西：
 * 编辑人设 / 查看聊天记录 / 电话（允许主动打电话 + 来电时刻 + 多久没聊才打）/
 * 联网搜索 / 人设主动关心 / 角色立绘 / Live 2D / 聊天背景 / 音色 / 模型 / 铃声 / 定时消息。
 *
 * **v0.54.5**：页内条目一律卡片形式，开关放卡片右侧。
 *
 * **v0.54.6（用户点名，本次）**：
 * - **整页取消卡片，改用灰线分隔的行**（`SettingsLineRow`：一条 1dp 发丝线隔开两行）；
 * - 「铃声」行 → 点开**底部弹层**选铃声（不再把列表摊在页面上）；
 * - 「定时消息」行 → 点开就是**添加定时消息**的弹层（已有的一条条列在下面，点一条改它）；
 * - 「允许主动打电话」卡片名改**「电话」**，开关**挪进弹层**；弹层里还有
 *   **什么时候打过来**（时间表盘，默认 21:00）与**多久没聊才打**（天数）；
 * - 所有弹层**从屏幕底部弹出**（`NeuSheetScaffold`）。
 *
 * 立绘 / Live 2D / 聊天背景仍是"只存不显"（待打电话时展示，展示方式未定）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSettingsScreen(
    personaId: Long,
    onBack: () -> Unit,
    onOpen: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val app = settingsContainer()
    val repo = app.settingsRepository
    val scope = rememberCoroutineScope()

    val personas by app.personaRepository.observePersonas().collectAsState(initial = emptyList())
    val persona = personas.firstOrNull { it.id == personaId }

    // 主动打电话（按角色）
    val callRules by repo.aiCallRules.collectAsState(initial = emptyList())
    val callRule = callRules.firstOrNull { it.personaId == personaId }
    val callAllowed = callRule?.enabled == true
    // 联网搜索（按角色）
    val chatWeb by repo.chatWebSearch.collectAsState(initial = emptyMap())
    val webOn = chatWeb[personaId] == true
    // 人设主动关心（按角色）
    val proactive by repo.personaProactive.collectAsState(initial = emptyMap())
    val proactiveOn = proactive[personaId] == true
    // 会话级资产
    val arts by repo.personaArt.collectAsState(initial = emptyMap())
    val live2ds by repo.personaLive2d.collectAsState(initial = emptyMap())
    val backgrounds by repo.chatBackground.collectAsState(initial = emptyMap())
    // 铃声
    val library by repo.callRingtoneLibrary.collectAsState(initial = emptyList())
    val defaultRingtone by repo.callRingtonePath.collectAsState(initial = "")
    val personaRingtones by repo.personaRingtone.collectAsState(initial = emptyMap())
    val myRingtone = personaRingtones[personaId] ?: ""
    // 定时主动消息（只列这个角色的）
    val allScheduled by repo.scheduledMessages.collectAsState(initial = emptyList())
    val myScheduled = allScheduled.filter { it.personaId == personaId }
    // 接口（本会话覆盖）
    val apiProfiles by repo.apiProfiles.collectAsState(initial = emptyList())
    val globalProfileId by repo.activeProfileId.collectAsState(initial = "")
    val overrideId by repo.personaApiProfileId(personaId).collectAsState(initial = null)
    val globalLabel = apiProfiles.firstOrNull { it.id == globalProfileId }?.name ?: "未配置"

    var showVoiceSheet by remember { mutableStateOf(false) }
    var showApiSwitch by remember { mutableStateOf(false) }
    var showRingtoneSheet by remember { mutableStateOf(false) }
    var showCallSheet by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ScheduledMessage?>(null) }

    /** 落盘这个角色的打电话规则，并按开关状态重排/撤销它的闹钟（只动这一个人设） */
    fun applyCallRule(next: AiCallRule) {
        scope.launch {
            repo.upsertAiCallRule(next)
            if (next.enabled) {
                AlarmScheduler.scheduleAiCallForPersona(context, personaId, next.time)
            } else {
                AlarmScheduler.cancelAiCallForPersona(context, personaId)
            }
        }
    }

    fun importAsset(uri: Uri, subDir: String, ext: String, hint: String, onDone: (String) -> Unit) {
        scope.launch {
            val path = runCatching {
                val dir = java.io.File(context.filesDir, subDir).apply { mkdirs() }
                val target = java.io.File(dir, "asset_${System.currentTimeMillis()}.$ext")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                target.takeIf { it.length() > 0 }?.absolutePath
            }.getOrNull()
            if (path == null) Toast.makeText(context, "这个文件读不出来", Toast.LENGTH_SHORT).show()
            else {
                onDone(path)
                Toast.makeText(context, hint, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val artPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            importAsset(it, "persona_art", "jpg", "立绘已保存") { p ->
                scope.launch { repo.setPersonaArt(personaId, p) }
            }
        }
    }
    val live2dPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            importAsset(it, "persona_live2d", "bin", "Live 2D 资源已保存") { p ->
                scope.launch { repo.setPersonaLive2d(personaId, p) }
            }
        }
    }
    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            importAsset(it, "chat_background", "jpg", "聊天背景已保存") { p ->
                scope.launch { repo.setChatBackground(personaId, p) }
            }
        }
    }

    val ringtoneSubtitle = when {
        myRingtone.isNotBlank() -> CallRingtone.displayNameOf(myRingtone)
        defaultRingtone.isNotBlank() -> "跟随默认（${CallRingtone.displayNameOf(defaultRingtone)}）"
        else -> "跟随默认（系统默认）"
    }
    val callSubtitle = if (callAllowed) {
        "已开启 · ${callRule?.time ?: DEFAULT_CALL_TIME} · ${callRule?.idleDays ?: 3} 天没聊"
    } else {
        "已关闭"
    }

    SettingsPage(title = "会话设置", onBack = onBack) {
        Spacer(Modifier.height(6.dp))

        SettingsLineRow("编辑人设", subtitle = persona?.name.orEmpty()) {
            onOpen("persona_editor/$personaId")
        }
        SettingsLineRow("查看聊天记录") { onOpen("chat_history/$personaId") }

        // 电话：开关、来电时刻、阈值都在底部弹层里（卡片上只显示状态）
        SettingsLineRow("电话", subtitle = callSubtitle) { showCallSheet = true }

        SettingsLineRow(
            "联网搜索",
            subtitle = if (webOn) "已开启" else "已关闭",
            onClick = { scope.launch { repo.setChatWebSearchEnabled(personaId, !webOn) } },
            trailing = {
                NeuSwitch(checked = webOn, onToggle = { on ->
                    scope.launch { repo.setChatWebSearchEnabled(personaId, on) }
                })
            },
        )
        SettingsLineRow(
            "人设主动关心",
            subtitle = if (proactiveOn) "已开启" else "已关闭",
            onClick = { scope.launch { repo.setPersonaProactive(personaId, !proactiveOn) } },
            trailing = {
                NeuSwitch(checked = proactiveOn, onToggle = { on ->
                    scope.launch { repo.setPersonaProactive(personaId, on) }
                })
            },
        )

        SettingsLineRow(
            "角色立绘",
            subtitle = if (arts[personaId].isNullOrBlank()) "未设置" else "已设置",
            onClick = {
                val current = arts[personaId]
                if (current.isNullOrBlank()) artPicker.launch("image/*") else scope.launch {
                    repo.setPersonaArt(personaId, "")
                }
            },
        )
        SettingsLineRow(
            "Live 2D",
            subtitle = if (live2ds[personaId].isNullOrBlank()) "未设置" else "已设置",
            onClick = {
                val current = live2ds[personaId]
                if (current.isNullOrBlank()) live2dPicker.launch("*/*") else scope.launch {
                    repo.setPersonaLive2d(personaId, "")
                }
            },
        )
        SettingsLineRow(
            "聊天背景",
            subtitle = if (backgrounds[personaId].isNullOrBlank()) "未设置" else "已设置",
            onClick = {
                val current = backgrounds[personaId]
                if (current.isNullOrBlank()) backgroundPicker.launch("image/*") else scope.launch {
                    repo.setChatBackground(personaId, "")
                }
            },
        )
        SettingsLineRow("音色", subtitle = "这个角色说话的声音") { showVoiceSheet = true }
        SettingsLineRow("模型", subtitle = "本会话用哪个 AI 接口") { showApiSwitch = true }

        // 铃声：页面上只留一行状态，点开是底部弹层里的铃声列表
        SettingsLineRow("铃声", subtitle = ringtoneSubtitle) { showRingtoneSheet = true }

        // 定时消息：这一行 = 添加；下面逐条列出已设的（点一条改它）
        SettingsLineRow(
            "定时消息",
            subtitle = if (myScheduled.isEmpty()) "还没有设置" else "已设 ${myScheduled.size} 条",
            onClick = {
                editing = ScheduledMessage(id = "sm" + System.currentTimeMillis(), personaId = personaId)
            },
        )
        myScheduled.forEach { m ->
            SettingsLineRow(
                m.time,
                subtitle = buildString {
                    if (m.withSearch) append("联网 · ")
                    if (!m.enabled) append("已关闭 · ")
                    append(m.instruction.take(30))
                },
                onClick = { editing = m },
            )
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showVoiceSheet) {
        PersonaVoiceSheet(personaId = personaId, onDismiss = { showVoiceSheet = false })
    }
    if (showApiSwitch) {
        ApiSwitchSheet(
            profiles = apiProfiles,
            activeId = if (overrideId != null) overrideId!! else globalProfileId,
            isFollowingGlobal = overrideId == null,
            globalLabel = globalLabel,
            onSelect = { id ->
                scope.launch { repo.setPersonaApiProfile(personaId, id) }
                showApiSwitch = false
            },
            onFollowGlobal = {
                scope.launch { repo.setPersonaApiProfile(personaId, null) }
                showApiSwitch = false
            },
            onDismiss = { showApiSwitch = false },
        )
    }

    // ===== 铃声选择（底部弹层，v0.54.6）=====
    if (showRingtoneSheet) {
        NeuSheetScaffold(title = "铃声", onDismiss = { showRingtoneSheet = false }) {
            Spacer(Modifier.height(6.dp))
            RingtoneOption(
                title = "跟随默认铃声",
                subtitle = if (defaultRingtone.isBlank()) "系统默认"
                else CallRingtone.displayNameOf(defaultRingtone),
                selected = myRingtone.isBlank(),
                onClick = {
                    scope.launch { repo.setPersonaRingtone(personaId, "") }
                    showRingtoneSheet = false
                },
            )
            library.forEach { p ->
                RingtoneOption(
                    title = CallRingtone.displayNameOf(p),
                    subtitle = "",
                    selected = myRingtone == p,
                    onClick = {
                        scope.launch { repo.setPersonaRingtone(personaId, p) }
                        showRingtoneSheet = false
                    },
                )
            }
            if (library.isEmpty()) {
                Text(
                    "还没有添加铃声，去「设置 → 通话行为 → 语音通话铃声」里添加。",
                    fontSize = 12.sp,
                    color = MutedText,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
        }
    }

    // ===== 电话（底部弹层，v0.54.6）：开关 + 什么时候打过来 + 多久没聊才打 =====
    if (showCallSheet) {
        val rule = callRule ?: AiCallRule(personaId = personaId)
        var pickTime by remember { mutableStateOf(false) }
        NeuSheetScaffold(title = "电话", onDismiss = { showCallSheet = false }) {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("允许主动打电话", fontSize = 15.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                NeuSwitch(
                    checked = rule.enabled,
                    onToggle = { on -> applyCallRule(rule.copy(enabled = on)) },
                )
            }
            Spacer(Modifier.height(1.dp))

            // 什么时候打过来：默认 21:00（固定，不必手输），点一下开时间表盘
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("什么时候打过来", fontSize = 15.sp, color = TextPrimary)
                Spacer(Modifier.weight(1f))
                NeuTextButton(
                    text = rule.time,
                    onClick = { pickTime = !pickTime },
                )
            }
            if (pickTime) {
                val parts = rule.time.split(":")
                val hour = parts.getOrNull(0)?.toIntOrNull() ?: 21
                val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
                val timeState = rememberTimePickerState(
                    initialHour = hour,
                    initialMinute = minute,
                    is24Hour = true,
                )
                TimePicker(
                    state = timeState,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = NeuSurface,
                        selectorColor = NeuAccent,
                        periodSelectorBorderColor = NeuHairline,
                        timeSelectorSelectedContainerColor = NeuAccent,
                    ),
                )
                Spacer(Modifier.height(4.dp))
                NeuButton(
                    text = "确定这个时间",
                    kind = NeuButtonKind.Primary,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val t = "%02d:%02d".format(timeState.hour, timeState.minute)
                        applyCallRule(rule.copy(time = t))
                        pickTime = false
                    },
                )
            }
            Spacer(Modifier.fillMaxWidth().height(1.dp).background(NeuHairline))

            Spacer(Modifier.height(16.dp))
            Text("多久没聊才打", fontSize = 15.sp, color = TextPrimary)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 3, 7, 30).forEach { d ->
                    NeuChip(text = "$d 天", selected = rule.idleDays == d) {
                        applyCallRule(rule.copy(idleDays = d))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    // ===== 定时消息（添加 / 编辑，同一套弹层）=====
    editing?.let { initial ->
        var draft by remember(initial.id) { mutableStateOf(initial) }
        val isNew = allScheduled.none { it.id == initial.id }
        NeuSheetScaffold(
            title = if (isNew) "添加定时消息" else "编辑定时消息",
            onDismiss = { editing = null },
        ) {
            NeuTextField(
                value = draft.time,
                onValueChange = { draft = draft.copy(time = it) },
                label = "每天几点（HH:mm）",
                placeholder = "08:00",
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("先联网搜一遍再写", fontSize = 14.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                NeuSwitch(checked = draft.withSearch, onToggle = { draft = draft.copy(withSearch = it) })
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("启用", fontSize = 14.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                NeuSwitch(checked = draft.enabled, onToggle = { draft = draft.copy(enabled = it) })
            }
            Spacer(Modifier.height(16.dp))
            NeuButton(
                text = "保存",
                kind = NeuButtonKind.Primary,
                enabled = draft.instruction.isNotBlank() && AiCallLogic.minuteOfDayOf(draft.time) != null,
                onClick = {
                    val saved = draft.copy(
                        personaId = personaId,
                        time = draft.time.trim(),
                        instruction = draft.instruction.trim(),
                    )
                    scope.launch {
                        repo.upsertScheduledMessage(saved)
                        if (saved.enabled) {
                            AlarmScheduler.scheduleScheduledMessage(context, saved.id, saved.time)
                        } else {
                            AlarmScheduler.cancelScheduledMessage(context, saved.id)
                        }
                    }
                    editing = null
                },
            )
            if (!isNew) {
                Spacer(Modifier.height(12.dp))
                NeuTextButton("删除这条", color = NeuDanger, onClick = {
                    scope.launch {
                        AlarmScheduler.cancelScheduledMessage(context, draft.id)
                        repo.deleteScheduledMessage(draft.id)
                    }
                    editing = null
                })
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

/** 铃声弹层里的一行（点一条即选中，选中打勾） */
@Composable
private fun RingtoneOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    NeuSheetRow(
        icon = MIcons.Bell,
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        selected = selected,
    )
    Spacer(Modifier.height(8.dp))
}
