package com.menghuan.app.ui.chat

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
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
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.TextPrimary
import kotlinx.coroutines.launch

/** 「电话」里的默认来电时刻（用户不填就用它） */
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
 * **v0.54.6**：
 * - **整页取消卡片，改用灰线分隔的行**（`SettingsLineRow`：一条 1dp 发丝线隔开两行）；
 * - 「铃声」行 → 点开**底部弹层**选铃声（不再把列表摊在页面上）；
 * - 「定时消息」行 → 点开就是**添加定时消息**的弹层（已有的一条条列在下面，点一条改它）；
 * - 「允许主动打电话」卡片名改**「电话」**，开关**挪进弹层**；
 * - 所有弹层**从屏幕底部弹出**（`NeuSheetScaffold`）。
 *
 * **v0.54.7（用户澄清）**：
 * - 「电话」弹层跟**「定时消息」**一个路子：**几点打过来**（手填 HH:mm，默认 21:00）+
 *   **说什么**（开场内容要求）+ **多久没聊才打（天数自己填 1–30）**——
 *   上一版把"多久没聊"做成了固定几选一、把时间做成了表盘，正好弄反了；
 * - 「聊天背景」点开是弹层**二选一**：原生背景 / 自定义图片（选自定义才去相册挑图）；
 * - 聊天背景**开始真的生效**（在这个角色的聊天页铺一张背景图），不再是"只存不显"。
 *
 * 角色立绘 / Live 2D 仍是"只存不显"（待接打电话时展示，展示方式未定）。
 */
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
    var showBackgroundSheet by remember { mutableStateOf(false) }
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
            subtitle = if (backgrounds[personaId].isNullOrBlank()) "原生背景" else "自定义图片",
            onClick = { showBackgroundSheet = true },
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

    // ===== 电话（底部弹层，v0.54.7）：跟「定时消息」一样 —— 几点打、说什么，天数可自定义 =====
    if (showCallSheet) {
        val rule = callRule ?: AiCallRule(personaId = personaId)
        var timeDraft by remember(rule.time) { mutableStateOf(rule.time) }
        var sayDraft by remember(rule.instruction) { mutableStateOf(rule.instruction) }
        var idleDraft by remember(rule.idleDays) { mutableStateOf(rule.idleDays.toString()) }
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
            Spacer(Modifier.height(14.dp))

            // 几点打过来：与「定时消息」同款手填 HH:mm（默认 21:00）
            NeuTextField(
                value = timeDraft,
                onValueChange = { v ->
                    timeDraft = v
                    if (AiCallLogic.minuteOfDayOf(v) != null) applyCallRule(rule.copy(time = v))
                },
                label = "几点打过来（HH:mm）",
                placeholder = DEFAULT_CALL_TIME,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))

            // 说什么：这次打电话的开场内容要求（留空 = 让 TA 自己按情境组织）
            NeuTextField(
                value = sayDraft,
                onValueChange = { v ->
                    sayDraft = v
                    applyCallRule(rule.copy(instruction = v))
                },
                label = "说什么",
                placeholder = "例：问我今天任务完成没，语气轻松点",
                singleLine = false,
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))

            // 多久没聊才打：**天数自己填**（1–30），不是固定几选一
            NeuTextField(
                value = idleDraft,
                onValueChange = { raw ->
                    val digits = raw.filter { it.isDigit() }.take(2)
                    idleDraft = digits
                    digits.toIntOrNull()?.coerceIn(1, 30)?.let { d ->
                        applyCallRule(rule.copy(idleDays = d))
                    }
                },
                label = "多久没聊才打（天，1–30）",
                placeholder = "3",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
        }
    }

    // ===== 聊天背景（底部弹层，v0.54.7）：原生背景 / 自定义图片 二选一 =====
    if (showBackgroundSheet) {
        val custom = backgrounds[personaId]
        NeuSheetScaffold(title = "聊天背景", onDismiss = { showBackgroundSheet = false }) {
            Spacer(Modifier.height(6.dp))
            NeuSheetRow(
                icon = MIcons.ImageIcon,
                title = "原生背景",
                subtitle = "App 自己的底色",
                onClick = {
                    scope.launch { repo.setChatBackground(personaId, "") }
                    showBackgroundSheet = false
                },
                selected = custom.isNullOrBlank(),
            )
            Spacer(Modifier.height(8.dp))
            NeuSheetRow(
                icon = MIcons.Palette,
                title = "自定义",
                subtitle = if (custom.isNullOrBlank()) "从相册选一张图" else "已设置 · 点此换一张",
                onClick = {
                    showBackgroundSheet = false
                    backgroundPicker.launch("image/*")
                },
                selected = !custom.isNullOrBlank(),
            )
            Spacer(Modifier.height(10.dp))
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
