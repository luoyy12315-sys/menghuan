package com.menghuan.app.ui.data

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.data.model.AiInsight
import com.menghuan.app.data.model.AiWeeklyReport
import com.menghuan.app.ui.common.AdaptiveScreen
import com.menghuan.app.ui.common.MarkdownText
import com.menghuan.app.ui.common.NeuCard
import com.menghuan.app.ui.common.NeuCircleButton
import com.menghuan.app.ui.common.NeuSectionTitle
import com.menghuan.app.ui.common.NeuTopBar
import com.menghuan.app.ui.common.neuSurface
import com.menghuan.app.ui.common.rememberNeuPressState
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
 * AI 洞察 + AI 周报 的全屏详情页（v0.42.0）。
 *
 * 合并动机：数据页原本并列两大板块（洞察列表 + 周报全文），把陪伴类 App 的首页
 * 撑成了"报表页"。现在数据页只留一张入口卡，详细内容进这个全屏页按需查看。
 *
 * 结构：全部洞察（新→旧，长按删）→ 周报历史（新→旧，点击展开全文、长按分享/删）。
 */
@Composable
fun AiDetailScreen(
    insights: List<AiInsight>,
    reports: List<AiWeeklyReport>,
    onBack: () -> Unit,
    onDeleteInsight: (Long) -> Unit,
    onDeleteReport: (Long) -> Unit,
    onShareReport: (AiWeeklyReport) -> Unit,
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

        // v0.42.1：周报默认**全部折叠**（用户要求"ai周报可以折叠且默认折叠"）。
        // 之前是进页面自动展开最新一期，十几期全文铺开会淹没上面的洞察区。
        var expandedReportId by remember { mutableStateOf<Long?>(null) }

        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                NeuSectionTitle("AI 洞察 · ${insights.size} 条", Modifier.padding(top = 6.dp, bottom = 2.dp))
            }
            if (insights.isEmpty()) {
                item { EmptyBlock("还没有洞察", "多聊聊，AI 会从对话里看出你的模式") }
            } else {
                items(insights, key = { "insight-${it.id}" }) { insight ->
                    InsightDetailCard(insight, onDelete = { onDeleteInsight(insight.id) })
                }
            }

            item {
                NeuSectionTitle("AI 周报 · ${reports.size} 期", Modifier.padding(top = 14.dp, bottom = 2.dp))
            }
            if (reports.isEmpty()) {
                item { EmptyBlock("还没有周报", "每周日 21:30 自动生成一期") }
            } else {
                items(reports, key = { "report-${it.id}" }) { report ->
                    ReportDetailCard(
                        report = report,
                        expanded = expandedReportId == report.id,
                        onToggle = { expandedReportId = if (expandedReportId == report.id) null else report.id },
                        onDelete = { onDeleteReport(report.id) },
                        onShare = { onShareReport(report) },
                    )
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

/**
 * 周报详情卡：期标签 + 生成日期 + 可折叠全文。
 * 折叠是对"历史多期"的必要让步——全屏页上十几期全文摆开会淹没洞察区；
 * 周期卡本身仍然是「展开即全文直出」，不搞预览截断。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReportDetailCard(
    report: AiWeeklyReport,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val press = rememberNeuPressState(raised = 3.dp)

    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .scale(press.scale)
                .neuSurface(16.dp, color = NeuSurface, elevation = if (expanded) 3.dp else 1.dp)
                .padding(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(weekLabelOf(report), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                    Spacer(Modifier.height(3.dp))
                    Text("生成于 ${dateLabelOf(report.createdAt)}", fontSize = 11.sp, color = MutedText)
                }
                Icon(
                    if (expanded) MIcons.ChevronUp else MIcons.ChevronDown,
                    contentDescription = null,
                    tint = MutedText,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (expanded) {
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(NeuHairline))
                Spacer(Modifier.height(12.dp))
                MarkdownText(report.content, color = TextSecondary)
            }
        }
        // 交互热区：铺满卡片，**点 = 折叠/展开，长按 = 弹菜单**。
        // v0.42.1 修：以前这里是 `onClick = {}`（只接管长按），而真正的 clickable 在外层
        // Column 上——被这层盖住压根收不到点击，于是"点周报打不开"。现在点击统一在此处理。
        Box(
            Modifier
                .matchParentSize()
                .combinedClickable(
                    interactionSource = press.interactionSource,
                    indication = null,
                    onClick = onToggle,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    },
                ),
        )
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
                    text = { Text("分享", fontSize = 14.sp) },
                    onClick = {
                        onShare()
                        menuOpen = false
                    },
                )
                DropdownMenuItem(
                    text = { Text("删除这期", fontSize = 14.sp, color = NeuDanger) },
                    onClick = {
                        onDelete()
                        menuOpen = false
                    },
                )
            }
        }
    }
}
