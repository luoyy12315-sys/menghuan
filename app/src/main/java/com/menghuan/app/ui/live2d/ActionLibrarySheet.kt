package com.menghuan.app.ui.live2d

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.live2d.ActionPart
import com.menghuan.app.live2d.ActionSource
import com.menghuan.app.live2d.ActionType
import com.menghuan.app.live2d.Live2dAction
import com.menghuan.app.ui.common.NeuMenu
import com.menghuan.app.ui.common.NeuMenuItem
import com.menghuan.app.ui.common.NeuSheetScaffold
import com.menghuan.app.ui.common.neuSurface
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.NeuSurface
import com.menghuan.app.ui.theme.NeuTrack
import com.menghuan.app.ui.theme.TextPrimary
import com.menghuan.app.ui.theme.TextSecondary

/**
 * 「动作库」弹层（v0.82.0 P2-3 从 `Live2dActionSheets.kt` 拆出来）。
 *
 * 容器统一走 [NeuSheetScaffold]（与「模型选择」同一个从底部升上来的弹层）——
 * 项目里"弹层"只有这一套，不另立第二种。共用的小组件见本包里的 `Live2dSheetWidgets.kt`。
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
