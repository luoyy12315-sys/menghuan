package com.menghuan.app.ui.persona

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.data.ClonedVoice
import com.menghuan.app.data.TtsClients
import com.menghuan.app.data.TtsConfig
import com.menghuan.app.data.TtsProvider
import com.menghuan.app.data.TtsVoices
import com.menghuan.app.data.VoiceCloner
import com.menghuan.app.data.VoiceRef
import com.menghuan.app.data.model.Persona
import com.menghuan.app.data.model.ToneStyle
import com.menghuan.app.ui.common.NeuAvatar
import com.menghuan.app.ui.common.NeuButton
import com.menghuan.app.ui.common.NeuButtonKind
import com.menghuan.app.ui.common.NeuChip
import com.menghuan.app.ui.common.NeuSheetScaffold
import com.menghuan.app.ui.common.NeuTextButton
import com.menghuan.app.ui.common.NeuTextField
import com.menghuan.app.ui.settings.VOICE_PREVIEW_TEXT
import com.menghuan.app.ui.settings.playTtsPreview
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuSurface
import com.menghuan.app.ui.theme.TextPrimary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.NeuSurface
import java.io.File
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip

/** 头像 emoji 快选预设（28 个，覆盖常见人设类型；自定义输入不受限） */
private val avatarPresets = listOf(
    "🙂", "🖋️", "🎯", "💭", "📚", "💪", "🧘", "🌱", "🎨", "💡", "🏃", "🌙",
    "🧑‍🏫", "📖", "✍️", "🏋️", "🧠", "☀️", "🌊", "🎵", "💼", "🦉", "🌲", "🍎",
    "⚖️", "🎭", "🚀", "🐱",
)

/** 人设设定词模板骨架（「插入模板」填入，用户在其上修改） */
private const val PERSONA_PROMPT_TEMPLATE =
    "你是用户的【角色名称】，专注【领域】。\n" +
        "\n" +
        "【语气与风格】\n" +
        "- 用【温和 / 直接 / 幽默】的方式交流\n" +
        "- 回复简洁具体，少讲大道理\n" +
        "\n" +
        "【职责】\n" +
        "- 帮助用户【目标】\n" +
        "- 定期跟进【事项】，发现拖延就直接点破\n" +
        "\n" +
        "【约束】\n" +
        "- 不编造用户没有提供的信息\n" +
        "- 建议要可执行，一次不超过 3 条"

/**
 * 人设编辑弹层：新增（persona = null）与编辑共用，名字必填。
 * 附「设定词写作规范」与「插入模板」（v0.33.0，应用内规范）。
 */
/**
 * 音色行的展示标签：预置音色显示中文描述，克隆音色显示名字，自定义显示 ID 本身。
 * 人设存的音色若不属于当前 provider（用户换了服务商），按"默认"显示——那个 ID 在新家不认。
 */
private fun voiceLabel(config: TtsConfig, ref: VoiceRef?, cloned: List<ClonedVoice>): String {
    val id = ref?.takeIf { it.provider == config.provider }?.voiceId
        ?: return "默认 · ${TtsVoices.defaultVoiceId(config.provider)}"
    TtsVoices.presets(config.provider).firstOrNull { it.id == id }?.let { return it.label }
    cloned.firstOrNull { it.uri == id }?.let { return "克隆 · ${it.customName}" }
    TtsVoices.localSamplePath(id)?.let { return "克隆 · ${File(it).nameWithoutExtension}" }
    return id
}

@Composable
fun PersonaEditorSheet(
    persona: Persona?,
    onSave: (Persona) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(persona?.name ?: "") }
    var title by remember { mutableStateOf(persona?.title ?: "") }
    var emoji by remember { mutableStateOf(persona?.avatarEmoji ?: "🙂") }
    var prompt by remember { mutableStateOf(persona?.systemPrompt ?: "") }
    var showGuide by remember { mutableStateOf(false) }
    var confirmOverwrite by remember { mutableStateOf(false) }
    // v0.36.0：AI 优化设定词子面板（用户丢一段自由描述 → LLM 输出结构化三件套 → 一键填入）
    var showAiOptimize by remember { mutableStateOf(false) }
    // 自定义头像图片路径（v0.35.0：非空时头像渲染图片，emoji 只作兜底）
    var avatarImagePath by remember { mutableStateOf(persona?.avatarImagePath.orEmpty()) }

    val context = LocalContext.current
    // 音色属于人设（在编辑人设里选，存 tts_voice_<personaId>）；v0.50.0 起**通话与 AI 语音条共用**这一个音色。
    // 容器在 sheet 内自取，调用点零改动；非 App 环境（单测宿主）拿不到时整行隐藏。
    val voiceSettings = remember(context) {
        runCatching {
            (context.applicationContext as? com.menghuan.app.MenghuanApp)?.settingsRepository
        }.getOrNull()
    }
    val editorScope = rememberCoroutineScope()
    var ttsConfig by remember { mutableStateOf(TtsConfig()) }
    var voiceRef by remember { mutableStateOf<VoiceRef?>(null) } // null = 用当前 provider 的默认预置音色
    var clonedVoices by remember { mutableStateOf<List<ClonedVoice>>(emptyList()) }
    var voiceMenuOpen by remember { mutableStateOf(false) }
    var customizingVoice by remember { mutableStateOf(false) }
    var customVoice by remember { mutableStateOf("") }
    var previewingVoice by remember { mutableStateOf(false) }
    LaunchedEffect(persona?.id, voiceSettings) {
        if (voiceSettings != null && persona != null) {
            ttsConfig = voiceSettings.ttsConfig.first()
            voiceRef = voiceSettings.voiceRefFor(persona.id).first()
        }
    }
    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        // 复制进应用私有目录：content:// 授权是一次性的，跨进程再用会 404
        runCatching {
            val dir = File(context.filesDir, "avatars").apply { mkdirs() }
            val target = File(dir, "avatar_${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            if (target.length() > 0) avatarImagePath = target.absolutePath
        }
    }

    fun buildPersona(): Persona = Persona(
        id = persona?.id ?: 0,
        name = name.trim(),
        title = title.trim().ifBlank { name.trim() },
        avatarEmoji = emoji.trim().ifBlank { "🙂" },
        avatarImagePath = avatarImagePath,
        systemPrompt = prompt.trim(),
        isBuiltIn = persona?.isBuiltIn ?: false,
        tone = persona?.tone ?: ToneStyle.GENTLE.name, // 对话风格已取消（v0.32.0），沿用原值
        pinned = persona?.pinned ?: false, // 编辑不改置顶状态
        createdAt = persona?.createdAt ?: System.currentTimeMillis(),
    )

    NeuSheetScaffold(
        title = if (persona == null) "新增人设" else "编辑人设",
        onDismiss = onDismiss,
    ) {
        Spacer(Modifier.height(16.dp))

        // 头像：直接点图上传/更换（不再有「自定义」按钮）。
        // 渐变环由 personaBrush(id) 恒定绘制，图片只是替换环内的内容 —— 换图不会掉环。
        Row(verticalAlignment = Alignment.CenterVertically) {
            NeuAvatar(
                emoji = emoji.trim().ifBlank { "🙂" },
                size = 56.dp,
                ringBrush = com.menghuan.app.ui.theme.personaBrush(persona?.id ?: 0L),
                imagePath = avatarImagePath,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { avatarPicker.launch("image/*") },
            )
            Spacer(Modifier.width(12.dp))
            // 已选图片时给一个明确的撤销口；未选时留一句状态说明（事实短语，不教用户怎么用）
            if (avatarImagePath.isNotBlank()) {
                NeuTextButton("恢复默认头像", onClick = { avatarImagePath = "" }, color = NeuDanger)
            } else {
                Text("点头像换图", fontSize = 12.sp, color = MutedText)
            }
        }
        Spacer(Modifier.height(12.dp))
        // emoji 快选：横向滑动一排预设，点了即换（头像用图时这排只决定回退显示什么）
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            avatarPresets.forEach { e ->
                NeuChip(text = e, selected = emoji == e) { emoji = e }
            }
        }
        Spacer(Modifier.height(14.dp))
        NeuTextField(
            value = name,
            onValueChange = { name = it },
            label = "名字",
            placeholder = "例：阿布",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        NeuTextField(
            value = title,
            onValueChange = { title = it },
            label = "身份",
            placeholder = "例：朋友 / 陪伴者 / 教练 / 心理咨询师",
            modifier = Modifier.fillMaxWidth(),
        )
        // 音色（v0.50.0）：当前 provider 的预置 + 我的克隆音色 + 试听。
        // **克隆动作不在这里做**——上传是账号级操作（要实名认证、产出的 uri 谁都能用），
        // 收在「设置 · 语音」里；人设这边只负责挑一个。
        if (voiceSettings != null && persona != null) {
            val currentVoiceId = voiceRef?.takeIf { it.provider == ttsConfig.provider }?.voiceId.orEmpty()
            Spacer(Modifier.height(14.dp))
            Box {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            voiceMenuOpen = true
                            // 打开菜单才拉克隆列表：这是网络请求，别在弹层一出现就打
                            if (ttsConfig.provider == TtsProvider.SILICONFLOW && ttsConfig.isComplete) {
                                editorScope.launch {
                                    runCatching { VoiceCloner.listVoices(ttsConfig) }
                                        .onSuccess { clonedVoices = it }
                                }
                            }
                        }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("音色", fontSize = 13.sp, color = TextPrimary)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            voiceLabel(ttsConfig, voiceRef, clonedVoices),
                            fontSize = 12.sp,
                            color = MutedText,
                        )
                        Spacer(Modifier.width(10.dp))
                        NeuChip(
                            text = if (previewingVoice) "播放中" else "试听",
                            selected = false,
                            onClick = {
                                if (previewingVoice || !ttsConfig.isComplete) return@NeuChip
                                previewingVoice = true
                                val target = voiceRef?.takeIf { it.provider == ttsConfig.provider && !it.isBlank }
                                    ?: VoiceRef(ttsConfig.provider, TtsVoices.defaultVoiceId(ttsConfig.provider))
                                editorScope.launch {
                                    val audio = TtsClients.from(ttsConfig).synthesize(VOICE_PREVIEW_TEXT, target)
                                    if (audio != null) {
                                        runCatching {
                                            playTtsPreview(context, audio.bytes, audio.format.extension)
                                        }
                                    }
                                    previewingVoice = false
                                }
                            },
                        )
                    }
                }
                DropdownMenu(
                    expanded = voiceMenuOpen,
                    onDismissRequest = { voiceMenuOpen = false },
                    containerColor = NeuSurface,
                    shadowElevation = 0.dp,
                    tonalElevation = 0.dp,
                ) {
                    TtsVoices.presets(ttsConfig.provider).forEach { preset ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(preset.label, fontSize = 14.sp, color = TextPrimary)
                                    Text(preset.id, fontSize = 10.sp, color = MutedText)
                                }
                            },
                            trailingIcon = if (currentVoiceId == preset.id) {
                                { Text("✓", color = TextPrimary, fontSize = 13.sp) }
                            } else null,
                            onClick = {
                                val ref = VoiceRef(ttsConfig.provider, preset.id)
                                voiceRef = ref
                                voiceMenuOpen = false
                                editorScope.launch { voiceSettings.setVoiceRefFor(persona.id, ref) }
                            },
                        )
                    }
                    clonedVoices.forEach { cloned ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("克隆 · ${cloned.customName}", fontSize = 14.sp, color = TextPrimary)
                                    Text(cloned.uri, fontSize = 10.sp, color = MutedText, maxLines = 1)
                                }
                            },
                            trailingIcon = if (currentVoiceId == cloned.uri) {
                                { Text("✓", color = TextPrimary, fontSize = 13.sp) }
                            } else null,
                            onClick = {
                                val ref = VoiceRef(ttsConfig.provider, cloned.uri)
                                voiceRef = ref
                                voiceMenuOpen = false
                                editorScope.launch { voiceSettings.setVoiceRefFor(persona.id, ref) }
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("填音色 ID…", fontSize = 14.sp, color = TextPrimary) },
                        onClick = {
                            customizingVoice = true
                            voiceMenuOpen = false
                        },
                    )
                }
            }
            if (customizingVoice) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NeuTextField(
                        value = customVoice,
                        onValueChange = { customVoice = it },
                        modifier = Modifier.weight(1f),
                        placeholder = "粘贴该服务商认的音色 ID",
                    )
                    Spacer(Modifier.width(8.dp))
                    NeuChip(
                        text = "确定",
                        selected = false,
                        onClick = {
                            val v = customVoice.trim()
                            if (v.isNotEmpty()) {
                                val ref = VoiceRef(ttsConfig.provider, v)
                                voiceRef = ref
                                customizingVoice = false
                                customVoice = ""
                                editorScope.launch { voiceSettings.setVoiceRefFor(persona.id, ref) }
                            }
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        NeuTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = "设定词（system prompt）",
            placeholder = "描述 TA 的性格、职责和说话方式",
            singleLine = false,
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Row {
            NeuTextButton("查看写作规范", onClick = { showGuide = true })
            Spacer(Modifier.width(16.dp))
            NeuTextButton("插入模板", onClick = {
                if (prompt.isBlank()) {
                    prompt = PERSONA_PROMPT_TEMPLATE
                } else {
                    confirmOverwrite = true // 已有内容：确认后覆盖
                }
            })
            Spacer(Modifier.width(16.dp))
            // v0.36.0：AI 优化设定词——把当前设定词或一段自由描述丢给 LLM，返回结构化三件套一键填入
            NeuTextButton("AI 优化设定词", onClick = { showAiOptimize = true })
        }

        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth()) {
            onDelete?.let { onDeleteAction ->
                NeuButton(
                    text = "删除",
                    onClick = onDeleteAction,
                    kind = NeuButtonKind.Danger,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
            }
            NeuButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            NeuButton(
                text = "保存",
                onClick = { onSave(buildPersona()) },
                kind = NeuButtonKind.Primary,
                enabled = name.isNotBlank(),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(6.dp))
    }

    if (showGuide) {
        PersonaPromptGuideSheet(onDismiss = { showGuide = false })
    }
    if (showAiOptimize) {
        // 初始输入：把当前设定词带过去（若为空则用户从头描述）；
        // onApply 一键回填三件套，emoji 有建议就一并覆盖
        AiPersonaOptimizeSheet(
            initialInput = prompt,
            onApply = { r ->
                name = r.name
                title = r.title
                prompt = r.systemPrompt
                r.avatarEmoji?.takeIf { it.isNotBlank() }?.let { emoji = it }
                showAiOptimize = false
            },
            onDismiss = { showAiOptimize = false },
        )
    }
    if (confirmOverwrite) {
        AlertDialog(
            tonalElevation = 0.dp, // v0.42.1 全应用去阴影（该重载阴影来自 tonalElevation）
            onDismissRequest = { confirmOverwrite = false },
            containerColor = NeuSurface,
            shape = RoundedCornerShape(20.dp),
            title = { Text("覆盖设定词", fontWeight = FontWeight.SemiBold, color = TextPrimary) },
            text = {
                Text(
                    "当前设定词不为空，插入模板将替换全部内容。确定继续吗？",
                    color = MutedText,
                    lineHeight = 20.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    prompt = PERSONA_PROMPT_TEMPLATE
                    confirmOverwrite = false
                }) { Text("覆盖", color = com.menghuan.app.ui.theme.NeuAccent) }
            },
            dismissButton = {
                TextButton(onClick = { confirmOverwrite = false }) { Text("取消", color = MutedText) }
            },
        )
    }
}

/** 设定词写作规范（应用内）：要素 + 模板 + 示例 + 要点，可在编辑器直接套用模板 */
@Composable
private fun PersonaPromptGuideSheet(onDismiss: () -> Unit) {
    NeuSheetScaffold(title = "设定词写作规范", onDismiss = onDismiss) {
        // 外层 NeuSheetScaffold 内容区已可滚动，这里不能再套 verticalScroll（会无限高度崩溃）
        Column(Modifier.fillMaxWidth()) {
            GuideSection("设定词是什么") {
                GuideText("设定词是写给 AI 的角色说明书，每次对话都会作为系统指令注入——它决定 TA 是谁、怎么说话、管什么事。")
            }
            GuideSection("好的设定词包含五要素") {
                GuideText("① 身份与角色：TA 是谁、专业背景是什么")
                GuideText("② 语气与说话方式：温和 / 直接 / 幽默 / 严格")
                GuideText("③ 职责范围：管什么、不管什么")
                GuideText("④ 行为约束：不做什么（防跑偏，最重要）")
                GuideText("⑤ 输出偏好：回复长短、要不要列步骤、要不要追问")
            }
            GuideSection("写作要点") {
                GuideText("· 具体大于抽象：「23:00 前提醒我睡觉」好于「督促我作息」")
                GuideText("· 一定要写约束：告诉 TA 不要做什么")
                GuideText("· 长度 100–300 字为宜，太长会稀释重点")
                GuideText("· 避免互相矛盾的要求")
                GuideText("· 用「插入模板」拿到底稿再改，比从零写快")
            }
            GuideSection("示例：健身教练") {
                GuideText(
                    "你是用户的私人健身教练，语气直接、不客套。\n" +
                        "职责：根据用户的身体情况安排每周训练计划，纠正动作与饮食。\n" +
                        "约束：不推荐药物与补剂品牌；用户偷懒时直接指出，不安慰。\n" +
                        "输出：训练计划用短列表，每次不超过 5 条。",
                )
            }
            GuideSection("示例：学习教练") {
                GuideText(
                    "你是用户的学习教练，专注英语备考，语气耐心但要求严格。\n" +
                        "职责：拆解备考目标到每日任务，检查单词与听力打卡。\n" +
                        "约束：不代写答案，只给思路；发现连续两天没打卡必须追问原因。\n" +
                        "输出：先结论后步骤，每次聚焦一个薄弱点。",
                )
            }
            GuideSection("写进设定词：AI 能替你做的事") {
                GuideText("除了聊天，AI 在这个应用里还能真的动手做事。可以在设定词里明确授权范围，让 TA 用起来：")
                GuideText("· 派发任务：让 TA 把讨论结论变成可勾选、可打卡的待办（会出现在任务页并按点提醒）")
                GuideText("· 打卡督促：把「今天做到哪了」的复盘变成每日任务，打卡记录会回写到对话里")
                GuideText("· 操作手机：用户授权手机 Agent 后，TA 能读屏并代替用户点击输入（有白名单围栏与敏感操作确认）")
                GuideText("· 生成图片：需要配图时 TA 能直接出图")
                GuideText("· 周报与洞察：每周自动汇总任务、打卡、档案与对话，生成 AI 周报")
                GuideText("· 记住你：档案与偏好由提炼引擎自动更新，后续对话会带上这些背景")
            }
            GuideSection("授权的写法示例") {
                GuideText(
                    "你可以直接给我派任务：我说「安排一下」时，把结论拆成每日任务写进任务页。\n" +
                        "你也可以调用手机操作帮我完成设置类操作，但涉及支付、删除、发送的动作必须先问我。\n" +
                        "约束：不要替我承诺做不到的时间点。",
                )
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun GuideSection(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Spacer(Modifier.height(14.dp))
    Text(
        title,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = TextPrimary,
    )
    Spacer(Modifier.height(6.dp))
    Column(content = content)
}

@Composable
private fun GuideText(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        color = MutedText,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}
