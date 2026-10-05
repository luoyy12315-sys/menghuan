package com.menghuan.app.ui.persona

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.menghuan.app.MenghuanApp
import com.menghuan.app.data.ClonedVoice
import com.menghuan.app.data.PersonaRepository
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
import com.menghuan.app.ui.common.NeuSheetRow
import com.menghuan.app.ui.common.NeuSheetScaffold
import com.menghuan.app.ui.common.NeuTextButton
import com.menghuan.app.ui.common.NeuTextField
import com.menghuan.app.ui.settings.SettingsPage
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.NeuSurface
import com.menghuan.app.ui.theme.TextPrimary
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

/**
 * 头像 emoji 快选：**只用人的形象**（v0.53.4 用户点名"全部换成人的形象"）。
 * v0.53.5：**点它不再选头像**（点出来的黑色选中底用户不要），只保留"左右滑动即选"。
 */
private val avatarPresets = listOf(
    "🙂", "😊", "😄", "😎", "🤗", "😌",
    "👦", "👧", "🧑", "👨", "👩", "🧓", "👴", "👵",
    "🧑‍🎓", "👨‍🏫", "👩‍🏫", "👨‍⚕️", "👩‍⚕️", "👨‍🍳", "👩‍🍳",
    "🧑‍💻", "👮", "👷", "🧘", "🏃",
)

/** 表单态的纯数据（保存时转成 [Persona]） */
private data class PersonaDraft(
    val name: String = "",
    val title: String = "",
    val emoji: String = "🙂",
    val prompt: String = "",
    val avatarImagePath: String = "",
)

/** 编辑人设页的数据源：读全部人设 → 按 id 找那条 */
class PersonaEditorViewModel(
    private val repository: PersonaRepository,
    private val settingsRepository: com.menghuan.app.data.SettingsRepository,
) : ViewModel() {
    /** null = 还没读到（三态：未加载 / 找不到（走新建）/ 找到了） */
    val personas = repository.observePersonas()

    fun save(persona: Persona) = viewModelScope.launch { repository.save(persona) }

    /** 删人设时**顺手清掉它的主动打电话规则**，别在设置里留一条对着空气的规则 */
    fun delete(id: Long) = viewModelScope.launch {
        repository.delete(id)
        runCatching { settingsRepository.removeAiCallRule(id) }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MenghuanApp
                PersonaEditorViewModel(app.personaRepository, app.settingsRepository)
            }
        }
    }
}

/**
 * 编辑人设（**v0.53.5 起是真正的整页**：独立路由 `persona_editor/{personaId}`，不再是弹窗、不是 Dialog）。
 *
 * [personaId] 传 0 = 新建。
 *
 * 几处用户点名的形状：
 * - 头像与人物 emoji **同一排**：那排**只认滚动**（点它不选头像，也不会出现黑色选中底），
 *   滑到哪、头像框里就变成谁；点头像框本身仍可换自定义图；
 * - 音色候选只有 **男声 / 女声 / 我克隆的声音**，点开是**从下方升起的弹层**（不是贴着选项的菜单）；
 * - 设定词只有「查看写作规范」与「AI 优化设定词」，没有模板按钮。
 */
@Composable
fun PersonaEditorScreen(
    personaId: Long,
    onBack: () -> Unit,
    vm: PersonaEditorViewModel = viewModel(factory = PersonaEditorViewModel.Factory),
) {
    val all by vm.personas.collectAsState(initial = null)
    val loaded = all
    if (loaded == null) {
        SettingsPage(title = if (personaId == 0L) "新增人设" else "编辑人设", onBack = onBack) {
            Spacer(Modifier.height(20.dp))
            Text("读取中…", fontSize = 13.sp, color = MutedText)
        }
        return
    }
    val persona = loaded.firstOrNull { it.id == personaId }
    PersonaEditorContent(
        persona = persona,
        onSave = { vm.save(it); onBack() },
        onDelete = persona?.let { p -> { vm.delete(p.id); onBack() } },
        onBack = onBack,
    )
}

@Composable
private fun PersonaEditorContent(
    persona: Persona?,
    onSave: (Persona) -> Unit,
    onDelete: (() -> Unit)?,
    onBack: () -> Unit,
) {
    var draft by remember(persona?.id) {
        mutableStateOf(
            PersonaDraft(
                name = persona?.name ?: "",
                title = persona?.title ?: "",
                emoji = persona?.avatarEmoji ?: "🙂",
                prompt = persona?.systemPrompt ?: "",
                avatarImagePath = persona?.avatarImagePath.orEmpty(),
            )
        )
    }
    var showGuide by remember { mutableStateOf(false) }
    var showAiOptimize by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 点头像框换图（v0.53.4：只删了那句提示文字，功能保留）
    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val dir = File(context.filesDir, "avatars").apply { mkdirs() }
            val target = File(dir, "avatar_${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            if (target.length() > 0) draft = draft.copy(avatarImagePath = target.absolutePath)
        }
    }

    // emoji 那排：**只认滚动**——滑到哪、头像框里就是谁；点它不改头像（也不再画黑色选中底）
    val avatarListState = rememberLazyListState()
    LaunchedEffect(avatarListState) {
        snapshotFlow {
            if (!avatarListState.isScrollInProgress) return@snapshotFlow null
            val info = avatarListState.layoutInfo
            if (info.visibleItemsInfo.isEmpty()) return@snapshotFlow null
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
            info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2f - center) }?.index
        }.filterNotNull().collect { index ->
            avatarPresets.getOrNull(index)?.let { draft = draft.copy(emoji = it) }
        }
    }

    // v0.53.6：音色的候选/弹层整块搬去「对话页右上角菜单 → 音色」（PersonaVoiceSheet）

    SettingsPage(
        title = if (persona == null) "新增人设" else "编辑人设",
        onBack = onBack,
    ) {
        Spacer(Modifier.height(16.dp))

        // 头像与 emoji 同一排（框里比这一排大）
        Row(verticalAlignment = Alignment.CenterVertically) {
            NeuAvatar(
                emoji = draft.emoji.trim().ifBlank { "🙂" },
                size = 56.dp,
                ringBrush = com.menghuan.app.ui.theme.personaBrush(persona?.id ?: 0L),
                imagePath = draft.avatarImagePath,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { avatarPicker.launch("image/*") },
            )
            Spacer(Modifier.width(12.dp))
            LazyRow(
                state = avatarListState,
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                itemsIndexed(avatarPresets) { _, e ->
                    // 纯文字、无底色：滚动到它就选中它（点它不做事，所以不会出现黑色选中底）
                    Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                        Text(e, fontSize = 22.sp)
                    }
                }
            }
        }
        if (draft.avatarImagePath.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            NeuTextButton("恢复默认头像", onClick = { draft = draft.copy(avatarImagePath = "") }, color = NeuDanger)
        }
        Spacer(Modifier.height(14.dp))
        NeuTextField(
            value = draft.name,
            onValueChange = { draft = draft.copy(name = it) },
            label = "名字",
            placeholder = "例：阿布",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        NeuTextField(
            value = draft.title,
            onValueChange = { draft = draft.copy(title = it) },
            label = "身份",
            placeholder = "例：朋友 / 陪伴者 / 教练 / 心理咨询师",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        // v0.53.6：音色那一项已按用户要求**从编辑人设里移除** ——
        // 改为从「对话页右上角菜单 → 音色」选（见 ui/persona/PersonaVoiceSheet.kt）。
        NeuTextField(
            value = draft.prompt,
            onValueChange = { draft = draft.copy(prompt = it) },
            label = "设定词",
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
            NeuTextButton("AI 优化设定词", onClick = { showAiOptimize = true })
        }

        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth()) {
            onDelete?.let { onDeleteAction ->
                NeuButton(
                    text = "删除",
                    onClick = { confirmDelete = true },
                    kind = NeuButtonKind.Danger,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
            }
            NeuButton(
                text = "取消",
                onClick = onBack,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            NeuButton(
                text = "保存",
                onClick = {
                    onSave(
                        Persona(
                            id = persona?.id ?: 0,
                            name = draft.name.trim(),
                            title = draft.title.trim().ifBlank { draft.name.trim() },
                            avatarEmoji = draft.emoji.trim().ifBlank { "🙂" },
                            avatarImagePath = draft.avatarImagePath,
                            systemPrompt = draft.prompt.trim(),
                            isBuiltIn = persona?.isBuiltIn ?: false,
                            tone = persona?.tone ?: ToneStyle.GENTLE.name, // 对话风格已取消（v0.32.0）
                            pinned = persona?.pinned ?: false,
                            createdAt = persona?.createdAt ?: System.currentTimeMillis(),
                        )
                    )
                },
                kind = NeuButtonKind.Primary,
                enabled = draft.name.isNotBlank(),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(6.dp))
    }

    // v0.53.6：音色选择已从这里搬走（对话页右上角菜单 → 音色），本页不再有音色弹层

    if (showGuide) {
        PersonaPromptGuideSheet(onDismiss = { showGuide = false })
    }
    if (showAiOptimize) {
        AiPersonaOptimizeSheet(
            initialInput = draft.prompt,
            onApply = { r ->
                draft = draft.copy(
                    name = r.name,
                    title = r.title,
                    prompt = r.systemPrompt,
                )
                r.avatarEmoji?.takeIf { it.isNotBlank() }?.let { draft = draft.copy(emoji = it) }
                showAiOptimize = false
            },
            onDismiss = { showAiOptimize = false },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            tonalElevation = 0.dp,
            onDismissRequest = { confirmDelete = false },
            containerColor = NeuSurface,
            shape = RoundedCornerShape(20.dp),
            title = { Text("删除人设", fontWeight = FontWeight.SemiBold, color = TextPrimary) },
            text = { Text("「${persona?.name.orEmpty()}」及其聊天记录都会被删除，确定吗？", color = MutedText, lineHeight = 20.sp) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete?.invoke() }) {
                    Text("删除", color = NeuDanger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消", color = MutedText) }
            },
        )
    }
}

/** 设定词写作规范（应用内）：要素 + 示例 + 要点，可在编辑器里对照着写 */
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
                GuideText("· 照着下面两个示例的结构写，比从零想快")
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
