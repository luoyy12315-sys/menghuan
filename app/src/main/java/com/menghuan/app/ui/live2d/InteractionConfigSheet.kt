package com.menghuan.app.ui.live2d

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.live2d.InteractionBindings
import com.menghuan.app.live2d.InteractionRegion
import com.menghuan.app.live2d.Live2dAction
import com.menghuan.app.ui.common.NeuSheetScaffold
import com.menghuan.app.ui.common.neuSurface
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.NeuSurface
import com.menghuan.app.ui.theme.NeuTrack
import com.menghuan.app.ui.theme.TextPrimary

/**
 * 「点击互动」配置弹层（v0.74.0；v0.82.0 P2-3 从 `Live2dActionSheets.kt` 拆出来）。
 *
 * 共用的小组件见本包里的 `Live2dSheetWidgets.kt`。
 */

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
