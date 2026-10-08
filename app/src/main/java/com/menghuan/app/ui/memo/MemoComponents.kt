package com.menghuan.app.ui.memo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.data.model.MemoEntry
import com.menghuan.app.data.model.MemoMath
import com.menghuan.app.data.model.Persona
import com.menghuan.app.ui.common.EaseOutExpo
import com.menghuan.app.ui.common.NeuMenu
import com.menghuan.app.ui.common.NeuMenuItem
import com.menghuan.app.ui.common.NeuSheetRow
import com.menghuan.app.ui.common.NeuSheetScaffold
import com.menghuan.app.ui.common.neuSurface
import com.menghuan.app.ui.common.rememberNeuPressState
import com.menghuan.app.ui.theme.AccentInk
import com.menghuan.app.ui.theme.BrandYellow
import com.menghuan.app.ui.theme.FaintText
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.NeuSurface
import com.menghuan.app.ui.theme.NeuTrack
import com.menghuan.app.ui.theme.TextPrimary
import com.menghuan.app.ui.theme.TextSecondary

/**
 * 备忘本 UI（v0.62.0 建，v0.63.0 收敛，v0.65.0 定形为"入口卡 + 全屏卡片列表"）。
 *
 * 这几件事是定死的，别再改回去：
 * 1. **时间和地点不手填**。时间就是记下的那一刻（[MemoMath.timeLabel]），地点整个删掉——
 *    自动定位要权限 + 第三方逆地理编码 Key，为一个字段不值当（用户已确认删）。
 * 2. **全程不弹窗**。添加是页顶那行内联输入框；点某条备忘 = 那张卡**原地**变成可编辑的文字，
 *    回车落库、✕ 放弃。原来那个底部弹层（正文/时间/地点三栏表单）已删。
 *    唯一还留着的弹层是「发给 AI」的**选人名单**——那不是表单，没有能内联的地方。
 * 3. **数据页只剩一张入口卡**（[MemoEntryCard]）：表面不摊条目、不摊条数、不打字，
 *    点进去才是卡片形式的全部备忘。以前那种"卡里写一条 + 卡里直接打字"的两用形态已撤。
 *
 * 招牌动效在 [MemoCard]：新增从上方轻落 + 撑开高度，删除先向左滑走再把高度收掉。
 * 用的是 `expandVertically` / `shrinkVertically` 这类**真布局变化**，下面的条目每帧
 * 跟着平移。删除因此必须两段式：先让这条播完出场（[MemoListContent] 里 delay 250ms），
 * **动画跑完才真的落库删**。逐条错峰进场走 `animateItem`（与人设管理同一套）。
 */

/**
 * 内联输入框：添加那行和"点某条原地改文字"**共用这一个**。
 *
 * ⚠️ 提交规则是刻意的：空内容、或者**一个字没改**，都算"取消"——
 * 不往库里写垃圾，也不弹「内容不能为空」那种废话提示。
 */
@Composable
private fun MemoInlineEditor(
    initial: String,
    onCommit: (String) -> Unit,
    onCancel: () -> Unit,
    placeholder: String = "记点什么…",
    modifier: Modifier = Modifier,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val savePress = rememberNeuPressState(raised = 2.dp)
    val cancelPress = rememberNeuPressState(raised = 2.dp)
    LaunchedEffect(Unit) { focus.requestFocus() }

    val dirty = text.isNotBlank() && text.trim() != initial.trim()
    val commit = { if (dirty) onCommit(text.trim()) else onCancel() }

    Row(
        modifier
            .fillMaxWidth()
            .testTag("memo_inline_editor")
            .neuSurface(12.dp, color = NeuTrack, inset = true)
            .padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus)
                .padding(vertical = 9.dp),
            textStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, color = TextPrimary),
            singleLine = true,
            cursorBrush = SolidColor(NeuAccent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) {
                        Text(placeholder, fontSize = 14.sp, lineHeight = 20.sp, color = FaintText)
                    }
                    inner()
                }
            },
        )
        Spacer(Modifier.width(6.dp))
        // ✕ 在左、✓ 在右：右手拇指天然落在最右边，和键盘的「完成」同一个位置
        Box(
            Modifier
                .size(28.dp)
                .scale(cancelPress.scale)
                .clickable(
                    interactionSource = cancelPress.interactionSource,
                    indication = null,
                    onClick = onCancel,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(MIcons.Close, contentDescription = "取消", tint = MutedText, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(2.dp))
        Box(
            Modifier
                .size(28.dp)
                .scale(savePress.scale)
                .then(
                    if (dirty) {
                        Modifier.neuSurface(999.dp, color = NeuAccent, elevation = 2.dp)
                    } else {
                        Modifier.neuSurface(999.dp, color = NeuTrack, inset = true)
                    },
                )
                .clickable(
                    interactionSource = savePress.interactionSource,
                    indication = null,
                    enabled = dirty,
                    onClick = { commit() },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                MIcons.Check,
                contentDescription = "保存",
                tint = if (dirty) AccentInk else FaintText,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** 添加用的输入行：平时是一行凹陷灰面占位，点下去才换成真输入框（卡片平时该是安静的） */
@Composable
fun MemoComposer(
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf(false) }
    if (editing) {
        MemoInlineEditor(
            initial = "",
            onCommit = { value ->
                onSave(value)
                editing = false
            },
            onCancel = { editing = false },
            modifier = modifier,
        )
    } else {
        val idlePress = rememberNeuPressState(raised = 2.dp)
        Row(
            modifier
                .fillMaxWidth()
                .testTag("memo_composer")
                .scale(idlePress.scale)
                .neuSurface(12.dp, color = NeuTrack, inset = true)
                .clickable(
                    interactionSource = idlePress.interactionSource,
                    indication = null,
                    onClick = { editing = true },
                )
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(MIcons.Plus, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(9.dp))
            Text("记点什么…", fontSize = 13.5.sp, color = MutedText)
        }
    }
}

/**
 * 一条备忘 = **一张卡片**。**点一下原地改文字**，长按弹菜单。
 *
 * 左端那 10dp **恒定占位**（不置顶也留位），否则置顶切换时正文会横跳一下。
 * 长按只弹菜单——删除要在菜单里点那一下才真删（全应用规矩）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MemoCard(
    memo: MemoEntry,
    beingDeleted: Boolean,
    editing: Boolean,
    modifier: Modifier = Modifier,
    onStartEdit: () -> Unit = {},
    onCommitEdit: (String) -> Unit = {},
    onCancelEdit: () -> Unit = {},
    onLongClick: () -> Unit = {},
) {
    val press = rememberNeuPressState(raised = 4.dp)
    AnimatedVisibility(
        visible = !beingDeleted,
        modifier = modifier,
        enter = expandVertically(tween(300, easing = EaseOutExpo)) +
            slideInVertically(tween(300, easing = EaseOutExpo)) { -it / 2 } +
            fadeIn(tween(200)),
        exit = slideOutHorizontally(tween(220, easing = EaseOutExpo)) { -it } +
            fadeOut(tween(150)) +
            shrinkVertically(tween(240, easing = EaseOutExpo)),
    ) {
        if (editing) {
            MemoInlineEditor(
                initial = memo.content,
                placeholder = "改点什么…",
                onCommit = onCommitEdit,
                onCancel = onCancelEdit,
            )
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .scale(press.scale)
                    .neuSurface(16.dp, color = NeuSurface)
                    .combinedClickable(
                        interactionSource = press.interactionSource,
                        indication = null,
                        onClick = onStartEdit,
                        onLongClick = onLongClick,
                    )
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
            ) {
                Box(Modifier.width(10.dp).padding(top = 7.dp)) {
                    if (memo.pinned) {
                        Box(Modifier.size(6.dp).background(BrandYellow, CircleShape))
                    }
                }
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        memo.content,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 21.sp,
                        letterSpacing = (-0.2).sp,
                        color = TextPrimary,
                        maxLines = 3,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            MIcons.Clock,
                            contentDescription = null,
                            tint = MutedText,
                            modifier = Modifier.size(11.5.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        // 时间自动 = 记下的那一刻（v0.63.0 用户拍板，没有手填这回事）
                        Text(MemoMath.timeLabel(memo), fontSize = 11.5.sp, color = MutedText)
                    }
                }
            }
        }
    }
}

/**
 * 备忘卡片列表（全屏页用；v0.65.0 起数据页不再摊条目）。
 *
 * 行菜单、两段式删除、"同一时刻只编辑一条"都在这里。
 *
 * **逐条错峰淡入**与人设管理同一套做法（`animateItem(fadeInSpec = tween(340, delay = index*50))`）：
 * 序号越大越晚出场，最多延后 300ms —— 条目几十上百条时不至于让最后一条等太久。
 * 删除仍走 [MemoCard] 自己的 `shrinkVertically`（先滑走再收高度，动画跑完才落库删），
 * 所以到 `animateItem` 退场那一刻，这一项的高度已经是 0，不会两套动画打架。
 */
@Composable
fun MemoListContent(
    memos: List<MemoEntry>,
    personas: List<Persona>,
    onSaveEdit: (id: Long, content: String, pinned: Boolean) -> Unit,
    onTogglePin: (Long, Boolean) -> Unit,
    onSendToAi: (personaId: Long, memo: MemoEntry) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuFor by remember { mutableStateOf<MemoEntry?>(null) }
    var deleting by remember { mutableStateOf<MemoEntry?>(null) }
    var sending by remember { mutableStateOf<MemoEntry?>(null) }
    var editingId by remember { mutableStateOf<Long?>(null) }
    val haptic = LocalHapticFeedback.current

    // 两段式删除：这条先滑走并收高，动画跑完才真的落库删
    LaunchedEffect(deleting?.id) {
        val target = deleting ?: return@LaunchedEffect
        kotlinx.coroutines.delay(250)
        onDelete(target.id)
        deleting = null
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(memos, key = { _, m -> m.id }, contentType = { _, _ -> "memo" }) { index, memo ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .animateItem(fadeInSpec = tween(340, delayMillis = (index * 50).coerceAtMost(300))),
            ) {
                MemoCard(
                    memo = memo,
                    beingDeleted = deleting?.id == memo.id,
                    editing = editingId == memo.id,
                    modifier = Modifier.fillMaxWidth(),
                    onStartEdit = { editingId = memo.id },
                    onCommitEdit = { value ->
                        onSaveEdit(memo.id, value, memo.pinned)
                        editingId = null
                    },
                    onCancelEdit = { editingId = null },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        editingId = null
                        menuFor = memo
                    },
                )
                // 菜单锚点：Popup 锚定它的直接父节点，所以包在贴卡右下角的 1dp Box 里
                Box(Modifier.align(Alignment.BottomEnd).size(1.dp)) {
                    NeuMenu(
                        expanded = menuFor?.id == memo.id,
                        onDismissRequest = { menuFor = null },
                    ) {
                        NeuMenuItem(
                            if (memo.pinned) "取消置顶" else "置顶",
                            onClick = {
                                onTogglePin(memo.id, !memo.pinned)
                                menuFor = null
                            },
                        )
                        NeuMenuItem(
                            "发给 AI",
                            onClick = {
                                sending = memo
                                menuFor = null
                            },
                        )
                        NeuMenuItem(
                            "删除",
                            danger = true,
                            onClick = {
                                deleting = memo
                                menuFor = null
                            },
                        )
                    }
                }
            }
        }
    }

    sending?.let { memo ->
        MemoSendPersonaSheet(
            personas = personas,
            onDismiss = { sending = null },
            onPick = { personaId ->
                sending = null
                onSendToAi(personaId, memo)
            },
        )
    }
}

/**
 * 数据页的备忘本入口卡（v0.65.0 用户点名：与记账本那张**一样大小**、表面不显示任何内容）。
 *
 * 尺寸与数据页那张「记账本」入口卡逐项对齐：16dp 圆角白面、16dp 内边距、
 * 38dp 凹陷圆图标 + 19dp 图标 + 15sp 卡名 + 18dp 箭头。
 * 条目、条数、输入行都收进全屏页——卡片表面只做入口（原来摊一条 + 卡内打字那一版撤掉了）。
 */
@Composable
fun MemoEntryCard(onOpenAll: () -> Unit) {
    val press = rememberNeuPressState(raised = 4.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .testTag("memo_entry_card")
            .scale(press.scale)
            .neuSurface(16.dp, color = NeuSurface)
            .clickable(interactionSource = press.interactionSource, indication = null, onClick = onOpenAll)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).neuSurface(999.dp, color = NeuTrack, inset = true),
            contentAlignment = Alignment.Center,
        ) {
            Icon(MIcons.FileText, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(13.dp))
        Text("备忘本", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
        Spacer(Modifier.weight(1f))
        Icon(MIcons.ChevronRight, contentDescription = null, tint = MutedText, modifier = Modifier.size(18.dp))
    }
}

/**
 * 发给 AI 前先选给谁（人设名单）。
 *
 * 这是备忘这条线上**唯一还留着的底部弹层**：它是一张名单，不是表单，没有能内联的地方。
 * 选完由调用方把这条写进那个会话的输入框草稿并跳过去（见 `DataViewModel.sendMemoToPersona`）。
 */
@Composable
private fun MemoSendPersonaSheet(
    personas: List<Persona>,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
) {
    NeuSheetScaffold(title = "发给谁", onDismiss = onDismiss) {
        personas.forEach { persona ->
            NeuSheetRow(
                icon = MIcons.ChatBubble,
                title = persona.name,
                subtitle = persona.title,
                onClick = { onPick(persona.id) },
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}
