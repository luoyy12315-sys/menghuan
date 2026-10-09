package com.menghuan.app.ui.live2d

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.data.Live2dSpaceStore
import com.menghuan.app.live2d.ActionComposer
import com.menghuan.app.live2d.ActionPart
import com.menghuan.app.live2d.ActionSource
import com.menghuan.app.live2d.ActionType
import com.menghuan.app.live2d.GenericChannel
import com.menghuan.app.live2d.Live2dAction
import com.menghuan.app.ui.common.NeuMenuItem
import com.menghuan.app.ui.common.NeuMenu
import com.menghuan.app.ui.common.NeuSheetScaffold
import com.menghuan.app.ui.common.NeuSwitch
import com.menghuan.app.ui.common.NeuTextField
import com.menghuan.app.ui.common.neuSurface
import com.menghuan.app.ui.theme.AccentInk
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.NeuHairline
import com.menghuan.app.ui.theme.NeuSurface
import com.menghuan.app.ui.theme.NeuTrack
import com.menghuan.app.ui.theme.TextPrimary
import com.menghuan.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * 角色空间用到的几个弹层（v0.64.0）。
 *
 * 全部走 [NeuSheetScaffold]（与「模型选择」同一个从底部升上来的弹层）——
 * 项目里"弹层"只有这一套容器，不另立第二种。
 *
 * 三条项目约定在这里同样成立：
 * 1. **可点区域不画涟漪**（`indication = null`），也不做"点击之后的灰色条"；
 * 2. **删除类操作一律长按 → 菜单里点删除**，长按补一次震动；
 * 3. 每层都只有一个主操作，别把"预览 / 保存 / 收藏 / 删除"平铺成一排。
 */

// =====================================================================
// 动作库
// =====================================================================

/**
 * 动作库：内置 / 模型自带 / 我的动作 / AI 生成，四档分组。
 *
 * 交互：
 * - 点一行 = **预览**（立刻在角色身上播，这是"动作库"最核心的动作）；
 * - 行尾的心 = 收藏（收藏的排在前面，由仓库层的排序保证）；
 * - **长按** = 菜单：内置的可以「另存为我的动作」（用户要的"待机 → 自定义待机"就是这条路），
 *   自己建的可以删除。
 *
 * ⚠️ 这一页**不写"点一行就是试播"那类说明**（AGENTS §四"界面不写说明性小字"）：
 * 行首那颗播放三角就是"点它会播"的交代，多加一行反而把列表推下去一截。
 */
@Composable
fun ActionLibrarySheet(
    actions: List<Live2dAction>,
    playingId: Long,
    onPlay: (Live2dAction) -> Unit,
    onFavorite: (Live2dAction) -> Unit,
    onDelete: (Live2dAction) -> Unit,
    onEdit: (Live2dAction) -> Unit,
    onDismiss: () -> Unit,
) {
    NeuSheetScaffold(title = "动作库", onDismiss = onDismiss) {
        if (actions.isEmpty()) {
            Text("还没有动作", fontSize = 13.sp, color = MutedText)
            return@NeuSheetScaffold
        }
        Spacer(Modifier.height(6.dp))

        // 分组顺序固定：能玩的最先（趣味动作），然后是模型自带的、自己的、AI 的
        val groups = listOf(
            ActionSource.BUILTIN to "内置动作",
            ActionSource.MODEL to "模型自带",
            ActionSource.USER to "我的动作",
            ActionSource.AI to "AI 生成",
        )
        groups.forEach { (source, title) ->
            val rows = actions.filter { it.source == source }
            if (rows.isEmpty()) return@forEach
            Spacer(Modifier.height(4.dp))
            Text(
                "$title · ${rows.size}",
                fontSize = 11.sp,
                color = MutedText,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
            )
            rows.forEach { action ->
                ActionLibraryRow(
                    action = action,
                    playing = action.id == playingId,
                    onPlay = { onPlay(action) },
                    onFavorite = { onFavorite(action) },
                    onDelete = { onDelete(action) },
                    onEdit = { onEdit(action) },
                )
                Spacer(Modifier.height(8.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionLibraryRow(
    action: Live2dAction,
    playing: Boolean,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .neuSurface(14.dp, color = if (playing) NeuTrack else NeuSurface)
                .combinedClickable(
                    onClick = onPlay,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    },
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (playing) MIcons.Pause else MIcons.Play,
                contentDescription = null,
                tint = if (playing) NeuAccent else TextPrimary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    action.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitleOf(action),
                    fontSize = 12.sp,
                    color = MutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            // 收藏：单独一颗可点区域，别和整行的"预览"抢同一次点击
            Box(
                Modifier
                    .size(38.dp)
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                        onClick = onFavorite,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    MIcons.Heart,
                    contentDescription = if (action.favorite) "取消收藏" else "收藏",
                    tint = if (action.favorite) NeuDanger else MutedText,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        // 长按菜单锚在这个 0 宽盒子上：DropdownMenu 的 Popup 只认直接父节点的位置
        NeuMenu(
            expanded = menu,
            onDismissRequest = { menu = false },
        ) {
            NeuMenuItem(text = "试播一次", onClick = { menu = false; onPlay() })
            NeuMenuItem(text = "另存为我的动作", onClick = { menu = false; onEdit() })
            if (action.deletable) {
                NeuMenuItem(text = "删除", onClick = { menu = false; onDelete() }, danger = true)
            }
        }
    }
}

/** 一行的副标题：来源 + 时长 + 这个动作会动哪些部分（普通用户看得懂的那种描述） */
private fun subtitleOf(action: Live2dAction): String {
    val parts = action.tracks.map { ActionPart.of(it.channel) }.distinct()
    val kind = when {
        action.type == ActionType.NATIVE -> "模型自带动作"
        action.type == ActionType.EXPRESSION -> "表情"
        parts.isEmpty() -> "表情"
        else -> parts.joinToString("+") { it.label }
    }
    val seconds = (action.safeDurationMs / 100f).let { String.format(java.util.Locale.CHINA, "%.1f", it) }
    val loop = if (action.loop) " · 循环" else ""
    return "$kind · ${seconds}s$loop"
}

// =====================================================================
// 新建 / 编辑动作（普通模式）
// =====================================================================

/**
 * 「新建动作」编辑器 —— 普通模式 + **高级模式**（v0.74.0 ROADMAP 候选②）。
 *
 * 普通模式用户看到的是：名字、基础动作、强度、速度、时长、四个部位开关、表情、预览、保存。
 * **全程不出现任何 Live2D 参数名。**
 *
 * 高级模式（用户点标题右侧的「高级」展开）给的是**逐通道的幅度倍率**：
 * 每个用到的通道一行滑块（0.2×–2.0×），解决"整体强度调大之后，某一条动得过了/不够"这种事。
 *
 * ⚠️ 高级模式**刻意不做逐关键帧编辑**：那是完整关键帧编辑器的量级，对绝大多数用户是负担。
 * 倍率是"整体强度"与"逐帧坐标"之间唯一真正能用的一层。
 * 普通模式里那四个部位开关在高级模式下依然有效（筛轨道的动作不变），两层叠加。
 */
@Composable
fun ActionEditorSheet(
    base: Live2dAction?,
    /** 可以当基础动作的：内置的趣味动作 + 用户自己建的（模型自带的也能用，但它们的时长由模型决定，先不放） */
    templates: List<Live2dAction>,
    /** 当前模型自带的表情名 */
    expressions: List<String>,
    onPreview: (Live2dAction) -> Unit,
    onSave: (Live2dAction) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(base) { mutableStateOf(base?.name ?: "") }
    var chosenKey by remember(base) { mutableStateOf(base?.builtinKey ?: NONE_KEY) }
    var chosenId by remember(base) { mutableStateOf(base?.id ?: 0L) }
    var amplitude by remember(base) { mutableStateOf(base?.safeAmplitude ?: 1f) }
    var speed by remember(base) { mutableStateOf(base?.safeSpeed ?: 1f) }
    var durationMs by remember(base) { mutableStateOf(base?.safeDurationMs ?: 2400) }
    var parts by remember(base) { mutableStateOf(ActionComposer.partsOf(base)) }
    var expression by remember(base) { mutableStateOf(base?.expression.orEmpty()) }
    // 高级模式：展开 + 逐通道倍率表（空表 = 全 1.0）
    var advanced by remember(base) { mutableStateOf(false) }
    var channelFactors by remember(base) { mutableStateOf(emptyMap<GenericChannel, Float>()) }

    val selectedTemplate = remember(chosenKey, chosenId, templates, base) {
        when {
            base != null && chosenKey == (base.builtinKey.ifBlank { NONE_KEY }) && chosenId == base.id -> base
            chosenKey == NONE_KEY -> null
            chosenKey.startsWith("id:") -> templates.firstOrNull { it.id == chosenId }
            else -> templates.firstOrNull { it.builtinKey == chosenKey }
        }
    }

    // 高级模式要调的通道 = 这套动作真正用到的那些（按部位开关筛完之后的）
    val advChannels = remember(selectedTemplate, parts) {
        val raw = selectedTemplate?.tracks?.takeIf { it.isNotEmpty() }
            ?: ActionComposer.defaultTracks(parts)
        raw.map { it.channel }.distinct()
    }

    fun build() = ActionComposer.compose(
        base = selectedTemplate,
        name = name,
        amplitude = amplitude,
        speed = speed,
        durationMs = durationMs,
        parts = parts,
        expression = expression,
        channelFactors = if (advanced) channelFactors else emptyMap(),
    )

    NeuSheetScaffold(title = if (base != null) "调整动作" else "新建动作", onDismiss = onDismiss) {
        NeuTextField(
            value = name,
            onValueChange = { name = it },
            label = "动作名字",
            placeholder = "比如：开心挥手",
        )
        Spacer(Modifier.height(16.dp))

        Text("基础动作", fontSize = 11.sp, color = TextSecondary, letterSpacing = 0.6.sp)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                PickChip(
                    text = "从零拼一个",
                    selected = chosenKey == NONE_KEY,
                    onClick = { chosenKey = NONE_KEY; chosenId = 0L },
                )
            }
            items(templates, key = { "${it.id}|${it.builtinKey}|${it.name}" }) { t ->
                PickChip(
                    text = t.name,
                    selected = chosenKey == t.builtinKey.ifBlank { "id:${t.id}" },
                    onClick = {
                        chosenKey = t.builtinKey.ifBlank { "id:${t.id}" }
                        chosenId = t.id
                        if (name.isBlank()) name = t.name
                        durationMs = t.safeDurationMs
                        parts = ActionComposer.partsOf(t)
                    },
                )
            }
        }
        Spacer(Modifier.height(18.dp))

        // 滑块的上限**直接取数据层那两个常数**（v0.66 之前这里写的是 1.8 / 1.8，
        // 比 `Live2dAction.MAX_AMPLITUDE / MAX_SPEED` 小一号）：两套上限并存时，
        // 用户在别处（AI 生成、导入）能拿到 2.0、在编辑器里却拖不到 —— 那是两个东西在说话。
        SliderRow(
            label = "强度",
            value = amplitude,
            range = Live2dAction.MIN_AMPLITUDE..Live2dAction.MAX_AMPLITUDE,
            display = "×%.1f".format(amplitude),
        ) {
            amplitude = it
        }
        SliderRow(
            label = "速度",
            value = speed,
            range = Live2dAction.MIN_SPEED..Live2dAction.MAX_SPEED,
            display = "×%.1f".format(speed),
        ) {
            speed = it
        }
        SliderRow(
            label = "时长",
            value = durationMs.toFloat(),
            range = 600f..6000f,
            display = "%.1f 秒".format(durationMs / 1000f),
        ) { durationMs = it.toInt() }

        Spacer(Modifier.height(6.dp))
        Text("动作包含哪些部分", fontSize = 11.sp, color = TextSecondary, letterSpacing = 0.6.sp)
        Spacer(Modifier.height(8.dp))
        ActionPart.entries.forEach { part ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(part.label, fontSize = 14.5.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                NeuSwitch(
                    checked = part in parts,
                    onToggle = { on ->
                        parts = if (on) parts + part else parts - part
                    },
                )
            }
        }

        if (expressions.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("顺带做个表情", fontSize = 11.sp, color = TextSecondary, letterSpacing = 0.6.sp)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    PickChip(text = "不用", selected = expression.isBlank(), onClick = { expression = "" })
                }
                items(expressions, key = { it }) { e ->
                    PickChip(text = e, selected = expression == e, onClick = { expression = e })
                }
            }
        }

        // ---- 高级模式（v0.74.0）：逐通道幅度倍率 ----
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = { advanced = !advanced },
                )
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("高级", fontSize = 14.5.sp, color = TextPrimary, modifier = Modifier.weight(1f))
            Text(if (advanced) "▾" else "▸", fontSize = 13.sp, color = MutedText)
        }
        if (advanced) {
            Spacer(Modifier.height(6.dp))
            if (advChannels.isEmpty()) {
                Text("这套动作还没有可调的通道", fontSize = 12.sp, color = MutedText)
            } else {
                advChannels.forEach { ch ->
                    val f = channelFactors[ch] ?: 1f
                    SliderRow(
                        label = channelLabel(ch),
                        value = f,
                        range = ActionComposer.MIN_CHANNEL_FACTOR..ActionComposer.MAX_CHANNEL_FACTOR,
                        display = "×%.2f".format(f),
                    ) {
                        // 拖回 1.0 就从表里删掉：表越干净，"这条其实没调过"越一眼可见
                        channelFactors = if (kotlin.math.abs(it - 1f) < 0.005f) {
                            channelFactors - ch
                        } else {
                            channelFactors + (ch to it)
                        }
                    }
                }
                if (channelFactors.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "恢复全部",
                        fontSize = 12.5.sp,
                        color = NeuAccent,
                        modifier = Modifier
                            .clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null,
                                onClick = { channelFactors = emptyMap() },
                            )
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) {
                PrimaryPill(
                    text = "预览",
                    filled = false,
                    enabled = build().hasTracks,
                    onClick = { onPreview(build()) },
                )
            }
            Box(Modifier.weight(1f)) {
                PrimaryPill(
                    text = "保存到我的动作",
                    filled = true,
                    enabled = build().hasTracks,
                    onClick = { onSave(build()) },
                )
            }
        }
        if (!build().hasTracks) {
            Spacer(Modifier.height(10.dp))
            Text(
                "这条动作现在什么都不动",
                fontSize = 12.sp,
                color = NeuDanger,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

private const val NONE_KEY = "__none__"

/** 高级模式里一条通道叫什么：`组名 · 通道名`（"头部 · 上下点头"），比裸通道名好认 */
private fun channelLabel(ch: GenericChannel): String = "${ch.group.label} · ${ch.label}"

// =====================================================================
// AI 生成
// =====================================================================

/**
 * AI 动作生成（用户 §七的流程：描述 → 分析 → 结合模型能力 → 生成配置 → 本地预览 → 确认保存）。
 *
 * ⚠️ 两步是**刻意的**：生成完不自动保存、不自动播放。
 * 用户要求里写着"本地预览 → 用户确认 → 保存到我的动作"，
 * 而且未经确认就落库的 AI 内容，用户想反悔还得自己去删 —— 那不像话。
 */
@Composable
fun AiActionSheet(
    generate: suspend (String) -> kotlin.Result<Live2dAction>,
    onPreview: (Live2dAction) -> Unit,
    onSave: (Live2dAction, () -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<Live2dAction?>(null) }

    NeuSheetScaffold(title = "AI 生成动作", onDismiss = onDismiss) {
        if (draft == null) {
            NeuTextField(
                value = text,
                onValueChange = { text = it },
                label = "想要什么动作",
                placeholder = "做一个开心地挥手动作",
                singleLine = false,
                maxLines = 3,
            )
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(AI_EXAMPLES, key = { it }) { example ->
                    PickChip(text = example, selected = false, onClick = { text = example })
                }
            }
            Spacer(Modifier.height(16.dp))
            PrimaryPill(
                text = if (busy) "正在生成…" else "生成",
                filled = true,
                enabled = !busy && text.isNotBlank(),
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        val r = generate(text)
                        busy = false
                        r.onSuccess { draft = it }.onFailure { error = it.message ?: "没生成出来，换句话再试" }
                    }
                },
            )
        } else {
            val d = draft!!
            Text(d.name, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                "会动：${d.tracks.map { ActionPart.of(it.channel) }.distinct().joinToString("+") { it.label }} · " +
                    "%.1f 秒".format(d.safeDurationMs / 1000f),
                fontSize = 12.5.sp,
                color = TextSecondary,
            )
            if (d.note.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("你说的：${d.note}", fontSize = 12.sp, color = MutedText, maxLines = 2)
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) {
                    PrimaryPill(text = "预览", filled = false, enabled = true, onClick = { onPreview(d) })
                }
                Box(Modifier.weight(1f)) {
                    PrimaryPill(
                        text = "保存",
                        filled = true,
                        enabled = true,
                        onClick = { onSave(d, { draft = null }) },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "重新描述",
                fontSize = 12.5.sp,
                color = MutedText,
                modifier = Modifier.clickable { draft = null },
            )
        }

        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, fontSize = 12.5.sp, color = NeuDanger, lineHeight = 18.sp)
        }
        Spacer(Modifier.height(10.dp))
    }
}

private val AI_EXAMPLES = listOf(
    "做一个开心地挥手动作",
    "做一个害羞地低头动作",
    "做一个生气地跺脚动作",
    "做一个夸张的翻跟斗动作",
)

// =====================================================================
// 背景
// =====================================================================

/**
 * 背景选择：v0.65.0 用户点名**只留两档** —— 系统预设 / 自定义背景；
 * v0.74.0 再加**视频背景**一档（用户点名"角色空间背景支持视频"）。
 *
 * 原来那"主题底色 + 4 张内置渐变 + 相册图"六行里，用户真正会用的只有"要不要换成自己的图"
 * 这一件事；四档渐变是给用户添麻烦的（选了第 3 档的人，事后说不出那三档有什么差别）。
 * 老数据（`theme`、`builtin:1..3`）不用迁移：不是 `file:` / `video:` 的一律按系统预设画。
 */
@Composable
fun SpaceBackgroundSheet(
    current: Live2dSpaceStore.SpaceConfig,
    onPick: (Live2dSpaceStore.SpaceConfig) -> Unit,
    onImport: (Uri) -> Unit,
    onImportVideo: (Uri) -> Unit,
    onDismiss: () -> Unit,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onImport(uri)
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onImportVideo(uri)
    }

    NeuSheetScaffold(title = "角色空间背景", onDismiss = onDismiss) {
        Spacer(Modifier.height(6.dp))
        BackgroundRow(
            label = "系统预设",
            selected = !current.isCustom,
            onClick = { onPick(Live2dSpaceStore.SpaceConfig(Live2dSpaceStore.BACKGROUND_PRESET)) },
        )
        Spacer(Modifier.height(8.dp))
        BackgroundRow(
            label = "自定义背景",
            selected = current.fileName != null,
            onClick = { picker.launch("image/*") },
        )
        Spacer(Modifier.height(8.dp))
        BackgroundRow(
            label = "视频背景（≤30 秒）",
            selected = current.isVideo,
            onClick = { videoPicker.launch("video/*") },
        )
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun BackgroundRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .neuSurface(14.dp, color = if (selected) NeuTrack else NeuSurface)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 15.sp, color = TextPrimary, modifier = Modifier.weight(1f))
        if (selected) {
            Text("✓", fontSize = 15.sp, color = NeuAccent, fontWeight = FontWeight.Bold)
        }
    }
}

// =====================================================================
// 点击互动配置（v0.74.0）
// =====================================================================

/**
 * 「点击互动」配置弹层（v0.74.0，ROADMAP 候选①）。
 *
 * 三行 = 三个区域（头部 / 身体 / 其他），点一行 → 展开该区的动作列表挑一条。
 *
 * 两条讲究：
 * 1. **"其他"区没有单独指定**：它按需求永远是"从趣味动作里随机一个"（用户原话），
 *    所以这一行只显示当前行为、不给挑 —— 硬给一个单选反而让"随机"这件事讲不清。
 * 2. **挑动作时列表用动作库那份**（内置 + 我的 + AI，模型自带也算），
 *    行上带"默认"一项：选它 = 解除指定、回到系统默认（点头 → 摇头、点身体 → 摆手）。
 *
 * ⚠️ 不写说明性小字（AGENTS §四）："点一行选一个动作"由行本身交代。
 */
@Composable
fun InteractionConfigSheet(
    bindings: com.menghuan.app.live2d.InteractionBindings,
    actions: List<Live2dAction>,
    onPick: (region: com.menghuan.app.live2d.InteractionRegion, actionId: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    // null = 没展开任何区；点一行展开它的动作列表
    var expanded by remember { mutableStateOf<com.menghuan.app.live2d.InteractionRegion?>(null) }

    val nameOf = remember(actions) { actions.associate { it.id to it.name } }

    fun currentLabel(region: com.menghuan.app.live2d.InteractionRegion): String {
        val bound = bindings.actionIdOf(region)
        return if (bound > 0L) nameOf[bound] ?: "自定义动作" else "默认"
    }

    NeuSheetScaffold(title = "点击互动", onDismiss = onDismiss) {
        Spacer(Modifier.height(6.dp))
        val rows = listOf(
            com.menghuan.app.live2d.InteractionRegion.HEAD to "点头部",
            com.menghuan.app.live2d.InteractionRegion.BODY to "点身体",
            com.menghuan.app.live2d.InteractionRegion.OTHER to "点其他",
        )
        rows.forEach { (region, label) ->
            val selected = expanded == region
            Row(
                Modifier
                    .fillMaxWidth()
                    .neuSurface(14.dp, color = if (selected) NeuTrack else NeuSurface)
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            // "其他"区是随机行为，不给展开（见类注释）
                            if (region != com.menghuan.app.live2d.InteractionRegion.OTHER) {
                                expanded = if (selected) null else region
                            }
                        },
                    )
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, fontSize = 15.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                Text(
                    if (region == com.menghuan.app.live2d.InteractionRegion.OTHER) "随机趣味动作"
                    else currentLabel(region),
                    fontSize = 13.sp,
                    color = MutedText,
                )
                if (region != com.menghuan.app.live2d.InteractionRegion.OTHER) {
                    Spacer(Modifier.width(6.dp))
                    Text(if (selected) "▾" else "▸", fontSize = 13.sp, color = MutedText)
                }
            }
            // 展开区：默认 + 全部动作，挑一条即定
            if (selected) {
                Spacer(Modifier.height(6.dp))
                InteractionActionRow(
                    label = "默认",
                    selected = bindings.actionIdOf(region) <= 0L,
                    onClick = { onPick(region, 0L); expanded = null },
                )
                Spacer(Modifier.height(6.dp))
                actions.forEach { action ->
                    InteractionActionRow(
                        label = action.name,
                        selected = bindings.actionIdOf(region) == action.id,
                        onClick = { onPick(region, action.id); expanded = null },
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun InteractionActionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 14.dp)
            .neuSurface(12.dp, color = if (selected) NeuTrack else NeuSurface)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 14.sp,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Text("✓", fontSize = 14.sp, color = NeuAccent, fontWeight = FontWeight.Bold)
        }
    }
}

// =====================================================================
// 小组件（这一批弹层共用）
// =====================================================================

/** 选择胶囊：选中 = 纯黑药丸白字（与全局 NeuChip 同一套语言） */
@Composable
private fun PickChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .neuSurface(999.dp, color = if (selected) NeuAccent else NeuSurface)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(
            text,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) AccentInk else TextPrimary,
            maxLines = 1,
        )
    }
}

/** 药丸主按钮：filled = 纯黑实心，否则白纸 + 发丝线 */
@Composable
private fun PrimaryPill(text: String, filled: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .neuSurface(999.dp, color = if (filled) NeuAccent else NeuSurface, intensity = if (enabled) 1f else 0.4f)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 14.5.sp,
            fontWeight = FontWeight.Medium,
            color = if (filled) AccentInk else TextPrimary,
        )
    }
}

/**
 * 滑块行。
 *
 * 项目里原本没有滑块（这是第一处用到），所以自己画一个而不是引入 M3 的 `Slider`：
 * 后者的轨道/滑块尺寸与配色是它自己那套，和这里"发丝线 + 纯黑填充 + 圆形白钮"的语言对不上，
 * 硬套会在一屏里出现两种控件质感。
 *
 * 拖动与点按各占一个 pointerInput（同 Live2dSpaceScreen 里那两个手势的道理）：
 * 合在一起的话点一下改不了值。
 */
@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onValueChange: (Float) -> Unit,
) {
    val track = NeuTrack
    val fill = NeuAccent
    val knob = NeuSurface
    val hairline = NeuHairline

    fun valueAt(x: Float, width: Float): Float {
        val frac = (x / width.coerceAtLeast(1f)).coerceIn(0f, 1f)
        return range.start + (range.endInclusive - range.start) * frac
    }

    Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 12.sp, color = TextSecondary, modifier = Modifier.weight(1f))
            Text(display, fontSize = 12.5.sp, color = TextPrimary)
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(26.dp)
                .pointerInput(range) {
                    val w = size.width.toFloat()
                    detectTapGestures { pos -> onValueChange(valueAt(pos.x, w)) }
                }
                .pointerInput(range) {
                    val w = size.width.toFloat()
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        onValueChange(valueAt(change.position.x, w))
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val frac = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
            Canvas(Modifier.fillMaxWidth().height(26.dp)) {
                val h = 6.dp.toPx()
                val cy = size.height / 2f
                val r = CornerRadius(h / 2f)
                // 轨道
                drawRoundRect(
                    color = track,
                    topLeft = Offset(0f, cy - h / 2f),
                    size = Size(size.width, h),
                    cornerRadius = r,
                )
                // 已填部分
                drawRoundRect(
                    color = fill,
                    topLeft = Offset(0f, cy - h / 2f),
                    size = Size((size.width * frac).coerceAtLeast(h), h),
                    cornerRadius = r,
                )
                // 滑块：白圆 + 发丝线（"界面层只有发丝线一种分隔手段"）
                val knobR = 10.dp.toPx()
                val cx = (size.width * frac).coerceIn(knobR, size.width - knobR)
                drawCircle(color = knob, radius = knobR, center = Offset(cx, cy))
                drawCircle(
                    color = hairline,
                    radius = knobR,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
        }
    }
}
