package com.menghuan.app.ui.data

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.data.model.AiInsight
import com.menghuan.app.data.model.InsightPeriod
import com.menghuan.app.ui.common.AdaptiveScreen
import com.menghuan.app.ui.common.NeuCard
import com.menghuan.app.ui.common.NeuCircleButton
import com.menghuan.app.ui.common.NeuSectionTitle
import com.menghuan.app.ui.common.NeuSkeleton
import com.menghuan.app.ui.common.NeuTopBar
import com.menghuan.app.ui.common.neuSurface
import com.menghuan.app.ui.common.EaseOutExpo
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.NeuHairline
import com.menghuan.app.ui.theme.NeuSurface
import com.menghuan.app.ui.theme.NeuTrack
import com.menghuan.app.ui.theme.TextPrimary
import com.menghuan.app.ui.theme.TextSecondary

/**
 * AI 洞察的全屏详情页。
 *
 * v0.42.0 起是「洞察 + 周报」合并页；v0.87.0 删掉 AI 周报后**只剩洞察**。
 * v0.88.0 起按**周期分组**（今日 / 本周 / 本月）—— 洞察现在有日/周/月三条链，
 * 混排在一起用户分不清哪条是哪期的。
 *
 * 三态：加载中（还没查完）/ 空（查过了确实没有）/ 有数据。空态的措辞也改了：
 * 以前是"多聊聊，AI 会从对话里看出你的模式"（旧链路），现在是"到点会自动归纳"
 * ——不写清楚用户会以为洞察还要靠聊天触发。
 */
@Composable
fun AiDetailScreen(
    insights: List<AiInsight>,
    loaded: Boolean,
    onBack: () -> Unit,
    onDeleteInsight: (Long) -> Unit,
) {
    AdaptiveScreen {
        NeuTopBar(
            title = "AI 洞察",
            navigationIcon = {
                NeuCircleButton(onClick = onBack, size = 38.dp) {
                    Icon(MIcons.Back, contentDescription = "返回", tint = TextPrimary, modifier = Modifier.size(19.dp))
                }
            },
            actionsWidth = 56.dp,
        )

        // 按周期分组，顺序固定：今日 → 本周 → 本月（用户点名过数据页顺序固定，这里同理）
        val grouped = insights.groupBy { it.period }
        val sections = InsightPeriod.ORDER.mapNotNull { period ->
            grouped[period]?.takeIf { it.isNotEmpty() }?.let { period to it }
        }

        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                // ① 还没查完：v0.97.0（审查 U8）原来这里是 `Unit` —— 整页只剩顶栏，
                // 空白主体。空白和"没有洞察"在视觉上没法区分，用户会当成加载失败。
                // 给骨架占位（形状与下面的卡片一致），查完再换成真内容或空态。
                !loaded -> {
                    item {
                        NeuSkeleton(count = 5, rowHeight = 88.dp, horizontalPadding = 0.dp)
                    }
                }
                // ② 查过、确实没有
                insights.isEmpty() -> {
                    item {
                        NeuSectionTitle("AI 洞察", Modifier.padding(top = 6.dp, bottom = 2.dp))
                    }
                    item {
                        EmptyBlock(
                            "还没有洞察",
                            "每晚 23:00 会自动整理记忆、任务与记账，有值得说的才写下来",
                        )
                    }
                }
                // ③ 有数据：按周期分段
                else -> {
                    item {
                        NeuSectionTitle("AI 洞察 · ${insights.size} 条", Modifier.padding(top = 6.dp, bottom = 2.dp))
                    }
                    sections.forEach { (period, list) ->
                        item(key = "header-$period") {
                            Text(
                                InsightPeriod.label(period),
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = MutedText,
                                modifier = Modifier.padding(top = 6.dp, bottom = 0.dp),
                            )
                        }
                        items(list, key = { "insight-${it.id}" }, contentType = { "insight" }) { insight ->
                            InsightDetailCard(insight, onDelete = { onDeleteInsight(insight.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyBlock(title: String, subtitle: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .neuSurface(16.dp, color = NeuTrack, inset = true)
            .padding(vertical = 26.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 13.5.sp, color = TextSecondary)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, fontSize = 12.sp, color = MutedText)
        }
    }
}

/**
 * 洞察详情卡：标题 + 完整说明 + 日期。
 * 长按弹菜单、菜单里点删除才真删（项目统一约定，不再套 AlertDialog）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InsightDetailCard(insight: AiInsight, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { revealed = true }
    val appear by animateFloatAsState(if (revealed) 1f else 0f, tween(340, easing = EaseOutExpo), label = "insightAppear")

    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.alpha(appear)) {
            NeuCard(elevation = 2.dp, contentPadding = 16.dp, modifier = Modifier.fillMaxWidth()) {
                Text(
                    insight.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-0.14).sp,
                    color = TextPrimary,
                )
                Spacer(Modifier.height(7.dp))
                Text(insight.detail, fontSize = 13.sp, lineHeight = 20.sp, color = TextSecondary)
                Spacer(Modifier.height(10.dp))
                Text(AiInsightDateLabel(insight.createdAt), fontSize = 11.sp, color = MutedText)
            }
        }
        // 长按热区：铺满卡片（点击不拦截，避免影响内部文本选择/滚动）
        Box(
            Modifier
                .matchParentSize()
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    },
                ),
        )
        // 菜单锚点：条目右下角小 Box（Popup 锚定直接父节点，直接挂 align/offset 无效）
        Box(Modifier.align(Alignment.BottomEnd).size(1.dp)) {
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                modifier = Modifier.border(1.dp, NeuHairline, RoundedCornerShape(14.dp)),
                containerColor = NeuSurface,
                shape = RoundedCornerShape(14.dp),
                shadowElevation = 0.dp, // v0.42.1 全应用去阴影
            ) {
                DropdownMenuItem(
                    text = { Text("删除这条洞察", fontSize = 14.sp, color = NeuDanger) },
                    onClick = {
                        onDelete()
                        menuOpen = false
                    },
                )
            }
        }
    }
}
