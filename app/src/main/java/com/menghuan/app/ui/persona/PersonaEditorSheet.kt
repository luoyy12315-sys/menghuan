package com.menghuan.app.ui.persona

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.menghuan.app.data.ASSISTANT_PERSONA_NAME
import com.menghuan.app.data.PersonaPromptSpec
import com.menghuan.app.data.PersonaRepository
import com.menghuan.app.data.model.Persona
import com.menghuan.app.data.model.ToneStyle
import com.menghuan.app.ui.common.AvatarEmojiWheel
import com.menghuan.app.ui.common.AvatarPill
import com.menghuan.app.ui.common.NeuButton
import com.menghuan.app.ui.common.NeuButtonKind
import com.menghuan.app.ui.common.NeuSheetScaffold
import com.menghuan.app.ui.common.NeuTextButton
import com.menghuan.app.ui.common.NeuTextField
import com.menghuan.app.ui.common.rememberAvatarWheelState
import com.menghuan.app.ui.settings.SettingsPage
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.NeuSurface
import com.menghuan.app.ui.theme.TextPrimary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * 内置助手的名字字段是**只读**的（见编辑区那条注释），但要走 `NeuTextField.onClick`
 * 那条只读分支就必须给一个点击动作 —— 这里给一个什么都不做的：
 * 它要的效果就是"点它不弹键盘、也不跳任何地方"，纯粹把这一格显示成不可改。
 */
private val ASSISTANT_NAME_LOCK_CLICK: () -> Unit = {}

/** 表单态的纯数据（保存时转成 [Persona]） */
private data class PersonaDraft(
    val name: String = "",
    val title: String = "",
    val emoji: String = "🙂",
    val prompt: String = "",
    val avatarImagePath: String = "",
    /** 角色立绘（v0.58.1）：AI 生图时当参考图，让 TA 画"自己"长相一致 */
    val artImagePath: String = "",
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
                artImagePath = persona?.artImagePath.orEmpty(),
            )
        )
    }
    var showGuide by remember { mutableStateOf(false) }
    var showAiOptimize by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val context = LocalContext.current

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

    // v0.58.1：角色立绘。和头像一样**复制进私有目录**再存绝对路径——
    // SAF 的读授权是会话级的，冷启动后 URI 就可能读不到，而这张图每次生图都要用。
    // 目录独立（portraits/），不和 avatars/ 混：一个是"脸"、一个是"整个人"。
    val artPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val old = draft.artImagePath
        runCatching {
            val dir = File(context.filesDir, "portraits").apply { mkdirs() }
            // 落盘文件名**沿用用户那张图的原名**：立绘那一栏的输入框显示的就是它。
            // 编一个 art_<时间戳> 出来，用户看到的是自己没见过的名字（v0.60.1）。
            val target = portraitTargetFile(dir, safDisplayName(context, uri))
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            if (target.length() > 0) {
                draft = draft.copy(artImagePath = target.absolutePath)
                // 换图就把上一张删掉，否则每改一次留一张在盘上
                if (old.isNotBlank() && old != target.absolutePath) {
                    runCatching { File(old).delete() }
                }
            }
        }
    }

    // 头像滚轮（v0.80.0：整块抽到 `ui/common/AvatarEmojiWheel.kt`，与「个人档案」共用一份实现）。
    // 形状与手感还是 v0.56.9~v0.56.15 定下来的那套：框 = 放大镜、滚到框位 = 选中、
    // 落定的那个被"挤进框里"、每跨一格轻震一下、点框换图；下面那颗细线胶囊一直在。
    // ⚠️ commitInitialAnchor 保持默认 true：新角色空 emoji 会被落成第一个预设（原来的行为）。
    val wheelState = rememberAvatarWheelState()

    SettingsPage(
        title = if (persona == null) "新增人设" else "编辑人设",
        onBack = onBack,
    ) {
        Spacer(Modifier.height(16.dp))
        AvatarEmojiWheel(
            emoji = draft.emoji,
            imagePath = draft.avatarImagePath,
            onEmojiChange = { draft = draft.copy(emoji = it) },
            onFrameClick = { avatarPicker.launch("image/*") },
            state = wheelState,
            frameSize = 56.dp,
            cellSize = 44.dp,
            lensSpan = 52.dp,
            emojiFontSize = 27.sp,
            ringBrush = com.menghuan.app.ui.theme.personaBrush(persona?.id ?: 0L),
            placeholder = "🙂",
        )
        Spacer(Modifier.height(4.dp))
        AvatarPill(
            text = if (draft.avatarImagePath.isNotBlank()) "恢复默认" else "点击编辑",
            danger = draft.avatarImagePath.isNotBlank(),
            width = 56.dp,
        ) {
            if (draft.avatarImagePath.isNotBlank()) {
                draft = draft.copy(avatarImagePath = "")
            } else {
                avatarPicker.launch("image/*")
            }
        }

        Spacer(Modifier.height(14.dp))
        // ⚠️ 内置「应用助手」的名字**锁住不给改**（v0.68.0）：整条"手册跟着版本走"的机制
        // 是按这份设定词属于谁运转的，名字一改，提示词页查不到、保存会再建一条助手、
        // 启动升级也找不到它 —— 他那本手册就永远停在改名那一刻（AGENTS §零.5 的红线）。
        // 只读走 NeuTextField 的 onClick 分支：同一个 label 与内边距，不弹键盘、不出现光标。
        val assistantLocked = persona?.isBuiltIn == true &&
            persona.name == ASSISTANT_PERSONA_NAME
        val lockedClick: (() -> Unit)? = if (assistantLocked) ASSISTANT_NAME_LOCK_CLICK else null
        NeuTextField(
            value = draft.name,
            onValueChange = { if (!assistantLocked) draft = draft.copy(name = it) },
            onClick = lockedClick,
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
        // v0.58.1：**角色立绘**（和头像两回事）—— AI 生图画"自己"时发给生图服务的参考图，
        // 让发色 / 瞳色 / 服装 / 画风保持一致；头像只是圆裁的一张脸，当参考图等于把形象全丢了。
        // v0.60.1（用户点名）：挪到「身份」下面，形状改成和名字/身份**同款的输入框**，
        // 框里显示已选图片的文件名、点整块弹图片选择器，原来那颗「上传立绘」按钮删掉。
        // label 字号因此天然与其它字段一致（同一个组件、同一份排版），不另画一套"看起来像输入框"的东西。
        // 已上传时下面留一颗「删除立绘」（与头像那行的「恢复默认头像」同一形状）——
        // 上传入口收进框里之后，这是唯一的清除口。按项目规矩：这里不写说明性小字。
        NeuTextField(
            value = draft.artImagePath.takeIf { it.isNotBlank() }?.let { File(it).name }.orEmpty(),
            onValueChange = {},
            label = "角色立绘",
            placeholder = "未上传，点这里选择图片",
            onClick = { artPicker.launch("image/*") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (draft.artImagePath.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            NeuTextButton(
                "删除立绘",
                onClick = {
                    runCatching { File(draft.artImagePath).delete() }
                    draft = draft.copy(artImagePath = "")
                },
                color = NeuDanger,
            )
        }
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
                    // v0.56.11：头像以**此刻框位上的 emoji** 为准（兜底重算一次），
                    // 不再单纯依赖滚动过程中的同步——"滚到框上就点保存"也不会丢
                    val savedEmoji = (wheelState.emojiAtAnchor() ?: draft.emoji).trim().ifBlank { "🙂" }
                    onSave(
                        Persona(
                            id = persona?.id ?: 0,
                            name = draft.name.trim(),
                            title = draft.title.trim().ifBlank { draft.name.trim() },
                            avatarEmoji = savedEmoji,
                            avatarImagePath = draft.avatarImagePath,
                            artImagePath = draft.artImagePath,
                            systemPrompt = draft.prompt.trim(),
                            isBuiltIn = persona?.isBuiltIn ?: false,
                            tone = persona?.tone ?: ToneStyle.GENTLE.name, // 对话风格已取消（v0.32.0）
                            pinned = persona?.pinned ?: false,
                            // ⚠️ v0.58.1：**这两个必须从原人设带过来**，漏一个就会在编辑设定词/头像时
                            // 把人设的通话形象绑定的那一列悄悄重置成默认值（VideoCall 白配）。
                            hidden = persona?.hidden ?: false,
                            videoVisualType = persona?.videoVisualType ?: "NONE",
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

/**
 * 设定词写作规范（应用内，v0.79.0 重写）。
 *
 * **这份内容的主体是给"外部 AI"看的**（用户拍板原话："把这个规范扔给其他 AI，
 * 就可以让他生成符合这个应用的人设提示词"）。所以面板 = 一句"这是干什么用的" + 一颗复制按钮
 * + 规范原文；不再有「示例：健身教练 / 学习教练」那两段，也不给填空模板
 * —— 用户点名："不要健身教练那些玩意""不要模板，也不要例子"。
 *
 * 规范正文的唯一来源是 [PersonaPromptSpec.TEXT]：编辑页弹出的这份、
 * 与「AI 优化设定词」按钮实际照的规范，是同一份文本（不许两边各说各话）。
 */
@Composable
private fun PersonaPromptGuideSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    NeuSheetScaffold(title = "设定词写作规范", onDismiss = onDismiss) {
        // 外层 NeuSheetScaffold 内容区已可滚动，这里不能再套 verticalScroll（会无限高度崩溃）
        Column(Modifier.fillMaxWidth()) {
            Text(
                "把这份规范复制下来，连同你想要的角色一起发给任意一个 AI" +
                    "（ChatGPT / Claude / DeepSeek 都行）——它会给你名字、身份和一份能直接用在这里的设定词。",
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = MutedText,
            )
            Spacer(Modifier.height(12.dp))
            NeuButton(
                text = if (copied) "已复制" else "复制规范",
                kind = NeuButtonKind.Primary,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as? android.content.ClipboardManager
                    cm?.setPrimaryClip(
                        android.content.ClipData.newPlainText("menghuan", PersonaPromptSpec.TEXT),
                    )
                    copied = true
                },
            )
            Spacer(Modifier.height(16.dp))
            // 规范原文：按空行分段渲染，长文本才不会挤成一坨
            PersonaPromptSpec.TEXT.split("\n\n").forEach { para ->
                if (para.isNotBlank()) {
                    Text(
                        para,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = TextPrimary,
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}


/** SAF 选中项的原始文件名；查不到就退回 URI 末段（立绘输入框显示的就是它） */
private fun safDisplayName(context: Context, uri: Uri): String = runCatching {
    var name: String? = null
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
    }
    name ?: uri.lastPathSegment?.substringAfterLast('/')
}.getOrNull().orEmpty()

/** 收掉路径分隔符与控制符、重名自动加序号；只落进 portraits/ 这一层目录 */
private fun portraitTargetFile(dir: File, rawName: String): File {
    val cleaned = rawName
        .replace(Regex("[^\\p{L}\\p{Digit}._\\- ]"), "_")
        .trim('.', ' ', '_')
        .take(60)
    val base = cleaned.substringBeforeLast('.').ifBlank { "立绘" }
    val ext = cleaned.substringAfterLast('.', "jpg")
        .takeIf { it.isNotBlank() && it != cleaned && it.length <= 5 } ?: "jpg"
    var f = File(dir, "$base.$ext")
    var i = 0
    while (f.exists()) {
        i++
        f = File(dir, "$base-$i.$ext")
    }
    return f
}
